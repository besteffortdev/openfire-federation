package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.ContactListManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.pep.PEPService;
import org.jivesoftware.openfire.pep.PEPServiceManager;
import org.jivesoftware.openfire.pubsub.Node;
import org.jivesoftware.openfire.pubsub.PublishedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;
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
 * mapping-ping/pong  → end-to-end probe of an active mapping's path; catches a route
 *                      silently broken mid-way (e.g. an intermediate deny)
 * contact-list       → the contacts another server shares with us (or relay it on)
 */
public class FederationIQHandler extends IQHandler {

    private static final Logger Log = LoggerFactory.getLogger(FederationIQHandler.class);

    private static final String NS_STANZA_ID   = "urn:xmpp:sid:0";
    static final String NS_OCCUPANT_ID = "urn:xmpp:occupant-id:0";
    /**
     * A peer-supplied stanza-id we reuse verbatim: Openfire's UUIDs and similar opaque tokens. At least
     * 16 characters, so it cannot equal the small numeric id Monitoring's MAM falls back to for an
     * archived message that has no stanza-id of its own (federated messages archived before 1.10.10).
     */
    private static final Pattern REUSABLE_STANZA_ID = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    private final IQHandlerInfo   info;
    private final FederationManager manager;

    /**
     * A groupchat delivery that {@link #injectMessage} couldn't place because its target room had
     * zero local occupants at that exact instant — buffered briefly so a client reconnecting from a
     * momentary blip (network hiccup, app backgrounding) still gets it live, instead of only being
     * able to retrieve it later via a MAM query (see {@link #archiveToRoomHistory}, which already ran
     * before this is queued — {@code deliverEl}/{@code virtualFrom} are the already-rewritten,
     * already-archived form, ready to redeliver as-is). Flushed by {@link #flushPendingDeliveries} on
     * the next real local occupant join for that room ({@code MUCEventListener.occupantJoined} — see
     * {@code RoomCreationListener}); otherwise pruned lazily once {@link #PENDING_DELIVERY_TTL_MS}
     * elapses. Bounded per room ({@link #PENDING_DELIVERY_MAX_PER_ROOM}) so a room that just stays
     * empty can't grow this map without limit.
     */
    private record PendingDelivery(Element deliverEl, String virtualFrom, long queuedAt) { }

    private static final long PENDING_DELIVERY_TTL_MS = 60_000;
    private static final int  PENDING_DELIVERY_MAX_PER_ROOM = 20;
    private final ConcurrentHashMap<String, Deque<PendingDelivery>> pendingRoomDeliveries = new ConcurrentHashMap<>();

    /**
     * Moderations relayed from other servers and applied here, per room, newest last, for
     * {@link #replayModerations}. In memory only: after a restart a late joiner sees those messages again.
     */
    private record StoredModeration(String targetId, Element announcement, long at) { }
    private static final int MODERATIONS_KEPT_PER_ROOM = 200;
    private final ConcurrentHashMap<String, Deque<StoredModeration>> federatedModerations = new ConcurrentHashMap<>();

    public FederationIQHandler(FederationManager manager) {
        super("Federation IQ Handler");
        this.manager = manager;
        this.info    = new IQHandlerInfo(FederationStanzaFactory.ELEMENT, FederationStanzaFactory.NS);
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

        Element fed = packet.getChildElement();
        if (fed == null) return error(packet, "Missing federation element");

        Element child = (Element) fed.elements().stream().findFirst().orElse(null);
        if (child == null) return error(packet, "Empty federation element");

        // Sender identity: federation traffic is server-to-server, and every peer's plugin sends it
        // from its bare domain JID. Openfire dispatches an IQ to this handler on its `to` and namespace
        // alone, whoever sent it — so without this check any USER on an allowlisted server (or a local
        // client) could address us directly and be treated as that server's federation plugin, with
        // all of the peer's rights. Our own domain is refused too: no peer legitimately is us.
        JID sender = packet.getFrom();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (sender == null || sender.getNode() != null || sender.getResource() != null
                || sender.getDomain() == null || sender.getDomain().equals(localDomain)) {
            Log.warn("SECURITY: dropping federation '{}' from {} — federation traffic must come from a "
                   + "peer server's bare domain, not a user, resource, or this server",
                     child.getName(), sender);
            return IQ.createResultIQ(packet);
        }
        String fromDomain = sender.getDomain();

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
            case "mapping-ping"        -> handleMappingPing(fromDomain, child);
            case "mapping-pong"        -> handleMappingPong(fromDomain, child);
            case "muc-forward"         -> handleMucForward(fromDomain, child);
            case "direct-forward"      -> handleDirectForward(fromDomain, child);
            case "presence-forward"    -> handlePresenceForward(fromDomain, child);
            case "iq-forward"          -> handleIqForward(fromDomain, child);
            case "contact-list", "contact-list-request"
                                       -> handleContactListAction(child.getName(), fromDomain, child);
            // Retired in 1.10.13 (replaced by contact-list); a peer still on an older build may send them.
            case "user-directory", "bookmark-push"
                                       -> Log.debug("Ignoring retired federation action '{}' from {}", child.getName(), fromDomain);
            case "file-request", "file-offer", "file-chunk", "file-error"
                                       -> handleFileRelay(child.getName(), fromDomain, child);
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
            // Only reachable with the allowlist off (open federation). A server nobody added is a
            // stranger: register it UNTRUSTED with nothing exposed, so it sees nothing until an admin
            // decides what it may see — or promotes it. It used to be registered trusted, which made
            // open federation mean "every server that connects gets full trusted-peer rights".
            manager.getPeerRegistry().addPeer(fromDomain);
            manager.getPeerRegistry().setUntrusted(fromDomain, true);
            Log.info("Auto-registered federation peer via incoming connection: {} (untrusted, nothing "
                   + "exposed — review it on the Peer Servers tab)", fromDomain);
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
            manager.getPeerRegistry().setUntrusted(fromDomain, true);   // a stranger — see handlePeerAnnounce
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
        boolean untrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        List<RouteEntry> received = new ArrayList<>();
        for (Element entry : el.elements("entry")) {
            String dest = entry.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
            String via  = entry.attributeValue(FederationStanzaFactory.ATTR_VIA);
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
                // An untrusted edge advertises what lies BEHIND it, never one of our own trusted
                // peers. (Other destinations it claims are installed as edge routes, which the routing
                // table never lets out-bid a clean route through the trusted mesh.)
                if (untrusted && manager.getPeerRegistry().contains(dest)
                        && !manager.getPeerRegistry().isUntrusted(dest)) {
                    Log.warn("SECURITY: ignoring route to {} advertised by untrusted peer {} — that is "
                           + "one of this server's trusted peers", dest, fromDomain);
                    continue;
                }
                boolean edge = "true".equals(entry.attributeValue(FederationStanzaFactory.ATTR_EDGE));
                received.add(new RouteEntry(dest, via, hops, edge));
            }
        }

