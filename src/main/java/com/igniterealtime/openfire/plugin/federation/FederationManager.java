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
        Log.info("Retrying S2S for peer: {}", domain);
        // Clear WITHDRAWN so S2SMonitor will track this peer again.
        peerRegistry.getPeer(domain).ifPresent(p -> {
            if (p.getStatus() == PeerServer.Status.WITHDRAWN) {
                peerRegistry.updateStatus(domain, PeerServer.Status.UNKNOWN);
            }
        });
        sendPeerAnnounce(domain);
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
            MUCRoom room = null;
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(localRoomJID.getDomain())) {
                    room = svc.getChatRoom(localRoomJID.getNode());
                    break;
                }
            }
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

    /**
     * Sends a synthetic join or leave presence for every current occupant of
     * localRoom toward remoteRoomJid on remoteDomain.  Used when establishing
     * or tearing down a specific spoke so both sides immediately see the correct
     * occupant state.
     *
     * Presences are marked with fed-origin so the receiving server's
     * injectPresence does not trigger a recursive sync.
     */
    public void pushVirtualPresences(String localRoom, String remoteDomain,
                                     String remoteRoomJid, boolean leaving) {
        try {
            JID localRoomJid = new JID(localRoom);
            MUCRoom room = null;
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(localRoomJid.getDomain())) {
                    room = svc.getChatRoom(localRoomJid.getNode());
                    break;
                }
            }
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
                    // Carry the occupant's current show/status so the remote side
                    // sees accurate availability from the moment the mapping is set up.
                    Element curEl = occupant.getPresence().getElement();
                    Element showEl = curEl.element("show");
                    if (showEl != null) p.getElement().addElement("show").setText(showEl.getText());
                    for (Element statusEl : curEl.elements("status")) {
                        p.getElement().addElement("status").setText(statusEl.getText());
                    }
                }
                FederationStanzaFactory.markAsForwarded(p);
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mucForward(
                            nextHop, remoteDomain, remoteRoomJid, localDomain, p));
                } catch (Exception ex) {
                    Log.warn("pushVirtualPresences: failed to push {} for {}: {}",
                             leaving ? "leave" : "join", occupant.getUserAddress(), ex.getMessage());
                }
            }
            Log.debug("pushVirtualPresences: sent {} {} for {} occupant(s) toward {}",
                      leaving ? "leave" : "join", localRoom, occupants.size(), remoteRoomJid);
        } catch (Exception e) {
            Log.warn("pushVirtualPresences: error for {}: {}", localRoom, e.getMessage());
        }
    }

    /**
     * Sends current local occupants' join presences to the remote side WITHOUT the
     * fed-origin marker so the remote's injectPresence triggers syncLocalOccupantsToRemote,
     * which sends the remote occupants back.  Used only at mapping-creation time.
     * Normal in-session forwarding uses pushVirtualPresences (WITH fed-origin).
     */
    public void pushInitialSyncPresences(String localRoom, String remoteDomain,
                                         String remoteRoomJid) {
        try {
            JID localRoomJid = new JID(localRoom);
            MUCRoom room = null;
            for (MultiUserChatService svc :
                    XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                if (svc.getServiceDomain().equals(localRoomJid.getDomain())) {
                    room = svc.getChatRoom(localRoomJid.getNode());
                    break;
                }
            }
            if (room == null) return;

            Collection<MUCOccupant> occupants = room.getOccupants();
            if (occupants.isEmpty()) return;

            String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
            String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);

            for (MUCOccupant occupant : occupants) {
                Presence p = new Presence();
                p.setFrom(occupant.getUserAddress());
                Element curEl = occupant.getPresence().getElement();
                Element showEl = curEl.element("show");
                if (showEl != null) p.getElement().addElement("show").setText(showEl.getText());
                for (Element statusEl : curEl.elements("status")) {
                    p.getElement().addElement("status").setText(statusEl.getText());
                }
                // Intentionally NOT marking as forwarded so the remote's injectPresence
                // calls syncLocalOccupantsToRemote to push its occupants back to us.
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mucForward(
                            nextHop, remoteDomain, remoteRoomJid, localDomain, p));
                } catch (Exception ex) {
                    Log.warn("pushInitialSyncPresences: failed for {}: {}", occupant.getUserAddress(), ex.getMessage());
                }
            }
            Log.debug("pushInitialSyncPresences: sent {} occupant(s) from {} toward {}",
                      occupants.size(), localRoom, remoteRoomJid);
        } catch (Exception e) {
            Log.warn("pushInitialSyncPresences: error for {}: {}", localRoom, e.getMessage());
        }
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
    public void setKeepaliveSeconds(int sec)     { s2sMonitor.setKeepaliveSeconds(sec); }
    public int  getReconnectSeconds()            { return s2sMonitor.getReconnectSeconds(); }
    public void setReconnectSeconds(int sec)     { s2sMonitor.setReconnectSeconds(sec); }
    public long getNextRetryAt(String domain)    { return s2sMonitor.getNextRetryAt(domain); }

    public PeerRegistry           getPeerRegistry()  { return peerRegistry;  }
    public FederationRoutingTable getRoutingTable()  { return routingTable;  }
    public FederatedRoomManager   getRoomManager()   { return roomManager;   }
}
