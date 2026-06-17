package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Distance-vector routing table for the federation overlay network.
 *
 * Each entry says: "to reach <destination>, send via <nextHop> — it's <hops> hops away."
 * Updates arrive as gossip from peers.  When a peer goes down, all routes
 * learned through it are purged, and the change can be gossiped outward.
 *
 * This is deliberately simple (Bellman-Ford, no triggered updates yet).
 * Convergence happens on the next polling cycle.
 */
public class FederationRoutingTable {

    private static final Logger Log = LoggerFactory.getLogger(FederationRoutingTable.class);
    static final int INFINITY = 16;   // anything >= 16 is considered unreachable

    /** destination → best known route */
    private final ConcurrentHashMap<String, RouteEntry> table = new ConcurrentHashMap<>();
    /** peer domain → destinations learned from that peer (for cleanup) */
    private final ConcurrentHashMap<String, Set<String>> routesLearnedFrom = new ConcurrentHashMap<>();

    /**
     * Registers a directly-connected peer (hop count = 1).
     * Called by S2SMonitor when a peer transitions to REACHABLE.
     */
    public void addDirectPeer(String domain) {
        table.put(domain, new RouteEntry(domain, domain, 1));
        // Do NOT add to routesLearnedFrom — direct routes are owned by addDirectPeer/removePeer,
        // not by gossip. A peer never advertises itself in routing-updates, so adding it here
        // would cause the stale-withdrawal check to delete the direct route on every update.
        Log.debug("Routing: direct peer added — {}", domain);
    }

    /**
     * Merges a routing table received from a peer via gossip.
     * Returns the set of destinations that are new or improved (for further propagation).
     */
    public Set<String> updateFromPeer(String fromPeer, List<RouteEntry> peerTable) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        Set<String> changed = new HashSet<>();
        Set<String> learnedSet = routesLearnedFrom.computeIfAbsent(fromPeer, k -> ConcurrentHashMap.newKeySet());
        Set<String> inUpdate = new HashSet<>();

        for (RouteEntry remote : peerTable) {
            if (remote.hops() >= INFINITY) continue;

            int candidate = remote.hops() + 1;
            if (candidate >= INFINITY) continue;

            String dest = remote.destination();
            if (dest.equals(localDomain)) continue;
            inUpdate.add(dest);
            RouteEntry current = table.get(dest);

            if (current == null || candidate < current.hops()) {
                table.put(dest, new RouteEntry(dest, fromPeer, candidate));
                learnedSet.add(dest);
                changed.add(dest);
                Log.debug("Routing: {} via {} in {} hop(s)", dest, fromPeer, candidate);
            }
        }

        // Withdraw routes previously learned from this peer that are no longer in their table.
        Set<String> stale = new HashSet<>(learnedSet);
        stale.removeAll(inUpdate);
        for (String dest : stale) {
            RouteEntry entry = table.get(dest);
            if (entry != null && entry.nextHop().equals(fromPeer)) {
                table.remove(dest);
                changed.add(dest);
                Log.info("Routing: withdrew {} (no longer advertised by {})", dest, fromPeer);
            }
            learnedSet.remove(dest);
        }

        return changed;
    }

    /**
     * Removes all routes associated with a peer that went down.
     * Returns the destinations that were removed (so we can gossip the withdrawal).
     */
    public Set<String> removePeer(String domain) {
        Set<String> removed = new HashSet<>();

        // Remove the direct entry
        if (table.remove(domain) != null) removed.add(domain);

        // Remove everything learned via this peer
        Set<String> learned = routesLearnedFrom.remove(domain);
        if (learned != null) {
            for (String dest : learned) {
                RouteEntry entry = table.get(dest);
                if (entry != null && entry.nextHop().equals(domain)) {
                    table.remove(dest);
                    removed.add(dest);
                }
            }
        }

        if (!removed.isEmpty()) {
            Log.info("Routing: peer {} down — purged routes to {}", domain, removed);
        }
        return removed;
    }

    /**
     * Returns the next-hop domain for reaching destination, or empty if unreachable.
     */
    public Optional<String> findNextHop(String destination) {
        return Optional.ofNullable(table.get(destination)).map(RouteEntry::nextHop);
    }

    public boolean isReachable(String destination) {
        return table.containsKey(destination);
    }

    /** Snapshot of the full table for UI display. */
    public Collection<RouteEntry> getAll() {
        return Collections.unmodifiableCollection(table.values());
    }

    /**
     * Split-horizon view of the table for gossip TO {@code toPeer}: routes whose
     * next hop is {@code toPeer} are omitted.  Advertising a route back toward the
     * neighbour we learned it from is exactly what forms distance-vector routing
     * loops (count-to-infinity); that neighbour already has a better route to
     * those destinations.  Omission also lets the receiver's stale-route detection
     * correctly withdraw a route if our path to it now runs through the receiver.
     */
    public Collection<RouteEntry> getRoutesExcludingNextHop(String toPeer) {
        List<RouteEntry> result = new ArrayList<>();
        for (RouteEntry e : table.values()) {
            if (e.nextHop().equals(toPeer)) continue;   // split-horizon
            result.add(e);
        }
        return result;
    }
}
