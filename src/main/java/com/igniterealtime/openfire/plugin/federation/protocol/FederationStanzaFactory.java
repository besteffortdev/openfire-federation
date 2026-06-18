package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

/**
 * Builds the IQ stanzas that implement our federation gossip protocol.
 *
 * Namespace: urn:xmpp:federation:1
 *
 * Message types:
 *   peer-announce       — "I exist and I speak this protocol version"
 *   routing-update      — distance-vector table from the sender
 *   room-advertisement  — list of rooms the sender has tagged as federatable
 *   room-mapping        — bilateral room pairing confirmation
 *   muc-forward         — a MUC packet being relayed through the overlay
 */
public final class FederationStanzaFactory {

    public static final String NS = "urn:xmpp:federation:1";

    private static final Logger Log = LoggerFactory.getLogger(FederationStanzaFactory.class);

    private FederationStanzaFactory() {}

    /** Creates a set IQ addressed to toDomain from our local server domain. */
    private static IQ base(String toDomain) {
        IQ iq = new IQ(IQ.Type.set);
        iq.setFrom(localJID());
        iq.setTo(new JID(null, toDomain, null));
        return iq;
    }

    private static JID localJID() {
        return new JID(null, XMPPServer.getInstance().getServerInfo().getXMPPDomain(), null);
    }

    // ── peer-announce ──────────────────────────────────────────────────────────

    public static IQ peerAnnounce(String toDomain) {
        return peerAnnounce(toDomain, false);
    }

