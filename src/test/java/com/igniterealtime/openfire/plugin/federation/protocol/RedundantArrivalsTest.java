package com.igniterealtime.openfire.plugin.federation.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The per-mapping count behind the admin page's "duplicates dropped" warning. */
class RedundantArrivalsTest {

    private static final String ROOM = "ops@conference.b.example";
    private static final String VIA_A = "a.example";
    private static final String VIA_C = "c.example";
    private static final long HOUR = 60L * 60 * 1000;

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RedundantArrivals arrivals = new RedundantArrivals(now::get);

    @Test
    @DisplayName("a mapping with no dropped copies has no warning")
    void noneByDefault() {
        assertNull(arrivals.recent(ROOM, VIA_A));
    }

    @Test
    @DisplayName("drops are counted per mapping, with the first and latest time")
    void countsPerMapping() {
        long start = now.get();
        arrivals.record(ROOM, VIA_A);
        now.addAndGet(5_000);
        arrivals.record(ROOM, VIA_A);
        arrivals.record(ROOM, VIA_C);

        RedundantArrivals.Summary a = arrivals.recent(ROOM, VIA_A);
        assertEquals(2, a.count());
        assertEquals(start, a.firstAt());
        assertEquals(start + 5_000, a.lastAt());
        assertEquals(1, arrivals.recent(ROOM, VIA_C).count());
        assertNull(arrivals.recent("other@conference.b.example", VIA_A), "rooms are separate");
    }

    @Test
    @DisplayName("the warning clears a day after the last drop, and a later drop starts a fresh count")
    void clearsAfterTheWindow() {
        arrivals.record(ROOM, VIA_A);
        arrivals.record(ROOM, VIA_A);

        now.addAndGet(23 * HOUR);
        assertNotNull(arrivals.recent(ROOM, VIA_A), "still within a day of the last drop");
        arrivals.record(ROOM, VIA_A);                    // extends the stretch
        now.addAndGet(23 * HOUR);
        assertEquals(3, arrivals.recent(ROOM, VIA_A).count());

        now.addAndGet(2 * HOUR);
        assertNull(arrivals.recent(ROOM, VIA_A), "a day after the last drop it is forgotten");

        arrivals.record(ROOM, VIA_A);
        RedundantArrivals.Summary fresh = arrivals.recent(ROOM, VIA_A);
        assertEquals(1, fresh.count());
        assertEquals(now.get(), fresh.firstAt());
    }

    @Test
    @DisplayName("the number of mappings tracked is bounded, stale ones making room first")
    void bounded() {
        for (int i = 0; i < RedundantArrivals.MAX_TRACKED; i++) arrivals.record(ROOM, "s" + i + ".example");

        arrivals.record(ROOM, "one-too-many.example");
        assertNull(arrivals.recent(ROOM, "one-too-many.example"), "full of live entries: not tracked");

        now.addAndGet(25 * HOUR);
        arrivals.record(ROOM, "one-too-many.example");
        assertNotNull(arrivals.recent(ROOM, "one-too-many.example"), "stale entries were pruned for it");
    }

    @Test
    @DisplayName("missing room or domain is ignored")
    void nulls() {
        arrivals.record(null, VIA_A);
        arrivals.record(ROOM, null);
        assertNull(arrivals.recent(null, VIA_A));
        assertNull(arrivals.recent(ROOM, null));
    }
}
