package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-room message bookkeeping for mapped rooms, shared by the forwarder (outbound) and injection
 * (inbound):
 * <ul>
 *   <li><b>Acceptance.</b> Openfire fires {@code MUCEventListener.messageReceived} only after the room
 *       has accepted and broadcast a public message. The forwarder runs as a post-processing
 *       interceptor, which sees every groupchat a local client sent, including ones the room
 *       rejected (sender not an occupant, banned, muted). It forwards only what was marked here.</li>
 *   <li><b>Used stanza-ids.</b> A relayed message keeps its origin's stanza-id (XEP-0359) in every copy of
 *       the room, so a peer could reuse the id of a message the room already has and make two
 *       messages share it; a reaction or reply would then resolve to the wrong one. Ids seen in a room
 *       are remembered (bounded) so a reused one can be replaced.</li>
 *   <li><b>Origins.</b> Each remembered id records where its message came from: the room it was first
 *       posted to, the id that room gave it, and its author. A moderation (XEP-0425) crosses the
 *       federation only for a message its author's own server can moderate, and this is how both
 *       sides tell. See {@link RoomModeration}.</li>
 * </ul>
 */
public final class RoomMessageIds {

    /**
     * Where a message in a mapped room came from. {@code room} is the room it was first posted to (this
     * room for a local user's message), {@code stanzaId} the id that room gave it (null if it gave none
     * we could use), and {@code author} the sender's bare JID.
     */
    public record Origin(String room, String stanzaId, String author) { }

    /** How long an accepted mark waits for the forwarder; it normally consumes it in microseconds. */
    private static final long ACCEPT_TTL_MS = 30_000;
    /** Stanza-ids remembered per mapped room. */
    static final int SEEN_PER_ROOM = 2_000;

    private final Map<String, Long> accepted = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Origin>> seen = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> moderated = new ConcurrentHashMap<>();

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

    /**
     * Records {@code stanzaId} as used in {@code roomJid} by a message from {@code origin}. Returns false,
     * recording nothing, if the room already had it among its last {@link #SEEN_PER_ROOM} ids.
     */
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
     * The id {@code roomJid} gave the message that {@code originRoom} knows as {@code originId}, or null
     * if not remembered. The oldest match wins: a later message reusing the id got a fresh one here.
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

    /** True the first time a moderation of {@code stanzaId} in {@code roomJid} is seen (bounded). */
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
     * The key both sides compute for one message: its stanza-id from the room, which Openfire stamps on
     * the client's packet before the room processes it, so the event's copy and the interceptor's
     * original carry the same one. With stanza-ids switched off server-wide
     * ({@code xmpp.sid.enabled=false}) it falls back to the client's stanza id and sender.
     */
    public static String keyOf(String stanzaId, String packetId, String from) {
        return stanzaId != null ? "sid:" + stanzaId : "pid:" + packetId + '\n' + from;
    }
}
