package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatManager;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Tracks which local MUC rooms are tagged as "federatable", which rooms
 * remote peers have advertised, and the confirmed bilateral room mappings.
 *
 * A single local room can be mapped to multiple remote rooms on different
 * peer domains (hub topology).  The reverse index (remoteJid → mapping)
 * remains 1:1 because each remote room JID uniquely identifies a spoke.
 *
 * Persistence: federation tags and room mappings are stored in JiveGlobals.
 * Remote room lists are ephemeral — rebuilt from gossip on reconnect.
 */
public class FederatedRoomManager {

    private static final Logger Log = LoggerFactory.getLogger(FederatedRoomManager.class);

    private static final String PROP_ROOMS_INDEX = "federation.rooms.index";
    private static final String PROP_ROOM_PREFIX = "federation.room.federated.";
    private static final String PROP_MAP_INDEX   = "federation.rooms.mappings.index";
    private static final String PROP_MAP_COUNT   = "federation.rooms.mapping.%s.count";
    private static final String PROP_MAP_REMOTE  = "federation.rooms.mapping.%s.%d.remote";
    private static final String PROP_MAP_DOMAIN  = "federation.rooms.mapping.%s.%d.domain";

    // Virtual occupant persistence — survives plugin reloads so eviction on peer-down
    // can still send leave presences even if no join events occurred after the reload.
    private static final String PROP_VOCC_ROOMS   = "federation.vocc.rooms.index";
    private static final String PROP_VOCC_DOMAINS = "federation.vocc.%s.domains";
    private static final String PROP_VOCC_NICKS   = "federation.vocc.%s.%s";

    /** Room JIDs (room@conference.domain) tagged locally as federatable. */
    private final Set<String> localFederatedRooms = ConcurrentHashMap.newKeySet();

    /** peer domain → rooms that peer has advertised as federatable. */
    private final ConcurrentHashMap<String, List<FederatedRoom>> remoteRooms = new ConcurrentHashMap<>();

    /** origin domain → which direct S2S neighbor last relayed that origin's rooms to us. */
    private final ConcurrentHashMap<String, String> roomRelaySource = new ConcurrentHashMap<>();

    /** localRoomJid → list of confirmed mappings (one per remote domain). */
    private final ConcurrentHashMap<String, List<RoomMapping>> localMappings = new ConcurrentHashMap<>();

    /** remoteRoomJid → mapping (reverse index for fast lookups). */
    private final ConcurrentHashMap<String, RoomMapping> remoteMappings = new ConcurrentHashMap<>();

