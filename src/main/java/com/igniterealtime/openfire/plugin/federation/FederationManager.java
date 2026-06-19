package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import org.dom4j.Element;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationIQHandler;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationPacketInterceptor;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.OutgoingServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central coordinator for the federation plugin.
 *
 * Lifecycle:
 *   FederationPlugin.initializePlugin() → FederationManager.start()
 *   FederationPlugin.destroyPlugin()    → FederationManager.stop()
 *
 * All subsystems are accessible through this class so JSP pages only need a
 * single reference: FederationPlugin.getInstance().getManager()
 */
public class FederationManager {

    private static final Logger Log = LoggerFactory.getLogger(FederationManager.class);

    private final PeerRegistry           peerRegistry    = new PeerRegistry();
    private final FederationRoutingTable routingTable    = new FederationRoutingTable();
    private final FederatedRoomManager   roomManager     = new FederatedRoomManager();
    private       S2SMonitor             s2sMonitor;
    private       FederationIQHandler    iqHandler;
    private       FederationPacketInterceptor interceptor;

    public void start() {
        Log.info("Federation plugin starting…");

        peerRegistry.load();
        roomManager.load();

        iqHandler   = new FederationIQHandler(this);
        interceptor = new FederationPacketInterceptor(this);

        XMPPServer.getInstance().getIQRouter().addHandler(iqHandler);
        InterceptorManager.getInstance().addInterceptor(interceptor);

        s2sMonitor = new S2SMonitor(peerRegistry, routingTable, roomManager, this);
        s2sMonitor.start();

        Log.info("Federation plugin started — {} peer(s) configured", peerRegistry.getPeers().size());
    }

    public void stop() {
        Log.info("Federation plugin stopping…");

        if (s2sMonitor != null) s2sMonitor.stop();

        if (iqHandler != null)
            XMPPServer.getInstance().getIQRouter().removeHandler(iqHandler);

        if (interceptor != null)
            InterceptorManager.getInstance().removeInterceptor(interceptor);

        Log.info("Federation plugin stopped.");
    }

    // ── Peer management (called from admin UI / api.jsp) ───────────────────────

    public PeerServer addPeer(String domain) {
        PeerServer peer = peerRegistry.addPeer(domain);
        Log.info("Peer added: {}", domain);
        sendPeerAnnounce(domain);
        return peer;
    }

    public void retryPeer(String domain) {
        PeerServer.Status cur = peerRegistry.getPeer(domain).map(PeerServer::getStatus).orElse(null);
        if (cur == PeerServer.Status.DISABLED || cur == PeerServer.Status.REMOTE_DISABLED) {
            Log.info("retryPeer ignored for {} — status {} (use enable; a REMOTE_DISABLED link can only be lifted by the remote)",
                     domain, cur);
            return;
        }
        Log.info("Retrying S2S for peer: {}", domain);
        // Clear WITHDRAWN so S2SMonitor will track this peer again.
        peerRegistry.getPeer(domain).ifPresent(p -> {
            if (p.getStatus() == PeerServer.Status.WITHDRAWN) {
                peerRegistry.updateStatus(domain, PeerServer.Status.UNKNOWN);
            }
        });
        sendPeerAnnounce(domain);
    }

