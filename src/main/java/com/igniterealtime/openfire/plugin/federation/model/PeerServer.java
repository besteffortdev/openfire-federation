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
     * WITHDRAWN — the remote sent peer-withdraw; reconnectable locally.
     * DISABLED — WE administratively disabled this peer; persistent, re-enableable
     *            locally, and re-asserted to the peer even if it re-creates the link.
     * REMOTE_DISABLED — the remote administratively disabled the link; persistent and
     *            NOT re-enableable locally (only the remote can lift it).
     * TRUST_MISMATCH — the two ends disagree on trust (one marked the link untrusted, the
     *            other trusted). The link is blocked (no federation) until both ends match;
     *            derived from peer-announce negotiation, not persisted, auto-clears on match.
     */
    public enum Status { UNKNOWN, REACHABLE, UNREACHABLE, WITHDRAWN, DISABLED, REMOTE_DISABLED, TRUST_MISMATCH }

    private final String domain;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNKNOWN);
    private final AtomicLong lastSeenMillis = new AtomicLong(0);

    /**
     * Untrusted peers (e.g. a foreign organisation reached only through an edge server)
     * receive NO routing updates and NO room advertisements by default — only the rooms
     * the admin has explicitly placed in {@link #exposedRooms}, and only routes to those
     * rooms' hosting servers.  Inbound mapping/forward from an untrusted peer is likewise
     * gated to the exposed set.  Default false = a fully-trusted peer.
     */
    private volatile boolean untrusted = false;

    /** Room JIDs an untrusted peer is allowed to see/map (empty = expose nothing). */
    private final Set<String> exposedRooms = ConcurrentHashMap.newKeySet();

    /**
     * The remote end's last-declared trust stance, learned from its peer-announce
     * ({@code untrusted="true|false"}). null until we've heard from it. Trust is a property
     * of the LINK: if this disagrees with our own {@link #untrusted}, the link is blocked
     * (TRUST_MISMATCH) until both ends match.
     */
    private volatile Boolean remoteUntrusted = null;

    public PeerServer(String domain) {
        this.domain = domain;
    }

    public String getDomain() { return domain; }

    public boolean isUntrusted() { return untrusted; }

    public void setUntrusted(boolean untrusted) { this.untrusted = untrusted; }

    public Set<String> getExposedRooms() { return exposedRooms; }

    public void setExposedRooms(Collection<String> rooms) {
        exposedRooms.clear();
        if (rooms != null) {
            for (String r : rooms) {
                if (r != null && !r.isBlank()) exposedRooms.add(r.strip());
            }
        }
    }

    /** True if {@code roomJid} is on this peer's exposed list. */
    public boolean exposes(String roomJid) {
        return roomJid != null && exposedRooms.contains(roomJid);
    }

    /** The remote's last-declared trust stance, or null if not yet heard. */
    public Boolean getRemoteUntrusted() { return remoteUntrusted; }

    public void setRemoteUntrusted(boolean remoteUntrusted) { this.remoteUntrusted = remoteUntrusted; }

    public Status getStatus() { return status.get(); }

    public void setStatus(Status s) { status.set(s); }

    public long getLastSeenMillis() { return lastSeenMillis.get(); }

    public void markSeen() { lastSeenMillis.set(System.currentTimeMillis()); }

    @Override
    public String toString() {
        return "PeerServer{domain='" + domain + "', status=" + status.get() + '}';
    }
}
