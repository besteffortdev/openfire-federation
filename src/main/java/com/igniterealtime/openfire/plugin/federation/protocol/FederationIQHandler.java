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
 * room-unmap         → remove a previously confirmed mapping (sender's spoke only)
 * muc-forward        → relay or inject a MUC packet; if we are the hub, fan out to
 *                      all other mapped spokes after injecting locally
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
        IQ.Type type = packet.getType();
        // Error/result bounce-backs must not be turned into result IQs — drop silently.
        if (type == IQ.Type.error || type == IQ.Type.result) {
            Log.debug("Ignoring IQ {} from {}", type, packet.getFrom());
            return null;
        }
        if (type != IQ.Type.set) {
            return IQ.createResultIQ(packet);
        }

        String fromDomain = packet.getFrom().getDomain();
        Element fed = packet.getChildElement();
        if (fed == null) return error(packet, "Missing federation element");

        Element child = (Element) fed.elements().stream().findFirst().orElse(null);
        if (child == null) return error(packet, "Empty federation element");

        switch (child.getName()) {
            case "peer-announce"       -> handlePeerAnnounce(fromDomain, child);
            case "peer-withdraw"       -> handlePeerWithdraw(fromDomain);
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
            manager.getPeerRegistry().addPeer(fromDomain);
            Log.info("Auto-registered federation peer via incoming connection: {}", fromDomain);
        }

        manager.getRoutingTable().addDirectPeer(fromDomain);

        // Only reply with full gossip if we didn't already initiate this exchange.
        // If the peer is already REACHABLE, S2SMonitor already sent our gossip and
        // this is just the remote's reply — replying again creates an infinite loop.
        boolean wasReachable = manager.getPeerRegistry().getPeer(fromDomain)
                .map(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                .orElse(false);

        manager.getPeerRegistry().updateStatus(fromDomain, PeerServer.Status.REACHABLE);

        if (!wasReachable) {
            manager.sendFullGossip(fromDomain);
        }

        if (isNew) {
            manager.propagateRoutingToAll(fromDomain);
        }
    }

    // ── peer-withdraw ──────────────────────────────────────────────────────────

    private void handlePeerWithdraw(String fromDomain) {
        Log.info("peer-withdraw from {} — marking WITHDRAWN and clearing cached state", fromDomain);
        manager.getRoomManager().clearRemoteRooms(fromDomain);
        manager.getRoutingTable().removePeer(fromDomain);
        manager.getPeerRegistry().updateStatus(fromDomain, PeerServer.Status.WITHDRAWN);
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

        if (!improved.isEmpty()) {
            manager.propagateRoutingToAll(fromDomain);
        }
    }

    // ── room-advertisement ────────────────────────────────────────────────────

    private void handleRoomAdvertisement(String fromDomain, Element el) {
        String origin      = el.attributeValue("origin");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Drop if this server already forwarded this advertisement (loop guard).
        if (via.contains(localDomain)) {
            Log.debug("room-advertisement loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;

        // Ignore advertisements about our own rooms bouncing back from peers.
        if (localDomain.equals(sourceDomain)) {
            Log.debug("room-advertisement origin is our own domain, ignoring");
            return;
        }

        List<FederatedRoom> rooms = new ArrayList<>();
        for (Element r : el.elements("room")) {
            String jid  = r.attributeValue("jid");
            String name = r.attributeValue("name", "");
            String desc = r.attributeValue("description", "");
            if (jid != null) {
                rooms.add(new FederatedRoom(jid, name, desc, sourceDomain));
            }
        }
        manager.getRoomManager().updateRemoteRooms(sourceDomain, fromDomain, rooms);
        Log.debug("room-advertisement from {} (source={}) — {} room(s)", fromDomain, sourceDomain, rooms.size());

        if (!rooms.isEmpty()) {
            // Append our domain to the via trail and relay to all other peers.
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.relayRoomAdvertisement(fromDomain, sourceDomain, rooms, newVia);
        }
    }

    // ── room-mapping ──────────────────────────────────────────────────────────

    private void handleRoomMapping(String fromDomain, Element el) {
        String destination = el.attributeValue("destination");
        String origin      = el.attributeValue("origin");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Relay if we are not the final destination (multi-hop topology).
        if (destination != null && !localDomain.equals(destination)) {
            manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
                nextHop -> {
                    for (Element map : el.elements("map")) {
                        String theirLocal  = map.attributeValue("local");
                        String theirRemote = map.attributeValue("remote");
                        if (theirLocal != null && theirRemote != null) {
                            try {
                                XMPPServer.getInstance().getPacketRouter()
                                          .route(FederationStanzaFactory.roomMapping(
                                              nextHop, destination, origin, theirLocal, theirRemote));
                            } catch (Exception e) {
                                Log.warn("Could not relay room-mapping toward {}: {}", destination, e.getMessage());
                            }
                        }
                    }
                },
                () -> Log.warn("room-mapping: no route to {}, dropping", destination)
            );
            return;
        }

        // We are the destination — store the mapping.
        // Use origin attribute when present so multi-hop senders are correctly identified.
        String actualOrigin = (origin != null) ? origin : fromDomain;
        for (Element map : el.elements("map")) {
            // "local"  = originator's local room JID (= our remote room)
            // "remote" = originator's remote room JID (= our local room)
            String theirLocal  = map.attributeValue("local");
            String theirRemote = map.attributeValue("remote");
            if (theirLocal != null && theirRemote != null) {
                manager.getRoomManager().addMapping(theirRemote, theirLocal, actualOrigin);
                Log.info("Room mapping received from {}: local={} ↔ remote={}",
                         actualOrigin, theirRemote, theirLocal);
                manager.pushVirtualPresences(theirRemote, actualOrigin, theirLocal, false);
            }
        }
    }

    // ── room-unmap ────────────────────────────────────────────────────────────

    private void handleRoomUnmap(String fromDomain, Element el) {
        String destination = el.attributeValue("destination");
        String origin      = el.attributeValue("origin");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Relay if we are not the final destination (multi-hop topology).
        if (destination != null && !localDomain.equals(destination)) {
            manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
                nextHop -> {
                    for (Element map : el.elements("map")) {
                        String theirLocal  = map.attributeValue("local");
                        String theirRemote = map.attributeValue("remote");
                        if (theirLocal != null && theirRemote != null) {
                            try {
                                XMPPServer.getInstance().getPacketRouter()
                                          .route(FederationStanzaFactory.roomUnmapping(
                                              nextHop, destination, origin, theirLocal, theirRemote));
                            } catch (Exception e) {
                                Log.warn("Could not relay room-unmap toward {}: {}", destination, e.getMessage());
                            }
                        }
                    }
                },
                () -> Log.warn("room-unmap: no route to {}, dropping", destination)
            );
            return;
        }

        String actualOrigin = (origin != null) ? origin : fromDomain;
        for (Element map : el.elements("map")) {
            String theirLocal  = map.attributeValue("local");   // originator's local  = our remote
            String theirRemote = map.attributeValue("remote");  // originator's remote = our local
            if (theirRemote != null) {
                if (theirLocal != null) {
                    manager.pushVirtualPresences(theirRemote, actualOrigin, theirLocal, true);
                }
                // Only remove the mapping for this specific originator — other spokes stay connected.
                manager.getRoomManager().removeMapping(theirRemote, actualOrigin);
                Log.info("Room mapping removed by remote {}: local={}", actualOrigin, theirRemote);
            }
        }
    }

    // ── muc-forward ───────────────────────────────────────────────────────────

    private void handleMucForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue("destination");
        String targetRoom  = el.attributeValue("targetRoom");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

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
            injectLocally(payloadEl, via, targetRoom, fromDomain);
            // Hub behavior: fan out to all other mapped spokes.
            fanOutToOtherMappings(fromDomain, payloadEl, targetRoom, via);
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

    private void injectLocally(Element payloadEl, String via, String targetRoom, String fromDomain) {
        try {
            switch (payloadEl.getName()) {
                case "message"  -> injectMessage(payloadEl, targetRoom);
                case "presence" -> injectPresence(payloadEl, targetRoom, fromDomain);
                default -> Log.warn("injectLocally: unexpected payload type '{}'", payloadEl.getName());
            }
        } catch (Exception e) {
            Log.warn("Failed to inject forwarded MUC packet: {}", e.getMessage(), e);
        }
    }

    /**
     * Relays the incoming payload to every other mapped spoke, skipping the
     * original sender and any domain already in the via trail.  This is what
     * makes a hub server automatically bridge messages between all its spokes.
     */
    private void fanOutToOtherMappings(String fromDomain, Element payloadEl,
                                       String targetRoom, String viaTrail) {
        if (targetRoom == null) return;
        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(targetRoom);
        if (mappings.size() <= 1) return;

        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String newVia = viaTrail.isEmpty() ? localDomain : viaTrail + "," + localDomain;

        for (RoomMapping m : mappings) {
            if (m.remoteDomain().equals(fromDomain)) continue;
            if (newVia.contains(m.remoteDomain())) continue;

            String nextHop = manager.getRoutingTable()
                                    .findNextHop(m.remoteDomain())
                                    .orElse(m.remoteDomain());
            try {
                Packet embedded = parsePacket(payloadEl);
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.mucForward(
                              nextHop, m.remoteDomain(), m.remoteRoomJid(), newVia, embedded));
                Log.debug("fanOut: relayed from {} to {} via {}",
                          fromDomain, m.remoteDomain(), nextHop);
            } catch (Exception e) {
                Log.warn("fanOutToOtherMappings: failed to relay to {}: {}",
                         m.remoteDomain(), e.getMessage());
            }
        }
    }

    /**
     * Delivers a forwarded groupchat message directly to each occupant of the
     * target room, bypassing MUC's non-occupant check entirely.
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
     * of the target room.  On join, also triggers a sync of local occupants back
     * to the sender so the joining user immediately sees who is already in the room.
     */
    private void injectPresence(Element presEl, String targetRoom, String fromDomain) {
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

        // On join: push our local occupants back to the sender so they immediately
        // see everyone already in the room (fixes join-ordering race).
        // Skip for sync presences (fed-origin present) to prevent ping-pong loops.
        if (!leaving && presEl.element("fed-origin") == null) {
            syncLocalOccupantsToRemote(targetRoom, occupants, fromDomain);
        }
    }

    /**
     * Pushes a synthetic join presence for every occupant of localRoom back to
     * the spoke identified by fromDomain.  Only targets the single mapping for
     * that domain so we don't double-push to other spokes.
     */
    private void syncLocalOccupantsToRemote(String localRoom,
                                            Collection<MUCOccupant> occupants,
                                            String fromDomain) {
        RoomMapping mapping = manager.getRoomManager().getMappingForLocal(localRoom, fromDomain);
        if (mapping == null) return;

        String remoteDomain  = mapping.remoteDomain();
        String remoteRoomJid = mapping.remoteRoomJid();
        String localDomain   = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

        for (MUCOccupant occupant : occupants) {
            Presence sync = new Presence();
            sync.setFrom(occupant.getUserAddress());
            FederationStanzaFactory.markAsForwarded(sync);
            try {
                XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.mucForward(
                        nextHop, remoteDomain, remoteRoomJid, localDomain, sync));
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
