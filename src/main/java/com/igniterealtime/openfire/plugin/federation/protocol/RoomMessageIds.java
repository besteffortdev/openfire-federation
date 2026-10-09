package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * </ul>
 */
public final class RoomMessageIds {

    /** How long an accepted mark waits for the forwarder; it normally consumes it in microseconds. */
    private static final long ACCEPT_TTL_MS = 30_000;
    /** Stanza-ids remembered per mapped room. */
    static final int SEEN_PER_ROOM = 2_000;

    private final Map<String, Long> accepted = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> seen = new ConcurrentHashMap<>();

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
     * Records {@code stanzaId} as used in {@code roomJid}. Returns false if the room already had it
     * among its last {@link #SEEN_PER_ROOM} ids.
     */
    public boolean claim(String roomJid, String stanzaId) {
        Set<String> ids = seen.computeIfAbsent(roomJid, k -> Collections.synchronizedSet(
                Collections.newSetFromMap(new LinkedHashMap<>() {
                    @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > SEEN_PER_ROOM;
                    }
                })));
        return ids.add(stanzaId);
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