        Set<String> changed = manager.getRoutingTable().updateFromPeer(fromDomain, received,
                manager.getPeerRegistry()::isUntrusted);
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
            // Debounced: one topology change arrives as a burst of routing-updates (triggered
            // updates + solicit replies), and each full room flood is itself relayed onward by
            // every receiver — coalescing the burst avoids an O(N² · rooms) storm per flap.
            manager.propagateRoomsToAllDebounced();
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
        String origin      = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        // Drop if this server already forwarded this advertisement (loop guard).
        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.debug("room-advertisement loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;
        // An untrusted peer may only advertise rooms for itself or servers behind it. Otherwise it
        // could replace — or, with an empty list, wipe — any server's room list here, and we would
        // relay that across the mesh.
        if (!claimedOriginOk(fromDomain, sourceDomain, "room-advertisement")) return;

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
            // Per-room visibility ACL carried on the ad so we enforce it when relaying onward. Absence
            // is parsed as an empty set, which roomVisibleAtHop treats as "visible to nobody" — the
            // same secure default as a locally-federated room. (Same-version peers always emit the
            // attribute, including the "*" sentinel; only pre-ACL peers omit it, in which case their
            // rooms won't relay past us until they upgrade.)
            String visibleto = r.attributeValue("visibleto", "");
            java.util.Set<String> visibleTo = new java.util.LinkedHashSet<>();
            for (String s : visibleto.split(",")) if (!s.isBlank()) visibleTo.add(s.strip().toLowerCase());
            // Reject a peer-supplied room JID carrying characters that are invalid in an XMPP JID and
            // that would enable admin-console script injection or malformed routing if cached/rendered.
            if (jid != null && isSafeFederationJid(jid)) {
                rooms.add(new FederatedRoom(jid, name, desc, sourceDomain, visibleTo));
            } else if (jid != null) {
                Log.warn("SECURITY: dropping advertised room with malformed JID from {} (len={})",
                         fromDomain, jid.length());
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
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String origin      = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping")) return;

        // Relay if we are not the final destination (multi-hop topology).
        if (destination != null && !localDomain.equals(destination)) {
            manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
                nextHop -> {
                    for (Element map : el.elements("map")) {
                        String theirLocal  = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);
                        String theirRemote = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
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
            String theirLocal  = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);
            String theirRemote = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
            if (theirLocal != null && theirRemote != null) {
                // Authorization: only accept a mapping onto a local room the admin has
                // explicitly enabled for federation AND explicitly shared with this origin via
                // its visibility ACL. Without this, any peer that ever observed the room's JID
                // (e.g. a transit hop merely relaying the ad toward a different allowed
                // destination) could map an arbitrary (even private, or ACL-restricted) local
                // room and siphon its roster + messages.
                if (!manager.roomSharedWith(theirRemote, actualOrigin)) {
                    Log.warn("SECURITY: rejecting room-mapping from {} onto local room {} — "
                           + "that room is not federation-enabled here, or its visibility ACL "
                           + "does not include this origin", actualOrigin, theirRemote);
                    continue;
                }
                if (!untrustedAllowsServer(fromDomain, localDomain)) {
                    Log.warn("SECURITY: rejecting room-mapping from untrusted peer {} onto local room {} — "
                           + "this server ({}) is not exposed to it", fromDomain, theirRemote, localDomain);
                    continue;
                }
                // A request never overwrites an ESTABLISHED mapping. It used to replace whatever was
                // there with a fresh token-less PENDING_IN record, which both cut the live mapping and
                // emptied its token — after which tokenOk() accepted ANY disable/unmap for it.
                RoomMapping existing = manager.getRoomManager().getMappingForLocal(theirRemote, actualOrigin);
                if (existing != null && !handleRepeatRequest(existing, theirLocal, localDomain)) continue;
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

    /**
     * Decides what an incoming request does to a mapping we already hold for the same local room and
     * origin. Returns true when the request should be stored as a fresh PENDING_IN (replacing a record
     * that holds no consent yet), false when it has been fully handled here.
     *
     * <ul>
     *   <li><b>ACTIVE</b> — never demoted. If the request names the same remote room, the remote has
     *       probably lost its state and is waiting in PENDING_OUT: re-send our acceptance with the
     *       existing token so both ends converge.</li>
     *   <li><b>DISABLED_LOCAL / DISABLED_REMOTE</b> — an explicit decision by one admin; a request does
     *       not undo it. Recovering takes an enable, or a remove and re-add.</li>
     *   <li><b>PENDING_OUT, same remote room</b> — both admins asked for this exact pairing, which is
     *       consent from both sides. The lexicographically lower domain accepts (the same tie-break
     *       {@code resendPendingRequests} uses); the higher one re-sends its own request so the lower
     *       one sees it even if the first was lost, then waits for the accept.</li>
     *   <li>Otherwise (PENDING_IN, REJECTED, or PENDING_OUT for a different room) — no consent is held,
     *       so the new request replaces it.</li>
     * </ul>
     */
    private boolean handleRepeatRequest(RoomMapping existing, String theirLocal, String localDomain) {
        String origin = existing.remoteDomain();
        boolean sameRoom = theirLocal.equalsIgnoreCase(existing.remoteRoomJid());
        switch (existing.state()) {
            case ACTIVE -> {
                if (sameRoom) {
                    manager.resendAccept(existing);
                } else {
                    Log.warn("SECURITY: ignoring room-mapping from {} onto {} — an ACTIVE mapping to {} "
                           + "exists and a request cannot replace it", origin, existing.localRoomJid(),
                             existing.remoteRoomJid());
                }
                return false;
            }
            case DISABLED_LOCAL, DISABLED_REMOTE -> {
                Log.warn("SECURITY: ignoring room-mapping from {} onto {} — the mapping is {} and a "
                       + "request cannot re-open it", origin, existing.localRoomJid(), existing.state());
                return false;
            }
            case PENDING_OUT -> {
                if (!sameRoom) return true;
                if (localDomain.compareTo(origin) < 0) {
                    manager.getRoomManager().setMappingState(existing.localRoomJid(), origin,
                                                             RoomMapping.State.PENDING_IN, "");
                    manager.acceptMapping(existing.localRoomJid(), origin);
                } else {
                    manager.resendMappingRequest(existing);
                }
                Log.info("Mutual mapping request {} ↔ {} ({})", existing.localRoomJid(),
                         existing.remoteRoomJid(), origin);
                return false;
            }
            default -> { return true; }
        }
    }

    // ── room-mapping lifecycle (accept / reject / disable / enable) ─────────────

    private void handleMappingAccept(String fromDomain, Element el) {
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping-accept")) return;
        if (relayMappingControl("room-mapping-accept", fromDomain, el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);   // sender's local = our remote room
            String ourLocal   = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);  // our local room
            if (ourLocal != null) manager.onMappingAccepted(ourLocal, actualOrigin, theirLocal, token);
        }
    }

    private void handleMappingReject(String fromDomain, Element el) {
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping-reject")) return;
        if (relayMappingControl("room-mapping-reject", fromDomain, el)) return;
        String actualOrigin = originOf(el, fromDomain);
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
            if (ourLocal != null) manager.onMappingRejected(ourLocal, actualOrigin);
        }
    }

    private void handleMappingDisable(String fromDomain, Element el) {
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping-disable")) return;
        if (relayMappingControl("room-mapping-disable", fromDomain, el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
            if (ourLocal != null && tokenOk(ourLocal, actualOrigin, token, "disable")) {
                manager.onMappingDisabledByPeer(ourLocal, actualOrigin);
            }
        }
    }

    private void handleMappingEnable(String fromDomain, Element el) {
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping-enable")) return;
        if (relayMappingControl("room-mapping-enable", fromDomain, el)) return;
        String actualOrigin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);
            String ourLocal   = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
            if (ourLocal != null && tokenOk(ourLocal, actualOrigin, token, "enable")) {
                manager.onMappingEnabledByPeer(ourLocal, actualOrigin, theirLocal);
            }
        }
    }

    private String originOf(Element el, String fromDomain) {
        String origin = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        return (origin != null) ? origin : fromDomain;
    }

    /**
     * Relays a mapping-lifecycle IQ toward its final destination if we are not it. Returns true when
     * relayed (the caller must stop), false when we are the destination and should apply it locally.
     */
    private boolean relayMappingControl(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (destination == null || localDomain.equals(destination)) return false;
        // Same exposure rule as the room-mapping relay: an untrusted peer may only steer mapping
        // control toward servers it was exposed to.
        if (!untrustedAllowsServer(fromDomain, destination)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                     element, fromDomain, destination);
            return true;
        }
        String origin = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String token  = el.attributeValue("token");
        String reason = el.attributeValue("reason");
        manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
            nextHop -> {
                for (Element map : el.elements("map")) {
                    String l = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);
                    String r = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
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

    // ── mapping-ping / mapping-pong (end-to-end mapping path probe) ─────────────

    /**
     * Answers (or relays) an end-to-end mapping probe.  At the destination we reply with a
     * mapping-pong toward the origin — but only when a mapping with that origin exists, so
     * the probe cannot be used to sweep for arbitrary reachable domains.
     */
    private void handleMappingPing(String fromDomain, Element el) {
        String origin = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        if (origin == null || origin.isEmpty()) return;
        if (!claimedOriginOk(fromDomain, origin, "mapping-ping")) return;
        if (relayMappingProbe("mapping-ping", fromDomain, el)) return;
        if (!manager.getRoomManager().hasMappingWith(origin)) {
            Log.debug("mapping-ping from {} — no active mapping with it, not answering", origin);
            return;
        }
        // The origin provably speaks the probe protocol — remember that for our own break detection.
        manager.markProbeCapable(origin);
        String ts = el.attributeValue(FederationStanzaFactory.ATTR_TS);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        manager.getRoutingTable().findNextHop(origin).ifPresentOrElse(
            nextHop -> {
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mappingPong(nextHop, origin, localDomain, "", ts));
                } catch (Exception e) {
                    Log.warn("Could not answer mapping-ping from {}: {}", origin, e.getMessage());
                }
            },
            () -> Log.debug("mapping-ping from {} — no route back, cannot answer", origin));
    }

    /** A pong for one of our probes arrived — the round trip to that mapped domain works. */
    private void handleMappingPong(String fromDomain, Element el) {
        String origin = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        if (origin == null || origin.isEmpty()) return;
        // A forged pong would mask a genuinely broken mapping path from the probe meant to catch it.
        if (!claimedOriginOk(fromDomain, origin, "mapping-pong")) return;
        if (relayMappingProbe("mapping-pong", fromDomain, el)) return;
        manager.onMappingPong(origin, el.attributeValue(FederationStanzaFactory.ATTR_TS));
    }

