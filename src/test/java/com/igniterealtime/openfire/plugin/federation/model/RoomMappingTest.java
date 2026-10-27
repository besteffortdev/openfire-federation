package com.igniterealtime.openfire.plugin.federation.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a mapping's activation time moves, and which tokens speak for it. */
class RoomMappingTest {

    private static final RoomMapping PENDING =
            new RoomMapping("ops@conference.a.example", "ops@conference.b.example", "b.example");

    @Test
    @DisplayName("becoming ACTIVE stamps the activation time")
    void activationIsStamped() {
        long before = System.currentTimeMillis();
        RoomMapping active = PENDING.with(RoomMapping.State.ACTIVE, "t");

        assertTrue(active.activatedAt() >= before);
    }

    @Test
    @DisplayName("staying ACTIVE or leaving it keeps the time; becoming ACTIVE again restarts it")
    void onlyTheTransitionStampsIt() {
        RoomMapping active = new RoomMapping("l", "r", "b.example", RoomMapping.State.ACTIVE, "t", 42L);

        assertEquals(42L, active.with(RoomMapping.State.ACTIVE, "t").activatedAt());
        RoomMapping disabled = active.with(RoomMapping.State.DISABLED_REMOTE, "t");
        assertEquals(42L, disabled.activatedAt());
        assertTrue(disabled.with(RoomMapping.State.ACTIVE, "t").activatedAt() > 42L);
    }

    @Test
    @DisplayName("only the stored token speaks for a mapping, unless none was stored")
    void tokens() {
        RoomMapping tokened = PENDING.with(RoomMapping.State.ACTIVE, "secret");

        assertTrue(tokened.acceptsToken("secret"));
        assertFalse(tokened.acceptsToken("guess"));
        assertFalse(tokened.acceptsToken(""));
        assertTrue(PENDING.acceptsToken("anything"), "a pre-token mapping accepts any token");
    }
}
