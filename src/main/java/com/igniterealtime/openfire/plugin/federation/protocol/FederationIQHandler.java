package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.ContactListManager;
import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationNames;
import com.igniterealtime.openfire.plugin.federation.files.FileRelayManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.dom4j.Element;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.handler.IQHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_DESTINATION;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_EDGE;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_LOCAL;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_ORIGIN;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_REMOTE;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_TS;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_VIA;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.appendToTrail;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.viaContains;

/**
 * Entry point for every incoming federation IQ ({@value FederationStanzaFactory#NS}). It checks the
 * sender, then handles peering, routing, room advertisements and mappings itself, and hands room
 * traffic, 1:1 traffic, contact lists and file transfers to their handlers.
 */
public class FederationIQHandler extends IQHandler {

    private static final Logger Log = LoggerFactory.getLogger(FederationIQHandler.class);

    /** Bounds on one server's advertised rooms, so a peer cannot make every server cache megabytes. */
    private static final int MAX_ADVERTISED_ROOMS  = 250;
    private static final int MAX_ROOM_NAME         = 128;
    private static final int MAX_ROOM_DESCRIPTION  = 512;
    private static final int MAX_ROOM_VISIBLE_TO   = 64;

    /** Hop count assumed for a routing entry whose hops attribute is missing or not a number. */
    private static final int UNKNOWN_HOPS = 99;

    private final IQHandlerInfo info;
    private final FederationManager manager;
    private final InboundValidation validation;
    private final MucTrafficHandler mucTraffic;
    private final DirectTrafficHandler directTraffic;

    FederationIQHandler(FederationManager manager, InboundValidation validation,
                               MucTrafficHandler mucTraffic, DirectTrafficHandler directTraffic) {
        super("Federation IQ Handler");
        this.manager       = manager;
        this.validation    = validation;
        this.mucTraffic    = mucTraffic;
        this.directTraffic = directTraffic;
        this.info          = new IQHandlerInfo(FederationStanzaFactory.ELEMENT, FederationStanzaFactory.NS);
    }

    /** Creates the IQ handler with its traffic handlers, which the manager exposes to the other hooks. */
    public static FederationIQHandler create(FederationManager manager) {
        InboundValidation validation = new InboundValidation(manager);
        return new FederationIQHandler(manager, validation,
                new MucTrafficHandler(manager, validation), new DirectTrafficHandler(manager, validation));
    }

    public MucTrafficHandler getMucTraffic() {
        return mucTraffic;
    }

    public DirectTrafficHandler getDirectTraffic() {
        return directTraffic;
    }

    @Override
    public IQHandlerInfo getInfo() {
        return info;
    }

