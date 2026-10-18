package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Distance-vector routing table of the federation overlay: for each destination, the neighbour to
 * send through and the hop count. Updates come from peer gossip and are passed on as soon as they
 * change something. Routes through an untrusted peer never outrank routes through trusted ones.
 */
public class FederationRoutingTable {

    private static final Logger Log = LoggerFactory.getLogger(FederationRoutingTable.class);
    /** Hop count treated as unreachable. */
    static final int INFINITY = 16;

    /** Most destinations one update from an untrusted peer may install, since each costs memory mesh-wide. */
    static final int MAX_ROUTES_FROM_UNTRUSTED = 128;
    /** The same bound for a trusted peer, generous, against a broken peer. */
    static final int MAX_ROUTES_FROM_TRUSTED = 2048;
    /** Most destinations the table holds; a new one beyond this is not installed. */
    static final int MAX_ROUTES = 4096;
    /** Most destinations {@link #everRoutable} remembers. */
    private static final int MAX_EVER_ROUTABLE = 8192;
    private volatile long capLoggedAt = 0;

    /** Destination to its best known route. */
    private final ConcurrentHashMap<String, RouteEntry> table = new ConcurrentHashMap<>();
    /** Peer to the destinations learned from it. */
    private final ConcurrentHashMap<String, Set<String>> routesLearnedFrom = new ConcurrentHashMap<>();
    /** Every destination routed since start, so the leak guard still knows an overlay domain whose route blipped. */
    private final Set<String> everRoutable = ConcurrentHashMap.newKeySet();

    /** Adds the one-hop route to a peer whose link just came up. */
    public void addDirectPeer(String domain) {
        table.put(domain, new RouteEntry(domain, domain, 1));
        rememberRoutable(domain);
        // Not marked as learned from the peer, which never advertises itself and would withdraw it next update.
        Log.debug("Routing: direct peer added — {}", domain);
    }

    /**
     * Merges a peer's routing table and returns the destinations that changed. An edge route, one
     * that crosses an untrusted peer, never displaces a clean route, and a clean route always displaces an edge one.
     */
    public Set<String> updateFromPeer(String fromPeer, List<RouteEntry> peerTable,
                                      Predicate<String> untrusted) {
        return updateFromPeer(XMPPServer.getInstance().getServerInfo().getXMPPDomain(),
                              fromPeer, peerTable, untrusted);
    }

