package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
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
        // Route a stanza to trigger Openfire S2S establishment (packets are queued
        // until the session authenticates, then delivered automatically).
        sendPeerAnnounce(domain);
        return peer;
    }

    /** Re-sends a peer-announce to trigger/retry S2S for a configured peer. */
    public void retryPeer(String domain) {
        Log.info("Retrying S2S for peer: {}", domain);
        sendPeerAnnounce(domain);
    }

    /** Returns a snapshot of all active incoming and outgoing S2S sessions. */
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

    /** Closes all S2S sessions in the given direction for a remote domain. */
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
        boolean removed = peerRegistry.removePeer(domain);
        if (removed) {
            routingTable.removePeer(domain);
            roomManager.clearRemoteRooms(domain);
        }
        return removed;
    }

    // ── Room federation (called from admin UI / api.jsp) ──────────────────────

    public void setRoomFederated(String roomJid, boolean federated) {
        if (!federated) {
            // If an active mapping exists for this room, tear it down first:
            // sends virtual leaves to both sides and notifies the remote server.
            RoomMapping existing = roomManager.getMappingForLocal(roomJid);
            if (existing != null) {
                unmapRooms(roomJid);
            }
        }
        roomManager.setFederated(roomJid, federated);
        // Advertise the updated room list to all reachable peers
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoomAdvertisement(peer.getDomain());
            }
        }
    }

    // ── Room mapping ──────────────────────────────────────────────────────────

    /**
     * Stores a local room mapping and notifies the remote server so both sides
     * record the same pairing.  Routing to the remote domain uses Openfire's
     * normal S2S (which will establish a connection if one doesn't exist yet).
     */
    public void mapRooms(String localJid, String remoteJid, String remoteDomain) {
        roomManager.addMapping(localJid, remoteJid, remoteDomain);
        // Immediately push our current occupants to the remote room so both sides
        // see each other's participants without waiting for someone to join/leave.
        pushVirtualPresences(localJid, remoteDomain, remoteJid, false);
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomMapping(remoteDomain, localJid, remoteJid));
        } catch (Exception e) {
            Log.warn("Failed to send room-mapping to {}: {}", remoteDomain, e.getMessage());
        }
    }

    /** Removes a local room mapping and notifies the remote server. */
    public void unmapRooms(String localJid) {
        RoomMapping m = roomManager.getMappingForLocal(localJid);
        if (m != null) {
            // Push virtual leaves for our local occupants BEFORE removing the mapping
            // so the remote side's clients see our users depart cleanly.
            pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        }
        roomManager.removeMapping(localJid);
        if (m != null) {
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.roomUnmapping(
                              m.remoteDomain(), localJid, m.remoteRoomJid()));
            } catch (Exception e) {
                Log.warn("Failed to send room-unmap to {}: {}", m.remoteDomain(), e.getMessage());
            }
        }
    }

    /**
     * Sends a synthetic join or leave presence for every current occupant of
     * localRoom toward remoteRoomJid on remoteDomain.  Called when establishing
     * or tearing down a room mapping so clients on both sides immediately see
     * the correct occupant state.
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
                if (leaving) p.setType(Presence.Type.unavailable);
                FederationStanzaFactory.markAsForwarded(p);
                try {
                    XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.mucForward(
                            nextHop, remoteDomain, remoteRoomJid, localDomain, p)
                    );
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

    // ── Routing propagation ────────────────────────────────────────────────────

    /**
     * Sends our current routing table to every REACHABLE peer except excludeDomain.
     */
    public void propagateRoutingToAll(String excludeDomain) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (!peer.getDomain().equals(excludeDomain)
                    && peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoutingUpdate(peer.getDomain());
            }
        }
    }

    /**
     * Relays a room-advertisement (received from originDomain) to all REACHABLE
     * peers except the one we received it from.  The origin attribute prevents
     * the next recipient from re-relaying (single-hop flood to avoid loops).
     */
    public void relayRoomAdvertisement(String excludeDomain, String originDomain, List<FederatedRoom> rooms) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (!peer.getDomain().equals(excludeDomain)
                    && peer.getStatus() == PeerServer.Status.REACHABLE) {
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.roomAdvertisement(
                                  peer.getDomain(), rooms, originDomain));
                } catch (Exception e) {
                    Log.warn("Failed to relay room-advertisement to {}: {}", peer.getDomain(), e.getMessage());
                }
            }
        }
    }

    // ── Gossip sending ─────────────────────────────────────────────────────────

    /**
     * Sends the complete local state to a peer:
     *   1. peer-announce  (who we are)
     *   2. routing-update (our routing table)
     *   3. room-advertisement (our federated rooms)
     */
    public void sendFullGossip(String toDomain) {
        Log.debug("Sending full gossip to {}", toDomain);
        sendPeerAnnounce(toDomain);
        sendRoutingUpdate(toDomain);
        sendRoomAdvertisement(toDomain);
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
                      .route(FederationStanzaFactory.routingUpdate(toDomain, routingTable.getAll()));
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

    public PeerRegistry           getPeerRegistry()  { return peerRegistry;  }
    public FederationRoutingTable getRoutingTable()  { return routingTable;  }
    public FederatedRoomManager   getRoomManager()   { return roomManager;   }
}