    /**
     * Checks a federation IQ and dispatches its action. A dropped action is still acknowledged with a
     * result (docs/protocol/01); unexpected failures propagate to Openfire, which logs them.
     */
    @Override
    public IQ handleIQ(IQ packet) {
        IQ.Type type = packet.getType();
        if (type == IQ.Type.error || type == IQ.Type.result) {
            Log.debug("Ignoring IQ {} from {}", type, packet.getFrom());
            return null;
        }
        if (type != IQ.Type.set) {
            return IQ.createResultIQ(packet);
        }

        Element fed = packet.getChildElement();
        if (fed == null) return error(packet, "Missing federation element");
        List<Element> actions = fed.elements();
        if (actions.isEmpty()) return error(packet, "Empty federation element");
        Element child = actions.get(0);

        String fromDomain = validation.senderDomain(packet.getFrom(), child.getName());
        if (fromDomain == null) return IQ.createResultIQ(packet);
        if (InboundValidation.hasControlCharacters(child)) {
            Log.warn("SECURITY: dropping federation '{}' from {} — a routing attribute contains a control character",
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
            case "muc-forward"         -> mucTraffic.handle(fromDomain, child);
            case "direct-forward"      -> directTraffic.handleDirectForward(fromDomain, child);
            case "presence-forward"    -> directTraffic.handlePresenceForward(fromDomain, child);
            case "iq-forward"          -> directTraffic.handleIqForward(fromDomain, child);
            case "contact-list", "contact-list-request"
                                       -> handleContactListAction(child.getName(), fromDomain, child);
            // Retired in 1.10.13; peers on older builds may still send them.
            case "user-directory", "bookmark-push"
                                       -> Log.debug("Ignoring retired federation action '{}' from {}",
                                                    child.getName(), fromDomain);
            case "file-request", "file-offer", "file-chunk", "file-error"
                                       -> handleFileRelay(child.getName(), fromDomain, child);
            default -> Log.warn("Unknown federation action '{}' from {}", child.getName(), fromDomain);
        }

        return IQ.createResultIQ(packet);
    }

    /** Records a live peer, negotiates trust, and sends our state when the link has just come up. */
    private void handlePeerAnnounce(String fromDomain, Element el) {
        boolean isReply = "true".equals(el.attributeValue("reply"));
        var peers = manager.getPeerRegistry();
        PeerServer.Status current = peers.getPeer(fromDomain).map(PeerServer::getStatus).orElse(null);

        if (current == PeerServer.Status.DISABLED) {
            Log.debug("peer-announce from DISABLED peer {} — re-asserting disable", fromDomain);
            manager.sendPeerDisable(fromDomain);
            return;
        }
        if (current == PeerServer.Status.REMOTE_DISABLED) {
            Log.info("peer {} re-enabled the connection — clearing REMOTE_DISABLED", fromDomain);
            peers.setControlStatus(fromDomain, PeerServer.Status.UNKNOWN);
        }

        Log.debug("peer-announce from {}{}", fromDomain, isReply ? " (reply)" : "");

        if (!peers.contains(fromDomain)) {
            peers.addPeer(fromDomain);
            peers.setUntrusted(fromDomain, true);
            Log.info("Auto-registered federation peer via incoming connection: {} (untrusted, nothing "
                   + "exposed — review it on the Peer Servers tab)", fromDomain);
        }

        boolean remoteUntrusted = "true".equals(el.attributeValue("untrusted"));
        peers.getPeer(fromDomain).ifPresent(p -> {
            p.setRemoteConfirmed(true);
            p.setRemoteUntrusted(remoteUntrusted);
        });
        boolean localUntrusted = peers.isUntrusted(fromDomain);
        if (localUntrusted != remoteUntrusted) {
            Log.warn("Trust mismatch from {}: local untrusted={}, remote untrusted={} — blocking link",
                     fromDomain, localUntrusted, remoteUntrusted);
            manager.blockForTrustMismatch(fromDomain);
            return;
        }
        if (current == PeerServer.Status.TRUST_MISMATCH) {
            peers.clearTrustMismatch(fromDomain);
        }

        manager.getRoutingTable().addDirectPeer(fromDomain);

        // Gossip only on the transition to REACHABLE; answering every announce would loop.
        boolean wasReachable = peers.getPeer(fromDomain)
                .map(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                .orElse(false);
        peers.updateStatus(fromDomain, PeerServer.Status.REACHABLE);

        if (!wasReachable) {
            manager.sendFullGossip(fromDomain);
            manager.propagateRoutingToAll(fromDomain);
            // S2SMonitor.onPeerUp will not fire for a peer already marked REACHABLE, so run its steps here.
            manager.solicitRouting(fromDomain);
            manager.getOccupantSync().resyncMappedDestinations(Set.of(fromDomain));
            manager.resendPendingRequests(fromDomain);
        } else if (!isReply) {
            // Keepalive: one reply keeps the reverse S2S socket warm; a reply is never answered.
            manager.sendPeerAnnounceReply(fromDomain);
        }
    }

    /** The peer removed us: drops its mappings, routes and cached state, and marks it WITHDRAWN. */
    private void handlePeerWithdraw(String fromDomain) {
        Log.info("peer-withdraw from {} — marking WITHDRAWN and clearing cached state", fromDomain);
        tearDownLinkTo(fromDomain,
                () -> manager.getPeerRegistry().updateStatus(fromDomain, PeerServer.Status.WITHDRAWN));
    }

    /** The peer's admin disabled the link: tears federation down and records REMOTE_DISABLED. */
    private void handlePeerDisable(String fromDomain) {
        Log.info("peer-disable from {} — tearing down and marking REMOTE_DISABLED (cannot re-enable locally)",
                 fromDomain);
        if (!manager.getPeerRegistry().contains(fromDomain)) {
            manager.getPeerRegistry().addPeer(fromDomain);
            manager.getPeerRegistry().setUntrusted(fromDomain, true);
        }
        tearDownLinkTo(fromDomain,
                () -> manager.getPeerRegistry().setControlStatus(fromDomain, PeerServer.Status.REMOTE_DISABLED));
    }

    /**
     * Drops the mappings and routes to a peer that withdrew or disabled the link, and the state
     * reached through it. The new status is applied before the change is gossiped, so the peer is not sent it.
     */
    private void tearDownLinkTo(String domain, Runnable applyStatus) {
        for (String localJid : new ArrayList<>(manager.getRoomManager().getLocalMappings().keySet())) {
            manager.getRoomManager().removeMapping(localJid, domain);
        }
        Set<String> removed = manager.getRoutingTable().removePeer(domain);
        manager.handleUnreachableDestinations(removed.isEmpty() ? Set.of(domain) : removed);
        applyStatus.run();
        manager.getPeerRegistry().getPeer(domain).ifPresent(p -> p.setRemoteConfirmed(false));
        if (!removed.isEmpty()) {
            manager.propagateTopologyChange(domain);
        }
    }

    /** Merges a peer's distance-vector table into ours and passes on whatever changed. */
    private void handleRoutingUpdate(String fromDomain, Element el) {
        boolean untrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        List<RouteEntry> received = new ArrayList<>();
        for (Element entry : el.elements("entry")) {
            RouteEntry route = acceptedRoute(fromDomain, entry, untrusted);
            if (route != null) received.add(route);
        }

        Set<String> changed = manager.getRoutingTable().updateFromPeer(fromDomain, received,
                manager.getPeerRegistry()::isUntrusted);
        Log.debug("routing-update from {} — {} entries, {} changed", fromDomain, received.size(), changed.size());
        if (changed.isEmpty()) return;

        manager.handleUnreachableDestinations(changed);
        manager.getOccupantSync().resyncMappedDestinations(changed);
        manager.propagateRoutingToAll(fromDomain);
        manager.propagateRoomsToAllDebounced();
        boolean lostRoute = changed.stream().anyMatch(d -> !manager.getRoutingTable().isReachable(d));
        if (lostRoute) manager.solicitRoutingFromAll(fromDomain);
    }

    /**
     * The route a routing-update entry advertises, or null when it is incomplete, refused by the
     * admin, or an untrusted peer claiming to reach one of our trusted peers.
     */
    private RouteEntry acceptedRoute(String fromDomain, Element entry, boolean untrusted) {
        String dest = entry.attributeValue(ATTR_DESTINATION);
        String via  = entry.attributeValue(ATTR_VIA);
        if (dest == null || via == null) return null;
        if (manager.getPeerRegistry().isRouteDenied(fromDomain, dest)) {
            Log.debug("routing-update from {}: destination {} is denied by admin — skipping", fromDomain, dest);
            return null;
        }
        if (untrusted && manager.getPeerRegistry().contains(dest) && !manager.getPeerRegistry().isUntrusted(dest)) {
            Log.warn("SECURITY: ignoring route to {} advertised by untrusted peer {} — that is "
                   + "one of this server's trusted peers", dest, fromDomain);
            return null;
        }
        boolean edge = "true".equals(entry.attributeValue(ATTR_EDGE));
        return new RouteEntry(dest, via, hopsOf(entry), edge);
    }

    private static int hopsOf(Element entry) {
        try {
            return Integer.parseInt(entry.attributeValue("hops", String.valueOf(UNKNOWN_HOPS)));
        } catch (NumberFormatException e) {
            return UNKNOWN_HOPS;
        }
    }

    /** A peer that lost routes asks for our state: replies with our routing table and rooms. */
    private void handleRoutingSolicit(String fromDomain) {
        Log.debug("routing-solicit from {} — replying with routing table + room state", fromDomain);
        manager.sendRoutingUpdate(fromDomain);
        manager.sendRoomState(fromDomain);
    }

    /** Caches the rooms one server advertises, where an empty list withdraws them, and relays the advertisement. */
    private void handleRoomAdvertisement(String fromDomain, Element el) {
        String origin = el.attributeValue(ATTR_ORIGIN);
        String via    = el.attributeValue(ATTR_VIA, "");
        if (viaContains(via, localDomain())) {
            Log.debug("room-advertisement loop detected (via={}), dropping", via);
            return;
        }

        String sourceDomain = (origin != null) ? origin : fromDomain;
        if (!validation.claimedOriginOk(fromDomain, sourceDomain, "room-advertisement")) return;
        if (localDomain().equals(sourceDomain)) {
            Log.debug("room-advertisement origin is our own domain, ignoring");
            return;
        }
        if (manager.getPeerRegistry().isRouteDenied(fromDomain, sourceDomain)) {
            Log.debug("room-advertisement from {} for denied origin {} — dropping", fromDomain, sourceDomain);
            return;
        }

        List<FederatedRoom> rooms = advertisedRooms(fromDomain, sourceDomain, el);
        if (rooms.isEmpty()) {
            // Clear only this origin's rooms, not everything relayed through the sender.
            manager.getRoomManager().clearRemoteRoomsForOrigin(sourceDomain);
            Log.debug("room-advertisement WITHDRAWAL from {} (source={}) — clearing origin's rooms and relaying",
                      fromDomain, sourceDomain);
        } else {
            manager.getRoomManager().updateRemoteRooms(sourceDomain, fromDomain, rooms);
            Log.debug("room-advertisement from {} (source={}) — {} room(s)", fromDomain, sourceDomain, rooms.size());
        }
        manager.relayRoomAdvertisement(fromDomain, sourceDomain, rooms, appendToTrail(via, localDomain()));
    }

    /** The well-formed rooms of an advertisement, within the size limits; malformed or excess rooms are logged. */
    private List<FederatedRoom> advertisedRooms(String fromDomain, String sourceDomain, Element el) {
        List<FederatedRoom> rooms = new ArrayList<>();
        int overLimit = 0;
        for (Element r : el.elements("room")) {
            if (rooms.size() >= MAX_ADVERTISED_ROOMS) {
                overLimit++;
                continue;
            }
            String jid = r.attributeValue("jid");
            if (jid == null) continue;
            if (!FederationNames.isSafeJid(jid)) {
                Log.warn("SECURITY: dropping advertised room with malformed JID from {} (len={})",
                         fromDomain, jid.length());
                continue;
            }
            String name = clip(r.attributeValue("name", ""), MAX_ROOM_NAME);
            String desc = clip(r.attributeValue("description", ""), MAX_ROOM_DESCRIPTION);
            rooms.add(new FederatedRoom(jid, name, desc, sourceDomain, visibleTo(r)));
        }
        if (overLimit > 0) {
            Log.warn("room-advertisement from {} (source={}): ignored {} room(s) past the limit of {}",
                     fromDomain, sourceDomain, overLimit, MAX_ADVERTISED_ROOMS);
        }
        return rooms;
    }

    /** An advertised room's visibility list, lowercased and capped; absent means visible to nobody. */
    private static Set<String> visibleTo(Element room) {
        Set<String> visibleTo = new LinkedHashSet<>();
        for (String s : room.attributeValue("visibleto", "").split(",")) {
            if (visibleTo.size() >= MAX_ROOM_VISIBLE_TO) break;
            if (!s.isBlank()) visibleTo.add(s.strip().toLowerCase());
        }
        return visibleTo;
    }

    /** Relays a mapping request toward its destination, or records it PENDING_IN when the room is ours. */
    private void handleRoomMapping(String fromDomain, Element el) {
        String destination = el.attributeValue(ATTR_DESTINATION);
        String origin = el.attributeValue(ATTR_ORIGIN);
        if (!validation.claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-mapping")) return;

        if (!InboundValidation.isForUs(destination)) {
            if (!validation.untrustedAllowsServer(fromDomain, destination)) {
                Log.warn("SECURITY: rejecting relayed room-mapping from untrusted peer {} — server {} "
                       + "is not exposed to it", fromDomain, destination);
                return;
            }
            String nextHop = nextHopOrDrop(destination, "room-mapping");
            if (nextHop == null) return;
            for (Element map : el.elements("map")) {
                String theirLocal  = map.attributeValue(ATTR_LOCAL);
                String theirRemote = map.attributeValue(ATTR_REMOTE);
                if (theirLocal == null || theirRemote == null) continue;
                FederationSender.send("room-mapping", destination, () -> FederationStanzaFactory.roomMapping(
                        nextHop, destination, origin, theirLocal, theirRemote));
            }
            return;
        }

        String actualOrigin = originOf(el, fromDomain);
        for (Element map : el.elements("map")) {
            // The sender's "local" room is our remote one, and its "remote" room is ours.
            String theirLocal  = map.attributeValue(ATTR_LOCAL);
            String theirRemote = map.attributeValue(ATTR_REMOTE);
            if (theirLocal != null && theirRemote != null) {
                receiveMappingRequest(fromDomain, actualOrigin, theirRemote, theirLocal);
            }
        }
    }

    /**
     * Records a request to map our room {@code localRoom} to {@code remoteRoom} on {@code origin}, if
     * the room is shared with that server, and accepts it at once when the room auto-accepts.
     */
    private void receiveMappingRequest(String fromDomain, String origin, String localRoom, String remoteRoom) {
        if (!manager.roomSharedWith(localRoom, origin)) {
            Log.warn("SECURITY: rejecting room-mapping from {} onto local room {} — "
                   + "that room is not federation-enabled here, or its visibility ACL "
                   + "does not include this origin", origin, localRoom);
            return;
        }
        if (!validation.untrustedAllowsServer(fromDomain, localDomain())) {
            Log.warn("SECURITY: rejecting room-mapping from untrusted peer {} onto local room {} — "
                   + "this server ({}) is not exposed to it", fromDomain, localRoom, localDomain());
            return;
        }
        RoomMapping existing = manager.getRoomManager().getMappingForLocal(localRoom, origin);
        if (existing != null && !handleRepeatRequest(existing, remoteRoom)) return;
        manager.getRoomManager().addMapping(localRoom, remoteRoom, origin, RoomMapping.State.PENDING_IN, "");
        Log.info("Mapping request received from {}: local={} ↔ remote={} (pending)", origin, localRoom, remoteRoom);
        if (manager.getRoomManager().isAutoAccept(localRoom)) {
            manager.acceptMapping(localRoom, origin);
        }
    }

    /**
     * Applies a request for a mapping we already hold. True when it should replace the record as a
     * fresh PENDING_IN; false when handled here, since an ACTIVE or disabled mapping is never demoted.
     */
    private boolean handleRepeatRequest(RoomMapping existing, String theirLocal) {
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
                // Both admins asked for this pairing; the lower domain accepts, the higher re-sends.
                if (localDomain().compareTo(origin) < 0) {
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

    private void handleMappingAccept(String fromDomain, Element el) {
        if (!checkedMappingControl("room-mapping-accept", fromDomain, el)) return;
        String origin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue(ATTR_LOCAL);
            String ourLocal   = map.attributeValue(ATTR_REMOTE);
            if (ourLocal != null) manager.onMappingAccepted(ourLocal, origin, theirLocal, token);
        }
    }

    private void handleMappingReject(String fromDomain, Element el) {
        if (!checkedMappingControl("room-mapping-reject", fromDomain, el)) return;
        String origin = originOf(el, fromDomain);
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue(ATTR_REMOTE);
            if (ourLocal != null) manager.onMappingRejected(ourLocal, origin);
        }
    }

    private void handleMappingDisable(String fromDomain, Element el) {
        if (!checkedMappingControl("room-mapping-disable", fromDomain, el)) return;
        String origin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String ourLocal = map.attributeValue(ATTR_REMOTE);
            if (ourLocal != null && tokenOk(ourLocal, origin, token, "disable")) {
                manager.onMappingDisabledByPeer(ourLocal, origin);
            }
        }
    }

    private void handleMappingEnable(String fromDomain, Element el) {
        if (!checkedMappingControl("room-mapping-enable", fromDomain, el)) return;
        String origin = originOf(el, fromDomain);
        String token = el.attributeValue("token", "");
        for (Element map : el.elements("map")) {
            String theirLocal = map.attributeValue(ATTR_LOCAL);
            String ourLocal   = map.attributeValue(ATTR_REMOTE);
            if (ourLocal != null && tokenOk(ourLocal, origin, token, "enable")) {
                manager.onMappingEnabledByPeer(ourLocal, origin, theirLocal);
            }
        }
    }

    /** Whether a mapping lifecycle action passed the origin check and is for us; one for another server is relayed. */
    private boolean checkedMappingControl(String element, String fromDomain, Element el) {
        if (!validation.claimedOriginOk(fromDomain, originOf(el, fromDomain), element)) return false;
        return !relayMappingControl(element, fromDomain, el);
    }

    private String originOf(Element el, String fromDomain) {
        String origin = el.attributeValue(ATTR_ORIGIN);
        return (origin != null) ? origin : fromDomain;
    }

    /** Relays a mapping lifecycle IQ that is not for us; true when the caller must stop. */
    private boolean relayMappingControl(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(ATTR_DESTINATION);
        if (InboundValidation.isForUs(destination)) return false;
        if (!validation.untrustedAllowsServer(fromDomain, destination)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                     element, fromDomain, destination);
            return true;
        }
        String nextHop = nextHopOrDrop(destination, element);
        if (nextHop == null) return true;
        String origin = el.attributeValue(ATTR_ORIGIN);
        String token  = el.attributeValue("token");
        String reason = el.attributeValue("reason");
        for (Element map : el.elements("map")) {
            String l = map.attributeValue(ATTR_LOCAL);
            String r = map.attributeValue(ATTR_REMOTE);
            if (l == null || r == null) continue;
            FederationSender.send(element, destination, () -> FederationStanzaFactory.mappingLifecycle(
                    element, nextHop, destination, origin, l, r, token, reason));
        }
        return true;
    }

    /**
     * Relays a mapping probe, or answers it with a pong when we are the destination. Only a server
     * we hold a mapping with gets an answer, so the probe cannot be used to sweep for domains.
     */
    private void handleMappingPing(String fromDomain, Element el) {
        String origin = el.attributeValue(ATTR_ORIGIN);
        if (origin == null || origin.isEmpty()) return;
        if (!validation.claimedOriginOk(fromDomain, origin, "mapping-ping")) return;
        if (relayMappingProbe("mapping-ping", fromDomain, el)) return;
        if (!manager.getRoomManager().hasMappingWith(origin)) {
            Log.debug("mapping-ping from {} — no active mapping with it, not answering", origin);
            return;
        }
        manager.getMappingProbe().markProbeCapable(origin);
        String nextHop = manager.getRoutingTable().findNextHop(origin).orElse(null);
        if (nextHop == null) {
            Log.debug("mapping-ping from {} — no route back, cannot answer", origin);
            return;
        }
        String ts = el.attributeValue(ATTR_TS);
        FederationSender.send("mapping-pong", origin, () -> FederationStanzaFactory.mappingPong(
                nextHop, origin, localDomain(), "", ts));
    }

    /** Relays a mapping-pong, or records the round trip when it answers one of our probes. */
    private void handleMappingPong(String fromDomain, Element el) {
        String origin = el.attributeValue(ATTR_ORIGIN);
        if (origin == null || origin.isEmpty()) return;
        if (!validation.claimedOriginOk(fromDomain, origin, "mapping-pong")) return;
        if (relayMappingProbe("mapping-pong", fromDomain, el)) return;
        manager.getMappingProbe().onPong(origin, el.attributeValue(ATTR_TS));
    }

    /** Relays a mapping probe that is not for us; true when it was relayed or dropped. */
    private boolean relayMappingProbe(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(ATTR_DESTINATION);
        if (InboundValidation.isForUs(destination)) return false;
        String via = el.attributeValue(ATTR_VIA, "");
        if (viaContains(via, localDomain())) {
            Log.warn("{} loop detected (via={}), dropping", element, via);
            return true;
        }
        if (!validation.untrustedAllowsServer(fromDomain, destination)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                     element, fromDomain, destination);
            return true;
        }
        String nextHop = manager.getRoutingTable().findNextHop(destination).orElse(null);
        if (nextHop == null) {
            // A ping with no route on is exactly what the origin detects through the missing pong.
            Log.debug("{}: no route to {}, dropping", element, destination);
            return true;
        }
        String origin = el.attributeValue(ATTR_ORIGIN);
        String ts     = el.attributeValue(ATTR_TS);
        String newVia = appendToTrail(via, localDomain());
        boolean ping  = element.equals("mapping-ping");
        FederationSender.send(element, destination, () -> ping
                ? FederationStanzaFactory.mappingPing(nextHop, destination, origin, newVia, ts)
                : FederationStanzaFactory.mappingPong(nextHop, destination, origin, newVia, ts));
        return true;
    }

