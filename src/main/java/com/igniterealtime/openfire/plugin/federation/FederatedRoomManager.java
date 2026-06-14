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

    /** Room JIDs (room@conference.domain) tagged locally as federatable. */
    private final Set<String> localFederatedRooms = ConcurrentHashMap.newKeySet();

    /** peer domain → rooms that peer has advertised as federatable. */
    private final ConcurrentHashMap<String, List<FederatedRoom>> remoteRooms = new ConcurrentHashMap<>();

    /** localRoomJid → list of confirmed mappings (one per remote domain). */
    private final ConcurrentHashMap<String, List<RoomMapping>> localMappings = new ConcurrentHashMap<>();

    /** remoteRoomJid → mapping (reverse index for fast lookups). */
    private final ConcurrentHashMap<String, RoomMapping> remoteMappings = new ConcurrentHashMap<>();

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

    // ── Remote room list ──────────────────────────────────────────────────────

    public void updateRemoteRooms(String sourceDomain, List<FederatedRoom> rooms) {
        remoteRooms.put(sourceDomain, Collections.unmodifiableList(new ArrayList<>(rooms)));
    }

    public void clearRemoteRooms(String peerDomain) {
        remoteRooms.remove(peerDomain);
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
}
