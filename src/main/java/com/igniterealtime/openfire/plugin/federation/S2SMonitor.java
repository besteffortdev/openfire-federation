package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.openfire.RoutingTable;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ConnectionSettings;
import org.jivesoftware.openfire.session.DomainPair;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Watches the S2S links to configured peers and drives the federation's timers: link up and down
 * detection, keepalives, reconnects with back-off, mapping probes and certificate pinning. It also
 * manages Openfire's server-wide S2S idle timeout while the plugin runs.
 */
public class S2SMonitor {

    private static final Logger Log = LoggerFactory.getLogger(S2SMonitor.class);
    /** Liveness poll interval; the check itself is a cheap in-memory lookup. */
    private static final int    POLL_SECONDS           = 10;
    /** How often each live peer's certificate is checked again; a link that just came up is checked at once. */
    private static final int    CERT_SWEEP_SECONDS     = 30;
    static final         String KEEPALIVE_JIVE_KEY     = "plugin.federation.keepaliveSeconds";
    static final         int    KEEPALIVE_DEFAULT       = 240;
    static final         int    KEEPALIVE_MINIMUM       = 30;

    /** Whether to disable Openfire's S2S idle reaper; see {@link #disableS2SIdleReaperIfRequested()}. */
    static final         String DISABLE_IDLE_JIVE_KEY  = "plugin.federation.disableS2SIdle";
    static final         boolean DISABLE_IDLE_DEFAULT  = true;

    /** Where the administrator's idle timeout is saved, persisted so a later stop can restore it. */
    static final         String SAVED_IDLE_JIVE_KEY    = "plugin.federation.savedS2SIdleMillis";
    /** Openfire's documented "never time out" value for xmpp.server.session.idle. */
    private static final long   IDLE_NEVER_MS          = -1L;

    /** The longest reconnect back-off; due peers are checked every {@link #RECONNECT_POLL_SECONDS}. */
    static final         String RECONNECT_JIVE_KEY     = "plugin.federation.reconnectSeconds";
    static final         int    RECONNECT_DEFAULT       = 30;
    static final         int    RECONNECT_MINIMUM       = 5;

    /** Mapping probe interval; 0 disables it. */
    static final         String MAPPING_PING_JIVE_KEY  = "plugin.federation.mappingPingSeconds";
    static final         int    MAPPING_PING_DEFAULT    = 30;
    static final         int    MAPPING_PING_MINIMUM    = 15;
    private static final int    RECONNECT_POLL_SECONDS  = 5;
    private static final int    RECONNECT_BACKOFF_BASE  = 5;

    private final PeerRegistry           peerRegistry;
    private final FederationRoutingTable routingTable;
    private final FederationManager      federationManager;

    private volatile ScheduledExecutorService scheduler;
    private ScheduledFuture<?>       keepaliveFuture;
    private ScheduledFuture<?>       reconnectFuture;
    private ScheduledFuture<?>       mappingPingFuture;
    /** The interval the keepalive task currently runs at, after clamping. */
    private volatile int            scheduledKeepaliveSeconds = -1;
    /** The interval the mapping-ping task currently runs at; 0 when disabled. */
    private volatile int            scheduledMappingPingSeconds = -1;
    /** When the last periodic certificate sweep ran, in epoch milliseconds. */
    private volatile long           lastCertSweepMs = 0L;

    /** Reconnect attempts per peer since it last went UNREACHABLE. */
    private final ConcurrentHashMap<String, Integer> peerRetryCount  = new ConcurrentHashMap<>();
    /** When each UNREACHABLE peer may next be retried, in epoch milliseconds. */
    private final ConcurrentHashMap<String, Long>    peerNextRetryAt = new ConcurrentHashMap<>();

    public S2SMonitor(PeerRegistry peerRegistry,
                      FederationRoutingTable routingTable,
                      FederationManager federationManager) {
        this.peerRegistry      = peerRegistry;
        this.routingTable      = routingTable;
        this.federationManager = federationManager;
    }

    /** Disables the S2S idle reaper if configured and schedules the poll, keepalive, reconnect and probe tasks. */
    public void start() {
        disableS2SIdleReaperIfRequested();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "federation-s2s-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::poll, 5, POLL_SECONDS, TimeUnit.SECONDS);
        rescheduleKeepalive();
        reconnectFuture = scheduler.scheduleAtFixedRate(
                this::sendReconnects, RECONNECT_POLL_SECONDS, RECONNECT_POLL_SECONDS, TimeUnit.SECONDS);
        rescheduleMappingPing();
        Log.info("S2SMonitor started (poll={}s, keepalive={}s, reconnect-poll={}s, reconnect-cap={}s, "
               + "mapping-ping={}s, s2s-idle={}s)",
                 POLL_SECONDS, getEffectiveKeepaliveSeconds(), RECONNECT_POLL_SECONDS, getReconnectSeconds(),
                 scheduledMappingPingSeconds, idleTimeoutSeconds());
    }

