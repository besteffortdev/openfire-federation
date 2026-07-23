# Overlay routing (distance-vector)

How the federation overlay decides which neighbour to hand a stanza to when the destination server isn't
directly connected. This is a walk through the real code in
[`FederationRoutingTable.java`](../src/main/java/com/igniterealtime/openfire/plugin/federation/FederationRoutingTable.java)
— every snippet below is copied from it.

## The idea

Servers don't need a full map of the federation. Each one keeps a **distance-vector** table — one row per
reachable destination — and periodically tells its direct neighbours what it can reach. Neighbours add one
hop and merge that into their own tables. Repeated over the polling cycle, this **Bellman-Ford** gossip
converges so every server learns a shortest path (fewest hops) to every other, without any server knowing the
whole topology.

Each row is a `RouteEntry`:

```
"to reach <destination>, send via <nextHop> — it's <hops> hops away"
```

`INFINITY = 16` caps the metric; anything at or above it is treated as unreachable (the classic distance-vector
guard against count-to-infinity).

## State

```java
/** destination → best known route */
private final ConcurrentHashMap<String, RouteEntry> table = new ConcurrentHashMap<>();
/** peer domain → destinations learned from that peer (for cleanup) */
private final ConcurrentHashMap<String, Set<String>> routesLearnedFrom = new ConcurrentHashMap<>();
```

`table` is the routing table itself. `routesLearnedFrom` is the reverse index — *which destinations did we
learn from each peer* — and it's what makes clean withdrawal possible: when a peer drops or stops advertising
something, we can find exactly the rows to remove. There is also an `everRoutable` set (never pruned) used by
the leak diagnostics to tell "an overlay domain whose route momentarily blipped" apart from "a genuinely
external XMPP domain."

## Directly-connected peers

When `S2SMonitor` sees a peer become REACHABLE, it registers a hop-1 route:

```java
public void addDirectPeer(String domain) {
    table.put(domain, new RouteEntry(domain, domain, 1));
    everRoutable.add(domain);
    // Do NOT add to routesLearnedFrom — direct routes are owned by addDirectPeer/removePeer,
    // not by gossip. A peer never advertises itself in routing-updates, so adding it here
    // would cause the stale-withdrawal check to delete the direct route on every update.
    Log.debug("Routing: direct peer added — {}", domain);
}
```

The comment flags a real subtlety: a direct route is **owned by the connection lifecycle**, not by gossip.
Keeping it out of `routesLearnedFrom` prevents the stale-route sweep (below) from ever deleting a live direct
link.

## Merging gossip from a peer

This is the heart of it — `updateFromPeer` takes the table a neighbour advertised and folds it in, returning
the set of destinations that changed (so the caller can trigger onward propagation):

```java
for (RouteEntry remote : peerTable) {
    if (remote.hops() >= INFINITY) continue;

    int candidate = remote.hops() + 1;      // one more hop to go through this neighbour
    if (candidate >= INFINITY) continue;

    String dest = remote.destination();
    if (dest.equals(localDomain)) continue; // never route to ourselves
    if (dest.equals(fromPeer)) continue;    // a peer never advertises a route to itself
    inUpdate.add(dest);
    RouteEntry current = table.get(dest);

    if (current == null || candidate < current.hops()) {
        // brand-new destination, or a strictly shorter path → take it
        table.put(dest, new RouteEntry(dest, fromPeer, candidate));
        ...
        changed.add(dest);
    } else if (current.nextHop().equals(fromPeer)) {
        // update FROM our current next hop is authoritative for its own metric —
        // accept even a WORSE hop count (the path behind it lengthened)
        table.put(dest, new RouteEntry(dest, fromPeer, candidate));
        if (candidate != current.hops()) changed.add(dest);
    }
}
```

Two acceptance rules, and the second is the non-obvious one:

1. **Strictly shorter wins** (`candidate < current.hops()`) — standard Bellman-Ford relaxation.
2. **The current next hop is authoritative for its own metric** — if the update comes from the neighbour we're
   *already* routing through, we accept even a **worse** hop count, because the path behind it genuinely got
   longer. Without this branch the table would cling to a stale-but-better metric forever, and a genuinely
   shorter alternate via a different peer could never win rule 1's comparison. (An unchanged metric still
   rewrites the row to refresh `updatedAt`, but isn't gossiped as a change.)

## Withdrawing stale routes

After processing an update, anything we *used* to hear from this peer but that's **absent from this update** is
withdrawn — but only if we were actually routing it through them:

```java
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
```

This is how a route disappears *gracefully* — the far end simply stops advertising it, and the withdrawal
ripples outward on the next cycle, each hop gossiping the change to its own neighbours.

## Losing a peer

When a link drops entirely, `removePeer` purges the direct entry **and** everything learned through it, and
returns the removed destinations so the caller can gossip the withdrawal:

```java
public Set<String> removePeer(String domain) {
    Set<String> removed = new HashSet<>();
    if (table.remove(domain) != null) removed.add(domain);          // the direct entry
    Set<String> learned = routesLearnedFrom.remove(domain);
    if (learned != null) {
        for (String dest : learned) {
            RouteEntry entry = table.get(dest);
            if (entry != null && entry.nextHop().equals(domain)) {   // only rows via this peer
                table.remove(dest);
                removed.add(dest);
            }
        }
    }
    return removed;
}
```

`removeRouteVia(destination, viaPeer)` is the surgical version used by the admin **Deny** control: it removes a
single destination only when its next hop is that specific peer, leaving an alternate path via another peer
untouched.

## Split-horizon: the loop guard

When we gossip our table *to* a peer, we omit any route whose next hop **is** that peer:

```java
public Collection<RouteEntry> getRoutesExcludingNextHop(String toPeer) {
    List<RouteEntry> result = new ArrayList<>();
    for (RouteEntry e : table.values()) {
        if (e.nextHop().equals(toPeer)) continue;   // split-horizon
        result.add(e);
    }
    return result;
}
```

Advertising a route back toward the neighbour we learned it from is exactly what creates distance-vector
routing loops (count-to-infinity) — that neighbour already has a better route to those destinations. Omitting
them also lets the receiver's stale-route detection correctly withdraw a route if our path to it now runs
through the receiver.

## How a stanza uses the table

Delivery just asks for the next hop and forwards there:

```java
public Optional<String> findNextHop(String destination) {
    return Optional.ofNullable(table.get(destination)).map(RouteEntry::nextHop);
}
```

`FederationManager` calls this when relaying a `muc-forward` / `direct-forward` / `iq-forward` toward a domain
with no direct S2S link: look up the next hop, hand the wrapped stanza to that neighbour, and let *it* repeat
the lookup. Multi-hop delivery is just this one-hop decision made independently at each server.

## Convergence & caveats

- Convergence is **cycle-driven**: `updateFromPeer` returns the `changed` set so the caller can propagate
  promptly, but the baseline guarantee is that the table settles on the next polling cycle after a topology
  change.
- `INFINITY = 16` bounds path length and the count-to-infinity blast radius — a federation more than 15 hops
  wide is out of scope by design.
- Everything here is per-server local state (`ConcurrentHashMap`); there is no central controller. That's the
  point — servers that can't even see each other still exchange traffic through the servers that can.

## Related

- **Cleanup of remote occupants** when a route drops is in `FederatedRoomManager` (virtual occupants are keyed
  by both home origin and arrival neighbour, so both a peer-down and a route-withdrawal can target the right
  ghosts).
- **The `SECURITY:`-gated deny/untrusted filtering** that decides *whether* a received route is even offered to
  this table is in [security.md](security.md).
