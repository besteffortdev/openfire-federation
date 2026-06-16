package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls the Openfire routing table every POLL_SECONDS to detect changes in
 * S2S connectivity for configured federation peers.
 *
 * On UNKNOWN → REACHABLE: adds a direct route and triggers gossip exchange.
 * On REACHABLE → UNREACHABLE: purges routes learned through that peer,
 *                              clears remote room advertisements from it.
 */
public class S2SMonitor {

    private static final Logger Log = LoggerFactory.getLogger(S2SMonitor.class);
    private static final int    POLL_SECONDS         = 30;
    static final         String KEEPALIVE_JIVE_KEY   = "plugin.federation.keepaliveSeconds";
    static final         int    KEEPALIVE_DEFAULT     = 240;
    static final         int    KEEPALIVE_MINIMUM     = 30;

    private final PeerRegistry           peerRegistry;
    private final FederationRoutingTable routingTable;
    private final FederatedRoomManager   roomManager;
    private final FederationManager      federationManager;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>       keepaliveFuture;

    public S2SMonitor(PeerRegistry peerRegistry,
                      FederationRoutingTable routingTable,
                      FederatedRoomManager roomManager,
                      FederationManager federationManager) {
        this.peerRegistry      = peerRegistry;
        this.routingTable      = routingTable;
        this.roomManager       = roomManager;
        this.federationManager = federationManager;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "federation-s2s-monitor");
            t.setDaemon(true);
            return t;
        });
        // First poll after 5 s, then every POLL_SECONDS.
        scheduler.scheduleAtFixedRate(this::poll, 5, POLL_SECONDS, TimeUnit.SECONDS);
        // Keepalive pings prevent idle S2S sessions from timing out between real messages.
        int keepaliveSec = getKeepaliveSeconds();
        keepaliveFuture = scheduler.scheduleAtFixedRate(
                this::sendKeepalives, keepaliveSec, keepaliveSec, TimeUnit.SECONDS);
        Log.info("S2SMonitor started (poll={}s, keepalive={}s)", POLL_SECONDS, keepaliveSec);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public int getKeepaliveSeconds() {
        return JiveGlobals.getIntProperty(KEEPALIVE_JIVE_KEY, KEEPALIVE_DEFAULT);
    }

    /** Persists the new interval and reschedules the keepalive task immediately. */
    public void setKeepaliveSeconds(int seconds) {
        if (seconds < KEEPALIVE_MINIMUM) seconds = KEEPALIVE_MINIMUM;
        JiveGlobals.setProperty(KEEPALIVE_JIVE_KEY, String.valueOf(seconds));
        if (keepaliveFuture != null) {
            keepaliveFuture.cancel(false);
        }
        keepaliveFuture = scheduler.scheduleAtFixedRate(
                this::sendKeepalives, seconds, seconds, TimeUnit.SECONDS);
        Log.info("S2S keepalive interval updated to {}s", seconds);
    }

    private void poll() {
        try {
            doPoll();
        } catch (Exception e) {
            // ScheduledExecutorService cancels the task permanently on uncaught exception.
            // Catch everything here so a transient error can't kill the poll loop.
            Log.error("S2S poll task threw unexpectedly — task continues", e);
        }
    }

    private void doPoll() {
        RoutingTable   openfireRoutes = XMPPServer.getInstance().getRoutingTable();
        SessionManager sm             = XMPPServer.getInstance().getSessionManager();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        for (PeerServer peer : peerRegistry.getPeers()) {
            // WITHDRAWN peers intentionally disconnected — skip until admin reconnects.
            if (peer.getStatus() == PeerServer.Status.WITHDRAWN) continue;

            String domain = peer.getDomain();
            boolean s2sUp;
            try {
                // hasServerRoute only tracks outgoing sessions; also check incoming so
                // that auto-discovered peers (where the remote side initiated S2S) are
                // not incorrectly flipped to UNREACHABLE on the next poll cycle.
                s2sUp = openfireRoutes.hasServerRoute(new DomainPair(localDomain, domain))
                     || !sm.getIncomingServerSessions(domain).isEmpty();
            } catch (Exception e) {
                Log.warn("Error checking S2S route for {}: {}", domain, e.getMessage());
                s2sUp = false;
            }

            PeerServer.Status prev = peer.getStatus();
            PeerServer.Status next = s2sUp ? PeerServer.Status.REACHABLE : PeerServer.Status.UNREACHABLE;

            if (prev == next) continue;   // no transition — nothing to do

            peerRegistry.updateStatus(domain, next);

            if (next == PeerServer.Status.REACHABLE) {
                onPeerUp(domain);
            } else {
                onPeerDown(domain);
            }
        }
    }

    private void sendKeepalives() {
        try {
            int count = 0;
            for (PeerServer peer : peerRegistry.getPeers()) {
                if (peer.getStatus() == PeerServer.Status.REACHABLE) {
                    federationManager.sendPeerAnnounce(peer.getDomain());
                    count++;
                }
            }
            Log.info("S2S keepalive pings sent to {} peer(s)", count);
        } catch (Exception e) {
            // Same ScheduledExecutorService contract — never let exceptions escape.
            Log.error("S2S keepalive task threw unexpectedly — task continues", e);
        }
    }

    private void onPeerUp(String domain) {
        Log.info("Federation peer UP: {}", domain);
        routingTable.addDirectPeer(domain);
        // Send our full state (peer-announce + routing table + room list) to the new peer.
        federationManager.sendFullGossip(domain);
    }

    private void onPeerDown(String domain) {
        Log.info("Federation peer DOWN: {}", domain);
        routingTable.removePeer(domain);
        roomManager.clearRemoteRooms(domain);
    }
}
