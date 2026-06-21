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
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

        // Opt-in peer allowlist: when enabled, drop every federation action from a peer the
        // admin hasn't approved (default mode is open — any peer accepted). This is the trust
        // gate for internet-facing deployments where Openfire's S2S is not itself restricted.
        if (allowlistEnabled() && !manager.getPeerRegistry().isApproved(fromDomain)) {
            Log.warn("SECURITY: dropping federation '{}' from non-allowlisted peer {} "
                   + "(approve it with Add peer, or set plugin.federation.peerAllowlist=false)",
                     child.getName(), fromDomain);
            return IQ.createResultIQ(packet);
        }

        switch (child.getName()) {
            case "peer-announce"       -> handlePeerAnnounce(fromDomain, child);
            case "peer-withdraw"       -> handlePeerWithdraw(fromDomain);
            case "peer-disable"        -> handlePeerDisable(fromDomain);
            case "routing-update"      -> handleRoutingUpdate(fromDomain, child);
            case "routing-solicit"     -> handleRoutingSolicit(fromDomain);
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
        boolean isReply = "true".equals(el.attributeValue("reply"));

        PeerServer.Status current = manager.getPeerRegistry().getPeer(fromDomain)
                .map(PeerServer::getStatus).orElse(null);

        // Enforcement: if we administratively DISABLED this peer, re-assert the disable
        // instead of gossiping — even if it removed and re-created the connection.
        if (current == PeerServer.Status.DISABLED) {
            Log.debug("peer-announce from DISABLED peer {} — re-asserting disable", fromDomain);
            manager.sendPeerDisable(fromDomain);
            return;
        }

        // An announce from a peer we had marked REMOTE_DISABLED means the remote (the
        // authority) lifted its block — it only announces after enabling. Clear and
        // fall through to normal processing so federation resumes.
        if (current == PeerServer.Status.REMOTE_DISABLED) {
            Log.info("peer {} re-enabled the connection — clearing REMOTE_DISABLED", fromDomain);
            manager.getPeerRegistry().setControlStatus(fromDomain, PeerServer.Status.UNKNOWN);
        }

        Log.debug("peer-announce from {}{}", fromDomain, isReply ? " (reply)" : "");

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
            // Tell all other existing peers about this newly reachable server so they can
            // update their routing tables. Without this, configured peers that reconnect
            // are only known to us — no one else learns about them until the next triggered
            // update fires (which requires the new peer to have something new to offer us).
            manager.propagateRoutingToAll(fromDomain);
        } else if (!isReply) {
            // Steady-state keepalive: send one reply back so the reverse S2S socket —
            // which our own keepalive timer cannot reach (separate per-direction sockets,
            // each with its own idle timer) — stays warm too. A reply never triggers
            // another reply, so there is no ping-pong.
            manager.sendPeerAnnounceReply(fromDomain);
        }
    }

    // ── peer-withdraw ──────────────────────────────────────────────────────────

    private void handlePeerWithdraw(String fromDomain) {
        Log.info("peer-withdraw from {} — marking WITHDRAWN and clearing cached state", fromDomain);
        // The withdrawing peer should have sent room-unmap stanzas before this,
        // but defensively remove any remaining local mappings to that domain so
        // we stop fanning out traffic toward it (which would re-open S2S).
        for (String localJid : new ArrayList<>(manager.getRoomManager().getLocalMappings().keySet())) {
            manager.getRoomManager().removeMapping(localJid, fromDomain);
        }
        Set<String> removed = manager.getRoutingTable().removePeer(fromDomain);
        // Evict ghosts and drop cached rooms for the peer and everything reached
        // through it; clients see leave presences and stale rooms disappear.
        manager.handleUnreachableDestinations(removed.isEmpty() ? Set.of(fromDomain) : removed);
        manager.getPeerRegistry().updateStatus(fromDomain, PeerServer.Status.WITHDRAWN);
        // doPoll skips WITHDRAWN peers so onPeerDown never fires as a fallback —
        // propagate the withdrawal here while we still know what was removed.
        if (!removed.isEmpty()) {
            manager.propagateTopologyChange(fromDomain);
        }
    }

    // ── peer-disable ─────────────────────────────────────────────────────────────

    private void handlePeerDisable(String fromDomain) {
        Log.info("peer-disable from {} — tearing down and marking REMOTE_DISABLED (cannot re-enable locally)", fromDomain);
        // Record the block even if the peer wasn't known yet, so a re-add stays disabled.
        if (!manager.getPeerRegistry().contains(fromDomain)) {
            manager.getPeerRegistry().addPeer(fromDomain);
        }
        // Tear down federation toward this domain (same as a removal).
        for (String localJid : new ArrayList<>(manager.getRoomManager().getLocalMappings().keySet())) {
            manager.getRoomManager().removeMapping(localJid, fromDomain);
        }
        Set<String> removed = manager.getRoutingTable().removePeer(fromDomain);
        manager.handleUnreachableDestinations(removed.isEmpty() ? Set.of(fromDomain) : removed);
        manager.getPeerRegistry().setControlStatus(fromDomain, PeerServer.Status.REMOTE_DISABLED);
        if (!removed.isEmpty()) {
            manager.propagateTopologyChange(fromDomain);
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

        Set<String> changed = manager.getRoutingTable().updateFromPeer(fromDomain, received);
        Log.debug("routing-update from {} — {} entries, {} changed", fromDomain, received.size(), changed.size());

        if (!changed.isEmpty()) {
            // Destinations that dropped out of our table are now unreachable: evict
            // their ghost occupants and drop their cached rooms. Destinations that
            // (re)appeared are reachable again: re-sync occupants for any room mapped
            // to them. This is what makes a peer-down/up ripple through every hop.
            manager.handleUnreachableDestinations(changed);
            manager.resyncMappedDestinations(changed);
            manager.propagateRoutingToAll(fromDomain);
            // Re-flood room knowledge so remote-room caches follow the routing change.
            manager.propagateRoomsToAll();
            // If we lost any route, ask our other peers for an alternate path (and its
            // rooms) — triggered-only DV would otherwise never re-learn it.
            boolean lostRoute = changed.stream().anyMatch(d -> !manager.getRoutingTable().isReachable(d));
            if (lostRoute) manager.solicitRoutingFromAll(fromDomain);
        }
    }

    // ── routing-solicit ────────────────────────────────────────────────────────

    private void handleRoutingSolicit(String fromDomain) {
        // A peer that lost routes is asking for our current state — reply with our
        // routing table (split-horizon applied) and full room cache so it reconverges.
        Log.debug("routing-solicit from {} — replying with routing table + room state", fromDomain);
        manager.sendRoutingUpdate(fromDomain);
        manager.sendRoomState(fromDomain);
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
        String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
        if (rooms.isEmpty()) {
            // Withdrawal: the origin stopped federating its own rooms (but is still up and
            // still relaying others' rooms). Clear ONLY this origin's rooms — NOT rooms merely
            // relayed through the sender, or a hub-spoke would wipe its whole cache (everything
            // arrived via the hub). Propagate so downstream servers drop this origin's rooms too.
            manager.getRoomManager().clearRemoteRoomsForOrigin(sourceDomain);
            Log.debug("room-advertisement WITHDRAWAL from {} (source={}) — clearing origin's rooms and relaying", fromDomain, sourceDomain);
        } else {
            manager.getRoomManager().updateRemoteRooms(sourceDomain, fromDomain, rooms);
            Log.debug("room-advertisement from {} (source={}) — {} room(s)", fromDomain, sourceDomain, rooms.size());
        }
        manager.relayRoomAdvertisement(fromDomain, sourceDomain, rooms, newVia);
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
                // Authorization: only accept a mapping onto a local room the admin has
                // explicitly enabled for federation. Without this, any peer could map an
                // arbitrary (even private) local room and siphon its roster + messages.
                if (!isFederatedLocalRoom(theirRemote)) {
                    Log.warn("SECURITY: rejecting room-mapping from {} onto local room {} — "
                           + "that room is not federation-enabled here", actualOrigin, theirRemote);
                    continue;
                }
                manager.getRoomManager().addMapping(theirRemote, theirLocal, actualOrigin);
                Log.info("Room mapping received from {}: local={} ↔ remote={}",
                         actualOrigin, theirRemote, theirLocal);
                // Proactively push our full roster (real + virtual occupants) to the
                // mapping peer so its clients see everyone immediately — even if it has
                // no occupants of its own to trigger the reverse sync.
                manager.pushRosterToPeer(theirRemote, actualOrigin, theirLocal);
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
                // Drop the virtual occupants that came in through this mapping so local clients
                // see them leave. If no mapping remains for the room, every virtual occupant is
                // now unreachable — evict them ALL. This is essential for multi-hop spokes:
                // hub-relayed users (from other servers) are tracked under the relay domain, not
                // the origin, so a per-origin evict would miss them and leave ghosts.
                if (manager.getRoomManager().getMappingsForLocal(theirRemote).isEmpty()) {
                    manager.evictAllVirtualOccupantsInRoom(theirRemote);
                } else {
                    manager.evictVirtualOccupants(theirRemote, actualOrigin);
                }
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

        Log.debug("muc-forward: from={} finalDest={} targetRoom={} via=[{}] payload={}[type={},from={}] -> {}",
                  fromDomain, finalDest, targetRoom, via, payloadEl.getName(),
                  payloadEl.attributeValue("type"), payloadEl.attributeValue("from"),
                  (finalDest == null || localDomain.equals(finalDest)) ? "LOCAL+fanOut" : "RELAY");

        if (finalDest == null || localDomain.equals(finalDest)) {
            // We are the destination — inject into the local MUC room.
            // Authorization: never inject forwarded traffic into a room the admin hasn't
            // federation-enabled. directDeliver bypasses MUC's non-occupant check, so without
            // this a peer could inject (or spoof) messages/presence into ANY local room.
            if (!isFederatedLocalRoom(targetRoom)) {
                Log.warn("SECURITY: dropping muc-forward from {} into non-federated local room {} "
                       + "(type={}, from={})", fromDomain, targetRoom,
                       payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
                return;
            }
            injectLocally(payloadEl, via, targetRoom, fromDomain);
            // Hub behavior: fan out to all other mapped spokes.
            fanOutToOtherMappings(fromDomain, payloadEl, targetRoom, via);
        } else {
            // We are an intermediate hop — forward to the next hop.
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;

            // If we also have a spoke mapping whose remote room is targetRoom, inject locally now.
            // We won't receive a hub fanOut because our domain will be in the via trail.
            // Same authorization gate: only inject into a federation-enabled local room.
            if (targetRoom != null) {
                RoomMapping ownMapping = manager.getRoomManager().getMappingForRemote(targetRoom);
                if (ownMapping != null && isFederatedLocalRoom(ownMapping.localRoomJid())) {
                    injectLocally(payloadEl, via, ownMapping.localRoomJid(), fromDomain);
                }
            }

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
        if (targetRoom == null) { Log.debug("fanOut: skip — null targetRoom (from={})", fromDomain); return; }
        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(targetRoom);
        if (mappings.size() <= 1) {
            Log.debug("fanOut: skip — only {} mapping(s) for {} (from={})",
                      mappings.size(), targetRoom, fromDomain);
            return;
        }

        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Use a fresh via containing only the hub domain.  This lets relay servers forward
        // the packet without being blocked by the incoming via trail.
        // Servers already in the incoming via are excluded at the hub level: they either
        // already processed the packet (relay-injection) or are the source.
        String newVia = localDomain;
        Set<String> viaServers = viaTrail.isEmpty() ? Collections.emptySet()
            : new HashSet<>(Arrays.asList(viaTrail.split(",")));

        // Home server of the user this packet is about. Because we RESET the via trail to
        // just the local domain above, the incoming trail no longer protects against a
        // downstream relay forwarding the packet back to its origin. Skip the user's home
        // domain explicitly so a user's own presence/message never loops back to them.
        String payloadHome = originOf(virtualNick(payloadEl.attributeValue("from")), "");

        Log.debug("fanOut: enter from={} targetRoom={} via=[{}] payloadFrom={} payloadHome={} mappings={}",
                  fromDomain, targetRoom, viaTrail, payloadEl.attributeValue("from"), payloadHome,
                  mappings.stream().map(RoomMapping::remoteDomain).toList());

        for (RoomMapping m : mappings) {
            if (m.remoteDomain().equals(fromDomain)) {
                Log.debug("fanOut: skip {} — is direct sender", m.remoteDomain());
                continue;   // skip direct sender
            }
            if (viaServers.contains(m.remoteDomain())) {
                Log.debug("fanOut: skip {} — in via trail [{}]", m.remoteDomain(), viaTrail);
                continue; // skip source + relay-injected servers
            }
            if (m.remoteDomain().equals(payloadHome)) {
                Log.debug("fanOut: skip {} — is payload home", m.remoteDomain());
                continue;  // never echo a user back to their home
            }

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

        // Self-echo guard (see injectPresence): never re-deliver one of our OWN users'
        // messages looped back through a cyclic topology — they already got it locally.
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (localDomain.equals(originOf(senderNick, localDomain))) {
            Log.debug("injectMessage: dropping self-echo of local user {} into {}", senderNick, targetRoom);
            return;
        }

        String virtualFrom = targetJID.getNode() + "@" + targetJID.getDomain() + "/" + senderNick;

        for (MUCOccupant occupant : occupants) {
            Element copy = msgEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to",   occupant.getUserAddress().toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationStanzaFactory.directDeliver(delivery);
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
        // NOTE: do NOT bail out when there are no real local occupants. A pure relay
        // hub has none, but it must still track the virtual occupant and run
        // syncLocalOccupantsToRemote so it can serve the room roster to other spokes.
        Collection<MUCOccupant> occupants = room.getOccupants();

        String originalFrom = presEl.attributeValue("from");
        boolean leaving     = "unavailable".equals(presEl.attributeValue("type"));
        String senderNick   = virtualNick(originalFrom);

        // Self-echo guard: never inject one of OUR OWN local users as a remote virtual
        // occupant. On a cyclic/diamond topology a user's presence can loop back to their
        // home server through a relay's fan-out (which resets the via trail, see
        // fanOutToOtherMappings), producing the "user sees a ghost copy of themself" bug.
        // The home server already has the real occupant, so drop it here. This is the
        // robust boundary guard — it catches the loop regardless of which forwarding path
        // produced it (mirrors the home-domain exclusion in forwardVirtualOccupants).
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (localDomain.equals(originOf(senderNick, fromDomain))) {
            Log.debug("injectPresence: dropping self-echo of local user {} looped back via {} into {}",
                      senderNick, fromDomain, targetRoom);
            return;
        }

        JID targetJID      = new JID(targetRoom);
        JID virtualFromJID = new JID(targetJID.getNode(), targetJID.getDomain(), senderNick);

        for (MUCOccupant occupant : occupants) {
            Presence delivery = new Presence();
            delivery.setFrom(virtualFromJID);
            delivery.setTo(occupant.getUserAddress());
            if (leaving) delivery.setType(Presence.Type.unavailable);

            // Propagate show/status so local clients see the remote user's actual availability.
            if (!leaving) {
                Element showEl = presEl.element("show");
                if (showEl != null) delivery.getElement().addElement("show").setText(showEl.getText());
                for (Element statusEl : presEl.elements("status")) {
                    delivery.getElement().addElement("status").setText(statusEl.getText());
                }
            }

            Element x    = delivery.getElement().addElement("x", "http://jabber.org/protocol/muc#user");
            Element item = x.addElement("item");
            item.addAttribute("affiliation", "none");
            item.addAttribute("role", leaving ? "none" : "participant");
            if (originalFrom != null) item.addAttribute("jid", originalFrom);

            FederationStanzaFactory.markAsForwarded(delivery);
            FederationStanzaFactory.directDeliver(delivery);
        }
        Log.debug("injectPresence: {} virtual presence for {} (from={}, via fromDomain={}) to {} occupant(s) in {}",
                  leaving ? "leave" : "join", senderNick, originalFrom, fromDomain, occupants.size(), targetRoom);

        // Keep the virtual occupant roster up-to-date so eviction on peer removal
        // can send the right leave presences to local clients.  Track both the user's
        // HOME (origin, encoded in the "user@home" nick) and the immediate sender we got
        // them from (arrivedVia) so reachability- and mapping-driven eviction stay correct
        // on multi-hop paths without re-deriving one from the other later.
        if (leaving) {
            // Capture the occupant's split-horizon metadata BEFORE untracking so the leave
            // can be propagated the same way joins are (forwardVirtualOccupants). Fall back to
            // the immediate sender if we somehow weren't tracking it.
            String leaveOrigin = originOf(senderNick, fromDomain);
            Set<String> leaveArrivedVia = manager.getRoomManager().getVirtualOccupants(targetRoom).stream()
                    .filter(vo -> vo.nick().equals(senderNick))
                    .findFirst()
                    .map(vo -> new HashSet<>(vo.arrivedVia()))
                    .orElseGet(() -> new HashSet<>(Collections.singletonList(fromDomain)));

            // Propagate the leave to other mappings ONLY on the first removal — a duplicate
            // leave (arriving via both the fan-out and this state-based forward) removes
            // nothing and must not re-forward, or leaves would multiply at every hop.
            if (manager.getRoomManager().untrackVirtualOccupant(targetRoom, senderNick)) {
                manager.forwardVirtualLeave(targetRoom, senderNick, leaveOrigin, leaveArrivedVia);
            }
        } else {
            manager.getRoomManager().trackVirtualOccupant(targetRoom, originOf(senderNick, fromDomain),
                                                           fromDomain, senderNick);
        }

        // On join: push our local occupants back to the joiner so they immediately
        // see everyone already in the room (fixes join-ordering race).
        // Skip for sync presences (fed-origin present) to prevent ping-pong loops.
        if (!leaving && presEl.element("fed-origin") == null) {
            syncLocalOccupantsToRemote(targetRoom, occupants, fromDomain, originOf(senderNick, fromDomain));
        }
    }

    /**
     * Pushes a synthetic join presence for every occupant of localRoom back toward the
     * joining user so they see who is already in the room.
     *
     * Target selection must reach the JOINER, which is not always the domain we received
     * their presence from.  When two rooms are mapped across a relay (e.g. a 2504-room↔
     * 2501-room mapping whose servers both peer only with hub 2502), the join arrives at
     * the far end with {@code fromDomain} = the relay (2502), not the joiner's home (2504).
     * Targeting the relay's mapping would sync our occupants to the hub — which has no
     * mapping back to the joiner — so a directly-mapped local occupant would never reach
     * them.  We therefore target the mapping for the joiner's HOME ({@code joinerOrigin})
     * as well as the one matching {@code fromDomain}; routing then carries it through the
     * relay to the joiner.  Tiered so the common case sends to exactly one mapping rather
     * than duplicating to the relay too: (1) the joiner's HOME mapping; else (2) the mapping
     * for the domain we received them from; else (3) — true multi-hop where the joiner's home
     * is not directly mapped to us — sync through ALL mappings.
     */
    private void syncLocalOccupantsToRemote(String localRoom,
                                            Collection<MUCOccupant> occupants,
                                            String fromDomain, String joinerOrigin) {
        List<RoomMapping> allMappings = manager.getRoomManager().getMappingsForLocal(localRoom);
        if (allMappings.isEmpty()) return;

        // Tier 1: the joiner's HOME mapping — reaches them directly through any relay.
        List<RoomMapping> targets = allMappings.stream()
            .filter(m -> m.remoteDomain().equals(joinerOrigin))
            .collect(Collectors.toList());
        // Tier 2: else the mapping for the domain we actually received the join from.
        if (targets.isEmpty()) {
            targets = allMappings.stream()
                .filter(m -> m.remoteDomain().equals(fromDomain))
                .collect(Collectors.toList());
        }
        // Tier 3: true multi-hop (joiner's home not directly mapped) — sync through all mappings.
        if (targets.isEmpty()) targets = allMappings;

        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        for (RoomMapping mapping : targets) {
            String remoteDomain  = mapping.remoteDomain();
            String remoteRoomJid = mapping.remoteRoomJid();
            String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

            for (MUCOccupant occupant : occupants) {
                Presence sync = new Presence();
                sync.setFrom(occupant.getUserAddress());
                // Copy show/status from occupant's last known presence
                Element curEl = occupant.getPresence().getElement();
                Element showEl = curEl.element("show");
                if (showEl != null) sync.getElement().addElement("show").setText(showEl.getText());
                for (Element statusEl : curEl.elements("status")) {
                    sync.getElement().addElement("status").setText(statusEl.getText());
                }
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

            // Also forward virtual occupants reached through us so this peer sees the
            // full room, not only our directly-connected clients (excludes its own users).
            manager.forwardVirtualOccupants(localRoom, remoteDomain, remoteRoomJid);
            Log.debug("syncLocalOccupantsToRemote: pushed {} occupant(s) + virtuals from {} to {}",
                      occupants.size(), localRoom, remoteRoomJid);
        }
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

    /** Home (origin) domain of a virtual nick ("user@home"); falls back to the immediate sender. */
    private String originOf(String nick, String fallback) {
        try {
            String d = new JID(nick).getDomain();
            return (d == null || d.isEmpty()) ? fallback : d;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Finds a local MUCRoom by full JID string (room@conference.domain). */
    private MUCRoom findLocalRoom(String roomJid) {
        return manager.findLocalMucRoom(roomJid);
    }

    /**
     * A local MUC room the admin has explicitly enabled for federation. These are the only
     * rooms a remote peer may map or inject forwarded traffic into — the authorization
     * boundary that stops a peer from reading or writing rooms it was never granted.
     */
    private boolean isFederatedLocalRoom(String roomJid) {
        return roomJid != null && manager.getRoomManager().isFederated(roomJid);
    }

    /** Opt-in peer allowlist toggle (default false = open federation, current behaviour). */
    private boolean allowlistEnabled() {
        return JiveGlobals.getBooleanProperty("plugin.federation.peerAllowlist", false);
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
