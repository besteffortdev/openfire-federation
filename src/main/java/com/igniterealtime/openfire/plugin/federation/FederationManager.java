package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.files.FileRelayManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import com.igniterealtime.openfire.plugin.federation.protocol.DirectTrafficHandler;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationIQHandler;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationPacketInterceptor;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import com.igniterealtime.openfire.plugin.federation.protocol.MucTrafficHandler;
import com.igniterealtime.openfire.plugin.federation.protocol.RedundantArrivals;
import com.igniterealtime.openfire.plugin.federation.protocol.RoomMessageIds;
import org.dom4j.Element;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.OutgoingServerSession;
import org.jivesoftware.openfire.user.PresenceEventDispatcher;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.StringUtils;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Central coordinator of the federation plugin. It owns the subsystems, registers the Openfire hooks
 * on start, and carries out the peer, mapping, gossip and trust actions that the protocol handlers
 * and the admin console request.
 */
public class FederationManager {

    private static final Logger Log = LoggerFactory.getLogger(FederationManager.class);

    /** Window in which routing-driven room floods are coalesced into one. */
    private static final long ROOMS_FLOOD_DEBOUNCE_MS = 2000;

    private final PeerRegistry           peerRegistry      = new PeerRegistry();
    private final FederationRoutingTable routingTable      = new FederationRoutingTable();
    private final FederatedRoomManager   roomManager       = new FederatedRoomManager();
    private final RoomDefaultsManager    roomDefaults      = new RoomDefaultsManager();
    private final ContactListManager     contactLists      = new ContactListManager(this);
    private final FederationFileConfig   fileConfig        = new FederationFileConfig();
    private final RoomMessageIds         roomMessageIds    = new RoomMessageIds();
    private final RedundantArrivals      redundantArrivals = new RedundantArrivals();
    private final PresenceRelay          presenceRelay     = new PresenceRelay(this);
    private final OccupantSync           occupantSync      = new OccupantSync(this);
    private final MappingProbe           mappingProbe      = new MappingProbe(this);
    private final CertificatePinning     certificatePinning = new CertificatePinning(this);
    private final AtomicBoolean          roomsFloodPending = new AtomicBoolean(false);
    private       FileRelayManager       fileRelay;
    private       S2SMonitor             s2sMonitor;
    private       FederationIQHandler    iqHandler;
    private       FederationPacketInterceptor interceptor;
    private       RoomEventListener      roomListener;

    /** Loads persisted state, starts the subsystems and registers the Openfire hooks. */
    public void start() {
        Log.info("Federation plugin starting…");

        FederationProperties.register();
        peerRegistry.load();
        roomManager.load();
        roomDefaults.load();
        contactLists.load();
        LegacyBookmarkCleanup.runOnceInBackground();

        fileRelay = new FileRelayManager(this);
        fileRelay.start();

        iqHandler   = FederationIQHandler.create(this);
        interceptor = new FederationPacketInterceptor(this);
        XMPPServer.getInstance().getIQRouter().addHandler(iqHandler);
        InterceptorManager.getInstance().addInterceptor(interceptor);

        s2sMonitor = new S2SMonitor(peerRegistry, routingTable, this);
        s2sMonitor.start();

        PresenceEventDispatcher.addListener(presenceRelay);
        roomListener = new RoomEventListener(this);
        MUCEventDispatcher.addListener(roomListener);

        fileConfig.ingest(this);

        Log.info("Federation plugin started — {} peer(s) configured", peerRegistry.getPeers().size());
    }

    /**
     * Stops the subsystems and removes every Openfire hook registered by {@link #start()}. Each step
     * runs even if an earlier one fails, so a partial start is undone too.
     */
    public void stop() {
        Log.info("Federation plugin stopping…");
        stopStep("S2S monitor", () -> { if (s2sMonitor != null) s2sMonitor.stop(); });
        stopStep("presence listener", () -> PresenceEventDispatcher.removeListener(presenceRelay));
        stopStep("room listener", () -> { if (roomListener != null) MUCEventDispatcher.removeListener(roomListener); });
        stopStep("IQ handler", () -> {
            if (iqHandler != null) XMPPServer.getInstance().getIQRouter().removeHandler(iqHandler);
        });
        stopStep("packet interceptor", () -> {
            if (interceptor != null) InterceptorManager.getInstance().removeInterceptor(interceptor);
        });
        stopStep("contact lists", contactLists::stop);
        stopStep("file relay", () -> { if (fileRelay != null) fileRelay.stop(); });
        Log.info("Federation plugin stopped.");
    }

