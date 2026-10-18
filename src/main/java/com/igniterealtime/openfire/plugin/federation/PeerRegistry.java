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
 * The configured federation peers and their settings, persisted in Openfire properties. Live link
 * status is in memory only and kept current by {@link S2SMonitor}, except the administrative blocks.
 */
public class PeerRegistry {

    private static final Logger Log = LoggerFactory.getLogger(PeerRegistry.class);
    private static final String PROP_PEERS      = "federation.peers";
    private static final String PROP_PEER_STATE = "federation.peer.state.";
    private static final String PROP_UNTRUSTED  = "federation.peer.untrusted.";
    private static final String PROP_EXPOSED_SRV = "federation.peer.exposedsrv.";
    /** A retired per-room exposure key, deleted on load and on removal. */
    private static final String PROP_EXPOSED_LEGACY = "federation.peer.exposed.";
    private static final String PROP_CERTFP     = "federation.peer.certfp.";
    private static final String PROP_DENIED_RT  = "federation.peer.deniedroutes.";
    private static final String PROP_CONTACT_LISTS = "federation.peer.contactlists.";
    private static final String PROP_CONTACT_LISTS_REFUSED = "federation.peer.contactlistsrefused.";

    private final ConcurrentHashMap<String, PeerServer> peers = new ConcurrentHashMap<>();

    /** Loads the persisted peers and their settings; called once on start. */
    public void load() {
        String stored = JiveGlobals.getProperty(PROP_PEERS, "").strip();
        if (!stored.isEmpty()) {
            for (String domain : stored.split(",")) {
                domain = domain.strip();
                if (!domain.isEmpty()) {
                    PeerServer peer = new PeerServer(domain);
                    String state = JiveGlobals.getProperty(PROP_PEER_STATE + domain, "").strip();
                    if (!state.isEmpty()) {
                        try {
                            PeerServer.Status s = PeerServer.Status.valueOf(state);
                            if (s == PeerServer.Status.DISABLED || s == PeerServer.Status.REMOTE_DISABLED) {
                                peer.setStatus(s);
                                Log.info("Loaded federation peer {} with persisted status {}", domain, s);
                            }
                        } catch (IllegalArgumentException ignored) {
                            // A status this build does not know: the peer starts out UNKNOWN.
                        }
                    } else {
                        Log.info("Loaded configured federation peer: {}", domain);
                    }
                    JiveGlobals.deleteProperty(PROP_EXPOSED_LEGACY + domain);
                    if (JiveGlobals.getBooleanProperty(PROP_UNTRUSTED + domain, false)) {
                        peer.setUntrusted(true);
                        peer.setExposedServers(parseCsv(JiveGlobals.getProperty(PROP_EXPOSED_SRV + domain, "")));
                        peer.setContactListsAllowed(JiveGlobals.getBooleanProperty(PROP_CONTACT_LISTS + domain, false));
                        peer.setContactListsRefused(
                                JiveGlobals.getBooleanProperty(PROP_CONTACT_LISTS_REFUSED + domain, false));
                        Log.info("Loaded untrusted peer {} with {} exposed server(s)",
                                 domain, peer.getExposedServers().size());
                    }
                    String fp = JiveGlobals.getProperty(PROP_CERTFP + domain, "").strip();
                    if (!fp.isEmpty()) peer.setPinnedCertFp(fp);
                    peer.setDeniedRoutes(parseCsv(JiveGlobals.getProperty(PROP_DENIED_RT + domain, "")));
                    peers.put(domain, peer);
                }
            }
        }
    }

    /** Whether a domain may federate while the peer allowlist is on: only configured peers may. */
    public boolean isApproved(String domain) {
        return peers.containsKey(domain);
    }

    /** Adds a peer and persists the list; returns the existing peer if it was already there. */
    public PeerServer addPeer(String domain) {
        PeerServer peer = peers.computeIfAbsent(domain, PeerServer::new);
        persist();
        return peer;
    }

    /** Removes a peer with all its settings and persists the list; true if it was present. */
    public boolean removePeer(String domain) {
        boolean removed = peers.remove(domain) != null;
        if (removed) {
            JiveGlobals.deleteProperty(PROP_PEER_STATE + domain);
            JiveGlobals.deleteProperty(PROP_UNTRUSTED + domain);
            JiveGlobals.deleteProperty(PROP_EXPOSED_SRV + domain);
            JiveGlobals.deleteProperty(PROP_EXPOSED_LEGACY + domain);
            JiveGlobals.deleteProperty(PROP_CERTFP + domain);
            JiveGlobals.deleteProperty(PROP_DENIED_RT + domain);
            JiveGlobals.deleteProperty(PROP_CONTACT_LISTS + domain);
            JiveGlobals.deleteProperty(PROP_CONTACT_LISTS_REFUSED + domain);
            persist();
        }
        return removed;
    }

