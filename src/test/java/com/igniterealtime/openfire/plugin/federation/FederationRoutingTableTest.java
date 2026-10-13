package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The distance-vector rules, written as what a peer may and may not make this server believe.
 * Every route arrives as a peer's claim, so most of these are cases of a claim that must be refused.
 */
class FederationRoutingTableTest {

    private static final String LOCAL = "local.example";
    private static final String A = "a.example";
    private static final String B = "b.example";
    private static final String C = "c.example";
    private static final String D = "d.example";
    private static final String UNTRUSTED = "u.example";

    private final FederationRoutingTable table = new FederationRoutingTable();
    private final Set<String> untrusted = new HashSet<>(Set.of(UNTRUSTED));

    @Test
    @DisplayName("a route learned from a peer is one hop further than the peer says")
    void learnsRouteOneHopFurther() {
        table.addDirectPeer(A);

        Set<String> changed = gossip(A, route(C, 1));

        assertEquals(Set.of(C), changed);
        assertRoute(C, A, 2);
    }

    @Test
    @DisplayName("a negative, infinite or overflowing metric is refused")
    void refusesBogusMetrics() {
        table.addDirectPeer(A);

        gossip(A, route(C, -1), route(D, FederationRoutingTable.INFINITY), route("e.example", 15),
               route("f.example", Integer.MAX_VALUE));

        assertFalse(table.isReachable(C), "a negative metric would beat every real route, direct links included");
        assertFalse(table.isReachable(D));
        assertFalse(table.isReachable("e.example"), "15 + 1 is already unreachable");
        assertFalse(table.isReachable("f.example"), "MAX_VALUE + 1 overflows to a winning negative");
    }

    @Test
    @DisplayName("a peer cannot advertise a route to this server or to itself")
    void refusesRoutesToSelfAndToTheAdvertiser() {
        table.addDirectPeer(A);

        gossip(A, route(LOCAL, 0), route(A, 0));

        assertFalse(table.isReachable(LOCAL));
        assertRoute(A, A, 1);
    }

    @Test
    @DisplayName("a peer cannot install a route to itself while its direct link is down")
    void refusesSelfRouteWithoutDirectLink() {
        // With the direct link up, the direct-link rule already protects it; this is the gap that
        // the self-route check alone covers, e.g. gossip processed just before the link is re-added.
        gossip(A, route(A, 0), route(C, 1));

        assertFalse(table.isReachable(A));
        assertTrue(table.isReachable(C));
    }

    @Test
    @DisplayName("gossip never displaces a direct link")
    void gossipNeverDisplacesDirectLink() {
        table.addDirectPeer(A);
        table.addDirectPeer(B);

        gossip(A, route(B, 0));
        gossip(A);   // and a later update without it must not withdraw the direct link either

        assertRoute(B, B, 1);
    }

    @Test
    @DisplayName("a shorter route through another peer wins; a longer one does not")
    void prefersShorterRoute() {
        table.addDirectPeer(A);
        table.addDirectPeer(B);
        gossip(A, route(C, 3));

        assertEquals(Set.of(C), gossip(B, route(C, 1)));
        assertRoute(C, B, 2);

        gossip(A, route(C, 2));
        assertRoute(C, B, 2);
    }

    @Test
    @DisplayName("the current next hop is believed when its own path gets longer")
    void currentNextHopIsAuthoritative() {
        table.addDirectPeer(A);
        gossip(A, route(C, 1));

        assertEquals(Set.of(C), gossip(A, route(C, 5)));
        assertRoute(C, A, 6);
    }

    @Test
    @DisplayName("an unchanged route is refreshed but not reported as a change")
    void unchangedRouteIsNotGossiped() {
        table.addDirectPeer(A);
        gossip(A, route(C, 1));

        assertEquals(Set.of(), gossip(A, route(C, 1)));
    }

    @Test
    @DisplayName("an untrusted edge cannot out-bid the clean mesh, however short its claim")
    void edgeNeverOutbidsCleanRoute() {
        table.addDirectPeer(A);
        table.addDirectPeer(UNTRUSTED);
        gossip(A, route(C, 5));

        gossip(UNTRUSTED, route(C, 0));

        assertRoute(C, A, 6);
    }

    @Test
    @DisplayName("a clean route displaces an edge route even when it is longer")
    void cleanRouteDisplacesEdgeRoute() {
        table.addDirectPeer(A);
        table.addDirectPeer(UNTRUSTED);
        gossip(UNTRUSTED, route(C, 0));
        assertTrue(table.getRoute(C).orElseThrow().edge());

        gossip(A, route(C, 8));

        assertRoute(C, A, 9);
        assertFalse(table.getRoute(C).orElseThrow().edge());
    }

