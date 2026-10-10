package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNotFoundException;
import org.jivesoftware.openfire.group.SharedGroupVisibility;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNameManager;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Cross-server contact-list sharing, modelled on Openfire's own Contact List (Roster) Sharing.
 *
 * <p>Two halves:
 * <ul>
 *   <li><b>Advertising</b> — the admin shares local groups with specific peer servers
 *       ({@link ShareRule}); a group is shared by membership, never as single users. Each target
 *       server is sent its own {@code contact-list}: the members of every group shared with it,
 *       routed hop-by-hop to that server only (never flooded). Sharing a user with a server (through
 *       one of their groups) is also what authorizes that server's users to see the user's
 *       presence and PEP data (avatar, nickname): probes and subscription requests from an
 *       authorized server are answered by the plugin, without prompting the user.</li>
 *   <li><b>Receiving</b> — each server that shares contacts with us shows up as one received list.
 *       Once the admin maps it to local groups ({@link ListMapping}), the plugin keeps a real Openfire
 *       shared group in step with it: the remote contacts are its members and it is shown in the
 *       rosters of the chosen local groups. Openfire's own roster engine then puts the contacts in
 *       each user's contact list, with a {@code to} subscription.</li>
 * </ul>
 *
 * <p>Presence is one-way: sharing alice with server B lets B's mapped users see alice; alice sees
 * B's users only if B shares them back. A contact list crosses an untrusted link only when the
 * admin on each side allowed contact lists on that link and its exposed-server settings permit it.
 *
 * <p>Persistence: share rules and mappings in JiveGlobals (rewritten wholesale — both sets are
 * small). Received lists are in memory: the advertising server re-sends whenever the route comes
 * back and on request, and the mapped Openfire group keeps its members across a restart.
 */
public class ContactListManager {

    private static final Logger Log = LoggerFactory.getLogger(ContactListManager.class);

    private static final String PROP_SHARE_COUNT   = "federation.contactshare.count";
    /** Retired with single-user sharing (1.10.15); a rule carrying kind=user is dropped on load. */
    private static final String PROP_SHARE_KIND    = "federation.contactshare.%d.kind";
    private static final String PROP_SHARE_NAME    = "federation.contactshare.%d.name";
    private static final String PROP_SHARE_TARGETS = "federation.contactshare.%d.targets";   // csv of domains

    private static final String PROP_MAP_COUNT   = "federation.contactmap.count";
    private static final String PROP_MAP_ORIGIN  = "federation.contactmap.%d.origin";
    private static final String PROP_MAP_DISPLAY = "federation.contactmap.%d.display";
    private static final String PROP_MAP_GROUPS  = "federation.contactmap.%d.groups";       // csv of groups, or "*"

    /** Prefix of the Openfire group the plugin keeps for one origin's list. */
    public static final String GROUP_PREFIX = "federation-contacts-";
    /** Group property marking a group as plugin-owned, holding the origin it mirrors. */
    public static final String GROUP_ORIGIN_PROP = "federation.contactList.origin";

    /** Mapping token meaning "show this list to every local user". */
    public static final String EVERYBODY = "*";


    /** Most contacts accepted in one list; anything past this is dropped (and logged). */
    public static final int MAX_CONTACTS = 5000;
    /** Longest display name accepted for a contact. */
    private static final int MAX_NAME = 128;

    /** Re-send every list this often even when nothing changed — covers a lost IQ. */
    private static final long RESEND_EVERY_MS = 10 * 60_000L;

    /** One shared contact: a bare JID plus the display name its server gave it (may be empty). */
    public record Contact(String jid, String name) {}