    /** Whether a lifecycle request carries the mapping's token; an empty stored token is accepted. */
    private boolean tokenOk(String localJid, String remoteDomain, String incomingToken, String op) {
        RoomMapping m = manager.getRoomManager().getMappingForLocal(localJid, remoteDomain);
        if (m == null) return false;
        String stored = m.token();
        if (stored == null || stored.isEmpty() || stored.equals(incomingToken)) return true;
        Log.warn("SECURITY: dropping mapping-{} for {} from {} — token mismatch", op, localJid, remoteDomain);
        return false;
    }

    /** Relays a room-unmap toward its destination, or removes the mapping and its occupants here. */
    private void handleRoomUnmap(String fromDomain, Element el) {
        if (!validation.claimedOriginOk(fromDomain, originOf(el, fromDomain), "room-unmap")) return;
        String destination = el.attributeValue(ATTR_DESTINATION);
        String token = el.attributeValue("token", "");

        if (!InboundValidation.isForUs(destination)) {
            if (!validation.untrustedAllowsServer(fromDomain, destination)) {
                Log.warn("SECURITY: dropping room-unmap from untrusted peer {} toward non-exposed server {}",
                         fromDomain, destination);
                return;
            }
            String nextHop = nextHopOrDrop(destination, "room-unmap");
            if (nextHop == null) return;
            String origin = el.attributeValue(ATTR_ORIGIN);
            for (Element map : el.elements("map")) {
                String theirLocal  = map.attributeValue(ATTR_LOCAL);
                String theirRemote = map.attributeValue(ATTR_REMOTE);
                if (theirLocal == null || theirRemote == null) continue;
                // The token travels with the relay, or the destination's token check would drop the unmap.
                FederationSender.send("room-unmap", destination, () -> FederationStanzaFactory.roomUnmapping(
                        nextHop, destination, origin, theirLocal, theirRemote, token));
            }
            return;
        }

        String origin = originOf(el, fromDomain);
        for (Element map : el.elements("map")) {
            String theirLocal  = map.attributeValue(ATTR_LOCAL);
            String ourLocal    = map.attributeValue(ATTR_REMOTE);
            if (ourLocal == null || !tokenOk(ourLocal, origin, token, "unmap")) continue;
            if (theirLocal != null) {
                manager.getOccupantSync().pushVirtualPresences(ourLocal, origin, theirLocal, true);
            }
            manager.getRoomManager().removeMapping(ourLocal, origin);
            manager.getOccupantSync().evictForInactiveMapping(ourLocal, origin);
            Log.info("Room mapping removed by remote {}: local={}", origin, ourLocal);
        }
    }

