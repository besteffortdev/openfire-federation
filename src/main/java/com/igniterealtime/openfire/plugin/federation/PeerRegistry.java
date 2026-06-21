package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of truth for the set of configured federation peers and their live
 * S2S connection status.  The peer list is persisted via JiveGlobals so it
 * survives plugin restarts; status is ephemeral and rebuilt by S2SMonitor.
 */
public class PeerRegistry {

    private static final Logger Log = LoggerFactory.getLogger(PeerRegistry.class);
    private static final String PROP_PEERS      = "federation.peers";
    private static final String PROP_PEER_STATE = "federation.peer.state.";   // + domain → DISABLED|REMOTE_DISABLED
    private static final String PROP_APPROVED      = "federation.peers.approved";       // CSV of admin-approved domains
    private static final String PROP_APPROVED_INIT = "federation.peers.approved.init";  // one-time grandfather marker

    private final ConcurrentHashMap<String, PeerServer> peers = new ConcurrentHashMap<>();

    /**
     * Domains the local admin has explicitly approved as federation peers. Only consulted
     * when the opt-in allowlist (plugin.federation.peerAllowlist) is enabled; in the default
     * open mode any peer is accepted and this set is irrelevant.
     */
    private final Set<String> approved = ConcurrentHashMap.newKeySet();

    /** Called once by FederationManager.start() — loads persisted peers. */
    public void load() {
        String stored = JiveGlobals.getProperty(PROP_PEERS, "").strip();
        if (!stored.isEmpty()) {
            for (String domain : stored.split(",")) {
                domain = domain.strip();
                if (!domain.isEmpty()) {
                    PeerServer peer = new PeerServer(domain);
                    // Restore a persisted control status (DISABLED / REMOTE_DISABLED) so the
                    // block survives restarts and the peer re-adding the connection.
                    String state = JiveGlobals.getProperty(PROP_PEER_STATE + domain, "").strip();
                    if (!state.isEmpty()) {
                        try {
                            PeerServer.Status s = PeerServer.Status.valueOf(state);
                            if (s == PeerServer.Status.DISABLED || s == PeerServer.Status.REMOTE_DISABLED) {
                                peer.setStatus(s);
                                Log.info("Loaded federation peer {} with persisted status {}", domain, s);
                            }
                        } catch (IllegalArgumentException ignored) {}
                    } else {
                        Log.info("Loaded configured federation peer: {}", domain);
                    }
                    peers.put(domain, peer);
                }
            }
        }
        loadApproved();
    }

    /**
     * Loads the admin-approved set. On first run after upgrade (no init marker) it grandfathers
     * every currently-configured peer as approved, so turning the allowlist on later never drops
     * the existing mesh — the admin only has to manage NEW peers from then on.
     */
    private void loadApproved() {
        if (JiveGlobals.getBooleanProperty(PROP_APPROVED_INIT, false)) {
            String stored = JiveGlobals.getProperty(PROP_APPROVED, "").strip();
            for (String d : stored.split(",")) {
                d = d.strip();
                if (!d.isEmpty()) approved.add(d);
            }
        } else {
            approved.addAll(peers.keySet());   // grandfather the current peer set, once
            JiveGlobals.setProperty(PROP_APPROVED_INIT, "true");
            persistApproved();
            if (!approved.isEmpty()) Log.info("Initialised federation peer allowlist with {} existing peer(s)", approved.size());
        }
    }

    /** Marks a domain as an admin-approved peer (the opt-in allowlist). */
    public void approve(String domain) {
        if (approved.add(domain)) persistApproved();
    }

    /** Revokes a domain's approval. */
    public void unapprove(String domain) {
        if (approved.remove(domain)) persistApproved();
    }

    /** Whether a domain is on the admin-approved allowlist. */
    public boolean isApproved(String domain) {
        return approved.contains(domain);
    }

    private void persistApproved() {
        JiveGlobals.setProperty(PROP_APPROVED, String.join(",", approved));
    }

    /**
     * Adds a peer domain to the registry and persists the list.
     * Silently ignored if the domain is already present.
     */
    public PeerServer addPeer(String domain) {
        PeerServer peer = peers.computeIfAbsent(domain, PeerServer::new);
        persist();
        return peer;
    }

    /**
     * Removes a peer domain and persists the updated list.
     * Returns true if the peer was present.
     */
    public boolean removePeer(String domain) {
        boolean removed = peers.remove(domain) != null;
        if (removed) {
            JiveGlobals.deleteProperty(PROP_PEER_STATE + domain);
            persist();
        }
        return removed;
    }

    public Collection<PeerServer> getPeers() {
        return Collections.unmodifiableCollection(peers.values());
    }

    public Optional<PeerServer> getPeer(String domain) {
        return Optional.ofNullable(peers.get(domain));
    }

    public boolean contains(String domain) {
        return peers.containsKey(domain);
    }

    /**
     * Called by S2SMonitor to update live connection state.  Sticky control statuses
     * (DISABLED / REMOTE_DISABLED) are never overwritten by live updates — they change
     * only through {@link #setControlStatus}.
     */
    public void updateStatus(String domain, PeerServer.Status status) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        PeerServer.Status cur = peer.getStatus();
        if (cur == PeerServer.Status.DISABLED || cur == PeerServer.Status.REMOTE_DISABLED) return;
        peer.setStatus(status);
        if (status == PeerServer.Status.REACHABLE) {
            peer.markSeen();
        }
    }

    /**
     * Sets a control status set by an administrative action (disable/enable) rather than
     * by live polling.  Persists DISABLED / REMOTE_DISABLED so the state survives restarts
     * and the peer re-creating the connection; clears the persisted state otherwise.
     */
    public void setControlStatus(String domain, PeerServer.Status status) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setStatus(status);
        if (status == PeerServer.Status.DISABLED || status == PeerServer.Status.REMOTE_DISABLED) {
            JiveGlobals.setProperty(PROP_PEER_STATE + domain, status.name());
        } else {
            JiveGlobals.deleteProperty(PROP_PEER_STATE + domain);
        }
    }

    private void persist() {
        JiveGlobals.setProperty(PROP_PEERS, String.join(",", peers.keySet()));
    }
}