    /** One local group shared with a set of peer servers. {@code group} is the key. */
    public record ShareRule(String group, List<String> targets) {
        public ShareRule {
            targets = (targets == null) ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * How one received list is placed into local rosters. {@code groups} holds local group names, or
     * the single element {@link #EVERYBODY}.
     */
    public record ListMapping(String origin, String displayName, List<String> groups) {
        public ListMapping {
            groups = (groups == null) ? List.of() : List.copyOf(groups);
        }
        public boolean everybody() { return groups.contains(EVERYBODY); }
    }

    /** A list some server shared with us, as last received. */
    public record ReceivedList(List<Contact> contacts, long receivedAt) {}

    private final FederationManager manager;

    private final List<ShareRule> rules = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, ListMapping> mappings = new ConcurrentHashMap<>();
    private final Map<String, ReceivedList> received = new ConcurrentHashMap<>();

    /** target domain → the list last sent to it (sorted by JID). */
    private final Map<String, List<Contact>> sent = new ConcurrentHashMap<>();
    /** target domain → bare JIDs of the local users currently shared with it. */
    private volatile Map<String, Set<String>> authorized = Map.of();
    /** local bare JID → remote bare JIDs that probed or subscribed to it through a shared list. */
    private final Map<String, Set<String>> subscribers = new ConcurrentHashMap<>();

    private Set<String> targetsUpLastTick = Set.of();
    private Set<String> originsUpLastTick = Set.of();
    private long lastFullResend = System.currentTimeMillis();

    /** Origins we registered a {@link UserNameManager} provider for. */
    private final Set<String> nameProviders = ConcurrentHashMap.newKeySet();

    /** Last problem applying a mapping, per origin, for the admin page. */
    private final Map<String, String> mappingErrors = new ConcurrentHashMap<>();

    /**
     * Group writes run off the caller's thread: an inbound list is handled on the S2S IQ thread, and a
     * membership change makes Openfire rewrite the roster of every user the group is shown to.
     * Single-threaded so changes for one origin apply in order; daemon so a stuck write never blocks
     * plugin unload.
     */
    /** Serializes group writes; separate from the instance lock so a slow write never stalls {@link #tick}. */
    private final Object groupLock = new Object();

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "federation-contact-lists");
        t.setDaemon(true);
        return t;
    });

