package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-room message bookkeeping for mapped rooms: which local messages the room accepted, which
 * stanza-ids it has used and where each message came from, and which relayed copies already
 * arrived. The forwarder and the injection path share it; every map is bounded per room.
 */
public final class RoomMessageIds {

    /** Where a message came from: the room it was posted to, the id that room gave it, and its author. */
    public record Origin(String room, String stanzaId, String author) { }

    /** How long an accepted mark waits for the forwarder, which normally takes it at once. */
    private static final long ACCEPT_TTL_MS = 30_000;
    /** Stanza-ids remembered per mapped room. */
    static final int SEEN_PER_ROOM = 2_000;

    private final Map<String, Long> accepted = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Origin>> seen = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> moderated = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> arrived = new ConcurrentHashMap<>();

    /** Records that {@code roomJid} accepted and broadcast the message identified by {@code key}. */
    public void markAccepted(String roomJid, String key) {
        long now = System.currentTimeMillis();
        accepted.put(roomJid + '\n' + key, now);
        if (accepted.size() > 1_000) accepted.values().removeIf(t -> now - t > ACCEPT_TTL_MS);
    }

    /** True, once, if {@code roomJid} accepted that message within the last {@link #ACCEPT_TTL_MS}. */
    public boolean consumeAccepted(String roomJid, String key) {
        Long at = accepted.remove(roomJid + '\n' + key);
        return at != null && System.currentTimeMillis() - at <= ACCEPT_TTL_MS;
    }

    /** Records {@code stanzaId} as used in {@code roomJid}; false, recording nothing, if it was already used. */
    public boolean claim(String roomJid, String stanzaId, Origin origin) {
        Map<String, Origin> ids = lru(seen, roomJid);
        synchronized (ids) {
            if (ids.containsKey(stanzaId)) return false;
            ids.put(stanzaId, origin);
            return true;
        }
    }

    /** Where the message {@code roomJid} knows as {@code stanzaId} came from, or null if not remembered. */
    public Origin originOf(String roomJid, String stanzaId) {
        Map<String, Origin> ids = seen.get(roomJid);
        return ids == null ? null : ids.get(stanzaId);
    }

    /**
     * The id {@code roomJid} gave the message {@code originRoom} knows as {@code originId}, or null.
     * The oldest match wins, because a later message that reused the id was given a fresh one here.
     */
    public String localIdOf(String roomJid, String originRoom, String originId) {
        Map<String, Origin> ids = seen.get(roomJid);
        if (ids == null || originRoom == null || originId == null) return null;
        synchronized (ids) {
            for (Map.Entry<String, Origin> e : ids.entrySet()) {
                Origin o = e.getValue();
                if (originRoom.equals(o.room()) && originId.equals(o.stanzaId())) return e.getKey();
            }
        }
        return null;
    }

    /**
     * True the first time a relayed copy of that origin message reaches {@code roomJid}. Atomic, so
     * two copies arriving together over different paths cannot both count as the first.
     */
    public boolean firstArrival(String roomJid, String originRoom, String originId) {
        return lru(arrived, roomJid).putIfAbsent(originRoom + '\n' + originId, Boolean.TRUE) == null;
    }

    /** True the first time a moderation of {@code stanzaId} in {@code roomJid} is seen. */
    public boolean markModerated(String roomJid, String stanzaId) {
        Map<String, Boolean> ids = lru(moderated, roomJid);
        return ids.putIfAbsent(stanzaId, Boolean.TRUE) == null;
    }

    private static <V> Map<String, V> lru(Map<String, Map<String, V>> byRoom, String roomJid) {
        return byRoom.computeIfAbsent(roomJid, k -> Collections.synchronizedMap(new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > SEEN_PER_ROOM;
            }
        }));
    }

    /**
     * The key the forwarder and the room event both compute for one message: the room's stanza-id,
     * or the client's packet id and sender when stanza-ids are disabled server-wide.
     */
    public static String keyOf(String stanzaId, String packetId, String from) {
        return stanzaId != null ? "sid:" + stanzaId : "pid:" + packetId + '\n' + from;
    }
}