    /** localRoomJid → remoteDomain → set of virtual nicks currently injected into local sessions. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Set<String>>> virtualOccupants
        = new ConcurrentHashMap<>();

    public void load() {
        // Load federated room tags
        String index = JiveGlobals.getProperty(PROP_ROOMS_INDEX, "").strip();
        if (!index.isEmpty()) {
            for (String jid : index.split(",")) {
                jid = jid.strip();
                if (!jid.isEmpty() && JiveGlobals.getBooleanProperty(PROP_ROOM_PREFIX + jid, false)) {
                    localFederatedRooms.add(jid);
                    Log.info("Loaded federated room: {}", jid);
                }
            }
        }

        // Load room mappings — supports new count-indexed format and migrates legacy single-mapping format.
        String mapIndex = JiveGlobals.getProperty(PROP_MAP_INDEX, "").strip();
        if (!mapIndex.isEmpty()) {
            for (String localJid : mapIndex.split(",")) {
                localJid = localJid.strip();
                if (localJid.isEmpty()) continue;

                String countStr = JiveGlobals.getProperty(String.format(PROP_MAP_COUNT, localJid));
                if (countStr != null) {
                    try {
                        int count = Integer.parseInt(countStr);
                        for (int i = 0; i < count; i++) {
                            String remoteJid    = JiveGlobals.getProperty(String.format(PROP_MAP_REMOTE, localJid, i));
                            String remoteDomain = JiveGlobals.getProperty(String.format(PROP_MAP_DOMAIN, localJid, i));
                            if (remoteJid != null && remoteDomain != null) {
                                RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain);
                                localMappings.computeIfAbsent(localJid, k -> new ArrayList<>()).add(m);
                                remoteMappings.put(remoteJid, m);
                                Log.info("Loaded room mapping: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
                            }
                        }
                    } catch (NumberFormatException e) {
                        Log.warn("Invalid mapping count for {}: {}", localJid, countStr);
                    }
                } else {
                    // Legacy single-mapping format — migrate on load.
                    String remoteJid    = JiveGlobals.getProperty("federation.rooms.mapping." + localJid + ".remote");
                    String remoteDomain = JiveGlobals.getProperty("federation.rooms.mapping." + localJid + ".domain");
                    if (remoteJid != null && remoteDomain != null) {
                        RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain);
                        localMappings.computeIfAbsent(localJid, k -> new ArrayList<>()).add(m);
                        remoteMappings.put(remoteJid, m);
                        Log.info("Migrating legacy mapping: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
                        persistMappingsForRoom(localJid);
                        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".remote");
                        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".domain");
                    }
                }
            }
        }

        // Load persisted virtual occupant tracker so eviction works after plugin reload.
        String voccRooms = JiveGlobals.getProperty(PROP_VOCC_ROOMS, "").strip();
        if (!voccRooms.isEmpty()) {
            for (String localJid : voccRooms.split(",")) {
                localJid = localJid.strip();
                if (localJid.isEmpty()) continue;
                String domains = JiveGlobals.getProperty(
                    String.format(PROP_VOCC_DOMAINS, localJid), "").strip();
                if (domains.isEmpty()) continue;
                for (String domain : domains.split(",")) {
                    domain = domain.strip();
                    if (domain.isEmpty()) continue;
                    String nicks = JiveGlobals.getProperty(
                        String.format(PROP_VOCC_NICKS, localJid, domain), "").strip();
                    if (nicks.isEmpty()) continue;
                    ConcurrentHashMap<String, Set<String>> byDomain =
                        virtualOccupants.computeIfAbsent(localJid, k -> new ConcurrentHashMap<>());
                    Set<String> nickSet = byDomain.computeIfAbsent(domain, k -> ConcurrentHashMap.newKeySet());
                    for (String nick : nicks.split(",")) {
                        nick = nick.strip();
                        if (!nick.isEmpty()) nickSet.add(nick);
                    }
                    Log.info("Loaded {} virtual occupant(s) in {} from {}", nickSet.size(), localJid, domain);
                }
            }
        }
    }

    // ── Federation tags ───────────────────────────────────────────────────────

    public void setFederated(String roomJid, boolean federated) {
        if (federated) {
            localFederatedRooms.add(roomJid);
        } else {
            localFederatedRooms.remove(roomJid);
        }
        JiveGlobals.setProperty(PROP_ROOM_PREFIX + roomJid, String.valueOf(federated));
        persistRoomsIndex();
        Log.info("Room {} federation tag → {}", roomJid, federated);
    }

    public boolean isFederated(String roomJid) {
        return localFederatedRooms.contains(roomJid);
    }

    public Set<String> getLocalFederatedRoomJids() {
        return Collections.unmodifiableSet(localFederatedRooms);
    }

    public List<FederatedRoom> getLocalFederatedRoomsWithDetails() {
        MultiUserChatManager mucMgr = XMPPServer.getInstance().getMultiUserChatManager();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        List<FederatedRoom> result = new ArrayList<>();

        for (String jid : localFederatedRooms) {
            String[] parts = jid.split("@", 2);
            if (parts.length != 2) continue;
            String roomName      = parts[0];
            String serviceDomain = parts[1];
            try {
                MUCRoom room = null;
                for (MultiUserChatService svc : mucMgr.getMultiUserChatServices()) {
                    if (svc.getServiceDomain().equals(serviceDomain)) {
                        room = svc.getChatRoom(roomName);
                        break;
                    }
                }
                if (room == null) continue;
                result.add(new FederatedRoom(jid, room.getNaturalLanguageName(),
                                             room.getDescription(), localDomain));
            } catch (Exception e) {
                Log.warn("Could not read room details for {}: {}", jid, e.getMessage());
            }
        }
        return result;
    }

    public List<Map<String, Object>> getAllLocalRoomsWithTag() {
        MultiUserChatManager mucMgr = XMPPServer.getInstance().getMultiUserChatManager();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MultiUserChatService svc : mucMgr.getMultiUserChatServices()) {
            for (MUCRoom room : svc.getActiveChatRooms()) {
                String jid = room.getName() + "@" + svc.getServiceDomain();
                List<RoomMapping> mappings = localMappings.getOrDefault(jid, Collections.emptyList());

                List<Map<String, String>> mappingsList = mappings.stream()
                    .map(m -> {
                        Map<String, String> e = new LinkedHashMap<>();
                        e.put("remoteRoomJid", m.remoteRoomJid());
                        e.put("remoteDomain",  m.remoteDomain());
                        return e;
                    })
                    .collect(Collectors.toList());

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jid",         jid);
                entry.put("name",        room.getNaturalLanguageName());
                entry.put("description", room.getDescription());
                entry.put("federated",   localFederatedRooms.contains(jid));
                entry.put("occupants",   room.getOccupantsCount());
                entry.put("mappings",    mappingsList);
                result.add(entry);
            }
        }
        return result;
    }

    // ── Room mappings ─────────────────────────────────────────────────────────

    /**
     * Adds (or replaces) a mapping from localJid toward remoteDomain.
     * One local room can have at most one mapping per remote domain.
     */
    public void addMapping(String localJid, String remoteJid, String remoteDomain) {
        List<RoomMapping> list = localMappings.computeIfAbsent(localJid, k -> new ArrayList<>());

        // Remove any existing mapping to the same remote domain before adding the new one.
        list.removeIf(m -> {
            if (m.remoteDomain().equals(remoteDomain)) {
                remoteMappings.remove(m.remoteRoomJid());
                return true;
            }
            return false;
        });

        RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain);
        list.add(m);
        remoteMappings.put(remoteJid, m);

