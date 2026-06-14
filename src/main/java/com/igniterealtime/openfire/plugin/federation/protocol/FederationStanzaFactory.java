package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

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
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element ann = fed.addElement("peer-announce");
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
     * their rooms.  The remote side interprets:
     *   local  → the JID of the room on THIS server (sender's local)
     *   remote → the JID of the room on THAT server (receiver's local)
     *
     * So the receiver stores: localRoomJid=remote, remoteRoomJid=local.
     */
    public static IQ roomMapping(String toDomain, String localJid, String remoteJid) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element mapping = fed.addElement("room-mapping");
        Element map = mapping.addElement("map");
        map.addAttribute("local",  localJid);
        map.addAttribute("remote", remoteJid);
        return iq;
    }

    /**
     * Notifies a remote server that a previously confirmed mapping has been
     * dissolved by the local admin.
     */
    public static IQ roomUnmapping(String toDomain, String localJid, String remoteJid) {
        IQ iq = base(toDomain);
        Element fed = iq.setChildElement("federation", NS);
        Element mapping = fed.addElement("room-unmap");
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
