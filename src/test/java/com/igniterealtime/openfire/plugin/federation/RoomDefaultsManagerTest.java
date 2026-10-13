package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The room-name globs an admin writes in a default rule. */
class RoomDefaultsManagerTest {

    @Test
    @DisplayName("* matches any run and ? exactly one character")
    void wildcards() {
        assertTrue(RoomDefaultsManager.globMatches("ops-*", "ops-alpha"));
        assertTrue(RoomDefaultsManager.globMatches("ops-*", "ops-"));
        assertTrue(RoomDefaultsManager.globMatches("ops-?", "ops-1"));
        assertFalse(RoomDefaultsManager.globMatches("ops-?", "ops-12"));
        assertTrue(RoomDefaultsManager.globMatches("*", "anything"));
    }

    @Test
    @DisplayName("the whole name must match, not a substring")
    void anchored() {
        assertFalse(RoomDefaultsManager.globMatches("ops", "devops"));
        assertFalse(RoomDefaultsManager.globMatches("ops", "ops-1"));
    }

    @Test
    @DisplayName("matching ignores case")
    void caseInsensitive() {
        assertTrue(RoomDefaultsManager.globMatches("OPS-*", "ops-alpha"));
    }

    @Test
    @DisplayName("regex metacharacters in a glob are literal")
    void metacharactersAreLiteral() {
        assertFalse(RoomDefaultsManager.globMatches("a.b", "axb"));
        assertTrue(RoomDefaultsManager.globMatches("a.b", "a.b"));
        assertTrue(RoomDefaultsManager.globMatches("team[1]", "team[1]"));
        assertFalse(RoomDefaultsManager.globMatches("(a|b)", "a"));
    }

    @Test
    @DisplayName("a missing glob or name matches nothing")
    void nulls() {
        assertFalse(RoomDefaultsManager.globMatches(null, "x"));
        assertFalse(RoomDefaultsManager.globMatches("*", null));
    }
}
