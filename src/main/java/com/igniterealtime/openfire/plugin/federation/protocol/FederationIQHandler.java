package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Handles all incoming federation IQ stanzas (namespace urn:xmpp:federation:1).
 *
 * peer-announce      → record that the peer is alive; send our state back
 * routing-update     → merge the sender's distance-vector table into ours
 * room-advertisement → update cached list of remote federatable rooms;
 *                      relay to all other reachable peers (transitive, single hop)
 * room-mapping       → store a bilateral room pairing confirmed by the remote admin
 * room-unmap         → remove a previously confirmed mapping
 * muc-forward        → relay or inject a MUC packet depending on destination
 */
public class FederationIQHandler extends IQHandler {

    private static final Logger Log = LoggerFactory.getLogger(FederationIQHandler.class);

    private final IQHandlerInfo   info;
    private final FederationManager manager;

    public FederationIQHandler(FederationManager manager) {
        super("Federation IQ Handler");
        this.manager = manager;
        this.info    = new IQHandlerInfo("federation", FederationStanzaFactory.NS);
    }

    @Override
    public IQHandlerInfo getInfo() {
        return info;
    }

    @Override
    public IQ handleIQ(IQ packet) throws UnauthorizedException {
        if (packet.getType() != IQ.Type.set) {
            return IQ.createResultIQ(packet);
        }

        String fromDomain = packet.getFrom().getDomain();
        Element fed = packet.getChildElement();
        if (fed == null) return error(packet, "Missing federation element");

        Element child = (Element) fed.elements().stream().findFirst().orElse(null);
        if (child == null) return error(packet, "Empty federation element");

        switch (child.getName()) {
            case "peer-announce"       -> handlePeerAnnounce(fromDomain, child);
            case "routing-update"      -> handleRoutingUpdate(fromDomain, child);
            case "room-advertisement"  -> handleRoomAdvertisement(fromDomain, child);
            case "room-mapping"        -> handleRoomMapping(fromDomain, child);
            case "room-unmap"          -> handleRoomUnmap(fromDomain, child);
            case "muc-forward"         -> handleMucForward(fromDomain, child);
            default -> Log.warn("Unknown federation action '{}' from {}", child.getName(), fromDomain);
        }

        return IQ.createResultIQ(packet);
    }

    // ── peer-announce ──────────────────────────────────────────────────────────

    private void handlePeerAnnounce(String fromDomain, Element el) {
        Log.info("peer-announce from {}", fromDomain);

        boolean isNew = !manager.getPeerRegistry().contains(fromDomain);

        if (isNew) {
            // Auto-register: a server running the federation plugin connected to us.
            manager.getPeerRegistry().addPeer(fromDomain);
            Log.info("Auto-registered federation peer via incoming connection: {}", fromDomain);
        }

        // IQ arrived over an active S2S session — mark directly reachable now.
        manager.getRoutingTable().addDirectPeer(fromDomain);
        manager.getPeerRegistry().updateStatus(fromDomain, PeerServer.Status.REACHABLE);

        // Reply with our full state so both sides synchronise immediately.
        manager.sendFullGossip(fromDomain);

        if (isNew) {
            // Push routing table to every other reachable peer so they build
            // multi-hop paths through us to the newly-registered peer.
            manager.propagateRoutingToAll(fromDomain);
        }
    }

    // ── routing-update ─────────────────────────────────────────────────────────

    private void handleRoutingUpdate(String fromDomain, Element el) {
        List<RouteEntry> received = new ArrayList<>();
        for (Element entry : el.elements("entry")) {
            String dest = entry.attributeValue("destination");
            String via  = entry.attributeValue("via");
            int hops;
            try {
                hops = Integer.parseInt(entry.attributeValue("hops", "99"));
            } catch (NumberFormatException e) {
                hops = 99;
            }
            if (dest != null && via != null) {
                received.add(new RouteEntry(dest, via, hops));
            }
        }

        Set<String> improved = manager.getRoutingTable().updateFromPeer(fromDomain, received);
        Log.debug("routing-update from {} — {} entries, {} improved", fromDomain, received.size(), improved.size());

        // Triggered update: propagate to all peers (except sender) when routes improve.
        if (!improved.isEmpty()) {
            manager.propagateRoutingToAll(fromDomain);
        }
    }

    // ── room-advertisement ────────────────────────────────────────────────────

    private void handleRoomAdvertisement(String fromDomain, Element el) {
        // If 'origin' is absent this is a direct advertisement from the sender;
        // if present this is an already-relayed advertisement — don't relay again.
        String origin       = el.attributeValue("origin");
        String sourceDomain = (origin != null) ? origin : fromDomain;

        List<FederatedRoom> rooms = new ArrayList<>();
        for (Element r : el.elements("room")) {
            String jid  = r.attributeValue("jid");
            String name = r.attributeValue("name", "");
            String desc = r.attributeValue("description", "");
            if (jid != null) {
                rooms.add(new FederatedRoom(jid, name, desc, sourceDomain));
            }
        }
        manager.getRoomManager().updateRemoteRooms(sourceDomain, rooms);
        Log.debug("room-advertisement from {} (source={}) — {} room(s)", fromDomain, sourceDomain, rooms.size());

        // Relay to all other reachable peers so non-direct nodes learn about the rooms.
        // Only the first recipient relays (origin == null means first hop).
        if (origin == null && !rooms.isEmpty()) {
            manager.relayRoomAdvertisement(fromDomain, fromDomain, rooms);
        }
    }

