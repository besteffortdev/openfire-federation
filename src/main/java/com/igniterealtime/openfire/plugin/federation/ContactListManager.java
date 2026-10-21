package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.group.Group;
import org.jivesoftware.openfire.group.GroupAlreadyExistsException;
import org.jivesoftware.openfire.group.GroupManager;
import org.jivesoftware.openfire.group.GroupNameInvalidException;
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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Shares contact lists between servers, after Openfire's own shared groups. The admin shares local
 * groups with peer servers, and each list received from another server can be mapped into a
 * plugin-owned Openfire group shown in chosen local rosters, made searchable in the
 * {@link FederatedDirectory}, or both. Sharing a user with a server also lets that server's users see
 * the user's presence and PEP data.
 */
public class ContactListManager {

    private static final Logger Log = LoggerFactory.getLogger(ContactListManager.class);

    private static final String PROP_SHARE_COUNT   = "federation.contactshare.count";
    /** Retired with single-user sharing; a rule with kind=user is dropped on load. */
    private static final String PROP_SHARE_KIND    = "federation.contactshare.%d.kind";
    private static final String PROP_SHARE_NAME    = "federation.contactshare.%d.name";
    private static final String PROP_SHARE_TARGETS = "federation.contactshare.%d.targets";

    private static final String PROP_MAP_COUNT   = "federation.contactmap.count";
    private static final String PROP_MAP_ORIGIN  = "federation.contactmap.%d.origin";
    private static final String PROP_MAP_DISPLAY = "federation.contactmap.%d.display";
    private static final String PROP_MAP_GROUPS  = "federation.contactmap.%d.groups";
    private static final String PROP_MAP_DIRECTORY = "federation.contactmap.%d.directory";

    /** Origins whose lists the admin denied, comma-separated. */
    private static final String PROP_DENIED = "federation.contactlist.denied";

    /** Prefix of the Openfire group the plugin keeps for one origin's list. */
    public static final String GROUP_PREFIX = "federation-contacts-";
    /** Group property marking a group as plugin-owned, holding the origin it mirrors. */
    public static final String GROUP_ORIGIN_PROP = "federation.contactList.origin";

    /** Mapping token meaning "show this list to every local user". */
    public static final String EVERYBODY = "*";


    /** Most contacts accepted in one list; the rest are dropped and logged. */
    public static final int MAX_CONTACTS = 5000;
    /** Longest display name accepted for a contact. */
    private static final int MAX_NAME = 128;

    /** Most lists kept from servers nobody mapped yet; a list from one more such server is dropped. */
    static final int MAX_UNMAPPED_LISTS = 32;
    /** Most contacts kept across all unmapped lists together. */
    static final int MAX_UNMAPPED_CONTACTS = 50_000;
    /** Most remote contacts tracked as receiving one local user's presence and PEP updates. */
    private static final int MAX_SUBSCRIBERS_PER_USER = 1000;
    /** A server's contact-list-request is answered at most this often. */
    private static final long REQUEST_COOLDOWN_MS = 30_000L;
    /** A received list is logged at INFO at most this often per origin; refusals at most this often overall. */
    private static final long LOG_EVERY_MS = 60_000L;

    /** Every list is re-sent this often even when unchanged, in case one was lost. */
    private static final long RESEND_EVERY_MS = 10 * 60_000L;

    /** One shared contact: a bare JID and the display name its server gave it, possibly empty. */
    public record Contact(String jid, String name) {}