    public ContactListManager(FederationManager manager) {
        this.manager = manager;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    public void load() {
        rules.clear();
        boolean droppedUserRules = false;
        int count = JiveGlobals.getIntProperty(PROP_SHARE_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String kind = JiveGlobals.getProperty(String.format(PROP_SHARE_KIND, i));
            String name = JiveGlobals.getProperty(String.format(PROP_SHARE_NAME, i));
            if (name == null || name.isBlank()) continue;
            if ("user".equals(kind)) {
                Log.warn("Dropping contact share of single user '{}': only groups can be shared now", name);
                droppedUserRules = true;
                continue;
            }
            rules.add(new ShareRule(name.strip(),
                    parseCsv(JiveGlobals.getProperty(String.format(PROP_SHARE_TARGETS, i)))));
        }
        if (droppedUserRules) persistRules();

        mappings.clear();
        count = JiveGlobals.getIntProperty(PROP_MAP_COUNT, 0);
        for (int i = 0; i < count; i++) {
            String origin = JiveGlobals.getProperty(String.format(PROP_MAP_ORIGIN, i));
            if (origin == null || origin.isBlank()) continue;
            String display = JiveGlobals.getProperty(String.format(PROP_MAP_DISPLAY, i), origin);
            List<String> groups = parseCsv(JiveGlobals.getProperty(String.format(PROP_MAP_GROUPS, i)));
            mappings.put(origin.strip(), new ListMapping(origin.strip(), display, groups));
        }

        // Authorization must be in place before the first S2S poll: a contact's server probes our
        // users as soon as the link is back, often before that poll runs.
        authorized = computeAuthorization();
        Log.info("Loaded {} contact-share rule(s) and {} contact-list mapping(s)", rules.size(), mappings.size());
        // Re-apply sharing settings (an admin may have edited the group by hand while we were down).
        for (String origin : mappings.keySet()) syncGroupAsync(origin);
    }

    public void stop() {
        executor.shutdownNow();
        for (String origin : nameProviders) UserNameManager.removeUserNameProvider(origin);
        nameProviders.clear();
    }

    // ── Advertising: share rules ────────────────────────────────────────────────

    /** Share rules, sorted by group name. */
    public List<ShareRule> getShareRules() {
        synchronized (rules) {
            List<ShareRule> copy = new ArrayList<>(rules);
            copy.sort((a, b) -> a.group().compareToIgnoreCase(b.group()));
            return copy;
        }
    }

    /** Sets the servers a group is shared with (none = stop sharing), persists, and re-sends affected lists. */
    public void saveShareRule(String group, Collection<String> targets) {
        if (group == null || group.isBlank()) return;
        String g = group.strip();
        ShareRule rule = new ShareRule(g, new ArrayList<>(new LinkedHashSet<>(targets)));
        synchronized (rules) {
            rules.removeIf(r -> r.group().equals(g));
            if (!rule.targets().isEmpty()) rules.add(rule);
            persistRules();
        }
        Log.info("Contact share saved: group '{}' → {}", g, rule.targets());
        tick();
    }

    /** Stops sharing a group; its members are withdrawn from servers no other shared group still covers. */
    public void deleteShareRule(String group) {
        if (group == null) return;
        String g = group.strip();
        synchronized (rules) {
            rules.removeIf(r -> r.group().equals(g));
            persistRules();
        }
        Log.info("Contact share removed: group '{}'", g);
        tick();
    }

    /**
     * Servers a share can target: every routable server contact lists may travel to (see
     * {@link #canExchangeWith}).
     */
    public Set<String> shareableServers() {
        Set<String> out = new java.util.TreeSet<>();
        for (RouteEntry e : manager.getRoutingTable().getAll()) {
            if (canExchangeWith(e.destination())) out.add(e.destination());
        }
        return out;
    }

    /** What each target server was last sent (target → contacts). */
    public Map<String, List<Contact>> getSentLists() {
        return new TreeMap<>(sent);
    }

    /** The contacts {@code target} should currently receive, sorted by JID. */
    public List<Contact> computeSnapshot(String target) {
        Map<String, String> byJid = new TreeMap<>();
        for (ShareRule rule : getShareRules()) {
            if (!rule.targets().contains(target)) continue;
            try {
                Group g = GroupManager.getInstance().getGroup(rule.group());
                if (isPluginGroup(g)) continue;   // never re-share contacts another server shared with us
                for (JID j : g.getAll()) {
                    if (XMPPServer.getInstance().isLocal(j) && j.getNode() != null) addLocalUser(byJid, j.getNode());
                }
            } catch (GroupNotFoundException e) {
                Log.debug("Contact share: group '{}' no longer exists", rule.group());
            }
        }
        List<Contact> out = new ArrayList<>(byJid.size());
        byJid.forEach((jid, name) -> out.add(new Contact(jid, name)));
        return out;
    }

    private void addLocalUser(Map<String, String> byJid, String username) {
        try {
            User u = UserManager.getInstance().getUser(username);
            String name = (u.isNameVisible() && u.getName() != null) ? u.getName().strip() : "";
            byJid.put(XMPPServer.getInstance().createJID(u.getUsername(), null).toBareJID(), name);
        } catch (UserNotFoundException e) {
            Log.debug("Contact share: user '{}' no longer exists", username);
        }
    }

    /**
     * Periodic reconciliation, run from the S2S poll and after every admin change. Sends each target
     * its list when the list changed, when the target just became reachable, or on the periodic
     * refresh; asks each origin we hold a list or mapping for to re-send when it just became
     * reachable; and refreshes which local users each server is authorized to see.
     */
    public synchronized void tick() {
        long now = System.currentTimeMillis();
        boolean refresh = now - lastFullResend > RESEND_EVERY_MS;
        if (refresh) lastFullResend = now;

        Set<String> configured = new HashSet<>();
        for (ShareRule r : getShareRules()) configured.addAll(r.targets());
        Set<String> tracked = new HashSet<>(configured);
        tracked.addAll(sent.keySet());

        Map<String, Set<String>> auth = new HashMap<>();
        Set<String> up = new HashSet<>();
        for (String target : tracked) {
            List<Contact> snap = configured.contains(target) ? computeSnapshot(target) : List.of();
            if (!snap.isEmpty()) auth.put(target, jidsOf(snap));
            if (!canExchangeWith(target)) continue;
            up.add(target);
            boolean changed = !snap.equals(sent.get(target));
            if (changed || refresh || !targetsUpLastTick.contains(target)) send(target, snap);
            if (snap.isEmpty() && !configured.contains(target)) sent.remove(target);   // withdrawal delivered
        }
        targetsUpLastTick = up;
        authorized = Collections.unmodifiableMap(auth);
        pruneSubscribers();

        // Receiving side: solicit a fresh list from an origin that just came (back) into reach. This is
        // what clears a list the origin withdrew while we could not hear it (e.g. it restarted after
        // the admin stopped sharing with us, so it has no record that we still hold the old list).
        Set<String> origins = new HashSet<>(mappings.keySet());
        origins.addAll(received.keySet());
        Set<String> originsUp = new HashSet<>();
        for (String origin : origins) {
            if (!canRequestFrom(origin)) continue;
            originsUp.add(origin);
            if (!originsUpLastTick.contains(origin)) sendRequest(origin);
        }
        originsUpLastTick = originsUp;
    }

    /** Sends every reachable target its list now, and asks every reachable origin for its list. */
    public synchronized void resendAll() {
        targetsUpLastTick = Set.of();
        originsUpLastTick = Set.of();
        tick();
    }

    private Map<String, Set<String>> computeAuthorization() {
        Set<String> configured = new HashSet<>();
        for (ShareRule r : getShareRules()) configured.addAll(r.targets());
        Map<String, Set<String>> auth = new HashMap<>();
        for (String target : configured) {
            List<Contact> snap = computeSnapshot(target);
            if (!snap.isEmpty()) auth.put(target, jidsOf(snap));
        }
        return Collections.unmodifiableMap(auth);
    }

    private void send(String target, List<Contact> contacts) {
        String nextHop = manager.getRoutingTable().findNextHop(target).orElse(null);
        if (nextHop == null) return;
        String localDomain = localDomain();
        try {
            XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.contactList(nextHop, target, localDomain, localDomain, contacts));
            sent.put(target, contacts);
            Log.info("contact-list: sent {} contact(s) to {} via {}", contacts.size(), target, nextHop);
        } catch (Exception e) {
            Log.warn("contact-list: failed to send to {}: {}", target, e.getMessage());
        }
    }

