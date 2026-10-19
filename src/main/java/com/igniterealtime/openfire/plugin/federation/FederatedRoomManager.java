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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Room state of the federation: which local rooms are tagged for federation and who may see them,
 * the rooms peers advertised, the room mappings, and the remote users injected into local rooms as
 * virtual occupants. Tags, mappings and virtual occupants are persisted in Openfire properties.
 */
public class FederatedRoomManager {

    private static final Logger Log = LoggerFactory.getLogger(FederatedRoomManager.class);

    private static final String PROP_ROOMS_INDEX = "federation.rooms.index";
    private static final String PROP_ROOM_PREFIX = "federation.room.federated.";
    private static final String PROP_ROOM_VISIBLE = "federation.room.visibleto.";
    private static final String PROP_VIS_MIGRATED = "federation.room.visibility.migrated";
    /** Visibility entry meaning "visible to every peer"; an empty visibility list means nobody. */
    public static final String VISIBLE_ALL = "*";
    private static final String PROP_MAP_INDEX   = "federation.rooms.mappings.index";
    private static final String PROP_MAP_COUNT   = "federation.rooms.mapping.%s.count";
    private static final String PROP_MAP_REMOTE  = "federation.rooms.mapping.%s.%d.remote";
    private static final String PROP_MAP_DOMAIN  = "federation.rooms.mapping.%s.%d.domain";
    private static final String PROP_MAP_STATE   = "federation.rooms.mapping.%s.%d.state";
    private static final String PROP_MAP_TOKEN   = "federation.rooms.mapping.%s.%d.token";
    private static final String PROP_ROOM_AUTOACCEPT = "federation.room.autoaccept.";
    private static final String PROP_ROOM_FILES = "federation.room.files.";

    private static final String PROP_VOCC_ROOMS   = "federation.vocc.rooms.index";
    private static final String PROP_VOCC_NICKS   = "federation.vocc.%s.nicks";
    private static final String PROP_VOCC_ENTRY   = "federation.vocc.%s.occ.%s";
    private static final String PROP_VOCC_DOMAINS_LEGACY = "federation.vocc.%s.domains";
    private static final String PROP_VOCC_NICKS_LEGACY   = "federation.vocc.%s.%s";

    /** Local room JIDs tagged for federation. */
    private final Set<String> localFederatedRooms = ConcurrentHashMap.newKeySet();

    /** Local room JID to the servers allowed to see it; a room with no entry is visible to nobody. */
    private final ConcurrentHashMap<String, Set<String>> roomVisibility = new ConcurrentHashMap<>();

    /** Origin domain to the rooms it advertised. */
    private final ConcurrentHashMap<String, List<FederatedRoom>> remoteRooms = new ConcurrentHashMap<>();

    /** Origin domain to the neighbour that last relayed its rooms to us. */
    private final ConcurrentHashMap<String, String> roomRelaySource = new ConcurrentHashMap<>();

    /**
     * Local room JID to its mappings, one per remote domain. Copy-on-write lists, because the
     * interceptor iterates them on every MUC packet while lifecycle changes are rare.
     */
    private final ConcurrentHashMap<String, List<RoomMapping>> localMappings = new ConcurrentHashMap<>();

    /** Remote room JID to its mapping. */
    private final ConcurrentHashMap<String, RoomMapping> remoteMappings = new ConcurrentHashMap<>();

    /**
     * A remote user injected into a local room: {@code origin} is the user's home server, and
     * {@code arrivedVia} holds every mapped server the user currently reaches us through.
     */
    public record VirtualOccupant(String nick, String origin, Set<String> arrivedVia) {}