    /** One local group shared with a set of peer servers, keyed by {@code group}. */
    public record ShareRule(String group, List<String> targets) {
        public ShareRule {
            targets = (targets == null) ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * How a received list is used here: placed into the rosters of {@code groups} (or {@link #EVERYBODY};
     * none keeps it out of every roster), and searchable in the federated directory when {@code directory}.
     */
    public record ListMapping(String origin, String displayName, List<String> groups, boolean directory) {
        public ListMapping {
            groups = (groups == null) ? List.of() : List.copyOf(groups);
        }
        public boolean everybody() { return groups.contains(EVERYBODY); }
        public boolean inRosters() { return !groups.isEmpty(); }
    }

    /** A list another server shared with us; {@code crossedUntrusted} if it came across an untrusted link. */
    public record ReceivedList(List<Contact> contacts, long receivedAt, boolean crossedUntrusted) {}

    private final FederationManager manager;

    private final List<ShareRule> rules = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, ListMapping> mappings = new ConcurrentHashMap<>();
    private final Map<String, ReceivedList> received = new ConcurrentHashMap<>();
    /** Origins whose lists are dropped on arrival, and whose last list is not used, until allowed again. */
    private final Set<String> denied = ConcurrentHashMap.newKeySet();

    /** Target domain to the list last sent to it, sorted by JID. */
    private final Map<String, List<Contact>> sent = new ConcurrentHashMap<>();
    /** Target domain to the bare JIDs of the local users currently shared with it. */
    private volatile Map<String, Set<String>> authorized = Map.of();
    /** Local bare JID to the remote bare JIDs that probed or subscribed to it through a shared list. */
    private final Map<String, Set<String>> subscribers = new ConcurrentHashMap<>();

    private Set<String> targetsUpLastTick = Set.of();
    private Set<String> originsUpLastTick = Set.of();
    private long lastFullResend = System.currentTimeMillis();

    /** Requester domain to when its last contact-list-request was answered. */
    private final Map<String, Long> requestAnsweredAt = new ConcurrentHashMap<>();
    /** Origin to when a list from it was last logged at INFO. */
    private final Map<String, Long> loggedAt = new ConcurrentHashMap<>();
    private volatile long overflowLoggedAt = 0;
    /** Guards the unmapped-list budget across concurrent receipts. */
    private final Object receiveLock = new Object();
    /** Origins with a group sync queued but not started, so a burst of lists collapses into one sync. */
    private final Set<String> pendingSyncs = ConcurrentHashMap.newKeySet();

    /** Mapped origins we registered a {@link UserNameManager} provider for. */
    private final Set<String> nameProviders = ConcurrentHashMap.newKeySet();

    /** Last problem applying a mapping, per origin, for the admin page. */
    private final Map<String, String> mappingErrors = new ConcurrentHashMap<>();

    /** Serializes group writes; separate from the instance lock so a slow write never stalls {@link #tick}. */
    private final Object groupLock = new Object();

    /**
     * Runs group writes off the S2S thread, since a membership change rewrites the roster of every
     * user who sees the group. Single-threaded so one origin's changes apply in order.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "federation-contact-lists");
        t.setDaemon(true);
        return t;
    });

    public ContactListManager(FederationManager manager) {
        this.manager = manager;
    }

    /** Loads the share rules and mappings and re-applies every mapped list's group. */
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
            boolean directory = JiveGlobals.getBooleanProperty(String.format(PROP_MAP_DIRECTORY, i), false);
            mappings.put(origin.strip(), new ListMapping(origin.strip(), display, groups, directory));
        }

        denied.clear();
        denied.addAll(parseCsv(JiveGlobals.getProperty(PROP_DENIED)));

        // Needed before the first S2S poll: a contact's server probes our users as soon as the link is back.
        authorized = computeAuthorization();
        Log.info("Loaded {} contact-share rule(s) and {} contact-list mapping(s)", rules.size(), mappings.size());
        for (String origin : mappings.keySet()) {
            registerNameProvider(origin);
            syncGroupAsync(origin);
        }
    }

    public void stop() {
        executor.shutdownNow();
        for (String origin : nameProviders) UserNameManager.removeUserNameProvider(origin);
        nameProviders.clear();
    }

    /** Share rules, sorted by group name. */
    public List<ShareRule> getShareRules() {
        synchronized (rules) {
            List<ShareRule> copy = new ArrayList<>(rules);
            copy.sort((a, b) -> a.group().compareToIgnoreCase(b.group()));
            return copy;
        }
    }