    /**
     * Relays a mapping probe toward its destination if we are not it. Returns true when handled
     * (relayed or dropped), false when we are the destination and should process it locally.
     */
    private boolean relayMappingProbe(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (destination == null || localDomain.equals(destination)) return false;
        String via = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("{} loop detected (via={}), dropping", element, via);
            return true;
        }
        // Untrusted-peer exposure gate: an untrusted peer may only probe toward exposed servers.
        if (!untrustedAllowsServer(fromDomain, destination)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                     element, fromDomain, destination);
            return true;
        }
        String origin = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String ts = el.attributeValue(FederationStanzaFactory.ATTR_TS);
        String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
        manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
            nextHop -> {
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        element.equals("mapping-ping")
                            ? FederationStanzaFactory.mappingPing(nextHop, destination, origin, newVia, ts)
                            : FederationStanzaFactory.mappingPong(nextHop, destination, origin, newVia, ts));
                } catch (Exception e) {
                    Log.warn("Could not relay {} toward {}: {}", element, destination, e.getMessage());
                }
            },
            // No route toward the destination: drop silently. For a ping this is exactly the
            // deny/asymmetry case — the origin detects it by the missing pong.
            () -> Log.debug("{}: no route to {}, dropping", element, destination));
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
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String origin      = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (!claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-unmap")) return;

        // Relay if we are not the final destination (multi-hop topology).
        if (destination != null && !localDomain.equals(destination)) {
            if (!untrustedAllowsServer(fromDomain, destination)) {
                Log.warn("SECURITY: dropping room-unmap from untrusted peer {} toward non-exposed server {}",
                         fromDomain, destination);
                return;
            }
            // Carry the consent token across the relay — without it the final destination's
            // tokenOk() check fails (non-empty stored vs empty relayed) and it drops the unmap,
            // leaving hub-relayed cross-spoke occupants behind as ghosts.
            String relayToken = el.attributeValue("token", "");
            manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
                nextHop -> {
                    for (Element map : el.elements("map")) {
                        String theirLocal  = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);
                        String theirRemote = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);
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
            String theirLocal  = map.attributeValue(FederationStanzaFactory.ATTR_LOCAL);   // originator's local  = our remote
            String theirRemote = map.attributeValue(FederationStanzaFactory.ATTR_REMOTE);  // originator's remote = our local
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
        String finalDest   = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String targetRoom  = el.attributeValue("targetRoom");
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
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
        // The RAW src attr (no fallback) is the claimed entry server — payloadOriginOk uses it to
        // recognize hub fan-out, where the payload origin legitimately arrives off its own route.
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "muc-forward", false,
                             el.attributeValue("src"))) return;
        // The entry-server claim is checked here, ahead of BOTH branches: on the relay branch the next
        // hop receives this from us, a trusted neighbour, and would take an untrusted peer's src on
        // trust — naming another server's mapping is exactly how a room it has no mapping on would
        // otherwise be reached.
        if (!claimedOriginOk(fromDomain, src, "muc-forward")) return;

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
            // Mapping-consent gate: federation-enabled is NOT consent on its own. Traffic may only
            // enter a room that has at least one ACTIVE mapping — otherwise a reachable peer could
            // push messages or spoofed presence into any tagged-but-unmapped room. getMappingsForLocal
            // returns ACTIVE mappings only, so a room with all mappings disabled/pending is also closed.
            if (manager.getRoomManager().getMappingsForLocal(targetRoom).isEmpty()) {
                Log.warn("SECURITY: dropping muc-forward from {} into federated but UNMAPPED local room {} "
                       + "(type={}, from={})", fromDomain, targetRoom,
                       payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
                return;
            }
            // ...and the mapping must be with the server this traffic enters through. "Some active
            // mapping exists" let any peer write into a room that was mapped only to somebody else,
            // and never shared with it. Every legitimate sender stamps src with its own domain and
            // sends only along its own ACTIVE mappings (the hub stamps itself on fan-out), so src
            // names the far end of exactly one of our mappings for this room.
            if (!mappedToEntry(targetRoom, src, fromDomain, payloadEl)) return;
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
                // Only an ACTIVE mapping consents to traffic. getMappingForRemote returns a mapping in
                // ANY state, so without the isActive() check a disabled/pending/rejected mapping would
                // still admit injected packets into the local room.
                // The mapping must point at the room's actual home (finalDest), and an untrusted
                // sender must have been exposed to THIS server too — the gate above only checked the
                // destination, while this injects into one of our own rooms.
                if (ownMapping != null && ownMapping.isActive()
                        && ownMapping.remoteDomain().equals(finalDest)
                        && untrustedAllowsServer(fromDomain, localDomain)
                        && isFederatedLocalRoom(ownMapping.localRoomJid())) {
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

    /**
     * Whether {@code targetRoom} has an ACTIVE mapping whose far end is {@code src}, the mapped server
     * this muc-forward claims to enter through ({@code src} was already checked against the sending
     * link by the caller, for untrusted peers).
     */
    private boolean mappedToEntry(String targetRoom, String src, String fromDomain, Element payloadEl) {
        boolean mapped = manager.getRoomManager().getMappingsForLocal(targetRoom).stream()
                                .anyMatch(m -> m.remoteDomain().equals(src));
        if (!mapped) {
            Log.warn("SECURITY: dropping muc-forward from {} into local room {} — it enters through {}, "
                   + "which has no active mapping on that room (type={}, from={})", fromDomain, targetRoom,
                     src, payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
        }
        return mapped;
    }

    // ── direct-forward (1:1 private messaging) ─────────────────────────────────

    /**
     * Relays or delivers a 1:1 message carried over the overlay.  Mirrors {@link #handleMucForward}
     * but for user-addressed messages: no room, no fan-out.  At the final destination the embedded
     * message is delivered straight to the recipient (online session or offline storage) via
     * {@code directDeliver}; an intermediate hop forwards it on toward the destination.
     *
     * <p>1:1 messaging must be able to cross an untrusted edge — that is the point of the overlay —
     * but only toward servers that edge was exposed to. See {@link #oneToOneExposureOk}: the routing
     * view we send an untrusted peer bounds what it is TOLD, not what it may name as a destination.
     */
    private void handleDirectForward(String fromDomain, Element el) {
        String finalDest   = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
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
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "direct-forward", true, null)) return;
        if (!oneToOneExposureOk(fromDomain, finalDest, localDomain, "direct-forward")) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            // We are the destination — deliver to the local recipient (bypasses interceptors, so the
            // recipient's reply is a fresh outbound message caught and relayed back symmetrically).
            Message msg = new Message(payloadEl.createCopy());
            JID mto = msg.getTo();
            if (!deliverableHere(mto, "direct-forward", fromDomain)) return;
            if (isLocalConferenceDomain(mto.getDomain())) {
                // groupchat/PM from a remote occupant to a LOCAL room (federated remote-room join) —
                // route to the MUC service unmarked so the room broadcasts it and the re-broadcast to
                // other remote occupants stays clean for relay back.  No re-relay risk: a stanza
                // addressed to a local conference hits the interceptor's local-conference early-returns.
                XMPPServer.getInstance().getPacketRouter().route(msg);
                Log.info("direct-forward: delivered MUC message {} -> {} (from {})", msg.getFrom(), mto, fromDomain);
                return;
            }
            // File share in a 1:1: rewrite the upload URL to our own download endpoint and pull
            // the content over the overlay before handing the message to the recipient.
            if (manager.getFileRelay() != null) {
                manager.getFileRelay().rewriteInPlace(msg, fromDomain);
            }
            FederationStanzaFactory.markAsForwarded(msg);
            logRecipientCarbonState(msg.getTo());   // DEBUG diagnostic (multi-client carbon investigation)
            if (FederationStanzaFactory.directDeliver(msg)) {
                // Went straight to a session, so the router's interceptor chain — and the message
                // archiver in it — never saw this. A bare-JID target routes normally and is already
                // captured, hence only the bypass case. Sender is remote, so no session to pass.
                FederationPacketInterceptor.runArchiveCapturePass(msg, null);
            }
            Log.info("direct-forward: delivered 1:1 {} -> {} (from {})", msg.getFrom(), msg.getTo(), fromDomain);
        } else {
            // Intermediate hop — forward toward the destination, appending ourselves to the trail.
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Message embedded = new Message(payloadEl.createCopy());
                        if (!manager.egressExposureOk(nextHop, embedded.getFrom(), "direct-forward")) return;
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

    /**
     * DEBUG diagnostic (multi-client message-carbons investigation): logs each online resource of a
     * local recipient and whether it has XEP-0280 message carbons enabled, so we can see why a
     * federated 1:1 message did or didn't fan out to every client. No-op unless federation DEBUG
     * logging is on; purely observational.
     */
    private void logRecipientCarbonState(JID to) {
        if (to == null || !Log.isDebugEnabled()) return;
        if (to.getNode() == null || !XMPPServer.getInstance().isLocal(to)) return;
        try {
            java.util.Collection<org.jivesoftware.openfire.session.ClientSession> sessions =
                    org.jivesoftware.openfire.SessionManager.getInstance().getSessions(to.getNode());
            StringBuilder sb = new StringBuilder();
            for (org.jivesoftware.openfire.session.ClientSession s : sessions) {
                sb.append("\n    ").append(s.getAddress())
                  .append("  carbons=").append(s.isMessageCarbonsEnabled());
            }
            Log.debug("direct-forward recipient {} has {} session(s):{}", to.toBareJID(), sessions.size(), sb);
        } catch (Exception e) {
            Log.debug("logRecipientCarbonState failed for {}: {}", to, e.getMessage());
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
        String finalDest   = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
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
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "presence-forward", true, null)) return;
        if (!oneToOneExposureOk(fromDomain, finalDest, localDomain, "presence-forward")) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            Presence pres = new Presence(payloadEl.createCopy());
            JID pto = pres.getTo();
            // Checked ahead of the probe branch too: answering a probe for a user who does not live
            // here would disclose local presence to a peer that addressed someone else's server.
            if (!deliverableHere(pto, "presence-forward", fromDomain)) return;
            // A probe or subscription request for a user shared with the sender's server: answered by
            // the plugin, so the user is never prompted to approve a contact the admin shared.
            if (manager.getContactLists().handleInboundSubscription(pres)) {
                Log.info("presence-forward: answered {} {} -> {} for a shared contact (from {})",
                         pres.getType(), pres.getFrom(), pto, fromDomain);
                return;
            }
            // A probe for a local user: Openfire's own answer would be routed past the interceptor and
            // never cross the overlay, so answer explicitly with the user's current presence.
            if (pres.getType() == Presence.Type.probe) {
                manager.answerPresenceProbe(pres.getFrom(), pto);
                Log.info("presence-forward: answered probe {} -> {} (from {})",
                         pres.getFrom(), pto, fromDomain);
                return;
            }
            if (isLocalConferenceDomain(pto.getDomain())) {
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
            Log.info("presence-forward: delivered 1:1 {} {} -> {} (from {}) avatarHash={}",
                     pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), pres.getTo(), fromDomain,
                     avatarHashOf(pres));
        } else {
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        Presence embedded = new Presence(payloadEl.createCopy());
                        if (!manager.egressExposureOk(nextHop, embedded.getFrom(), "presence-forward")) return;
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
    //
    // Both answerVCardLocally and answerPepItemsLocally exist because Openfire's own handlers for
    // these two namespaces answer a GET by delivering the reply via the internal deliverer, which
    // bypasses both the PacketInterceptor and any IQResultListener — so a reply to a multi-hop
    // requester is handed to (non-existent) native S2S and silently lost. Every other IQ type
    // (disco, caps, version, ping, Jingle signaling) either targets a specific local session directly
    // (fresh outbound reply gets caught and relayed back symmetrically like any other stanza) or
    // doesn't cross federation as a request/reply pair at all, so it needs no special handling here.

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
        String finalDest   = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
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
        if (!payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "iq-forward", true, null)) return;
        if (!oneToOneExposureOk(fromDomain, finalDest, localDomain, "iq-forward")) return;

        if (finalDest == null || localDomain.equals(finalDest)) {
            IQ iq = new IQ(payloadEl.createCopy());
            if (!deliverableHere(iq.getTo(), "iq-forward", fromDomain)) return;
            IQ.Type type = iq.getType();
            if (type == IQ.Type.get || type == IQ.Type.set) {
                // A REQUEST landed here.  Openfire's own handlers (vCard etc.) deliver their reply via
                // the internal deliverer — NOT through the PacketInterceptor — so a reply to a multi-hop
                // requester is handed to (non-existent) native S2S and lost.  We can't rely on Openfire
                // routing the reply back, so we build the reply ourselves and relay it over the overlay.
                if (!answerVCardLocally(iq, fromDomain) && !answerPepItemsLocally(iq, fromDomain)) {
                    // Not a vCard or PEP items request: best-effort local delivery (Openfire may answer
                    // it, but its reply does not yet relay back over multi-hop — see iq-forward reply
                    // limitation).
                    XMPPServer.getInstance().getPacketRouter().route(iq);
                    Log.info("iq-forward: delivered 1:1 {} {} -> {} (from {}) [reply relay best-effort]",
                             type, iq.getFrom(), iq.getTo(), fromDomain);
                }
            } else {
                // A REPLY (result/error) reached its final destination — deliver to the local client.
                FederationPacketInterceptor.deliverOverlayReply(iq);
                Log.info("iq-forward: delivered 1:1 reply {} {} -> {} (from {})",
                         type, iq.getFrom(), iq.getTo(), fromDomain);
            }
        } else {
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
                nextHop -> {
                    try {
                        IQ embedded = new IQ(payloadEl.createCopy());
                        if (!manager.egressExposureOk(nextHop, embedded.getFrom(), "iq-forward")) return;
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

    /**
     * Untrusted-peer exposure gate for the three 1:1 forwards ({@code direct-forward},
     * {@code presence-forward}, {@code iq-forward}), applied on BOTH branches: the server the
     * envelope is headed for — {@code finalDest} when we relay, this server when we deliver — must be
     * one the peer was exposed to.
     *
     * <p>These forwards were once left ungated on the reasoning that the filtered routing view an
     * untrusted peer receives already bounds where it can reach. It does not: that view bounds what the
     * peer is told, while the envelope's {@code destination} is the peer's own choice. Ungated, an edge
     * could name any server we route to; the next hop would see the stanza arrive from US, a trusted
     * neighbour, and skip its own untrusted checks — reaching users, presence and PEP data on servers
     * that were never exposed to it.
     */
    private boolean oneToOneExposureOk(String fromDomain, String finalDest, String localDomain, String what) {
        String target = (finalDest == null || localDomain.equals(finalDest)) ? localDomain : finalDest;
        if (untrustedAllowsServer(fromDomain, target)) return true;
        Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                 what, fromDomain, target);
        return false;
    }

    /** True if {@code domain} is one of this server's local MUC service domains. */
    private boolean isLocalConferenceDomain(String domain) {
        if (domain == null) return false;
        return XMPPServer.getInstance().getMultiUserChatManager()
                         .getMultiUserChatServices().stream()
                         .anyMatch(svc -> svc.getServiceDomain().equals(domain));
    }

    /**
     * Whether an overlay envelope that names US as its final destination is carrying a stanza we may
     * actually deliver: one addressed to this server, one of its MUC services, or another component
     * registered here.
     *
     * <p>The envelope's {@code destination} says where the OVERLAY hop ends; it says nothing about
     * who the embedded stanza is addressed to, and the two were never compared. A configured peer
     * could therefore hand us an envelope claiming this server as the destination while embedding a
     * recipient somewhere else entirely — and because the delivery branch hands the stanza to
     * Openfire's own packet router, the server would dutifully emit it over native S2S. That makes
     * this server a relay for traffic it never authorized, under its own identity. The
     * {@code from}-spoofing gate does not catch it: that one validates the sender, not the recipient.
     *
     * @param stanza human-readable stanza kind, for the rejection log line
     */
    private boolean deliverableHere(JID to, String stanza, String fromDomain) {
        if (to == null || to.getDomain() == null || to.getDomain().isBlank()) {
            Log.warn("SECURITY: dropping {} from {} — final destination is us but the payload has no "
                   + "recipient", stanza, fromDomain);
            return false;
        }
        XMPPServer server = XMPPServer.getInstance();
        if (server.isLocal(to) || isLocalConferenceDomain(to.getDomain()) || server.matchesComponent(to)) {
            return true;
        }
        Log.warn("SECURITY: dropping {} from {} — final destination is us but the payload is "
               + "addressed to {}, which is not served here (refusing to relay it onward)",
                 stanza, fromDomain, to.getDomain());
        return false;
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

    /**
     * If {@code request} is a PEP items GET (XEP-0060 pubsub {@code <items node='...'/>}, addressed to
     * a local user's bare JID) reads the requested node straight out of that user's {@link PEPService}
     * and relays the result back to the remote requester over the overlay — then returns true. Returns
     * false for anything else so the caller can fall back to plain local delivery.
     *
     * Same rationale as {@link #answerVCardLocally}: Openfire's IQPEPHandler answers a PEP get by
     * delivering the reply through the internal deliverer, which bypasses the interceptor, so the reply
     * to a multi-hop requester is lost. We read the node's published item(s) directly instead.
     *
     * Deliberately generic across node names, not avatar-only — XEP-0084 avatar metadata/data is the
     * motivating case (mirrors the {@code vcard-temp} avatar path above for clients that moved off
     * presence-hash avatars onto PEP), but the same reply-loss bug applies to every PEP node a client
     * might publish (e.g. OMEMO device-lists/bundles, XEP-0384), and PEP is a personal-*eventing*
     * protocol — a node's contents are, by design, what its owner published for interested parties to
     * read — subject to the node's access model, checked in {@link #pepAccessAllowed}.
     *
     * The three XEP-0060 outcomes are kept distinct, because a client reads them differently:
     * {@code item-not-found} for a node the contact never created, an empty {@code <items/>} for a
     * node that exists but holds nothing, and the item itself otherwise.
     */
    /**
     * Answers a PEP items GET that reached us over native S2S from a contact on a server the target
     * user is shared with (see {@code ContactListManager}). Openfire would refuse it: the contact is
     * not on the user's roster, so a presence-access node (the avatar) looks private to them. Returns
     * false, leaving the request to Openfire, for anything else.
     */
    boolean answerSharedContactPepFetch(IQ request) {
        JID from = request.getFrom();
        JID to   = request.getTo();
        if (from == null || to == null || !XMPPServer.getInstance().isLocal(to)) return false;
        if (!manager.getContactLists().isAuthorizedContact(to, from)) return false;
        return answerPepItemsLocally(request, from.getDomain());
    }

    private boolean answerPepItemsLocally(IQ request, String fromDomain) {
        if (request.getType() != IQ.Type.get) return false;
        Element pubsub = request.getChildElement();
        if (pubsub == null || !"pubsub".equals(pubsub.getName())
                || !"http://jabber.org/protocol/pubsub".equals(pubsub.getNamespaceURI())) {
            return false;
        }
        Element itemsEl = pubsub.element("items");
        String node = itemsEl == null ? null : itemsEl.attributeValue("node");
        if (node == null) return false;

        JID contact = request.getTo();
        if (contact == null || contact.getNode() == null) return false;

        Node pepNode;
        try {
            PEPServiceManager pepServiceManager = XMPPServer.getInstance().getIQPEPHandler().getServiceManager();
            PEPService pepService = pepServiceManager.getPEPService(contact.asBareJID(), false);
            pepNode = pepService == null ? null : pepService.getNodes().stream()
                    .filter(n -> node.equals(n.getUniqueIdentifier().getNodeId()))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            // We learned nothing about the node, so "ask again later" — not "it isn't there".
            Log.warn("iq-forward: failed to resolve PEP node '{}' for {}: {}", node, contact, e.getMessage());
            return relayPepReply(pepError(request, PacketError.Condition.internal_server_error,
                    PacketError.Type.wait), contact, node, fromDomain);
        }
        if (pepNode == null) {
            // XEP-0060 §6.5.9.5: a fetch against a node that does not exist is item-not-found, not an
            // empty result. The distinction is load-bearing for the avatar path this exists to serve:
            // an empty <items/> asserts "this contact HAS an avatar node and it is empty", which tells
            // a client to render no avatar, where item-not-found leaves it free to fall back to the
            // vcard-temp photo that answerVCardLocally above still serves for older clients.
            Log.debug("iq-forward: PEP node '{}' does not exist for {} — replying item-not-found", node, contact);
            return relayPepReply(pepError(request, PacketError.Condition.item_not_found,
                    PacketError.Type.cancel), contact, node, fromDomain);
        }

        // Honour the node's access model, exactly as Openfire's own PubSubEngine does for a local fetch.
        // Without it this path served every node — presence-only ones to strangers, and whitelist
        // (private) nodes such as XEP-0402 bookmarks, which can hold room passwords — to any requester.
        if (!pepAccessAllowed(pepNode, contact, request.getFrom())) {
            Log.info("SECURITY: refusing PEP node '{}' of {} to {} — not allowed by its access model ({})",
                     node, contact, request.getFrom(), pepNode.getAccessModel().getName());
            return relayPepReply(pepError(request, PacketError.Condition.forbidden,
                    PacketError.Type.auth), contact, node, fromDomain);
        }

        IQ result = IQ.createResultIQ(request);
        Element resultItems = result.setChildElement("pubsub", "http://jabber.org/protocol/pubsub")
                                     .addElement("items");
        resultItems.addAttribute("node", node);
        try {
            Element itemFilter = itemsEl.element("item");
            String wantedId = itemFilter == null ? null : itemFilter.attributeValue("id");
            PublishedItem item = wantedId != null ? pepNode.getPublishedItem(wantedId)
                                                    : pepNode.getLastPublishedItem();
            // The node exists but holds nothing (or not the requested id): an empty <items/> is the
            // correct answer, per XEP-0060 §6.5.4 — leave resultItems childless.
            if (item != null) {
                Element itemEl = resultItems.addElement("item");
                itemEl.addAttribute("id", item.getID());
                Element payload = item.getPayload();
                if (payload != null) itemEl.add(payload.createCopy());
            }
        } catch (Exception e) {
            Log.warn("iq-forward: failed to read items of PEP node '{}' for {}: {}", node, contact, e.getMessage());
            return relayPepReply(pepError(request, PacketError.Condition.internal_server_error,
                    PacketError.Type.wait), contact, node, fromDomain);
        }
        return relayPepReply(result, contact, node, fromDomain);
    }

    /**
     * Whether {@code requester} may read items of {@code pepNode}, owned by local user {@code owner}.
     * Delegates to the node's own {@link org.jivesoftware.openfire.pubsub.models.AccessModel} with the
     * same arguments {@code PubSubEngine} passes for a local items request (requester bare JID, then
     * full JID). For a {@code presence} node we additionally accept a subscriber this plugin tracked
     * from a relayed {@code subscribed} — the same set the push path uses — because the roster row of a
     * multi-hop contact is the part of Openfire's state federation has historically had to reconcile.
     */
    private boolean pepAccessAllowed(Node pepNode, JID owner, JID requester) {
        if (requester == null) return false;
        try {
            var model = pepNode.getAccessModel();
            if (model.canAccessItems(pepNode, requester.asBareJID(), requester)) return true;
            return "presence".equals(model.getName()) && manager.isPresenceSubscriber(owner, requester);
        } catch (Exception e) {
            Log.warn("iq-forward: access check on PEP node '{}' of {} failed: {}",
                     pepNode.getUniqueIdentifier().getNodeId(), owner, e.getMessage());
            return false;
        }
    }

    /**
     * Builds an error reply to a PEP items GET, echoing the request's own child element as RFC 6120
     * §8.3.1 asks of an error stanza — a pubsub client matches the reply against what it sent.
     */
    private IQ pepError(IQ request, PacketError.Condition condition, PacketError.Type type) {
        IQ err = IQ.createResultIQ(request);
        Element child = request.getChildElement();
        if (child != null) err.setChildElement(child.createCopy());
        err.setError(new PacketError(condition, type));   // also flips the stanza type to "error"
        return err;
    }

    /** Relays a built PEP reply (result or error) back to the remote requester. Always returns true. */
    private boolean relayPepReply(IQ reply, JID contact, String node, String fromDomain) {
        String kind = reply.getType() == IQ.Type.error ? "error" : "result";
        if (manager.forwardDirectIq(reply)) {
            Log.info("iq-forward: answered + relayed PEP node '{}' for {} -> {} as {} (from {})",
                     node, contact, reply.getTo(), kind, fromDomain);
        } else {
            Log.warn("iq-forward: built PEP {} for {} node '{}' but no route back to {}",
                     kind, contact, node, reply.getTo());
        }
        return true;
    }

    // ── contact-list / contact-list-request (per-server contact sharing) ────────

    /**
     * Relays a {@code contact-list} or {@code contact-list-request} toward its destination, or applies
     * it when we are the destination.
     *
     * <p>An untrusted link is crossed only when this server allows contact lists on that link (a
     * per-peer setting), and then under the link's exposure settings, like the 1:1 forwards: arriving
     * over an untrusted link, the
     * origin must be the peer or a server behind it, and the destination (us, or the server we would
     * relay to) must be one we expose to that peer; leaving over an untrusted link, the origin must be
     * a server we expose to it.
     */
    private void handleContactListAction(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String origin      = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (destination == null || origin == null || origin.equals(localDomain)) {
            Log.warn("{} from {} has a missing or invalid origin/destination ({} → {}), dropping",
                     element, fromDomain, origin, destination);
            return;
        }
        // Arriving over an untrusted link needs no opt-in: the per-link flag gates sending, and the
        // receiving admin still decides what a list does by mapping it. The exposure model applies,
        // and an admin who does not trust the neighbour can refuse its lists outright.
        boolean fromUntrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        if (fromUntrusted) {
            if ("contact-list".equals(element) && manager.getPeerRegistry().isContactListsRefused(fromDomain)) {
                Log.debug("Refusing contact-list from untrusted peer {} (origin {}) — refused on that link here",
                          fromDomain, origin);
                return;
            }
            if (!claimedOriginOk(fromDomain, origin, element)) return;
            if (!oneToOneExposureOk(fromDomain, destination, localDomain, element)) return;
        }
        if (FederationStanzaFactory.viaContains(via, localDomain)) {
            Log.warn("{} loop detected (via={}), dropping", element, via);
            return;
        }

        if (!destination.equals(localDomain)) {
            String nextHop = manager.getRoutingTable().findNextHop(destination).orElse(null);
            if (nextHop == null) {
                Log.debug("{}: no route to {}, dropping", element, destination);
                return;
            }
            if (manager.getPeerRegistry().isUntrusted(nextHop)) {
                // Only a list leaving over the link needs the link's flag; a request carries no contacts.
                boolean allowed = "contact-list-request".equals(element)
                               || manager.getPeerRegistry().isContactListsAllowed(nextHop);
                if (!allowed || !manager.getPeerRegistry().getExposedServers(nextHop).contains(origin)) {
                    Log.warn("SECURITY: not relaying {} from {} toward {} — the next hop {} is untrusted and {}",
                             element, origin, destination, nextHop,
                             allowed ? origin + " is not exposed to it" : "sending contact lists is not allowed on that link here");
                    return;
                }
            }
            String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.relay(nextHop, el, newVia));
            } catch (Exception e) {
                Log.warn("Could not relay {} toward {}: {}", element, destination, e.getMessage());
            }
            return;
        }

        if ("contact-list-request".equals(element)) {
            manager.getContactLists().handleRequest(origin);
            return;
        }
        List<ContactListManager.Contact> contacts = new ArrayList<>();
        int rejected = 0;
        for (Element c : el.elements("contact")) {
            ContactListManager.Contact contact =
                    ContactListManager.parseContact(origin, c.attributeValue("jid"), c.attributeValue("name"));
            if (contact == null) { rejected++; continue; }
            if (contacts.size() >= ContactListManager.MAX_CONTACTS) { rejected++; continue; }
            contacts.add(contact);
        }
        if (rejected > 0) {
            Log.warn("contact-list from {}: ignored {} entr(ies) — not a bare JID on {}, or past the {}-contact limit",
                     origin, rejected, origin, ContactListManager.MAX_CONTACTS);
        }
        boolean crossedUntrusted = fromUntrusted || manager.getContactLists().crossesUntrusted(origin);
        manager.getContactLists().handleContactList(origin, contacts, crossedUntrusted);
    }

    private void injectLocally(Element payloadEl, String via, String targetRoom, String fromDomain, String src) {
        try {
            switch (payloadEl.getName()) {
                case "message"  -> injectMessage(payloadEl, targetRoom, fromDomain, src);
                case "presence" -> injectPresence(payloadEl, targetRoom, fromDomain, src);
                default -> Log.warn("injectLocally: unexpected payload type '{}'", payloadEl.getName());
            }
        } catch (Exception e) {
            Log.warn("Failed to inject forwarded MUC packet: {}", e.getMessage(), e);
        }
    }

    // ── file relay (transparent HTTP-upload federation) ────────────────────────

    /**
     * Dispatches a file-* element: relays it toward its destination when we are a transit hop
     * (subject to the untrusted-peer exposure gate, like every other routed federation stanza),
     * otherwise hands it to the {@link com.igniterealtime.openfire.plugin.federation.files.FileRelayManager}.
     */
    private void handleFileRelay(String element, String fromDomain, Element el) {
        var relay = manager.getFileRelay();
        if (relay == null) return;
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (destination != null && !localDomain.equals(destination)) {
            if (!untrustedAllowsServer(fromDomain, destination)) {
                Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                         element, fromDomain, destination);
                return;
            }
            relay.relayToward(element, el, fromDomain);
            return;
        }
        // The gate above only covered traffic we pass THROUGH. An untrusted peer addressing this
        // server directly reached the relay unchecked — including file-request, which asks us to
        // hand over content. Same rule as handleRoomMapping applies to its local branch.
        if (!untrustedAllowsServer(fromDomain, localDomain)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} addressed to this server, which "
                   + "it has not been exposed to", element, fromDomain);
            return;
        }
        switch (element) {
            case "file-request" -> relay.handleFileRequest(fromDomain, el);
            case "file-offer"   -> relay.handleFileOffer(fromDomain, el);
            case "file-chunk"   -> relay.handleFileChunk(fromDomain, el);
            case "file-error"   -> relay.handleFileError(fromDomain, el);
            default -> { }
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
    private void injectMessage(Element msgEl, String targetRoom, String fromDomain, String src) {
        MUCRoom room = findLocalRoom(targetRoom);
        if (room == null) {
            Log.warn("injectMessage: room {} not found locally", targetRoom);
            return;
        }

        Element moderatedRetract = RoomModeration.moderatedRetract(msgEl);
        if (moderatedRetract != null) {
            injectModeration(msgEl, moderatedRetract, room);
            return;
        }

        JID targetJID      = new JID(targetRoom);
        String senderNick  = virtualNick(msgEl.attributeValue("from"));

        // Self-echo guard (see injectPresence): never re-deliver one of our OWN users'
        // messages looped back through a cyclic topology — they already got it locally.
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (localDomain.equals(originOf(senderNick, localDomain))) {
            Log.debug("injectMessage: dropping self-echo of local user {} into {}", senderNick, targetRoom);
            return;
        }

        // A file share rides in with a fed-file annotation: deliver a copy whose URL points at
        // OUR download endpoint (registering the overlay pull), while the untouched original
        // continues to fan-out so downstream servers can rewrite for themselves.  The mapped
        // entry server (src) and the relay neighbour are pull fallbacks when the annotating
        // origin itself is not routable from here (filtered edges).  Done up front, unconditionally
        // on occupancy, so a share into a currently-empty room still starts staging/pulling right
        // away instead of waiting for someone to reconnect.
        Element deliverEl = msgEl;
        if (manager.getFileRelay() != null) {
            if (!manager.getRoomManager().isFilesEnabled(targetRoom)
                    && manager.getFileRelay().annotationOf(msgEl) != null) {
                // This room has file federation off: don't pull the file — deliver a "disabled for this
                // room" notice to its occupants (the local-room counterpart of the egress notice).
                Element noticeEl = msgEl.createCopy();
                manager.getFileRelay().replaceWithLocalBlockedNotice(noticeEl);
                deliverEl = noticeEl;
            } else {
                Element rewritten = manager.getFileRelay().rewriteForDelivery(msgEl, targetRoom, src, fromDomain);
                if (rewritten != null) deliverEl = rewritten;
            }
        }
        // msgEl itself continues to fan-out with the origin's stanza-id intact, so stamp a copy.
        if (deliverEl == msgEl) deliverEl = msgEl.createCopy();
        RoomModeration.stripModeration(deliverEl);
        stampRoomIdentity(deliverEl, room, senderNick);

        String virtualFrom = targetJID.getNode() + "@" + targetJID.getDomain() + "/" + senderNick;

        // Feed the room's own history exactly once, regardless of who (if anyone) is here to see it
        // live — see archiveToRoomHistory for the two mechanisms this covers (in-memory replay-on-
        // join, always; persistent log for MAM/admin reporting, if the room has logging on). Together
        // these handle an arbitrarily long gap, not just PendingDelivery's short reconnect-blip window.
        archiveToRoomHistory(room, deliverEl, virtualFrom, senderNick);

        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) {
            bufferPendingDelivery(targetRoom, deliverEl, virtualFrom);
            return;
        }
        deliverToOccupants(deliverEl, virtualFrom, occupants);
        Log.debug("injectMessage: delivered to {} occupant(s) in {}", occupants.size(), targetRoom);
    }

    /**
     * Gives a relayed groupchat message the room-scoped identity Openfire gives a local occupant's
     * message: a {@code <stanza-id by=thisRoom>} (XEP-0359) and an {@code <occupant-id>} (XEP-0421).
     * Clients address a groupchat message by that stanza-id when they react (XEP-0444), reply
     * (XEP-0461), retract or moderate it, and Conversations refuses to react without one ("Could not
     * add reaction"). It also drops any incoming reaction that has no occupant-id.
     *
     * <p>The origin room's stanza-id arrives on the forwarded copy (Openfire stamps the original packet
     * before our post-processing forwarder sees it) and every hop relays it unchanged. Reusing its
     * {@code id} with {@code by} set to this room gives a message the same ID in every copy of the
     * room. A reaction made on any server then names a message every other server knows, with no ID
     * translation table.
     *
     * <p>Only the stanza-id stamped by the origin room is reused: {@code by} must equal the payload's
     * {@code to}, the room it was sent to. The origin server strips any client-supplied stanza-id that
     * claims its own room, so a user cannot choose that one, while one with any other {@code by} is
     * whatever the client wrote. An id this room has already used gets a fresh one instead, so a peer
     * cannot make two messages share an id. Every incoming stanza-id/occupant-id is dropped: a peer
     * must not assert one in this room's name.
     */
    private void stampRoomIdentity(Element msgEl, MUCRoom room, String senderNick) {
        String roomJid = room.getJID().toBareJID();
        String originRoom = bareJidOf(msgEl.attributeValue("to"));
        String id = null;
        for (Element sid : msgEl.elements(QName.get("stanza-id", NS_STANZA_ID))) {
            String candidate = sid.attributeValue("id");
            if (id == null && originRoom != null && !originRoom.equals(roomJid)
                    && originRoom.equals(bareJidOf(sid.attributeValue("by")))
                    && candidate != null && REUSABLE_STANZA_ID.matcher(candidate).matches()) {
                id = candidate;
            }
            msgEl.remove(sid);
        }
        // Remembered with its origin so a moderation from that room can find it (see injectModeration).
        var origin = new RoomMessageIds.Origin(originRoom, id, senderNick);
        if (id != null && !manager.getRoomMessageIds().claim(roomJid, id, origin)) {
            Log.warn("SECURITY: relayed message from {} into {} reuses stanza-id {} already used in the room; "
                   + "giving it a fresh one", senderNick, roomJid, id);
            id = null;
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
            manager.getRoomMessageIds().claim(roomJid, id, origin);
        }
        msgEl.addElement(QName.get("stanza-id", NS_STANZA_ID))
             .addAttribute("id", id)
             .addAttribute("by", roomJid);

        for (Element oid : msgEl.elements(QName.get("occupant-id", NS_OCCUPANT_ID))) {
            msgEl.remove(oid);
        }
        String occupantId = occupantIdFor(room, senderNick);
        if (occupantId != null) {
            msgEl.addElement(QName.get("occupant-id", NS_OCCUPANT_ID)).addAttribute("id", occupantId);
        }
    }

    /**
     * Applies a moderation (XEP-0425) relayed from the room {@code msgEl} names in {@code from}/{@code to}.
     * It is applied only to a message that came from that same room and whose author belongs to that
     * room's server: the author's own server may remove its users' messages everywhere, and nobody else
     * may (see {@link RoomModeration}). The message is found by the origin's stanza-id in this room's
     * bookkeeping; one too old to be remembered, or from before a restart, is not moderated here.
     *
     * <p>The room's occupants get the announcement from this room's bare JID with the id this room gave
     * the message, as from a local moderator, and it is kept for {@link #replayModerations}.
     */
    private void injectModeration(Element msgEl, Element retract, MUCRoom room) {
        String roomJid    = room.getJID().toBareJID();
        String originRoom = bareJidOf(msgEl.attributeValue("to"));
        String targetId   = retract.attributeValue("id");
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        if (originRoom == null || originRoom.equals(roomJid)
                || !originRoom.equals(msgEl.attributeValue("from"))) {
            Log.warn("SECURITY: dropping a moderation for {} that does not come from its origin room "
                   + "(from={}, to={})", roomJid, msgEl.attributeValue("from"), msgEl.attributeValue("to"));
            return;
        }
        if (RoomModeration.belongsToRoomServer(localDomain, originRoom)) {
            Log.debug("injectModeration: dropping an echo of our own room {}'s moderation", originRoom);
            return;
        }
        var ids = manager.getRoomMessageIds();
        String localId = ids.localIdOf(roomJid, originRoom, targetId);
        if (localId == null) {
            Log.info("Not applying a moderation from {} in {}: message {} is not one this room received "
                   + "from there, or is too old to be remembered", originRoom, roomJid, targetId);
            return;
        }
        String author = ids.originOf(roomJid, localId).author();
        if (!RoomModeration.belongsToRoomServer(author, originRoom)) {
            Log.warn("SECURITY: refusing a moderation from {} in {}: message {} was written by {}, who is "
                   + "not a user of that room's server", originRoom, roomJid, targetId, author);
            return;
        }
        if (!ids.markModerated(roomJid, localId)) {
            Log.debug("injectModeration: {} in {} already moderated", localId, roomJid);
            return;
        }

        // by names the moderator's virtual occupant here, the same "user@home" nick their messages use.
        // Only the origin's own users can moderate there, so anyone else is dropped from the claim.
        Element moderated = retract.element(QName.get("moderated", RoomModeration.NS_MODERATE));
        String moderator = null;
        try {
            String claimed = new JID(moderated.attributeValue("by")).getResource();
            if (claimed != null && RoomModeration.belongsToRoomServer(claimed, originRoom)) moderator = claimed;
        } catch (Exception ignored) {
            // no or unparseable by: announce without one
        }
        Element announcement = RoomModeration.announcement(roomJid, msgEl.attributeValue("id"), localId,
                moderator == null ? null : roomJid + "/" + moderator,
                moderator == null ? null : occupantIdFor(room, moderator),
                RoomModeration.reasonOf(retract));
        announcement.remove(announcement.attribute("to"));

        Deque<StoredModeration> kept = federatedModerations.computeIfAbsent(roomJid, k -> new ConcurrentLinkedDeque<>());
        kept.addLast(new StoredModeration(localId, announcement.createCopy(), System.currentTimeMillis()));
        while (kept.size() > MODERATIONS_KEPT_PER_ROOM) kept.pollFirst();

        Log.info("Applying a moderation from {} in {}: message {} by {}", originRoom, roomJid, localId, author);
        // An empty room needs no buffering: whoever joins next gets it from replayModerations.
        deliverToOccupants(announcement, roomJid, room.getOccupants());
    }

    /**
     * Re-sends the federated moderations this room applied to a local occupant who just joined, for
     * messages still in the room's history, so the history replay on join does not bring them back. A
     * moderation made on this server is replayed by the moderation plugin itself, never here. Each
     * goes out with a XEP-0203 delay, which also keeps the forwarder from relaying it again.
     */
    public void replayModerations(String roomJid, JID joiner) {
        Deque<StoredModeration> kept = federatedModerations.get(roomJid);
        if (kept == null || kept.isEmpty() || joiner == null) return;
        MUCRoom room = findLocalRoom(roomJid);
        if (room == null) return;

        Set<String> inHistory = new HashSet<>();
        for (var it = room.getRoomHistory().getMessageHistory(); it.hasNext(); ) {
            Message m = it.next();
            for (Element sid : m.getElement().elements(QName.get("stanza-id", NS_STANZA_ID))) {
                if (roomJid.equals(bareJidOf(sid.attributeValue("by")))) inHistory.add(sid.attributeValue("id"));
            }
        }
        int sent = 0;
        for (StoredModeration sm : kept) {
            if (!inHistory.contains(sm.targetId())) continue;
            Element copy = sm.announcement().createCopy();
            copy.addElement(QName.get("delay", RoomModeration.NS_DELAY))
                .addAttribute("from", roomJid)
                .addAttribute("stamp", org.jivesoftware.util.XMPPDateTimeFormat.format(new java.util.Date(sm.at())));
            copy.addAttribute("from", roomJid);
            copy.addAttribute("to", joiner.toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationStanzaFactory.directDeliver(delivery);
            sent++;
        }
        if (sent > 0) Log.debug("replayModerations: sent {} to {} joining {}", sent, joiner, roomJid);
    }

    /** Normalized bare JID of {@code jid}, or null if absent or unparseable. */
    private static String bareJidOf(String jid) {
        if (jid == null) return null;
        try {
            return new JID(jid).toBareJID();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The XEP-0421 occupant-id of a virtual occupant ("user@home" nick): what Openfire computes for a
     * real occupant with that bare JID (an HMAC over room and user), so it stays the same across
     * messages, presences and reconnects. Null if Openfire cannot compute one; the stanza then goes out
     * without it, as it did before.
     */
    private String occupantIdFor(MUCRoom room, String senderNick) {
        try {
            return room.generateOccupantId(new JID(senderNick));
        } catch (Exception e) {
            Log.debug("Could not compute an occupant-id for {} in {}: {}", senderNick, room.getJID(), e.getMessage());
            return null;
        }
    }

    /**
     * Writes {@code deliverEl} into the room's conversation log (backing both classic MUC history
     * requests and MAM queries) independent of live delivery. {@code sender} is the real remote
     * user's bare JID behind the virtual room nick — matches what a genuine local occupant's real
     * JID would be for the same log entry.
     */
    private void archiveToRoomHistory(MUCRoom room, Element deliverEl, String virtualFrom, String senderNick) {
        try {
            Element copy = deliverEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to", room.getJID().toString());
            Message archived = new Message(copy);
            JID sender = new JID(senderNick);
            // In-memory "replay recent history on join" buffer (classic XEP-0045 muc#history) — every
            // MUC-compliant client requests this automatically, no archiving plugin required. The room's
            // own HistoryStrategy self-governs whether/how much it actually keeps, same as it would for
            // a genuine local occupant's message, so this is unconditional.
            room.getRoomHistory().addMessage(archived);
            // Persistent conversation log (admin console reporting), gated on the room's own logging toggle.
            if (room.isLogEnabled()) {
                room.getMUCService().logConversation(room, archived, sender);
            }
            // A MAM/XEP-0313 provider (e.g. Monitoring Service) builds its own archive off THIS event,
            // not off the two calls above — its capture hook is a MUCEventListener, which Openfire core
            // only fires from MUCRoom's own broadcast path. injectMessage delivers via directDeliver()
            // specifically to bypass that path (avoids reprocessing/loops), so without this call a MAM
            // provider never learns the message existed, even though muc#history and the conversation
            // log above already have it. Confirmed against Monitoring Service 2.8.0's source: its
            // GroupConversationInterceptor is exactly this listener, feeding MAM only from this event.
            MUCEventDispatcher.messageReceived(room.getJID(), sender, senderNick, archived);
        } catch (Exception e) {
            Log.warn("injectMessage: could not archive message into {} history: {}", room.getJID(), e.getMessage());
        }
    }

    /** Delivers one already-rewritten, already-archived message to every given occupant. */
    private void deliverToOccupants(Element deliverEl, String virtualFrom, Collection<MUCOccupant> occupants) {
        for (MUCOccupant occupant : occupants) {
            Element copy = deliverEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to", occupant.getUserAddress().toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationStanzaFactory.directDeliver(delivery);
        }
    }

    /**
     * Queues a delivery that arrived to an empty room — a fast path for a brief reconnect blip
     * (flushed the instant a real occupant rejoins, see {@link #flushPendingDeliveries}); the
     * durable, unbounded-gap path is {@link #archiveToRoomHistory}, already done by the time this
     * is called. See {@link PendingDelivery}.
     */
    private void bufferPendingDelivery(String targetRoom, Element deliverEl, String virtualFrom) {
        Deque<PendingDelivery> q = pendingRoomDeliveries.computeIfAbsent(targetRoom, k -> new ConcurrentLinkedDeque<>());
        pruneStale(q);
        q.addLast(new PendingDelivery(deliverEl.createCopy(), virtualFrom, System.currentTimeMillis()));
        while (q.size() > PENDING_DELIVERY_MAX_PER_ROOM) q.pollFirst();
        Log.debug("injectMessage: room {} empty — buffered delivery ({} pending, {} ms TTL)",
                   targetRoom, q.size(), PENDING_DELIVERY_TTL_MS);
    }

    private void pruneStale(Deque<PendingDelivery> q) {
        long cutoff = System.currentTimeMillis() - PENDING_DELIVERY_TTL_MS;
        while (true) {
            PendingDelivery head = q.peekFirst();
            if (head == null || head.queuedAt() >= cutoff) break;
            q.pollFirst();
        }
    }

    /**
     * Called (via {@code RoomCreationListener.occupantJoined}) whenever a real local occupant joins
     * any local MUC room — retries any deliveries that were buffered while {@code roomJid} was
     * empty. A no-op for the overwhelmingly common case (no backlog for this room). Delivery only
     * (no re-archiving — {@link #archiveToRoomHistory} already ran when each entry was queued).
     *
     * <p>The queue is claimed out of the map only once we know we can actually deliver it: taking it
     * first and then bailing on one of the guards below would discard the very backlog this exists to
     * protect. Whichever of two concurrent joins wins the {@code remove} does the delivery; the other
     * sees null and does nothing, so a buffered message is never delivered twice.
     */
    public void flushPendingDeliveries(String roomJid) {
        Deque<PendingDelivery> pending = pendingRoomDeliveries.get(roomJid);
        if (pending == null) return;
        pruneStale(pending);
        MUCRoom room = findLocalRoom(roomJid);
        if (room == null) {
            // Room is gone — nothing can ever be delivered into it, so drop the backlog rather than
            // leaving it to age out (this is the one guard where re-queueing would be pointless).
            pendingRoomDeliveries.remove(roomJid);
            return;
        }
        if (pending.isEmpty()) return;
        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) return;   // still empty — leave queued for the next join or TTL
        Deque<PendingDelivery> q = pendingRoomDeliveries.remove(roomJid);
        if (q == null) return;             // a concurrent join drained it first
        Log.info("injectMessage: {} gained an occupant — delivering {} buffered message(s)", roomJid, q.size());
        for (PendingDelivery pd : q) {
            deliverToOccupants(pd.deliverEl(), pd.virtualFrom(), occupants);
        }
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

        String occupantId = occupantIdFor(room, senderNick);
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
                // XEP-0153 avatar hash: without this, a client has no signal that the virtual
                // occupant even has a vCard photo worth fetching, so it never issues a vCard get
                // in the first place — answerVCardLocally never gets a chance to relay one back.
                for (Element xEl : presEl.elements("x")) {
                    if ("vcard-temp:x:update".equals(xEl.getNamespaceURI())) {
                        delivery.getElement().add(xEl.createCopy());
                    }
                }
            }

            Element x    = delivery.getElement().addElement("x", "http://jabber.org/protocol/muc#user");
            Element item = x.addElement("item");
            item.addAttribute("affiliation", "none");
            item.addAttribute("role", leaving ? "none" : "participant");
            if (originalFrom != null) item.addAttribute("jid", originalFrom);
            // XEP-0421: the room advertises occupant-id, so its occupants' presence must carry it;
            // the same value as on this occupant's messages (see stampRoomIdentity).
            if (occupantId != null) {
                delivery.getElement().addElement("occupant-id", NS_OCCUPANT_ID).addAttribute("id", occupantId);
            }

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
                // Copy show/status/avatar-hash from occupant's last known presence (see injectPresence's
                // vcard-temp:x:update copy for why the avatar hash matters).
                Element curEl = occupant.getPresence().getElement();
                Element showEl = curEl.element("show");
                if (showEl != null) sync.getElement().addElement("show").setText(showEl.getText());
                for (Element statusEl : curEl.elements("status")) {
                    sync.getElement().addElement("status").setText(statusEl.getText());
                }
                for (Element xEl : curEl.elements("x")) {
                    if ("vcard-temp:x:update".equals(xEl.getNamespaceURI())) {
                        sync.getElement().add(xEl.createCopy());
                    }
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

    /**
     * Diagnostic only (see [[architecture-protocol]] avatar-hash investigation): mirrors
     * {@link FederationManager#avatarHashOf} on the receiving end of presence-forward, so a hash
     * present at the origin can be confirmed to have survived the hop-to-hop relay unmodified.
     */
    private String avatarHashOf(Presence pres) {
        for (Element xEl : pres.getElement().elements("x")) {
            if (!"vcard-temp:x:update".equals(xEl.getNamespaceURI())) continue;
            Element photo = xEl.element("photo");
            if (photo == null) return "x-present,no-photo-el";
            String hash = photo.getTextTrim();
            return hash.isEmpty() ? "x-present,empty-photo" : hash;
        }
        return "no-x-update-element";
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
     * Conservative syntactic gate for a peer-supplied JID before we cache, route on, or render it.
     * Rejects control characters, whitespace, and the characters that enable admin-console script
     * injection ({@code " ' < > & \}) — none of which are valid in an XMPP domain or room JID. This
     * is not full RFC 7622 validation; it closes the injection/robustness surface (see the admin UI's
     * inline event handlers) without rejecting legitimate lab room JIDs like {@code r_ext@conference.2503}.
     */
    private static boolean isSafeFederationJid(String jid) {
        if (jid == null || jid.isEmpty() || jid.length() > 3071) return false;   // RFC 7622 max length
        for (int i = 0; i < jid.length(); i++) {
            char c = jid.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;                             // control chars
            if (c == '"' || c == '\'' || c == '<' || c == '>'
                    || c == '&' || c == '\\' || c == ' ') return false;
        }
        return jid.indexOf('@') > 0 || jid.indexOf('.') > 0;   // a room JID or at least a dotted domain
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
     *       forged our-side identity and is dropped — UNLESS the packet's claimed entry server
     *       ({@code claimedEntry}, the muc-forward {@code src} attr a fan-out hub re-stamps with
     *       its own domain) is distinct from the sender and itself routes through the sender:
     *       that is the hub fan-out shape, where a spoke's user legitimately reaches us from the
     *       hub's direction instead of its own. src == sender (or absent) earns no allowance —
     *       an off-route origin on the sender's own word is exactly the forgery this gate stops.
     *       Residual: an untrusted peer already ON the relay path to a mapped hub can claim
     *       origins behind itself — no new power, since an on-path relay can already tamper with
     *       the legitimate traffic it carries (the overlay has no end-to-end signing). NOT
     *       applied to trusted peers: asymmetric DV routes (diamonds) and hub fan-out make
     *       legitimate trusted traffic arrive off the origin's route, so a strict check there
     *       would break real flows — the trusted mesh remains a documented trust boundary.</li>
     * </ul>
     */
    private boolean payloadOriginOk(String fromDomain, String payloadFrom, String what,
                                    boolean rejectLocalClaim, String claimedEntry) {
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
        if (claimedEntry != null && !claimedEntry.equals(fromDomain)) {
            java.util.Optional<String> entryHop = manager.getRoutingTable().findNextHop(claimedEntry);
            if (entryHop.isPresent() && entryHop.get().equals(fromDomain)) {
                // Hub fan-out: the mapped entry server is behind the sender, so the packet's
                // immediate provenance is consistent with the link it arrived on (see Javadoc).
                Log.debug("{} from untrusted peer {}: off-route origin {} allowed — claimed entry {} "
                        + "routes via the sender (hub fan-out)", what, fromDomain, host, claimedEntry);
                return true;
            }
        }
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

    /**
     * Control-plane counterpart of {@link #payloadOriginOk}: whether an untrusted peer may make a claim
     * on behalf of {@code origin} — the {@code origin} attribute of a room advertisement, a mapping
     * request or lifecycle message, an unmap, or a probe. Allowed when the origin is the peer itself, or
     * a server we route THROUGH that peer. Anything else is the peer speaking for a server on another
     * side of the mesh, which is how a forged reject, accept or unmap used to reach mappings it had no
     * part in.
     *
     * <p>Stricter than {@code payloadOriginOk} in one respect: an origin we cannot route to at all is
     * refused rather than allowed. A control-plane action for a server we cannot reach is not one we
     * could carry out anyway, and there is no need to give the benefit of the doubt. Trusted peers are
     * not checked, matching the documented asymmetry: diamonds and hub fan-out make legitimate trusted
     * traffic arrive from the "wrong" direction.
     */
    private boolean claimedOriginOk(String fromDomain, String origin, String what) {
        if (!manager.getPeerRegistry().isUntrusted(fromDomain)) return true;
        if (origin != null && origin.equals(fromDomain)) return true;
        if (origin != null && manager.getRoutingTable().findNextHop(origin)
                                     .map(fromDomain::equals).orElse(false)) {
            return true;
        }
        Log.warn("SECURITY: dropping {} from untrusted peer {} — it claims origin {}, which is not that "
               + "peer or a server reached through it", what, fromDomain, origin);
        return false;
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
        // RFC 6120 §8.3: an error stanza MUST carry a defined condition. A missing or empty
        // federation child is a malformed request, so bad-request (type modify) with the reason
        // as descriptive <text/>. setError() also flips the stanza's type attribute to "error".
        err.setError(new PacketError(PacketError.Condition.bad_request,
                PacketError.Type.modify, reason));
        return err;
    }
}
