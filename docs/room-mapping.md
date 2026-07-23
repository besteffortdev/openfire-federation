# Room mapping & multi-hop MUC forwarding

How a message typed into a room on one server reaches occupants of a *mapped* room on servers that may be
several hops away — and how remote occupants appear locally. Snippets are from
[`FederationIQHandler.java`](../src/main/java/com/igniterealtime/openfire/plugin/federation/protocol/FederationIQHandler.java)
and [`FederationManager.java`](../src/main/java/com/igniterealtime/openfire/plugin/federation/FederationManager.java).

## The moving parts

- **A mapping** links a local room to a remote room on a peer (`RoomMapping`: `localRoomJid`,
  `remoteDomain`, `remoteRoomJid`, a consent `State`, and a shared `token`). Mappings are created by an admin
  (or a name-pattern default), gated by the [consent handshake](security.md#mapping-consent).
- **`muc-forward`** is the envelope that carries one real MUC packet (a `<presence>` or `<message>`) across
  the overlay. Its attributes — `destination`, `targetRoom`, `via`, `src` — are what each hop reads to decide
  what to do.
- **Virtual occupants** are the remote users, injected into the local room and tracked by both their **home
  origin** and the **neighbour they arrived through** (`arrivedVia`), so cleanup can target the right ghosts
  when either a home server or a transit link drops.

## Egress: a local packet goes out

When a local occupant speaks or changes presence, `FederationManager` wraps the packet in a `muc-forward`
addressed to each active mapping's `remoteDomain`/`remoteRoomJid` and routes it to the next hop toward that
domain (via the [routing table](routing.md)). The envelope records `src` = this server (the mapped entry
point) and seeds the `via` trail for loop prevention.

## The hop decision: `handleMucForward`

Every server that receives a `muc-forward` runs the same routine. First, loop and spoofing guards:

```java
if (FederationStanzaFactory.viaContains(via, localDomain)) {
    Log.warn("muc-forward loop detected (via={}), dropping", via);
    return;
}
...
// Origin (from-spoofing) gate ... untrusted-peer exposure gate ...
String targetServer = (finalDest == null || localDomain.equals(finalDest)) ? localDomain : finalDest;
if (!untrustedAllowsServer(fromDomain, targetServer)) {
    Log.warn("SECURITY: dropping muc-forward from untrusted peer {} for room {} on non-exposed server {} ...");
    return;
}
```

Then the fork — **are we the destination, or a relay?**

```java
if (finalDest == null || localDomain.equals(finalDest)) {
    // We are the destination — inject into the local MUC room.
    if (!isFederatedLocalRoom(targetRoom)) { /* SECURITY: drop */ return; }
    if (manager.getRoomManager().getMappingsForLocal(targetRoom).isEmpty()) { /* SECURITY: drop */ return; }
    injectLocally(payloadEl, via, targetRoom, fromDomain, src);
    fanOutToOtherMappings(fromDomain, payloadEl, targetRoom, via);   // hub behaviour
} else {
    // We are an intermediate hop — forward toward the next hop.
    String newVia = via.isEmpty() ? localDomain : via + "," + localDomain;
    ...
    manager.getRoutingTable().findNextHop(finalDest).ifPresentOrElse(
        nextHop -> route(mucForward(nextHop, finalDest, targetRoom, newVia, src, embedded)),
        ()      -> Log.warn("muc-forward: no route to {}, dropping", finalDest));
}
```

Two authorization gates guard injection, and the comments in the source spell out *why* each is
load-bearing:

1. **`isFederatedLocalRoom`** — `directDeliver` bypasses MUC's own non-occupant check, so without this a peer
   could inject into *any* local room.
2. **non-empty active mappings** — "federation-enabled is NOT consent on its own"; a tagged-but-unmapped room
   is still closed to traffic.

A relay hop that *also* holds its own spoke mapping for `targetRoom` injects locally right there (it won't
receive the hub's fan-out because its domain will already be in the `via` trail).

## Injection bypasses the non-occupant check

`injectLocally` dispatches by payload type; `injectMessage`/`injectPresence` deliver **straight to each
occupant**, deliberately going around MUC's membership check (which is exactly why the gates above exist):

```java
private void injectLocally(Element payloadEl, String via, String targetRoom, String fromDomain, String src) {
    switch (payloadEl.getName()) {
        case "message"  -> injectMessage(payloadEl, targetRoom, fromDomain, src);
        case "presence" -> injectPresence(payloadEl, targetRoom, fromDomain, src);
        default -> Log.warn("injectLocally: unexpected payload type '{}'", payloadEl.getName());
    }
}
```

`injectPresence` is also where a newly-seen remote occupant is registered as a virtual occupant and (on a
`fed-origin`-marked join) triggers `syncLocalOccupantsToRemote` so the two rooms exchange rosters exactly
once — the marker stops that reverse sync from bouncing back.

## Hubs: bridging spokes with `fanOutToOtherMappings`

A server mapped to *several* rooms for the same local room is a **hub**. After injecting, it relays the packet
to every *other* spoke, so servers that can't see each other still share a room through the hub:

```java
for (RoomMapping m : mappings) {
    if (m.remoteDomain().equals(fromDomain))      continue; // skip direct sender
    if (viaServers.contains(m.remoteDomain()))    continue; // skip source + already-relayed
    if (m.remoteDomain().equals(payloadHome))     continue; // never echo a user back home
    String nextHop = manager.getRoutingTable().findNextHop(m.remoteDomain()).orElse(m.remoteDomain());
    // We are the hub, so occupants enter the spoke through US — stamp src with our own domain.
    route(mucForward(nextHop, m.remoteDomain(), m.remoteRoomJid(), /*newVia=*/localDomain, localDomain, embedded));
}
```

The hub **resets** the `via` trail to just itself so downstream relays aren't blocked by the inbound trail —
and because that removes the trail's loop protection, it explicitly skips the payload's **home domain** so a
user's own presence never loops back to them.

## Teardown: no stale ghosts

Because every virtual occupant carries both its **origin** and its **arrivedVia** neighbour, the manager can
evict precisely the right set on each kind of failure:

| Trigger | Method | Evicts by |
|---|---|---|
| A user's **home** server becomes unreachable | `evictAllVirtualOccupantsFromDomain` | origin domain |
| A **mapping** is disabled/removed | `evictVirtualOccupants` / `evictForInactiveMapping` | arrivedVia |
| A **transit hub** is lost | `evictOccupantsArrivedViaLostServer` | arrivedVia |
| A **mapping probe** goes silent (3 misses) | `onMappingPathBroken` | that mapping's occupants |

Each eviction sends the corresponding virtual-leave presences outward, so the ghost disappears from every
room across the federation — the same multi-hop path, in reverse.

## Related

- **Which neighbour** a hop forwards to: [routing.md](routing.md).
- **Whether** a mapping/room/peer is allowed to move this traffic at all: [security.md](security.md)
  (opt-in rooms, mapping consent + token, untrusted-peer exposure, anti-spoofing, mapping probe).
