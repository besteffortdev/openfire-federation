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

/**
 * Tracks which local MUC rooms are tagged as "federatable", which rooms
 * remote peers have advertised, and the confirmed bilateral room mappings.
 *
 * Persistence: federation tags and room mappings are stored in JiveGlobals so
 * they survive plugin restarts.  Remote room lists are ephemeral — rebuilt from
 * gossip on reconnect.
 */
public class FederatedRoomManager {

    private static final Logger Log = LoggerFactory.getLogger(FederatedRoomManager.class);

    private static final String PROP_ROOMS_INDEX   = "federation.rooms.index";
    private static final String PROP_ROOM_PREFIX   = "federation.room.federated.";
    private static final String PROP_MAP_INDEX     = "federation.rooms.mappings.index";
    private static final String PROP_MAP_REMOTE    = "federation.rooms.mapping.%s.remote";
    private static final String PROP_MAP_DOMAIN    = "federation.rooms.mapping.%s.domain";

    /** Room JIDs (room@conference.domain) that are tagged locally as federatable. */
    private final Set<String> localFederatedRooms = ConcurrentHashMap.newKeySet();

    /** peer domain → rooms that peer has advertised as federatable. */
    private final ConcurrentHashMap<String, List<FederatedRoom>> remoteRooms = new ConcurrentHashMap<>();

    /** localRoomJid → confirmed bilateral mapping */
    private final ConcurrentHashMap<String, RoomMapping> localMappings = new ConcurrentHashMap<>();
    /** remoteRoomJid → mapping (reverse index for fast lookups from the other direction) */
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

        // Load room mappings
        String mapIndex = JiveGlobals.getProperty(PROP_MAP_INDEX, "").strip();
        if (!mapIndex.isEmpty()) {
            for (String localJid : mapIndex.split(",")) {
                localJid = localJid.strip();
                if (localJid.isEmpty()) continue;
                String remoteJid    = JiveGlobals.getProperty(String.format(PROP_MAP_REMOTE, localJid));
                String remoteDomain = JiveGlobals.getProperty(String.format(PROP_MAP_DOMAIN, localJid));
                if (remoteJid != null && remoteDomain != null) {
                    RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain);
                    localMappings.put(localJid, m);
                    remoteMappings.put(remoteJid, m);
                    Log.info("Loaded room mapping: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
                }
            }
        }
    }

    /**
     * Tags or un-tags a local room as federatable.
     * Callers should gossip the updated room list after calling this.
     */
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

    /**
     * Returns FederatedRoom descriptors for all locally-tagged rooms,
     * enriched with name/description pulled from the live MUC service.
     */
    public List<FederatedRoom> getLocalFederatedRoomsWithDetails() {
        MultiUserChatManager mucMgr = XMPPServer.getInstance().getMultiUserChatManager();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        List<FederatedRoom> result = new ArrayList<>();

        for (String jid : localFederatedRooms) {
            String[] parts = jid.split("@", 2);
            if (parts.length != 2) continue;
            String roomName     = parts[0];
            String serviceDomain = parts[1];
            try {
                // getMultiUserChatService(String) takes the service name ("conference"),
                // not the full domain ("conference.server3") — iterate to match by domain.
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

    /**
     * Returns all local MUC rooms (federated or not) with their current federation
     * tag and mapping info, used to populate the admin UI room list.
     */
    public List<Map<String, Object>> getAllLocalRoomsWithTag() {
        MultiUserChatManager mucMgr = XMPPServer.getInstance().getMultiUserChatManager();
        List<Map<String, Object>> result = new ArrayList<>();
        for (MultiUserChatService svc : mucMgr.getMultiUserChatServices()) {
            for (MUCRoom room : svc.getActiveChatRooms()) {
                String jid = room.getName() + "@" + svc.getServiceDomain();
                RoomMapping mapping = localMappings.get(jid);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jid",           jid);
                entry.put("name",          room.getNaturalLanguageName());
                entry.put("description",   room.getDescription());
                entry.put("federated",     localFederatedRooms.contains(jid));
                entry.put("occupants",     room.getOccupantsCount());
                entry.put("mappedTo",      mapping != null ? mapping.remoteRoomJid() : null);
                entry.put("mappedDomain",  mapping != null ? mapping.remoteDomain()  : null);
                result.add(entry);
            }
        }
        return result;
    }

    // ── Room mappings ─────────────────────────────────────────────────────────

    /**
     * Stores a confirmed bilateral room mapping and persists it.
     * Replaces any existing mapping for the same local room.
     */
    public void addMapping(String localJid, String remoteJid, String remoteDomain) {
        RoomMapping old = localMappings.get(localJid);
        if (old != null) remoteMappings.remove(old.remoteRoomJid());

        RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain);
        localMappings.put(localJid, m);
        remoteMappings.put(remoteJid, m);

        JiveGlobals.setProperty(String.format(PROP_MAP_REMOTE, localJid), remoteJid);
        JiveGlobals.setProperty(String.format(PROP_MAP_DOMAIN, localJid), remoteDomain);
        persistMappingsIndex();
        Log.info("Room mapping added: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
    }

    /** Removes the mapping for localJid (both directions). */
    public void removeMapping(String localJid) {
        RoomMapping m = localMappings.remove(localJid);
        if (m != null) {
            remoteMappings.remove(m.remoteRoomJid());
            JiveGlobals.deleteProperty(String.format(PROP_MAP_REMOTE, localJid));
            JiveGlobals.deleteProperty(String.format(PROP_MAP_DOMAIN, localJid));
            persistMappingsIndex();
            Log.info("Room mapping removed: {}", localJid);
        }
    }

    /** Returns the mapping for a local room, or null if none. */
    public RoomMapping getMappingForLocal(String localJid) {
        return localMappings.get(localJid);
    }

    /** Returns the mapping whose remote side matches remoteJid, or null if none. */
    public RoomMapping getMappingForRemote(String remoteJid) {
        return remoteMappings.get(remoteJid);
    }

    /** Snapshot of all local mappings keyed by local room JID. */
    public Map<String, RoomMapping> getLocalMappings() {
        return Collections.unmodifiableMap(localMappings);
    }

    // ── Remote room list ──────────────────────────────────────────────────────

    /** Called when gossip arrives from a peer (direct or relayed). */
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
}
