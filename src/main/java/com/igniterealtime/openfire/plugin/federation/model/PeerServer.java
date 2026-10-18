package com.igniterealtime.openfire.plugin.federation.model;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A configured federation peer: its trust settings, certificate pin and live link status. The
 * status is kept current by {@code S2SMonitor} and the peer-announce exchange.
 */
public final class PeerServer {

    /** Link status shown to the admin and used to gate federation traffic. */
    public enum Status {
        /** Not polled yet. */
        UNKNOWN,
        /** S2S is up, but the remote plugin has not announced us as its peer yet. */
        PENDING,
        /** S2S is up and both plugins have announced each other. */
        REACHABLE,
        /** S2S is down. */
        UNREACHABLE,
        /** The remote sent peer-withdraw; it can be reconnected from here. */
        WITHDRAWN,
        /** Disabled by our admin; persisted and re-asserted to the peer. */
        DISABLED,
        /** Disabled by the remote admin; only the remote can lift it. */
        REMOTE_DISABLED,
        /** The two ends disagree on trust; blocked until they agree. Not persisted. */
        TRUST_MISMATCH
    }

    private final String domain;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.UNKNOWN);
    private final AtomicLong lastSeenMillis = new AtomicLong(0);

    /** An untrusted peer receives routes and rooms only for its {@link #exposedServers}. */
    private volatile boolean untrusted = false;

    /** Servers an untrusted peer may see and map onto; empty exposes nothing. */
    private final Set<String> exposedServers = ConcurrentHashMap.newKeySet();

    /** Whether shared contact lists may be sent to this untrusted peer. */
    private volatile boolean contactListsAllowed = false;

    /** Whether contact lists arriving from this untrusted peer are refused. */
    private volatile boolean contactListsRefused = false;

    /** Set once the remote plugin has announced us as its peer; in memory only. */
    private volatile boolean remoteConfirmed = false;

    /** Destinations whose route and room advertisements from this peer are refused; persisted. */
    private final Set<String> deniedRoutes = ConcurrentHashMap.newKeySet();

    /** The remote's declared trust stance from its last peer-announce; null until heard. */
    private volatile Boolean remoteUntrusted = null;

    /** Pinned fingerprint of the peer's S2S identity key (trust on first use); persisted. */
    private volatile String pinnedCertFp = null;

    /** Fingerprint seen most recently, pinned when the admin accepts a changed key. */
    private volatile String lastSeenCertFp = null;

    /** Set while the observed fingerprint differs from the pin; the peer is untrusted meanwhile. */
    private volatile boolean certMismatch = false;

    public PeerServer(String domain) {
        this.domain = domain;
    }

    public String getDomain() { return domain; }

    public boolean isUntrusted() { return untrusted; }

    public void setUntrusted(boolean untrusted) { this.untrusted = untrusted; }

    public Set<String> getExposedServers() { return exposedServers; }

    public boolean isContactListsAllowed() { return contactListsAllowed; }

    public void setContactListsAllowed(boolean allowed) { this.contactListsAllowed = allowed; }

    public boolean isContactListsRefused() { return contactListsRefused; }

    public void setContactListsRefused(boolean refused) { this.contactListsRefused = refused; }

    public void setExposedServers(Collection<String> servers) {
        exposedServers.clear();
        if (servers != null) {
            for (String s : servers) {
                if (s != null && !s.isBlank()) exposedServers.add(s.strip());
            }
        }
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

    /** Whether advertisements for {@code destination} from this peer are refused. */
    public boolean isRouteDenied(String destination) {
        return destination != null && deniedRoutes.contains(destination);
    }

    /** The remote's declared trust stance, or null if it has not announced one yet. */
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