    /**
     * Relays a contact-list or contact-list-request toward its destination, or applies it here.
     * Over an untrusted link the per-peer contact-list settings and the exposure rules apply.
     */
    private void handleContactListAction(String element, String fromDomain, Element el) {
        String destination = el.attributeValue(ATTR_DESTINATION);
        String origin      = el.attributeValue(ATTR_ORIGIN);
        String via         = el.attributeValue(ATTR_VIA, "");

        if (destination == null || origin == null || origin.equals(localDomain())) {
            Log.warn("{} from {} has a missing or invalid origin/destination ({} → {}), dropping",
                     element, fromDomain, origin, destination);
            return;
        }
        boolean fromUntrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        if (fromUntrusted) {
            if ("contact-list".equals(element) && manager.getPeerRegistry().isContactListsRefused(fromDomain)) {
                Log.debug("Refusing contact-list from untrusted peer {} (origin {}) — refused on that link here",
                          fromDomain, origin);
                return;
            }
            if (!validation.claimedOriginOk(fromDomain, origin, element)) return;
            if (!validation.exposureOk(fromDomain, destination, element)) return;
        }
        if (viaContains(via, localDomain())) {
            Log.warn("{} loop detected (via={}), dropping", element, via);
            return;
        }

        if (!destination.equals(localDomain())) {
            relayContactListAction(element, el, destination, origin, via);
        } else if ("contact-list-request".equals(element)) {
            manager.getContactLists().handleRequest(origin);
        } else {
            boolean crossedUntrusted = fromUntrusted || manager.getContactLists().crossesUntrusted(origin);
            manager.getContactLists().handleContactList(origin, receivedContacts(origin, el), crossedUntrusted);
        }
    }

