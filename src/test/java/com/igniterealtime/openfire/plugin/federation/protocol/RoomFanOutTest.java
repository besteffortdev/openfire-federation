package com.igniterealtime.openfire.plugin.federation.protocol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a payload spreads through mapped rooms, and that it stops. The mesh tests run the real
 * arrival and fan-out decisions over a simulated transport, with each loop fix switchable.
 */
class RoomFanOutTest {

    private static final String A = "2501-xmpp.example.net";
    private static final String B = "2503-xmpp.example.net";
    private static final String C = "2504-xmpp.example.net";
    private static final String D = "2507-xmpp.example.net";

    /** Deliveries past this mean the payload is circling. */
    private static final int LOOP_LIMIT = 10_000;

    @Test
    @DisplayName("a hub skips the sender, the via trail, the user's home and every server already a hub")
    void skipRules() {
        Set<String> via = Set.of(C);

        assertEquals("is direct sender", RoomFanOut.skipReason(B, B, via, "", A));
        assertEquals("in via trail", RoomFanOut.skipReason(C, B, via, "", A));
        assertEquals("is payload home", RoomFanOut.skipReason(A, B, via, "", A));
        assertEquals("already a hub for it", RoomFanOut.skipReason(D, B, via, A + "," + D, ""));
        assertNull(RoomFanOut.skipReason(D, B, via, A + "," + B, A));
    }

    @Test
    @DisplayName("a hub trail matches whole domains only")
    void hubTrailMatchesWholeDomains() {
        // xmpp.example.net is a substring of 2501-xmpp.example.net but a different server.
        assertNull(RoomFanOut.skipReason("xmpp.example.net", B, Set.of(), A, ""));
    }

    @Test
    @DisplayName("the hub trail grows by one server per hub and never repeats one")
    void trailHelpers() {
        assertEquals(A, FederationStanzaFactory.appendToTrail(null, A));
        assertEquals(A, FederationStanzaFactory.appendToTrail("", A));
        assertEquals(A + "," + B, FederationStanzaFactory.appendToTrail(A, B));
        assertEquals(A + "," + B, FederationStanzaFactory.appendToTrail(A + "," + B, A));

        assertEquals(0, FederationStanzaFactory.trailLength(null));
        assertEquals(0, FederationStanzaFactory.trailLength(""));
        assertEquals(2, FederationStanzaFactory.trailLength(A + "," + B));
        assertEquals(2, FederationStanzaFactory.trailLength(A + ",," + B + ","));
    }

    @Test
    @DisplayName("full mesh of four: a message reaches every room exactly once")
    void fullMeshMessageOnce() {
        Result r = spread(fullMesh(A, B, C, D), A, true, true, true);

        assertFalse(r.looped);
        assertEachOnce(r, B, C, D);
    }

    @Test
    @DisplayName("ring of four: a message reaches every room exactly once")
    void ringMessageOnce() {
        Result r = spread(ring(A, B, C, D), A, true, true, true);

        assertFalse(r.looped);
        assertEachOnce(r, B, C, D);
    }

    @Test
    @DisplayName("every server can be the origin")
    void everyOrigin() {
        for (String origin : List.of(A, B, C, D)) {
            Result r = spread(fullMesh(A, B, C, D), origin, true, true, true);
            List<String> others = new java.util.ArrayList<>(List.of(A, B, C, D));
            others.remove(origin);
            assertEachOnce(r, others.toArray(String[]::new));
        }
    }

    @Test
    @DisplayName("a payload with no id (a presence) still stops, and reaches every room")
    void meshWithoutIdTerminates() {
        for (Map<String, Set<String>> mesh : List.of(fullMesh(A, B, C, D), ring(A, B, C, D))) {
            Result r = spread(mesh, A, false, true, true);

            assertFalse(r.looped, "circled in " + mesh);
            for (String s : List.of(B, C, D)) assertTrue(r.delivered.get(s) >= 1, s + " never got it in " + mesh);
        }
    }

    @Test
    @DisplayName("a plain hub still bridges its spokes, which see each other only through it")
    void starStillBridges() {
        Map<String, Set<String>> star = new TreeMap<>();
        star.put(A, Set.of(B, C, D));
        star.put(B, Set.of(A));
        star.put(C, Set.of(A));
        star.put(D, Set.of(A));

        assertEachOnce(spread(star, B, true, true, true), A, C, D);
    }

    @Test
    @DisplayName("a chain of hubs passes it all the way along")
    void chainStillReachesTheEnd() {
        Map<String, Set<String>> chain = new TreeMap<>();
        chain.put(A, Set.of(B));
        chain.put(B, Set.of(A, C));
        chain.put(C, Set.of(B, D));
        chain.put(D, Set.of(C));

        assertEachOnce(spread(chain, A, true, true, true), B, C, D);
        assertEachOnce(spread(chain, A, false, true, true), B, C, D);
    }

