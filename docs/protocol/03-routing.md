# 03 — Routing

The overlay carries its own reachability table so a stanza can cross servers that have no direct S2S
link. It is plain distance-vector (Bellman-Ford) with split horizon and triggered updates.

Two actions: `routing-update` (here is my table) and `routing-solicit` (send me yours).

## The model

Every server keeps one table:

```
destination domain  →  ( next hop domain , hop count )
```

- A **directly-connected peer** is installed at hop count **1**, with itself as next hop, the moment it
  becomes reachable. Direct routes are owned locally — they are never learned from gossip.
- Everything else is learned by adding 1 to a neighbour's advertised metric.
- **`INFINITY` is 16.** A metric of 16 or more means unreachable. There is no explicit
  route-poisoning: withdrawal is by omission (see [below](#withdrawal-by-omission)).
- Lookups return the next hop only. A server never knows the full path to a destination, and does not
  need to.

## `routing-update`

```xml
<iq type='set' from='beta.example' to='alpha.example' id='f4'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update>
      <entry destination='gamma.example' hops='1' via='gamma.example'/>
      <entry destination='delta.example' hops='2' via='gamma.example'/>
    </routing-update>
  </federation>
</iq>
```

Link-local: no `destination` attribute on the action, never relayed. What propagates is not the stanza
but the *knowledge*, re-originated by each receiver as its own update.

### The `<entry/>` element

| Attribute | Meaning |
|-----------|---------|
| `destination` | The server domain this route reaches. Required. |
| `hops` | The **sender's** distance to it. Required; unparseable is treated as 99, i.e. unreachable. |
| `via` | The **sender's** next hop for it. Required — an entry missing `destination` or `via` is skipped entirely. |

> **`via` here is a single next-hop domain, not an accumulated trail.** This is the one attribute in
> the protocol whose meaning depends on the element it sits on ([01-transport.md](01-transport.md#the-one-overloaded-attribute)).
> On every other action, `via` is a comma-separated loop trail.

The receiver does not actually use the `via` value to compute anything — it installs *itself → sender*
as the next hop regardless. It is present for diagnostics and for the receiver's own split-horizon
bookkeeping. Emit it truthfully; do not depend on the far side reading it.

### Merge algorithm

For each `<entry/>`, in this order:

```
if hops >= 16                       → skip (already unreachable)
candidate := hops + 1
if candidate >= 16                  → skip (would become unreachable here)
if destination == our own domain    → skip
if destination == the sending peer  → skip     ← see below
if this peer's advertisement of this destination is admin-denied → skip

record destination as "seen in this update"

current := our table entry for destination
if current is absent OR candidate < current.hops:
    install (destination → sender, candidate); mark CHANGED
else if current.nextHop == sender:
    install (destination → sender, candidate)          ← even if WORSE
    mark CHANGED only if the metric actually differs
else:
    ignore (we have an equal or better route via someone else)
```

Two subtleties that are easy to get wrong and both matter:

**Rejecting `destination == sender`.** A peer never legitimately advertises a route to *itself* —
split horizon guarantees it will not, because its own route to itself is direct. Accepting one would
let a peer overwrite the direct entry that the link layer owns, which is how a misbehaving peer could
make itself look further away or reachable via someone else.

**Accepting a worse metric from the current next hop.** If a route currently goes via beta and beta
now says the path got longer, you must take the new, worse number. The current next hop is
authoritative for its own metric. Skip this and your table keeps the stale better metric forever, so a
genuinely shorter path via another neighbour can never win the `candidate < current.hops` comparison —
the table wedges permanently on a path that no longer exists.

### Withdrawal by omission

**A `routing-update` is a complete snapshot, not a delta.**

After merging, the receiver takes every destination it had previously learned *from this peer*, and
removes any that did not appear in this update — provided the current next hop is still that peer.

```
stale := (destinations previously learned from sender) − (destinations in this update)
for each d in stale:
    if table[d].nextHop == sender:
        remove table[d]; mark CHANGED
    forget that we learned d from sender
```

This is the only withdrawal mechanism. There is no "unreachable" entry, no metric-16 poison. If your
implementation sends incremental updates containing only what changed, **every peer will withdraw
every route you did not mention.** Always send the whole (split-horizon filtered) table.

### Split horizon, on send

When building an update *for* peer P, omit every route whose next hop is P.

```
getRoutesExcludingNextHop(P) = { e ∈ table : e.nextHop ≠ P }
```

Advertising a route back toward the neighbour you learned it from is precisely what creates
distance-vector loops and count-to-infinity. It is also what makes the withdrawal rule above work
correctly: if your path to some destination now runs *through* the receiver, omitting it lets the
receiver correctly drop any route it had via you.

### Untrusted peers

When the link is untrusted ([02](02-peering.md#trust-negotiation)), filter the split-horizon table
down to destinations in that peer's **exposed servers** list before sending. An untrusted peer with an
empty exposure list receives an empty `<routing-update/>` — which is well-formed and means "I can
reach nothing you are allowed to know about".

## `routing-solicit`

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f5'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-solicit/>
  </federation>
</iq>
```

No attributes, no children. *"Re-send me your routing table and your room knowledge."*

The receiver responds with a `routing-update` **and** a room-state push (its own
`room-advertisement` plus its cached knowledge of other origins' rooms, per
[04](04-rooms.md#room-advertisement)).

Solicit exists because updates are triggered, not periodic. If a route is lost, nothing will
spontaneously re-advertise an alternate path — the neighbour that has one already sent it once and
sees no change to trigger a resend. Soliciting breaks that deadlock.

Send a solicit when:

- a link comes up (part of the peer-up sequence), and
- any merge results in a destination becoming **unreachable** — then solicit from *all other* peers,
  so alternate paths re-form.

## Triggered updates

Convergence is event-driven. After any merge that reported changes, this implementation:

1. Handles now-unreachable destinations — evicts virtual occupants, drops cached rooms.
2. Re-syncs mapped rooms for destinations that became reachable again.
3. **Propagates the routing change to every other peer** (split-horizon filtered per peer).
4. Re-floods room knowledge, debounced.
5. If anything became unreachable, solicits from all other peers.

Step 4's debounce is worth copying. One topology change arrives as a *burst* of routing updates —
triggered updates plus solicit replies — and each full room flood gets relayed onward by every
receiver. Re-flooding per update rather than per burst is O(N² · rooms) of stanzas on every link flap.

There is no periodic full-table broadcast. A poll loop exists but only re-solicits; it does not
blindly re-advertise.

## Peer down

When a link drops (or the peer withdraws/disables):

1. Remove the direct entry for that domain.
2. Remove every route whose next hop is that domain.
3. Collect everything removed and gossip the change onward, so servers further out converge too.

The collected set is also what drives cleanup at the application layer — see
[05-muc-traffic.md](05-muc-traffic.md#eviction) for the ghost-occupant consequences.

## Worked example

Topology: `alpha — beta — gamma`, and gamma also peers with `delta`.

```xml
<!-- gamma tells beta what it can reach. gamma's own direct peers are beta and delta. -->
<iq type='set' from='gamma.example' to='beta.example' id='g1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update>
      <entry destination='delta.example' hops='1' via='delta.example'/>
    </routing-update>
  </federation>
</iq>
<!-- Note: gamma does NOT advertise beta (split horizon: beta IS the next hop),
     and does NOT advertise itself. -->
```

beta merges: `delta.example → via gamma.example, 2 hops`. That is a change, so beta triggers an update
to its other peers — alpha:

```xml
<iq type='set' from='beta.example' to='alpha.example' id='b7'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update>
      <entry destination='gamma.example' hops='1' via='gamma.example'/>
      <entry destination='delta.example' hops='2' via='gamma.example'/>
    </routing-update>
  </federation>
</iq>
```

alpha merges: `gamma → via beta, 2` and `delta → via beta, 3`. alpha can now address `delta.example`
in a routed action's `destination` attribute, and it will get there.

Now the `beta—gamma` link drops. beta purges both routes and tells alpha — by sending a table that no
longer mentions them:

```xml
<iq type='set' from='beta.example' to='alpha.example' id='b8'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update/>
  </federation>
</iq>
```

alpha's stale check fires: gamma and delta were learned from beta, they are absent from this snapshot,
and their next hop is still beta — so both are withdrawn. An empty `<routing-update/>` is not a no-op;
**it is a complete withdrawal of everything you previously advertised.**

---

Previous: [02-peering.md](02-peering.md) · Next: [04-rooms.md](04-rooms.md)