    /**
     * Relays a contact-list action. Leaving over an untrusted link it needs the origin exposed to that
     * peer, and a list also needs the link's contact-list permission.
     */
    private void relayContactListAction(String element, Element el, String destination, String origin, String via) {
        String nextHop = manager.getRoutingTable().findNextHop(destination).orElse(null);
        if (nextHop == null) {
            Log.debug("{}: no route to {}, dropping", element, destination);
            return;
        }
        if (manager.getPeerRegistry().isUntrusted(nextHop)) {
            // A request carries no contacts, so only a list needs the link's permission.
            boolean allowed = "contact-list-request".equals(element)
                           || manager.getPeerRegistry().isContactListsAllowed(nextHop);
            if (!allowed || !manager.getPeerRegistry().getExposedServers(nextHop).contains(origin)) {
                Log.warn("SECURITY: not relaying {} from {} toward {} — the next hop {} is untrusted and {}",
                         element, origin, destination, nextHop,
                         allowed ? origin + " is not exposed to it"
                                 : "sending contact lists is not allowed on that link here");
                return;
            }
        }
        String newVia = appendToTrail(via, localDomain());
        FederationSender.send(element, destination, () -> FederationStanzaFactory.relay(nextHop, el, newVia));
    }

    /** The valid contacts of a received list, up to the limit; the rest are counted and logged. */
    private static List<ContactListManager.Contact> receivedContacts(String origin, Element el) {
        List<ContactListManager.Contact> contacts = new ArrayList<>();
        int rejected = 0;
        for (Element c : el.elements("contact")) {
            ContactListManager.Contact contact =
                    ContactListManager.parseContact(origin, c.attributeValue("jid"), c.attributeValue("name"));
            if (contact == null || contacts.size() >= ContactListManager.MAX_CONTACTS) {
                rejected++;
                continue;
            }
            contacts.add(contact);
        }
        if (rejected > 0) {
            Log.warn("contact-list from {}: ignored {} entr(ies) — not a bare JID on {}, or past the "
                   + "{}-contact limit", origin, rejected, origin, ContactListManager.MAX_CONTACTS);
        }
        return contacts;
    }

