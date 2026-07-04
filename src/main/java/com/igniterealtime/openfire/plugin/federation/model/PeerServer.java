package com.igniterealtime.openfire.plugin.federation.model;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a configured federation peer and its current S2S connection status.
 * Status is updated by S2SMonitor on each polling cycle.
 */
public final class PeerServer {

    /**
     * UNKNOWN/REACHABLE/UNREACHABLE — live S2S state maintained by S2SMonitor.
     * PENDING — the S2S link is up but the remote's federation plugin has not confirmed us
     *            as a peer yet (no peer-announce received). Shown until the remote adds us
     *            back; no routes or gossip flow until it does.
     * WITHDRAWN — the remote sent peer-withdraw; reconnectable locally.
     * DISABLED — WE administratively disabled this peer; persistent, re-enableable
     *            locally, and re-asserted to the peer even if it re-creates the link.
     * REMOTE_DISABLED — the remote administratively disabled the link; persistent and
     *            NOT re-enableable locally (only the remote can lift it).
     * TRUST_MISMATCH — the two ends disagree on trust (one marked the link untrusted, the
     *            other trusted). The link is blocked (no federation) until both ends match;
     *            derived from peer-announce negotiation, not persisted, auto-clears on match.
     */
    public enum Status { UNKNOWN, PENDING, REACHABLE, UNREACHABLE, WITHDRAWN, DISABLED, REMOTE_DISABLED, TRUST_MISMATCH }

    private final String domain;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNKNOWN);
    private final AtomicLong lastSeenMillis = new AtomicLong(0);

    /**
     * Untrusted peers (e.g. a foreign organisation reached only through an edge server)
     * receive NO routing updates and NO room advertisements by default — only the servers
     * the admin has explicitly placed in {@link #exposedServers}.  Exposing a server means
     * the peer sees that server's federated rooms (and a route to reach it); local rooms are
     * exposed by listing our own domain.  Inbound mapping/forward from an untrusted peer is
     * likewise gated to those servers.  Default false = a fully-trusted peer.
     */
    private volatile boolean untrusted = false;

    /** Server domains an untrusted peer is allowed to see/map onto (empty = expose nothing). */
    private final Set<String> exposedServers = ConcurrentHashMap.newKeySet();

    /**
     * True once we've received a peer-announce from this peer — proof the remote's federation
     * plugin knows us as a peer (mutual add). Until then a live S2S link shows PENDING, not
     * REACHABLE. In-memory only: after a restart the first keepalive/announce re-confirms.
     */
    private volatile boolean remoteConfirmed = false;

    /**
     * Destination server domains whose route/room advertisements from THIS peer the admin has
     * denied. A denied destination is never installed in the routing table when advertised by
     * this peer (an alternate path via another peer is unaffected). Persisted.
     */
    private final Set<String> deniedRoutes = ConcurrentHashMap.newKeySet();

    /**
     * The remote end's last-declared trust stance, learned from its peer-announce
     * ({@code untrusted="true|false"}). null until we've heard from it. Trust is a property
     * of the LINK: if this disagrees with our own {@link #untrusted}, the link is blocked
     * (TRUST_MISMATCH) until both ends match.
     */
    private volatile Boolean remoteUntrusted = null;

    /**
     * SHA-256 (hex) of the top-of-chain certificate this peer presented on the S2S link, pinned
     * on first sighting (TOFU). null = not yet pinned. A later observed cert that differs from
     * this pin is treated as possible impersonation — see {@link #certMismatch}. Persisted.
     */
    private volatile String pinnedCertFp = null;

    /** The most recently observed top-of-chain cert fingerprint, used to (re)pin on admin accept. */
    private volatile String lastSeenCertFp = null;

    /**
     * True when the last observed S2S cert fingerprint differs from {@link #pinnedCertFp}.
     * Transient — re-derived each observation cycle, never persisted. While set, the peer is
     * auto-untrusted and the admin is alerted until they accept the new cert.
     */
    private volatile boolean certMismatch = false;

    public PeerServer(String domain) {
        this.domain = domain;
    }

    public String getDomain() { return domain; }

    public boolean isUntrusted() { return untrusted; }

    public void setUntrusted(boolean untrusted) { this.untrusted = untrusted; }

    public Set<String> getExposedServers() { return exposedServers; }

    public void setExposedServers(Collection<String> servers) {
        exposedServers.clear();
        if (servers != null) {
            for (String s : servers) {
                if (s != null && !s.isBlank()) exposedServers.add(s.strip());
            }
        }
    }

    /** True if {@code serverDomain} is on this peer's exposed list. */
    public boolean exposesServer(String serverDomain) {
        return serverDomain != null && exposedServers.contains(serverDomain);
    }

    public boolean isRemoteConfirmed() { return remoteConfirmed; }

    public void setRemoteConfirmed(boolean confirmed) { this.remoteConfirmed = confirmed; }

    public Set<String> getDeniedRoutes() { return deniedRoutes; }

    public void setDeniedRoutes(Collection<String> routes) {
        deniedRoutes.clear();
        if (routes != null) {
            for (String r : routes) {
                if (r != null && !r.isBlank()) deniedRoutes.add(r.strip());
            }
        }
    }

    /** True if the admin denied advertisements for {@code destination} arriving from this peer. */
    public boolean isRouteDenied(String destination) {
        return destination != null && deniedRoutes.contains(destination);
    }

    /** The remote's last-declared trust stance, or null if not yet heard. */
    public Boolean getRemoteUntrusted() { return remoteUntrusted; }

    public void setRemoteUntrusted(boolean remoteUntrusted) { this.remoteUntrusted = remoteUntrusted; }

    public String getPinnedCertFp() { return pinnedCertFp; }

    public void setPinnedCertFp(String fp) { this.pinnedCertFp = fp; }

    public String getLastSeenCertFp() { return lastSeenCertFp; }

    public void setLastSeenCertFp(String fp) { this.lastSeenCertFp = fp; }

    public boolean isCertMismatch() { return certMismatch; }

    public void setCertMismatch(boolean certMismatch) { this.certMismatch = certMismatch; }

    public Status getStatus() { return status.get(); }

    public void setStatus(Status s) { status.set(s); }

    public long getLastSeenMillis() { return lastSeenMillis.get(); }

    public void markSeen() { lastSeenMillis.set(System.currentTimeMillis()); }

    @Override
    public String toString() {
        return "PeerServer{domain='" + domain + "', status=" + status.get() + '}';
    }
}
