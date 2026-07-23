package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Default federation settings applied to a MUC room the moment it is created,
 * selected by a glob match on the room's name (the localpart of the room JID).
 *
 * <p>The admin defines an ordered set of <em>rules</em>; each rule is a name
 * pattern ({@code *} = any run of characters, {@code ?} = one character — Linux
 * glob style) plus the settings to apply: whether the room is federated, its
 * visibility ACL, whether incoming mapping requests auto-accept, and whether to
 * auto-request a same-named mapping on each visible peer.</p>
 *
 * <p>When several patterns match a name, the <strong>most specific</strong> wins:
 * the pattern with the most literal (non-wildcard) characters. {@code *} therefore
 * acts as the catch-all fallback. Ties break toward the longer pattern, then the
 * earlier-defined rule.</p>
 *
 * <p>Persistence: a count plus per-index properties in JiveGlobals, rewritten
 * wholesale on every change (the rule set is small).</p>
 */
public class RoomDefaultsManager {

    private static final Logger Log = LoggerFactory.getLogger(RoomDefaultsManager.class);

    private static final String PROP_COUNT      = "federation.roomdefaults.count";
    private static final String PROP_PATTERN    = "federation.roomdefaults.%d.pattern";
    private static final String PROP_FEDERATED  = "federation.roomdefaults.%d.federated";
    private static final String PROP_AUTOACCEPT = "federation.roomdefaults.%d.autoaccept";
    private static final String PROP_VISIBLE    = "federation.roomdefaults.%d.visible";   // csv of domains, or "*"
    private static final String PROP_AUTOMAP    = "federation.roomdefaults.%d.automap";
    private static final String PROP_FILES      = "federation.roomdefaults.%d.files";     // per-rule file federation (default true)

    /** The wildcard token that means "visible to every peer" (mirrors {@link FederatedRoomManager#VISIBLE_ALL}). */
    public static final String VISIBLE_ALL = FederatedRoomManager.VISIBLE_ALL;

