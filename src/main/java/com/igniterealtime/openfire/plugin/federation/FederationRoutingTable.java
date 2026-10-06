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
 * Deliberately simple Bellman-Ford. Convergence is driven by triggered updates — a peer-up,
 * peer-down, or any change {@link #updateFromPeer} reports back is gossiped onward immediately
 * (see {@code FederationManager.propagateTopologyChange}) rather than waiting for a polling cycle;
 * the S2S poll only re-solicits, so a triggered update lost to a flap still converges.
 */
public class FederationRoutingTable {

    private static final Logger Log = LoggerFactory.getLogger(FederationRoutingTable.class);
    static final int INFINITY = 16;   // anything >= 16 is considered unreachable

    /** destination → best known route */
    private final ConcurrentHashMap<String, RouteEntry> table = new ConcurrentHashMap<>();
    /** peer domain → destinations learned from that peer (for cleanup) */
    private final ConcurrentHashMap<String, Set<String>> routesLearnedFrom = new ConcurrentHashMap<>();
    /**
     * Every destination that has EVER had a route since plugin start — never pruned. Lets the
     * leak diagnostics (and later the reject-and-bounce guard) recognise a stanza aimed at an
     * overlay domain even while its route is momentarily absent from {@link #table}.
     */
    private final Set<String> everRoutable = ConcurrentHashMap.newKeySet();

    /**
     * Registers a directly-connected peer (hop count = 1).
     * Called by S2SMonitor when a peer transitions to REACHABLE.
     */
    public void addDirectPeer(String domain) {
        table.put(domain, new RouteEntry(domain, domain, 1));
        everRoutable.add(domain);
        // Do NOT add to routesLearnedFrom — direct routes are owned by addDirectPeer/removePeer,
        // not by gossip. A peer never advertises itself in routing-updates, so adding it here
        // would cause the stale-withdrawal check to delete the direct route on every update.
        Log.debug("Routing: direct peer added — {}", domain);
    }

    /**
     * Merges a routing table received from a peer via gossip.
     * Returns the set of destinations that are new or improved (for further propagation).
     *
     * <p>Routes carry a trust class as well as a metric. A route is an EDGE route when it crosses an
     * untrusted peer anywhere on its path: learned from an untrusted neighbour, or flagged
     * {@code edge} by the trusted neighbour that advertised it (every hop re-advertises the flag, see
     * {@link #isEdge}). Hop counts are the advertiser's own word, so they are only compared within a
     * class:
     * <ul>
     *   <li>an edge route never displaces a clean one, however short it claims to be — otherwise an
     *       untrusted edge could pull traffic for our side of the mesh just by advertising a small
     *       number;</li>
     *   <li>a clean route always displaces an edge one — so a claim an edge slipped in while the clean
     *       route was briefly down is undone as soon as the clean route returns.</li>
     * </ul>
     * The flag is what keeps the second rule loop-free. A route through US toward something behind the
     * edge is derived from our own advertisement, which carries the flag, so it can never come back
     * to us as a clean route and win over the edge route it was derived from.
     *
     * @param untrusted whether a given neighbour is an untrusted peer
     */
    public Set<String> updateFromPeer(String fromPeer, List<RouteEntry> peerTable,
                                      java.util.function.Predicate<String> untrusted) {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        Set<String> changed = new HashSet<>();
        Set<String> learnedSet = routesLearnedFrom.computeIfAbsent(fromPeer, k -> ConcurrentHashMap.newKeySet());
        Set<String> inUpdate = new HashSet<>();
        boolean fromUntrusted = untrusted.test(fromPeer);

        for (RouteEntry remote : peerTable) {
            // The advertised metric is the peer's claim, so bound it before using it: a negative
            // value (or one that overflows on +1) would otherwise beat every route we have,
            // including direct links, and pull traffic for the whole mesh toward the advertiser.
            if (remote.hops() < 0 || remote.hops() >= INFINITY) continue;

            int candidate = remote.hops() + 1;
            if (candidate >= INFINITY) continue;

            String dest = remote.destination();
            if (dest.equals(localDomain)) continue;
            // A peer never legitimately advertises a route to ITSELF (split-horizon omits it);
            // accepting one would let a misbehaving peer overwrite its own direct entry, which
            // is owned by addDirectPeer/removePeer.
            if (dest.equals(fromPeer)) continue;
            inUpdate.add(dest);
            boolean candidateEdge = fromUntrusted || remote.edge();
            RouteEntry current = table.get(dest);
            RouteEntry offered = new RouteEntry(dest, fromPeer, candidate, candidateEdge);

            if (current == null) {
                install(offered, learnedSet, changed);
            } else if (current.nextHop().equals(dest)) {
                // A direct link is owned by addDirectPeer/removePeer; gossip never displaces it.
                continue;
            } else if (current.nextHop().equals(fromPeer)) {
                // The route's CURRENT next hop is authoritative for its own metric and class: accept
                // even a WORSE hop count (the path behind it lengthened). Without this the table keeps
                // the stale better metric forever, so a genuinely shorter alternate via another peer
                // can never win. An unchanged route still rewrites the entry so updatedAt stays fresh,
                // but is not gossiped as a change.
                table.put(dest, offered);
                learnedSet.add(dest);
                if (candidate != current.hops() || candidateEdge != current.edge()) {
                    changed.add(dest);
                    Log.debug("Routing: {} via {} metric {} → {} hop(s){}", dest, fromPeer, current.hops(),
                              candidate, candidateEdge ? " [edge]" : "");
                }
            } else {
                boolean currentEdge = isEdge(current, untrusted);
                if (candidateEdge && !currentEdge) continue;   // never out-bid the clean mesh
                if ((!candidateEdge && currentEdge) || candidate < current.hops()) {
                    install(offered, learnedSet, changed);
                }
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
     * Removes the route to {@code destination} only if its current next hop is {@code viaPeer}.
     * Used when the admin denies that peer's advertisement of the destination; a route via a
     * different peer is left untouched. Returns true if a route was removed.
     */
    public boolean removeRouteVia(String destination, String viaPeer) {
        RouteEntry entry = table.get(destination);
        if (entry == null || !entry.nextHop().equals(viaPeer)) return false;
        table.remove(destination);
        Set<String> learned = routesLearnedFrom.get(viaPeer);
        if (learned != null) learned.remove(destination);
        Log.info("Routing: removed {} via {} (advertisement denied by admin)", destination, viaPeer);
        return true;
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

    /**
     * True if {@code destination} has had a route at ANY point since plugin start, even if the
     * table has no entry for it right now.  Used to tell "overlay peer whose route blipped"
     * apart from "genuinely external XMPP domain" in the native-S2S leak diagnostics.
     */
    public boolean wasEverRoutable(String destination) {
        return everRoutable.contains(destination);
    }

    /**
     * Whether {@code entry} crosses an untrusted edge: flagged so when learned, or its next hop is an
     * untrusted peer (which also covers a direct link to an untrusted peer). This is the value we
     * advertise onward, so every downstream hop knows the path is not clean.
     */
    public static boolean isEdge(RouteEntry entry, java.util.function.Predicate<String> untrusted) {
        return entry.edge() || untrusted.test(entry.nextHop());
    }

    private void install(RouteEntry route, Set<String> learnedSet, Set<String> changed) {
        table.put(route.destination(), route);
        everRoutable.add(route.destination());
        learnedSet.add(route.destination());
        changed.add(route.destination());
        Log.debug("Routing: {} via {} in {} hop(s){}", route.destination(), route.nextHop(), route.hops(),
                  route.edge() ? " [edge]" : "");
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