    @Test
    @DisplayName("without either fix the payload circles a full mesh forever (the reported bug)")
    void withoutFixesMeshLoops() {
        assertTrue(spread(fullMesh(A, B, C, D), A, true, false, false).looped);
    }

    @Test
    @DisplayName("without either fix a ring of four stops, but only at the author's home, after duplicating")
    void withoutFixesRingDuplicates() {
        // Each path dies when the next hop would be the author's home server, so a ring does not loop.
        // But the message travels both ways round, and the room facing the origin gets it twice.
        Result r = spread(ring(A, B, C, D), A, true, false, false);

        assertFalse(r.looped);
        assertTrue(r.delivered.values().stream().anyMatch(n -> n > 1), "expected a duplicate: " + r.delivered);
    }

    @Test
    @DisplayName("the duplicate check alone makes a message exactly-once")
    void dedupAloneFixesMessages() {
        Result r = spread(fullMesh(A, B, C, D), A, true, true, false);

        assertFalse(r.looped);
        assertEachOnce(r, B, C, D);
    }

    @Test
    @DisplayName("the duplicate check cannot help a payload with no id: it still circles a mesh")
    void dedupAloneCannotStopPresence() {
        assertTrue(spread(fullMesh(A, B, C, D), A, false, true, false).looped);
    }

    @Test
    @DisplayName("the hub trail alone stops it circling, but a mesh still delivers duplicates")
    void hubTrailAloneStopsButDuplicates() {
        Result r = spread(fullMesh(A, B, C, D), A, true, false, true);

        assertFalse(r.looped);
        assertTrue(r.delivered.values().stream().anyMatch(n -> n > 1), "expected a duplicate: " + r.delivered);
    }

    private record Envelope(String to, String from, String via, String hubs) { }

    private record Result(Map<String, Integer> delivered, boolean looped) { }

    /**
     * Spreads one payload posted on {@code origin}. {@code withId} is whether it carries an origin
     * stanza-id (a message does, a presence does not).
     */
    private static Result spread(Map<String, Set<String>> mappings, String origin, boolean withId,
                                 boolean dedup, boolean hubTrail) {
        String originRoom = roomOf(origin);
        String originId = "5f1c2e1a-8f0e-4c55-9b1d-2a7e6c1d0f3b";
        Map<String, RoomMessageIds> ids = new LinkedHashMap<>();
        Map<String, Integer> delivered = new TreeMap<>();
        for (String s : mappings.keySet()) {
            ids.put(s, new RoomMessageIds());
            delivered.put(s, 0);
        }

        Deque<Envelope> wire = new ArrayDeque<>();
        // Origin egress: every caller of the six-argument mucForward seeds via and hubs with itself.
        for (String m : mappings.get(origin)) wire.add(new Envelope(m, origin, origin, hubTrail ? origin : ""));

        int steps = 0;
        while (!wire.isEmpty()) {
            if (++steps > LOOP_LIMIT) return new Result(delivered, true);
            Envelope e = wire.poll();
            String here = e.to();

            if (FederationStanzaFactory.viaContains(e.via(), here)) continue;     // via loop guard
            if (FederationStanzaFactory.viaContains(e.hubs(), here)) continue;    // already a hub for it
            if (here.equals(origin)) continue;                                    // self-echo: not new
            if (dedup && withId && !ids.get(here).firstArrival(roomOf(here), originRoom, originId)) continue;

            delivered.merge(here, 1, Integer::sum);

            Set<String> viaServers = new HashSet<>(Arrays.asList(e.via().split(",")));
            String newHubs = hubTrail ? FederationStanzaFactory.appendToTrail(e.hubs(), here) : "";
            for (String spoke : mappings.get(here)) {
                if (RoomFanOut.skipReason(spoke, e.from(), viaServers, e.hubs(), origin) != null) continue;
                wire.add(new Envelope(spoke, here, here, newHubs));
            }
        }
        return new Result(delivered, false);
    }

    private static String roomOf(String server) {
        return "room@conference." + server;
    }

    private static Map<String, Set<String>> fullMesh(String... servers) {
        Map<String, Set<String>> m = new TreeMap<>();
        for (String s : servers) {
            Set<String> others = new HashSet<>(Arrays.asList(servers));
            others.remove(s);
            m.put(s, Collections.unmodifiableSet(others));
        }
        return m;
    }

    private static Map<String, Set<String>> ring(String... servers) {
        Map<String, Set<String>> m = new TreeMap<>();
        for (int i = 0; i < servers.length; i++) {
            m.put(servers[i], Set.of(servers[(i + servers.length - 1) % servers.length],
                                     servers[(i + 1) % servers.length]));
        }
        return m;
    }

    private static void assertEachOnce(Result r, String... servers) {
        assertFalse(r.looped, "circled: " + r.delivered);
        for (String s : servers) assertEquals(1, r.delivered.get(s), s + " in " + r.delivered);
    }
}
