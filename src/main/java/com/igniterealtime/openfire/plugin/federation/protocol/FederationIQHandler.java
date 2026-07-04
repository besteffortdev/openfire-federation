package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.UserDirectory;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.vcard.VCardManager;
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
            case "room-mapping-accept" -> handleMappingAccept(fromDomain, child);
            case "room-mapping-reject" -> handleMappingReject(fromDomain, child);
            case "room-mapping-disable"-> handleMappingDisable(fromDomain, child);
            case "room-mapping-enable" -> handleMappingEnable(fromDomain, child);
            case "room-unmap"          -> handleRoomUnmap(fromDomain, child);
            case "muc-forward"         -> handleMucForward(fromDomain, child);
            case "direct-forward"      -> handleDirectForward(fromDomain, child);
            case "presence-forward"    -> handlePresenceForward(fromDomain, child);
            case "iq-forward"          -> handleIqForward(fromDomain, child);
            case "user-directory"      -> handleUserDirectory(fromDomain, child);
            case "bookmark-push"       -> handleBookmarkPush(fromDomain, child);
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

        // A peer-announce is proof the remote's federation plugin has us configured as a peer
        // (mutual add) — this is what flips a PENDING link to REACHABLE on the next transition.
        manager.getPeerRegistry().getPeer(fromDomain)
               .ifPresent(p -> p.setRemoteConfirmed(true));

        // Link-level trust negotiation: record the remote's declared stance and block the link
        // if it disagrees with ours. Trust is a property of the LINK — both admins must agree;
        // a one-sided change blocks (TRUST_MISMATCH) rather than silently changing behaviour.
        final boolean remoteUntrusted = "true".equals(el.attributeValue("untrusted"));
        manager.getPeerRegistry().getPeer(fromDomain)
               .ifPresent(p -> p.setRemoteUntrusted(remoteUntrusted));
        boolean localUntrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        if (localUntrusted != remoteUntrusted) {
            Log.warn("Trust mismatch from {}: local untrusted={}, remote untrusted={} — blocking link",
                     fromDomain, localUntrusted, remoteUntrusted);
            manager.blockForTrustMismatch(fromDomain);
            return;
        }
        // Stances agree — if we were holding a prior mismatch block, lift it so we can re-gossip.
        if (current == PeerServer.Status.TRUST_MISMATCH) {
            manager.getPeerRegistry().clearTrustMismatch(fromDomain);
            current = PeerServer.Status.UNKNOWN;
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
            // Run the SAME peer-up sequence as S2SMonitor.onPeerUp. Marking the peer REACHABLE
            // here means the monitor's poll sees no transition and onPeerUp never fires, so
            // without these the side that learns of the link via an inbound announce (a ~10s
            // race it loses about half the time) would never pull the peer's state, re-sync
            // mapped room rosters, or revive PENDING_OUT mapping requests toward it.
            manager.solicitRouting(fromDomain);
            manager.resyncMappedDestinations(Set.of(fromDomain));
            manager.resendPendingRequests(fromDomain);
            manager.publishDirectoryTo(fromDomain);
            manager.pushBookmarksTo(fromDomain);
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
        // The remote no longer has us configured — require a fresh announce (mutual re-add)
        // before a future link shows REACHABLE again.
        manager.getPeerRegistry().getPeer(fromDomain).ifPresent(p -> p.setRemoteConfirmed(false));
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
        // The remote blocked us — require a fresh announce (it re-enabling) before REACHABLE.
        manager.getPeerRegistry().getPeer(fromDomain).ifPresent(p -> p.setRemoteConfirmed(false));
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
                // Admin-denied advertisement: never install this destination when it is
                // advertised by THIS peer. Leaving it out of `received` also lets the
                // stale-withdrawal logic drop a previously-installed route via this peer.
                if (manager.getPeerRegistry().isRouteDenied(fromDomain, dest)) {
                    Log.debug("routing-update from {}: destination {} is denied by admin — skipping", fromDomain, dest);
                    continue;
                }
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
        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.debug("room-advertisement loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;

        // Ignore advertisements about our own rooms bouncing back from peers.
        if (localDomain.equals(sourceDomain)) {
            Log.debug("room-advertisement origin is our own domain, ignoring");
            return;
        }

        // Admin denied this origin's advertisements from this peer — drop without caching
        // or relaying (mirrors the routing-update filter, so rooms and routes stay in step).
        if (manager.getPeerRegistry().isRouteDenied(fromDomain, sourceDomain)) {
            Log.debug("room-advertisement from {} for denied origin {} — dropping", fromDomain, sourceDomain);
            return;
        }

        List<FederatedRoom> rooms = new ArrayList<>();
        for (Element r : el.elements("room")) {
            String jid  = r.attributeValue("jid");
            String name = r.attributeValue("name", "");
            String desc = r.attributeValue("description", "");
            // Carry the per-room visibility ACL so we enforce it when relaying onward (absent = all).
            String visibleto = r.attributeValue("visibleto", "");
            java.util.Set<String> visibleTo = new java.util.LinkedHashSet<>();
            for (String s : visibleto.split(",")) if (!s.isBlank()) visibleTo.add(s.strip().toLowerCase());
            if (jid != null) {
                rooms.add(new FederatedRoom(jid, name, desc, sourceDomain, visibleTo));
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
                            // theirRemote is a room IN OUR NETWORK homed on `destination`.
                            if (!untrustedAllowsServer(fromDomain, destination)) {
                                Log.warn("SECURITY: rejecting relayed room-mapping from untrusted peer {} "
                                       + "toward room {} — server {} is not exposed to it",
                                       fromDomain, theirRemote, destination);
                                continue;
                            }
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
                if (!untrustedAllowsServer(fromDomain, localDomain)) {
                    Log.warn("SECURITY: rejecting room-mapping from untrusted peer {} onto local room {} — "
                           + "this server ({}) is not exposed to it", fromDomain, theirRemote, localDomain);
                    continue;
                }
                // Consent: store the request PENDING_IN — it does not forward until we accept.
                manager.getRoomManager().addMapping(theirRemote, theirLocal, actualOrigin,
                                                    RoomMapping.State.PENDING_IN, "");
                Log.info("Mapping request received from {}: local={} ↔ remote={} (pending)",
                         actualOrigin, theirRemote, theirLocal);
                // Auto-accept if the admin has opened this room to anyone.
                if (manager.getRoomManager().isAutoAccept(theirRemote)) {
                    manager.acceptMapping(theirRemote, actualOrigin);
                }
            }
        }
    }

    // ── room-mapping lifecycle (accept / reject / disable / enable) ─────────────

    private void handleMappingAccept(String fromDomain, Element el) {
        if (relayMappingControl("room-mapping-accept", el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue("local");   // sender's local = our remote room
            String ourLocal   = map.attributeValue("remote");  // our local room
            if (ourLocal != null) manager.onMappingAccepted(ourLocal, actualOrigin, theirLocal, token);
        }
    }

    private void handleMappingReject(String fromDomain, Element el) {
        if (relayMappingControl("room-mapping-reject", el)) return;
        String actualOrigin = originOf(el, fromDomain);
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue("remote");
            if (ourLocal != null) manager.onMappingRejected(ourLocal, actualOrigin);
        }
    }

    private void handleMappingDisable(String fromDomain, Element el) {
        if (relayMappingControl("room-mapping-disable", el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue("remote");
            if (ourLocal != null && tokenOk(ourLocal, actualOrigin, token, "disable")) {
                manager.onMappingDisabledByPeer(ourLocal, actualOrigin);
            }
        }
    }

    private void handleMappingEnable(String fromDomain, Element el) {
        if (relayMappingControl("room-mapping-enable", el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue("local");
            String ourLocal   = map.attributeValue("remote");
            if (ourLocal != null && tokenOk(ourLocal, actualOrigin, token, "enable")) {
                manager.onMappingEnabledByPeer(ourLocal, actualOrigin, theirLocal);
            }
        }
    }

    private String originOf(Element el, String fromDomain) {
        String origin = el.attributeValue("origin");
        return (origin != null) ? origin : fromDomain;
    }

    /**
     * Relays a mapping-lifecycle IQ toward its final destination if we are not it. Returns true when
     * relayed (the caller must stop), false when we are the destination and should apply it locally.
     */
    private boolean relayMappingControl(String element, Element el) {
        String destination = el.attributeValue("destination");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (destination == null || localDomain.equals(destination)) return false;
        String origin = el.attributeValue("origin");
        String token  = el.attributeValue("token");
        String reason = el.attributeValue("reason");
        manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
            nextHop -> {
                for (Element map : el.elements("map")) {
                    String l = map.attributeValue("local");
                    String r = map.attributeValue("remote");
                    if (l != null && r != null) {
                        try {
                            XMPPServer.getInstance().getPacketRouter().route(
                                FederationStanzaFactory.mappingLifecycle(element, nextHop, destination, origin, l, r, token, reason));
                        } catch (Exception e) {
                            Log.warn("Could not relay {} toward {}: {}", element, destination, e.getMessage());
                        }
                    }
                }
            },
            () -> Log.warn("{}: no route to {}, dropping", element, destination));
        return true;
    }

    /** Validates a lifecycle token against the stored mapping (empty stored token = legacy, accepted). */
    private boolean tokenOk(String localJid, String remoteDomain, String incomingToken, String op) {
        RoomMapping m = manager.getRoomManager().getMappingForLocal(localJid, remoteDomain);
        if (m == null) return false;
        String stored = m.token();
        if (stored == null || stored.isEmpty() || stored.equals(incomingToken)) return true;
        Log.warn("SECURITY: dropping mapping-{} for {} from {} — token mismatch", op, localJid, remoteDomain);
        return false;
    }

    // ── room-unmap ────────────────────────────────────────────────────────────

    private void handleRoomUnmap(String fromDomain, Element el) {
        String destination = el.attributeValue("destination");
        String origin      = el.attributeValue("origin");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Relay if we are not the final destination (multi-hop topology).
        if (destination != null && !localDomain.equals(destination)) {
            // Carry the consent token across the relay — without it the final destination's
            // tokenOk() check fails (non-empty stored vs empty relayed) and it drops the unmap,
            // leaving hub-relayed cross-spoke occupants behind as ghosts.
            String relayToken = el.attributeValue("token", "");
            manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
                nextHop -> {
                    for (Element map : el.elements("map")) {
                        String theirLocal  = map.attributeValue("local");
                        String theirRemote = map.attributeValue("remote");
                        if (theirLocal != null && theirRemote != null) {
                            try {
                                XMPPServer.getInstance().getPacketRouter()
                                          .route(FederationStanzaFactory.roomUnmapping(
                                              nextHop, destination, origin, theirLocal, theirRemote, relayToken));
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
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal  = map.attributeValue("local");   // originator's local  = our remote
            String theirRemote = map.attributeValue("remote");  // originator's remote = our local
            if (theirRemote != null) {
                if (!tokenOk(theirRemote, actualOrigin, token, "unmap")) continue;
                if (theirLocal != null) {
                    manager.pushVirtualPresences(theirRemote, actualOrigin, theirLocal, true);
                }
                // Only remove the mapping for this specific originator — other spokes stay connected.
                manager.getRoomManager().removeMapping(theirRemote, actualOrigin);
                // Drop the virtual occupants reached through this mapping (and, if it was the
                // last active mapping, every virtual occupant in the room — catching hub-relayed
                // cross-spoke users that origin/arrivedVia keying would miss on a multi-hop path).
                // Same teardown path used by mapping disable.
                manager.evictForInactiveMapping(theirRemote, actualOrigin);
                Log.info("Room mapping removed by remote {}: local={}", actualOrigin, theirRemote);
            }
        }
    }

    // ── muc-forward ───────────────────────────────────────────────────────────

    private void handleMucForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue("destination");
        String targetRoom  = el.attributeValue("targetRoom");
        String via         = el.attributeValue("via", "");
        // The mapped server this traffic enters us through (far end of our mapping / a hub).
        // Used as the occupant's arrivedVia so a mapping-disable evicts exactly its arrivals.
        // Older peers omit it — fall back to the immediate sender so behaviour degrades safely.
        String src         = el.attributeValue("src", fromDomain);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("muc-forward loop detected (via={}), dropping", via);
            return;
        }

        Element payloadEl = (Element) el.elements().stream().findFirst().orElse(null);
        if (payloadEl == null) {
            Log.warn("muc-forward from {} has no payload", fromDomain);
            return;
        }

        // Origin (from-spoofing) gate. rejectLocalClaim=false: trusted diamond/hub echoes of our
        // own users are legitimate here and neutralized by inject's self-echo guards instead.
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "muc-forward", false)) return;

        // Untrusted-peer exposure gate: an untrusted peer may only move traffic toward a server
        // it has been exposed to, whether we inject the room here or relay it onward. The target
        // room is homed on finalDest (or on us when finalDest is absent/local).
        String targetServer = (finalDest == null || localDomain.equals(finalDest)) ? localDomain : finalDest;
        if (!untrustedAllowsServer(fromDomain, targetServer)) {
            Log.warn("SECURITY: dropping muc-forward from untrusted peer {} for room {} on non-exposed "
                   + "server {} (type={}, from={})", fromDomain, targetRoom, targetServer,
                     payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
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
            injectLocally(payloadEl, via, targetRoom, fromDomain, src);
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
                    injectLocally(payloadEl, via, ownMapping.localRoomJid(), fromDomain, src);
                }
            }

            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Packet embedded = parsePacket(payloadEl);
                        // Pure relay — preserve src so the destination still sees the original
                        // mapped server this traffic enters through, not us (a mid-path relay).
                        XMPPServer.getInstance().getPacketRouter()
                                  .route(FederationStanzaFactory.mucForward(
                                      nextHop, finalDest, targetRoom, newVia, src, embedded));
                    } catch (Exception e) {
                        Log.warn("Could not relay muc-forward toward {}: {}", finalDest, e.getMessage());
                    }
                },
                () -> Log.warn("muc-forward: no route to {}, dropping", finalDest)
            );
        }
    }

    // ── direct-forward (1:1 private messaging) ─────────────────────────────────

    /**
     * Relays or delivers a 1:1 message carried over the overlay.  Mirrors {@link #handleMucForward}
     * but for user-addressed messages: no room, no fan-out.  At the final destination the embedded
     * message is delivered straight to the recipient (online session or offline storage) via
     * {@code directDeliver}; an intermediate hop forwards it on toward the destination.
     *
     * <p>No untrusted-peer gate here on purpose: 1:1 messaging must be able to cross an untrusted
     * edge (that is the whole point of the overlay), and reachability is already constrained by the
     * routing table.  The embedded {@code from} is trusted to the same degree as any S2S sender.
     */
    private void handleDirectForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue("destination");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("direct-forward loop detected (via={}), dropping", via);
            return;
        }

        Element payloadEl = el.element("message");
        if (payloadEl == null) {
            Log.warn("direct-forward from {} has no message payload", fromDomain);
            return;
        }

        // Origin (from-spoofing) gate — see payloadOriginOk. Applied per hop (relay AND deliver).
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "direct-forward", true)) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            // We are the destination — deliver to the local recipient (bypasses interceptors, so the
            // recipient's reply is a fresh outbound message caught and relayed back symmetrically).
            Message msg = new Message(payloadEl.createCopy());
            JID mto = msg.getTo();
            if (mto != null && isLocalConferenceDomain(mto.getDomain())) {
                // groupchat/PM from a remote occupant to a LOCAL room (federated remote-room join) —
                // route to the MUC service unmarked so the room broadcasts it and the re-broadcast to
                // other remote occupants stays clean for relay back.  No re-relay risk: a stanza
                // addressed to a local conference hits the interceptor's local-conference early-returns.
                XMPPServer.getInstance().getPacketRouter().route(msg);
                Log.info("direct-forward: delivered MUC message {} -> {} (from {})", msg.getFrom(), mto, fromDomain);
                return;
            }
            FederationStanzaFactory.markAsForwarded(msg);
            FederationStanzaFactory.directDeliver(msg);
            Log.info("direct-forward: delivered 1:1 {} -> {} (from {})", msg.getFrom(), msg.getTo(), fromDomain);
        } else {
            // Intermediate hop — forward toward the destination, appending ourselves to the trail.
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Message embedded = new Message(payloadEl.createCopy());
                        XMPPServer.getInstance().getPacketRouter()
                                  .route(FederationStanzaFactory.directForward(nextHop, finalDest, newVia, embedded));
                    } catch (Exception e) {
                        Log.warn("Could not relay direct-forward toward {}: {}", finalDest, e.getMessage());
                    }
                },
                () -> Log.warn("direct-forward: no route to {}, dropping", finalDest)
            );
        }
    }

    // ── presence-forward (1:1 subscription + presence) ─────────────────────────

    /**
     * Relays or delivers a 1:1 presence carried over the overlay.  Same relay shape as
     * {@link #handleDirectForward}, but at the destination the presence is routed through the normal
     * packet router (NOT directDeliver) so Openfire's roster/subscription/presence engine processes
     * it — recording subscriptions and broadcasting presence as if it had arrived via S2S.  Marked
     * forwarded first so our own interceptor doesn't re-relay it.
     */
    private void handlePresenceForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue("destination");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("presence-forward loop detected (via={}), dropping", via);
            return;
        }

        Element payloadEl = el.element("presence");
        if (payloadEl == null) {
            Log.warn("presence-forward from {} has no presence payload", fromDomain);
            return;
        }

        // Origin (from-spoofing) gate — the critical one: a forged `subscribed`/presence here
        // would flow straight into Openfire's roster engine at the destination.
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "presence-forward", true)) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            Presence pres = new Presence(payloadEl.createCopy());
            // A probe for a local user: Openfire's own answer would be routed past the interceptor and
            // never cross the overlay, so answer explicitly with the user's current presence.
            if (pres.getType() == Presence.Type.probe) {
                manager.answerPresenceProbe(pres.getFrom(), pres.getTo());
                Log.info("presence-forward: answered probe {} -> {} (from {})",
                         pres.getFrom(), pres.getTo(), fromDomain);
                return;
            }
            JID pto = pres.getTo();
            if (pto != null && isLocalConferenceDomain(pto.getDomain())) {
                // A remote user joining/leaving a LOCAL room directly over the overlay — hand it to
                // the MUC service unmarked so the room's reflected presences stay clean and get
                // relayed back to the occupant.  No re-relay risk (local-conference early-returns).
                XMPPServer.getInstance().getPacketRouter().route(pres);
                Log.info("presence-forward: delivered MUC {} {} -> {} (from {})",
                         pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), pto, fromDomain);
                return;
            }
            FederationStanzaFactory.markAsForwarded(pres);
            XMPPServer.getInstance().getPacketRouter().route(pres);   // let Openfire's roster engine handle it
            Log.info("presence-forward: delivered 1:1 {} {} -> {} (from {})",
                     pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), pres.getTo(), fromDomain);
        } else {
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Presence embedded = new Presence(payloadEl.createCopy());
                        XMPPServer.getInstance().getPacketRouter()
                                  .route(FederationStanzaFactory.presenceForward(nextHop, finalDest, newVia, embedded));
                    } catch (Exception e) {
                        Log.warn("Could not relay presence-forward toward {}: {}", finalDest, e.getMessage());
                    }
                },
                () -> Log.warn("presence-forward: no route to {}, dropping", finalDest)
            );
        }
    }

    // ── iq-forward (vCard / disco / caps / version / ping / PEP) ───────────────

    /**
     * Relays or delivers a user-addressed IQ carried over the overlay.  Same relay shape as
     * {@link #handleDirectForward}; at the destination the unwrapped IQ is routed through the normal
     * packet router so Openfire's own handlers answer it (vCard, disco, PEP) or deliver it to the
     * target session.  No {@code fed-origin} marker — it would corrupt single-child IQ payloads (e.g.
     * {@code <vCard>}); loop-safety comes from addressing (every delivered IQ targets a local JID at
     * its hop, so the interceptor's multi-hop check never re-relays it). id/from/to are preserved, so
     * the result relays back the same way and correlates.
     */
    private void handleIqForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue("destination");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("iq-forward loop detected (via={}), dropping", via);
            return;
        }

        Element payloadEl = el.element("iq");
        if (payloadEl == null) {
            Log.warn("iq-forward from {} has no iq payload", fromDomain);
            return;
        }

        // Origin (from-spoofing) gate — a forged `set` IQ delivered to the router could mutate
        // server-side state (roster, vCard) as the claimed user.
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "iq-forward", true)) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            IQ iq = new IQ(payloadEl.createCopy());
            IQ.Type type = iq.getType();
            if (type == IQ.Type.get || type == IQ.Type.set) {
                // A REQUEST landed here.  Openfire's own handlers (vCard etc.) deliver their reply via
                // the internal deliverer — NOT through the PacketInterceptor — so a reply to a multi-hop
                // requester is handed to (non-existent) native S2S and lost.  We can't rely on Openfire
                // routing the reply back, so we build the reply ourselves and relay it over the overlay.
                if (!answerVCardLocally(iq, fromDomain)) {
                    // Not a vCard request: best-effort local delivery (Openfire may answer it, but its
                    // reply does not yet relay back over multi-hop — see iq-forward reply limitation).
                    XMPPServer.getInstance().getPacketRouter().route(iq);
                    Log.info("iq-forward: delivered 1:1 {} {} -> {} (from {}) [reply relay best-effort]",
                             type, iq.getFrom(), iq.getTo(), fromDomain);
                }
            } else {
                // A REPLY (result/error) reached its final destination — deliver to the local client.
                XMPPServer.getInstance().getPacketRouter().route(iq);
                Log.info("iq-forward: delivered 1:1 reply {} {} -> {} (from {})",
                         type, iq.getFrom(), iq.getTo(), fromDomain);
            }
        } else {
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        IQ embedded = new IQ(payloadEl.createCopy());
                        XMPPServer.getInstance().getPacketRouter()
                                  .route(FederationStanzaFactory.iqForward(nextHop, finalDest, newVia, embedded));
                    } catch (Exception e) {
                        Log.warn("Could not relay iq-forward toward {}: {}", finalDest, e.getMessage());
                    }
                },
                () -> Log.warn("iq-forward: no route to {}, dropping", finalDest)
            );
        }
    }

    /** True if {@code domain} is one of this server's local MUC service domains. */
    private boolean isLocalConferenceDomain(String domain) {
        if (domain == null) return false;
        return XMPPServer.getInstance().getMultiUserChatManager()
                         .getMultiUserChatServices().stream()
                         .anyMatch(svc -> svc.getServiceDomain().equals(domain));
    }

    /**
     * If {@code request} is a vCard get (XEP-0054, namespace {@code vcard-temp}) addressed to a local
     * user, builds that user's vCard locally via {@link VCardManager} and relays the result back to the
     * remote requester over the overlay — then returns true.  Returns false for anything else so the
     * caller can fall back to plain local delivery.
     *
     * We build the reply ourselves (rather than letting Openfire's vCard handler answer) because that
     * handler delivers its reply through the internal deliverer, which neither passes the
     * PacketInterceptor nor fires an {@code IQResultListener}, so the reply to a multi-hop requester is
     * handed to native S2S (no direct link) and lost.  This covers the avatar (vCard {@code PHOTO},
     * XEP-0153) and profile fields — the data clients actually fetch for a contact.
     */
    private boolean answerVCardLocally(IQ request, String fromDomain) {
        Element child = request.getChildElement();
        if (request.getType() != IQ.Type.get
                || child == null
                || !"vCard".equals(child.getName())
                || !"vcard-temp".equals(child.getNamespaceURI())) {
            return false;
        }
        JID contact = request.getTo();                  // e.g. 2501-user@server1 (whose vCard is wanted)
        if (contact == null || contact.getNode() == null) return false;

        IQ result = IQ.createResultIQ(request);         // from=contact, to=requester, same id (correlates)
        try {
            Element vcard = VCardManager.getInstance().getVCard(contact.getNode());
            result.setChildElement(vcard != null ? vcard.createCopy()
                                                  : org.dom4j.DocumentHelper.createElement("vCard")
                                                        .addNamespace("", "vcard-temp"));
        } catch (Exception e) {
            Log.warn("iq-forward: failed to build vCard for {}: {}", contact, e.getMessage());
            result.setChildElement("vCard", "vcard-temp");
        }
        if (manager.forwardDirectIq(result)) {
            Log.info("iq-forward: answered + relayed vCard for {} -> {} (from {})",
                     contact, request.getFrom(), fromDomain);
        } else {
            Log.warn("iq-forward: built vCard for {} but no route back to {}", contact, request.getFrom());
        }
        return true;
    }

    // ── user-directory (opt-in online-user gossip) ─────────────────────────────

    /** Caches an inbound user-directory and relays it onward (loop-guarded). */
    private void handleUserDirectory(String fromDomain, Element el) {
        String origin      = el.attributeValue("origin");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.debug("user-directory loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;
        if (localDomain.equals(sourceDomain)) {
            Log.debug("user-directory origin is our own domain, ignoring");
            return;
        }

        List<UserDirectory.UserPresence> users = new ArrayList<>();
        for (Element u : el.elements("user")) {
            String jid = u.attributeValue("jid");
            if (jid != null && !jid.isBlank()) {
                users.add(new UserDirectory.UserPresence(
                        jid.strip(),
                        u.attributeValue("show",   ""),
                        u.attributeValue("status", "")));
            }
        }
        String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
        manager.handleUserDirectory(fromDomain, sourceDomain, users, newVia);
        Log.debug("user-directory from {} (source={}) — {} user(s)", fromDomain, sourceDomain, users.size());
    }

    // ── bookmark-push (XEP-0048 connected-client advertisement) ────────────────

    /** Injects an inbound bookmark-push into local users' storage and relays it onward (loop-guarded). */
    private void handleBookmarkPush(String fromDomain, Element el) {
        String origin      = el.attributeValue("origin");
        String via         = el.attributeValue("via", "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.debug("bookmark-push loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;
        if (localDomain.equals(sourceDomain)) {
            Log.debug("bookmark-push origin is our own domain, ignoring");
            return;
        }

        List<UserDirectory.UserPresence> users = new ArrayList<>();
        for (Element u : el.elements("user")) {
            String jid = u.attributeValue("jid");
            if (jid != null && !jid.isBlank()) {
                users.add(new UserDirectory.UserPresence(
                        jid.strip(),
                        u.attributeValue("show",   ""),
                        u.attributeValue("status", "")));
            }
        }
        String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
        manager.handleBookmarkPush(fromDomain, sourceDomain, users, newVia);
        Log.debug("bookmark-push from {} (source={}) — {} user(s)", fromDomain, sourceDomain, users.size());
    }

    private void injectLocally(Element payloadEl, String via, String targetRoom, String fromDomain, String src) {
        try {
            switch (payloadEl.getName()) {
                case "message"  -> injectMessage(payloadEl, targetRoom);
                case "presence" -> injectPresence(payloadEl, targetRoom, fromDomain, src);
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
                // We are the mapped server (hub) for this spoke, so the occupant enters the
                // spoke through US — stamp src with our own domain, not the original source.
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.mucForward(
                              nextHop, m.remoteDomain(), m.remoteRoomJid(), newVia, localDomain, embedded));
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
    private void injectPresence(Element presEl, String targetRoom, String fromDomain, String src) {
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

        // Track this virtual occupant's live presence for the admin "who's here" view.
        if (leaving) {
            manager.getRoomManager().clearVirtualOccupantPresence(targetRoom, senderNick);
        } else {
            Element showEl0   = presEl.element("show");
            Element statusEl0 = presEl.element("status");
            manager.getRoomManager().setVirtualOccupantPresence(targetRoom, senderNick,
                    showEl0   != null ? showEl0.getText()   : "",
                    statusEl0 != null ? statusEl0.getText() : "");
        }

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
            // the mapped server it entered through (src) if we somehow weren't tracking it.
            String leaveOrigin = originOf(senderNick, fromDomain);
            Set<String> leaveArrivedVia = manager.getRoomManager().getVirtualOccupants(targetRoom).stream()
                    .filter(vo -> vo.nick().equals(senderNick))
                    .findFirst()
                    .map(vo -> new HashSet<>(vo.arrivedVia()))
                    .orElseGet(() -> new HashSet<>(Collections.singletonList(src)));

            // Propagate the leave to other mappings ONLY on the first removal — a duplicate
            // leave (arriving via both the fan-out and this state-based forward) removes
            // nothing and must not re-forward, or leaves would multiply at every hop.
            if (manager.getRoomManager().untrackVirtualOccupant(targetRoom, senderNick)) {
                manager.forwardVirtualLeave(targetRoom, senderNick, leaveOrigin, leaveArrivedVia);
            }
        } else {
            // arrivedVia = the MAPPED server we entered through (src), NOT the relay neighbour,
            // so a mapping-disable can evict exactly the occupants that came through that mapping
            // — including hub-relayed cross-spoke users, whose src is the hub, not their home.
            manager.getRoomManager().trackVirtualOccupant(targetRoom, originOf(senderNick, fromDomain),
                                                           src, senderNick);
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
                            nextHop, remoteDomain, remoteRoomJid, localDomain, localDomain, sync));
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

    /**
     * Origin check for overlay-forwarded payloads — the overlay's substitute for native S2S's
     * stream-level {@code from} validation (a real S2S stream may only carry stanzas from its
     * authenticated domain; the overlay would otherwise deliver any embedded {@code from} as-is).
     *
     * <p>Two tiers:
     * <ul>
     *   <li><b>Local claim (any sender, when {@code rejectLocalClaim}):</b> a payload claiming to
     *       originate from THIS server's domain or one of its MUC services is dropped — no
     *       legitimate inbound 1:1 forward carries our own users as sender. Not enforced for
     *       trusted muc-forward ({@code rejectLocalClaim=false}): on cyclic/diamond topologies a
     *       local user's own room traffic can legitimately echo back through a hub fan-out, and
     *       the self-echo guards in injectMessage/injectPresence already neutralize it silently.</li>
     *   <li><b>Route-back (untrusted senders only):</b> the claimed origin's host domain must be
     *       the peer itself, a destination routed THROUGH that peer, or not routable here at all
     *       (cross-edge traffic where routes are exposure-filtered — unverifiable by design and
     *       bounded by the exposure gates). An origin we route via a DIFFERENT neighbour is a
     *       forged our-side identity and is dropped. NOT applied to trusted peers: asymmetric DV
     *       routes (diamonds) and hub fan-out make legitimate trusted traffic arrive off the
     *       origin's route, so a strict check there would break real flows — the trusted mesh
     *       remains a documented trust boundary.</li>
     * </ul>
     */
    private boolean payloadOriginOk(String fromDomain, String payloadFrom, String what,
                                    boolean rejectLocalClaim) {
        if (payloadFrom == null || payloadFrom.isEmpty()) return true;   // no identity claimed
        boolean untrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        String domain;
        try { domain = new JID(payloadFrom).getDomain(); } catch (Exception e) { domain = null; }
        if (domain == null || domain.isEmpty()) {
            if (!untrusted) return true;                                 // preserve trusted behaviour
            Log.warn("SECURITY: dropping {} from untrusted peer {} — unparseable payload from '{}'",
                     what, fromDomain, payloadFrom);
            return false;
        }
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String host = originHost(domain, fromDomain, localDomain);
        if (host.equals(localDomain) || isLocalConferenceDomain(domain)) {
            if (rejectLocalClaim || untrusted) {
                Log.warn("SECURITY: dropping {} from {} — payload from '{}' claims to originate on "
                       + "THIS server; a peer never speaks for our own users", what, fromDomain, payloadFrom);
                return false;
            }
            return true;   // trusted muc-forward echo — inject's self-echo guard handles it
        }
        if (!untrusted) return true;
        if (host.equals(fromDomain)) return true;                        // the peer's own users
        java.util.Optional<String> hop = manager.getRoutingTable().findNextHop(host);
        if (hop.isEmpty()) return true;   // not routable here (exposure-filtered edge) — unverifiable
        if (hop.get().equals(fromDomain)) return true;                   // origin is behind the sender
        Log.warn("SECURITY: dropping {} from untrusted peer {} — payload from '{}' claims origin {} "
               + "which routes via {} (forged identity from the wrong direction)",
                 what, fromDomain, payloadFrom, host, hop.get());
        return false;
    }

    /**
     * The host SERVER a payload origin domain belongs to: the domain itself when it is the
     * sender, local, or a known routing destination; else its parent label-stripped domain
     * ({@code conference.X} → {@code X}) when THAT is known — components are never routing
     * destinations themselves. Falls back to the domain unchanged (then unroutable → allowed).
     */
    private String originHost(String domain, String fromDomain, String localDomain) {
        if (domain.equals(fromDomain) || domain.equals(localDomain)
                || manager.getRoutingTable().isReachable(domain)) return domain;
        int dot = domain.indexOf('.');
        if (dot > 0 && dot < domain.length() - 1) {
            String parent = domain.substring(dot + 1);
            if (parent.equals(fromDomain) || parent.equals(localDomain)
                    || manager.getRoutingTable().isReachable(parent)) return parent;
        }
        return domain;
    }

    /**
     * Inbound exposure gate for untrusted peers: a trusted peer is always allowed; an
     * untrusted peer may only map/inject/relay toward rooms homed on a server it has been
     * exposed to. {@code serverDomain} is the home server of the target room, already in scope
     * at each call site (the routing destination, the final-dest, or our own domain for a local
     * room). Composed with {@link #isFederatedLocalRoom} so an untrusted peer is bounded by BOTH
     * "the room is federation-enabled" and "its home server was exposed to this peer".
     */
    private boolean untrustedAllowsServer(String fromDomain, String serverDomain) {
        if (!manager.getPeerRegistry().isUntrusted(fromDomain)) return true;
        return serverDomain != null && manager.getPeerRegistry().getExposedServers(fromDomain).contains(serverDomain);
    }

    /** Peer allowlist toggle (default true = only admin-approved peers may federate). */
    private boolean allowlistEnabled() {
        return com.igniterealtime.openfire.plugin.federation.FederationProperties.PEER_ALLOWLIST.getValue();
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
