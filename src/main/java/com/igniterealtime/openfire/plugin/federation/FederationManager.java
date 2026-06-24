package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import org.dom4j.Element;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
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
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
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
import java.util.stream.Collectors;

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

        FederationProperties.register();   // expose all plugin properties under the Federation plugin
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
            propagateTopologyChange(domain);
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
                propagateTopologyChange(domain);
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
        // We may be a hub with no real occupants of our own: the local room can already
        // hold virtual occupants from OTHER spokes. Forward them to the newly-mapped spoke
        // so it sees clients on the previously-mapped servers, not only future joiners.
        forwardVirtualOccupants(localJid, remoteDomain, remoteJid);
    }

    /** Removes ALL spoke mappings for localJid and notifies every remote. */
    public void unmapRooms(String localJid) {
        List<RoomMapping> mappings = new ArrayList<>(roomManager.getMappingsForLocal(localJid));
        for (RoomMapping m : mappings) {
            pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        }
        roomManager.removeMapping(localJid);
        // Every mapping is gone — drop all virtual occupants in the room (incl. hub-relayed
        // users tracked under a relay domain, which a per-domain evict would miss).
        evictAllVirtualOccupantsInRoom(localJid);
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
        pushVirtualPresences(localJid, m.remoteDomain(), m.remoteRoomJid(), true);
        roomManager.removeMapping(localJid, remoteDomain);
        // If this was the room's last mapping, every virtual occupant is now unreachable —
        // evict them all (covers hub-relayed users tracked under a relay domain). Otherwise
        // fall back to evicting the ones from this specific remote.
        if (roomManager.getMappingsForLocal(localJid).isEmpty()) {
            evictAllVirtualOccupantsInRoom(localJid);
        } else {
            evictVirtualOccupants(localJid, remoteDomain);
        }
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
     * Drops every client ORIGINATING from remoteDomain across all local rooms — used
     * when the route to remoteDomain is lost.  Eviction is keyed on the virtual nick's
     * HOME domain rather than the domain it was tracked under, so multi-hop occupants
     * (tracked under a relay) are also dropped when the far server becomes unreachable.
     */
    public void evictAllVirtualOccupantsFromDomain(String remoteDomain) {
        for (String localJid : roomManager.getRoomsWithAnyVirtualOccupants()) {
            evictVirtualOccupantsByOrigin(localJid, remoteDomain);
        }
    }

    /**
     * Delivers leave presences to every current local occupant of localRoomJid for each
     * virtual nick that ARRIVED VIA remoteDomain (the immediate neighbour we got them from).
     * Used when tearing down a specific mapping while others on the room survive.
     */
    public void evictVirtualOccupants(String localRoomJid, String remoteDomain) {
        sendVirtualLeaves(localRoomJid, roomManager.clearVirtualOccupantsByArrivedVia(localRoomJid, remoteDomain));
    }

    /**
     * Like {@link #evictVirtualOccupants} but matches virtual nicks by their HOME domain
     * (user@home), so it drops a server's clients even when they reached us via a relay.
     */
    public void evictVirtualOccupantsByOrigin(String localRoomJid, String originDomain) {
        sendVirtualLeaves(localRoomJid, roomManager.clearVirtualOccupantsByOrigin(localRoomJid, originDomain));
    }

    /**
     * Drops EVERY virtual occupant in a local room — used when its last federation mapping
     * is removed (the whole federation feeding the room is gone).  Catches hub-relayed users
     * tracked under a relay domain, which per-origin/per-domain eviction misses multi-hop.
     */
    public void evictAllVirtualOccupantsInRoom(String localRoomJid) {
        sendVirtualLeaves(localRoomJid, roomManager.clearAllVirtualOccupants(localRoomJid));
    }

    /** Sends an unavailable presence for each given virtual nick to every real local occupant. */
    private void sendVirtualLeaves(String localRoomJid, Set<String> nicks) {
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
            Log.debug("sendVirtualLeaves: sent {} leave(s) in {}", nicks.size(), localRoomJid);
        } catch (Exception e) {
            Log.warn("sendVirtualLeaves: error for {}: {}", localRoomJid, e.getMessage());
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
     * (a) virtuals that arrived VIA toDomain (don't echo them back the way they came),
     * and (b) users whose HOME (origin) server is toDomain (don't echo a server its own
     * users — otherwise a user sees a ghost copy of themself looping back home).  Both are
     * first-class fields on {@link FederatedRoomManager.VirtualOccupant}, so no nick parsing.
     */
    public void forwardVirtualOccupants(String localRoom, String toDomain, String remoteRoomJid) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(toDomain).orElse(toDomain);
        for (FederatedRoomManager.VirtualOccupant vo : roomManager.getVirtualOccupants(localRoom)) {
            if (vo.arrivedVia().contains(toDomain)) continue;     // don't echo back any way it came
            if (toDomain.equals(vo.origin()))     continue;       // toDomain's own user — don't echo
            try {
                Presence vsync = new Presence();
                vsync.setFrom(new JID(vo.nick()));
                FederationStanzaFactory.markAsForwarded(vsync);
                XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.mucForward(nextHop, toDomain, remoteRoomJid, localDomain, vsync));
            } catch (Exception e) {
                Log.warn("forwardVirtualOccupants: failed for {}: {}", vo.nick(), e.getMessage());
            }
        }
    }

    /**
     * Leave-equivalent of {@link #forwardVirtualOccupants}: when a virtual occupant leaves,
     * propagate an unavailable presence for it toward every mapping of localRoom, using the
     * SAME split-horizon exclusions — (a) don't echo it back down a path it arrived via, and
     * (b) don't echo a server its own user.  Marked fed-origin like the join sync.
     *
     * <p>Why this is needed: a JOIN reaches distant (multi-hop) spokes via the state-based
     * {@code forwardVirtualOccupants} path, but a LEAVE previously had no state-based
     * equivalent — it relied solely on the transient hub fan-out, which does not reliably
     * reach those spokes.  The result was a ghost occupant: the join propagated but the
     * matching leave did not.  This makes leave propagation symmetric with join propagation.
     */
    public void forwardVirtualLeave(String localRoom, String leavingNick,
                                    String origin, Set<String> arrivedVia) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (RoomMapping m : roomManager.getMappingsForLocal(localRoom)) {
            String toDomain = m.remoteDomain();
            if (arrivedVia.contains(toDomain)) continue;   // don't echo back the way it came
            if (toDomain.equals(origin))       continue;   // toDomain's own user — don't echo
            String nextHop = routingTable.findNextHop(toDomain).orElse(toDomain);
            try {
                Presence vleave = new Presence();
                vleave.setFrom(new JID(leavingNick));
                vleave.setType(Presence.Type.unavailable);
                FederationStanzaFactory.markAsForwarded(vleave);
                XMPPServer.getInstance().getPacketRouter().route(
                    FederationStanzaFactory.mucForward(nextHop, toDomain, m.remoteRoomJid(), localDomain, vleave));
            } catch (Exception e) {
                Log.warn("forwardVirtualLeave: failed for {}: {}", leavingNick, e.getMessage());
            }
        }
        Log.debug("forwardVirtualLeave: propagated leave of {} from {} (origin={}, arrivedVia={})",
                  leavingNick, localRoom, origin, arrivedVia);
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
            evictAllVirtualOccupantsFromDomain(dest);       // by ORIGIN home domain
            evictOccupantsForLostHub(dest);                 // by mapped hub (catches un-routed origins)
            roomManager.clearRemoteRooms(dest);
        }
    }

    /**
     * A room's federation hub just became unreachable: drop the occupants we learned through it.
     *
     * <p>{@link #evictAllVirtualOccupantsFromDomain} only evicts occupants whose HOME (origin)
     * domain equals the lost destination — fine on a fully-trusted mesh, where every hop carries
     * routes to all origins. But across an <b>untrusted edge</b>, routes are filtered down to the
     * exposed rooms' hosting servers only: occupants from the servers <i>behind</i> the hub flow
     * through as muc-forwards yet their origin domains are never routable here, so by-origin
     * eviction misses them and they linger as ghosts after the link drops.
     *
     * <p>So for every local room mapped to the now-unreachable hub, if that was the room's only
     * remaining reachable federation path, evict <i>every</i> virtual occupant in it — they all
     * reached us through the hub we just lost. If the room still has another reachable mapping
     * (multi-hub), we leave its occupants alone; the per-origin pass above covers what it can.
     */
    private void evictOccupantsForLostHub(String lostDomain) {
        for (String localJid : roomManager.getRoomsWithAnyVirtualOccupants()) {
            List<RoomMapping> mappings = roomManager.getMappingsForLocal(localJid);
            boolean mappedToLost = mappings.stream().anyMatch(m -> m.remoteDomain().equals(lostDomain));
            if (!mappedToLost) continue;
            boolean anyReachable = mappings.stream().anyMatch(m -> routingTable.isReachable(m.remoteDomain()));
            if (!anyReachable) {
                evictAllVirtualOccupantsInRoom(localJid);
            }
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

    /** Sends our full room knowledge — own federated rooms + cached remote rooms — to one peer. */
    public void sendRoomState(String toDomain) {
        sendRoomAdvertisement(toDomain);
        sendCachedRemoteRoomAdvertisements(toDomain);
    }

    /**
     * Re-floods room knowledge to every reachable peer.  Called on routing changes so
     * remote-room caches reconverge along new paths after a link is removed/disabled —
     * e.g. a server that regains a destination via an alternate route gets its rooms back.
     */
    public void propagateRoomsToAll() {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                sendRoomState(peer.getDomain());
            }
        }
    }

    /**
     * Asks every reachable peer (except excludeDomain) to re-send its routing table and
     * room cache.  Sent when we lose routes so alternate paths re-form: a peer that still
     * reaches a now-lost destination advertises it (and its rooms) back to us.  Without
     * this, triggered-only distance-vector never re-learns a route via an alternate path.
     */
    /**
     * Asks a single peer to (re-)send its routing table and room cache.  Sent on
     * peer-UP so we PULL the peer's state instead of only pushing ours: an S2S link
     * is two independent one-way sockets, so a reap/flap is asymmetric — the far peer
     * may never have seen us go down and so still considers us REACHABLE.  In that case
     * its reply to our peer-announce is only a keepalive (no routing-update), and we would
     * never re-learn the destinations reachable through it.  A solicit forces a full reply
     * (sendRoutingUpdate + sendRoomState) regardless of the peer's view of the link.
     */
    public void solicitRouting(String toDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.routingSolicit(toDomain));
        } catch (Exception e) {
            Log.warn("Failed to send routing-solicit to {}: {}", toDomain, e.getMessage());
        }
    }

    public void solicitRoutingFromAll(String excludeDomain) {
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() == PeerServer.Status.REACHABLE
                    && !peer.getDomain().equals(excludeDomain)) {
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.routingSolicit(peer.getDomain()));
                } catch (Exception e) {
                    Log.warn("Failed to send routing-solicit to {}: {}", peer.getDomain(), e.getMessage());
                }
            }
        }
    }

    /**
     * A link was removed/disabled: gossip the routing change, re-flood room caches, and
     * solicit alternate routes so reachability and room visibility reconverge across the
     * whole network (not just on the directly-affected node).
     */
    public void propagateTopologyChange(String excludeDomain) {
        propagateRoutingToAll(excludeDomain);
        propagateRoomsToAll();
        solicitRoutingFromAll(excludeDomain);
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
                // Untrusted peer: relay only the exposed subset; skip if it's allowed none
                // (but still relay an empty list, which is a withdrawal and reveals nothing).
                List<FederatedRoom> toSend = filterRoomsForPeer(peer.getDomain(), rooms);
                if (toSend.isEmpty() && !rooms.isEmpty()) continue;
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.roomAdvertisement(
                                  peer.getDomain(), toSend, originDomain, via));
                } catch (Exception e) {
                    Log.warn("Failed to relay room-advertisement to {}: {}", peer.getDomain(), e.getMessage());
                }
            }
        }
    }

    // ── Untrusted-peer exposure filtering ──────────────────────────────────────
    //
    // An untrusted peer (e.g. a foreign org reached only through this edge server) gets
    // NO routes and NO room advertisements except for the SERVERS the admin has explicitly
    // exposed to it: it sees each exposed server's federated rooms and a route to reach it.
    // A room is exposed to the peer iff its originServer is in the exposed set (local rooms
    // carry originServer == our domain, so the admin exposes them by listing our own domain).
    // All per-peer senders funnel through the methods below, so filtering here covers
    // gossip/propagate/solicit.

    private boolean isUntrusted(String domain) {
        return peerRegistry.isUntrusted(domain);
    }

    /** Restricts a room list to rooms homed on a peer's exposed servers; unchanged for trusted peers. */
    private List<FederatedRoom> filterRoomsForPeer(String domain, List<FederatedRoom> rooms) {
        if (!isUntrusted(domain)) return rooms;
        Set<String> exposed = peerRegistry.getExposedServers(domain);
        if (exposed.isEmpty()) return Collections.emptyList();
        return rooms.stream().filter(r -> exposed.contains(r.originServer())).collect(Collectors.toList());
    }

    /**
     * The servers the admin may expose to an untrusted peer: this server (its local rooms) plus
     * every routing destination reachable WITHOUT going through that peer. Destinations whose next
     * hop is the peer itself — or the peer's own domain — are excluded, so we never offer to
     * re-expose to a peer the servers it is advertising to us (a meaningless echo). Used by the
     * admin UI to build the per-peer exposed-server checklist.
     */
    public Set<String> exposableServers(String peerDomain) {
        Set<String> out = new LinkedHashSet<>();
        out.add(XMPPServer.getInstance().getServerInfo().getXMPPDomain());
        for (RouteEntry e : routingTable.getAll()) {
            String dest = e.destination();
            if (dest.equals(peerDomain) || peerDomain.equals(e.nextHop())) continue;
            out.add(dest);
        }
        return out;
    }

    // ── Trust-on-first-use: foreign-domain default + S2S cert pinning ───────────
    //
    // A peer whose PARENT domain differs from ours is treated as a stranger and defaults to
    // untrusted on add. Separately, the top-of-chain cert each peer presents on the S2S link is
    // pinned on first sighting; if it later changes (a server re-created under the same domain
    // name presents a different cert/CA) the peer is auto-untrusted and the admin is alerted.

    /** Number of trailing DNS labels that define the "parent" (registrable) domain. */
    private int trustDomainLabels() {
        return Math.max(1, JiveGlobals.getIntProperty("plugin.federation.trustDomainLabels", 2));
    }

    /** The last {@link #trustDomainLabels()} dot-separated labels of {@code domain}, lowercased. */
    private String parentDomain(String domain) {
        if (domain == null) return "";
        String[] labels = domain.strip().toLowerCase().split("\\.");
        int n = Math.min(trustDomainLabels(), labels.length);
        return String.join(".", Arrays.copyOfRange(labels, labels.length - n, labels.length));
    }

    /** True if {@code peerDomain} is under a different parent domain than this server. */
    public boolean isForeignDomain(String peerDomain) {
        String local = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        return !parentDomain(peerDomain).equals(parentDomain(local));
    }

    /** SHA-256 (lowercase hex) of a certificate's encoded form, or null on failure. */
    private static String sha256Hex(java.security.cert.Certificate cert) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** The top-of-chain cert the peer presents on the S2S link (outgoing session preferred). */
    private java.security.cert.Certificate[] peerCertChain(String domain) {
        String local = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        SessionManager sm = XMPPServer.getInstance().getSessionManager();
        try {
            OutgoingServerSession out = sm.getOutgoingServerSession(new DomainPair(local, domain));
            if (out != null) {
                java.security.cert.Certificate[] c = out.getPeerCertificates();
                if (c != null && c.length > 0) return c;
            }
            for (IncomingServerSession in : sm.getIncomingServerSessions(domain)) {
                java.security.cert.Certificate[] c = in.getPeerCertificates();
                if (c != null && c.length > 0) return c;
            }
        } catch (Exception e) {
            Log.debug("Could not read S2S certificates for {}: {}", domain, e.getMessage());
        }
        return null;
    }

    /**
     * Observes the peer's current S2S cert and applies TOFU pinning: pins on first sighting,
     * accepts a matching cert, and on a CHANGED cert auto-untrusts the peer and raises an alert.
     * Called from the poll loop for each REACHABLE peer. No-op when no certificate is available
     * (e.g. a plain server-dialback link with no TLS).
     */
    public void observePeerCertificate(String domain) {
        java.security.cert.Certificate[] chain = peerCertChain(domain);
        if (chain == null) return;                       // no TLS cert visible — nothing to pin
        String fp = sha256Hex(chain[chain.length - 1]);  // top-of-chain (closest to root presented)
        if (fp == null) return;
        peerRegistry.setLastSeenCertFp(domain, fp);

        String pinned = peerRegistry.getPinnedCertFp(domain);
        if (pinned == null || pinned.isBlank()) {
            peerRegistry.setPinnedCertFp(domain, fp);
            peerRegistry.setCertMismatch(domain, false);
            Log.info("Pinned S2S cert for {} ({}…)", domain, fp.substring(0, Math.min(12, fp.length())));
            return;
        }
        if (pinned.equals(fp)) {
            peerRegistry.setCertMismatch(domain, false);
            return;
        }
        // Cert changed under the same domain name — possible impersonation. Act once on transition.
        if (!peerRegistry.isCertMismatch(domain)) {
            peerRegistry.setCertMismatch(domain, true);
            Log.warn("SECURITY: S2S cert for {} changed (pinned={}…, seen={}…) — auto-untrusting; "
                   + "admin must accept the new cert to restore", domain,
                     pinned.substring(0, Math.min(12, pinned.length())),
                     fp.substring(0, Math.min(12, fp.length())));
            if (!peerRegistry.isUntrusted(domain)) {
                peerRegistry.setUntrusted(domain, true);
                applyLocalTrustChange(domain);
            }
        }
    }

    /** Admin accepted a changed cert: re-pin the last observed fingerprint and clear the alert. */
    public void acceptNewCertificate(String domain) {
        String fp = peerRegistry.getPeer(domain).map(PeerServer::getLastSeenCertFp).orElse(null);
        if (fp == null || fp.isBlank()) {
            Log.warn("accept-cert for {} ignored — no observed certificate to pin", domain);
            return;
        }
        peerRegistry.setPinnedCertFp(domain, fp);
        peerRegistry.setCertMismatch(domain, false);
        Log.info("Admin accepted new S2S cert for {} ({}…)", domain, fp.substring(0, Math.min(12, fp.length())));
    }

    // ── Link-level trust negotiation ────────────────────────────────────────────
    //
    // Trust is a property of the LINK, not one local flag. Each side announces its stance
    // (untrusted true/false) in peer-announce; if the two ends disagree the link is blocked
    // (TRUST_MISMATCH) until both match. Most-restrictive does NOT silently win — the admins
    // must agree — so an accidental one-sided change is visible rather than quietly downgrading.

    /**
     * Blocks federation with a peer because the two ends disagree on trust. Tears down routes
     * and mappings toward it and marks TRUST_MISMATCH. Idempotent; only re-announces our stance
     * on the transition INTO mismatch so the two sides don't ping-pong announces. The link
     * auto-recovers once both ends declare the same stance.
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
            sendPeerAnnounce(domain);   // notify the peer once so it blocks too
        }
    }

    /**
     * Re-evaluates a link after the LOCAL admin changed our trust stance: announces the new
     * stance, then blocks or clears the block immediately based on the remote's last-known
     * stance, so the UI reflects the change without waiting for the next keepalive cycle.
     */
    public void applyLocalTrustChange(String domain) {
        sendPeerAnnounce(domain);   // tell the peer our new stance
        peerRegistry.getPeer(domain).ifPresent(p -> {
            Boolean remote = p.getRemoteUntrusted();
            if (remote == null) return;                     // not heard from them yet; defer to announce
            boolean local = p.isUntrusted();
            if (local != remote) {
                blockForTrustMismatch(domain);
            } else {
                // Stances agree. Clear any prior block (the announce round-trip re-gossips), and
                // if the link is live re-push filtered routing + rooms so the new stance applies.
                if (p.getStatus() == PeerServer.Status.TRUST_MISMATCH) {
                    peerRegistry.clearTrustMismatch(domain);
                } else if (p.getStatus() == PeerServer.Status.REACHABLE) {
                    sendRoutingUpdate(domain);
                    sendRoomState(domain);
                }
            }
        });
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
            List<FederatedRoom> rooms = filterRoomsForPeer(toDomain, entry.getValue());
            if (rooms.isEmpty()) continue;                // untrusted peer: none of these exposed
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
                      .route(FederationStanzaFactory.peerAnnounce(toDomain, false, isUntrusted(toDomain)));
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
                      .route(FederationStanzaFactory.peerAnnounce(toDomain, true, isUntrusted(toDomain)));
        } catch (Exception e) {
            Log.warn("Failed to send peer-announce reply to {}: {}", toDomain, e.getMessage());
        }
    }

    public void sendRoutingUpdate(String toDomain) {
        try {
            Collection<RouteEntry> table = routingTable.getRoutesExcludingNextHop(toDomain);
            if (isUntrusted(toDomain)) {
                // Untrusted peer: reveal only routes to the servers the admin has exposed to it.
                Set<String> allowed = peerRegistry.getExposedServers(toDomain);
                table = table.stream()
                             .filter(e -> allowed.contains(e.destination()))
                             .collect(Collectors.toList());
            }
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.routingUpdate(toDomain, table));
        } catch (Exception e) {
            Log.warn("Failed to send routing-update to {}: {}", toDomain, e.getMessage());
        }
    }

    public void sendRoomAdvertisement(String toDomain) {
        List<FederatedRoom> rooms = filterRoomsForPeer(toDomain, roomManager.getLocalFederatedRoomsWithDetails());
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
