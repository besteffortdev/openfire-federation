package com.igniterealtime.openfire.plugin.federation.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Message;
import org.xmpp.packet.Presence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The marker that makes the interceptor skip a stanza: only this server's own deliveries carry weight. */
class ForwardedMarkerTest {

    @Test
    @DisplayName("a packet this server marked is recognised")
    void ownMarkCounts() {
        Message delivery = new Message();
        FederationStanzaFactory.markAsForwarded(delivery);

        assertTrue(FederationStanzaFactory.isMarkedAsForwarded(delivery));
    }

    @Test
    @DisplayName("a marker element written by the sender does not exempt a stanza from the checks")
    void forgedElementDoesNotCount() {
        IQ search = new IQ(IQ.Type.set);
        search.setChildElement("query", "jabber:iq:search");
        search.getElement().addElement("fed-origin", FederationStanzaFactory.NS);
        Presence join = new Presence();
        join.getElement().addElement("fed-origin");

        assertFalse(FederationStanzaFactory.isMarkedAsForwarded(search));
        assertFalse(FederationStanzaFactory.isMarkedAsForwarded(join));
    }

    @Test
    @DisplayName("a copy of a marked packet, as a peer would send it back, is not marked")
    void copyIsNotMarked() {
        Message delivery = new Message();
        FederationStanzaFactory.markAsForwarded(delivery);

        assertFalse(FederationStanzaFactory.isMarkedAsForwarded(new Message(delivery.getElement().createCopy())));
    }

    @Test
    @DisplayName("the sync marker tells a peer not to reply, and gives the packet no standing here")
    void syncMarkerIsOnlyAnElement() {
        Presence sync = new Presence();
        FederationStanzaFactory.addSyncMarker(sync);

        assertNotNull(sync.getElement().element("fed-origin"));
        assertFalse(FederationStanzaFactory.isMarkedAsForwarded(sync));
    }

    @Test
    @DisplayName("a file annotation is acted on only from a reachable peer naming itself as the origin")
    void annotationTrust() {
        assertTrue(FederationPacketInterceptor.annotationTrusted("b.example", "b.example", true));

        assertFalse(FederationPacketInterceptor.annotationTrusted("b.example", "b.example", false), "not a peer");
        assertFalse(FederationPacketInterceptor.annotationTrusted("b.example", "c.example", true), "another origin");
        assertFalse(FederationPacketInterceptor.annotationTrusted("b.example", null, true), "no origin");
        assertFalse(FederationPacketInterceptor.annotationTrusted(null, "b.example", true), "no sender");
    }
}
