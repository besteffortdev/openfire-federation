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
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.OutgoingServerSession;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.user.PresenceEventDispatcher;
import org.jivesoftware.openfire.user.PresenceEventListener;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
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
    private final UserDirectory          userDirectory   = new UserDirectory();
    private final BookmarkInjector       bookmarkInjector = new BookmarkInjector();
    private       S2SMonitor             s2sMonitor;
    private       FederationIQHandler    iqHandler;
    private       FederationPacketInterceptor interceptor;
    private       PresenceEventListener  presenceListener;

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

        // Republish our directory live as local users come/go/change presence (only when publishing
        // is enabled; the S2S poll tick remains a backstop).
        presenceListener = new PresenceEventListener() {
            @Override public void availableSession(ClientSession s, Presence p) {
                onLocalPresenceEvent(s, p);
                // On login, probe our multi-hop contacts to pull their current presence.
                try { if (s != null && s.getAddress() != null) probeRemoteContacts(s.getAddress()); }
                catch (Exception e) { Log.debug("probeRemoteContacts failed: {}", e.getMessage()); }
            }
            @Override public void unavailableSession(ClientSession s, Presence p) { onLocalPresenceEvent(s, p); }
            @Override public void presenceChanged(ClientSession s, Presence p)    { onLocalPresenceEvent(s, p); }
            @Override public void subscribedToPresence(JID subscriberJID, JID userJID) {
                // A local user just became subscribed to a contact's presence. If that contact lives on
                // a multi-hop peer, Openfire's own probe leaks to native S2S and fails — re-issue it over
                // the overlay so the contact's presence is actually pulled (and the entry stays visible).
                try { probeRemoteContact(subscriberJID, userJID); }
                catch (Exception e) { Log.debug("probeRemoteContact failed: {}", e.getMessage()); }
            }
            @Override public void unsubscribedToPresence(JID a, JID b) { }
        };
        PresenceEventDispatcher.addListener(presenceListener);

        Log.info("Federation plugin started — {} peer(s) configured", peerRegistry.getPeers().size());
    }

    /**
     * A local user's presence changed. Two effects: (1) refresh the published directory if publishing
     * is on; (2) forward the user's new presence to mapped peers for any federated room they occupy so
     * an in-room status change propagates live (a broadcast presence never reaches the interceptor).
     */
    private void onLocalPresenceEvent(ClientSession session, Presence presence) {
        if (FederationProperties.DIRECTORY_PUBLISH.getValue()) publishDirectory();
        if (FederationProperties.BOOKMARK_PUSH.getValue())     pushBookmarks();
        try {
            if (session != null && session.getAddress() != null) {
                forwardLocalOccupantPresence(session.getAddress(), presence);        // MUC occupants
                forwardPresenceToRemoteSubscribers(session.getAddress(), presence);  // 1:1 roster contacts
            }
        } catch (Exception e) {
            Log.debug("presence-event forward failed: {}", e.getMessage());
        }
    }

    /**
     * Forwards a local user's presence to their roster subscribers living on MULTI-HOP peers.
     * Needed because a user's availability is a BROADCAST presence (no {@code to}); Openfire's
     * server-generated directed copies to remote subscribers (and probe answers) bypass the packet
     * interceptor, so without this a federated contact never sees the user go online/away/offline.
     * Mirrors the MUC fix in {@link #forwardLocalOccupantPresence}. Direct peers are left to native
     * S2S, which delivers subscriber presence correctly on its own.
     */
    public void forwardPresenceToRemoteSubscribers(JID user, Presence presence) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (user == null || user.getNode() == null) return;
        if (!XMPPServer.getInstance().isLocal(user)) return;

        // Targets = anyone subscribed to this user's presence. We union two sources:
        //  (1) subscribers we tracked from the `subscribed` stanzas we relayed — authoritative and
        //      robust even when Openfire's cross-server roster state lands incomplete; and
        //  (2) roster items marked FROM/BOTH (covers restart, where the tracked map is empty).
        Set<String> targets = new LinkedHashSet<>(
                remoteSubscribers.getOrDefault(user.toBareJID(), Collections.emptySet()));
        try {
            Roster roster = XMPPServer.getInstance().getRosterManager().getRoster(user.getNode());
            if (roster != null) {
                for (RosterItem item : roster.getRosterItems()) {
                    JID c = item.getJid();
                    if (c == null || c.getNode() == null) continue;
                    RosterItem.SubType sub = item.getSubStatus();
                    if (sub == RosterItem.SUB_FROM || sub == RosterItem.SUB_BOTH) targets.add(c.toBareJID());
                }
            }
        } catch (Exception ignored) { }

        for (String bare : targets) {
            JID contact = new JID(bare);
            if (!isMultiHopPeer(contact.getDomain())) continue;          // direct peers: native S2S
            forwardDirectPresence(directedPresence(user, contact, presence));
        }
    }

    /** Subscribers (remote bare JIDs) of each local user's presence, tracked from relayed `subscribed`. */
    private final Map<String, Set<String>> remoteSubscribers = new java.util.concurrent.ConcurrentHashMap<>();

    /** Record that {@code subscriber} is now subscribed to local {@code localUser}'s presence. */
    public void addRemoteSubscriber(JID localUser, JID subscriber) {
        if (localUser == null || subscriber == null || subscriber.getNode() == null) return;
        remoteSubscribers.computeIfAbsent(localUser.toBareJID(),
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(subscriber.toBareJID());
    }

    /** Forget a subscriber (relayed `unsubscribed`). */
    public void removeRemoteSubscriber(JID localUser, JID subscriber) {
        if (localUser == null || subscriber == null) return;
        Set<String> subs = remoteSubscribers.get(localUser.toBareJID());
        if (subs != null) {
            subs.remove(subscriber.toBareJID());
            if (subs.isEmpty()) remoteSubscribers.remove(localUser.toBareJID());
        }
    }

    /**
     * Pushes a local user's current presence to a remote (multi-hop) target.  Used both when we have
     * just approved a subscription (the relayed {@code subscribed}) and when answering a presence
     * probe — in both cases Openfire's own presence delivery to the remote peer would be routed past
     * the packet interceptor, so we send it explicitly over the overlay.
     */
    public void pushUserPresenceTo(JID target, JID localUser) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (localUser == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (target == null || target.getNode() == null) return;
        if (!isMultiHopPeer(target.getDomain())) return;
        Presence cur = currentPresenceOf(localUser);
        if (cur == null) return;   // user offline — nothing to advertise
        forwardDirectPresence(directedPresence(localUser, target, cur));
    }

    /** Answer a relayed presence probe for a local user by sending that user's presence to the prober. */
    public void answerPresenceProbe(JID prober, JID localUser) { pushUserPresenceTo(prober, localUser); }

    /**
     * On a local user's login, probe their multi-hop roster contacts so we pull each contact's current
     * presence (Openfire's own probe is routed past the interceptor and never crosses the overlay).
     * The far server answers via {@link #answerPresenceProbe}.
     */
    public void probeRemoteContacts(JID user) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (user == null || user.getNode() == null || !XMPPServer.getInstance().isLocal(user)) return;
        Roster roster;
        try {
            roster = XMPPServer.getInstance().getRosterManager().getRoster(user.getNode());
        } catch (Exception e) {
            return;
        }
        if (roster == null) return;
        for (RosterItem item : roster.getRosterItems()) {
            JID contact = item.getJid();
            if (contact == null || contact.getNode() == null) continue;
            if (!isMultiHopPeer(contact.getDomain())) continue;
            RosterItem.SubType sub = item.getSubStatus();
            if (sub != RosterItem.SUB_TO && sub != RosterItem.SUB_BOTH) continue;   // we subscribe to them
            Presence probe = new Presence();
            probe.setType(Presence.Type.probe);
            probe.setFrom(user.asBareJID());
            probe.setTo(contact);
            forwardDirectPresence(probe);
        }
    }

    /**
     * Probe a single multi-hop contact's presence over the overlay on behalf of a local user — used when
     * the user becomes subscribed to that contact mid-session ({@code subscribedToPresence}). Openfire's
     * own auto-probe at that moment is routed straight to native S2S and, for a multi-hop peer with no
     * direct link, hangs and fails; this re-issues the same probe over the overlay so the contact's
     * presence is pulled. Gated by {@link FederationProperties#PROBE_ON_SUBSCRIBE} (and the relay master
     * switch). A no-op unless {@code localUser} is local and {@code contact} is a multi-hop peer user.
     */
    public void probeRemoteContact(JID localUser, JID contact) {
        if (!FederationProperties.PROBE_ON_SUBSCRIBE.getValue()) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (localUser == null || localUser.getNode() == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (contact == null || contact.getNode() == null) return;
        if (!isMultiHopPeer(contact.getDomain())) return;
        Presence probe = new Presence();
        probe.setType(Presence.Type.probe);
        probe.setFrom(localUser.asBareJID());
        probe.setTo(contact.asBareJID());
        forwardDirectPresence(probe);
        Log.info("subscription-probe: probing multi-hop contact {} for {} over overlay", contact.asBareJID(), localUser.asBareJID());
    }

    /** The highest-priority available presence of a local user's sessions, or null if offline. */
    private Presence currentPresenceOf(JID user) {
        for (ClientSession s : XMPPServer.getInstance().getSessionManager().getSessions(user.getNode())) {
            Presence p = s.getPresence();
            if (p != null && p.isAvailable()) return p;
        }
        return null;
    }

    /**
     * Builds a directed presence from→to.  Copies the source presence WHOLE (not just show/status)
     * so extension elements survive — the avatar hash {@code vcard-temp:x:update} (XEP-0153), entity
     * caps {@code <c/>} (XEP-0115) and priority — then overwrites from/to.  Without this a contact's
     * client never learns there is an avatar to fetch or what the contact supports.
     */
    private Presence directedPresence(JID from, JID to, Presence src) {
        Presence p = (src != null) ? new Presence(src.getElement().createCopy()) : new Presence();
        p.setFrom(src != null && src.getFrom() != null ? src.getFrom() : from);
        p.setTo(to);
        return p;
    }

    private boolean isMultiHopPeer(String domain) {
        return routingTable.findNextHop(domain).map(nh -> !nh.equals(domain)).orElse(false);
    }

    public void stop() {
        Log.info("Federation plugin stopping…");

        if (s2sMonitor != null) s2sMonitor.stop();

        if (presenceListener != null)
            PresenceEventDispatcher.removeListener(presenceListener);

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
            // Tear down all mappings (any state) before clearing the federation tag.
            if (!roomManager.getAllMappingsForLocal(roomJid).isEmpty()) {
                unmapRooms(roomJid);
            }
        }
        roomManager.setFederated(roomJid, federated);
        reAdvertiseToAllReachable();
    }

    /**
     * Sets a room's visibility ACL (server domains allowed to see it; empty = all) and re-advertises
     * to every reachable peer so the change (including withdrawals from now-excluded peers) takes
     * effect immediately. The ACL is persisted, so it survives a listed server being unavailable.
     */
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

    // ── Room mapping ──────────────────────────────────────────────────────────

    /**
     * Adds one spoke mapping (localJid ↔ remoteJid on remoteDomain) and notifies
     * the remote server.  Can be called multiple times on the hub to connect
     * additional spokes to the same local room.
     */
    /**
     * Requests a mapping from our local room to a remote room. Stores it PENDING_OUT and sends a
     * room-mapping REQUEST; nothing forwards until the remote accepts (handleMappingAccept). No
     * roster is pushed yet — that happens on the accept transition.
     */
    public void requestMapping(String localJid, String remoteJid, String remoteDomain) {
        roomManager.addMapping(localJid, remoteJid, remoteDomain, RoomMapping.State.PENDING_OUT, "");
        sendMappingRequest(localJid, remoteJid, remoteDomain);
    }

    private void sendMappingRequest(String localJid, String remoteJid, String remoteDomain) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomMapping(
                          nextHop, remoteDomain, localDomain, localJid, remoteJid));
            Log.info("Sent mapping request {} → {} ({})", localJid, remoteJid, remoteDomain);
        } catch (Exception e) {
            Log.warn("Failed to send mapping request to {}: {}", remoteDomain, e.getMessage());
        }
    }

    /**
     * Re-sends pending outgoing mapping requests toward a peer that just came up. To avoid both ends
     * racing on a legacy/queued link, only the lexicographically-smaller domain initiates; the other
     * side simply accepts the incoming request.
     */
    public void resendPendingRequests(String remoteDomain) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        if (localDomain.compareTo(remoteDomain) >= 0) return;
        for (List<RoomMapping> list : roomManager.getLocalMappings().values()) {
            for (RoomMapping m : list) {
                if (m.remoteDomain().equals(remoteDomain) && m.state() == RoomMapping.State.PENDING_OUT) {
                    sendMappingRequest(m.localRoomJid(), m.remoteRoomJid(), remoteDomain);
                }
            }
        }
    }

    /**
     * Accepts an incoming mapping request on one of our rooms (we are the room owner). Mints a shared
     * token, marks the mapping ACTIVE, tells the requester (room-mapping-accept), and pushes our roster.
     */
    public void acceptMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) { Log.warn("acceptMapping: no mapping {} ({})", localJid, remoteDomain); return; }
        String token = org.jivesoftware.util.StringUtils.randomString(40);
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, token);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        route(FederationStanzaFactory.roomMappingAccept(nextHop, remoteDomain, localDomain,
                m.localRoomJid(), m.remoteRoomJid(), token), remoteDomain);
        // Push our roster so the requester's clients see our occupants immediately.
        pushRosterToPeer(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Accepted mapping {} ({})", localJid, remoteDomain);
    }

    /** Rejects an incoming mapping request: tells the requester and drops our pending record. */
    public void rejectMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        route(FederationStanzaFactory.roomMappingReject(nextHop, remoteDomain, localDomain,
                m.localRoomJid(), m.remoteRoomJid(), null), remoteDomain);
        roomManager.removeMapping(localJid, remoteDomain);
        Log.info("Rejected mapping request {} ({})", localJid, remoteDomain);
    }

    /** Disables an active mapping from our side; the peer will show "disabled by peer". */
    public void disableMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.DISABLED_LOCAL, m.token());
        pushVirtualPresences(localJid, remoteDomain, m.remoteRoomJid(), true);
        evictForInactiveMapping(localJid, remoteDomain);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        route(FederationStanzaFactory.roomMappingDisable(nextHop, remoteDomain, localDomain,
                m.localRoomJid(), m.remoteRoomJid(), m.token()), remoteDomain);
        Log.info("Disabled mapping {} ({})", localJid, remoteDomain);
    }

    /** Re-enables a mapping we previously disabled (DISABLED_LOCAL → ACTIVE) and re-syncs rosters. */
    public void enableMapping(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        if (m == null || m.state() != RoomMapping.State.DISABLED_LOCAL) return;
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, m.token());
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        route(FederationStanzaFactory.roomMappingEnable(nextHop, remoteDomain, localDomain,
                m.localRoomJid(), m.remoteRoomJid(), m.token()), remoteDomain);
        pushRosterToPeer(localJid, remoteDomain, m.remoteRoomJid());
        Log.info("Enabled mapping {} ({})", localJid, remoteDomain);
    }

    // ── Inbound lifecycle transitions (called from FederationIQHandler) ──────────

    /** Requester side: the remote accepted our request → go ACTIVE, store the token, push our roster. */
    public void onMappingAccepted(String localJid, String remoteDomain, String remoteJid, String token) {
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE, token);
        pushInitialSyncPresences(localJid, remoteDomain, remoteJid);
        forwardVirtualOccupants(localJid, remoteDomain, remoteJid);
        Log.info("Mapping {} ({}) accepted by remote — now ACTIVE", localJid, remoteDomain);
    }

    /** Requester side: the remote rejected our request. */
    public void onMappingRejected(String localJid, String remoteDomain) {
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.REJECTED, "");
        Log.info("Mapping {} ({}) rejected by remote", localJid, remoteDomain);
    }

    /** Peer disabled the mapping → mark DISABLED_REMOTE and evict its virtual occupants. */
    public void onMappingDisabledByPeer(String localJid, String remoteDomain) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.DISABLED_REMOTE,
                                    m != null ? m.token() : "");
        if (m != null) evictForInactiveMapping(localJid, remoteDomain);
        Log.info("Mapping {} ({}) disabled by peer", localJid, remoteDomain);
    }

    /** Peer re-enabled the mapping → mark ACTIVE and re-sync. */
    public void onMappingEnabledByPeer(String localJid, String remoteDomain, String remoteJid) {
        RoomMapping m = roomManager.getMappingForLocal(localJid, remoteDomain);
        roomManager.setMappingState(localJid, remoteDomain, RoomMapping.State.ACTIVE,
                                    m != null ? m.token() : "");
        pushInitialSyncPresences(localJid, remoteDomain, remoteJid);
        forwardVirtualOccupants(localJid, remoteDomain, remoteJid);
        Log.info("Mapping {} ({}) re-enabled by peer", localJid, remoteDomain);
    }

    private void route(IQ iq, String remoteDomain) {
        try {
            XMPPServer.getInstance().getPacketRouter().route(iq);
        } catch (Exception e) {
            Log.warn("Failed to route mapping lifecycle IQ to {}: {}", remoteDomain, e.getMessage());
        }
    }

    /** Removes ALL spoke mappings for localJid (any state) and notifies every remote. */
    public void unmapRooms(String localJid) {
        List<RoomMapping> mappings = new ArrayList<>(roomManager.getAllMappingsForLocal(localJid));
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
                              nextHop, m.remoteDomain(), localDomain, localJid, m.remoteRoomJid(), m.token()));
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
        // If this was the room's last active mapping, every virtual occupant is now unreachable
        // (covers hub-relayed cross-spoke users); otherwise drop this remote's users by origin
        // and propagate their leaves to the still-active spokes. Same path as mapping disable.
        evictForInactiveMapping(localJid, remoteDomain);
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop = routingTable.findNextHop(m.remoteDomain()).orElse(m.remoteDomain());
        try {
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.roomUnmapping(
                          nextHop, m.remoteDomain(), localDomain, localJid, m.remoteRoomJid(), m.token()));
        } catch (Exception e) {
            Log.warn("Failed to send room-unmap to {}: {}", m.remoteDomain(), e.getMessage());
        }
    }

    /**
     * Drops every client ORIGINATING from remoteDomain across all local rooms — used
     * when the route to remoteDomain is lost.  Eviction is keyed on the virtual nick's
     * HOME domain rather than the domain it was tracked under, so multi-hop occupants
     * (tracked under a relay) are also dropped when the far server becomes unreachable.
     *
     * <p>Like the arrivedVia pass, each leave is PROPAGATED to the occupant's other spokes.
     * Without this, a spoke that learned the occupant through us (and so cannot evict it on its
     * own route loss) keeps it as a ghost — and worse, racing this local eviction against an
     * inbound propagated leave can untrack the occupant first, so the inbound leave then finds
     * nothing to re-forward and the spoke is never told.
     */
    public void evictAllVirtualOccupantsFromDomain(String remoteDomain) {
        for (String localJid : roomManager.getRoomsWithAnyVirtualOccupants()) {
            List<FederatedRoomManager.VirtualOccupant> gone =
                    roomManager.removeVirtualOccupantsByOrigin(localJid, remoteDomain);
            if (gone.isEmpty()) continue;
            sendVirtualLeaves(localJid,
                    gone.stream().map(FederatedRoomManager.VirtualOccupant::nick)
                        .collect(java.util.stream.Collectors.toSet()));
            for (FederatedRoomManager.VirtualOccupant vo : gone) {
                forwardVirtualLeave(localJid, vo.nick(), vo.origin(), vo.arrivedVia());
            }
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
     * Cleans up virtual occupants after a mapping toward {@code remoteDomain} goes inactive
     * (disabled by either end).  Two cases, mirroring {@link #unmapRoom}:
     *
     * <ul>
     *   <li><b>No ACTIVE mapping still feeds the room</b> — every virtual occupant is now
     *       unreachable (including hub-relayed cross-spoke users whose HOME is some other
     *       server but who only reached us through this mapping), so drop them all.  This is
     *       the spoke side of a hub disable: the spoke's single mapping is gone, so the hub's
     *       own users <i>and</i> the other spokes' relayed users must all leave.</li>
     *   <li><b>Other mappings remain</b> — drop only the virtual occupants that ARRIVED VIA the
     *       now-inactive mapping (keyed on {@code arrivedVia == remoteDomain}, where arrivedVia
     *       is the MAPPED server an occupant entered through).  This drops the disabled remote's
     *       own users AND the cross-spoke users it relayed in (their arrivedVia is this mapping's
     *       far server / hub, even though their HOME is elsewhere), while occupants reachable
     *       through a <i>surviving</i> mapping keep that path and stay.  Each resulting leave is
     *       propagated to the still-active spokes with the occupant's real origin so they stop
     *       seeing those users too.</li>
     * </ul>
     *
     * <p>Keying on arrivedVia (the mapping traversed) rather than on the user's HOME is what
     * makes this correct for BOTH duals of a multi-mapping room: disabling the mapping a relayed
     * user came through evicts it; disabling an UNRELATED mapping leaves it alone — a HOME-based
     * key cannot tell those apart because the user's home is the same in both.
     */
    public void evictForInactiveMapping(String localRoomJid, String remoteDomain) {
        if (roomManager.getMappingsForLocal(localRoomJid).isEmpty()) {
            evictAllVirtualOccupantsInRoom(localRoomJid);
            return;
        }
        List<FederatedRoomManager.VirtualOccupant> gone =
                roomManager.removeVirtualOccupantsArrivedVia(localRoomJid, remoteDomain);
        if (gone.isEmpty()) return;
        sendVirtualLeaves(localRoomJid,
                gone.stream().map(FederatedRoomManager.VirtualOccupant::nick)
                    .collect(java.util.stream.Collectors.toSet()));
        for (FederatedRoomManager.VirtualOccupant vo : gone) {
            forwardVirtualLeave(localRoomJid, vo.nick(), vo.origin(), java.util.Set.of(remoteDomain));
        }
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
                            nextHop, remoteDomain, remoteRoomJid, localDomain, localDomain, p));
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
                    FederationStanzaFactory.mucForward(nextHop, toDomain, remoteRoomJid, localDomain, localDomain, vsync));
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
                    FederationStanzaFactory.mucForward(nextHop, toDomain, m.remoteRoomJid(), localDomain, localDomain, vleave));
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
            evictOccupantsArrivedViaLostServer(dest);       // by ARRIVED-VIA mapping (catches relayed cross-spoke)
            evictAllVirtualOccupantsFromDomain(dest);       // by ORIGIN home domain
            evictOccupantsForLostHub(dest);                 // by mapped hub (catches un-routed origins)
            roomManager.clearRemoteRooms(dest);
            userDirectory.clearUsersForOrigin(dest);        // drop the gone server's published users
            bookmarkInjector.applyForOrigin(dest, java.util.Collections.emptyList());  // withdraw its injected bookmarks
        }
    }

    /**
     * A mapped server (a peer or hub) just became unreachable: across every local room, drop the
     * virtual occupants that ARRIVED VIA it and propagate those leaves to the room's other spokes
     * so they stop seeing them too.  This is the route-loss analogue of
     * {@link #evictForInactiveMapping}, and the reason it is needed alongside the by-origin pass:
     * a user RELAYED in through this server (e.g. a cross-spoke occupant that reached us through a
     * hub) has its own HOME domain — which may still be perfectly routable — so by-origin eviction
     * keeps it as a ghost even though the only path it actually used is now gone.  Keying on
     * arrivedVia (the mapped server traversed, recorded at injection) drops exactly those, while
     * occupants still reachable through another mapping keep that path and stay.
     */
    public void evictOccupantsArrivedViaLostServer(String lostDomain) {
        for (String localJid : roomManager.getRoomsWithAnyVirtualOccupants()) {
            List<FederatedRoomManager.VirtualOccupant> gone =
                    roomManager.removeVirtualOccupantsArrivedVia(localJid, lostDomain);
            if (gone.isEmpty()) continue;
            sendVirtualLeaves(localJid,
                    gone.stream().map(FederatedRoomManager.VirtualOccupant::nick)
                        .collect(java.util.stream.Collectors.toSet()));
            for (FederatedRoomManager.VirtualOccupant vo : gone) {
                forwardVirtualLeave(localJid, vo.nick(), vo.origin(), java.util.Set.of(lostDomain));
            }
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
                // Untrusted-peer exposure + per-room visibility: relay only the allowed subset.
                // Always send the filtered list, EVEN WHEN EMPTY: a peer that was previously on the
                // path for this origin but is now excluded (e.g. dropped from a room's visibility
                // ACL) must receive the empty list so it withdraws the origin's rooms. Safe because
                // updateRemoteRooms REPLACES the origin's room set per receiver and an empty list
                // clears only THIS origin's rooms, so an off-path peer drops exactly them and relays
                // the withdrawal onward. (The old "skip empty unless the origin's list is empty"
                // guard never delivered the withdrawal to multi-hop excluded peers — the ACL-removal
                // bug where unchecking one server left it still seeing the room.)
                List<FederatedRoom> toSend = filterRoomsForHop(peer.getDomain(), rooms);
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

    // ── User directory + 1:1 message relay ─────────────────────────────────────

    /**
     * Publishes our online-user directory to every trusted, reachable peer when publishing is
     * enabled (default OFF). When publishing is disabled we send an EMPTY list once so peers that
     * previously cached our users withdraw them. Untrusted peers never receive the directory.
     * Cheap and idempotent — safe to call from the S2S poll tick and on peer-up.
     */
    public void publishDirectory() {
        boolean publish = FederationProperties.DIRECTORY_PUBLISH.getValue();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        Collection<UserDirectory.UserPresence> users =
                publish ? userDirectory.localOnlineUsers() : Collections.emptyList();
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() != PeerServer.Status.REACHABLE) continue;
            if (isUntrusted(peer.getDomain())) continue;   // never expose our user list to an untrusted edge
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.userDirectory(
                              peer.getDomain(), users, localDomain, null));
            } catch (Exception e) {
                Log.warn("Failed to send user-directory to {}: {}", peer.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Records an inbound user-directory for {@code originDomain} and gossips it onward to other
     * trusted peers not already on the {@code via} trail (multi-hop, loop-guarded — mirrors
     * {@link #relayRoomAdvertisement}). An empty list clears the origin's cached users.
     */
    public void handleUserDirectory(String fromDomain, String originDomain,
                                    Collection<UserDirectory.UserPresence> users, String via) {
        // Trust gate (receive side): we never expose our own directory across an untrusted edge,
        // and legitimate directory data never crosses one, so anything arriving over an untrusted
        // link is a misbehaving/malicious peer trying to poison our view — drop it.
        if (isUntrusted(fromDomain)) {
            Log.warn("SECURITY: dropping user-directory from untrusted peer {} — refusing to cache its user list", fromDomain);
            return;
        }
        userDirectory.setUsersForOrigin(originDomain, users);
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getDomain().equals(fromDomain)) continue;
            if (peer.getStatus() != PeerServer.Status.REACHABLE) continue;
            if (isUntrusted(peer.getDomain())) continue;
            if (via != null && via.contains(peer.getDomain())) continue;
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.userDirectory(
                              peer.getDomain(), users, originDomain, via));
            } catch (Exception e) {
                Log.warn("Failed to relay user-directory to {}: {}", peer.getDomain(), e.getMessage());
            }
        }
    }

    // ── Bookmark push (XEP-0048 connected-client advertisement) ────────────────

    /**
     * Pushes this server's connected clients to every trusted, reachable peer as a {@code
     * bookmark-push} when {@link FederationProperties#BOOKMARK_PUSH} is enabled. When disabled we
     * send an EMPTY list once so peers withdraw the bookmarks they previously injected for us.
     * Untrusted peers never receive it. Cheap and idempotent — safe from the S2S tick and on peer-up.
     */
    public void pushBookmarks() {
        boolean enabled = FederationProperties.BOOKMARK_PUSH.getValue();
        sendBookmarks(enabled ? userDirectory.localOnlineUsers() : Collections.emptyList());
    }

    /**
     * One-shot advertisement of our current connected clients regardless of the auto-push toggle —
     * backs the admin "Push now" button. Peers inject the bookmarks; nothing is withdrawn.
     */
    public void pushBookmarksNow() {
        sendBookmarks(userDirectory.localOnlineUsers());
    }

    /** Sends a bookmark-push (the given user set; empty = withdrawal) to every trusted, reachable peer. */
    private void sendBookmarks(Collection<UserDirectory.UserPresence> users) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getStatus() != PeerServer.Status.REACHABLE) continue;
            if (isUntrusted(peer.getDomain())) continue;   // never advertise our clients to an untrusted edge
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.bookmarkPush(
                              peer.getDomain(), users, localDomain, null));
            } catch (Exception e) {
                Log.warn("Failed to send bookmark-push to {}: {}", peer.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Injects an inbound {@code bookmark-push} into local users' bookmark storage and gossips it
     * onward to other trusted peers not already on the {@code via} trail (multi-hop, loop-guarded —
     * mirrors {@link #handleUserDirectory}). An empty list withdraws the origin's bookmarks.
     */
    public void handleBookmarkPush(String fromDomain, String originDomain,
                                   Collection<UserDirectory.UserPresence> users, String via) {
        // Trust gate (receive side): bookmark-push writes into EVERY local user's private storage.
        // We never send it across an untrusted edge and legitimate pushes never traverse one, so a
        // push arriving over an untrusted link can only be a hostile peer trying to inject bookmarks
        // into our users' clients — drop it before it touches storage.
        if (isUntrusted(fromDomain)) {
            Log.warn("SECURITY: dropping bookmark-push from untrusted peer {} — refusing to inject into local users", fromDomain);
            return;
        }
        bookmarkInjector.applyForOrigin(originDomain, users);
        for (PeerServer peer : peerRegistry.getPeers()) {
            if (peer.getDomain().equals(fromDomain)) continue;
            if (peer.getStatus() != PeerServer.Status.REACHABLE) continue;
            if (isUntrusted(peer.getDomain())) continue;
            if (via != null && via.contains(peer.getDomain())) continue;
            try {
                XMPPServer.getInstance().getPacketRouter()
                          .route(FederationStanzaFactory.bookmarkPush(
                              peer.getDomain(), users, originDomain, via));
            } catch (Exception e) {
                Log.warn("Failed to relay bookmark-push to {}: {}", peer.getDomain(), e.getMessage());
            }
        }
    }

    /**
     * Relays an outbound 1:1 message toward its destination domain over the overlay.  Called by the
     * packet interceptor once it has decided the message targets an overlay-reachable peer user.
     * Returns true if the message was handed to a next hop (the caller then suppresses native S2S),
     * false if there is no route (the caller leaves Openfire to handle it normally).
     */
    public boolean forwardDirectMessage(Message msg) {
        String destDomain  = msg.getTo().getDomain();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop     = routingTable.findNextHop(destDomain).orElse(null);
        if (nextHop == null) return false;
        try {
            Message copy = new Message(msg.getElement().createCopy());
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.directForward(nextHop, destDomain, localDomain, copy));
            Log.debug("direct-forward: 1:1 {} -> {} via {}", msg.getFrom(), msg.getTo(), nextHop);
            return true;
        } catch (Exception e) {
            Log.warn("Failed to forward 1:1 message to {}: {}", destDomain, e.getMessage());
            return false;
        }
    }

    /**
     * Relays an outbound 1:1 presence stanza (subscribe/subscribed/probe/directed presence) toward
     * its destination domain over the overlay.  Counterpart of {@link #forwardDirectMessage}; lets a
     * remote contact's subscription handshake and presence cross the overlay (incl. untrusted edges).
     * Returns true if handed to a next hop (caller suppresses native S2S), false if no route.
     */
    public boolean forwardDirectPresence(Presence pres) {
        String destDomain  = pres.getTo().getDomain();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop     = routingTable.findNextHop(destDomain).orElse(null);
        if (nextHop == null) return false;
        try {
            Presence copy = new Presence(pres.getElement().createCopy());
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.presenceForward(nextHop, destDomain, localDomain, copy));
            Log.debug("presence-forward: 1:1 {} {} -> {} via {}",
                      pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), pres.getTo(), nextHop);
            return true;
        } catch (Exception e) {
            Log.warn("Failed to forward 1:1 presence to {}: {}", destDomain, e.getMessage());
            return false;
        }
    }

    /**
     * Relays a user-addressed IQ (vCard/disco/caps/version/ping/PEP) toward its destination domain
     * over the overlay.  Counterpart of {@link #forwardDirectMessage}; lets profile/avatar/discovery
     * round-trips cross multi-hop links.  Returns true if handed to a next hop (caller suppresses
     * native S2S), false if no route.
     */
    public boolean forwardDirectIq(IQ iq) {
        String destDomain  = iq.getTo().getDomain();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String nextHop     = routingTable.findNextHop(destDomain).orElse(null);
        if (nextHop == null) return false;
        try {
            IQ copy = new IQ(iq.getElement().createCopy());
            XMPPServer.getInstance().getPacketRouter()
                      .route(FederationStanzaFactory.iqForward(nextHop, destDomain, localDomain, copy));
            Log.debug("iq-forward: 1:1 {} {} -> {} via {}", iq.getType(), iq.getFrom(), iq.getTo(), nextHop);
            return true;
        } catch (Exception e) {
            Log.warn("Failed to forward 1:1 IQ to {}: {}", destDomain, e.getMessage());
            return false;
        }
    }

    /**
     * Occupant roster of a local room for the admin "who's here" tracking view: real local occupants
     * (live presence from {@link MUCOccupant#getPresence()}) plus remote virtual occupants (cached
     * presence). Each entry: name, jid, kind (local|remote), show, status.
     */
    public List<Map<String, String>> getRoomOccupants(String localRoomJid) {
        List<Map<String, String>> out = new ArrayList<>();
        MUCRoom room = findLocalMucRoom(localRoomJid);
        if (room != null) {
            for (MUCOccupant occ : room.getOccupants()) {
                String show = "", status = "";
                Presence p = occ.getPresence();
                if (p != null) {
                    Element s  = p.getElement().element("show");
                    Element st = p.getElement().element("status");
                    if (s  != null) show   = s.getText();
                    if (st != null) status = st.getText();
                }
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", occ.getNickname());
                m.put("jid", occ.getUserAddress() != null ? occ.getUserAddress().toBareJID() : "");
                m.put("kind", "local");
                m.put("show", show);
                m.put("status", status);
                out.add(m);
            }
        }
        for (Map.Entry<String, String> e : roomManager.getVirtualOccupantPresence(localRoomJid).entrySet()) {
            String[] ps = e.getValue().split("\\|", 2);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", e.getKey());                 // MUC nick "user@home"
            m.put("jid", e.getKey());
            m.put("kind", "remote");
            m.put("show",   ps.length > 0 ? ps[0] : "");
            m.put("status", ps.length > 1 ? ps[1] : "");
            out.add(m);
        }
        return out;
    }

    /**
     * Forwards a local user's updated presence (availability/show/status change) to the peers mapped
     * to each federated room the user occupies.  Needed because a client changes status by sending a
     * BROADCAST presence (no {@code to}); Openfire updates MUC occupancy internally and never emits a
     * directed room/nick stanza, so the packet interceptor (which only forwards directed join/leave
     * presence) never sees the change.  Without this, an in-room status change only propagates on a
     * full roster re-sync (mapping/peer disable-enable).  Driven by the {@link PresenceEventListener}.
     */
    public void forwardLocalOccupantPresence(JID user, Presence presence) {
        if (user == null || user.getNode() == null) return;
        JID bareUser = user.asBareJID();
        for (String localRoomJid : roomManager.getLocalMappings().keySet()) {
            List<RoomMapping> mappings = roomManager.getMappingsForLocal(localRoomJid);   // ACTIVE only
            if (mappings.isEmpty()) continue;
            MUCRoom room = findLocalMucRoom(localRoomJid);
            if (room == null) continue;
            boolean present = room.getOccupants().stream().anyMatch(o ->
                    o.getUserAddress() != null && bareUser.equals(o.getUserAddress().asBareJID()));
            if (!present) continue;

            // Build the room presence the interceptor would have forwarded for a directed change.
            // injectPresence derives the remote nick from this 'from' (user@home), so a bare JID
            // updates the same virtual occupant created at join time.
            JID roomJid = new JID(localRoomJid);
            Presence fwd = new Presence();
            fwd.setFrom(bareUser);
            fwd.setTo(new JID(roomJid.getNode(), roomJid.getDomain(), bareUser.toString()));
            if (presence != null && presence.getType() == Presence.Type.unavailable) {
                fwd.setType(Presence.Type.unavailable);
            } else if (presence != null) {
                if (presence.getShow()   != null) fwd.setShow(presence.getShow());
                if (presence.getStatus() != null) fwd.setStatus(presence.getStatus());
            }
            for (RoomMapping m : mappings) {
                forwardMucPresence(fwd, m);
            }
        }
    }

    /** Wraps a room presence in a muc-forward toward a mapped peer (same path as the interceptor). */
    private void forwardMucPresence(Presence pres, RoomMapping mapping) {
        String localDomain  = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String remoteDomain = mapping.remoteDomain();
        String nextHop = routingTable.findNextHop(remoteDomain).orElse(remoteDomain);
        try {
            Presence copy = new Presence(pres.getElement().createCopy());
            XMPPServer.getInstance().getPacketRouter().route(
                FederationStanzaFactory.mucForward(
                    nextHop, remoteDomain, mapping.remoteRoomJid(), localDomain, localDomain, copy));
        } catch (Exception e) {
            Log.warn("Failed to forward occupant presence to {}: {}", remoteDomain, e.getMessage());
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
     * Per-room visibility ACL check for one outbound hop. The ACL semantics are:
     * <ul>
     *   <li>empty → visible to <b>nobody</b> (the default for a newly-federated room);</li>
     *   <li>contains the {@code "*"} sentinel → visible to <b>all</b> peers;</li>
     *   <li>otherwise → visible to the named servers and the path toward them.</li>
     * </ul>
     * For the named-servers case a room is sent/relayed to {@code peerDomain} when the ACL names the
     * peer directly, or the peer is the next hop toward some allowed destination — so the ad both
     * reaches multi-hop destinations and stays on the route toward them. An allowed destination with
     * no current route is simply pending until one appears.
     */
    private boolean roomVisibleAtHop(Set<String> visibleTo, String peerDomain) {
        if (visibleTo == null || visibleTo.isEmpty()) return false;                 // none
        if (visibleTo.contains(FederatedRoomManager.VISIBLE_ALL)) return true;      // all
        if (visibleTo.contains(peerDomain)) return true;
        for (String member : visibleTo) {
            if (routingTable.findNextHop(member).map(h -> h.equals(peerDomain)).orElse(false)) return true;
        }
        return false;
    }

    /** Untrusted-peer server filter AND per-room visibility ACL, for one destination peer. */
    private List<FederatedRoom> filterRoomsForHop(String toDomain, List<FederatedRoom> rooms) {
        return filterRoomsForPeer(toDomain, rooms).stream()
                .filter(r -> roomVisibleAtHop(r.visibleTo(), toDomain))
                .collect(Collectors.toList());
    }

    /**
     * Reciprocity gate for mapping: true iff our local room {@code localJid} is federation-enabled
     * AND its visibility ACL shares it with {@code destDomain}. A server may map one of its rooms to
     * a peer's room only when it has first exposed THAT room to the peer — you cannot consume a
     * peer's room without offering your own room to it in return.
     */
    public boolean roomSharedWith(String localJid, String destDomain) {
        if (destDomain == null || !roomManager.isFederated(localJid)) return false;
        Set<String> vis = roomManager.getRoomVisibility(localJid);
        if (vis == null || vis.isEmpty()) return false;
        return vis.contains(FederatedRoomManager.VISIBLE_ALL)
            || vis.contains(destDomain.toLowerCase());
    }

    /** Distinct routing-table destinations — the candidate servers for a room's visibility ACL. */
    public Set<String> routableServers() {
        Set<String> out = new LinkedHashSet<>();
        for (RouteEntry e : routingTable.getAll()) out.add(e.destination());
        return out;
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
            List<FederatedRoom> rooms = filterRoomsForHop(toDomain, entry.getValue());
            if (rooms.isEmpty()) continue;                // untrusted/not-visible: nothing for this peer
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
        List<FederatedRoom> rooms = filterRoomsForHop(toDomain, roomManager.getLocalFederatedRoomsWithDetails());
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
    public UserDirectory          getUserDirectory() { return userDirectory; }
    public BookmarkInjector       getBookmarkInjector() { return bookmarkInjector; }
}