        persistMappingsForRoom(localJid);
        persistMappingsIndex();
        Log.info("Room mapping added: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
    }

    /** Removes ALL mappings for a local room. */
    public void removeMapping(String localJid) {
        List<RoomMapping> removed = localMappings.remove(localJid);
        if (removed != null) {
            removed.forEach(m -> remoteMappings.remove(m.remoteRoomJid()));
            clearPersistedMappings(localJid);
            persistMappingsIndex();
            Log.info("All room mappings removed for: {}", localJid);
        }
    }

    /** Removes only the mapping from localJid toward remoteDomain. Returns true if found. */
    public boolean removeMapping(String localJid, String remoteDomain) {
        List<RoomMapping> list = localMappings.get(localJid);
        if (list == null) return false;

        Optional<RoomMapping> toRemove = list.stream()
            .filter(m -> m.remoteDomain().equals(remoteDomain))
            .findFirst();
        if (toRemove.isEmpty()) return false;

        list.remove(toRemove.get());
        remoteMappings.remove(toRemove.get().remoteRoomJid());

        if (list.isEmpty()) {
            localMappings.remove(localJid);
            clearPersistedMappings(localJid);
            persistMappingsIndex();
        } else {
            persistMappingsForRoom(localJid);
        }
        Log.info("Room mapping removed: {} → {} ({})", localJid, toRemove.get().remoteRoomJid(), remoteDomain);
        return true;
    }

