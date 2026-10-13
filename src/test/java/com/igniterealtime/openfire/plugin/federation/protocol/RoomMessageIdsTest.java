package com.igniterealtime.openfire.plugin.federation.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recognising the second copy of a message that reached a room by two paths. */
class RoomMessageIdsTest {

    private static final String ROOM = "ops@conference.b.example";
    private static final String ORIGIN = "ops@conference.a.example";
    private static final String ID = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";

    private final RoomMessageIds ids = new RoomMessageIds();

    @Test
    @DisplayName("only the first copy of a message counts as its arrival")
    void secondCopyIsADuplicate() {
        assertTrue(ids.firstArrival(ROOM, ORIGIN, ID));
        assertFalse(ids.firstArrival(ROOM, ORIGIN, ID));
        assertFalse(ids.firstArrival(ROOM, ORIGIN, ID));
    }

    @Test
    @DisplayName("each copy of the room keeps its own arrivals")
    void roomsAreIndependent() {
        assertTrue(ids.firstArrival(ROOM, ORIGIN, ID));
        assertTrue(ids.firstArrival("ops@conference.c.example", ORIGIN, ID));
    }

    @Test
    @DisplayName("the same id from a different origin room is a different message")
    void originRoomIsPartOfTheKey() {
        assertTrue(ids.firstArrival(ROOM, ORIGIN, ID));
        assertTrue(ids.firstArrival(ROOM, "ops@conference.d.example", ID));
    }

    @Test
    @DisplayName("arrivals are remembered per room up to a bound, oldest forgotten first")
    void bounded() {
        for (int i = 0; i <= RoomMessageIds.SEEN_PER_ROOM; i++) ids.firstArrival(ROOM, ORIGIN, "id-" + i);

        assertTrue(ids.firstArrival(ROOM, ORIGIN, "id-0"), "the oldest should have been forgotten");
        assertFalse(ids.firstArrival(ROOM, ORIGIN, "id-" + RoomMessageIds.SEEN_PER_ROOM));
    }

    @Test
    @DisplayName("two copies arriving at once cannot both be taken for the first")
    void atomicUnderContention() throws Exception {
        int threads = 16;
        for (int round = 0; round < 200; round++) {
            String id = "race-" + round;
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger firsts = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        go.await();
                        if (ids.firstArrival(ROOM, ORIGIN, id)) firsts.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, firsts.get(), "round " + round);
        }
    }
}