    @Test
    @DisplayName("the edge flag a trusted peer passes on is kept, and loses to a clean route")
    void edgeFlagFromTrustedAdvertiserIsKept() {
        table.addDirectPeer(A);
        table.addDirectPeer(B);
        gossip(A, new RouteEntry(C, "x", 1, true));
        assertTrue(table.getRoute(C).orElseThrow().edge(), "the upstream edge must stay visible downstream");

        gossip(B, route(C, 3));

        assertRoute(C, B, 4);
    }

    @Test
    @DisplayName("a route the peer stops advertising is withdrawn")
    void withdrawsRouteNoLongerAdvertised() {
        table.addDirectPeer(A);
        gossip(A, route(C, 1), route(D, 1));

        Set<String> changed = gossip(A, route(C, 1));

        assertEquals(Set.of(D), changed);
        assertFalse(table.isReachable(D));
        assertTrue(table.isReachable(C));
        assertTrue(table.wasEverRoutable(D), "the leak diagnostics must still recognise it");
    }

    @Test
    @DisplayName("a peer going down purges its direct link and everything learned through it")
    void removePeerPurgesItsRoutes() {
        table.addDirectPeer(A);
        table.addDirectPeer(B);
        gossip(A, route(C, 1));
        gossip(B, route(D, 1));

        assertEquals(Set.of(A, C), table.removePeer(A));

        assertFalse(table.isReachable(A));
        assertFalse(table.isReachable(C));
        assertRoute(D, B, 2);
    }

    @Test
    @DisplayName("an admin denial removes the route only through the denied peer")
    void removeRouteViaChecksTheNextHop() {
        table.addDirectPeer(A);
        gossip(A, route(C, 1));

        assertFalse(table.removeRouteVia(C, B));
        assertTrue(table.isReachable(C));
        assertTrue(table.removeRouteVia(C, A));
        assertFalse(table.isReachable(C));
    }

    @Test
    @DisplayName("routes are never gossiped back to the peer they were learned from")
    void splitHorizon() {
        table.addDirectPeer(A);
        table.addDirectPeer(B);
        gossip(A, route(C, 1));

        List<String> toA = table.getRoutesExcludingNextHop(A).stream().map(RouteEntry::destination).toList();

        assertEquals(List.of(B), toA);
    }

    @Test
    @DisplayName("an untrusted peer can install only a bounded number of destinations")
    void capsRoutesFromUntrustedPeer() {
        table.addDirectPeer(UNTRUSTED);
        table.addDirectPeer(A);

        gossip(UNTRUSTED, manyRoutes("u", 200));
        gossip(A, manyRoutes("t", 200));

        long viaUntrusted = table.getAll().stream().filter(r -> r.nextHop().equals(UNTRUSTED)
                                                              && !r.destination().equals(UNTRUSTED)).count();
        long viaTrusted = table.getAll().stream().filter(r -> r.nextHop().equals(A)
                                                           && !r.destination().equals(A)).count();
        assertEquals(FederationRoutingTable.MAX_ROUTES_FROM_UNTRUSTED, viaUntrusted);
        assertEquals(200, viaTrusted);
    }

    @Test
    @DisplayName("a destination that could break out of an attribute, a log line or the page is refused")
    void refusesImplausibleDestinations() {
        table.addDirectPeer(A);
        List<String> hostile = List.of("evil\".example", "x@y.example", "a b.example", "a/b.example",
                                       "<script>.example", "a&b.example", "a\nb.example", "nodot", ".lead",
                                       "x".repeat(250) + ".com");

        gossip(A, hostile.stream().map(d -> route(d, 1)).toArray(RouteEntry[]::new));

        for (String d : hostile) assertFalse(table.isReachable(d), d);
        assertTrue(FederationRoutingTable.isPlausibleDomain("conference.c-1.example"));
    }

    private Set<String> gossip(String from, RouteEntry... routes) {
        return table.updateFromPeer(LOCAL, from, List.of(routes), untrusted::contains);
    }

    /** A route as the peer advertises it; its own next hop is not ours, so it is never read. */
    private static RouteEntry route(String destination, int hops) {
        return new RouteEntry(destination, "peer-internal", hops);
    }

    private static RouteEntry[] manyRoutes(String prefix, int n) {
        List<RouteEntry> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(route(prefix + i + ".example", 1));
        return out.toArray(RouteEntry[]::new);
    }

    private void assertRoute(String destination, String nextHop, int hops) {
        RouteEntry r = table.getRoute(destination).orElseThrow(() -> new AssertionError("no route to " + destination));
        assertEquals(nextHop, r.nextHop(), "next hop for " + destination);
        assertEquals(hops, r.hops(), "hops to " + destination);
    }
}