    public boolean isUntrusted(String domain) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isUntrusted();
    }

    /** The servers exposed to a peer; empty when none are set. */
    public Set<String> getExposedServers(String domain) {
        PeerServer peer = peers.get(domain);
        return peer == null ? Collections.emptySet()
                            : Collections.unmodifiableSet(peer.getExposedServers());
    }

    /** Marks or clears a peer as untrusted and persists it; ignored for an unknown peer. */
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

    /** Whether contact lists may be sent across the untrusted link to {@code domain}. */
    public boolean isContactListsAllowed(String domain) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isContactListsAllowed();
    }

    /** Allows or stops sending contact lists to {@code domain} and persists it; ignored for an unknown peer. */
    public void setContactListsAllowed(String domain, boolean allowed) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setContactListsAllowed(allowed);
        if (allowed) {
            JiveGlobals.setProperty(PROP_CONTACT_LISTS + domain, "true");
        } else {
            JiveGlobals.deleteProperty(PROP_CONTACT_LISTS + domain);
        }
    }

    /** Whether contact lists arriving over the untrusted link from {@code domain} are refused. */
    public boolean isContactListsRefused(String domain) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isContactListsRefused();
    }

    /** Refuses or accepts contact lists from {@code domain} and persists it; ignored for an unknown peer. */
    public void setContactListsRefused(String domain, boolean refused) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setContactListsRefused(refused);
        if (refused) {
            JiveGlobals.setProperty(PROP_CONTACT_LISTS_REFUSED + domain, "true");
        } else {
            JiveGlobals.deleteProperty(PROP_CONTACT_LISTS_REFUSED + domain);
        }
    }

    /** Replaces the servers exposed to a peer and persists them; ignored for an unknown peer. */
    public void setExposedServers(String domain, Collection<String> servers) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setExposedServers(servers);
        Set<String> stored = peer.getExposedServers();
        if (stored.isEmpty()) {
            JiveGlobals.deleteProperty(PROP_EXPOSED_SRV + domain);
        } else {
            JiveGlobals.setProperty(PROP_EXPOSED_SRV + domain, String.join(",", stored));
        }
    }

    /** The destinations whose advertisements from {@code domain} the admin refused. */
    public Set<String> getDeniedRoutes(String domain) {
        PeerServer peer = peers.get(domain);
        return peer == null ? Collections.emptySet()
                            : Collections.unmodifiableSet(peer.getDeniedRoutes());
    }

    /** Whether advertisements for {@code destination} from {@code domain} are refused. */
    public boolean isRouteDenied(String domain, String destination) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isRouteDenied(destination);
    }

    /** Refuses or allows a destination's advertisements from a peer and persists it; ignored for an unknown peer. */
    public void setRouteDenied(String domain, String destination, boolean denied) {
        PeerServer peer = peers.get(domain);
        if (peer == null || destination == null || destination.isBlank()) return;
        if (denied) peer.getDeniedRoutes().add(destination.strip());
        else        peer.getDeniedRoutes().remove(destination.strip());
        Set<String> stored = peer.getDeniedRoutes();
        if (stored.isEmpty()) {
            JiveGlobals.deleteProperty(PROP_DENIED_RT + domain);
        } else {
            JiveGlobals.setProperty(PROP_DENIED_RT + domain, String.join(",", stored));
        }
    }

    /** The pinned certificate fingerprint of a peer, or null if none is pinned yet. */
    public String getPinnedCertFp(String domain) {
        PeerServer peer = peers.get(domain);
        return peer == null ? null : peer.getPinnedCertFp();
    }

    /** Pins or clears a peer's certificate fingerprint and persists it; ignored for an unknown peer. */
    public void setPinnedCertFp(String domain, String fp) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        peer.setPinnedCertFp(fp);
        if (fp == null || fp.isBlank()) {
            JiveGlobals.deleteProperty(PROP_CERTFP + domain);
        } else {
            JiveGlobals.setProperty(PROP_CERTFP + domain, fp);
        }
    }

    public void setLastSeenCertFp(String domain, String fp) {
        PeerServer peer = peers.get(domain);
        if (peer != null) peer.setLastSeenCertFp(fp);
    }

    public void setCertMismatch(String domain, boolean mismatch) {
        PeerServer peer = peers.get(domain);
        if (peer != null) peer.setCertMismatch(mismatch);
    }

    public boolean isCertMismatch(String domain) {
        PeerServer peer = peers.get(domain);
        return peer != null && peer.isCertMismatch();
    }

    /** Blocks a link because the two ends disagree on trust; not persisted. */
    public void setTrustMismatch(String domain) {
        PeerServer peer = peers.get(domain);
        if (peer != null) peer.setStatus(PeerServer.Status.TRUST_MISMATCH);
    }

    /** Lifts a TRUST_MISMATCH block now that the ends agree; other statuses are left alone. */
    public void clearTrustMismatch(String domain) {
        PeerServer peer = peers.get(domain);
        if (peer != null && peer.getStatus() == PeerServer.Status.TRUST_MISMATCH) {
            peer.setStatus(PeerServer.Status.UNKNOWN);
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
     * Sets a peer's live link status. The blocks DISABLED, REMOTE_DISABLED and TRUST_MISMATCH are never
     * overwritten here; they change only through {@link #setControlStatus} and the trust exchange.
     */
    public void updateStatus(String domain, PeerServer.Status status) {
        PeerServer peer = peers.get(domain);
        if (peer == null) return;
        PeerServer.Status cur = peer.getStatus();
        if (cur == PeerServer.Status.DISABLED || cur == PeerServer.Status.REMOTE_DISABLED
                || cur == PeerServer.Status.TRUST_MISMATCH) return;
        peer.setStatus(status);
        if (status == PeerServer.Status.REACHABLE) {
            peer.markSeen();
        }
    }

    /** Sets a status from an administrative action; DISABLED and REMOTE_DISABLED are persisted. */
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
