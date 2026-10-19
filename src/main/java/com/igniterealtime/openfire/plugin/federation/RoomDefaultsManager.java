package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Default federation settings for new MUC rooms, chosen by a glob on the room name ({@code *} and
 * {@code ?}). When several rules match, the one with the most literal characters wins. Rules are
 * persisted in Openfire properties.
 */
public class RoomDefaultsManager {

    private static final Logger Log = LoggerFactory.getLogger(RoomDefaultsManager.class);

    private static final String PROP_COUNT      = "federation.roomdefaults.count";
    private static final String PROP_PATTERN    = "federation.roomdefaults.%d.pattern";
    private static final String PROP_FEDERATED  = "federation.roomdefaults.%d.federated";
    private static final String PROP_AUTOACCEPT = "federation.roomdefaults.%d.autoaccept";
    private static final String PROP_VISIBLE    = "federation.roomdefaults.%d.visible";
    private static final String PROP_AUTOMAP    = "federation.roomdefaults.%d.automap";
    private static final String PROP_FILES      = "federation.roomdefaults.%d.files";

    /** The visibility entry meaning "every peer". */
    public static final String VISIBLE_ALL = FederatedRoomManager.VISIBLE_ALL;

    /** One rule, keyed by its pattern; {@code visible} holds server domains or the single entry {@code "*"}. */
    public record Rule(String pattern, boolean federated, boolean autoAccept,
                       List<String> visible, boolean autoMap, boolean filesEnabled) {
        public Rule {
            visible = (visible == null) ? List.of() : List.copyOf(visible);
        }

        /** The number of literal characters, which ranks overlapping rules. */
        int specificity() {
            int n = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '*' && c != '?') n++;
            }
            return n;
        }
    }

    /** Rules in insertion order. */
    private final List<Rule> rules = Collections.synchronizedList(new ArrayList<>());

    /** Loads the persisted rules. */
    public void load() {
        rules.clear();
        int count = JiveGlobals.getIntProperty(PROP_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String pattern = JiveGlobals.getProperty(String.format(PROP_PATTERN, i));
            if (pattern == null || pattern.isBlank()) continue;
            boolean federated  = JiveGlobals.getBooleanProperty(String.format(PROP_FEDERATED, i), false);
            boolean autoAccept = JiveGlobals.getBooleanProperty(String.format(PROP_AUTOACCEPT, i), false);
            boolean autoMap    = JiveGlobals.getBooleanProperty(String.format(PROP_AUTOMAP, i), false);
            boolean files      = JiveGlobals.getBooleanProperty(String.format(PROP_FILES, i), true);
            List<String> visible = parseCsv(JiveGlobals.getProperty(String.format(PROP_VISIBLE, i)));
            rules.add(new Rule(pattern.strip(), federated, autoAccept, visible, autoMap, files));
        }
        Log.info("Loaded {} room-default rule(s)", rules.size());
    }

    /** The rules, most specific first. */
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

    /** Adds a rule or replaces the one with the same pattern, and persists the rules. */
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

    /** Removes the rule with this pattern, ignoring case, and persists the rules. */
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

    /** The most specific rule matching {@code roomName}, the room JID's local part, or null. */
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

    private void persist() {
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

    /** Lowercases a visibility list and reduces it to {@code "*"} alone when it contains it. */
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

    /** Compiled globs, since every rule is matched on each room creation; bounded by the small rule set. */
    private static final ConcurrentHashMap<String, Pattern> GLOB_CACHE =
        new ConcurrentHashMap<>();

    /** Case-insensitive glob match, where {@code *} is any run of characters and {@code ?} one character. */
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