    /**
     * @param isReply marks a keepalive sent in response to another peer-announce.
     *        The receiver does not reply to a reply, so one side's keepalive timer
     *        warms BOTH S2S directions (each direction is a separate socket with its
     *        own idle timer) without an endless ping-pong.
     */
    public static IQ peerAnnounce(String toDomain, boolean isReply) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element ann = fed.addElement("peer-announce");
        if (isReply) ann.addAttribute("reply", "true");
        ann.addElement("server").setText(localJID().getDomain());
        ann.addElement("version").setText("1");
        return iq;
    }

    public static IQ peerWithdraw(String toDomain) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        fed.addElement("peer-withdraw");
        return iq;
    }

    // ── routing-update ─────────────────────────────────────────────────────────

    public static IQ routingUpdate(String toDomain, Collection<RouteEntry> table) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element upd = fed.addElement("routing-update");
        for (RouteEntry entry : table) {
            Element e = upd.addElement("entry");
            e.addAttribute("destination", entry.destination());
            e.addAttribute("hops",        String.valueOf(entry.hops()));
            e.addAttribute("via",         entry.nextHop());
        }
        return iq;
    }

    // ── room-advertisement ────────────────────────────────────────────────────

    /**
     * Builds a room-advertisement stanza for rooms owned by this server.
     * No origin attribute — the receiver knows the rooms came from us.
     */
    public static IQ roomAdvertisement(String toDomain, List<FederatedRoom> rooms) {
        return roomAdvertisement(toDomain, rooms, null);
    }

    /**
     * Builds a relayed room-advertisement carrying an origin attribute so the
     * receiver knows the rooms belong to {@code originDomain}, not to the
     * immediate sender.  The {@code via} trail (comma-separated server domains)
     * prevents flooding loops across multi-hop paths.
     */
    public static IQ roomAdvertisement(String toDomain, List<FederatedRoom> rooms, String originDomain) {
        return roomAdvertisement(toDomain, rooms, originDomain, null);
    }

    public static IQ roomAdvertisement(String toDomain, List<FederatedRoom> rooms,
                                       String originDomain, String via) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element adv = fed.addElement("room-advertisement");
        if (originDomain != null) adv.addAttribute("origin", originDomain);
        if (via != null && !via.isEmpty()) adv.addAttribute("via", via);
        for (FederatedRoom room : rooms) {
            Element r = adv.addElement("room");
            r.addAttribute("jid",         room.jid());
            r.addAttribute("name",        room.name()        != null ? room.name()        : "");
            r.addAttribute("description", room.description() != null ? room.description() : "");
        }
        return iq;
    }

    // ── room-mapping ──────────────────────────────────────────────────────────

    /**
     * Notifies a remote server that we are pairing our local room with one of
     * their rooms.  The IQ is addressed to {@code nextHop} (our direct S2S
     * neighbour); {@code destination} carries the final target domain and
     * {@code originDomain} carries the initiating server so intermediate hops
     * can relay without losing the original sender identity.
     *
     * The receiver interprets:
     *   local  → the JID of the room on the originating server
     *   remote → the JID of the room on the destination server (receiver's local)
     *
     * So the destination stores: localRoomJid=remote, remoteRoomJid=local, remoteDomain=origin.
     */
    public static IQ roomMapping(String nextHop, String destination, String originDomain,
                                 String localJid, String remoteJid) {
        IQ iq = base(nextHop);
        Element fed = iq.setChildElement("federation", NS);
        Element mapping = fed.addElement("room-mapping");
        mapping.addAttribute("destination", destination);
        mapping.addAttribute("origin",      originDomain);
        Element map = mapping.addElement("map");
        map.addAttribute("local",  localJid);
        map.addAttribute("remote", remoteJid);
        return iq;
    }

    /**
     * Notifies a remote server that a previously confirmed mapping has been
     * dissolved by the local admin.  Routed hop-by-hop like room-mapping.
     */
    public static IQ roomUnmapping(String nextHop, String destination, String originDomain,
                                   String localJid, String remoteJid) {
        IQ iq = base(nextHop);
        Element fed = iq.setChildElement("federation", NS);
        Element mapping = fed.addElement("room-unmap");
        mapping.addAttribute("destination", destination);
        mapping.addAttribute("origin",      originDomain);
        Element map = mapping.addElement("map");
        map.addAttribute("local",  localJid);
        map.addAttribute("remote", remoteJid);
        return iq;
    }

    // ── muc-forward ───────────────────────────────────────────────────────────

    /**
     * Wraps a MUC packet for relay to the next hop toward finalDestination.
     *
     * @param nextHop          directly-connected peer we're sending to now
     * @param finalDestination the domain that ultimately receives this packet
     * @param targetRoom       the room JID on finalDestination to inject into
     *                         (null = use the payload's existing to-address)
     * @param viaTrail         comma-separated list of servers already visited
     * @param payload          the original MUC packet (message or presence)
     */
    public static IQ mucForward(String nextHop,
                                String finalDestination,
                                String targetRoom,
                                String viaTrail,
                                Packet payload) {
        IQ iq = base(nextHop);
        Element fed = iq.setChildElement("federation", NS);
        Element fwd = fed.addElement("muc-forward");
        fwd.addAttribute("destination", finalDestination);
        if (targetRoom != null) fwd.addAttribute("targetRoom", targetRoom);
        fwd.addAttribute("via",         viaTrail);
        fwd.add(payload.getElement().createCopy());
        return iq;
    }

    /**
     * Delivers a packet directly to the recipient's ClientSession, bypassing the
     * packet router and all PacketInterceptors (including MUC's non-occupant check).
     * Falls back to the packet router if the session is not found locally (e.g. the
     * user just disconnected between the occupant-list snapshot and delivery).
     */
    public static void directDeliver(Packet packet) {
        JID to = packet.getTo();
        if (to != null && to.getResource() != null) {
            RoutingTable rt = XMPPServer.getInstance().getRoutingTable();
            ClientSession session = rt.getClientRoute(to);
            if (session != null) {
                try {
                    session.process(packet);
                    return;
                } catch (Exception e) {
                    Log.warn("directDeliver: session.process failed for {}: {}", to, e.getMessage());
                }
            }
        }
        // Fallback: user just disconnected or bare JID — route normally.
        XMPPServer.getInstance().getPacketRouter().route(packet);
    }

    /**
     * Attaches a marker element to a packet before delivering it locally so the
     * PacketInterceptor knows not to re-forward it (loop prevention).
     */
    public static void markAsForwarded(Packet packet) {
        Element el = packet.getElement();
        if (el.element(NS_ORIGIN_ELEMENT) == null) {
            el.addElement(NS_ORIGIN_ELEMENT, NS);
        }
    }

    public static boolean isMarkedAsForwarded(Packet packet) {
        return packet.getElement().element(NS_ORIGIN_ELEMENT) != null;
    }

    private static final String NS_ORIGIN_ELEMENT = "fed-origin";
}