    // ── room-mapping ──────────────────────────────────────────────────────────

    private void handleRoomMapping(String fromDomain, Element el) {
        for (Element map : el.elements("map")) {
            // "local"  = their local room JID (= our remote room)
            // "remote" = their remote room JID (= our local room)
            String theirLocal  = map.attributeValue("local");
            String theirRemote = map.attributeValue("remote");
            if (theirLocal != null && theirRemote != null) {
                // From our perspective: our local = theirRemote, our remote = theirLocal
                manager.getRoomManager().addMapping(theirRemote, theirLocal, fromDomain);
                Log.info("Room mapping received from {}: local={} ↔ remote={}",
                         fromDomain, theirRemote, theirLocal);
                // Push our current occupants to the sender immediately so both
                // sides see each other's participants as soon as the mapping is live.
                manager.pushVirtualPresences(theirRemote, fromDomain, theirLocal, false);
            }
        }
    }

    // ── room-unmap ────────────────────────────────────────────────────────────

    private void handleRoomUnmap(String fromDomain, Element el) {
        for (Element map : el.elements("map")) {
            String theirLocal  = map.attributeValue("local");   // their local  = our remote room
            String theirRemote = map.attributeValue("remote");  // their remote = our local room
            if (theirRemote != null) {
                // Before removing the mapping, push virtual leaves for our local
                // occupants so the initiating server's clients see them depart.
                if (theirLocal != null) {
                    manager.pushVirtualPresences(theirRemote, fromDomain, theirLocal, true);
                }
                manager.getRoomManager().removeMapping(theirRemote);
                Log.info("Room mapping removed by remote {}: local={}", fromDomain, theirRemote);
            }
        }
    }

    // ── muc-forward ───────────────────────────────────────────────────────────