    /** Sets the servers a group is shared with, where none stops sharing, and re-sends the affected lists. */
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

    /** Stops sharing a group; its members are withdrawn from servers no other shared group covers. */
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

    /** Every routable server a contact list may be sent to; see {@link #canExchangeWith}. */
    public Set<String> shareableServers() {
        Set<String> out = new TreeSet<>();
        for (RouteEntry e : manager.getRoutingTable().getAll()) {
            if (canExchangeWith(e.destination())) out.add(e.destination());
        }
        return out;
    }

    /** What each target server was last sent. */
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
                // Contacts other servers shared with us are never shared on.
                if (isPluginGroup(g)) continue;
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
     * Reconciles sharing, from the S2S poll and after every admin change: sends changed lists, asks
     * origins that just came into reach for theirs, and refreshes which users each server may see.
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
            if (snap.isEmpty() && !configured.contains(target)) sent.remove(target);
        }
        targetsUpLastTick = up;
        authorized = Collections.unmodifiableMap(auth);
        pruneSubscribers();

        // A fresh request also clears a list the origin withdrew while it could not reach us.
        pruneUnmappedLists();
        Set<String> origins = new HashSet<>(mappings.keySet());
        origins.addAll(received.keySet());
        Set<String> originsUp = new HashSet<>();
        origins.removeAll(denied);
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
        if (FederationSender.send("contact-list", target,
                () -> FederationStanzaFactory.contactList(nextHop, target, localDomain, localDomain, contacts))) {
            sent.put(target, contacts);
            Log.info("contact-list: sent {} contact(s) to {} via {}", contacts.size(), target, nextHop);
        }
    }

    private void sendRequest(String origin) {
        String nextHop = manager.getRoutingTable().findNextHop(origin).orElse(null);
        if (nextHop == null) return;
        String localDomain = localDomain();
        if (FederationSender.send("contact-list-request", origin,
                () -> FederationStanzaFactory.contactListRequest(nextHop, origin, localDomain, localDomain))) {
            Log.debug("contact-list: requested a fresh list from {}", origin);
        }
    }

    /** Answers a server that asked for its list, at most once per {@link #REQUEST_COOLDOWN_MS}. */
    public synchronized void handleRequest(String requester) {
        if (!canExchangeWith(requester)) return;
        long now = System.currentTimeMillis();
        Long last = requestAnsweredAt.get(requester);
        if (last != null && now - last < REQUEST_COOLDOWN_MS) {
            Log.debug("contact-list: ignoring a repeated request from {} (answered {} ms ago)", requester, now - last);
            return;
        }
        requestAnsweredAt.values().removeIf(t -> now - t >= REQUEST_COOLDOWN_MS);
        requestAnsweredAt.put(requester, now);
        boolean configured = getShareRules().stream().anyMatch(r -> r.targets().contains(requester));
        send(requester, configured ? computeSnapshot(requester) : List.of());
        if (!configured) sent.remove(requester);
    }

    /** Whether local user {@code localBare} is shared with {@code remoteDomain}. */
    public boolean isAuthorized(String localBare, String remoteDomain) {
        if (localBare == null || remoteDomain == null) return false;
        Set<String> users = authorized.get(remoteDomain);
        return users != null && users.contains(localBare);
    }

    /** Whether {@code contact} may see local {@code localUser}'s presence through a shared list. */
    public boolean isAuthorizedContact(JID localUser, JID contact) {
        if (localUser == null || contact == null || contact.getNode() == null) return false;
        return isAuthorized(localUser.toBareJID(), contact.getDomain());
    }

    /** The remote contacts that probed or subscribed to a local user through a list still shared with them. */
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
     * Answers a probe or subscribe from a contact whose server the local user is shared with, on the
     * user's behalf, so the user is never asked to approve it. True when consumed.
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
            // Also left to Openfire: the contact may be on the user's roster the ordinary way too.
            removeSubscriber(localBare, remoteBare);
            return false;
        }
        if (!isAuthorized(localBare, from.getDomain())) return false;

        Set<String> subs = subscribers.computeIfAbsent(localBare, k -> ConcurrentHashMap.newKeySet());
        if (!subs.contains(remoteBare) && subs.size() >= MAX_SUBSCRIBERS_PER_USER) {
            // Answered once, but not tracked: a server inventing JIDs must not grow the set without limit.
            manager.getPresenceRelay().pushContactPresenceTo(from.asBareJID(), to.asBareJID());
            Log.debug("contact-list: {} already has {} remote subscribers — not tracking {}", localBare,
                      MAX_SUBSCRIBERS_PER_USER, remoteBare);
            return true;
        }
        boolean isNew = subs.add(remoteBare);
        PresenceRelay presenceRelay = manager.getPresenceRelay();
        presenceRelay.pushContactPresenceTo(from.asBareJID(), to.asBareJID());
        // PEP items change only on publish, which is relayed separately, so a known contact needs no repeat.
        if (isNew) presenceRelay.pushContactPepItemsTo(from.asBareJID(), to.asBareJID());
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

    /**
     * Records the list {@code origin} shares with us, where empty means withdrawn, and applies it to
     * its group. An unmapped list is kept only within the unmapped budget; a denied origin's is dropped.
     */
    public void handleContactList(String origin, List<Contact> contacts, boolean crossedUntrusted) {
        List<Contact> sorted = new ArrayList<>(contacts);
        sorted.sort((a, b) -> a.jid().compareTo(b.jid()));
        boolean mapped = mappings.containsKey(origin);
        ReceivedList prev;
        // Under the lock that denyOrigin takes, so a list arriving during a deny is not kept after it.
        synchronized (receiveLock) {
            if (denied.contains(origin)) {
                logDeniedList(origin);
                return;
            }
            prev = received.get(origin);
            if (!mapped) {
                if (sorted.isEmpty()) {
                    if (prev != null) {
                        received.remove(origin);
                        loggedAt.remove(origin);
                        Log.info("contact-list: {} withdrew its list (not mapped)", origin);
                    }
                    return;
                }
                if (!unmappedBudgetAllows(origin, sorted.size())) {
                    long now = System.currentTimeMillis();
                    if (now - overflowLoggedAt >= LOG_EVERY_MS) {
                        overflowLoggedAt = now;
                        Log.warn("contact-list: not keeping the list from {} ({} contact(s)) — lists nobody mapped "
                               + "are limited to {} servers and {} contacts in all. Further refusals are logged "
                               + "at most once a minute.", origin, sorted.size(), MAX_UNMAPPED_LISTS,
                                 MAX_UNMAPPED_CONTACTS);
                    }
                    return;
                }
            }
            received.put(origin, new ReceivedList(List.copyOf(sorted), System.currentTimeMillis(), crossedUntrusted));
        }
        boolean changed = prev == null || !prev.contacts().equals(sorted);
        if (changed) {
            long now = System.currentTimeMillis();
            Long last = loggedAt.get(origin);
            if (last == null || now - last >= LOG_EVERY_MS) {
                loggedAt.put(origin, now);
                Log.info("contact-list: {} shares {} contact(s) with us{}{}", origin, sorted.size(),
                         mapped ? "" : " (not mapped)",
                         crossedUntrusted ? " — arrived across an untrusted link" : "");
            }
        }
        if (mapped) syncGroupAsync(origin);
    }

    private void logDeniedList(String origin) {
        long now = System.currentTimeMillis();
        Long last = loggedAt.get(origin);
        if (last == null || now - last >= LOG_EVERY_MS) {
            loggedAt.put(origin, now);
            Log.info("contact-list: dropped the list from {} — its lists are denied here", origin);
        }
    }

    /** Whether one more unmapped list of {@code size} contacts from {@code origin} fits the budget. */
    private boolean unmappedBudgetAllows(String origin, int size) {
        int lists = 0;
        long total = 0;
        for (Map.Entry<String, ReceivedList> e : received.entrySet()) {
            if (e.getKey().equals(origin) || mappings.containsKey(e.getKey())) continue;
            lists++;
            total += e.getValue().contacts().size();
        }
        return lists < MAX_UNMAPPED_LISTS && total + size <= MAX_UNMAPPED_CONTACTS;
    }

    /** Forgets unmapped lists from servers we no longer route to; they re-send when they return. */
    private void pruneUnmappedLists() {
        synchronized (receiveLock) {
            for (String origin : new ArrayList<>(received.keySet())) {
                if (mappings.containsKey(origin)) continue;
                if (manager.getRoutingTable().getRoute(origin).isEmpty()) {
                    received.remove(origin);
                    loggedAt.remove(origin);
                    Log.debug("contact-list: forgot the unmapped list from {} (no route)", origin);
                }
            }
        }
    }

    /** Lists received from other servers, by origin. */
    public Map<String, ReceivedList> getReceived() {
        return new TreeMap<>(received);
    }

    public Map<String, ListMapping> getMappings() {
        return new TreeMap<>(mappings);
    }

    /** The received lists the admin made searchable in the federated directory, by origin; none denied. */
    public Map<String, List<Contact>> searchableLists() {
        Map<String, List<Contact>> out = new TreeMap<>();
        for (ListMapping m : mappings.values()) {
            if (!m.directory() || denied.contains(m.origin())) continue;
            ReceivedList list = received.get(m.origin());
            if (list != null) out.put(m.origin(), list.contacts());
        }
        return out;
    }

    /** Whether any list not denied is searchable, received yet or not; the directory runs while one is. */
    public boolean hasSearchableList() {
        return mappings.values().stream().anyMatch(m -> m.directory() && !denied.contains(m.origin()));
    }

    public Map<String, String> getMappingErrors() {
        return new HashMap<>(mappingErrors);
    }

    /**
     * Maps, or re-maps, {@code origin}'s list into the rosters of the given local groups, none for no
     * roster, and into the federated directory when {@code directory}.
     */
    public void setMapping(String origin, String displayName, List<String> groups, boolean directory) {
        if (origin == null || origin.isBlank()) return;
        String o = origin.strip();
        String display = (displayName == null || displayName.isBlank()) ? o : displayName.strip();
        List<String> g = groups.contains(EVERYBODY) ? List.of(EVERYBODY) : List.copyOf(new LinkedHashSet<>(groups));
        mappings.put(o, new ListMapping(o, display, g, directory));
        persistMappings();
        registerNameProvider(o);
        Log.info("Contact list from {} mapped as '{}' for {}{}", o, display, g.isEmpty() ? "no roster" : g,
                 directory ? ", searchable in the directory" : "");
        syncGroupAsync(o);
        manager.refreshDirectory();
    }

    /** Unmaps {@code origin}'s list; its group is deleted, which takes the contacts out of every roster. */
    public void removeMapping(String origin) {
        if (origin == null) return;
        mappings.remove(origin.strip());
        mappingErrors.remove(origin.strip());
        persistMappings();
        if (nameProviders.remove(origin.strip())) UserNameManager.removeUserNameProvider(origin.strip());
        Log.info("Contact list from {} unmapped", origin);
        syncGroupAsync(origin.strip());
        manager.refreshDirectory();
    }

    /**
     * Drops {@code origin}'s later lists and stops using the one held: its contacts leave every roster
     * and the directory. The entry and its mapping are kept, so {@link #allowOrigin} restores them.
     */
    public void denyOrigin(String origin) {
        if (origin == null || origin.isBlank()) return;
        String o = origin.strip();
        synchronized (receiveLock) {
            if (!denied.add(o)) return;
            persistDenied();
        }
        Log.info("Contact lists from {} denied: its lists are dropped and its contacts are not used", o);
        syncGroupAsync(o);
        manager.refreshDirectory();
    }

    /** Uses {@code origin}'s list and mapping again, and asks it for a fresh list. */
    public void allowOrigin(String origin) {
        if (origin == null) return;
        String o = origin.strip();
        synchronized (receiveLock) {
            if (!denied.remove(o)) return;
            persistDenied();
        }
        Log.info("Contact lists from {} allowed again", o);
        syncGroupAsync(o);
        manager.refreshDirectory();
        if (canRequestFrom(o)) sendRequest(o);
    }

    /**
     * Forgets {@code origin}: its list, any mapping (which takes its contacts out of every roster and
     * the directory) and any deny. A list it sends later is shown again as a new one.
     */
    public void deleteOrigin(String origin) {
        if (origin == null || origin.isBlank()) return;
        String o = origin.strip();
        synchronized (receiveLock) {
            received.remove(o);
            loggedAt.remove(o);
            if (denied.remove(o)) persistDenied();
        }
        if (mappings.containsKey(o)) removeMapping(o);
        Log.info("Contact list from {} deleted; a list it sends later is shown again", o);
    }

    /** Whether {@code origin}'s lists are denied. */
    public boolean isDenied(String origin) {
        return denied.contains(origin);
    }

    /** Origins whose lists are denied, sorted. */
    public Set<String> getDenied() {
        return new TreeSet<>(denied);
    }

    /** The name of the Openfire group holding {@code origin}'s contacts. */
    public static String groupNameFor(String origin) {
        return GROUP_PREFIX + origin;
    }

    /** Whether {@code g} is a group this plugin maintains. */
    public static boolean isPluginGroup(Group g) {
        return g != null && g.getProperties().get(GROUP_ORIGIN_PROP) != null;
    }

    /** Local groups an admin may share or map to: every group except the plugin's own. */
    public List<String> localGroupNames() {
        List<String> out = new ArrayList<>();
        for (Group g : GroupManager.getInstance().getGroups()) {
            if (!isPluginGroup(g)) out.add(g.getName());
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Whether the group provider is read-only (for example LDAP), so lists cannot be mapped. */
    public boolean groupsReadOnly() {
        return GroupManager.getInstance().isReadOnly();
    }

    private void syncGroupAsync(String origin) {
        if (!pendingSyncs.add(origin)) return;
        try {
            executor.execute(() -> {
                pendingSyncs.remove(origin);
                try {
                    syncGroup(origin);
                } catch (RuntimeException e) {
                    mappingErrors.put(origin, e.getMessage() == null ? e.toString() : e.getMessage());
                    Log.warn("contact-list: could not apply the list from {}", origin, e);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The plugin is stopping, so there is nothing left to apply.
        }
    }

    /**
     * Brings the plugin's group for {@code origin} in line with its mapping and the last list received;
     * a list in no roster, or denied, has no group. Until a list arrives after start-up the members are
     * kept, so a restart empties nothing.
     */
    private void syncGroup(String origin) {
        synchronized (groupLock) {
            syncGroupLocked(origin);
        }
    }

    private void syncGroupLocked(String origin) {
        GroupManager gm = GroupManager.getInstance();
        String name = groupNameFor(origin);
        Group group;
        try {
            group = gm.getGroup(name);
        } catch (GroupNotFoundException e) {
            group = null;
        }
        if (group != null && !isPluginGroup(group)) {
            mappingErrors.put(origin, "A group named '" + name
                    + "' already exists and was not created by this plugin.");
            Log.warn("contact-list: group '{}' exists but is not plugin-owned — leaving it alone", name);
            return;
        }

        ListMapping mapping = mappings.get(origin);
        if (mapping == null || !mapping.inRosters() || denied.contains(origin)) {
            if (group != null) deleteGroup(gm, group, origin);
            mappingErrors.remove(origin);
            return;
        }
        if (gm.isReadOnly()) {
            mappingErrors.put(origin,
                    "The group provider is read-only, so the plugin cannot create a group for this list.");
            return;
        }
        if (group == null) {
            group = createGroup(gm, name, origin);
            if (group == null) return;
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
                Log.info("contact-list: group '{}' now has {} member(s) (+{} −{})",
                         name, want.size(), added.size(), removed);
            }
            probeNewContacts(added);
        }
        mappingErrors.remove(origin);
    }

    private static void deleteGroup(GroupManager gm, Group group, String origin) {
        try {
            gm.deleteGroup(group);
            Log.info("contact-list: deleted group '{}' (list from {} is in no roster now, or denied)",
                     group.getName(), origin);
        } catch (GroupNotFoundException ignored) {
            // Already deleted by someone else, which is the state we want.
        }
    }

    /** Creates the plugin-owned group for {@code origin}; null, with the reason recorded, when that fails. */
    private Group createGroup(GroupManager gm, String name, String origin) {
        try {
            Group group = gm.createGroup(name);
            group.setDescription("Contacts shared by " + origin
                    + " over federation (managed by the federation plugin)");
            group.getProperties().put(GROUP_ORIGIN_PROP, origin);
            Log.info("contact-list: created group '{}' for the list from {}", name, origin);
            return group;
        } catch (GroupAlreadyExistsException | GroupNameInvalidException e) {
            mappingErrors.put(origin, "The plugin could not create the group '" + name + "' for this list.");
            Log.warn("contact-list: could not create group '{}' for the list from {}", name, origin, e);
            return null;
        }
    }

    private static void applySharing(Group group, ListMapping mapping) {
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
     * Probes new multi-hop contacts over the overlay for users online now. Openfire's own probe to
     * such a server goes to native S2S and is lost; direct peers get Openfire's probe.
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

    /** Lets Openfire show a shared contact under the name its own server gave it, not its bare JID. */
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

    /**
     * Whether this server may send its contact list to {@code domain}: it has a route, and an
     * untrusted next hop allows contact lists and has this server exposed to it.
     */
    public boolean canExchangeWith(String domain) {
        return reachable(domain, true);
    }

    /** Whether this server may ask {@code domain} for its list; only the exposure rules apply to a request. */
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

    /** Whether the route to {@code domain} crosses an untrusted link, for the admin page. */
    public boolean crossesUntrusted(String domain) {
        return manager.getRoutingTable().getRoute(domain).map(e -> !cleanRoute(e)).orElse(false);
    }

    private boolean cleanRoute(RouteEntry e) {
        return !FederationRoutingTable
                .isEdge(e, manager.getPeerRegistry()::isUntrusted);
    }

    /**
     * A received contact, validated: a bare JID on {@code origin}, since a server may only share its
     * own users, with a length-capped name. Null for anything else.
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
            JiveGlobals.deleteProperty(String.format(PROP_MAP_DIRECTORY, i));
        }
        int i = 0;
        for (ListMapping m : getMappings().values()) {
            JiveGlobals.setProperty(String.format(PROP_MAP_ORIGIN, i), m.origin());
            JiveGlobals.setProperty(String.format(PROP_MAP_DISPLAY, i), m.displayName());
            JiveGlobals.setProperty(String.format(PROP_MAP_GROUPS, i), String.join(",", m.groups()));
            if (m.directory()) JiveGlobals.setProperty(String.format(PROP_MAP_DIRECTORY, i), "true");
            i++;
        }
        JiveGlobals.setProperty(PROP_MAP_COUNT, String.valueOf(i));
    }

    private void persistDenied() {
        if (denied.isEmpty()) {
            JiveGlobals.deleteProperty(PROP_DENIED);
        } else {
            JiveGlobals.setProperty(PROP_DENIED, String.join(",", new TreeSet<>(denied)));
        }
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