    /** {@link #updateFromPeer(String, List, Predicate)} with the local domain supplied, for tests. */
    Set<String> updateFromPeer(String localDomain, String fromPeer, List<RouteEntry> peerTable,
                               Predicate<String> untrusted) {
        Set<String> changed = new HashSet<>();
        Set<String> learnedSet = routesLearnedFrom.computeIfAbsent(fromPeer, k -> ConcurrentHashMap.newKeySet());
        Set<String> inUpdate = new HashSet<>();
        boolean fromUntrusted = untrusted.test(fromPeer);
        int perPeerCap = fromUntrusted ? MAX_ROUTES_FROM_UNTRUSTED : MAX_ROUTES_FROM_TRUSTED;
        int skipped = 0;

        for (RouteEntry remote : peerTable) {
            // A negative or overflowing metric would beat every route, direct links included.
            if (remote.hops() < 0 || remote.hops() >= INFINITY) continue;

            int candidate = remote.hops() + 1;
            if (candidate >= INFINITY) continue;

            String dest = remote.destination();
            if (dest.equals(localDomain)) continue;
            // A peer never advertises itself; accepting it would overwrite the direct route.
            if (dest.equals(fromPeer)) continue;
            if (!isPlausibleDomain(dest)) { skipped++; continue; }
            if (inUpdate.size() >= perPeerCap) { skipped++; continue; }
            inUpdate.add(dest);
            boolean candidateEdge = fromUntrusted || remote.edge();
            RouteEntry current = table.get(dest);
            RouteEntry offered = new RouteEntry(dest, fromPeer, candidate, candidateEdge);

            if (current == null) {
                if (table.size() >= MAX_ROUTES) { skipped++; continue; }
                install(offered, learnedSet, changed);
            } else if (current.nextHop().equals(dest)) {
                // A direct link is never displaced by gossip.
                continue;
            } else if (current.nextHop().equals(fromPeer)) {
                // The current next hop is authoritative for its route, even when the metric gets worse.
                table.put(dest, offered);
                learnedSet.add(dest);
                if (candidate != current.hops() || candidateEdge != current.edge()) {
                    changed.add(dest);
                    Log.debug("Routing: {} via {} metric {} → {} hop(s){}", dest, fromPeer, current.hops(),
                              candidate, candidateEdge ? " [edge]" : "");
                }
            } else {
                boolean currentEdge = isEdge(current, untrusted);
                if (candidateEdge && !currentEdge) continue;
                if ((!candidateEdge && currentEdge) || candidate < current.hops()) {
                    install(offered, learnedSet, changed);
                }
            }
        }

        if (skipped > 0) {
            long now = System.currentTimeMillis();
            if (now - capLoggedAt >= 60_000L) {
                capLoggedAt = now;
                Log.warn("Routing: ignored {} destination(s) from {} — malformed, or past the limit of {} per {} "
                       + "peer / {} in all. Further cases are logged at most once a minute.", skipped, fromPeer,
                         perPeerCap, fromUntrusted ? "untrusted" : "trusted", MAX_ROUTES);
            }
        }

        // Routes learned from this peer that it no longer advertises are withdrawn.
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

    /** Removes the route to a peer and every route learned through it; returns the destinations removed. */
    public Set<String> removePeer(String domain) {
        Set<String> removed = new HashSet<>();

        if (table.remove(domain) != null) removed.add(domain);

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

    /** Removes the route to {@code destination} if it goes through {@code viaPeer}; true if one was removed. */
    public boolean removeRouteVia(String destination, String viaPeer) {
        RouteEntry entry = table.get(destination);
        if (entry == null || !entry.nextHop().equals(viaPeer)) return false;
        table.remove(destination);
        Set<String> learned = routesLearnedFrom.get(viaPeer);
        if (learned != null) learned.remove(destination);
        Log.info("Routing: removed {} via {} (advertisement denied by admin)", destination, viaPeer);
        return true;
    }

    /** The neighbour to send through to reach {@code destination}, or empty if it is unreachable. */
    public Optional<String> findNextHop(String destination) {
        return Optional.ofNullable(table.get(destination)).map(RouteEntry::nextHop);
    }

    /** The route to {@code destination}, or empty if unreachable. */
    public Optional<RouteEntry> getRoute(String destination) {
        return Optional.ofNullable(table.get(destination));
    }

    public boolean isReachable(String destination) {
        return table.containsKey(destination);
    }

    /** Whether {@code destination} has had a route at any time since the plugin started. */
    public boolean wasEverRoutable(String destination) {
        return everRoutable.contains(destination);
    }

    /** Whether a route crosses an untrusted peer: flagged when learned, or its next hop is untrusted. */
    public static boolean isEdge(RouteEntry entry, Predicate<String> untrusted) {
        return entry.edge() || untrusted.test(entry.nextHop());
    }

    /**
     * Whether a peer may announce {@code d}: a dotted bare domain with no character that could break
     * out of XML, a log line or HTML.
     */
    static boolean isPlausibleDomain(String d) {
        if (d == null || d.isEmpty() || d.length() > 253 || d.indexOf('.') <= 0) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == '@' || c == '/' || c == '"' || c == '\'' || c == '<'
                    || c == '>' || c == '&' || c == '\\' || c == ',') return false;
        }
        return true;
    }

    private void rememberRoutable(String destination) {
        if (everRoutable.size() < MAX_EVER_ROUTABLE) everRoutable.add(destination);
    }

    private void install(RouteEntry route, Set<String> learnedSet, Set<String> changed) {
        table.put(route.destination(), route);
        rememberRoutable(route.destination());
        learnedSet.add(route.destination());
        changed.add(route.destination());
        Log.debug("Routing: {} via {} in {} hop(s){}", route.destination(), route.nextHop(), route.hops(),
                  route.edge() ? " [edge]" : "");
    }

    /** Every route, for the admin console. */
    public Collection<RouteEntry> getAll() {
        return Collections.unmodifiableCollection(table.values());
    }

    /** The routes to advertise to {@code toPeer}: all but those through it (split horizon, against routing loops). */
    public Collection<RouteEntry> getRoutesExcludingNextHop(String toPeer) {
        List<RouteEntry> result = new ArrayList<>();
        for (RouteEntry e : table.values()) {
            if (e.nextHop().equals(toPeer)) continue;
            result.add(e);
        }
        return result;
    }
}
