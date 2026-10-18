package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.ContactListManager;
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
 * Builds the IQ stanzas of the federation protocol ({@value #NS}) and holds its element and
 * attribute vocabulary. Every builder here has a matching reader in {@link FederationIQHandler} or
 * the file relay. The wire format is specified in {@code docs/protocol/}.
 */
public final class FederationStanzaFactory {

    public static final String NS = "urn:xmpp:federation:1";

    /** The IQ child element that carries every federation payload. */
    public static final String ELEMENT = "federation";

    /** Domain a payload originated at. */
    public static final String ATTR_ORIGIN = "origin";

    /** Domain a payload is finally addressed to, as opposed to the next hop. */
    public static final String ATTR_DESTINATION = "destination";

    /** On a routing-update entry, the route's next hop; elsewhere, the hop trail used to stop loops. */
    public static final String ATTR_VIA = "via";
    /** Routing-update entry flag: the route crosses an untrusted peer. */
    public static final String ATTR_EDGE = "edge";
    /** muc-forward: servers that already delivered and fanned out the payload; never reset, unlike via. */
    public static final String ATTR_HUBS = "hubs";
    /** Most entries a {@link #ATTR_HUBS} trail may carry; a longer one is a loop that escaped, or abuse. */
    public static final int MAX_HUBS = 32;

    /** Room mapping: the peer's side of the pairing. */
    public static final String ATTR_REMOTE = "remote";

    /** Room mapping: our side of the pairing. */
    public static final String ATTR_LOCAL = "local";

    /** Send time in epoch milliseconds, echoed back by mapping-pong to measure the round trip. */
    public static final String ATTR_TS = "ts";

    /** File relay: the transfer id, which is the SHA-256 of the upload URL. */
    public static final String ATTR_ID = "id";

    /** File relay: the per-share capability a file-request must echo; the transfer id alone is public. */
    public static final String ATTR_TOKEN = "token";

    /** muc-forward: the mapped server the payload enters the destination through. */
    public static final String ATTR_SRC = "src";

    /** muc-forward: the room on the destination to deliver into. */
    public static final String ATTR_TARGET_ROOM = "targetRoom";

    private static final String NS_ORIGIN_ELEMENT = "fed-origin";

    private FederationStanzaFactory() {}

    /** A set IQ from this server's domain to {@code toDomain}. */
    private static IQ base(String toDomain) {
        IQ iq = new IQ(IQ.Type.set);
        iq.setFrom(localJID());
        iq.setTo(new JID(null, toDomain, null));
        return iq;
    }

    private static JID localJID() {
        return new JID(null, XMPPServer.getInstance().getServerInfo().getXMPPDomain(), null);
    }

    /** Adds the federation element to {@code iq} and returns its payload child named {@code name}. */
    private static Element payload(IQ iq, String name) {
        return iq.setChildElement(ELEMENT, NS).addElement(name);
    }

    /** As {@link #payload}, for a payload routed hop by hop: it names its final destination and origin. */
    private static Element routedPayload(IQ iq, String name, String destination, String origin) {
        Element routed = payload(iq, name);
        routed.addAttribute(ATTR_DESTINATION, destination);
        routed.addAttribute(ATTR_ORIGIN, origin);
        return routed;
    }

    /** Adds the attribute only when {@code value} is neither null nor empty. */
    private static void addIfPresent(Element el, String name, String value) {
        if (value != null && !value.isEmpty()) el.addAttribute(name, value);
    }

    /**
     * A peer-announce carrying our trust stance toward the peer. A reply is never answered, so a
     * keepalive warms both S2S directions without a ping-pong.
     */
    public static IQ peerAnnounce(String toDomain, boolean isReply, boolean untrusted) {
        IQ iq = base(toDomain);
        Element ann = payload(iq, "peer-announce");
        if (isReply) ann.addAttribute("reply", "true");
        if (untrusted) ann.addAttribute("untrusted", "true");
        ann.addElement("server").setText(localJID().getDomain());
        ann.addElement("version").setText("1");
        return iq;
    }

    public static IQ peerWithdraw(String toDomain) {
        IQ iq = base(toDomain);
        payload(iq, "peer-withdraw");
        return iq;
    }

    /** Tells the remote that our admin disabled the link; it records REMOTE_DISABLED until we re-enable. */
    public static IQ peerDisable(String toDomain) {
        IQ iq = base(toDomain);
        payload(iq, "peer-disable");
        return iq;
    }

    public static IQ routingUpdate(String toDomain, Collection<RouteEntry> table) {
        IQ iq = base(toDomain);
        Element upd = payload(iq, "routing-update");
        for (RouteEntry entry : table) {
            Element e = upd.addElement("entry");
            e.addAttribute(ATTR_DESTINATION, entry.destination());
            e.addAttribute("hops", String.valueOf(entry.hops()));
            e.addAttribute(ATTR_VIA, entry.nextHop());
            if (entry.edge()) e.addAttribute(ATTR_EDGE, "true");
        }
        return iq;
    }

    /** Asks the recipient to re-send its routing table and room cache, so lost routes re-form. */
    public static IQ routingSolicit(String toDomain) {
        IQ iq = base(toDomain);
        payload(iq, "routing-solicit");
        return iq;
    }

    /** A room-advertisement for this server's own rooms. */
    public static IQ roomAdvertisement(String toDomain, List<FederatedRoom> rooms) {
        return roomAdvertisement(toDomain, rooms, null, null);
    }

    /** A room-advertisement relayed for {@code originDomain}, whose trail so far is {@code via}. */
    public static IQ roomAdvertisement(String toDomain, List<FederatedRoom> rooms,
                                       String originDomain, String via) {
        IQ iq = base(toDomain);
        Element adv = payload(iq, "room-advertisement");
        if (originDomain != null) adv.addAttribute(ATTR_ORIGIN, originDomain);
        addIfPresent(adv, ATTR_VIA, via);
        for (FederatedRoom room : rooms) {
            Element r = adv.addElement("room");
            r.addAttribute("jid", room.jid());
            r.addAttribute("name", room.name()        != null ? room.name()        : "");
            r.addAttribute("description", room.description() != null ? room.description() : "");
            if (room.visibleTo() != null && !room.visibleTo().isEmpty()) {
                r.addAttribute("visibleto", String.join(",", room.visibleTo()));
            }
        }
        return iq;
    }

    /**
     * A mapping request from our room {@code localJid} to {@code remoteJid} on {@code destination},
     * sent to {@code nextHop} and relayed hop by hop.
     */
    public static IQ roomMapping(String nextHop, String destination, String originDomain,
                                 String localJid, String remoteJid) {
        return mappingLifecycle("room-mapping", nextHop, destination, originDomain,
                                localJid, remoteJid, null, null);
    }

    /** Tells the remote that a mapping was removed here; relayed hop by hop like a request. */
    public static IQ roomUnmapping(String nextHop, String destination, String originDomain,
                                   String localJid, String remoteJid, String token) {
        return mappingLifecycle("room-unmap", nextHop, destination, originDomain,
                                localJid, remoteJid, token, null);
    }

    /** Accepts a mapping request, carrying the newly minted consent token. */
    public static IQ roomMappingAccept(String nextHop, String destination, String originDomain,
                                       String localJid, String remoteJid, String token) {
        return mappingLifecycle("room-mapping-accept", nextHop, destination, originDomain,
                                localJid, remoteJid, token, null);
    }

    /** Rejects a mapping request, with an optional reason. */
    public static IQ roomMappingReject(String nextHop, String destination, String originDomain,
                                       String localJid, String remoteJid, String reason) {
        return mappingLifecycle("room-mapping-reject", nextHop, destination, originDomain,
                                localJid, remoteJid, null, reason);
    }

    /** Disables an active mapping; the peer shows it as disabled by peer. */
    public static IQ roomMappingDisable(String nextHop, String destination, String originDomain,
                                        String localJid, String remoteJid, String token) {
        return mappingLifecycle("room-mapping-disable", nextHop, destination, originDomain,
                                localJid, remoteJid, token, null);
    }

    /** Re-enables a mapping we disabled. */
    public static IQ roomMappingEnable(String nextHop, String destination, String originDomain,
                                       String localJid, String remoteJid, String token) {
        return mappingLifecycle("room-mapping-enable", nextHop, destination, originDomain,
                                localJid, remoteJid, token, null);
    }

    /** Builds any mapping request or lifecycle IQ; relays also use it to pass one on unchanged. */
    public static IQ mappingLifecycle(String element, String nextHop, String destination,
                                      String originDomain, String localJid, String remoteJid,
                                      String token, String reason) {
        IQ iq = base(nextHop);
        Element mapping = routedPayload(iq, element, destination, originDomain);
        addIfPresent(mapping, "token", token);
        addIfPresent(mapping, "reason", reason);
        Element map = mapping.addElement("map");
        map.addAttribute(ATTR_LOCAL, localJid);
        map.addAttribute(ATTR_REMOTE, remoteJid);
        return iq;
    }

    /**
     * An end-to-end probe of a mapped server's path; {@code destination} answers with a mapping-pong.
     * A missing pong reveals a path broken mid-way while our own routes still look fine.
     */
    public static IQ mappingPing(String nextHop, String destination, String originDomain,
                                 String viaTrail, String ts) {
        return mappingProbe("mapping-ping", nextHop, destination, originDomain, viaTrail, ts);
    }

    /** The reply to a mapping-ping; {@code ts} echoes the ping's send time unchanged. */
    public static IQ mappingPong(String nextHop, String destination, String originDomain,
                                 String viaTrail, String ts) {
        return mappingProbe("mapping-pong", nextHop, destination, originDomain, viaTrail, ts);
    }

    private static IQ mappingProbe(String element, String nextHop, String destination,
                                   String originDomain, String viaTrail, String ts) {
        IQ iq = base(nextHop);
        Element probe = routedPayload(iq, element, destination, originDomain);
        addIfPresent(probe, ATTR_VIA, viaTrail);
        addIfPresent(probe, ATTR_TS, ts);
        return iq;
    }

    /**
     * Wraps a MUC packet for relay toward {@code targetRoom} on {@code finalDestination}.
     * {@code srcMapped} is the mapped server the payload enters the destination through.
     */
    public static IQ mucForward(String nextHop,
                                String finalDestination,
                                String targetRoom,
                                String viaTrail,
                                String srcMapped,
                                Packet payload) {
        return mucForward(nextHop, finalDestination, targetRoom, viaTrail, srcMapped, srcMapped, payload);
    }

    /** As {@link #mucForward(String, String, String, String, String, Packet)}, with an explicit hub trail. */
    public static IQ mucForward(String nextHop,
                                String finalDestination,
                                String targetRoom,
                                String viaTrail,
                                String srcMapped,
                                String hubs,
                                Packet payload) {
        IQ iq = base(nextHop);
        Element fwd = payload(iq, "muc-forward");
        fwd.addAttribute(ATTR_DESTINATION, finalDestination);
        if (targetRoom != null) fwd.addAttribute(ATTR_TARGET_ROOM, targetRoom);
        fwd.addAttribute(ATTR_VIA, viaTrail);
        if (srcMapped != null) fwd.addAttribute(ATTR_SRC, srcMapped);
        addIfPresent(fwd, ATTR_HUBS, hubs);
        fwd.add(payload.getElement().createCopy());
        return iq;
    }

    /** Wraps a 1:1 stanza in the {@code element} envelope: direct-forward, presence-forward or iq-forward. */
    public static IQ oneToOneForward(String element, String nextHop, String finalDestination,
                                     String viaTrail, Packet payload) {
        IQ iq = base(nextHop);
        Element fwd = payload(iq, element);
        fwd.addAttribute(ATTR_DESTINATION, finalDestination);
        fwd.addAttribute(ATTR_VIA, viaTrail);
        fwd.add(payload.getElement().createCopy());
        return iq;
    }

    /** The full list of contacts {@code origin} shares with {@code destination}; an empty list withdraws it. */
    public static IQ contactList(String nextHop, String destination, String origin, String via,
                                 Collection<ContactListManager.Contact> contacts) {
        IQ iq = base(nextHop);
        Element list = routedPayload(iq, "contact-list", destination, origin);
        addIfPresent(list, ATTR_VIA, via);
        for (ContactListManager.Contact c : contacts) {
            Element e = list.addElement("contact");
            e.addAttribute("jid", c.jid());
            addIfPresent(e, "name", c.name());
        }
        return iq;
    }

    /** Asks {@code destination} to re-send the contact list it shares with {@code origin}. */
    public static IQ contactListRequest(String nextHop, String destination, String origin, String via) {
        IQ iq = base(nextHop);
        Element req = routedPayload(iq, "contact-list-request", destination, origin);
        addIfPresent(req, ATTR_VIA, via);
        return iq;
    }

    /** Asks {@code destination}, which holds the content, to stream file {@code id} back to {@code origin}. */
    public static IQ fileRequest(String nextHop, String destination, String origin,
                                 String id, String token, String via) {
        IQ iq = base(nextHop);
        Element req = routedPayload(iq, "file-request", destination, origin);
        req.addAttribute(ATTR_ID, id);
        addIfPresent(req, ATTR_TOKEN, token);
        addIfPresent(req, ATTR_VIA, via);
        return iq;
    }

    /** The transfer header sent before the chunks: file metadata and the chunk layout. */
    public static IQ fileOffer(String nextHop, String destination, String origin, String id,
                               String name, String mime, long size, String sha256,
                               int chunkSize, int totalChunks, String via) {
        IQ iq = base(nextHop);
        Element offer = routedPayload(iq, "file-offer", destination, origin);
        offer.addAttribute(ATTR_ID, id);
        offer.addAttribute("name", name != null ? name : "file");
        offer.addAttribute("mime", mime != null ? mime : "application/octet-stream");
        offer.addAttribute("size", Long.toString(size));
        addIfPresent(offer, "sha256", sha256);
        offer.addAttribute("chunkSize", Integer.toString(chunkSize));
        offer.addAttribute("totalChunks", Integer.toString(totalChunks));
        addIfPresent(offer, ATTR_VIA, via);
        return iq;
    }

    /** One base64 chunk of a file transfer. */
    public static IQ fileChunk(String nextHop, String destination, String origin, String id,
                               int seq, String dataB64, String via) {
        IQ iq = base(nextHop);
        Element chunk = routedPayload(iq, "file-chunk", destination, origin);
        chunk.addAttribute(ATTR_ID, id);
        chunk.addAttribute("seq", Integer.toString(seq));
        addIfPresent(chunk, ATTR_VIA, via);
        chunk.setText(dataB64);
        return iq;
    }

    /** A negative answer to a file-request, such as {@code not-found}, so the requester stops waiting. */
    public static IQ fileError(String nextHop, String destination, String origin, String id,
                               String reason, String via) {
        IQ iq = base(nextHop);
        Element err = routedPayload(iq, "file-error", destination, origin);
        err.addAttribute(ATTR_ID, id);
        addIfPresent(err, "reason", reason);
        addIfPresent(err, ATTR_VIA, via);
        return iq;
    }

    /** Passes a destination-routed element on to the next hop unchanged apart from its via trail. */
    public static IQ relay(String nextHop, Element element, String newVia) {
        IQ iq = base(nextHop);
        Element fed = iq.setChildElement(ELEMENT, NS);
        Element copy = element.createCopy();
        copy.addAttribute(ATTR_VIA, newVia);
        fed.add(copy);
        return iq;
    }

    /** Marks a packet as delivered by federation, so the interceptor does not forward it again. */
    public static void markAsForwarded(Packet packet) {
        Element el = packet.getElement();
        if (el.element(NS_ORIGIN_ELEMENT) == null) {
            el.addElement(NS_ORIGIN_ELEMENT, NS);
        }
    }

    public static boolean isMarkedAsForwarded(Packet packet) {
        return packet.getElement().element(NS_ORIGIN_ELEMENT) != null;
    }

    /** Whether {@code domain} is one of the entries of the comma-separated trail {@code via}. */
    public static boolean viaContains(String via, String domain) {
        if (via == null || via.isEmpty() || domain == null) return false;
        for (String d : via.split(",")) {
            if (domain.equals(d.strip())) return true;
        }
        return false;
    }

    /** {@code trail} with {@code domain} appended, unless it is already an entry of it. */
    public static String appendToTrail(String trail, String domain) {
        if (trail == null || trail.isEmpty()) return domain;
        return viaContains(trail, domain) ? trail : trail + "," + domain;
    }

    /** How many entries the comma-separated {@code trail} holds. */
    public static int trailLength(String trail) {
        if (trail == null || trail.isEmpty()) return 0;
        int n = 0;
        for (String d : trail.split(",")) if (!d.isBlank()) n++;
        return n;
    }
}