    private void sendRequest(String origin) {
        String nextHop = manager.getRoutingTable().findNextHop(origin).orElse(null);
        if (nextHop == null) return;
        String localDomain = localDomain();
        try {
            XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.contactListRequest(nextHop, origin, localDomain, localDomain));
            Log.debug("contact-list: requested a fresh list from {}", origin);
        } catch (Exception e) {
            Log.warn("contact-list: failed to request a list from {}: {}", origin, e.getMessage());
        }
    }

    /** A server asked for its list (it just regained a route to us, or restarted). */
    public synchronized void handleRequest(String requester) {
        if (!canExchangeWith(requester)) return;
        boolean configured = getShareRules().stream().anyMatch(r -> r.targets().contains(requester));
        send(requester, configured ? computeSnapshot(requester) : List.of());
        if (!configured) sent.remove(requester);
    }

    // ── Advertising: presence authorization ─────────────────────────────────────

    /** True when local user {@code localBare} is shared with {@code remoteDomain}. */
    public boolean isAuthorized(String localBare, String remoteDomain) {
        if (localBare == null || remoteDomain == null) return false;
        Set<String> users = authorized.get(remoteDomain);
        return users != null && users.contains(localBare);
    }

    /** True when {@code contact} may see local {@code localUser}'s presence through a shared list. */
    public boolean isAuthorizedContact(JID localUser, JID contact) {
        if (localUser == null || contact == null || contact.getNode() == null) return false;
        return isAuthorized(localUser.toBareJID(), contact.getDomain());
    }

    /**
     * Remote contacts to send a local user's presence and PEP updates to: everyone who probed or
     * subscribed to the user through a shared list, as long as the user is still shared with them.
     */
    public Set<String> subscribersOf(JID localUser) {
        if (localUser == null) return Set.of();
        Set<String> subs = subscribers.get(localUser.toBareJID());
        if (subs == null || subs.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String s : subs) {
            if (isAuthorized(localUser.toBareJID(), new JID(s).getDomain())) out.add(s);
        }
        return out;
    }

    /**
     * Handles a probe or subscribe from a remote contact to a local user that is shared with the
     * contact's server. Returns true when consumed (the caller must not let Openfire process it): the
     * plugin answers on the user's behalf, so the user is never asked to approve a contact the admin
     * already shared, and the user's roster never gains an entry for every remote viewer. An
     * unsubscribe stops the pushes and is still left to Openfire. Anything else returns false.
     */
    public boolean handleInboundSubscription(Presence pres) {
        Presence.Type type = pres.getType();
        if (type != Presence.Type.probe && type != Presence.Type.subscribe && type != Presence.Type.unsubscribe) {
            return false;
        }
        JID from = pres.getFrom();
        JID to   = pres.getTo();
        if (from == null || to == null || from.getNode() == null || to.getNode() == null) return false;
        XMPPServer server = XMPPServer.getInstance();
        if (!server.isLocal(to) || server.isLocal(from)) return false;

        String localBare = to.toBareJID();
        String remoteBare = from.toBareJID();
        if (type == Presence.Type.unsubscribe) {
            // Stop pushing to them, but let Openfire see it too: the contact may also be on the
            // user's roster the ordinary way, and that subscription must end normally.
            removeSubscriber(localBare, remoteBare);
            return false;
        }
        if (!isAuthorized(localBare, from.getDomain())) return false;

        boolean isNew = subscribers.computeIfAbsent(localBare, k -> ConcurrentHashMap.newKeySet()).add(remoteBare);
        manager.pushContactPresenceTo(from.asBareJID(), to.asBareJID());
        // PEP only for a contact seen for the first time: mapping a list makes the receiving server
        // send a subscribe AND a probe per recipient per contact, and the items only change on publish
        // (relayed separately).
        if (isNew) manager.pushContactPepItemsTo(from.asBareJID(), to.asBareJID());
        Log.debug("contact-list: answered {} from {} for shared user {}", type, remoteBare, localBare);
        return true;
    }

    private boolean removeSubscriber(String localBare, String remoteBare) {
        Set<String> subs = subscribers.get(localBare);
        if (subs == null) return false;
        boolean removed = subs.remove(remoteBare);
        if (subs.isEmpty()) subscribers.remove(localBare);
        return removed;
    }

    /** Drops subscribers whose server no longer has the user shared with it. */
    private void pruneSubscribers() {
        for (Map.Entry<String, Set<String>> e : subscribers.entrySet()) {
            e.getValue().removeIf(s -> !isAuthorized(e.getKey(), new JID(s).getDomain()));
            if (e.getValue().isEmpty()) subscribers.remove(e.getKey());
        }
    }

    // ── Receiving ───────────────────────────────────────────────────────────────

    /** Records the list {@code origin} shares with us (empty = withdrawn) and applies it to its group. */
    public void handleContactList(String origin, List<Contact> contacts) {
        List<Contact> sorted = new ArrayList<>(contacts);
        sorted.sort((a, b) -> a.jid().compareTo(b.jid()));
        ReceivedList prev = received.get(origin);
        received.put(origin, new ReceivedList(List.copyOf(sorted), System.currentTimeMillis()));
        registerNameProvider(origin);
        if (prev != null && prev.contacts().equals(sorted) && !mappings.containsKey(origin)) return;
        Log.info("contact-list: {} shares {} contact(s) with us{}", origin, sorted.size(),
                 mappings.containsKey(origin) ? "" : " (not mapped)");
        syncGroupAsync(origin);
    }

    /** Lists received from other servers, by origin. */
    public Map<String, ReceivedList> getReceived() {
        return new TreeMap<>(received);
    }

    public Map<String, ListMapping> getMappings() {
        return new TreeMap<>(mappings);
    }

    public Map<String, String> getMappingErrors() {
        return new HashMap<>(mappingErrors);
    }

    /** Maps (or re-maps) {@code origin}'s list into the rosters of the given local groups. */
    public void setMapping(String origin, String displayName, List<String> groups) {
        if (origin == null || origin.isBlank()) return;
        String o = origin.strip();
        String display = (displayName == null || displayName.isBlank()) ? o : displayName.strip();
        List<String> g = groups.contains(EVERYBODY) ? List.of(EVERYBODY) : List.copyOf(new LinkedHashSet<>(groups));
        mappings.put(o, new ListMapping(o, display, g));
        persistMappings();
        Log.info("Contact list from {} mapped as '{}' for {}", o, display, g);
        syncGroupAsync(o);
    }

    /** Removes the mapping: the plugin's group for {@code origin} is deleted, taking the contacts out of every roster. */
    public void removeMapping(String origin) {
        if (origin == null) return;
        mappings.remove(origin.strip());
        mappingErrors.remove(origin.strip());
        persistMappings();
        Log.info("Contact list from {} unmapped", origin);
        syncGroupAsync(origin.strip());
    }

    /** The name of the Openfire group holding {@code origin}'s contacts. */
    public static String groupNameFor(String origin) {
        return GROUP_PREFIX + origin;
    }

    /** True when {@code g} is a group this plugin maintains. */
    public static boolean isPluginGroup(Group g) {
        return g != null && g.getProperties().get(GROUP_ORIGIN_PROP) != null;
    }

    /** Local groups an admin may share or map to — every group except the plugin's own. */
    public List<String> localGroupNames() {
        List<String> out = new ArrayList<>();
        for (Group g : GroupManager.getInstance().getGroups()) {
            if (!isPluginGroup(g)) out.add(g.getName());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** True when the group provider cannot be written to (e.g. LDAP), so lists cannot be mapped. */
    public boolean groupsReadOnly() {
        return GroupManager.getInstance().isReadOnly();
    }

    private void syncGroupAsync(String origin) {
        try {
            executor.execute(() -> {
                try {
                    syncGroup(origin);
                } catch (Exception e) {
                    mappingErrors.put(origin, e.getMessage() == null ? e.toString() : e.getMessage());
                    Log.warn("contact-list: failed to apply the list from {}: {}", origin, e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            // Plugin stopping — nothing to apply.
        }
    }

    /**
     * Brings the plugin's Openfire group for {@code origin} in line with the mapping and the last list
     * received: created when mapped, deleted when unmapped, members replaced by the list. Without a
     * list received since start-up the members are left as they are, so a restart does not empty
     * everyone's contact list while the origin is still out of reach.
     */
    private void syncGroup(String origin) throws Exception {
        synchronized (groupLock) {
            syncGroupLocked(origin);
        }
    }

    private void syncGroupLocked(String origin) throws Exception {
        GroupManager gm = GroupManager.getInstance();
        String name = groupNameFor(origin);
        Group group;
        try {
            group = gm.getGroup(name);
        } catch (GroupNotFoundException e) {
            group = null;
        }
        if (group != null && !isPluginGroup(group)) {
            mappingErrors.put(origin, "A group named '" + name + "' already exists and was not created by this plugin.");
            Log.warn("contact-list: group '{}' exists but is not plugin-owned — leaving it alone", name);
            return;
        }

        ListMapping mapping = mappings.get(origin);
        if (mapping == null) {
            if (group != null) {
                gm.deleteGroup(group);
                Log.info("contact-list: deleted group '{}' (list from {} unmapped)", name, origin);
            }
            mappingErrors.remove(origin);
            return;
        }
        if (gm.isReadOnly()) {
            mappingErrors.put(origin, "The group provider is read-only, so the plugin cannot create a group for this list.");
            return;
        }
        if (group == null) {
            group = gm.createGroup(name);
            group.setDescription("Contacts shared by " + origin + " over federation (managed by the federation plugin)");
            group.getProperties().put(GROUP_ORIGIN_PROP, origin);
            Log.info("contact-list: created group '{}' for the list from {}", name, origin);
        }
        applySharing(group, mapping);

        ReceivedList list = received.get(origin);
        if (list != null) {
            Set<JID> want = new LinkedHashSet<>();
            for (Contact c : list.contacts()) want.add(new JID(c.jid()));
            Collection<JID> members = group.getMembers();
            int removed = 0;
            for (JID j : new ArrayList<>(members)) {
                if (!want.contains(j)) { members.remove(j); removed++; }
            }
            List<JID> added = new ArrayList<>();
            for (JID j : want) {
                if (!members.contains(j)) { members.add(j); added.add(j); }
            }
            if (removed > 0 || !added.isEmpty()) {
                Log.info("contact-list: group '{}' now has {} member(s) (+{} −{})", name, want.size(), added.size(), removed);
            }
            probeNewContacts(added);
        }
        mappingErrors.remove(origin);
    }

    private void applySharing(Group group, ListMapping mapping) {
        if (mapping.everybody()) {
            if (group.getSharedWith() != SharedGroupVisibility.everybody
                    || !mapping.displayName().equals(group.getSharedDisplayName())) {
                group.shareWithEverybody(mapping.displayName());
            }
            return;
        }
        List<String> groups = new ArrayList<>(mapping.groups());
        groups.removeIf(g -> g.equals(group.getName()));
        if (groups.isEmpty()) {
            if (group.getSharedWith() != SharedGroupVisibility.nobody) group.shareWithNobody();
            return;
        }
        if (group.getSharedWith() != SharedGroupVisibility.usersOfGroups
                || !mapping.displayName().equals(group.getSharedDisplayName())
                || !new HashSet<>(groups).equals(new HashSet<>(group.getSharedWithUsersInGroupNames()))) {
            group.shareWithUsersInGroups(groups, mapping.displayName());
        }
    }

    /**
     * Pulls the presence of contacts just added to a roster for users who are online right now.
     * Openfire probes them itself, but for a server reached over more than one hop that probe goes
     * to native S2S and is lost, so it is re-sent over the overlay. Direct peers get Openfire's own
     * probe, which the sharing server answers.
     */
    private void probeNewContacts(List<JID> added) {
        List<JID> multiHop = new ArrayList<>();
        for (JID j : added) if (manager.isMultiHopDomain(j.getDomain())) multiHop.add(j);
        if (multiHop.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        for (ClientSession s : XMPPServer.getInstance().getSessionManager().getSessions()) {
            JID user = s.getAddress();
            if (user == null || user.getNode() == null || !seen.add(user.getNode())) continue;
            if (!XMPPServer.getInstance().isLocal(user)) continue;
            Roster roster;
            try {
                roster = XMPPServer.getInstance().getRosterManager().getRoster(user.getNode());
            } catch (UserNotFoundException e) {
                continue;
            }
            for (JID contact : multiHop) {
                if (!roster.isRosterItem(contact)) continue;
                Presence probe = new Presence(Presence.Type.probe);
                probe.setFrom(user.asBareJID());
                probe.setTo(contact);
                manager.forwardDirectPresence(probe);
            }
        }
    }

    /**
     * Lets Openfire show a shared contact under the name its own server gave it. Without a provider
     * Openfire names a remote roster entry by its bare JID.
     */
    private void registerNameProvider(String origin) {
        if (!nameProviders.add(origin)) return;
        UserNameManager.addUserNameProvider(origin, jid -> {
            ReceivedList list = received.get(origin);
            String bare = jid.toBareJID();
            if (list != null) {
                for (Contact c : list.contacts()) {
                    if (c.jid().equals(bare) && !c.name().isEmpty()) return c.name();
                }
            }
            return bare;
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * Whether this server may send its contact list to {@code domain}: it has a route, and if our own
     * next hop on it is an untrusted peer, that link allows sending contact lists and this server is
     * exposed to it. An untrusted link further along is checked by the server sending across it (see
     * {@code FederationIQHandler}); this server cannot see its settings, so such a route qualifies here
     * and is flagged by {@link #crossesUntrusted}.
     */
    public boolean canExchangeWith(String domain) {
        return reachable(domain, true);
    }

    /**
     * Whether this server may ask {@code domain} for its list. A request carries no contacts, so the
     * per-link flag does not apply, only the exposure model.
     */
    public boolean canRequestFrom(String domain) {
        return reachable(domain, false);
    }

    private boolean reachable(String domain, boolean sendingList) {
        if (domain == null || domain.equals(localDomain())) return false;
        Optional<RouteEntry> route = manager.getRoutingTable().getRoute(domain);
        if (route.isEmpty()) return false;
        String nextHop = route.get().nextHop();
        PeerRegistry peers = manager.getPeerRegistry();
        if (!peers.isUntrusted(nextHop)) return true;
        return (!sendingList || peers.isContactListsAllowed(nextHop))
            && peers.getExposedServers(nextHop).contains(localDomain());
    }

    /** True when the route to {@code domain} crosses an untrusted link (for the admin page). */
    public boolean crossesUntrusted(String domain) {
        return manager.getRoutingTable().getRoute(domain).map(e -> !cleanRoute(e)).orElse(false);
    }

    private boolean cleanRoute(RouteEntry e) {
        return !com.igniterealtime.openfire.plugin.federation.FederationRoutingTable
                .isEdge(e, manager.getPeerRegistry()::isUntrusted);
    }

    /**
     * Validates and normalizes a received contact: a bare JID on {@code origin} (a server may only
     * share its own users), with a bounded display name. Returns null for anything else.
     */
    public static Contact parseContact(String origin, String jid, String name) {
        if (jid == null || jid.isBlank()) return null;
        JID parsed;
        try {
            parsed = new JID(jid.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (parsed.getNode() == null || parsed.getResource() != null || !Objects.equals(parsed.getDomain(), origin)) {
            return null;
        }
        String n = name == null ? "" : name.strip();
        if (n.length() > MAX_NAME) n = n.substring(0, MAX_NAME);
        return new Contact(parsed.toBareJID(), n);
    }

    private static Set<String> jidsOf(List<Contact> contacts) {
        Set<String> out = new HashSet<>();
        for (Contact c : contacts) out.add(c.jid());
        return Collections.unmodifiableSet(out);
    }

    private static String localDomain() {
        return XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    }

    private void persistRules() {
        int old = JiveGlobals.getIntProperty(PROP_SHARE_COUNT, 0);
        for (int i = 0; i < old; i++) {
            JiveGlobals.deleteProperty(String.format(PROP_SHARE_KIND, i));
            JiveGlobals.deleteProperty(String.format(PROP_SHARE_NAME, i));
            JiveGlobals.deleteProperty(String.format(PROP_SHARE_TARGETS, i));
        }
        int i = 0;
        for (ShareRule r : rules) {
            JiveGlobals.setProperty(String.format(PROP_SHARE_NAME, i), r.group());
            JiveGlobals.setProperty(String.format(PROP_SHARE_TARGETS, i), String.join(",", r.targets()));
            i++;
        }
        JiveGlobals.setProperty(PROP_SHARE_COUNT, String.valueOf(i));
    }

    private synchronized void persistMappings() {
        int old = JiveGlobals.getIntProperty(PROP_MAP_COUNT, 0);
        for (int i = 0; i < old; i++) {
            JiveGlobals.deleteProperty(String.format(PROP_MAP_ORIGIN, i));
            JiveGlobals.deleteProperty(String.format(PROP_MAP_DISPLAY, i));
            JiveGlobals.deleteProperty(String.format(PROP_MAP_GROUPS, i));
        }
        int i = 0;
        for (ListMapping m : getMappings().values()) {
            JiveGlobals.setProperty(String.format(PROP_MAP_ORIGIN, i), m.origin());
            JiveGlobals.setProperty(String.format(PROP_MAP_DISPLAY, i), m.displayName());
            JiveGlobals.setProperty(String.format(PROP_MAP_GROUPS, i), String.join(",", m.groups()));
            i++;
        }
        JiveGlobals.setProperty(PROP_MAP_COUNT, String.valueOf(i));
    }

    private static List<String> parseCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            String t = s.strip();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }
}
