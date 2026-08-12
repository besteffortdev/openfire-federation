# 04 — Rooms: advertisement, mapping, probing

Two separate mechanisms, often confused:

- **Advertisement** tells the network a room *exists* and may be federated with. It is flooded, and it
  moves no chat traffic whatsoever.
- **Mapping** pairs a room here with a room there, after an explicit consent handshake. Only an
  **ACTIVE mapping** causes traffic to flow ([05](05-muc-traffic.md)).

A third action pair, `mapping-ping`/`mapping-pong`, probes whether an active mapping's path actually
works end to end.

## `room-advertisement`

```xml
<iq type='set' from='beta.example' to='alpha.example' id='f6'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-advertisement origin='gamma.example' via='gamma.example,beta.example'>
      <room jid='ops@conference.gamma.example'
            name='Operations'
            description='Shared ops room'
            visibleto='alpha.example,beta.example'/>
      <room jid='news@conference.gamma.example'
            name='News'
            description=''
            visibleto='*'/>
    </room-advertisement>
  </federation>
</iq>
```

Flooded, not routed: no `destination`. A receiver caches the content and re-emits it to its own other
peers, appending itself to `via`.

### Attributes

| On | Attribute | Meaning |
|----|-----------|---------|
| `room-advertisement` | `origin` | Server that homes these rooms. **Absent means the sender's own rooms.** |
| | `via` | Comma-separated loop trail ([01](01-transport.md#the-via-trail)). Absent/empty on first emission. |
| `room` | `jid` | Full room JID. Required. |
| | `name` | Human-readable name. Emitted as `""` when unset, not omitted. |
| | `description` | Same. |
| | `visibleto` | Visibility ACL — see below. |

### Visibility (`visibleto`)

A comma-separated list of server domains allowed to see this room, or the single sentinel `*` meaning
"all peers". **The ACL travels with the advertisement**, so every relaying server can enforce it and
off-path servers never learn the room exists.

A relaying server forwards a room toward peer P only if:

```
visibleto contains "*"                              → yes
visibleto contains P                                → yes
visibleto contains some domain whose next hop is P  → yes   (P is on the path to an allowed server)
otherwise                                           → no
```

> **An absent `visibleto` attribute parses as the empty set, which means visible to nobody.** Such a
> room is cached but never relayed onward. This is a secure default, and it is also a real interop
> hazard: if your implementation omits the attribute, your rooms will be visible to your direct peers
> and will stop dead there. **Always emit `visibleto`**, using `*` if you have no ACL concept.

### Withdrawal

**An advertisement with no `<room/>` children is a withdrawal** — it clears every cached room for that
`origin`, and is relayed onward so downstream servers clear theirs too.

```xml
<room-advertisement origin='gamma.example' via='gamma.example,beta.example'/>
```

Critically, it clears **only that origin's** rooms — not everything received from the sending
neighbour. A hub relays many origins' rooms; treating a withdrawal as "forget everything from this
sender" would wipe the entire cache every time one origin stopped federating.

### Receiver algorithm

```
if via contains our own domain                       → drop (loop)
source := origin, or sender if origin absent
if source == our own domain                          → drop (our rooms bouncing back)
if this peer's advertisements for `source` are admin-denied → drop, do not relay

for each <room/>:
    if jid fails the JID safety check                → skip this room, log
    parse visibleto into a set (absent → empty set)
    accumulate

if no rooms accumulated → clear cached rooms for `source`
else                    → replace cached rooms for `source`

relay onward to other peers, with via + our own domain,
    filtered per peer by visibility and by untrusted-peer exposure
```

The **JID safety check** is normative and covered in
[09-validation.md](09-validation.md#peer-supplied-jids). Peer-supplied room JIDs get cached, routed on,
and rendered in an admin UI; a JID containing quotes or angle brackets is not a JID.

### Replaying the cache

When a link comes up, send your own `room-advertisement` **and then one per cached remote origin**,
each with that origin's `origin` attribute and your own domain as the `via` trail. Without this, a
server joining mid-session never learns about rooms advertised before it arrived — advertisements are
event-driven, and nothing re-floods them spontaneously.

## The mapping lifecycle

Mapping pairs `room@conference.alpha.example` with `room@conference.beta.example` so their occupants
see each other. It is a **consent handshake**: the requester's stanza creates nothing but a pending
request, and no traffic flows until the other admin accepts.

```
                  room-mapping           ┌──────────────┐
   alpha  ────────────────────────────▶  │  PENDING_IN  │  beta
   (PENDING_OUT)                         └──────┬───────┘
                                                │ admin accepts
                  room-mapping-accept (token)   │
          ◀─────────────────────────────────────┘
   ACTIVE ◀──────────────────────────────────▶ ACTIVE     traffic flows

   later:  room-mapping-disable (token)  →  DISABLED_REMOTE at the far end
           room-mapping-enable  (token)  →  back to ACTIVE
           room-unmap           (token)  →  gone, occupants evicted
```

States this implementation tracks: `PENDING_OUT`, `PENDING_IN`, `ACTIVE`, `DISABLED_LOCAL`,
`DISABLED_REMOTE`, `REJECTED`. **Only `ACTIVE` forwards traffic.**

### Shared element shape

Every mapping action has the same structure:

```xml
<ACTION destination='beta.example' origin='alpha.example' token='…' reason='…'>
  <map local='chat@conference.alpha.example' remote='chat@conference.beta.example'/>
</ACTION>
```

| Attribute | Notes |
|-----------|-------|
| `destination` | Final destination. Routed action ([01](01-transport.md#the-routed-action-decision)). |
| `origin` | The server that initiated this mapping relationship. Absent → sender. |
| `token` | Consent token. Present on accept/disable/enable/unmap; absent on request and reject. |
| `reason` | Optional free text, `room-mapping-reject` only. |

Multiple `<map/>` children are permitted and each is processed independently.

### The `local`/`remote` inversion

**This is the single most error-prone part of the protocol.** The attributes are written from the
**sender's** point of view and the receiver must invert them:

```
sender writes:      local  = MY room
                    remote = YOUR room

receiver reads:     the map's `remote`  is MY local room
                    the map's `local`   is THEIR remote room
                    the `origin` attr   is THEIR domain
```

So a receiver storing a mapping records `localRoomJid = map/@remote`, `remoteRoomJid = map/@local`,
`remoteDomain = @origin`. Get this backwards and mappings appear to install correctly and then move no
traffic at all.

The inversion is **not** re-applied on relay: an intermediate hop forwards `local` and `remote`
unchanged.

### `room-mapping` — the request

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f7'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-mapping destination='beta.example' origin='alpha.example'>
      <map local='chat@conference.alpha.example' remote='chat@conference.beta.example'/>
    </room-mapping>
  </federation>
</iq>
```

The destination stores it as **PENDING_IN** and forwards nothing. Two authorisation checks run first,
and both MUST be implemented:

1. **`roomSharedWith(remote, origin)`** — the local room named by `map/@remote` must be
   federation-enabled here *and* its visibility ACL must include the origin domain. Without this, any
   peer that ever observed a room JID — including a transit hop that merely relayed an advertisement
   toward somebody else — could map an arbitrary local room and siphon its roster and messages.
2. **Untrusted-peer exposure** — if the sending link is untrusted, our own domain must be in that
   peer's exposed-servers list.

A room may be configured to **auto-accept**, in which case the destination immediately proceeds to the
accept step. Nothing on the wire distinguishes an auto-accept from a human one.

### `room-mapping-accept`

```xml
<room-mapping-accept destination='alpha.example' origin='beta.example'
                     token='7f3aa1c9e04b8d62f5a0…'>
  <map local='chat@conference.beta.example' remote='chat@conference.alpha.example'/>
</room-mapping-accept>
```

The **accepting** side mints the token — this implementation uses a 40-character random string — and
stores it against the mapping before sending. Both ends then hold the same token, and every later
lifecycle action for that mapping must carry it.

Note the inversion again: the accept is sent *by* beta, so `local` is now beta's room.

On accept, this implementation also pushes its room roster to the requester so their clients see the
existing occupants immediately. That is `muc-forward` traffic ([05](05-muc-traffic.md)), not part of
the mapping handshake.

### `room-mapping-reject`

```xml
<room-mapping-reject destination='alpha.example' origin='beta.example' reason='not for external orgs'>
  <map local='chat@conference.beta.example' remote='chat@conference.alpha.example'/>
</room-mapping-reject>
```

No token — there is no mapping to authenticate against yet. The rejecting side drops its pending
record; the requester marks its side `REJECTED`. `reason` is optional free text for display.

### `room-mapping-disable` / `room-mapping-enable`

```xml
<room-mapping-disable destination='alpha.example' origin='beta.example' token='7f3aa1c9…'>
  <map local='chat@conference.beta.example' remote='chat@conference.alpha.example'/>
</room-mapping-disable>
```

Suspend an active mapping without dissolving it. The far end shows "disabled by peer" and cannot
re-enable it itself; the disabling side sends `room-mapping-enable` (same shape, same token) to
restore it. Disabling evicts the virtual occupants that arrived through that mapping.

### `room-unmap`

```xml
<room-unmap destination='alpha.example' origin='beta.example' token='7f3aa1c9…'>
  <map local='chat@conference.beta.example' remote='chat@conference.alpha.example'/>
</room-unmap>
```

Dissolves the mapping permanently. The receiver pushes leave presences for the departing virtual
occupants, removes **only that originator's** mapping — other spokes on the same local room stay
connected — and evicts the occupants that arrived through it.

**Relay hops MUST carry the token through unchanged.** An intermediate server that drops it causes the
final destination's token check to fail (non-empty stored versus empty received), so the unmap is
silently discarded and hub-relayed occupants are left behind as ghosts.

### Token validation

```
stored := token of our mapping (localJid, remoteDomain)
if no such mapping                          → reject the action
if stored is empty                          → accept  (legacy peer, pre-token)
if stored == presented                      → accept
otherwise                                   → reject, log SECURITY
```

The empty-stored fallback exists for mappings created before tokens. If your implementation does not
mint tokens, send the actions without a `token` attribute; a peer that has a stored token for the
mapping **will reject them**. Mint tokens.

Note there is no timing-safe comparison here and no replay window — the token defends against a third
party forging lifecycle actions for a mapping it is merely relaying, not against the peers themselves.

### Relaying mapping actions

An intermediate hop re-emits the action unchanged except for the IQ addressing, preserving `origin`,
`token`, `reason` and every `<map/>`. For `room-mapping` specifically the relay also applies the
untrusted exposure gate against the `destination`, per `<map/>`.

Mapping actions carry **no `via` trail**. Loop protection comes from the routing table alone: a
destination has exactly one next hop, so a cycle cannot form unless the routing table itself is
inconsistent. Do not add one; a peer would ignore it.

## `mapping-ping` / `mapping-pong`

An end-to-end liveness probe for an active mapping.

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f8'>
  <federation xmlns='urn:xmpp:federation:1'>
    <mapping-ping destination='gamma.example' origin='alpha.example' ts='1754899200000'/>
  </federation>
</iq>
```

```xml
<!-- gamma answers, routed back toward alpha -->
<iq type='set' from='gamma.example' to='beta.example' id='g4'>
  <federation xmlns='urn:xmpp:federation:1'>
    <mapping-pong destination='alpha.example' origin='gamma.example' ts='1754899200000'/>
  </federation>
</iq>
```

| Attribute | Meaning |
|-----------|---------|
| `destination` | Where the probe is headed. |
| `origin` | Who to route the answer back to (ping), or who is answering (pong). |
| `via` | Loop trail, appended by relays. Reset to empty by the answering server. |
| `ts` | **Opaque.** Echoed back byte-for-byte so the prober can compute an RTT on its own clock. This implementation uses epoch milliseconds; do not parse or interpret a peer's value. |

### Why it exists

A route deny, an exposure filter, or a broken mapping partway along the path is **invisible** to the
servers on either end — their routing tables still show the destination as reachable while replies die
silently in the middle. Only a full round trip proves the path works.

### Answering

At the destination:

```
if origin is absent or empty                  → ignore
if we have NO active mapping with `origin`    → do NOT answer
else                                          → send mapping-pong toward origin,
                                                 echoing ts, with an empty via
```

**Refusing to answer without an existing mapping is a security requirement**, not an optimisation.
Answering unconditionally turns the probe into a reachability scanner for arbitrary domains across
somebody else's overlay.

### Relaying

Standard routed relay, plus a loop check on `via` and the untrusted exposure gate against
`destination`. **No route toward the destination means drop silently** — for a ping that is exactly
the case being detected, and the prober learns of it by the missing pong, not by an error.

### Interpreting results

This implementation probes every active mapping every `plugin.federation.mappingPingSeconds`
(default **30 s**, `0` disables, effective values below 15 are clamped). **Three consecutive
unanswered probes** — roughly two minutes at the default — flags the mapping as *not responding* and
drops its remote occupants, so users do not stare at ghosts. Pongs resuming clears the flag and
re-syncs rosters.

One subtlety worth copying: a domain is only eligible for the *not responding* verdict once it has
**provably answered or sent a probe at least once**, and that proof is persisted across restarts.
Otherwise every peer running an implementation that does not support the probe would be permanently
flagged as broken. If you do not implement `mapping-ping`, you will simply never be flagged.

---

Previous: [03-routing.md](03-routing.md) · Next: [05-muc-traffic.md](05-muc-traffic.md)