    /**
     * One rule. {@code pattern} doubles as the unique id — there is at most one
     * rule per pattern. {@code visible} is a list of server domains, or the single
     * element {@code "*"} meaning all peers; it is only meaningful when federated.
     */
    public record Rule(String pattern, boolean federated, boolean autoAccept,
                       List<String> visible, boolean autoMap, boolean filesEnabled) {
        public Rule {
            visible = (visible == null) ? List.of() : List.copyOf(visible);
        }

        /** Literal (non-wildcard) character count — the specificity score. */
        int specificity() {
            int n = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '*' && c != '?') n++;
            }
            return n;
        }
    }

    /** Ordered by insertion; pattern is the de-dup key. */
    private final List<Rule> rules = Collections.synchronizedList(new ArrayList<>());

    public void load() {
        rules.clear();
        int count = JiveGlobals.getIntProperty(PROP_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String pattern = JiveGlobals.getProperty(String.format(PROP_PATTERN, i));
            if (pattern == null || pattern.isBlank()) continue;
            boolean federated  = JiveGlobals.getBooleanProperty(String.format(PROP_FEDERATED, i), false);
            boolean autoAccept = JiveGlobals.getBooleanProperty(String.format(PROP_AUTOACCEPT, i), false);
            boolean autoMap    = JiveGlobals.getBooleanProperty(String.format(PROP_AUTOMAP, i), false);
            boolean files      = JiveGlobals.getBooleanProperty(String.format(PROP_FILES, i), true);   // default on
            List<String> visible = parseCsv(JiveGlobals.getProperty(String.format(PROP_VISIBLE, i)));
            rules.add(new Rule(pattern.strip(), federated, autoAccept, visible, autoMap, files));
        }
        Log.info("Loaded {} room-default rule(s)", rules.size());
    }

    /** Snapshot of the current rules, most-specific first (matching evaluation order). */
    public List<Rule> getRules() {
        synchronized (rules) {
            List<Rule> copy = new ArrayList<>(rules);
            copy.sort((a, b) -> {
                int s = Integer.compare(b.specificity(), a.specificity());
                if (s != 0) return s;
                return Integer.compare(b.pattern().length(), a.pattern().length());
            });
            return copy;
        }
    }

    /** Adds or replaces (by pattern) a rule, then persists. */
    public void save(String pattern, boolean federated, boolean autoAccept,
                     List<String> visible, boolean autoMap, boolean filesEnabled) {
        if (pattern == null || pattern.isBlank()) return;
        String p = pattern.strip();
        Rule rule = new Rule(p, federated, autoAccept, normalizeVisible(visible), autoMap, filesEnabled);
        synchronized (rules) {
            rules.removeIf(r -> r.pattern().equalsIgnoreCase(p));
            rules.add(rule);
            persist();
        }
        Log.info("Room-default rule saved: {} → federated={} autoAccept={} visible={} autoMap={} files={}",
                 p, federated, autoAccept, rule.visible(), autoMap, filesEnabled);
    }

    /** Removes the rule with this exact pattern (case-insensitive), then persists. */
    public void delete(String pattern) {
        if (pattern == null) return;
        String p = pattern.strip();
        synchronized (rules) {
            if (rules.removeIf(r -> r.pattern().equalsIgnoreCase(p))) {
                persist();
                Log.info("Room-default rule deleted: {}", p);
            }
        }
    }

    /**
     * The most-specific rule whose pattern matches {@code roomName} (the room JID
     * localpart), or {@code null} when no rule matches.
     */
    public Rule bestMatch(String roomName) {
        if (roomName == null) return null;
        Rule best = null;
        synchronized (rules) {
            for (Rule r : rules) {
                if (!globMatches(r.pattern(), roomName)) continue;
                if (best == null
                        || r.specificity() > best.specificity()
                        || (r.specificity() == best.specificity()
                            && r.pattern().length() > best.pattern().length())) {
                    best = r;
                }
            }
        }
        return best;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void persist() {
        // Clear any stale tail from a previously larger set, then write the new set.
        int old = JiveGlobals.getIntProperty(PROP_COUNT, 0);
        for (int i = 0; i < old; i++) {
            JiveGlobals.deleteProperty(String.format(PROP_PATTERN, i));
            JiveGlobals.deleteProperty(String.format(PROP_FEDERATED, i));
            JiveGlobals.deleteProperty(String.format(PROP_AUTOACCEPT, i));
            JiveGlobals.deleteProperty(String.format(PROP_VISIBLE, i));
            JiveGlobals.deleteProperty(String.format(PROP_AUTOMAP, i));
            JiveGlobals.deleteProperty(String.format(PROP_FILES, i));
        }
        int i = 0;
        for (Rule r : rules) {
            JiveGlobals.setProperty(String.format(PROP_PATTERN, i), r.pattern());
            JiveGlobals.setProperty(String.format(PROP_FEDERATED, i), String.valueOf(r.federated()));
            JiveGlobals.setProperty(String.format(PROP_AUTOACCEPT, i), String.valueOf(r.autoAccept()));
            JiveGlobals.setProperty(String.format(PROP_AUTOMAP, i), String.valueOf(r.autoMap()));
            JiveGlobals.setProperty(String.format(PROP_FILES, i), String.valueOf(r.filesEnabled()));
            JiveGlobals.setProperty(String.format(PROP_VISIBLE, i), String.join(",", r.visible()));
            i++;
        }
        JiveGlobals.setProperty(PROP_COUNT, String.valueOf(rules.size()));
    }

    /** Normalizes a visibility list: lower-cases domains, collapses any "*" to the single sentinel. */
    private static List<String> normalizeVisible(List<String> visible) {
        if (visible == null) return List.of();
        Set<String> set = new LinkedHashSet<>();
        for (String s : visible) {
            if (s == null) continue;
            String v = s.strip().toLowerCase();
            if (v.isEmpty()) continue;
            if (v.equals(VISIBLE_ALL)) return List.of(VISIBLE_ALL);
            set.add(v);
        }
        return new ArrayList<>(set);
    }

    private static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split(",")) {
                if (!s.isBlank()) out.add(s.strip().toLowerCase());
            }
        }
        return out;
    }

    /**
     * Compiled-glob cache.  bestMatch runs every rule's glob on every room creation (and
     * applyDefaultsToAllRooms runs it across every existing room), so recompiling the regex per
     * call was pure waste.  Keyed by the glob string; bounded in practice by the small
     * admin-defined rule set (deleted rules leave a stale entry, which is negligible).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> GLOB_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Case-insensitive Linux-style glob match ({@code *} = any run, {@code ?} = one char). */
    static boolean globMatches(String glob, String name) {
        if (glob == null || name == null) return false;
        return GLOB_CACHE.computeIfAbsent(glob, RoomDefaultsManager::compileGlob)
                         .matcher(name).matches();
    }

    private static Pattern compileGlob(String glob) {
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default  -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        re.append("$");
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }
}