    /** All mappings for a local room (empty list if none). Never null. */
    public List<RoomMapping> getMappingsForLocal(String localJid) {
        List<RoomMapping> list = localMappings.get(localJid);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /** The mapping from localJid toward a specific remoteDomain, or null. */
    public RoomMapping getMappingForLocal(String localJid, String remoteDomain) {
        return getMappingsForLocal(localJid).stream()
            .filter(m -> m.remoteDomain().equals(remoteDomain))
            .findFirst().orElse(null);
    }

    /** Reverse lookup: find the mapping whose remote room JID matches, or null. */
    public RoomMapping getMappingForRemote(String remoteJid) {
        return remoteMappings.get(remoteJid);
    }

    public Map<String, List<RoomMapping>> getLocalMappings() {
        Map<String, List<RoomMapping>> result = new LinkedHashMap<>();
        localMappings.forEach((k, v) -> result.put(k, Collections.unmodifiableList(v)));
        return Collections.unmodifiableMap(result);
    }

    // ── Virtual occupant tracking ─────────────────────────────────────────────

    public void trackVirtualOccupant(String localRoomJid, String remoteDomain, String virtualNick) {
        virtualOccupants.computeIfAbsent(localRoomJid, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(remoteDomain, k -> ConcurrentHashMap.newKeySet())
                        .add(virtualNick);
        persistVirtualOccupants(localRoomJid, remoteDomain);
    }

    public void untrackVirtualOccupant(String localRoomJid, String remoteDomain, String virtualNick) {
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null) return;
        Set<String> nicks = byDomain.get(remoteDomain);
        if (nicks == null) return;
        nicks.remove(virtualNick);
        if (nicks.isEmpty()) {
            byDomain.remove(remoteDomain, nicks);
            if (byDomain.isEmpty()) virtualOccupants.remove(localRoomJid, byDomain);
            clearPersistedVirtualOccupants(localRoomJid, remoteDomain);
        } else {
            persistVirtualOccupants(localRoomJid, remoteDomain);
        }
    }


    /**
     * Snapshot of virtual occupants in a room, grouped by source domain.  Used to
     * forward the users reached through a hub to a newly-mapped spoke so it sees
     * everyone immediately, not just the hub's directly-connected clients.  Never null.
     */
    public Map<String, Set<String>> getVirtualOccupantsByDomain(String localRoomJid) {
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null) return Collections.emptyMap();
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        byDomain.forEach((d, nicks) -> copy.put(d, new HashSet<>(nicks)));
        return copy;
    }

    /** All local room JIDs that currently have any virtual occupants tracked. */
    public Set<String> getRoomsWithAnyVirtualOccupants() {
        return new java.util.HashSet<>(virtualOccupants.keySet());
    }

