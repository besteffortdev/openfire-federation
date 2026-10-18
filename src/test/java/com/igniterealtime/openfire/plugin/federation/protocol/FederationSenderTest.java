package com.igniterealtime.openfire.plugin.federation.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Best-effort sending: a failure is reported to the caller and never thrown. */
class FederationSenderTest {

    @Test
    @DisplayName("a builder that fails is reported, not thrown")
    void failingBuilder() {
        assertFalse(FederationSender.send("peer-announce", "peer.example", () -> {
            throw new IllegalArgumentException("malformed next hop");
        }));
    }

    @Test
    @DisplayName("a builder with nothing to send sends nothing")
    void nothingToSend() {
        assertFalse(FederationSender.send("room-advertisement", "peer.example", () -> null));
    }

    @Test
    @DisplayName("a routing failure is reported, not thrown")
    void routingFailure() {
        // No Openfire server runs in a unit test, so routing fails.
        assertFalse(FederationSender.send("routing-update", "peer.example", new IQ(IQ.Type.set)));
    }
}
