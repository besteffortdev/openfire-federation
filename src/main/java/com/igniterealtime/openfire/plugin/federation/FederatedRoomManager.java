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
    private static final String PROP_VOCC_NICKS   = "federation.vocc.%s.nicks";   // room → nick list
    private static final String PROP_VOCC_ENTRY   = "federation.vocc.%s.occ.%s";  // (room, nick) → "origin|arrivedVia"
    // Legacy (pre-1.3.20) virtual-occupant keys — read once on load, then swept.
    private static final String PROP_VOCC_DOMAINS_LEGACY = "federation.vocc.%s.domains";
    private static final String PROP_VOCC_NICKS_LEGACY   = "federation.vocc.%s.%s";

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

    /**
     * One injected remote user in a local room.
     *
     * <p>{@code origin} is the user's HOME domain (where they really live);
     * {@code arrivedVia} is the immediate S2S neighbour we received them from
     * (which, on a multi-hop path, is a relay — NOT the origin).  Keeping both
     * first-class removes the long-standing "tracked under the immediate sender,
     * not the true origin" trap: reachability events evict by {@code origin},
     * mapping/topology events evict by {@code arrivedVia}, with no nick parsing.
     *
     * <p>{@code nick} is the MUC nick ("user@home") and is unique within a room.
     */
    public record VirtualOccupant(String nick, String origin, String arrivedVia) {}

    /** localRoomJid → nick → VirtualOccupant currently injected into local sessions. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, VirtualOccupant>> virtualOccupants
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
                if (loadVirtualOccupantsForRoom(localJid)) continue;   // new format present
                migrateLegacyVirtualOccupants(localJid);              // fall back to pre-1.3.20 format
            }
            // Rewrite the index/props in the new format so legacy keys don't linger.
            persistVirtualOccupantRoomsIndex();
        }
    }

    /** Loads (room → nick → VirtualOccupant) from the 1.3.20+ persistence format. */
    private boolean loadVirtualOccupantsForRoom(String localJid) {
        String nicks = JiveGlobals.getProperty(String.format(PROP_VOCC_NICKS, localJid), "").strip();
        if (nicks.isEmpty()) return false;
        ConcurrentHashMap<String, VirtualOccupant> byNick =
            virtualOccupants.computeIfAbsent(localJid, k -> new ConcurrentHashMap<>());
        for (String nick : nicks.split(",")) {
            nick = nick.strip();
            if (nick.isEmpty()) continue;
            String entry = JiveGlobals.getProperty(String.format(PROP_VOCC_ENTRY, localJid, nick), "").strip();
            String[] p = entry.split("\\|", 2);
            String origin     = (p.length > 0 && !p[0].isEmpty()) ? p[0] : homeOf(nick, nick);
            String arrivedVia = (p.length > 1 && !p[1].isEmpty()) ? p[1] : origin;
            byNick.put(nick, new VirtualOccupant(nick, origin, arrivedVia));
        }
        if (byNick.isEmpty()) { virtualOccupants.remove(localJid); return false; }
        Log.info("Loaded {} virtual occupant(s) in {}", byNick.size(), localJid);
        return true;
    }

    /** Imports the pre-1.3.20 (room → domain → nicks) format, then deletes the legacy keys. */
    private void migrateLegacyVirtualOccupants(String localJid) {
        String domains = JiveGlobals.getProperty(
            String.format(PROP_VOCC_DOMAINS_LEGACY, localJid), "").strip();
        if (domains.isEmpty()) return;
        for (String domain : domains.split(",")) {
            domain = domain.strip();
            if (domain.isEmpty()) continue;
            String nicks = JiveGlobals.getProperty(
                String.format(PROP_VOCC_NICKS_LEGACY, localJid, domain), "").strip();
            for (String nick : nicks.split(",")) {
                nick = nick.strip();
                if (nick.isEmpty()) continue;
                // Legacy keyed by the immediate sender; origin lives in the nick, arrivedVia = old key.
                virtualOccupants.computeIfAbsent(localJid, k -> new ConcurrentHashMap<>())
                                .put(nick, new VirtualOccupant(nick, homeOf(nick, domain), domain));
            }
            JiveGlobals.deleteProperty(String.format(PROP_VOCC_NICKS_LEGACY, localJid, domain));
        }
        JiveGlobals.deleteProperty(String.format(PROP_VOCC_DOMAINS_LEGACY, localJid));
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localJid);
        if (byNick != null && !byNick.isEmpty()) {
            persistVirtualOccupantsForRoom(localJid);
            Log.info("Migrated {} legacy virtual occupant(s) in {} to new format", byNick.size(), localJid);
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

    /**
     * Records a remote user injected into a local room.  {@code origin} is the user's
     * home domain; {@code arrivedVia} is the immediate S2S neighbour we got them from
     * (a relay on multi-hop paths).  Both are stored first-class so later eviction never
     * has to guess one from the other.
     */
    public void trackVirtualOccupant(String localRoomJid, String origin, String arrivedVia, String virtualNick) {
        virtualOccupants.computeIfAbsent(localRoomJid, k -> new ConcurrentHashMap<>())
                        .put(virtualNick, new VirtualOccupant(virtualNick, origin, arrivedVia));
        persistVirtualOccupantsForRoom(localRoomJid);
    }

    public void untrackVirtualOccupant(String localRoomJid, String virtualNick) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return;
        if (byNick.remove(virtualNick) == null) return;
        if (byNick.isEmpty()) {
            virtualOccupants.remove(localRoomJid, byNick);
            clearPersistedVirtualOccupantsForRoom(localRoomJid);
        } else {
            persistVirtualOccupantsForRoom(localRoomJid);
        }
    }

    /**
     * Snapshot of virtual occupants in a room.  Used to forward users reached through a hub
     * to a newly-mapped spoke so it sees everyone immediately, not just the hub's directly-
     * connected clients.  Each entry carries origin + arrivedVia for split-horizon.  Never null.
     */
    public Collection<VirtualOccupant> getVirtualOccupants(String localRoomJid) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptyList();
        return new ArrayList<>(byNick.values());
    }

    /** All local room JIDs that currently have any virtual occupants tracked. */
    public Set<String> getRoomsWithAnyVirtualOccupants() {
        return new HashSet<>(virtualOccupants.keySet());
    }

    /**
     * Removes and returns virtual nicks in a room whose ORIGIN (home) domain is originDomain.
     * Used to drop a server's clients when its route is lost, even when they reached us through
     * a relay — origin is stored explicitly, so no nick parsing or sender-domain guessing.
     */
    public Set<String> clearVirtualOccupantsByOrigin(String localRoomJid, String originDomain) {
        return removeMatching(localRoomJid, vo -> originDomain.equals(vo.origin()));
    }

    /**
     * Removes and returns virtual nicks in a room that ARRIVED VIA arrivedViaDomain (the
     * immediate neighbour).  Used when one mapping is torn down while others on the room
     * survive: drop only the occupants that came in through that link.
     */
    public Set<String> clearVirtualOccupantsByArrivedVia(String localRoomJid, String arrivedViaDomain) {
        return removeMatching(localRoomJid, vo -> arrivedViaDomain.equals(vo.arrivedVia()));
    }

    /**
     * Removes and returns ALL virtual nicks tracked in a room.  Used when a room's last
     * federation mapping is removed: every virtual occupant came in through that federation
     * and is now unreachable — including hub-relayed users.
     */
    public Set<String> clearAllVirtualOccupants(String localRoomJid) {
        return removeMatching(localRoomJid, vo -> true);
    }

    /** Removes occupants matching {@code pred}; returns their nicks and rewrites persistence. */
    private Set<String> removeMatching(String localRoomJid, java.util.function.Predicate<VirtualOccupant> pred) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptySet();
        Set<String> removed = new HashSet<>();
        for (VirtualOccupant vo : new ArrayList<>(byNick.values())) {
            if (pred.test(vo) && byNick.remove(vo.nick()) != null) removed.add(vo.nick());
        }
        if (removed.isEmpty()) return Collections.emptySet();
        if (byNick.isEmpty()) {
            virtualOccupants.remove(localRoomJid, byNick);
            clearPersistedVirtualOccupantsForRoom(localRoomJid);
        } else {
            persistVirtualOccupantsForRoom(localRoomJid);
        }
        return removed;
    }

    /** Home domain encoded in a virtual nick ("user@home"); falls back to the given default. */
    private String homeOf(String nick, String fallback) {
        try {
            String d = new JID(nick).getDomain();
            return (d == null || d.isEmpty()) ? fallback : d;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ── Remote room list ──────────────────────────────────────────────────────

    public void updateRemoteRooms(String sourceDomain, String fromDomain, List<FederatedRoom> rooms) {
        remoteRooms.put(sourceDomain, Collections.unmodifiableList(new ArrayList<>(rooms)));
        roomRelaySource.put(sourceDomain, fromDomain);
    }

    /**
     * Full clear for peer-down / unreachability: drops rooms that ORIGINATE from peerDomain
     * AND rooms that were merely relayed TO us THROUGH it (now unreachable).
     */
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

    /**
     * Withdrawal clear: drops ONLY the rooms that originate from originDomain, leaving rooms
     * merely relayed THROUGH it intact.  Used when a peer that is still up (and still relaying
     * others' rooms) stops federating its own room — clearing relay-learned rooms here would
     * wrongly wipe a hub-spoke's entire cache (everything came via the hub).
     */
    public void clearRemoteRoomsForOrigin(String originDomain) {
        // Drop the entry keyed directly by this origin.
        remoteRooms.remove(originDomain);
        roomRelaySource.remove(originDomain);
        // Belt-and-suspenders: a multi-hop advertisement can end up cached under the
        // immediate sender's (relay's) domain rather than the true origin, so a key-only
        // remove would miss it — leaving the room still showing as "available for mapping"
        // on distant nodes while direct spokes (keyed by origin) clear correctly. Also drop
        // any room whose RECORDED origin matches, regardless of the key it was filed under,
        // and remove now-empty cache entries.
        remoteRooms.entrySet().removeIf(e -> {
            List<FederatedRoom> kept = e.getValue().stream()
                    .filter(r -> !originDomain.equals(r.originServer()))
                    .collect(Collectors.toList());
            if (kept.size() == e.getValue().size()) return false;   // nothing from this origin here
            if (kept.isEmpty()) {
                roomRelaySource.remove(e.getKey());
                return true;
            }
            e.setValue(Collections.unmodifiableList(kept));
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

    /** Writes every occupant of one room (nick list + per-nick origin|arrivedVia) and the rooms index. */
    private void persistVirtualOccupantsForRoom(String localRoomJid) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null || byNick.isEmpty()) {
            clearPersistedVirtualOccupantsForRoom(localRoomJid);
            return;
        }
        JiveGlobals.setProperty(String.format(PROP_VOCC_NICKS, localRoomJid),
                                String.join(",", byNick.keySet()));
        for (VirtualOccupant vo : byNick.values()) {
            JiveGlobals.setProperty(String.format(PROP_VOCC_ENTRY, localRoomJid, vo.nick()),
                                    vo.origin() + "|" + vo.arrivedVia());
        }
        persistVirtualOccupantRoomsIndex();
    }

    /** Deletes all persisted state for one room and refreshes the rooms index. */
    private void clearPersistedVirtualOccupantsForRoom(String localRoomJid) {
        String nicks = JiveGlobals.getProperty(String.format(PROP_VOCC_NICKS, localRoomJid), "").strip();
        if (!nicks.isEmpty()) {
            for (String nick : nicks.split(",")) {
                nick = nick.strip();
                if (!nick.isEmpty()) JiveGlobals.deleteProperty(String.format(PROP_VOCC_ENTRY, localRoomJid, nick));
            }
        }
        JiveGlobals.deleteProperty(String.format(PROP_VOCC_NICKS, localRoomJid));
        persistVirtualOccupantRoomsIndex();
    }

    private void persistVirtualOccupantRoomsIndex() {
        Set<String> rooms = new LinkedHashSet<>(virtualOccupants.keySet());
        if (rooms.isEmpty()) JiveGlobals.deleteProperty(PROP_VOCC_ROOMS);
        else JiveGlobals.setProperty(PROP_VOCC_ROOMS, String.join(",", rooms));
    }
}