    /**
     * Removes and returns virtual nicks in a room whose HOME domain (from the nick,
     * which is "user@home") is originDomain — across every tracked sender-domain group.
     * Used to drop a server's clients when its route is lost, even when they reached us
     * through a relay (and are therefore tracked under the relay's domain, not the origin).
     */
    public Set<String> clearVirtualOccupantsByOrigin(String localRoomJid, String originDomain) {
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null) return Collections.emptySet();
        Set<String> removed = new java.util.HashSet<>();
        Set<String> emptied = new java.util.HashSet<>();
        for (Map.Entry<String, Set<String>> e : byDomain.entrySet()) {
            String tracked = e.getKey();
            Set<String> nicks = e.getValue();
            Set<String> toRemove = new java.util.HashSet<>();
            for (String nick : nicks) {
                if (originDomain.equals(homeOf(nick, tracked))) toRemove.add(nick);
            }
            if (toRemove.isEmpty()) continue;
            nicks.removeAll(toRemove);
            removed.addAll(toRemove);
            if (nicks.isEmpty()) emptied.add(tracked);
            else persistVirtualOccupants(localRoomJid, tracked);
        }
        for (String d : emptied) {
            byDomain.remove(d);
            clearPersistedVirtualOccupants(localRoomJid, d);
        }
        if (byDomain.isEmpty()) virtualOccupants.remove(localRoomJid);
        return removed;
    }

    /** Home domain encoded in a virtual nick ("user@home"); falls back to the tracked domain. */
    private String homeOf(String nick, String fallback) {
        try {
            String d = new JID(nick).getDomain();
            return (d == null || d.isEmpty()) ? fallback : d;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Removes and returns all virtual nicks tracked for (localRoomJid, remoteDomain). */
    public Set<String> clearVirtualOccupants(String localRoomJid, String remoteDomain) {
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null) return Collections.emptySet();
        Set<String> removed = byDomain.remove(remoteDomain);
        if (removed == null) return Collections.emptySet();
        if (byDomain.isEmpty()) virtualOccupants.remove(localRoomJid, byDomain);
        clearPersistedVirtualOccupants(localRoomJid, remoteDomain);
        return removed;
    }

    // ── Remote room list ──────────────────────────────────────────────────────

    public void updateRemoteRooms(String sourceDomain, String fromDomain, List<FederatedRoom> rooms) {
        remoteRooms.put(sourceDomain, Collections.unmodifiableList(new ArrayList<>(rooms)));
        roomRelaySource.put(sourceDomain, fromDomain);
    }

    public void clearRemoteRooms(String peerDomain) {
        // Clear rooms that originated directly from this peer.
        remoteRooms.remove(peerDomain);
        roomRelaySource.remove(peerDomain);
        // Also clear rooms that were relayed TO us through this peer — they're now unreachable.
        roomRelaySource.entrySet().removeIf(e -> {
            if (peerDomain.equals(e.getValue())) {
                remoteRooms.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    public Map<String, List<FederatedRoom>> getRemoteRooms() {
        return Collections.unmodifiableMap(remoteRooms);
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private void persistRoomsIndex() {
        JiveGlobals.setProperty(PROP_ROOMS_INDEX, String.join(",", localFederatedRooms));
    }

    private void persistMappingsIndex() {
        JiveGlobals.setProperty(PROP_MAP_INDEX, String.join(",", localMappings.keySet()));
    }

    private void persistMappingsForRoom(String localJid) {
        clearPersistedMappings(localJid);
        List<RoomMapping> list = localMappings.get(localJid);
        if (list == null || list.isEmpty()) return;
        JiveGlobals.setProperty(String.format(PROP_MAP_COUNT, localJid), String.valueOf(list.size()));
        for (int i = 0; i < list.size(); i++) {
            JiveGlobals.setProperty(String.format(PROP_MAP_REMOTE, localJid, i), list.get(i).remoteRoomJid());
            JiveGlobals.setProperty(String.format(PROP_MAP_DOMAIN, localJid, i), list.get(i).remoteDomain());
        }
    }

    private void clearPersistedMappings(String localJid) {
        String countStr = JiveGlobals.getProperty(String.format(PROP_MAP_COUNT, localJid));
        if (countStr != null) {
            try {
                int count = Integer.parseInt(countStr);
                for (int i = 0; i < count; i++) {
                    JiveGlobals.deleteProperty(String.format(PROP_MAP_REMOTE, localJid, i));
                    JiveGlobals.deleteProperty(String.format(PROP_MAP_DOMAIN, localJid, i));
                }
            } catch (NumberFormatException ignored) {}
            JiveGlobals.deleteProperty(String.format(PROP_MAP_COUNT, localJid));
        }
        // Also sweep legacy keys so they don't re-appear after a downgrade.
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".remote");
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".domain");
    }

    // ── Virtual occupant persistence ──────────────────────────────────────────

    private void persistVirtualOccupants(String localRoomJid, String remoteDomain) {
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null) return;
        Set<String> nicks = byDomain.get(remoteDomain);
        if (nicks == null || nicks.isEmpty()) {
            clearPersistedVirtualOccupants(localRoomJid, remoteDomain);
            return;
        }
        // Persist nicks for this (room, domain) pair.
        JiveGlobals.setProperty(String.format(PROP_VOCC_NICKS, localRoomJid, remoteDomain),
                                String.join(",", nicks));
        // Keep the domains list up-to-date.
        String domainsKey = String.format(PROP_VOCC_DOMAINS, localRoomJid);
        Set<String> allDomains = new LinkedHashSet<>(byDomain.keySet());
        JiveGlobals.setProperty(domainsKey, String.join(",", allDomains));
        // Keep the rooms index up-to-date.
        JiveGlobals.setProperty(PROP_VOCC_ROOMS, String.join(",", virtualOccupants.keySet()));
    }

    private void clearPersistedVirtualOccupants(String localRoomJid, String remoteDomain) {
        JiveGlobals.deleteProperty(String.format(PROP_VOCC_NICKS, localRoomJid, remoteDomain));
        // Rebuild domains list from current in-memory state.
        ConcurrentHashMap<String, Set<String>> byDomain = virtualOccupants.get(localRoomJid);
        if (byDomain == null || byDomain.isEmpty()) {
            JiveGlobals.deleteProperty(String.format(PROP_VOCC_DOMAINS, localRoomJid));
            // Rebuild rooms index without this room.
            Set<String> rooms = new LinkedHashSet<>(virtualOccupants.keySet());
            if (rooms.isEmpty()) {
                JiveGlobals.deleteProperty(PROP_VOCC_ROOMS);
            } else {
                JiveGlobals.setProperty(PROP_VOCC_ROOMS, String.join(",", rooms));
            }
        } else {
            JiveGlobals.setProperty(String.format(PROP_VOCC_DOMAINS, localRoomJid),
                                    String.join(",", byDomain.keySet()));
        }
    }
}
