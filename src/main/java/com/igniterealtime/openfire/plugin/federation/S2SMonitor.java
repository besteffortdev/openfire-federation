package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.session.ConnectionSettings;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int    POLL_SECONDS           = 30;
    static final         String KEEPALIVE_JIVE_KEY     = "plugin.federation.keepaliveSeconds";
    static final         int    KEEPALIVE_DEFAULT       = 240;
    static final         int    KEEPALIVE_MINIMUM       = 30;

    // When true (default), the plugin disables Openfire's server-wide S2S idle reaper
    // on startup — see disableS2SIdleReaperIfRequested() for why a keepalive can't
    // substitute for it on Openfire's one-way S2S sockets.
    static final         String DISABLE_IDLE_JIVE_KEY  = "plugin.federation.disableS2SIdle";
    static final         boolean DISABLE_IDLE_DEFAULT  = true;

    // reconnectSeconds = back-off cap (max interval between retry attempts).
    // The scheduler always polls every RECONNECT_POLL_SECONDS; per-peer nextRetryAt
    // controls when each peer is actually retried.
    static final         String RECONNECT_JIVE_KEY     = "plugin.federation.reconnectSeconds";
    static final         int    RECONNECT_DEFAULT       = 30;
    static final         int    RECONNECT_MINIMUM       = 5;
    private static final int    RECONNECT_POLL_SECONDS  = 5;   // hard-coded poll interval
    private static final int    RECONNECT_BACKOFF_BASE  = 5;   // first retry delay in seconds

    private final PeerRegistry           peerRegistry;
    private final FederationRoutingTable routingTable;
    private final FederatedRoomManager   roomManager;
    private final FederationManager      federationManager;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>       keepaliveFuture;
    private ScheduledFuture<?>       reconnectFuture;
    /** The interval the keepalive task is currently scheduled at (effective, post-clamp). */
    private volatile int            scheduledKeepaliveSeconds = -1;

    /** How many reconnect attempts have been made per peer since it last went UNREACHABLE. */
    private final ConcurrentHashMap<String, Integer> peerRetryCount  = new ConcurrentHashMap<>();
    /** Epoch-ms after which the next reconnect attempt is allowed for each UNREACHABLE peer. */
    private final ConcurrentHashMap<String, Long>    peerNextRetryAt = new ConcurrentHashMap<>();

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
        // Stop Openfire from reaping our (write-only) outgoing S2S sockets as "idle"
        // before scheduling anything that relies on those sockets staying up.
        disableS2SIdleReaperIfRequested();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "federation-s2s-monitor");
            t.setDaemon(true);
            return t;
        });
        // First poll after 5 s, then every POLL_SECONDS.
        scheduler.scheduleAtFixedRate(this::poll, 5, POLL_SECONDS, TimeUnit.SECONDS);
        // Keepalive pings prevent idle S2S sessions from timing out between real messages.
        // Interval is clamped below Openfire's S2S idle timeout (see rescheduleKeepalive).
        rescheduleKeepalive();
        // Reconnect retries poll every RECONNECT_POLL_SECONDS; per-peer back-off controls
        // when each UNREACHABLE peer is actually retried (up to reconnectSeconds cap).
        reconnectFuture = scheduler.scheduleAtFixedRate(
                this::sendReconnects, RECONNECT_POLL_SECONDS, RECONNECT_POLL_SECONDS, TimeUnit.SECONDS);
        Log.info("S2SMonitor started (poll={}s, keepalive={}s, reconnect-poll={}s, reconnect-cap={}s, s2s-idle={}s)",
                 POLL_SECONDS, getEffectiveKeepaliveSeconds(), RECONNECT_POLL_SECONDS, getReconnectSeconds(),
                 idleTimeoutSeconds());
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
        rescheduleKeepalive();
        Log.info("S2S keepalive interval updated to {}s (effective {}s)", seconds, scheduledKeepaliveSeconds);
    }

    /**
     * Openfire's S2S idle-disconnect timeout in seconds, or -1 if disabled/unknown.
     * This is the timeout that produces the peer's "Connection has been idle" stream
     * error; the keepalive must fire comfortably inside it.
     */
    private int idleTimeoutSeconds() {
        try {
            Duration idle = ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.getValue();
            if (idle != null && !idle.isZero() && !idle.isNegative()) {
                return (int) idle.getSeconds();
            }
        } catch (Exception e) {
            Log.debug("Could not read S2S idle timeout: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * Disables Openfire's S2S idle reaper (xmpp.server.idle) on startup, unless the admin
     * opted out via {@value #DISABLE_IDLE_JIVE_KEY}=false.
     *
     * Why: Openfire reaps an S2S socket that has received no INBOUND bytes for the idle
     * timeout.  XMPP S2S (RFC 6120) uses a separate one-way socket per direction, and
     * Openfire 5.x implements no XEP-0288 bidi to merge them — so our outgoing session to
     * a peer only ever writes (the peer's replies come back on its own separate socket).
     * It therefore reads nothing after the handshake and is force-closed at EXACTLY the
     * idle timeout no matter how often the keepalive writes to it.  Federation already
     * owns liveness (30s poll + reconnect back-off), so the reaper is pure churn here.
     *
     * NOTE: this is a SERVER-WIDE setting — it affects every S2S connection on this server,
     * not just federation peers.
     */
    private void disableS2SIdleReaperIfRequested() {
        if (!JiveGlobals.getBooleanProperty(DISABLE_IDLE_JIVE_KEY, DISABLE_IDLE_DEFAULT)) {
            Log.info("S2S idle reaper left untouched ({}=false); note federation keepalives "
                     + "cannot keep one-way S2S sockets alive — expect periodic idle reconnects.",
                     DISABLE_IDLE_JIVE_KEY);
            return;
        }
        try {
            int current = idleTimeoutSeconds();
            if (current <= 0) {
                Log.debug("S2S idle reaper already disabled — nothing to do");
                return;
            }
            // -1ms is Openfire's documented "never time out" sentinel for
            // xmpp.server.session.idle (it is also the property's minimum value).
            // Do NOT use Duration.ZERO — 0ms can be read as an immediate-timeout.
            ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.setValue(Duration.ofMillis(-1));
            Log.info("Disabled Openfire's S2S idle reaper (was {}s, set to -1=never) — federation "
                     + "manages liveness via poll/reconnect; one-way S2S sockets would otherwise be "
                     + "reaped every {}s. Set {}=false to restore Openfire's timeout.",
                     current, current, DISABLE_IDLE_JIVE_KEY);
        } catch (Exception e) {
            Log.warn("Could not disable S2S idle timeout: {}", e.getMessage());
        }
    }

    /**
     * The keepalive interval actually used for scheduling: the configured value,
     * clamped to 80% of Openfire's S2S idle timeout so a ping always lands before the
     * peer closes the link.  Returns the configured value when the idle timeout is
     * disabled, unreadable, or already comfortably larger.
     */
    public int getEffectiveKeepaliveSeconds() {
        int configured = getKeepaliveSeconds();
        int idleSec = idleTimeoutSeconds();
        if (idleSec > 0) {
            int safe = Math.max(KEEPALIVE_MINIMUM, (int) (idleSec * 0.8));
            if (configured > safe) return safe;
        }
        return configured;
    }

    /**
     * (Re)schedules the keepalive task at the current effective interval.  Idempotent —
     * does nothing if the effective interval is unchanged, so it is safe to call from
     * the poll loop to adapt when the admin changes the S2S idle timeout at runtime.
     */
    private synchronized void rescheduleKeepalive() {
        if (scheduler == null) return;
        int effective = getEffectiveKeepaliveSeconds();
        if (effective == scheduledKeepaliveSeconds) return;
        if (keepaliveFuture != null) keepaliveFuture.cancel(false);
        keepaliveFuture = scheduler.scheduleAtFixedRate(
                this::sendKeepalives, effective, effective, TimeUnit.SECONDS);
        scheduledKeepaliveSeconds = effective;
        int configured = getKeepaliveSeconds();
        if (effective != configured) {
            Log.info("S2S keepalive every {}s (configured {}s, clamped below the S2S idle timeout of {}s)",
                     effective, configured, idleTimeoutSeconds());
        } else {
            Log.debug("S2S keepalive every {}s", effective);
        }
    }

    public int getReconnectSeconds() {
        return JiveGlobals.getIntProperty(RECONNECT_JIVE_KEY, RECONNECT_DEFAULT);
    }

    /** Persists the new back-off cap and resets all per-peer back-off state. */
    public void setReconnectSeconds(int seconds) {
        if (seconds < RECONNECT_MINIMUM) seconds = RECONNECT_MINIMUM;
        JiveGlobals.setProperty(RECONNECT_JIVE_KEY, String.valueOf(seconds));
        // Clear back-off state so UNREACHABLE peers retry promptly with the new cap.
        peerRetryCount.clear();
        peerNextRetryAt.clear();
        Log.info("S2S reconnect back-off cap updated to {}s", seconds);
    }

    /** Epoch-ms when the next reconnect attempt is scheduled for this domain, or 0 if imminent. */
    public long getNextRetryAt(String domain) {
        return peerNextRetryAt.getOrDefault(domain, 0L);
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
            // DISABLED / REMOTE_DISABLED are administrative blocks — never auto-poll them.
            PeerServer.Status st = peer.getStatus();
            if (st == PeerServer.Status.WITHDRAWN
                    || st == PeerServer.Status.DISABLED
                    || st == PeerServer.Status.REMOTE_DISABLED) continue;

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

        // Adapt the keepalive cadence if the admin changed the S2S idle timeout at runtime.
        rescheduleKeepalive();
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
            Log.error("S2S keepalive task threw unexpectedly — task continues", e);
        }
    }

    private void sendReconnects() {
        try {
            long now   = System.currentTimeMillis();
            int  count = 0;
            for (PeerServer peer : peerRegistry.getPeers()) {
                if (peer.getStatus() != PeerServer.Status.UNREACHABLE) continue;
                String domain  = peer.getDomain();
                long   retryAt = peerNextRetryAt.getOrDefault(domain, 0L);
                if (now < retryAt) continue;   // back-off not yet elapsed

                federationManager.sendPeerAnnounce(domain);
                count++;

                // Compute next back-off: 5s * 2^(retries-1), capped at reconnectSeconds.
                int retries    = peerRetryCount.merge(domain, 1, Integer::sum);
                int shift      = Math.min(retries - 1, 10);
                int backoffSec = Math.min(RECONNECT_BACKOFF_BASE << shift, getReconnectSeconds());
                peerNextRetryAt.put(domain, now + backoffSec * 1000L);
                Log.debug("Reconnect attempt #{} to {} — next retry in {}s", retries, domain, backoffSec);
            }
            if (count > 0) Log.info("S2S reconnect attempts sent to {} UNREACHABLE peer(s)", count);
        } catch (Exception e) {
            Log.error("S2S reconnect task threw unexpectedly — task continues", e);
        }
    }

    private void onPeerUp(String domain) {
        Log.info("Federation peer UP: {}", domain);
        peerRetryCount.remove(domain);
        peerNextRetryAt.remove(domain);
        routingTable.addDirectPeer(domain);
        // Send our full state (peer-announce + routing table + room list) to the new peer.
        federationManager.sendFullGossip(domain);
        // PULL the peer's state too.  sendFullGossip only pushes ours; if the link flapped
        // asymmetrically (the peer never saw us drop), the peer treats our peer-announce as a
        // steady-state keepalive and replies WITHOUT a routing-update — so we would never
        // re-learn the destinations reachable through it, and would never re-advertise them
        // onward.  A solicit forces the peer to re-send its routing table + room cache.
        federationManager.solicitRouting(domain);
        // Re-sync occupant rosters for any room mapped to this peer so users that
        // were already in those rooms become visible again without rejoining.
        federationManager.resyncMappedDestinations(Set.of(domain));
    }

    private void onPeerDown(String domain) {
        Log.info("Federation peer DOWN: {}", domain);
        Set<String> removed = routingTable.removePeer(domain);
        // Evict ghost occupants and drop cached rooms for the peer AND every
        // destination that was only reachable through it, then gossip the routing
        // withdrawal so downstream servers converge and clean up the same way.
        federationManager.handleUnreachableDestinations(removed.isEmpty() ? Set.of(domain) : removed);
        if (!removed.isEmpty()) {
            federationManager.propagateTopologyChange(domain);
        }
    }
}
