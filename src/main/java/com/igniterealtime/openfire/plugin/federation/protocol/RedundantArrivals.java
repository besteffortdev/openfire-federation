package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts, per mapping, the copies dropped because the room already had them: a second copy of a
 * message, or a payload whose hub trail already names this server. Either means the room is reached
 * by more than one path — its mappings form a ring or a mesh — so the admin page can say which
 * mapping is carrying the redundant traffic. Nothing is lost when this happens; it is a warning
 * about topology, not delivery.
 *
 * <p>A mapping's count covers one stretch of drops and is forgotten {@link #WINDOW_MS} after the
 * last, so the warning clears on its own once the redundant mapping is removed.</p>
 */
public final class RedundantArrivals {

    /** How long after its last drop a mapping keeps its warning. */
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Most mappings tracked; stale ones are pruned first, and past it a new one is not tracked. */
    static final int MAX_TRACKED = 1024;

    /** Drops through one mapping: how many, and when the first and the latest happened. */
    public record Summary(long count, long firstAt, long lastAt) { }

    private final Map<String, Summary> byMapping = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RedundantArrivals() {
        this(System::currentTimeMillis);
    }

    RedundantArrivals(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Records one dropped copy in {@code localRoomJid} that arrived through the mapping whose far
     * end is {@code remoteDomain}.
     */
    public void record(String localRoomJid, String remoteDomain) {
        if (localRoomJid == null || remoteDomain == null) return;
        long now = clock.getAsLong();
        String key = keyOf(localRoomJid, remoteDomain);
        if (!byMapping.containsKey(key) && byMapping.size() >= MAX_TRACKED) {
            byMapping.values().removeIf(s -> now - s.lastAt() > WINDOW_MS);
            if (byMapping.size() >= MAX_TRACKED) return;
        }
        byMapping.compute(key, (k, s) -> s == null || now - s.lastAt() > WINDOW_MS
                ? new Summary(1, now, now)
                : new Summary(s.count() + 1, s.firstAt(), now));
    }

    /** The drops through that mapping, or null if there were none in the last {@link #WINDOW_MS}. */
    public Summary recent(String localRoomJid, String remoteDomain) {
        if (localRoomJid == null || remoteDomain == null) return null;
        Summary s = byMapping.get(keyOf(localRoomJid, remoteDomain));
        return s == null || clock.getAsLong() - s.lastAt() > WINDOW_MS ? null : s;
    }

    private static String keyOf(String localRoomJid, String remoteDomain) {
        return localRoomJid + '\n' + remoteDomain;
    }
}