    /** Runs one shutdown step; a failure is logged so the remaining steps still run. */
    private static void stopStep(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.warn("Could not stop the {} cleanly", what, e);
        }
    }

    /** Re-reads the {@code <federation>} block of openfire.xml, as the admin console's Reload does. */
    public FederationFileConfig.IngestResult reloadConfigFile() {
        return fileConfig.ingest(this);
    }

    /** Whether {@code domain} is reached through at least one intermediate server. */
    public boolean isMultiHopDomain(String domain) {
        return routingTable.findNextHop(domain).map(nh -> !nh.equals(domain)).orElse(false);
    }

    /** The next hop toward {@code domain}, or the domain itself when there is no route yet. */
    private String nextHopToward(String domain) {
        return routingTable.findNextHop(domain).orElse(domain);
    }

    public PeerServer addPeer(String domain) {
        PeerServer peer = peerRegistry.addPeer(domain);
        Log.info("Peer added: {}", domain);
        sendPeerAnnounce(domain);
        return peer;
    }

    /** Re-announces to a peer, clearing WITHDRAWN first; ignored for a disabled link. */
    public void retryPeer(String domain) {
        PeerServer.Status cur = peerRegistry.getPeer(domain).map(PeerServer::getStatus).orElse(null);
        if (cur == PeerServer.Status.DISABLED || cur == PeerServer.Status.REMOTE_DISABLED) {
            Log.info("retryPeer ignored for {} — status {} (use enable; a REMOTE_DISABLED link can only be "
                   + "lifted by the remote)", domain, cur);
            return;
        }
        Log.info("Retrying S2S for peer: {}", domain);
        peerRegistry.getPeer(domain).ifPresent(p -> {
            if (p.getStatus() == PeerServer.Status.WITHDRAWN) {
                peerRegistry.updateStatus(domain, PeerServer.Status.UNKNOWN);
            }
        });
        sendPeerAnnounce(domain);
    }

    /**
     * Disables a peer: tears federation down as {@link #removePeer} does, but keeps the peer
     * registered as DISABLED and tells it so. {@link #enablePeer} lifts it.
     */
    public void disablePeer(String domain) {
        unmapAllRoomsWith(domain);
        peerRegistry.getPeer(domain).ifPresent(peer -> {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendPeerDisable(domain);
            }
        });
        killSession(domain, "outgoing");
        killSession(domain, "incoming");
        dropRoutesThrough(domain);
        if (!peerRegistry.contains(domain)) peerRegistry.addPeer(domain);
        peerRegistry.setControlStatus(domain, PeerServer.Status.DISABLED);
        Log.info("Peer disabled: {}", domain);
    }

    /** Lifts our own DISABLED status and re-announces, so the remote resumes federation. */
    public void enablePeer(String domain) {
        PeerServer.Status cur = peerRegistry.getPeer(domain).map(PeerServer::getStatus).orElse(null);
        if (cur != PeerServer.Status.DISABLED) {
            Log.info("enablePeer ignored for {} — status {} (only a locally DISABLED peer can be enabled here)",
                     domain, cur);
            return;
        }
        peerRegistry.setControlStatus(domain, PeerServer.Status.UNKNOWN);
        sendPeerAnnounce(domain);
        Log.info("Peer enabled: {}", domain);
    }

    /**
     * Removes a peer: unmaps its rooms while S2S is still up, sends peer-withdraw, closes the
     * sessions and drops every route and cached room reached through it.
     */
    public boolean removePeer(String domain) {
        unmapAllRoomsWith(domain);
        peerRegistry.getPeer(domain).ifPresent(peer -> {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                FederationSender.send("peer-withdraw", domain, () -> FederationStanzaFactory.peerWithdraw(domain));
            }
        });
        killSession(domain, "outgoing");
        killSession(domain, "incoming");
        boolean removed = peerRegistry.removePeer(domain);
        if (removed) dropRoutesThrough(domain);
        return removed;
    }

    /** Unmaps, and tells the remote, every local room that has a mapping to {@code domain}. */
    private void unmapAllRoomsWith(String domain) {
        for (String localJid : new ArrayList<>(roomManager.getLocalMappings().keySet())) {
            if (roomManager.getMappingForLocal(localJid, domain) != null) {
                unmapRoom(localJid, domain);
            }
        }
    }

    /** Drops the routes through a peer and the state they carried, and gossips the change. */
    void dropRoutesThrough(String domain) {
        Set<String> removedRoutes = routingTable.removePeer(domain);
        handleUnreachableDestinations(removedRoutes.isEmpty() ? Set.of(domain) : removedRoutes);
        if (!removedRoutes.isEmpty()) {
            propagateTopologyChange(domain);
        }
    }

    /** Every open S2S session, both directions, for the admin console. */
    public List<Map<String, Object>> getS2SSessions() {
        SessionManager sm = XMPPServer.getInstance().getSessionManager();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String domain : sm.getOutgoingServers()) {
            for (OutgoingServerSession s : sm.getOutgoingServerSessions(domain)) {
                result.add(sessionRow(domain, "outgoing", s.getCreationDate().getTime(), s.isEncrypted()));
            }
        }
        for (String domain : sm.getIncomingServers()) {
            for (IncomingServerSession s : sm.getIncomingServerSessions(domain)) {
                result.add(sessionRow(domain, "incoming", s.getCreationDate().getTime(), s.isEncrypted()));
            }
        }
        return result;
    }

    private Map<String, Object> sessionRow(String domain, String direction, long since, boolean encrypted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain",    domain);
        m.put("direction", direction);
        m.put("since",     since);
        m.put("encrypted", encrypted);
        m.put("fedPeer",   peerRegistry.contains(domain));
        return m;
    }

    /** Closes the S2S sessions with {@code domain} in one direction ("outgoing" or "incoming"). */
    public void killSession(String domain, String direction) {
        SessionManager sm = XMPPServer.getInstance().getSessionManager();
        if ("outgoing".equals(direction)) {
            sm.getOutgoingServerSessions(domain).forEach(s -> {
                Log.info("Killing outgoing S2S session to {}", domain);
                s.close();
            });
        } else if ("incoming".equals(direction)) {
            sm.getIncomingServerSessions(domain).forEach(s -> {
                Log.info("Killing incoming S2S session from {}", domain);
                s.close();
            });
        }
    }

    /** Tags or untags a local room for federation; untagging first removes all its mappings. */
    public void setRoomFederated(String roomJid, boolean federated) {
        if (!federated && !roomManager.getAllMappingsForLocal(roomJid).isEmpty()) {
            unmapRooms(roomJid);
        }
        roomManager.setFederated(roomJid, federated);
        reAdvertiseToAllReachable();
    }

    /** Sets a room's visibility list and re-advertises at once, so newly excluded peers drop the room. */
    public void setRoomVisibility(String roomJid, Collection<String> servers) {
        roomManager.setRoomVisibility(roomJid, servers);
        reAdvertiseToAllReachable();
    }

    private void reAdvertiseToAllReachable() {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoomAdvertisement(peer.getDomain());
            }
        }
    }

    /**
     * Requests a mapping from our room to a remote room. It is stored PENDING_OUT and carries no
     * traffic until the remote accepts.
     */
    public void requestMapping(String localJid, String remoteJid, String remoteDomain) {
        roomManager.addMapping(localJid, remoteJid, remoteDomain, RoomMapping.State.PENDING_OUT, "");
        sendMappingRequest(localJid, remoteJid, remoteDomain);
    }

    private void sendMappingRequest(String localJid, String remoteJid, String remoteDomain) {
        String nextHop = nextHopToward(remoteDomain);
        boolean sent = FederationSender.send("mapping request", remoteDomain, () -> FederationStanzaFactory.roomMapping(
                nextHop, remoteDomain, localDomain(), localJid, remoteJid));
        if (sent) Log.info("Sent mapping request {} → {} ({})", localJid, remoteJid, remoteDomain);
    }

    /**
     * Re-sends our pending requests to a peer that just came up. Only the lexicographically smaller
     * domain does so, so the two ends do not race.
     */
    public void resendPendingRequests(String remoteDomain) {
        if (localDomain().compareTo(remoteDomain) >= 0) return;
        for (List<RoomMapping> list : roomManager.getLocalMappings().values()) {
            for (RoomMapping m : list) {
                if (m.remoteDomain().equals(remoteDomain) && m.state() == RoomMapping.State.PENDING_OUT) {
                    sendMappingRequest(m.localRoomJid(), m.remoteRoomJid(), remoteDomain);
                }
            }
        }
    }

    /**
     * Accepts a request on one of our rooms: mints the token, goes ACTIVE, tells the requester and
     * sends it our roster.
     */
    public void acceptMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) {
            Log.warn("acceptMapping: no mapping {} ({})", localJid, remoteDomain);
            return;
        }
        String token = StringUtils.randomString(40);
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, token);
        String nextHop = nextHopToward(remoteDomain);
        FederationSender.send("mapping accept", remoteDomain, () -> FederationStanzaFactory.roomMappingAccept(
                nextHop, remoteDomain, localDomain(), m.localRoomJid(), m.remoteRoomJid(), token));
        occupantSync.pushRosterToPeer(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Accepted mapping {} ({})", localJid, remoteDomain);
    }

    /** Re-sends our acceptance of an ACTIVE mapping with its existing token, for a remote that lost its state. */
    public void resendAccept(RoomMapping m) {
        String nextHop = nextHopToward(m.remoteDomain());
        FederationSender.send("mapping accept", m.remoteDomain(), () -> FederationStanzaFactory.roomMappingAccept(
                nextHop, m.remoteDomain(), localDomain(), m.localRoomJid(), m.remoteRoomJid(), m.token()));
        Log.info("Re-sent acceptance of active mapping {} ({})", m.localRoomJid(), m.remoteDomain());
    }

    /** Re-sends our own pending request for {@code m}. */
    public void resendMappingRequest(RoomMapping m) {
        sendMappingRequest(m.localRoomJid(), m.remoteRoomJid(), m.remoteDomain());
    }

    /** Rejects an incoming request: tells the requester and drops our pending record. */
    public void rejectMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        String nextHop = nextHopToward(remoteDomain);
        FederationSender.send("mapping reject", remoteDomain, () -> FederationStanzaFactory.roomMappingReject(
                nextHop, remoteDomain, localDomain(), m.localRoomJid(), m.remoteRoomJid(), null));
        roomManager.removeMapping(localJid, remoteDomain);
        Log.info("Rejected mapping request {} ({})", localJid, remoteDomain);
    }

    /** Disables an active mapping from our side; the peer shows it as disabled by peer. */
    public void disableMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.DISABLED_LOCAL, m.token());
        occupantSync.pushVirtualPresences(localJid, remoteDomain, m.remoteRoomJid(), true);
        occupantSync.evictForInactiveMapping(localJid, remoteDomain);
        String nextHop = nextHopToward(remoteDomain);
        FederationSender.send("mapping disable", remoteDomain, () -> FederationStanzaFactory.roomMappingDisable(
                nextHop, remoteDomain, localDomain(), m.localRoomJid(), m.remoteRoomJid(), m.token()));
        Log.info("Disabled mapping {} ({})", localJid, remoteDomain);
    }

    /** Re-enables a mapping we disabled and re-syncs the rosters. */
    public void enableMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null || m.state() != RoomMapping.State.DISABLED_LOCAL) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, m.token());
        String nextHop = nextHopToward(remoteDomain);
        FederationSender.send("mapping enable", remoteDomain, () -> FederationStanzaFactory.roomMappingEnable(
                nextHop, remoteDomain, localDomain(), m.localRoomJid(), m.remoteRoomJid(), m.token()));
        occupantSync.pushRosterToPeer(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Enabled mapping {} ({})", localJid, remoteDomain);
    }

    /**
     * Whether {@code m} exists and is in {@code expected}; each inbound lifecycle transition is legal
     * from exactly one state, so a peer cannot accept or re-enable its own request.
     */
    private boolean inState(RoomMapping m, RoomMapping.State expected, String op,
                            String localJid, String remoteDomain) {
        if (m != null && m.state() == expected) return true;
        Log.warn("SECURITY: ignoring room-mapping-{} for {} from {} — mapping is {}, not {}",
                 op, localJid, remoteDomain, m == null ? "absent" : m.state(), expected);
        return false;
    }

    /** The remote accepted our request for the room we asked for: goes ACTIVE and syncs occupants. */
    public void onMappingAccepted(String localJid, String remoteDomain, String remoteJid, String token) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (!inState(m, RoomMapping.State.PENDING_OUT, "accept", localJid, remoteDomain)) return;
        if (remoteJid != null && !remoteJid.equalsIgnoreCase(m.remoteRoomJid())) {
            Log.warn("SECURITY: ignoring room-mapping-accept for {} from {} — it names room {}, but we "
                   + "requested {}", localJid, remoteDomain, remoteJid, m.remoteRoomJid());
            return;
        }
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, token);
        occupantSync.pushInitialSyncPresences(localJid, remoteDomain, m.remoteRoomJid());
        occupantSync.forwardVirtualOccupants(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Mapping {} ({}) accepted by remote — now ACTIVE", localJid, remoteDomain);
    }

    /** The remote rejected our pending request. */
    public void onMappingRejected(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (!inState(m, RoomMapping.State.PENDING_OUT, "reject", localJid, remoteDomain)) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.REJECTED, "");
        Log.info("Mapping {} ({}) rejected by remote", localJid, remoteDomain);
    }

    /** The peer disabled an ACTIVE mapping: marks it DISABLED_REMOTE and evicts its occupants. */
    public void onMappingDisabledByPeer(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (!inState(m, RoomMapping.State.ACTIVE, "disable", localJid, remoteDomain)) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.DISABLED_REMOTE, m.token());
        occupantSync.evictForInactiveMapping(localJid, remoteDomain);
        Log.info("Mapping {} ({}) disabled by peer", localJid, remoteDomain);
    }

    /** The peer lifted its own disable: goes ACTIVE and re-syncs. */
    public void onMappingEnabledByPeer(String localJid, String remoteDomain, String remoteJid) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (!inState(m, RoomMapping.State.DISABLED_REMOTE, "enable", localJid, remoteDomain)) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, m.token());
        occupantSync.pushInitialSyncPresences(localJid, remoteDomain, m.remoteRoomJid());
        occupantSync.forwardVirtualOccupants(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Mapping {} ({}) re-enabled by peer", localJid, remoteDomain);
    }

    /** Removes every mapping of {@code localJid}, in any state, and tells each remote. */
    public void unmapRooms(String localJid) {
        List<RoomMapping> mappings = new ArrayList<>(roomManager.getAllMappingsForLocal(localJid));
        for (RoomMapping m : mappings) {
            occupantSync.pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        }
        roomManager.removeMapping(localJid);
        occupantSync.evictAllVirtualOccupantsInRoom(localJid);
        for (RoomMapping m : mappings) {
            sendRoomUnmap(localJid, m);
        }
    }

    /** Removes the mapping from {@code localJid} to {@code remoteDomain} and tells that remote. */
    public void unmapRoom(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        occupantSync.pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        roomManager.removeMapping(localJid, remoteDomain);
        occupantSync.evictForInactiveMapping(localJid, remoteDomain);
        sendRoomUnmap(localJid, m);
    }

    private void sendRoomUnmap(String localJid, RoomMapping m) {
        String nextHop = nextHopToward(m.remoteDomain());
        FederationSender.send("room-unmap", m.remoteDomain(), () -> FederationStanzaFactory.roomUnmapping(
                nextHop, m.remoteDomain(), localDomain(), localJid, m.remoteRoomJid(), m.token()));
    }

    /** The local MUC room with this JID (room@conference.domain), or null. */
    public MUCRoom findLocalMucRoom(String roomJid) {
        try {
            JID jid = new JID(roomJid);
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(jid.getDomain())) {
                    return svc.getChatRoom(jid.getNode());
                }
            }
        } catch (RuntimeException e) {
            Log.warn("Could not look up local room {}", roomJid, e);
        }
        return null;
    }

    /**
     * Cleans up after destinations become unreachable: evicts their virtual occupants and drops their
     * cached rooms. A destination still reachable another way is skipped.
     */
    public void handleUnreachableDestinations(Collection<String> destinations) {
        for (String dest : destinations) {
            if (routingTable.isReachable(dest)) continue;
            occupantSync.evictOccupantsArrivedViaLostServer(dest);
            occupantSync.evictAllVirtualOccupantsFromDomain(dest);
            occupantSync.evictOccupantsForLostHub(dest);
            roomManager.clearRemoteRooms(dest);
            // Contact lists are kept on purpose, or every link flap would empty users' contact lists.
        }
    }

    /** Sends our own federated rooms and our cached remote rooms to one peer. */
    public void sendRoomState(String toDomain) {
        sendRoomAdvertisement(toDomain);
        sendCachedRemoteRoomAdvertisements(toDomain);
    }

    /** Sends our room state to every reachable peer, so room caches follow a routing change. */
    public void propagateRoomsToAll() {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoomState(peer.getDomain());
            }
        }
    }

    /**
     * {@link #propagateRoomsToAll}, coalesced: one topology change arrives as a burst of routing
     * updates, and flooding for each would multiply traffic across the mesh.
     */
    public void propagateRoomsToAllDebounced() {
        if (!roomsFloodPending.compareAndSet(false, true)) return;
        S2SMonitor monitor = s2sMonitor;
        boolean scheduled = monitor != null && monitor.schedule(() -> {
            roomsFloodPending.set(false);
            propagateRoomsToAll();
        }, ROOMS_FLOOD_DEBOUNCE_MS);
        if (!scheduled) {
            roomsFloodPending.set(false);
            propagateRoomsToAll();
        }
    }

    /**
     * Asks one peer to re-send its routing table and room cache. Sent when a link comes up, because
     * the far end may never have seen it go down and would otherwise send only a keepalive.
     */
    public void solicitRouting(String toDomain) {
        FederationSender.send("routing-solicit", toDomain, () -> FederationStanzaFactory.routingSolicit(toDomain));
    }

    /** Asks every reachable peer but {@code excludeDomain} for its routes, so a lost destination is re-learned. */
    public void solicitRoutingFromAll(String excludeDomain) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE && !peer.getDomain().equals(excludeDomain)) {
                solicitRouting(peer.getDomain());
            }
        }
    }

    /** After a link is removed or disabled: gossips routes and rooms and solicits alternate routes. */
    public void propagateTopologyChange(String excludeDomain) {
        propagateRoutingToAll(excludeDomain);
        propagateRoomsToAll();
        solicitRoutingFromAll(excludeDomain);
    }

    /** Sends our routing table to every reachable peer but {@code excludeDomain}. */
    public void propagateRoutingToAll(String excludeDomain) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (!peer.getDomain().equals(excludeDomain) && peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoutingUpdate(peer.getDomain());
            }
        }
    }

    /**
     * Relays a room advertisement to every other reachable peer, filtered for each. An empty list is
     * still sent, because it is what tells a newly excluded peer to drop the origin's rooms.
     */
    public void relayRoomAdvertisement(String excludeDomain, String originDomain,
                                       List<FederatedRoom> rooms, String via) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            String peerDomain = peer.getDomain();
            if (peerDomain.equals(excludeDomain)) continue;
            if (FederationStanzaFactory.viaContains(via, peerDomain)) continue;
            // Even an empty list names the origin, so an untrusted peer must be exposed to it.
            if (isUntrusted(peerDomain) && !peerRegistry.getExposedServers(peerDomain).contains(originDomain)) continue;
            if (peer.getStatus() != PeerServer.Status.REACHABLE) continue;
            List<FederatedRoom> toSend = filterRoomsForHop(peerDomain, rooms);
            FederationSender.send("room-advertisement", peerDomain, () -> FederationStanzaFactory.roomAdvertisement(
                    peerDomain, toSend, originDomain, via));
        }
    }

    /**
     * Egress gate for the 1:1 forwards: a stanza may go to an untrusted peer only when its sender is
     * on a server exposed to that peer, or a subdomain of one.
     */
    public boolean egressExposureOk(String nextHop, JID from, String action) {
        if (!isUntrusted(nextHop)) return true;
        String domain = from == null ? null : from.getDomain();
        if (domain != null) {
            for (String srv : peerRegistry.getExposedServers(nextHop)) {
                if (domain.equals(srv) || domain.endsWith("." + srv)) return true;
            }
        }
        Log.warn("SECURITY: dropping {} from {} toward untrusted peer {} — the sender's server is not "
               + "exposed to it", action, from, nextHop);
        return false;
    }

    /**
     * Relays an outbound 1:1 message over the overlay. True when it was sent or deliberately dropped,
     * so the caller suppresses native S2S; false when there is no route or the send failed.
     */
    public boolean forwardDirectMessage(Message msg) {
        return forwardDirectMessage(msg, msg.getTo().getDomain());
    }

    /** As {@link #forwardDirectMessage(Message)}, but routed toward the server {@code destDomain}. */
    public boolean forwardDirectMessage(Message msg, String destDomain) {
        return forwardDirect("direct-forward", msg, destDomain);
    }

    /** Relays an outbound 1:1 presence over the overlay; same contract as {@link #forwardDirectMessage(Message)}. */
    public boolean forwardDirectPresence(Presence pres) {
        return forwardDirectPresence(pres, pres.getTo().getDomain());
    }

    /** As {@link #forwardDirectPresence(Presence)}, but routed toward the server {@code destDomain}. */
    public boolean forwardDirectPresence(Presence pres, String destDomain) {
        return forwardDirect("presence-forward", pres, destDomain);
    }

    /** Relays a user-addressed IQ over the overlay; same contract as {@link #forwardDirectMessage(Message)}. */
    public boolean forwardDirectIq(IQ iq) {
        return forwardDirectIq(iq, iq.getTo().getDomain());
    }

    /** As {@link #forwardDirectIq(IQ)}, but routed toward the server {@code destDomain}. */
    public boolean forwardDirectIq(IQ iq, String destDomain) {
        return forwardDirect("iq-forward", iq, destDomain);
    }

    /** Relays a copy of a 1:1 stanza in the {@code action} envelope toward {@code destDomain}. */
    private boolean forwardDirect(String action, Packet stanza, String destDomain) {
        String nextHop = routingTable.findNextHop(destDomain).orElse(null);
        if (nextHop == null) return false;
        if (!egressExposureOk(nextHop, stanza.getFrom(), action)) return true;
        boolean sent = FederationSender.send(action, destDomain, () -> FederationStanzaFactory.oneToOneForward(
                action, nextHop, destDomain, localDomain(), stanza));
        // Guarded: presence relay is the hottest path, and the avatar hash would be computed even at INFO.
        if (sent && Log.isDebugEnabled()) {
            String absentType = stanza instanceof Presence ? "available" : "normal";
            String type = stanza.getElement().attributeValue("type", absentType);
            Log.debug("{}: {} {} -> {} via {} (dest {}){}", action, type, stanza.getFrom(), stanza.getTo(), nextHop,
                      destDomain, stanza instanceof Presence p ? " avatarHash=" + avatarHashOf(p) : "");
        }
        return sent;
    }

    /** For logging: the XEP-0153 photo hash a presence carries, or which part of it is missing. */
    public static String avatarHashOf(Presence pres) {
        for (Element xEl : pres.getElement().elements("x")) {
            if (!"vcard-temp:x:update".equals(xEl.getNamespaceURI())) continue;
            Element photo = xEl.element("photo");
            if (photo == null) return "x-present,no-photo-el";
            String hash = photo.getTextTrim();
            return hash.isEmpty() ? "x-present,empty-photo" : hash;
        }
        return "no-x-update-element";
    }

    private boolean isUntrusted(String domain) {
        return peerRegistry.isUntrusted(domain);
    }

    /** Keeps only rooms homed on a server exposed to an untrusted peer; trusted peers get the list unchanged. */
    private List<FederatedRoom> filterRoomsForPeer(String domain, List<FederatedRoom> rooms) {
        if (!isUntrusted(domain)) return rooms;
        Set<String> exposed = peerRegistry.getExposedServers(domain);
        if (exposed.isEmpty()) return Collections.emptyList();
        return rooms.stream().filter(r -> exposed.contains(r.originServer())).collect(Collectors.toList());
    }

    /**
     * Whether a room with this visibility list may go to {@code peerDomain}: never when empty, always
     * with {@code "*"}, else when the list names the peer or a server reached through it.
     */
    private boolean roomVisibleAtHop(Set<String> visibleTo, String peerDomain) {
        if (visibleTo == null || visibleTo.isEmpty()) return false;
        if (visibleTo.contains(FederatedRoomManager.VISIBLE_ALL)) return true;
        if (visibleTo.contains(peerDomain)) return true;
        for (String member : visibleTo) {
            if (routingTable.findNextHop(member).map(h -> h.equals(peerDomain)).orElse(false)) return true;
        }
        return false;
    }

    /** The rooms that may be sent to one peer: the untrusted-peer filter plus each room's visibility list. */
    private List<FederatedRoom> filterRoomsForHop(String toDomain, List<FederatedRoom> rooms) {
        return filterRoomsForPeer(toDomain, rooms).stream()
                .filter(r -> roomVisibleAtHop(r.visibleTo(), toDomain))
                .collect(Collectors.toList());
    }

    /**
     * Whether our room is federated and shared with {@code destDomain}. A server may map a peer's
     * room only after exposing its own room to that peer.
     */
    public boolean roomSharedWith(String localJid, String destDomain) {
        if (destDomain == null || !roomManager.isFederated(localJid)) return false;
        Set<String> vis = roomManager.getRoomVisibility(localJid);
        if (vis == null || vis.isEmpty()) return false;
        return vis.contains(FederatedRoomManager.VISIBLE_ALL)
            || vis.contains(destDomain.toLowerCase());
    }

    /**
     * Applies the most specific matching room-default rule to a room, typically one just created. A
     * rule with {@code federated=false} only excludes names from a broader rule, so it does nothing here.
     */
    public void applyDefaultsTo(String roomJid) {
        if (roomJid == null) return;
        int at = roomJid.indexOf('@');
        if (at <= 0 || at == roomJid.length() - 1) return;
        String node    = roomJid.substring(0, at);
        String service = roomJid.substring(at + 1);

        RoomDefaultsManager.Rule rule = roomDefaults.bestMatch(node);
        if (rule == null || !rule.federated()) return;

        roomManager.setFederated(roomJid, true);
        List<String> vis = rule.visible();
        boolean visibleToAll = vis.contains(RoomDefaultsManager.VISIBLE_ALL);
        roomManager.setRoomVisibility(roomJid, visibleToAll ? List.of(FederatedRoomManager.VISIBLE_ALL) : vis);
        roomManager.setAutoAccept(roomJid, rule.autoAccept());
        roomManager.setFilesEnabled(roomJid, rule.filesEnabled());

        Log.info("Applied room-default rule '{}' to {} (federated, visible={}, autoAccept={}, autoMap={}, files={})",
                 rule.pattern(), roomJid, visibleToAll ? "all peers" : vis,
                 rule.autoAccept(), rule.autoMap(), rule.filesEnabled());

        if (rule.autoMap()) autoMapRoom(roomJid, node, service, vis);
    }

    /** Re-applies the room-default rules to every existing local room; returns how many matched. */
    public int applyDefaultsToAllRooms() {
        int applied = 0;
        for (Map<String, Object> room : roomManager.getAllLocalRoomsWithTag()) {
            String jid = (String) room.get("jid");
            if (jid == null) continue;
            int at = jid.indexOf('@');
            if (at <= 0) continue;
            if (roomDefaults.bestMatch(jid.substring(0, at)) != null) {
                applyDefaultsTo(jid);
                applied++;
            }
        }
        Log.info("Applied room-default rules to {} existing room(s)", applied);
        return applied;
    }

    /**
     * Requests a same-named mapping on each target peer: the room's visibility list, or every routable
     * server for {@code "*"}. Peers the room is not shared with, or already mapped to, are skipped.
     */
    private void autoMapRoom(String localJid, String node, String localService, List<String> visible) {
        Set<String> targets = new LinkedHashSet<>(
                visible.contains(RoomDefaultsManager.VISIBLE_ALL) ? routableServers() : visible);
        String localDomain = localDomain();
        String mucSub = localService.endsWith("." + localDomain)
                ? localService.substring(0, localService.length() - localDomain.length() - 1)
                : "conference";

        for (String dest : targets) {
            if (dest == null || dest.isBlank() || dest.equalsIgnoreCase(localDomain)) continue;
            if (!roomSharedWith(localJid, dest)) continue;
            if (roomManager.getMappingForLocal(localJid, dest) != null) continue;
            requestMapping(localJid, node + "@" + mucSub + "." + dest, dest);
        }
    }

    /** Every routing-table destination; the candidate servers for a room's visibility list. */
    public Set<String> routableServers() {
        Set<String> out = new LinkedHashSet<>();
        for (RouteEntry e : routingTable.getAll()) out.add(e.destination());
        return out;
    }

    /**
     * The servers the admin may expose to an untrusted peer: this server plus every destination
     * reached without going through that peer.
     */
    public Set<String> exposableServers(String peerDomain) {
        Set<String> out = new LinkedHashSet<>();
        out.add(localDomain());
        for (RouteEntry e : routingTable.getAll()) {
            String dest = e.destination();
            if (dest.equals(peerDomain) || peerDomain.equals(e.nextHop())) continue;
            out.add(dest);
        }
        return out;
    }

    /**
     * Refuses {@code destination}'s advertisements from {@code peerDomain} and tears down any route to
     * it through that peer; a route through another peer stays.
     */
    public void denyRouteFromPeer(String peerDomain, String destination) {
        peerRegistry.setRouteDenied(peerDomain, destination, true);
        boolean removed = routingTable.removeRouteVia(destination, peerDomain);
        if (removed) {
            handleUnreachableDestinations(Set.of(destination));
            propagateTopologyChange(peerDomain);
        } else if (!routingTable.isReachable(destination)) {
            handleUnreachableDestinations(Set.of(destination));
        }
        Log.info("Denied route advertisements for {} from peer {}{}", destination, peerDomain,
                 removed ? " (installed route removed)" : "");
    }

    /** Lifts a route deny and asks the peer to re-send its routes, so the destination is re-learned at once. */
    public void allowRouteFromPeer(String peerDomain, String destination) {
        peerRegistry.setRouteDenied(peerDomain, destination, false);
        PeerServer.Status st = peerRegistry.getPeer(peerDomain).map(PeerServer::getStatus).orElse(null);
        if (st == PeerServer.Status.REACHABLE) {
            solicitRouting(peerDomain);
        }
        Log.info("Allowed route advertisements for {} from peer {}", destination, peerDomain);
    }

    /** How many trailing DNS labels make up the parent domain used to spot foreign peers. */
    private int trustDomainLabels() {
        return Math.max(1, JiveGlobals.getIntProperty("plugin.federation.trustDomainLabels", 2));
    }

    /** The last {@link #trustDomainLabels()} labels of {@code domain}, lowercased. */
    private String parentDomain(String domain) {
        if (domain == null) return "";
        String[] labels = domain.strip().toLowerCase().split("\\.");
        int n = Math.min(trustDomainLabels(), labels.length);
        return String.join(".", Arrays.copyOfRange(labels, labels.length - n, labels.length));
    }

    /** Whether {@code peerDomain} sits under a different parent domain than this server. */
    public boolean isForeignDomain(String peerDomain) {
        return !parentDomain(peerDomain).equals(parentDomain(localDomain()));
    }

    /**
     * Blocks a peer whose trust stance differs from ours: drops its mappings and routes and marks
     * TRUST_MISMATCH. Announces our stance only on the way into the mismatch, so the two ends do not ping-pong.
     */
    public void blockForTrustMismatch(String domain) {
        boolean wasMismatch = peerRegistry.getPeer(domain)
                .map(p -> p.getStatus() == PeerServer.Status.TRUST_MISMATCH).orElse(false);
        for (String localJid : new ArrayList<>(roomManager.getLocalMappings().keySet())) {
            if (roomManager.getMappingForLocal(localJid, domain) != null) {
                roomManager.removeMapping(localJid, domain);
            }
        }
        Set<String> removed = routingTable.removePeer(domain);
        handleUnreachableDestinations(removed.isEmpty() ? Set.of(domain) : removed);
        peerRegistry.setTrustMismatch(domain);
        if (!removed.isEmpty()) propagateTopologyChange(domain);
        if (!wasMismatch) {
            Log.warn("SECURITY: trust mismatch with {} — link blocked until both ends agree", domain);
            sendPeerAnnounce(domain);
        }
    }

    /**
     * Announces a trust change made by our admin, then blocks or unblocks the link at once by
     * comparing it with the remote's last known stance.
     */
    public void applyLocalTrustChange(String domain) {
        sendPeerAnnounce(domain);
        peerRegistry.getPeer(domain).ifPresent(p -> {
            Boolean remote = p.getRemoteUntrusted();
            if (remote == null) return;
            if (p.isUntrusted() != remote) {
                blockForTrustMismatch(domain);
            } else if (p.getStatus() == PeerServer.Status.TRUST_MISMATCH) {
                peerRegistry.clearTrustMismatch(domain);
            } else if (p.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoutingUpdate(domain);
                sendRoomState(domain);
            }
        });
    }

    /** Sends a peer everything it needs on link-up: announce, routes, our rooms and cached rooms. */
    public void sendFullGossip(String toDomain) {
        Log.debug("Sending full gossip to {}", toDomain);
        sendPeerAnnounce(toDomain);
        sendRoutingUpdate(toDomain);
        sendRoomAdvertisement(toDomain);
        sendCachedRemoteRoomAdvertisements(toDomain);
    }

    /** Replays every cached remote room advertisement to a peer, so a late joiner learns earlier rooms. */
    public void sendCachedRemoteRoomAdvertisements(String toDomain) {
        String localDomain = localDomain();
        for (Map.Entry<String, List<FederatedRoom>> entry : roomManager.getRemoteRooms().entrySet()) {
            String originDomain = entry.getKey();
            if (originDomain.equals(toDomain)) continue;
            List<FederatedRoom> rooms = filterRoomsForHop(toDomain, entry.getValue());
            if (rooms.isEmpty()) continue;
            FederationSender.send("cached room-advertisement of " + originDomain, toDomain,
                    () -> FederationStanzaFactory.roomAdvertisement(toDomain, rooms, originDomain, localDomain));
        }
    }

    public void sendPeerAnnounce(String toDomain) {
        FederationSender.send("peer-announce", toDomain,
                () -> FederationStanzaFactory.peerAnnounce(toDomain, false, isUntrusted(toDomain)));
    }

    public void sendPeerDisable(String toDomain) {
        FederationSender.send("peer-disable", toDomain, () -> FederationStanzaFactory.peerDisable(toDomain));
    }

    /** Sends a keepalive reply, which warms the reverse S2S socket and is never answered. */
    public void sendPeerAnnounceReply(String toDomain) {
        FederationSender.send("peer-announce reply", toDomain,
                () -> FederationStanzaFactory.peerAnnounce(toDomain, true, isUntrusted(toDomain)));
    }

    /**
     * Sends our routing table to a peer with split horizon, flagging routes that cross an untrusted
     * peer. An untrusted peer only learns routes to the servers exposed to it.
     */
    public void sendRoutingUpdate(String toDomain) {
        FederationSender.send("routing-update", toDomain, () -> {
            Collection<RouteEntry> table = routingTable.getRoutesExcludingNextHop(toDomain).stream()
                    .map(e -> new RouteEntry(e.destination(), e.nextHop(), e.hops(), e.updatedAt(),
                                             FederationRoutingTable.isEdge(e, this::isUntrusted)))
                    .collect(Collectors.toList());
            if (isUntrusted(toDomain)) {
                Set<String> allowed = peerRegistry.getExposedServers(toDomain);
                table = table.stream().filter(e -> allowed.contains(e.destination())).collect(Collectors.toList());
            }
            return FederationStanzaFactory.routingUpdate(toDomain, table);
        });
    }

    /** Advertises our federated rooms to a peer, filtered by exposure and visibility. */
    public void sendRoomAdvertisement(String toDomain) {
        List<FederatedRoom> rooms = filterRoomsForHop(toDomain, roomManager.getLocalFederatedRoomsWithDetails());
        FederationSender.send("room-advertisement", toDomain,
                () -> FederationStanzaFactory.roomAdvertisement(toDomain, rooms));
    }

    public int  getKeepaliveSeconds()            { return s2sMonitor.getKeepaliveSeconds(); }
    public int  getEffectiveKeepaliveSeconds()   { return s2sMonitor.getEffectiveKeepaliveSeconds(); }
    public void setKeepaliveSeconds(int sec)     { s2sMonitor.setKeepaliveSeconds(sec); }
    public int  getReconnectSeconds()            { return s2sMonitor.getReconnectSeconds(); }
    public void setReconnectSeconds(int sec)     { s2sMonitor.setReconnectSeconds(sec); }
    public int  getMappingPingSeconds()          { return s2sMonitor.getMappingPingSeconds(); }
    public void setMappingPingSeconds(int sec)   { s2sMonitor.setMappingPingSeconds(sec); }
    public long getNextRetryAt(String domain)    { return s2sMonitor.getNextRetryAt(domain); }

    public PeerRegistry           getPeerRegistry()       { return peerRegistry; }
    public FederationRoutingTable getRoutingTable()       { return routingTable; }
    public FederatedRoomManager   getRoomManager()        { return roomManager; }
    public RoomDefaultsManager    getRoomDefaults()       { return roomDefaults; }
    public ContactListManager     getContactLists()       { return contactLists; }
    public FederationFileConfig   getFileConfig()         { return fileConfig; }
    public FileRelayManager       getFileRelay()          { return fileRelay; }
    public RoomMessageIds         getRoomMessageIds()     { return roomMessageIds; }
    public RedundantArrivals      getRedundantArrivals()  { return redundantArrivals; }
    public PresenceRelay          getPresenceRelay()      { return presenceRelay; }
    public OccupantSync           getOccupantSync()       { return occupantSync; }
    public MappingProbe           getMappingProbe()       { return mappingProbe; }
    public CertificatePinning     getCertificatePinning() { return certificatePinning; }

    /** The room traffic handler, or null before the plugin has started. */
    public MucTrafficHandler getMucTraffic() {
        return iqHandler == null ? null : iqHandler.getMucTraffic();
    }

    /** The 1:1 traffic handler, or null before the plugin has started. */
    public DirectTrafficHandler getDirectTraffic() {
        return iqHandler == null ? null : iqHandler.getDirectTraffic();
    }
}
