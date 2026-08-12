# 09 — Validation rules

Normative. Everything here is a rule a receiver applies to inbound traffic before acting on it. An
implementation that skips these will appear to interoperate — traffic flows, rooms fill up — while
being trivially abusable by any peer on the mesh.

Two structural facts shape all of it:

1. **The overlay has no end-to-end authentication.** Stanzas are not signed. Trust is hop-by-hop,
   exactly as in S2S. A relaying server can read and alter anything it carries.
2. **A payload's `from` and `to` are claims, not facts.** Native S2S validates a stanza's `from`
   against the authenticated stream domain. The overlay embeds stanzas inside a stanza, so that
   validation does not happen — you must do it yourself.

Everything below follows from those two.

## Rule ordering

```
1.  peer allowlist              — before dispatch, uniformly
2.  loop guard (via)            — before any processing
3.  payload origin (from)       — before relay AND before delivery
4.  untrusted exposure          — per action, against a server domain
5.  recipient binding (to)      — final hop only
6.  application authorisation   — room federated? mapping active? token valid?
```

Ordering matters. Check 1 first so it covers every action uniformly. Check 3 before relaying, not
only before delivering — a forged identity must not be laundered onward by a hop that was not itself
the destination.

Every failure is **drop and log**, never an error reply. See
[01-transport.md](01-transport.md#acknowledgement) — you still return `<iq type='result'/>`.

This implementation tags every such log line with `SECURITY:` so an operator can grep for them. Adopt
the convention or your own; the point is that these are auditable events, not debug noise.

---

## 1. The peer allowlist

```
if allowlist mode is enabled and the sending domain is not an approved peer:
    log; drop; return result
```

Applied **once, before dispatch**, so it covers every action including ones added later.

This implementation defaults the allowlist **on**, meaning both sides must have explicitly added each
other. Turning it off gives open federation with auto-registration of any server that connects.

If your XMPP server's S2S is itself restricted to known domains, this is redundant. If it is
internet-facing, this is the trust boundary.

## 2. Loop guard

```
if our own domain appears as an ELEMENT of the comma-split via trail:
    drop; do not process; do not relay; do not error
```

Applies to every action carrying `via`. **Split and compare element-wise.** A substring search
false-positives whenever one server's domain is a substring of another's — `beta.example` inside
`edge-beta.example` — which silently drops legitimate traffic in a way that is very hard to diagnose.

Note the two loop guards that `via` cannot provide, both in [05](05-muc-traffic.md):

- Hub fan-out **resets** the trail, so a payload can legitimately return to a server already in the
  old trail. The payload's home domain is skipped explicitly instead.
- The **self-echo guard** at injection catches the rest: never inject a user whose home domain is your
  own.

## 3. Payload origin — the `from`-spoofing gate

Applies to `muc-forward`, `direct-forward`, `presence-forward`, `iq-forward`, at **every** hop.

This is the overlay's substitute for S2S stream-level `from` validation. Without it, a peer can
deliver a stanza claiming to be from anybody.

```
payloadFrom := embedded stanza's from attribute
if absent or empty                        → allow (no identity claimed)
untrusted := is the sending link untrusted?

domain := JID-parse payloadFrom → domain part
if unparseable:
    untrusted → drop
    trusted   → allow      (preserves existing behaviour)

host := originHost(domain)                 ← see below

── Tier 1: local claim ──
if host == our own domain, or domain is one of our MUC services:
    if rejectLocalClaim OR untrusted       → DROP
    else                                   → allow   (trusted muc-forward echo)

── Tier 2: route-back, untrusted senders only ──
if not untrusted                           → allow
if host == the sending domain              → allow   (the peer's own users)
hop := next hop toward host
if no route to host                        → allow   (unverifiable by design)
if hop == the sending domain               → allow   (origin is behind the sender)
if claimedEntry is present and != sender:
    entryHop := next hop toward claimedEntry
    if entryHop == the sending domain      → allow   (hub fan-out)
DROP
```

### `rejectLocalClaim`

| Action | Value | Why |
|--------|-------|-----|
| `direct-forward`, `presence-forward`, `iq-forward` | **true** | No legitimate inbound 1:1 forward carries one of our own users as sender. A forged `subscribed` here flows straight into the roster engine; a forged `set` IQ mutates server-side state as the claimed user. |
| `muc-forward` | **false** | On a cyclic or diamond topology, a local user's own room traffic legitimately echoes back through a hub fan-out. The self-echo guard at injection neutralises it silently, so rejecting here would break real flows. |

### `originHost`

A payload may claim to originate at a *component* — `conference.gamma.example` — which is never a
routing destination in its own right. Resolve to the host server:

```
if domain is the sender, is local, or is a known routing destination  → domain
strip the first label: conference.gamma.example → gamma.example
if THAT is the sender, is local, or is routable                       → the parent
otherwise                                                             → domain unchanged
                                                                         (then unroutable → allowed)
```

### The trusted/untrusted asymmetry

Tier 2 is **not applied to trusted peers**, and that is deliberate rather than an oversight. Asymmetric
distance-vector routes — diamond topologies — and hub fan-out both make legitimate trusted traffic
arrive from a direction other than the origin's own route. A strict check there would break working
deployments. **The trusted mesh is a documented trust boundary**: a trusted peer can misrepresent
users of domains it relays, and you are relying on its operator not to.

### The hub fan-out allowance

`claimedEntry` is the `muc-forward` `src` attribute ([05](05-muc-traffic.md#the-src-attribute)) — the
mapped entry server, which a fan-out hub re-stamps with its own domain. When it differs from the
sender *and* routes through the sender, a spoke's user legitimately reaches you from the hub's
direction rather than its own.

`src == sender`, or `src` absent, earns **no** allowance. An off-route origin on the sender's own word
is precisely the forgery this gate exists to stop. Use the **raw** attribute here, not the
fall-back-to-sender value used elsewhere.

**Residual risk, stated plainly:** an untrusted peer already on the relay path to a mapped hub can
claim origins behind itself. This grants no new power — an on-path relay can already tamper with the
legitimate traffic it carries, because there is no end-to-end signing.

## 4. Untrusted-peer exposure gate

An untrusted peer is given an explicit list of **server domains** it may see and act on. Everything
else is invisible to it and refused from it.

```
untrustedAllowsServer(peer, serverDomain):
    if peer is not untrusted        → allow
    return serverDomain ∈ exposedServers(peer)
```

Where it applies, and against what:

| Action | Checked against |
|--------|-----------------|
| `room-mapping`, relay branch | the `destination` |
| `room-mapping`, local branch | **our own domain** |
| `mapping-ping` / `mapping-pong`, relay | the `destination` |
| `muc-forward` | the `destination`, or our own domain when we are it |
| `file-*`, relay branch | the `destination` |
| `file-*`, addressed to us | **our own domain** |
| `user-directory`, `bookmark-push` | refused outright from an untrusted link ([08](08-directory.md)) |

Two notes:

**The local branches matter as much as the relay branches.** A gate that only covers traffic passing
*through* leaves an untrusted peer free to address the server directly — including `file-request`,
which asks you to hand over content. Both `room-mapping` and `file-*` check their local branch
explicitly. (The `file-*` local check was added in 1.10.6.)

**`direct-forward`, `presence-forward` and `iq-forward` are deliberately not gated.** 1:1 messaging
crossing an untrusted edge is the point of having one, and reachability is already bounded by the
filtered routing view an untrusted peer receives.

### Outbound side

The gate has a sending half, and both are required for the exposure model to mean anything:

- `routing-update` — filtered to exposed destinations ([03](03-routing.md#untrusted-peers)).
- `room-advertisement` — filtered to rooms homed on exposed servers, then by per-room visibility.
- `user-directory` / `bookmark-push` — never sent at all.

## 5. Recipient binding

Applies to `direct-forward`, `presence-forward`, `iq-forward` at the **final hop only**.

```
to := embedded stanza's to attribute
if absent or blank                        → DROP
if to's domain is our XMPP domain         → allow
if to's domain is one of our MUC services → allow
if to is a component registered here      → allow
DROP
```

The envelope's `destination` says where the overlay hop ends. It says nothing about who the payload is
addressed to. A peer can name you as the destination while embedding a recipient somewhere else — and
because the delivery branch hands the payload to your own router, your server emits it over native
S2S under its own identity. **That makes you an open relay for traffic you never authorised.**

Rule 3 does not catch this. It validates the sender; this validates the recipient. They are
independent claims and both must be checked.

For `presence-forward` this check MUST run **before** the probe branch. Answering a probe addressed to
another server's user discloses local presence to a peer with no standing to ask.

Added in **1.10.6**; peers older than that do not perform it.

## 6. Application authorisation

### Room injection — both checks, always

```
if targetRoom is not a federation-enabled local room       → DROP
if targetRoom has NO ACTIVE mapping                        → DROP
```

Injection deliberately bypasses the MUC service's non-occupant check, so the room's own protections
are not in the path. Without the first check, any reachable peer could inject or spoof into **any**
local room whose JID it knows. Without the second, a room tagged for federation but whose mappings are
all pending, rejected or disabled would still admit traffic — being federation-enabled is not consent.

### Mapping requests — reciprocity

```
if the named local room is not federation-enabled                    → reject
if its visibility ACL does not include the requesting origin         → reject
```

You may not map onto a room that was never offered to you. Without the ACL half, any peer that ever
observed a room JID — including a transit hop that merely relayed an advertisement toward someone
else — could map an arbitrary local room and siphon its roster and messages.

### Mapping lifecycle tokens

```
stored := token of the mapping (localJid, remoteDomain)
if no such mapping   → reject
if stored is empty   → accept    (legacy peer, pre-token)
if stored == presented → accept
otherwise            → reject, log SECURITY
```

Applies to `room-mapping-disable`, `-enable`, `room-unmap`. Relay hops MUST carry the token through
unchanged; dropping it makes the destination's check fail and the action is silently discarded.

### Probe answering

```
if we have no active mapping with the probe's origin → do NOT answer
```

Answering `mapping-ping` unconditionally turns it into a reachability scanner for arbitrary domains
across somebody else's overlay.

### Denied advertisements

An operator may refuse a specific destination when advertised by a specific peer. The refusal applies
to both `routing-update` entries and `room-advertisement`s for that origin, so routes and room
knowledge stay in step. A route to the same destination via a *different* peer is unaffected, and the
refusal is one-sided — nothing is negotiated with the peer.

## 7. Peer-supplied JIDs

Room JIDs from a `room-advertisement` get cached, routed on, and rendered in an admin UI. Validate
before any of that:

```
reject if empty, or longer than 3071 characters       (RFC 7622 maximum)
reject if it contains any character < 0x20, or 0x7f   (control characters)
reject if it contains  "  '  <  >  &  \  or space
require at least one '@' after position 0, or a '.'
```

None of those characters is valid in an XMPP domain or room JID, and each is a vector for markup
injection into an admin console. This is deliberately **not** full RFC 7622 validation — it closes the
injection and robustness surface without rejecting legitimate lab room JIDs.

## 8. File relay

Covered in [07](07-file-relay.md); collected here because they are security checks, not protocol
mechanics.

### Requester direction

```
if the sending link is trusted            → allow
if the claimed requester == the sender    → allow
if next hop toward the requester == the sender → allow
DROP
```

An untrusted peer's `file-request` must arrive on the route back to the domain it claims to be asking
for. A trusted peer's need not — diamonds and hub fan-out legitimately arrive off-path, the same
asymmetry rule 3 makes.

### Share capability

```
expected := token stored for this id
if expected is empty      → serve        (staged before capabilities existed)
if compare_constant_time(presented, expected) → serve
otherwise                 → file-error 'not-authorized'
```

Use a constant-time comparison. `id` is `SHA-256(url)` and travels with the announcement through every
server on the path — it identifies content, it is not a credential. This attribute is the credential.

### Reply direction

```
a file-offer, file-chunk or file-error must arrive from the next hop we sent the
file-request to; otherwise ignore and log
```

Stops an unrelated peer from injecting content into a transfer you started with somebody else.

### Offer geometry

```
size >= 0 and size <= configured maximum
chunkSize in [1, 1 MiB]
totalChunks >= 0  and  totalChunks == ceil(size / chunkSize)      exactly
```

This is the allocation bound. An offer claiming an implausible size or an inconsistent chunk count
must fail before anything is sized from it.

### Chunk length

```
expected := (seq == totalChunks-1) ? size - seq*chunkSize : chunkSize
if decoded length != expected → FAIL the transfer
```

Prevents a write outside the region the geometry accounted for.

---

## Summary table

| Rule | Applies to | Failure |
|------|-----------|---------|
| Peer allowlist | every action | drop |
| `via` loop guard | every action carrying `via` | drop |
| Payload origin | `muc-forward`, `direct-`/`presence-`/`iq-forward` | drop |
| Untrusted exposure | mapping, probes, `muc-forward`, `file-*` | drop |
| Recipient binding | `direct-`/`presence-`/`iq-forward`, final hop | drop |
| Room federation-enabled | `muc-forward` injection | drop |
| Active mapping exists | `muc-forward` injection | drop |
| Room shared with origin | `room-mapping` | reject that `<map/>` |
| Lifecycle token | mapping disable/enable/unmap | reject that `<map/>` |
| Mapping exists | `mapping-ping` answering | do not answer |
| JID safety | `room-advertisement` | skip that `<room/>` |
| Requester direction | `file-request` | ignore |
| Share capability | `file-request` | `not-authorized` |
| Reply direction | `file-offer`/`-chunk`/`-error` | ignore |
| Offer geometry | `file-offer` | fail transfer |
| Chunk length | `file-chunk` | fail transfer |

---

Previous: [08-directory.md](08-directory.md) · Next: [10-conformance.md](10-conformance.md)