    /**
     * Administratively disables a peer: tears down federation exactly like removePeer
     * but keeps the peer registered with a persistent DISABLED status, tells the remote
     * (peer-disable), and keeps refusing it — re-asserting the disable if it re-creates
     * the connection.  Re-enable locally with {@link #enablePeer}.
     */
    public void disablePeer(String domain) {
        for (String localJid : new ArrayList<>(roomManager.getLocalMappings().keySet())) {
            if (roomManager.getMappingForLocal(localJid, domain) != null) {
                unmapRoom(localJid, domain);
            }
        }
        peerRegistry.getPeer(domain).ifPresent(peer -> {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendPeerDisable(domain);
            }
        });
        killSession(domain, "outgoing");
        killSession(domain, "incoming");
        Set<String> removedRoutes = routingTable.removePeer(domain);
        handleUnreachableDestinations(removedRoutes.isEmpty() ? Set.of(domain) : removedRoutes);
        if (!removedRoutes.isEmpty()) {
            propagateRoutingToAll(domain);
        }
        if (!peerRegistry.contains(domain)) peerRegistry.addPeer(domain);
        peerRegistry.setControlStatus(domain, PeerServer.Status.DISABLED);
        Log.info("Peer disabled: {}", domain);
    }

    /**
     * Lifts a local DISABLE: clears the status and reaches out so the remote (which is
     * holding REMOTE_DISABLED) sees our announce and resumes federation.  No-op unless
     * the peer is currently DISABLED (a REMOTE_DISABLED link can only be lifted remotely).
     */
    public void enablePeer(String domain) {
        PeerServer.Status cur = peerRegistry.getPeer(domain).map(PeerServer::getStatus).orElse(null);
        if (cur != PeerServer.Status.DISABLED) {
            Log.info("enablePeer ignored for {} — status {} (only a locally DISABLED peer can be enabled here)", domain, cur);
            return;
        }
        peerRegistry.setControlStatus(domain, PeerServer.Status.UNKNOWN);
        sendPeerAnnounce(domain);
        Log.info("Peer enabled: {}", domain);
    }

    public List<Map<String, Object>> getS2SSessions() {
        SessionManager sm = XMPPServer.getInstance().getSessionManager();
        List<Map<String, Object>> result = new ArrayList<>();

        for (String domain : sm.getOutgoingServers()) {
            for (OutgoingServerSession s : sm.getOutgoingServerSessions(domain)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain",    domain);
                m.put("direction", "outgoing");
                m.put("since",     s.getCreationDate().getTime());
                m.put("encrypted", s.isEncrypted());
                m.put("fedPeer",   peerRegistry.contains(domain));
                result.add(m);
            }
        }

        for (String domain : sm.getIncomingServers()) {
            for (IncomingServerSession s : sm.getIncomingServerSessions(domain)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain",    domain);
                m.put("direction", "incoming");
                m.put("since",     s.getCreationDate().getTime());
                m.put("encrypted", s.isEncrypted());
                m.put("fedPeer",   peerRegistry.contains(domain));
                result.add(m);
            }
        }

        return result;
    }

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

    public boolean removePeer(String domain) {
        // Unmap every local room that has a mapping to this domain BEFORE killing
        // the session.  This delivers room-unmap IQs and virtual leave presences
        // while S2S is still open, AND clears the local mapping so the interceptor
        // won't try to re-open S2S after the session is gone.
        for (String localJid : new ArrayList<>(roomManager.getLocalMappings().keySet())) {
            if (roomManager.getMappingForLocal(localJid, domain) != null) {
                unmapRoom(localJid, domain);
            }
        }

        peerRegistry.getPeer(domain).ifPresent(peer -> {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.peerWithdraw(domain));
                } catch (Exception e) {
                    Log.warn("Failed to send peer-withdraw to {}: {}", domain, e.getMessage());
                }
            }
        });
        // Kill S2S sessions so the remote side detects the disconnect immediately.
        killSession(domain, "outgoing");
        killSession(domain, "incoming");
        boolean removed = peerRegistry.removePeer(domain);
        if (removed) {
            Set<String> removedRoutes = routingTable.removePeer(domain);
            // Drop cached rooms and evict ghost occupants for this peer AND every
            // destination that was only reachable through it.
            handleUnreachableDestinations(removedRoutes.isEmpty() ? Set.of(domain) : removedRoutes);
            if (!removedRoutes.isEmpty()) {
                propagateRoutingToAll(domain);
            }
        }
        return removed;
    }

    // ── Room federation (called from admin UI / api.jsp) ──────────────────────

    public void setRoomFederated(String roomJid, boolean federated) {
        if (!federated) {
            // Tear down all active mappings before clearing the federation tag.
            if (!roomManager.getMappingsForLocal(roomJid).isEmpty()) {
                unmapRooms(roomJid);
            }
        }
        roomManager.setFederated(roomJid, federated);
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoomAdvertisement(peer.getDomain());
            }
        }
    }

    // ── Room mapping ──────────────────────────────────────────────────────────

    /**
     * Adds one spoke mapping (localJid ↔ remoteJid on remoteDomain) and notifies
     * the remote server.  Can be called multiple times on the hub to connect
     * additional spokes to the same local room.
     */
    public void mapRooms(String localJid, String remoteJid, String remoteDomain) {
        roomManager.addMapping(localJid, remoteJid, remoteDomain);
        // Send the room-mapping IQ first so the remote side stores the mapping before
        // it receives our occupant-sync presences — this ensures syncLocalOccupantsToRemote
        // can fire correctly on the remote when it processes our muc-forward stanzas.
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomMapping(
                          nextHop, remoteDomain, localDomain, localJid, remoteJid));
        } catch (Exception e) {
            Log.warn("Failed to send room-mapping to {}: {}", remoteDomain, e.getMessage());
        }
        pushInitialSyncPresences(localJid, remoteDomain, remoteJid);
    }

    /** Removes ALL spoke mappings for localJid and notifies every remote. */
    public void unmapRooms(String localJid) {
        List<RoomMapping> mappings = new ArrayList<>(roomManager.getMappingsForLocal(localJid));
        for (RoomMapping m : mappings) {
            evictVirtualOccupants(localJid, m.remoteDomain());
            pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        }
        roomManager.removeMapping(localJid);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (RoomMapping m : mappings) {
            String nextHop = routingTable.findNextHop(m.remoteDomain()).orElse(m.remoteDomain());
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.roomUnmapping(
                              nextHop, m.remoteDomain(), localDomain, localJid, m.remoteRoomJid()));
            } catch (Exception e) {
                Log.warn("Failed to send room-unmap to {}: {}", m.remoteDomain(), e.getMessage());
            }
        }
    }

    /** Removes only the mapping from localJid toward remoteDomain and notifies that remote. */
    public void unmapRoom(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        evictVirtualOccupants(localJid, remoteDomain);
        pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        roomManager.removeMapping(localJid, remoteDomain);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(m.remoteDomain()).orElse(m.remoteDomain());
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomUnmapping(
                          nextHop, m.remoteDomain(), localDomain, localJid, m.remoteRoomJid()));
        } catch (Exception e) {
            Log.warn("Failed to send room-unmap to {}: {}", m.remoteDomain(), e.getMessage());
        }
    }

    /**
     * Evicts all virtual occupants from remoteDomain across every local room.
     * Used on unexpected peer disconnect so clients see leave presences rather
     * than ghost users that never departed.
     */
    public void evictAllVirtualOccupantsFromDomain(String remoteDomain) {
        for (String localJid : roomManager.getLocalRoomsWithVirtualOccupantsFrom(remoteDomain)) {
            evictVirtualOccupants(localJid, remoteDomain);
        }
    }

    /**
     * Delivers leave presences to every current local occupant of localRoomJid for
     * each virtual nick that was injected from remoteDomain.  Clears the tracking
     * so eviction is idempotent.  Called before removing a mapping so clients
     * immediately see the departing virtual users.
     */
    public void evictVirtualOccupants(String localRoomJid, String remoteDomain) {
        Set<String> nicks = roomManager.clearVirtualOccupants(localRoomJid, remoteDomain);
        if (nicks.isEmpty()) return;
        try {
            JID localRoomJID = new JID(localRoomJid);
            MUCRoom room = findLocalMucRoom(localRoomJid);
            if (room == null) return;
            Collection<MUCOccupant> occupants = room.getOccupants();
            if (occupants.isEmpty()) return;
            for (String nick : nicks) {
                JID virtualFrom = new JID(localRoomJID.getNode(), localRoomJID.getDomain(), nick);
                for (MUCOccupant occupant : occupants) {
                    Presence leave = new Presence();
                    leave.setFrom(virtualFrom);
                    leave.setTo(occupant.getUserAddress());
                    leave.setType(Presence.Type.unavailable);
                    Element x    = leave.getElement().addElement("x", "http://jabber.org/protocol/muc#user");
                    Element item = x.addElement("item");
                    item.addAttribute("affiliation", "none");
                    item.addAttribute("role", "none");
                    FederationStanzaFactory.markAsForwarded(leave);
                    FederationStanzaFactory.directDeliver(leave);
                }
            }
            Log.debug("evictVirtualOccupants: sent {} leave(s) from {} in {}",
                      nicks.size(), remoteDomain, localRoomJid);
        } catch (Exception e) {
            Log.warn("evictVirtualOccupants: error for {}/{}: {}", localRoomJid, remoteDomain, e.getMessage());
        }
    }

    /** Finds a local MUCRoom by full JID string (room@conference.domain), or null. */
    public MUCRoom findLocalMucRoom(String roomJid) {
        try {
            JID jid = new JID(roomJid);
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(jid.getDomain())) {
                    return svc.getChatRoom(jid.getNode());
                }
            }
        } catch (Exception e) {
            Log.warn("findLocalMucRoom: could not find room {}: {}", roomJid, e.getMessage());
        }
        return null;
    }

    /**
     * Sends a synthetic join or leave presence for every current occupant of
     * localRoom toward remoteRoomJid on remoteDomain.  Single code path behind
     * pushVirtualPresences and pushInitialSyncPresences.
     *
     * @param markForwarded when true, presences carry fed-origin so the receiver
     *        injects them without bouncing a reverse sync (in-session updates and
     *        leaves).  When false (initial mapping sync), the receiver's
     *        injectPresence runs syncLocalOccupantsToRemote and replies with its
     *        own occupants — a bilateral exchange.
     */
    private void pushOccupants(String localRoom, String remoteDomain, String remoteRoomJid,
                               boolean leaving, boolean markForwarded) {
        try {
            MUCRoom room = findLocalMucRoom(localRoom);
            if (room == null) return;

            Collection<MUCOccupant> occupants = room.getOccupants();
            if (occupants.isEmpty()) return;

            String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
            String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);

            for (MUCOccupant occupant : occupants) {
                Presence p = new Presence();
                p.setFrom(occupant.getUserAddress());
                if (leaving) {
                    p.setType(Presence.Type.unavailable);
                } else {
                    // Carry current show/status so the remote sees accurate availability.
                    Element curEl = occupant.getPresence().getElement();
                    Element showEl = curEl.element("show");
                    if (showEl != null) p.getElement().addElement("show").setText(showEl.getText());
                    for (Element statusEl : curEl.elements("status")) {
                        p.getElement().addElement("status").setText(statusEl.getText());
                    }
                }
                if (markForwarded) FederationStanzaFactory.markAsForwarded(p);
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mucForward(
                            nextHop, remoteDomain, remoteRoomJid, localDomain, p));
                } catch (Exception ex) {
                    Log.warn("pushOccupants: failed to push {} for {}: {}",
                             leaving ? "leave" : "join", occupant.getUserAddress(), ex.getMessage());
                }
            }
            Log.debug("pushOccupants: sent {} {} for {} occupant(s) toward {} (fedOrigin={})",
                      leaving ? "leave" : "join", localRoom, occupants.size(), remoteRoomJid, markForwarded);
        } catch (Exception e) {
            Log.warn("pushOccupants: error for {}: {}", localRoom, e.getMessage());
        }
    }

    /**
     * Sends synthetic join/leave presences for current occupants of localRoom toward
     * a spoke, marked fed-origin so the receiver does not bounce a reverse sync.
     */
    public void pushVirtualPresences(String localRoom, String remoteDomain,
                                     String remoteRoomJid, boolean leaving) {
        pushOccupants(localRoom, remoteDomain, remoteRoomJid, leaving, true);
    }

    /**
     * Sends current local occupants' join presences to the remote WITHOUT fed-origin
     * so the remote's injectPresence triggers syncLocalOccupantsToRemote and replies
     * with its own occupants.  Used only at mapping-creation time.
     */
    public void pushInitialSyncPresences(String localRoom, String remoteDomain,
                                         String remoteRoomJid) {
        pushOccupants(localRoom, remoteDomain, remoteRoomJid, false, false);
    }

    /**
     * Forwards the virtual occupants we know about in localRoom — users that reached
     * us from OTHER federated domains — toward toDomain, marked fed-origin.  Excludes:
     * (a) virtuals that arrived FROM toDomain (don't echo them back the way they came),
     * and (b) users whose HOME server is toDomain.  Exclusion (b) is keyed on the nick's
     * domain rather than the domain it was tracked under, because on a multi-hop path a
     * virtual is tracked under an intermediate neighbour — without this a user sees a
     * ghost copy of themself when their own presence loops back home.
     */
    public void forwardVirtualOccupants(String localRoom, String toDomain, String remoteRoomJid) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(toDomain).orElse(toDomain);
        Map<String, Set<String>> virtuals = roomManager.getVirtualOccupantsByDomain(localRoom);
        for (Map.Entry<String, Set<String>> ve : virtuals.entrySet()) {
            if (ve.getKey().equals(toDomain)) continue;          // arrived from toDomain
            for (String nick : ve.getValue()) {
                String nickHome;
                try { nickHome = new JID(nick).getDomain(); }
                catch (Exception e) { nickHome = ve.getKey(); }
                if (toDomain.equals(nickHome)) continue;          // toDomain's own user — don't echo
                try {
                    Presence vsync = new Presence();
                    vsync.setFrom(new JID(nick));
                    FederationStanzaFactory.markAsForwarded(vsync);
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mucForward(nextHop, toDomain, remoteRoomJid, localDomain, vsync));
                } catch (Exception e) {
                    Log.warn("forwardVirtualOccupants: failed for {}: {}", nick, e.getMessage());
                }
            }
        }
    }

    /**
     * Pushes our full roster of localRoom — real local occupants AND virtual occupants
     * reached through us — toward a peer that just mapped this room, all marked
     * fed-origin.  Called from the room-mapping handler so the mapping peer's clients
     * see everyone immediately, even when it has no occupants of its own to trigger the
     * reverse sync.
     */
    public void pushRosterToPeer(String localRoom, String toDomain, String remoteRoomJid) {
        pushOccupants(localRoom, toDomain, remoteRoomJid, false, true);
        forwardVirtualOccupants(localRoom, toDomain, remoteRoomJid);
    }

    // ── Routing propagation ────────────────────────────────────────────────────

    /**
     * Cleans up all cached state for destinations that just became unreachable:
     * evicts their virtual occupants (so local clients see leave presences) and
     * drops their advertised rooms.  Driven by routing-table withdrawals so the
     * cleanup converges across every hop as the withdrawal gossips outward — not
     * just on the directly-disconnected peer.  A destination still reachable by an
     * alternate path is skipped, so diamond topologies don't lose live rooms.
     */
    public void handleUnreachableDestinations(Collection<String> destinations) {
        for (String dest : destinations) {
            if (routingTable.isReachable(dest)) continue;   // still reachable another way
            evictAllVirtualOccupantsFromDomain(dest);
            roomManager.clearRemoteRooms(dest);
        }
    }

    /**
     * Re-pushes our local occupants toward each mapped remote that just became
     * reachable, so occupant rosters self-heal after a link comes up or a route
     * changes — without waiting for a user to leave and rejoin.  Sent WITHOUT
     * fed-origin so the remote replies with its own occupants (bilateral sync).
     */
    public void resyncMappedDestinations(Collection<String> destinations) {
        for (String dest : destinations) {
            if (!routingTable.isReachable(dest)) continue;
            for (List<RoomMapping> mappings : roomManager.getLocalMappings().values()) {
                for (RoomMapping m : mappings) {
                    if (m.remoteDomain().equals(dest)) {
                        pushInitialSyncPresences(m.localRoomJid(), dest, m.remoteRoomJid());
                    }
                }
            }
        }
    }

    public void propagateRoutingToAll(String excludeDomain) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (!peer.getDomain().equals(excludeDomain)
                    && peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoutingUpdate(peer.getDomain());
            }
        }
    }

    public void relayRoomAdvertisement(String excludeDomain, String originDomain,
                                       List<FederatedRoom> rooms, String via) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getDomain().equals(excludeDomain)) continue;
            if (via != null && via.contains(peer.getDomain())) continue;
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.roomAdvertisement(
                                  peer.getDomain(), rooms, originDomain, via));
                } catch (Exception e) {
                    Log.warn("Failed to relay room-advertisement to {}: {}", peer.getDomain(), e.getMessage());
                }
            }
        }
    }

    // ── Gossip sending ─────────────────────────────────────────────────────────

    public void sendFullGossip(String toDomain) {
        Log.debug("Sending full gossip to {}", toDomain);
        sendPeerAnnounce(toDomain);
        sendRoutingUpdate(toDomain);
        sendRoomAdvertisement(toDomain);
        sendCachedRemoteRoomAdvertisements(toDomain);
    }

    /**
     * Replays every remote room advertisement this server has cached to a
     * newly-connected peer.  Without this, a server that joins mid-session
     * would never see rooms that were advertised before it connected.
     * The local domain is set as the via trail so the recipient can relay
     * further without bouncing back to us.
     */
    public void sendCachedRemoteRoomAdvertisements(String toDomain) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (Map.Entry<String, List<FederatedRoom>> entry : roomManager.getRemoteRooms().entrySet()) {
            String originDomain = entry.getKey();
            if (originDomain.equals(toDomain)) continue;  // don't send a server its own rooms
            List<FederatedRoom> rooms = entry.getValue();
            if (rooms.isEmpty()) continue;
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.roomAdvertisement(
                              toDomain, rooms, originDomain, localDomain));
            } catch (Exception e) {
                Log.warn("Failed to send cached room-advertisement for {} to {}: {}",
                         originDomain, toDomain, e.getMessage());
            }
        }
    }

    public void sendPeerAnnounce(String toDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.peerAnnounce(toDomain));
        } catch (Exception e) {
            Log.warn("Failed to send peer-announce to {}: {}", toDomain, e.getMessage());
        }
    }

    public void sendPeerDisable(String toDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.peerDisable(toDomain));
        } catch (Exception e) {
            Log.warn("Failed to send peer-disable to {}: {}", toDomain, e.getMessage());
        }
    }

    /**
     * Sends a one-shot keepalive reply that warms the reverse S2S socket without
     * eliciting another reply.  Sent when a steady-state peer-announce arrives so a
     * single peer's keepalive timer keeps both directions of the link alive.
     */
    public void sendPeerAnnounceReply(String toDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.peerAnnounce(toDomain, true));
        } catch (Exception e) {
            Log.warn("Failed to send peer-announce reply to {}: {}", toDomain, e.getMessage());
        }
    }

    public void sendRoutingUpdate(String toDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.routingUpdate(
                          toDomain, routingTable.getRoutesExcludingNextHop(toDomain)));
        } catch (Exception e) {
            Log.warn("Failed to send routing-update to {}: {}", toDomain, e.getMessage());
        }
    }

    public void sendRoomAdvertisement(String toDomain) {
        List<FederatedRoom> rooms = roomManager.getLocalFederatedRoomsWithDetails();
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomAdvertisement(toDomain, rooms));
        } catch (Exception e) {
            Log.warn("Failed to send room-advertisement to {}: {}", toDomain, e.getMessage());
        }
    }

    // ── Accessors for subsystems ───────────────────────────────────────────────

    public int  getKeepaliveSeconds()            { return s2sMonitor.getKeepaliveSeconds(); }
    public int  getEffectiveKeepaliveSeconds()   { return s2sMonitor.getEffectiveKeepaliveSeconds(); }
    public void setKeepaliveSeconds(int sec)     { s2sMonitor.setKeepaliveSeconds(sec); }
    public int  getReconnectSeconds()            { return s2sMonitor.getReconnectSeconds(); }
    public void setReconnectSeconds(int sec)     { s2sMonitor.setReconnectSeconds(sec); }
    public long getNextRetryAt(String domain)    { return s2sMonitor.getNextRetryAt(domain); }

    public PeerRegistry           getPeerRegistry()  { return peerRegistry;  }
    public FederationRoutingTable getRoutingTable()  { return routingTable;  }
    public FederatedRoomManager   getRoomManager()   { return roomManager;   }
}
