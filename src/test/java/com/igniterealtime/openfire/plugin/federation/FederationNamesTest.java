package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The syntax checks on domains and JIDs taken from admins, the config file and peers. */
class FederationNamesTest {

    @Test
    @DisplayName("an admin-entered peer domain is lowercase host characters only")
    void plausibleDomain() {
        assertTrue(FederationNames.isPlausibleDomain("2501"));
        assertTrue(FederationNames.isPlausibleDomain("peer-1.lab_net.example"));

        assertFalse(FederationNames.isPlausibleDomain(null));
        assertFalse(FederationNames.isPlausibleDomain(""));
        assertFalse(FederationNames.isPlausibleDomain("Peer.example"));
        assertFalse(FederationNames.isPlausibleDomain("a\".example"));
        assertFalse(FederationNames.isPlausibleDomain("a b.example"));
        assertFalse(FederationNames.isPlausibleDomain("<x>.example"));
        assertFalse(FederationNames.isPlausibleDomain("a".repeat(254)));
    }

    @Test
    @DisplayName("room JIDs and dotted domains pass the JID check")
    void safeJidAccepts() {
        assertTrue(FederationNames.isSafeJid("lobby@conference.peer.example"));
        assertTrue(FederationNames.isSafeJid("peer.example"));
        assertTrue(FederationNames.isSafeJid("équipe@conference.peer.example"));
    }

    @Test
    @DisplayName("a JID that could break out of a log line, XML or HTML is refused")
    void safeJidRefuses() {
        assertFalse(FederationNames.isSafeJid(null));
        assertFalse(FederationNames.isSafeJid(""));
        assertFalse(FederationNames.isSafeJid("localhost"));
        assertFalse(FederationNames.isSafeJid("a@b.example\nFAKE LOG LINE"));
        assertFalse(FederationNames.isSafeJid("a@b.example\u007f"));
        assertFalse(FederationNames.isSafeJid("<script>@b.example"));
        assertFalse(FederationNames.isSafeJid("a\"b@c.example"));
        assertFalse(FederationNames.isSafeJid("a b@c.example"));
        assertFalse(FederationNames.isSafeJid("a@" + "b".repeat(3070) + ".example"));
    }
}