    /** Local room JID to nick to the virtual occupants injected into it. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, VirtualOccupant>> virtualOccupants
        = new ConcurrentHashMap<>();

    /** Local room JID to nick to {@code "show|status"} of each virtual occupant; in memory only. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> virtualOccupantPresence
        = new ConcurrentHashMap<>();

    /** Records a virtual occupant's current show and status for the admin console. */
    public void setVirtualOccupantPresence(String localRoomJid, String nick, String show, String status) {
        virtualOccupantPresence
            .computeIfAbsent(localRoomJid, k -> new ConcurrentHashMap<>())
            .put(nick, (show == null ? "" : show) + "|" + (status == null ? "" : status));
    }

    /** Forgets a virtual occupant's presence on leave or eviction. */
    public void clearVirtualOccupantPresence(String localRoomJid, String nick) {
        ConcurrentHashMap<String, String> byNick = virtualOccupantPresence.get(localRoomJid);
        if (byNick != null) {
            byNick.remove(nick);
            if (byNick.isEmpty()) virtualOccupantPresence.remove(localRoomJid);
        }
    }

    /** Nick to {@code "show|status"} for a room's virtual occupants; empty if none. */
    public Map<String, String> getVirtualOccupantPresence(String localRoomJid) {
        ConcurrentHashMap<String, String> byNick = virtualOccupantPresence.get(localRoomJid);
        return byNick == null ? Collections.emptyMap() : new LinkedHashMap<>(byNick);
    }

    /** Loads the persisted tags, visibility lists, mappings and virtual occupants, migrating old formats. */
    public void load() {
        loadFederatedRooms();
        loadMappings();
        loadVirtualOccupants();
    }

    private void loadFederatedRooms() {
        // One-time migration: an empty list used to mean "everyone", so legacy rooms are pinned to "*".
        boolean migrated = JiveGlobals.getBooleanProperty(PROP_VIS_MIGRATED, false);
        for (String entry : JiveGlobals.getProperty(PROP_ROOMS_INDEX, "").strip().split(",")) {
            String jid = entry.strip();
            if (jid.isEmpty() || !JiveGlobals.getBooleanProperty(PROP_ROOM_PREFIX + jid, false)) continue;
            localFederatedRooms.add(jid);
            Set<String> vis = loadVisibility(jid, migrated);
            if (!vis.isEmpty()) roomVisibility.put(jid, vis);
            Log.info("Loaded federated room: {} (visible to {})", jid,
                     vis.isEmpty() ? "none" : (vis.contains(VISIBLE_ALL) ? "all" : vis.size() + " server(s)"));
        }
        if (!migrated) JiveGlobals.setProperty(PROP_VIS_MIGRATED, "true");
    }

    /** A room's persisted visibility list; before the migration has run, a missing list becomes {@code "*"}. */
    private static Set<String> loadVisibility(String jid, boolean migrated) {
        String raw = JiveGlobals.getProperty(PROP_ROOM_VISIBLE + jid, null);
        if (raw != null && !raw.isBlank()) return parseCsvSet(raw);
        if (migrated) return Collections.emptySet();
        JiveGlobals.setProperty(PROP_ROOM_VISIBLE + jid, VISIBLE_ALL);
        return new LinkedHashSet<>(List.of(VISIBLE_ALL));
    }

    private void loadMappings() {
        for (String entry : JiveGlobals.getProperty(PROP_MAP_INDEX, "").strip().split(",")) {
            String localJid = entry.strip();
            if (localJid.isEmpty()) continue;
            String countStr = JiveGlobals.getProperty(String.format(PROP_MAP_COUNT, localJid));
            if (countStr != null) {
                loadMappingsForRoom(localJid, countStr);
            } else {
                migrateLegacyMapping(localJid);
            }
        }
    }