    private void handleMucForward(String fromDomain, Element el) {
        String finalDest  = el.attributeValue("destination");
        String targetRoom = el.attributeValue("targetRoom");
        String via        = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Loop guard
        if (via.contains(localDomain)) {
            Log.warn("muc-forward loop detected (via={}), dropping", via);
            return;
        }

        Element payloadEl = (Element) el.elements().stream().findFirst().orElse(null);
        if (payloadEl == null) {
            Log.warn("muc-forward from {} has no payload", fromDomain);
            return;
        }

        if (finalDest == null || localDomain.equals(finalDest)) {
            // We are the destination — inject into the local MUC room.
            injectLocally(payloadEl, via, targetRoom);
        } else {
            // We are an intermediate hop — forward to the next hop.
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Packet embedded = parsePacket(payloadEl);
                        XMPPServer.getInstance().getPacketRouter()
                                  .route(FederationStanzaFactory.mucForward(
                                      nextHop, finalDest, targetRoom, newVia, embedded));
                    } catch (Exception e) {
                        Log.warn("Could not relay muc-forward toward {}: {}", finalDest, e.getMessage());
                    }
                },
                () -> Log.warn("muc-forward: no route to {}, dropping", finalDest)
            );
        }
    }

    private void injectLocally(Element payloadEl, String via, String targetRoom) {
        try {
            switch (payloadEl.getName()) {
                case "message"  -> injectMessage(payloadEl, targetRoom);
                case "presence" -> injectPresence(payloadEl, targetRoom);
                default -> Log.warn("injectLocally: unexpected payload type '{}'", payloadEl.getName());
            }
        } catch (Exception e) {
            Log.warn("Failed to inject forwarded MUC packet: {}", e.getMessage(), e);
        }
    }

    /**
     * Delivers a forwarded groupchat message directly to each occupant of the
     * target room, bypassing MUC's non-occupant check entirely.
     *
     * The message uses a synthetic from="room@conf/nick" where nick encodes the
     * original sender's user@domain so clients can attribute the text correctly.
     */
    private void injectMessage(Element msgEl, String targetRoom) {
        MUCRoom room = findLocalRoom(targetRoom);
        if (room == null) {
            Log.warn("injectMessage: room {} not found locally", targetRoom);
            return;
        }
        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) return;

        JID targetJID      = new JID(targetRoom);
        String senderNick  = virtualNick(msgEl.attributeValue("from"));
        String virtualFrom = targetJID.getNode() + "@" + targetJID.getDomain() + "/" + senderNick;

        for (MUCOccupant occupant : occupants) {
            Element copy = msgEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to",   occupant.getUserAddress().toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            XMPPServer.getInstance().getPacketRouter().route(delivery);
        }
        Log.debug("injectMessage: delivered to {} occupant(s) in {}", occupants.size(), targetRoom);
    }

    /**
     * Broadcasts a virtual-occupant join/leave presence to each current occupant
     * of the target room, delivered directly (not through MUC processing).
     *
     * This means the remote room never does a real join for the remote user —
     * no roster or history is sent back to their client. Clients on this server
     * see the remote user in the occupant list with nick "user@remote-domain".
     */
    private void injectPresence(Element presEl, String targetRoom) {
        MUCRoom room = findLocalRoom(targetRoom);
        if (room == null) {
            Log.warn("injectPresence: room {} not found locally", targetRoom);
            return;
        }
        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) return;

        String originalFrom = presEl.attributeValue("from");
        boolean leaving     = "unavailable".equals(presEl.attributeValue("type"));
        String senderNick   = virtualNick(originalFrom);

        JID targetJID      = new JID(targetRoom);
        JID virtualFromJID = new JID(targetJID.getNode(), targetJID.getDomain(), senderNick);

        for (MUCOccupant occupant : occupants) {
            Presence delivery = new Presence();
            delivery.setFrom(virtualFromJID);
            delivery.setTo(occupant.getUserAddress());
            if (leaving) delivery.setType(Presence.Type.unavailable);

            // <x xmlns="http://jabber.org/protocol/muc#user"><item .../></x>
            Element x    = delivery.getElement().addElement("x", "http://jabber.org/protocol/muc#user");
            Element item = x.addElement("item");
            item.addAttribute("affiliation", "none");
            item.addAttribute("role", leaving ? "none" : "participant");
            if (originalFrom != null) item.addAttribute("jid", originalFrom);

            FederationStanzaFactory.markAsForwarded(delivery);
            XMPPServer.getInstance().getPacketRouter().route(delivery);
        }
        Log.debug("injectPresence: {} virtual presence to {} occupant(s) in {}",
                  leaving ? "leave" : "join", occupants.size(), targetRoom);

        // On join: push all current local occupants' presences back to the remote server
        // so that any newly-joined remote user immediately sees the local side's participants.
        // Skip when this IS already a sync presence (fed-origin set on payload element)
        // to prevent ping-pong loops between the two servers.
        if (!leaving && presEl.element("fed-origin") == null) {
            syncLocalOccupantsToRemote(targetRoom, occupants);
        }
    }

    /**
     * Pushes a synthetic join presence for every occupant of localRoom to the
     * remote mapped room.  Called after delivering a remote-side join so that
     * the joining user's client on the remote server also learns about the
     * local participants (fixes the join-ordering race).
     */
    private void syncLocalOccupantsToRemote(String localRoom, Collection<MUCOccupant> occupants) {
        RoomMapping mapping = manager.getRoomManager().getMappingForLocal(localRoom);
        if (mapping == null) return;

        String remoteDomain  = mapping.remoteDomain();
        String remoteRoomJid = mapping.remoteRoomJid();
        String localDomain   = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

        for (MUCOccupant occupant : occupants) {
            Presence sync = new Presence();
            sync.setFrom(occupant.getUserAddress());
            // Mark as forwarded so the receiving server's injectPresence skips
            // triggering another sync (loop-break condition checked via element).
            FederationStanzaFactory.markAsForwarded(sync);
            try {
                XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.mucForward(
                        nextHop, remoteDomain, remoteRoomJid, localDomain, sync)
                );
            } catch (Exception e) {
                Log.warn("syncLocalOccupantsToRemote: failed to push {}: {}",
                         occupant.getUserAddress(), e.getMessage());
            }
        }
        Log.debug("syncLocalOccupantsToRemote: pushed {} occupant(s) from {} to {}",
                  occupants.size(), localRoom, remoteRoomJid);
    }

    /** Derives a stable MUC nick from a full JID: "user@domain" (no resource). */
    private String virtualNick(String fromJid) {
        if (fromJid == null) return "remote";
        try {
            JID jid = new JID(fromJid);
            return (jid.getNode() != null ? jid.getNode() + "@" : "") + jid.getDomain();
        } catch (Exception e) {
            return "remote";
        }
    }

    /** Finds a local MUCRoom by full JID string (room@conference.domain). */
    private MUCRoom findLocalRoom(String roomJid) {
        try {
            JID jid = new JID(roomJid);
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(jid.getDomain())) {
                    return svc.getChatRoom(jid.getNode());
                }
            }
        } catch (Exception e) {
            Log.warn("findLocalRoom: could not find room {}: {}", roomJid, e.getMessage());
        }
        return null;
    }

    private Packet parsePacket(Element el) throws Exception {
        return switch (el.getName()) {
            case "message"  -> new Message(el.createCopy());
            case "presence" -> new Presence(el.createCopy());
            default -> throw new IllegalArgumentException("Unexpected packet type: " + el.getName());
        };
    }

    private IQ error(IQ packet, String reason) {
        Log.warn("Federation IQ error — {}", reason);
        IQ err = IQ.createResultIQ(packet);
        err.setType(IQ.Type.error);
        return err;
    }
}