    /** Stops the timers and gives Openfire back its S2S idle timeout. */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        restoreS2SIdleReaper("the plugin is stopping");
    }

    /** Runs a one-shot task on the monitor's thread after {@code delayMs}; false when the monitor is not running. */
    boolean schedule(Runnable task, long delayMs) {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return false;
        try {
            s.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    Log.error("Scheduled federation task threw unexpectedly", e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public int getKeepaliveSeconds() {
        return JiveGlobals.getIntProperty(KEEPALIVE_JIVE_KEY, KEEPALIVE_DEFAULT);
    }

    /** Persists a new keepalive interval and reschedules the keepalive at once. */
    public void setKeepaliveSeconds(int seconds) {
        if (seconds < KEEPALIVE_MINIMUM) seconds = KEEPALIVE_MINIMUM;
        JiveGlobals.setProperty(KEEPALIVE_JIVE_KEY, String.valueOf(seconds));
        rescheduleKeepalive();
        Log.info("S2S keepalive interval updated to {}s (effective {}s)", seconds, scheduledKeepaliveSeconds);
    }

    /** Openfire's S2S idle timeout in seconds, or -1 if it is disabled or unreadable. */
    private int idleTimeoutSeconds() {
        try {
            Duration idle = ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.getValue();
            if (idle != null && !idle.isZero() && !idle.isNegative()) {
                return (int) idle.getSeconds();
            }
        } catch (RuntimeException e) {
            Log.debug("Could not read the S2S idle timeout", e);
        }
        return -1;
    }

    /**
     * Disables Openfire's server-wide S2S idle reaper unless {@value #DISABLE_IDLE_JIVE_KEY} is false.
     * S2S sockets are one-way, so an outgoing socket never reads and is reaped whatever keepalives it writes.
     */
    private void disableS2SIdleReaperIfRequested() {
        if (!JiveGlobals.getBooleanProperty(DISABLE_IDLE_JIVE_KEY, DISABLE_IDLE_DEFAULT)) {
            Log.info("S2S idle reaper left untouched ({}=false); note federation keepalives "
                     + "cannot keep one-way S2S sockets alive — expect periodic idle reconnects.",
                     DISABLE_IDLE_JIVE_KEY);
            // The setting may have been switched off while an earlier run's "never" is still in place.
            restoreS2SIdleReaper(DISABLE_IDLE_JIVE_KEY + " is false");
            return;
        }
        try {
            int current = idleTimeoutSeconds();
            if (current <= 0) {
                Log.debug("S2S idle reaper already disabled — nothing to do");
                return;
            }
            // Saved before it is overwritten, so a crash in between cannot lose it.
            JiveGlobals.setProperty(SAVED_IDLE_JIVE_KEY, Long.toString(current * 1000L));
            // -1 ms means "never"; zero could be read as an immediate timeout.
            ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.setValue(Duration.ofMillis(IDLE_NEVER_MS));
            Log.info("Disabled Openfire's S2S idle reaper (was {}s, set to -1=never) — federation "
                     + "manages liveness via poll/reconnect; one-way S2S sockets would otherwise be "
                     + "reaped every {}s. The previous value is restored when the plugin stops, or "
                     + "on the next start with {}=false.",
                     current, current, DISABLE_IDLE_JIVE_KEY);
        } catch (RuntimeException e) {
            Log.warn("Could not disable the S2S idle timeout", e);
        }
    }

    /**
     * Restores the S2S idle timeout this plugin replaced, but only while our "never" value is still
     * set, so an administrator's own later value is kept. The saved value is dropped either way.
     */
    private void restoreS2SIdleReaper(String why) {
        String saved = JiveGlobals.getProperty(SAVED_IDLE_JIVE_KEY);
        if (saved == null || saved.isBlank()) return;
        try {
            Duration current = ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.getValue();
            long currentMs = current == null ? IDLE_NEVER_MS : current.toMillis();
            if (currentMs != IDLE_NEVER_MS) {
                Log.info("Not restoring the S2S idle timeout ({}): it is now {}ms, which this plugin "
                       + "did not set — leaving the administrator's value alone.", why, currentMs);
            } else {
                long restored = Long.parseLong(saved.strip());
                ConnectionSettings.Server.IDLE_TIMEOUT_PROPERTY.setValue(Duration.ofMillis(restored));
                Log.info("Restored Openfire's S2S idle timeout to {}s ({}).", restored / 1000L, why);
            }
        } catch (RuntimeException e) {
            Log.warn("Could not restore the S2S idle timeout from '{}'", saved, e);
        } finally {
            JiveGlobals.deleteProperty(SAVED_IDLE_JIVE_KEY);
        }
    }

    /** The keepalive interval in use: the configured one, clamped to 80% of the S2S idle timeout when that is set. */
    public int getEffectiveKeepaliveSeconds() {
        int configured = getKeepaliveSeconds();
        int idleSec = idleTimeoutSeconds();
        if (idleSec > 0) {
            int safe = Math.max(KEEPALIVE_MINIMUM, (int) (idleSec * 0.8));
            if (configured > safe) return safe;
        }
        return configured;
    }

    /** Reschedules the keepalive task when its effective interval changed; cheap to call from every poll. */
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

    /** Persists a new back-off cap and resets every peer's back-off, so unreachable peers retry promptly. */
    public void setReconnectSeconds(int seconds) {
        if (seconds < RECONNECT_MINIMUM) seconds = RECONNECT_MINIMUM;
        JiveGlobals.setProperty(RECONNECT_JIVE_KEY, String.valueOf(seconds));
        peerRetryCount.clear();
        peerNextRetryAt.clear();
        Log.info("S2S reconnect back-off cap updated to {}s", seconds);
    }

    /** When the next reconnect attempt to {@code domain} is due, in epoch milliseconds; 0 if now. */
    public long getNextRetryAt(String domain) {
        return peerNextRetryAt.getOrDefault(domain, 0L);
    }

    public int getMappingPingSeconds() {
        return JiveGlobals.getIntProperty(MAPPING_PING_JIVE_KEY, MAPPING_PING_DEFAULT);
    }

    /** The probe interval in use after clamping to the minimum; 0 when the probe is off. */
    public int getEffectiveMappingPingSeconds() {
        int configured = getMappingPingSeconds();
        return configured <= 0 ? 0 : Math.max(configured, MAPPING_PING_MINIMUM);
    }

    /** Persists a new probe interval, where 0 disables it, and reschedules the probe at once. */
    public void setMappingPingSeconds(int seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds > 0 && seconds < MAPPING_PING_MINIMUM) seconds = MAPPING_PING_MINIMUM;
        JiveGlobals.setProperty(MAPPING_PING_JIVE_KEY, String.valueOf(seconds));
        rescheduleMappingPing();
        Log.info("Mapping-ping interval updated to {}s{}", seconds,
                 seconds == 0 ? " (end-to-end probe disabled)" : "");
    }

    /** Reschedules the mapping-ping task when its effective interval changed; 0 cancels it. */
    private synchronized void rescheduleMappingPing() {
        if (scheduler == null) return;
        int effective = getEffectiveMappingPingSeconds();
        if (effective == scheduledMappingPingSeconds) return;
        if (mappingPingFuture != null) mappingPingFuture.cancel(false);
        mappingPingFuture = null;
        if (effective > 0) {
            mappingPingFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    federationManager.getMappingProbe().sendPings();
                } catch (RuntimeException e) {
                    // An exception escaping a scheduled task cancels it for good (CERT TPS03-J).
                    Log.error("Mapping-ping task threw unexpectedly — task continues", e);
                }
            }, effective, effective, TimeUnit.SECONDS);
        }
        scheduledMappingPingSeconds = effective;
    }

    private void poll() {
        try {
            doPoll();
        } catch (RuntimeException e) {
            // An exception escaping a scheduled task cancels it for good (CERT TPS03-J).
            Log.error("S2S poll task threw unexpectedly — task continues", e);
        }
    }

    /** Updates each peer's status from its S2S link and handles the transitions up and down. */
    private void doPoll() {
        RoutingTable   openfireRoutes = XMPPServer.getInstance().getRoutingTable();
        SessionManager sm             = XMPPServer.getInstance().getSessionManager();
        String localDomain = localDomain();

        long now = System.currentTimeMillis();
        boolean certSweep = (now - lastCertSweepMs) >= CERT_SWEEP_SECONDS * 1000L;

        for (PeerServer peer : peerRegistry.getPeers()) {
            // Administrative and negotiated blocks are never polled. TRUST_MISMATCH in particular
            // cannot be overwritten by updateStatus, so polling it would re-run onPeerUp every tick.
            PeerServer.Status st = peer.getStatus();
            if (st == PeerServer.Status.WITHDRAWN
                    || st == PeerServer.Status.DISABLED
                    || st == PeerServer.Status.REMOTE_DISABLED
                    || st == PeerServer.Status.TRUST_MISMATCH) continue;

            String domain = peer.getDomain();
            boolean s2sUp;
            try {
                // hasServerRoute only sees outgoing sessions; a peer that dialled us has only an incoming one.
                s2sUp = openfireRoutes.hasServerRoute(new DomainPair(localDomain, domain))
                     || !sm.getIncomingServerSessions(domain).isEmpty();
            } catch (RuntimeException e) {
                Log.warn("Could not check the S2S route to {}; treating it as down", domain, e);
                s2sUp = false;
            }

            PeerServer.Status prev = peer.getStatus();
            PeerServer.Status next;
            if (s2sUp) {
                next = peer.isRemoteConfirmed() ? PeerServer.Status.REACHABLE : PeerServer.Status.PENDING;
            } else {
                next = PeerServer.Status.UNREACHABLE;
            }

            boolean prevUp = prev == PeerServer.Status.REACHABLE || prev == PeerServer.Status.PENDING;
            boolean nextUp = next == PeerServer.Status.REACHABLE || next == PeerServer.Status.PENDING;
            if (s2sUp && (!prevUp || certSweep)) {
                federationManager.getCertificatePinning().observe(domain);
            }

            if (prev == next) continue;

            peerRegistry.updateStatus(domain, next);

            if (nextUp && !prevUp) {
                if (next == PeerServer.Status.REACHABLE) {
                    onPeerUp(domain);
                } else {
                    // Until the remote announces us back, only announce ourselves.
                    Log.info("Federation peer link up, awaiting remote confirmation: {}", domain);
                    federationManager.sendPeerAnnounce(domain);
                }
            } else if (!nextUp && prevUp) {
                onPeerDown(domain);
            }
        }

        if (certSweep) lastCertSweepMs = now;

        rescheduleKeepalive();

        try {
            federationManager.getContactLists().tick();
        } catch (RuntimeException e) {
            Log.warn("Contact-list reconciliation failed", e);
        }
    }

    /** Sends a keepalive announce to every REACHABLE or PENDING peer. */
    private void sendKeepalives() {
        try {
            int count = 0;
            for (PeerServer peer : peerRegistry.getPeers()) {
                if (peer.getStatus() == PeerServer.Status.REACHABLE
                        || peer.getStatus() == PeerServer.Status.PENDING) {
                    federationManager.sendPeerAnnounce(peer.getDomain());
                    count++;
                }
            }
            Log.info("S2S keepalive pings sent to {} peer(s)", count);
        } catch (RuntimeException e) {
            Log.error("S2S keepalive task threw unexpectedly — task continues", e);
        }
    }

    /** Announces to each UNREACHABLE or PENDING peer whose back-off has elapsed, doubling its back-off. */
    private void sendReconnects() {
        try {
            long now   = System.currentTimeMillis();
            int  count = 0;
            for (PeerServer peer : peerRegistry.getPeers()) {
                if (peer.getStatus() != PeerServer.Status.UNREACHABLE
                        && peer.getStatus() != PeerServer.Status.PENDING) continue;
                String domain  = peer.getDomain();
                long   retryAt = peerNextRetryAt.getOrDefault(domain, 0L);
                if (now < retryAt) continue;

                federationManager.sendPeerAnnounce(domain);
                count++;

                int retries    = peerRetryCount.merge(domain, 1, Integer::sum);
                int shift      = Math.min(retries - 1, 10);
                int backoffSec = Math.min(RECONNECT_BACKOFF_BASE << shift, getReconnectSeconds());
                peerNextRetryAt.put(domain, now + backoffSec * 1000L);
                Log.debug("Reconnect attempt #{} to {} — next retry in {}s", retries, domain, backoffSec);
            }
            if (count > 0) Log.info("S2S reconnect attempts sent to {} UNREACHABLE peer(s)", count);
        } catch (RuntimeException e) {
            Log.error("S2S reconnect task threw unexpectedly — task continues", e);
        }
    }

    /**
     * A peer came up: adds its direct route, exchanges state both ways, re-syncs mapped rooms and
     * re-sends pending mapping requests.
     */
    private void onPeerUp(String domain) {
        Log.info("Federation peer UP: {}", domain);
        peerRetryCount.remove(domain);
        peerNextRetryAt.remove(domain);
        routingTable.addDirectPeer(domain);
        federationManager.sendFullGossip(domain);
        // Pulled as well as pushed: after a one-sided flap the peer would answer only with a keepalive.
        federationManager.solicitRouting(domain);
        federationManager.getOccupantSync().resyncMappedDestinations(Set.of(domain));
        federationManager.resendPendingRequests(domain);
    }

    private void onPeerDown(String domain) {
        Log.info("Federation peer DOWN: {}", domain);
        federationManager.dropRoutesThrough(domain);
    }
}