    /**
     * Relays a file-* element toward its destination when we are a transit hop, otherwise hands it
     * to the {@link FileRelayManager}. Untrusted peers are held to the exposure rules both ways.
     */
    private void handleFileRelay(String element, String fromDomain, Element el) {
        FileRelayManager relay = manager.getFileRelay();
        if (relay == null) return;
        String destination = el.attributeValue(ATTR_DESTINATION);
        if (!InboundValidation.isForUs(destination)) {
            if (!validation.untrustedAllowsServer(fromDomain, destination)) {
                Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                         element, fromDomain, destination);
                return;
            }
            relay.relayToward(element, el, fromDomain);
            return;
        }
        if (!validation.untrustedAllowsServer(fromDomain, localDomain())) {
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

    /** The next hop toward {@code destination}, or null after logging that the {@code what} is dropped. */
    private String nextHopOrDrop(String destination, String what) {
        String nextHop = manager.getRoutingTable().findNextHop(destination).orElse(null);
        if (nextHop == null) Log.warn("{}: no route to {}, dropping", what, destination);
        return nextHop;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** A bad-request error reply (RFC 6120 §8.3) carrying {@code reason} as its text. */
    private IQ error(IQ packet, String reason) {
        Log.warn("Federation IQ error — {}", reason);
        IQ err = IQ.createResultIQ(packet);
        err.setError(new PacketError(PacketError.Condition.bad_request, PacketError.Type.modify, reason));
        return err;
    }
}
