package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.LogSafe;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.function.Supplier;

/**
 * Sends federation stanzas and delivers relayed ones locally. Sending is best effort: a failure is
 * logged with its cause and reported to the caller, never thrown, so one lost stanza cannot abort a
 * fan-out or a teardown.
 */
public final class FederationSender {

    private static final Logger Log = LoggerFactory.getLogger(FederationSender.class);

    private FederationSender() {}

    /** Routes {@code stanza}; {@code what} and {@code toward} describe it in the log if that fails. */
    public static boolean send(String what, String toward, Packet stanza) {
        return send(what, toward, () -> stanza);
    }

    /**
     * Builds a stanza with {@code builder} and routes it. A null stanza means there is nothing to send;
     * a failure to build or route is logged and returns false.
     */
    public static boolean send(String what, String toward, Supplier<? extends Packet> builder) {
        try {
            Packet stanza = builder.get();
            if (stanza == null) return false;
            XMPPServer.getInstance().getPacketRouter().route(stanza);
            return true;
        } catch (RuntimeException e) {
            Log.warn("Could not send {} toward {}", what, LogSafe.text(toward), e);
            return false;
        }
    }

    /**
     * Delivers a packet straight to the recipient's session, bypassing the router and its
     * interceptors, or routes it normally when there is no such session. True when it bypassed them.
     */
    public static boolean directDeliver(Packet packet) {
        JID to = packet.getTo();
        if (to != null && to.getResource() != null) {
            ClientSession session = XMPPServer.getInstance().getRoutingTable().getClientRoute(to);
            if (session != null) {
                try {
                    session.process(packet);
                    return true;
                } catch (RuntimeException e) {
                    Log.warn("Could not deliver to session {}; routing instead", to, e);
                }
            }
        }
        XMPPServer.getInstance().getPacketRouter().route(packet);
        return false;
    }
}
