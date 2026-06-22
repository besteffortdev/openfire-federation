package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
    private static final String PROP_UNTRUSTED  = "federation.peer.untrusted."; // + domain → true
    private static final String PROP_EXPOSED    = "federation.peer.exposed.";   // + domain → csv of room JIDs

    private final ConcurrentHashMap<String, PeerServer> peers = new ConcurrentHashMap<>();

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
                    // Restore untrusted flag + exposed-room allowlist (filtered-exposure peers).
                    if (JiveGlobals.getBooleanProperty(PROP_UNTRUSTED + domain, false)) {
                        peer.setUntrusted(true);
                        peer.setExposedRooms(parseCsv(JiveGlobals.getProperty(PROP_EXPOSED + domain, "")));
                        Log.info("Loaded untrusted peer {} with {} exposed room(s)",
                                 domain, peer.getExposedRooms().size());
                    }
                    peers.put(domain, peer);
                }
            }
        }
    }

    /**
     * Whether a domain is allowed to federate when the peer allowlist is enabled. The single
     * source of truth is the configured peer registry itself: a peer is approved iff it is a
     * configured peer (added by the admin, or auto-registered while the allowlist was off).
     * Using the registry directly — rather than a parallel "approved" set — avoids the two
     * desyncing, which previously could leave the approved set empty and reject every peer.
     */
    public boolean isApproved(String domain) {
        return peers.containsKey(domain);
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
            JiveGlobals.deleteProperty(PROP_UNTRUSTED + domain);
            JiveGlobals.deleteProperty(PROP_EXPOSED + domain);
            persist();
        }
        return removed;
    }

    // ── Untrusted (filtered-exposure) peers ────────────────────────────────────

    public boolean isUntrusted(String domain) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isUntrusted();
    }

    /** Room JIDs an untrusted peer may see/map; empty for trusted peers or none set. */
    public Set<String> getExposedRooms(String domain) {
        PeerServer peer = peers.get(domain);
        return peer == null ? Collections.emptySet()
                            : Collections.unmodifiableSet(peer.getExposedRooms());
    }

    /** Marks (or clears) a peer as untrusted and persists the flag. No-op if unknown. */
    public void setUntrusted(String domain, boolean untrusted) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setUntrusted(untrusted);
        if (untrusted) {
            JiveGlobals.setProperty(PROP_UNTRUSTED + domain, "true");
        } else {
            JiveGlobals.deleteProperty(PROP_UNTRUSTED + domain);
        }
    }

    /** Replaces an untrusted peer's exposed-room allowlist and persists it. No-op if unknown. */
    public void setExposedRooms(String domain, Collection<String> rooms) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setExposedRooms(rooms);
        Set<String> stored = peer.getExposedRooms();
        if (stored.isEmpty()) {
            JiveGlobals.deleteProperty(PROP_EXPOSED + domain);
        } else {
            JiveGlobals.setProperty(PROP_EXPOSED + domain, String.join(",", stored));
        }
    }

    private static Set<String> parseCsv(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv != null && !csv.isBlank()) {
            for (String s : Arrays.asList(csv.split(","))) {
                if (!s.isBlank()) out.add(s.strip());
            }
        }
        return out;
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