    /** Loads one room's mappings; one saved before consent existed is re-saved as PENDING_OUT. */
    private void loadMappingsForRoom(String localJid, String countStr) {
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            Log.warn("Invalid mapping count for {}: {}", localJid, countStr);
            return;
        }
        boolean rewrite = false;
        for (int i = 0; i < count; i++) {
            String remoteJid    = JiveGlobals.getProperty(String.format(PROP_MAP_REMOTE, localJid, i));
            String remoteDomain = JiveGlobals.getProperty(String.format(PROP_MAP_DOMAIN, localJid, i));
            if (remoteJid == null || remoteDomain == null) continue;
            String stateStr = JiveGlobals.getProperty(String.format(PROP_MAP_STATE, localJid, i));
            String token    = JiveGlobals.getProperty(String.format(PROP_MAP_TOKEN, localJid, i), "");
            if (stateStr == null) rewrite = true;
            RoomMapping.State state = parseState(stateStr);
            addLoadedMapping(new RoomMapping(localJid, remoteJid, remoteDomain, state, token));
            Log.info("Loaded room mapping: {} ↔ {} ({}) [{}]", localJid, remoteJid, remoteDomain, state);
        }
        if (rewrite) persistMappingsForRoom(localJid);
    }

    /** A persisted mapping state; a missing or unknown one must be accepted again, so it reads as PENDING_OUT. */
    private static RoomMapping.State parseState(String stored) {
        if (stored == null) return RoomMapping.State.PENDING_OUT;
        try {
            return RoomMapping.State.valueOf(stored);
        } catch (IllegalArgumentException unknownState) {
            return RoomMapping.State.PENDING_OUT;
        }
    }

    /** Moves a mapping from the single-mapping format of early versions into the indexed one. */
    private void migrateLegacyMapping(String localJid) {
        String remoteJid    = JiveGlobals.getProperty("federation.rooms.mapping." + localJid + ".remote");
        String remoteDomain = JiveGlobals.getProperty("federation.rooms.mapping." + localJid + ".domain");
        if (remoteJid == null || remoteDomain == null) return;
        addLoadedMapping(new RoomMapping(localJid, remoteJid, remoteDomain));
        Log.info("Migrating legacy mapping: {} ↔ {} ({})", localJid, remoteJid, remoteDomain);
        persistMappingsForRoom(localJid);
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".remote");
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".domain");
    }

    private void addLoadedMapping(RoomMapping m) {
        localMappings.computeIfAbsent(m.localRoomJid(), k -> new CopyOnWriteArrayList<>()).add(m);
        remoteMappings.put(m.remoteRoomJid(), m);
    }

    private void loadVirtualOccupants() {
        // Virtual occupants are persisted so they can still be evicted after a plugin reload.
        String voccRooms = JiveGlobals.getProperty(PROP_VOCC_ROOMS, "").strip();
        if (!voccRooms.isEmpty()) {
            for (String localJid : voccRooms.split(",")) {
                localJid = localJid.strip();
                if (localJid.isEmpty()) continue;
                if (loadVirtualOccupantsForRoom(localJid)) continue;
                migrateLegacyVirtualOccupants(localJid);
            }
            persistVirtualOccupantRoomsIndex();
        }
    }

    /** Loads one room's virtual occupants from the current persistence format; false if it has none. */
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
            String origin = (p.length > 0 && !p[0].isEmpty()) ? p[0] : homeOf(nick, nick);
            Set<String> via = ConcurrentHashMap.newKeySet();
            if (p.length > 1 && !p[1].isEmpty()) {
                for (String d : p[1].split(";")) { d = d.strip(); if (!d.isEmpty()) via.add(d); }
            }
            if (via.isEmpty()) via.add(origin);
            byNick.put(nick, new VirtualOccupant(nick, origin, via));
        }
        if (byNick.isEmpty()) { virtualOccupants.remove(localJid); return false; }
        Log.info("Loaded {} virtual occupant(s) in {}", byNick.size(), localJid);
        return true;
    }

    /** Imports one room's virtual occupants from the legacy format, then deletes the legacy keys. */
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
                // A nick listed under several legacy domains gets all of them as paths.
                final String via = domain;
                virtualOccupants.computeIfAbsent(localJid, k -> new ConcurrentHashMap<>())
                                .compute(nick, (n, existing) -> {
                    if (existing == null) {
                        Set<String> set = ConcurrentHashMap.newKeySet();
                        set.add(via);
                        return new VirtualOccupant(n, homeOf(n, via), set);
                    }
                    existing.arrivedVia().add(via);
                    return existing;
                });
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

    /** Tags or untags a local room for federation; untagging also drops its visibility and auto-accept settings. */
    public void setFederated(String roomJid, boolean federated) {
        if (federated) {
            localFederatedRooms.add(roomJid);
        } else {
            localFederatedRooms.remove(roomJid);
            roomVisibility.remove(roomJid);
            JiveGlobals.deleteProperty(PROP_ROOM_VISIBLE + roomJid);
            JiveGlobals.deleteProperty(PROP_ROOM_AUTOACCEPT + roomJid);
        }
        JiveGlobals.setProperty(PROP_ROOM_PREFIX + roomJid, String.valueOf(federated));
        persistRoomsIndex();
        Log.info("Room {} federation tag → {}", roomJid, federated);
    }

    public boolean isFederated(String roomJid) {
        return localFederatedRooms.contains(roomJid);
    }

    /** The servers allowed to see {@code roomJid}; empty means nobody. */
    public Set<String> getRoomVisibility(String roomJid) {
        return roomVisibility.getOrDefault(roomJid, Collections.emptySet());
    }

    /** Replaces and persists a room's visibility list; an empty or null list hides the room from everyone. */
    public void setRoomVisibility(String roomJid, Collection<String> servers) {
        Set<String> set = (servers == null) ? Collections.emptySet()
                : servers.stream().filter(s -> s != null && !s.isBlank())
                         .map(s -> s.strip().toLowerCase())
                         .collect(Collectors.toCollection(LinkedHashSet::new));
        if (set.isEmpty()) {
            roomVisibility.remove(roomJid);
            JiveGlobals.deleteProperty(PROP_ROOM_VISIBLE + roomJid);
        } else {
            roomVisibility.put(roomJid, set);
            JiveGlobals.setProperty(PROP_ROOM_VISIBLE + roomJid, String.join(",", set));
        }
        Log.info("Room {} visibility → {}", roomJid, set.isEmpty() ? "no peers" : set);
    }

    /**
     * Whether a cached remote room's visibility list names this server or {@code "*"}, as opposed to
     * our merely relaying it on toward another server.
     */
    public boolean isVisibleToLocalDomain(FederatedRoom room) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        Set<String> vis = room.visibleTo();
        return vis.contains(VISIBLE_ALL) || vis.contains(localDomain);
    }

    public boolean isAutoAccept(String roomJid) {
        return JiveGlobals.getBooleanProperty(PROP_ROOM_AUTOACCEPT + roomJid, false);
    }

    public void setAutoAccept(String roomJid, boolean autoAccept) {
        if (autoAccept) {
            JiveGlobals.setProperty(PROP_ROOM_AUTOACCEPT + roomJid, "true");
        } else {
            JiveGlobals.deleteProperty(PROP_ROOM_AUTOACCEPT + roomJid);
        }
        Log.info("Room {} auto-accept → {}", roomJid, autoAccept);
    }

    /** Whether file shares cross the federation in this room; on by default, and when off a notice replaces them. */
    public boolean isFilesEnabled(String roomJid) {
        return JiveGlobals.getBooleanProperty(PROP_ROOM_FILES + roomJid, true);
    }

    public void setFilesEnabled(String roomJid, boolean enabled) {
        if (enabled) {
            JiveGlobals.deleteProperty(PROP_ROOM_FILES + roomJid);
        } else {
            JiveGlobals.setProperty(PROP_ROOM_FILES + roomJid, "false");
        }
        Log.info("Room {} file federation → {}", roomJid, enabled);
    }

    private static Set<String> parseCsvSet(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : csv.split(",")) {
                if (!s.isBlank()) out.add(s.strip().toLowerCase());
            }
        }
        return out;
    }

    /** Our federated rooms with their current name, description and visibility, for advertisements. */
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
                                             room.getDescription(), localDomain,
                                             getRoomVisibility(jid)));
            } catch (RuntimeException e) {
                Log.warn("Could not read room details for {}", jid, e);
            }
        }
        return result;
    }

    /** Every active local room with its federation settings and mappings, for the admin console. */
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
                        e.put("state",         m.state().name());
                        return e;
                    })
                    .collect(Collectors.toList());

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jid",         jid);
                entry.put("name",        room.getNaturalLanguageName());
                entry.put("description", room.getDescription());
                entry.put("federated",   localFederatedRooms.contains(jid));
                entry.put("visibleTo",   new ArrayList<>(getRoomVisibility(jid)));
                entry.put("autoAccept",  isAutoAccept(jid));
                entry.put("filesEnabled", isFilesEnabled(jid));
                entry.put("occupants",   room.getOccupantsCount());
                entry.put("mappings",    mappingsList);
                result.add(entry);
            }
        }
        return result;
    }

    /** Adds a mapping from {@code localJid} to {@code remoteDomain}, replacing any existing one to that domain. */
    public void addMapping(String localJid, String remoteJid, String remoteDomain) {
        addMapping(localJid, remoteJid, remoteDomain, RoomMapping.State.PENDING_OUT, "");
    }

    public void addMapping(String localJid, String remoteJid, String remoteDomain,
                           RoomMapping.State state, String token) {
        List<RoomMapping> list = localMappings.computeIfAbsent(localJid, k -> new CopyOnWriteArrayList<>());

        list.removeIf(m -> {
            if (m.remoteDomain().equals(remoteDomain)) {
                remoteMappings.remove(m.remoteRoomJid());
                return true;
            }
            return false;
        });

        RoomMapping m = new RoomMapping(localJid, remoteJid, remoteDomain, state, token);
        list.add(m);
        remoteMappings.put(remoteJid, m);

        persistMappingsForRoom(localJid);
        persistMappingsIndex();
        Log.info("Room mapping added: {} ↔ {} ({}) [{}]", localJid, remoteJid, remoteDomain, state);
    }

    /** Moves an existing mapping to a new state and token; returns the updated mapping, or null. */
    public RoomMapping setMappingState(String localJid, String remoteDomain,
                                       RoomMapping.State state, String token) {
        List<RoomMapping> list = localMappings.get(localJid);
        if (list == null) return null;
        for (int i = 0; i < list.size(); i++) {
            RoomMapping m = list.get(i);
            if (m.remoteDomain().equals(remoteDomain)) {
                RoomMapping updated = m.with(state, token != null ? token : m.token());
                list.set(i, updated);
                remoteMappings.put(updated.remoteRoomJid(), updated);
                persistMappingsForRoom(localJid);
                Log.info("Room mapping {} ({}) → {}", localJid, remoteDomain, state);
                return updated;
            }
        }
        return null;
    }

    /** Removes every mapping of a local room. */
    public void removeMapping(String localJid) {
        List<RoomMapping> removed = localMappings.remove(localJid);
        if (removed != null) {
            removed.forEach(m -> remoteMappings.remove(m.remoteRoomJid()));
            clearPersistedMappings(localJid);
            persistMappingsIndex();
            Log.info("All room mappings removed for: {}", localJid);
        }
    }

    /** Removes the mapping from {@code localJid} to {@code remoteDomain}; true if there was one. */
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

    /**
     * The ACTIVE mappings of a local room, the only ones that carry traffic. Use
     * {@link #getAllMappingsForLocal} for every state.
     */
    public List<RoomMapping> getMappingsForLocal(String localJid) {
        List<RoomMapping> list = localMappings.get(localJid);
        if (list == null) return Collections.emptyList();
        return list.stream().filter(RoomMapping::isActive).collect(Collectors.toList());
    }

    /** Every mapping of a local room, in any state; never null. */
    public List<RoomMapping> getAllMappingsForLocal(String localJid) {
        List<RoomMapping> list = localMappings.get(localJid);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /** Every mapping, across all rooms, in the given state. */
    public List<RoomMapping> getMappingsByState(RoomMapping.State state) {
        return localMappings.values().stream().flatMap(List::stream)
                .filter(m -> m.state() == state).collect(Collectors.toList());
    }

    /** The mapping from {@code localJid} to {@code remoteDomain} in any state, or null. */
    public RoomMapping getMappingForLocal(String localJid, String remoteDomain) {
        return getAllMappingsForLocal(localJid).stream()
            .filter(m -> m.remoteDomain().equals(remoteDomain))
            .findFirst().orElse(null);
    }

    /** The mapping whose remote room is {@code remoteJid}, or null. */
    public RoomMapping getMappingForRemote(String remoteJid) {
        return remoteMappings.get(remoteJid);
    }

    public Map<String, List<RoomMapping>> getLocalMappings() {
        Map<String, List<RoomMapping>> result = new LinkedHashMap<>();
        localMappings.forEach((k, v) -> result.put(k, Collections.unmodifiableList(v)));
        return Collections.unmodifiableMap(result);
    }

    /** Whether any local room has a mapping, in any state, with {@code remoteDomain}. */
    public boolean hasMappingWith(String remoteDomain) {
        return localMappings.values().stream()
            .anyMatch(list -> list.stream().anyMatch(m -> m.remoteDomain().equals(remoteDomain)));
    }

    /**
     * Records a remote user injected into a local room, or one more path for one already there.
     * Persists only when something changed, since presence updates call this on every status change.
     */
    public void trackVirtualOccupant(String localRoomJid, String origin, String arrivedVia, String virtualNick) {
        boolean newRoom = !virtualOccupants.containsKey(localRoomJid);
        ConcurrentHashMap<String, VirtualOccupant> byNick =
            virtualOccupants.computeIfAbsent(localRoomJid, k -> new ConcurrentHashMap<>());
        boolean[] changed = new boolean[1];
        VirtualOccupant vo = byNick.compute(virtualNick, (n, existing) -> {
            if (existing == null) {
                Set<String> via = ConcurrentHashMap.newKeySet();
                via.add(arrivedVia);
                changed[0] = true;
                return new VirtualOccupant(n, origin, via);
            }
            if (existing.arrivedVia().add(arrivedVia)) changed[0] = true;
            return existing;
        });
        if (!changed[0]) return;
        JiveGlobals.setProperty(String.format(PROP_VOCC_ENTRY, localRoomJid, vo.nick()), voccEntryValue(vo));
        JiveGlobals.setProperty(String.format(PROP_VOCC_NICKS, localRoomJid), String.join(",", byNick.keySet()));
        if (newRoom) persistVirtualOccupantRoomsIndex();
    }

    /** Removes a virtual occupant; true only if one was removed, so a leave is passed on exactly once. */
    public boolean untrackVirtualOccupant(String localRoomJid, String virtualNick) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return false;
        if (byNick.remove(virtualNick) == null) return false;
        JiveGlobals.deleteProperty(String.format(PROP_VOCC_ENTRY, localRoomJid, virtualNick));
        if (byNick.isEmpty()) {
            virtualOccupants.remove(localRoomJid, byNick);
            JiveGlobals.deleteProperty(String.format(PROP_VOCC_NICKS, localRoomJid));
            persistVirtualOccupantRoomsIndex();
        } else {
            JiveGlobals.setProperty(String.format(PROP_VOCC_NICKS, localRoomJid), String.join(",", byNick.keySet()));
        }
        return true;
    }

    /** A snapshot of a room's virtual occupants; never null. */
    public Collection<VirtualOccupant> getVirtualOccupants(String localRoomJid) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptyList();
        return new ArrayList<>(byNick.values());
    }

    /** Every local room that has virtual occupants. */
    public Set<String> getRoomsWithAnyVirtualOccupants() {
        return new HashSet<>(virtualOccupants.keySet());
    }

    /** Removes every virtual occupant of a room; returns their nicks. */
    public Set<String> clearAllVirtualOccupants(String localRoomJid) {
        return removeMatching(localRoomJid, vo -> true);
    }

    /** As {@link #clearVirtualOccupantsByOrigin}, but returns the occupants themselves. */
    public List<VirtualOccupant> removeVirtualOccupantsByOrigin(String localRoomJid, String originDomain) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptyList();
        List<VirtualOccupant> removed = new ArrayList<>();
        for (VirtualOccupant vo : new ArrayList<>(byNick.values())) {
            if (originDomain.equals(vo.origin()) && byNick.remove(vo.nick(), vo)) removed.add(vo);
        }
        if (removed.isEmpty()) return Collections.emptyList();
        afterRemoval(localRoomJid, byNick);
        return removed;
    }

    /** As {@link #clearVirtualOccupantsByArrivedVia}, but returns the occupants themselves. */
    public List<VirtualOccupant> removeVirtualOccupantsArrivedVia(String localRoomJid, String arrivedViaDomain) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptyList();
        List<VirtualOccupant> removed = new ArrayList<>();
        boolean changed = false;
        for (VirtualOccupant vo : new ArrayList<>(byNick.values())) {
            if (!vo.arrivedVia().remove(arrivedViaDomain)) continue;
            changed = true;
            if (vo.arrivedVia().isEmpty() && byNick.remove(vo.nick(), vo)) removed.add(vo);
        }
        if (changed) afterRemoval(localRoomJid, byNick);
        return removed;
    }

    /** Removes a room's virtual occupants that match {@code pred}; returns their nicks. */
    private Set<String> removeMatching(String localRoomJid, Predicate<VirtualOccupant> pred) {
        ConcurrentHashMap<String, VirtualOccupant> byNick = virtualOccupants.get(localRoomJid);
        if (byNick == null) return Collections.emptySet();
        Set<String> removed = new HashSet<>();
        for (VirtualOccupant vo : new ArrayList<>(byNick.values())) {
            if (pred.test(vo) && byNick.remove(vo.nick()) != null) removed.add(vo.nick());
        }
        if (removed.isEmpty()) return Collections.emptySet();
        afterRemoval(localRoomJid, byNick);
        return removed;
    }

    /** Re-persists a room after removals, or drops its entry and persisted state when it emptied. */
    private void afterRemoval(String localRoomJid, ConcurrentHashMap<String, VirtualOccupant> byNick) {
        if (byNick.isEmpty()) {
            virtualOccupants.remove(localRoomJid, byNick);
            clearPersistedVirtualOccupantsForRoom(localRoomJid);
        } else {
            persistVirtualOccupantsForRoom(localRoomJid);
        }
    }

    /** The home domain in a virtual nick ("user@home"), or {@code fallback}. */
    private String homeOf(String nick, String fallback) {
        try {
            String d = new JID(nick).getDomain();
            return (d == null || d.isEmpty()) ? fallback : d;
        } catch (IllegalArgumentException notAJid) {
            return fallback;
        }
    }

    /** Caches the rooms {@code sourceDomain} advertised and the neighbour they arrived from. */
    public void updateRemoteRooms(String sourceDomain, String fromDomain, List<FederatedRoom> rooms) {
        remoteRooms.put(sourceDomain, Collections.unmodifiableList(new ArrayList<>(rooms)));
        roomRelaySource.put(sourceDomain, fromDomain);
    }

    /** Drops the rooms that originate at {@code peerDomain} and the rooms relayed to us through it. */
    public void clearRemoteRooms(String peerDomain) {
        remoteRooms.remove(peerDomain);
        roomRelaySource.remove(peerDomain);
        roomRelaySource.entrySet().removeIf(e -> {
            if (peerDomain.equals(e.getValue())) {
                remoteRooms.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    /**
     * Drops only the rooms that originate at {@code originDomain}, wherever they are cached, and
     * keeps rooms relayed through it, because it is still up and relaying.
     */
    public void clearRemoteRoomsForOrigin(String originDomain) {
        remoteRooms.remove(originDomain);
        roomRelaySource.remove(originDomain);
        // A multi-hop advertisement can be cached under the relay's domain, so match on each room's origin too.
        remoteRooms.entrySet().removeIf(e -> {
            List<FederatedRoom> kept = e.getValue().stream()
                    .filter(r -> !originDomain.equals(r.originServer()))
                    .collect(Collectors.toList());
            if (kept.size() == e.getValue().size()) return false;
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

    /**
     * {@link #getRemoteRooms()} without the rooms we only relay toward another server, for the admin
     * console. Relaying itself must use the full cache.
     */
    public Map<String, List<FederatedRoom>> getRemoteRoomsVisibleToUs() {
        Map<String, List<FederatedRoom>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<FederatedRoom>> e : remoteRooms.entrySet()) {
            List<FederatedRoom> kept = e.getValue().stream()
                    .filter(this::isVisibleToLocalDomain)
                    .collect(Collectors.toList());
            if (!kept.isEmpty()) out.put(e.getKey(), kept);
        }
        return out;
    }

    /** The rooms advertised to us through the neighbour {@code peerDomain}, whatever their origin. */
    public List<FederatedRoom> getRemoteRoomsViaPeer(String peerDomain) {
        List<FederatedRoom> out = new ArrayList<>();
        for (Map.Entry<String, List<FederatedRoom>> e : remoteRooms.entrySet()) {
            if (peerDomain.equals(roomRelaySource.get(e.getKey()))) out.addAll(e.getValue());
        }
        return out;
    }

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
            RoomMapping m = list.get(i);
            JiveGlobals.setProperty(String.format(PROP_MAP_REMOTE, localJid, i), m.remoteRoomJid());
            JiveGlobals.setProperty(String.format(PROP_MAP_DOMAIN, localJid, i), m.remoteDomain());
            JiveGlobals.setProperty(String.format(PROP_MAP_STATE,  localJid, i), m.state().name());
            if (m.token() != null && !m.token().isEmpty()) {
                JiveGlobals.setProperty(String.format(PROP_MAP_TOKEN, localJid, i), m.token());
            } else {
                JiveGlobals.deleteProperty(String.format(PROP_MAP_TOKEN, localJid, i));
            }
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
                    JiveGlobals.deleteProperty(String.format(PROP_MAP_STATE,  localJid, i));
                    JiveGlobals.deleteProperty(String.format(PROP_MAP_TOKEN,  localJid, i));
                }
            } catch (NumberFormatException ignored) {
                // A corrupt count leaves no indexed entries to delete; the count itself goes below.
            }
            JiveGlobals.deleteProperty(String.format(PROP_MAP_COUNT, localJid));
        }
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".remote");
        JiveGlobals.deleteProperty("federation.rooms.mapping." + localJid + ".domain");
    }

    /** Writes every virtual occupant of one room, then the rooms index. */
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
                                    voccEntryValue(vo));
        }
        persistVirtualOccupantRoomsIndex();
    }

    /** The persisted form of one virtual occupant: {@code "origin|via1;via2"}. */
    private static String voccEntryValue(VirtualOccupant vo) {
        return vo.origin() + "|" + String.join(";", vo.arrivedVia());
    }

    /** Deletes all persisted virtual occupants of one room and refreshes the rooms index. */
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
