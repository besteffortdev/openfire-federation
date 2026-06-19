package com.igniterealtime.openfire.plugin.federation.model;

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
     */
    public enum Status { UNKNOWN, REACHABLE, UNREACHABLE, WITHDRAWN, DISABLED, REMOTE_DISABLED }

    private final String domain;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNKNOWN);
    private final AtomicLong lastSeenMillis = new AtomicLong(0);

    public PeerServer(String domain) {
        this.domain = domain;
    }

    public String getDomain() { return domain; }

    public Status getStatus() { return status.get(); }

    public void setStatus(Status s) { status.set(s); }

    public long getLastSeenMillis() { return lastSeenMillis.get(); }

    public void markSeen() { lastSeenMillis.set(System.currentTimeMillis()); }

    @Override
    public String toString() {
        return "PeerServer{domain='" + domain + "', status=" + status.get() + '}';
    }
}
