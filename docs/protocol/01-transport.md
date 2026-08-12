# 01 — Transport and envelope

Everything in this protocol is an `<iq type='set'/>` between two server domains, carrying one child
element in one namespace. This document defines that envelope. The rest of the specification only
defines what goes inside it.

## Namespace

```
urn:xmpp:federation:1
```

This is an unregistered namespace in the `urn:xmpp:` tree — it is not an XSF-approved XEP. Treat it as
a private protocol identifier; it is stable across all versions of this implementation.

## Stanza shape

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <ACTION .../>
  </federation>
</iq>
```

Rules a receiver enforces:

- The stanza type MUST be `set`. A `get` is answered with an empty `result` and otherwise ignored.
  A `result` or `error` stanza in this namespace is dropped silently and MUST NOT be answered — this
  is what stops two servers from ping-ponging error bounces at each other.
- `from` and `to` MUST be **bare domain JIDs** — no node, no resource. The receiver derives the
  sending peer's identity from `from`'s domain and from nothing else. Note that `peer-announce`
  carries a redundant `<server/>` element; it is **not** used for identity (see
  [02-peering.md](02-peering.md#the-redundant-server-element)).
- `<federation/>` MUST be the IQ's only child element and MUST carry the namespace above.
- `<federation/>` MUST contain at least one child. **The receiver processes the first child element
  and ignores any others.** Send exactly one action per stanza.

The action element's name selects the handler. The complete set:

| Action | Document | Routed? |
|--------|----------|---------|
| `peer-announce`, `peer-withdraw`, `peer-disable` | [02](02-peering.md) | link-local |
| `routing-update`, `routing-solicit` | [03](03-routing.md) | link-local |
| `room-advertisement` | [04](04-rooms.md) | flooded |
| `room-mapping`, `room-mapping-accept`, `room-mapping-reject`, `room-mapping-disable`, `room-mapping-enable`, `room-unmap` | [04](04-rooms.md) | routed |
| `mapping-ping`, `mapping-pong` | [04](04-rooms.md) | routed |
| `muc-forward` | [05](05-muc-traffic.md) | routed |
| `direct-forward`, `presence-forward`, `iq-forward` | [06](06-direct-traffic.md) | routed |
| `file-request`, `file-offer`, `file-chunk`, `file-error` | [07](07-file-relay.md) | routed |
| `user-directory`, `bookmark-push` | [08](08-directory.md) | flooded |

An unrecognised action name is logged and ignored. The stanza is still acknowledged. Do not rely on
an error to tell you a peer did not understand something — see [Acknowledgement](#acknowledgement).

## The two addressing layers

This is the single most important concept in the protocol, and the one most likely to trip up an
implementer who has only worked with plain S2S.

```
   IQ  from / to     →  THIS HOP.        Always adjacent servers with a live S2S link.
   destination attr  →  THE WHOLE PATH.  The server that finally consumes the action.
```

The IQ's `to` is the **next hop**. The action element's `destination` attribute is the **final
destination**. When a stanza crosses three servers, the IQ `to` changes at every hop and
`destination` never changes.

```xml
<!-- alpha wants gamma to see this; alpha only peers with beta. -->
<!-- Hop 1: alpha → beta -->
<iq type='set' from='alpha.example' to='beta.example' id='f1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <mapping-ping destination='gamma.example' origin='alpha.example' via='' ts='1754899200000'/>
  </federation>
</iq>

<!-- Hop 2: beta → gamma. New IQ, same action, via trail grew. -->
<iq type='set' from='beta.example' to='gamma.example' id='f2'>
  <federation xmlns='urn:xmpp:federation:1'>
    <mapping-ping destination='gamma.example' origin='alpha.example' via='beta.example' ts='1754899200000'/>
  </federation>
</iq>
```

**Three classes of action**, distinguished by how they travel:

- **Link-local** (`peer-announce`, `routing-update`, `routing-solicit`, `peer-withdraw`,
  `peer-disable`) — no `destination`. They describe the sender's own state to one neighbour and are
  never forwarded. A receiver acts on them and stops.
- **Flooded** (`room-advertisement`, `user-directory`, `bookmark-push`) — no `destination`, but an
  `origin` and a `via` trail. A receiver caches the content, then **re-emits it to its own other
  peers**, appending itself to `via`. This is how knowledge spreads to servers you have never heard
  of. Flooding is bounded by the `via` loop check and by policy filters.
- **Routed** (everything else) — carry `destination`. A receiver that is not the destination looks up
  the next hop in its routing table ([03](03-routing.md)) and re-emits toward it; a receiver that
  *is* the destination consumes it.

### The routed-action decision

Every routed action is handled with the same three-branch logic. Implement it once:

```
destination is absent OR destination == our own domain
    → we are the destination; consume it
otherwise
    → look up next hop for destination
        found   → re-emit toward next hop, appending ourselves to via
        missing → drop, log; do NOT bounce an error
```

Note that **an absent `destination` is treated as "for us"**, not as an error. This implementation
always emits it on routed actions; a receiver tolerates its absence.

## The `via` trail

`via` is a **comma-separated list of server domains**, in the order they relayed the stanza. Every
relaying server appends its own domain before re-emitting. It exists purely to break loops.

```
via=''                                    first hop, nobody has relayed yet
via='beta.example'                        beta relayed it
via='beta.example,delta.example'          then delta did
```

Rules:

- Before processing a stanza that carries `via`, a server MUST check whether **its own domain is
  already an element of the trail**. If so, the stanza has looped: drop it. Do not process, do not
  relay, do not error.
- Comparison MUST be **element-wise on the comma-split list, not a substring search**. `beta.example`
  is a substring of `edge-beta.example`; a naive `contains()` silently drops legitimate traffic. This
  implementation splits on `,`, trims each element, and compares for equality.
- A server appends itself **when it relays**, not when it consumes.
- Empty or absent `via` means an empty trail. Both forms are emitted by this implementation
  (attributes are omitted when empty on some actions, sent as `via=''` on others) and both MUST be
  accepted.

### The one overloaded attribute

On `routing-update` entries, `via` means something completely different: **the single next-hop domain
of one route**, not an accumulated trail. It is the same wire name for two unrelated readings, decided
by the element it sits on. This is a wart, documented rather than fixed because changing it would
break every deployed peer. See [03-routing.md](03-routing.md#the-entry-element).

## The `origin` attribute

`origin` names the domain a payload started at, as distinct from the neighbour that handed it to you.
It survives relaying unchanged.

Its precise meaning is per-action and you MUST NOT assume one reading everywhere:

- On a **flooded** action (`room-advertisement`, `user-directory`, `bookmark-push`) it identifies
  whose content this is. A receiver caches under `origin`, not under the sender.
- On a **mapping** action it identifies the server that initiated the mapping request.
- On a **`mapping-ping`/`mapping-pong`** it is the prober, i.e. where the answer must be routed back to.
- On a **`file-*`** action it is the *requester* for `file-request`, and the *holder* for
  `file-offer`/`file-chunk`/`file-error` — in other words, it names whichever end originated **that
  particular stanza**, so the reverse direction can be addressed.

When `origin` is absent on an action that uses it, the receiver falls back to the sending domain
(`from`). That fallback is what makes single-hop federation work with an implementation that omits it.

## Acknowledgement

**Every well-formed federation IQ is answered with an empty `<iq type='result'/>`.** Including one
that was dropped for a policy or security reason.

```xml
<iq type='result' from='beta.example' to='alpha.example' id='f1'/>
```

The only error a receiver generates is for a malformed envelope:

```xml
<iq type='error' from='beta.example' to='alpha.example' id='f1'>
  <error type='modify'>
    <bad-request xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>
    <text xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'>Empty federation element</text>
  </error>
</iq>
```

emitted for exactly two conditions: the `<federation/>` child element is missing, or it has no child
elements. Note that this implementation does **not** echo the offending child element back on the
error — the reply carries only `<error/>`. RFC 6120 §8.3.1 prefers the echo; nothing in this protocol
depends on it either way, since the only thing that can provoke this reply is a stanza that had
nothing worth echoing.

**Consequences for your implementation:**

- The result is a transport acknowledgement, nothing more. It does not mean the action was accepted,
  applied, authorised, or even understood.
- **You cannot learn whether a peer honoured anything from the IQ layer.** Confirmation is always
  in-band, via a *different* action arriving later: a `room-mapping` is confirmed by a
  `room-mapping-accept`, a route is confirmed by the destination becoming reachable, a path is
  confirmed by a `mapping-pong`. Design your state machines around that, not around IQ results.
- Do not treat a missing result as a protocol failure. Handle it the way you handle any S2S timeout.
- If you send an error where this specification says "drop silently", the peer will ignore it — this
  implementation drops inbound `error` and `result` stanzas in this namespace without processing.

## Ordering and reliability

The protocol assumes the ordering guarantees of the underlying XML stream: stanzas sent over one S2S
connection to one peer arrive in order. It assumes nothing more.

- **No ordering across hops.** A stanza relayed through beta and one sent directly to gamma may arrive
  in either order.
- **No delivery guarantee.** An S2S link that drops takes queued stanzas with it. Recovery is by
  periodic re-assertion, not retransmission: peers re-announce on a keepalive timer, routing is
  re-solicited after a topology change, mapping state is re-sent on reconnect.
- **Idempotence is required.** Every action in this protocol MUST be safe to apply twice. Duplicate
  `room-advertisement`s, repeated `routing-update`s and re-sent mappings are normal and frequent.
  `file-chunk` is the one action with explicit duplicate suppression (by sequence number).
- **No fragmentation.** A single action must fit in one stanza. This matters for `file-chunk`, whose
  size is bounded for exactly this reason ([07](07-file-relay.md#chunk-geometry)), and for
  `routing-update`/`room-advertisement`, which grow with topology size.

## Payload embedding

`muc-forward`, `direct-forward`, `presence-forward` and `iq-forward` carry a complete XMPP stanza as a
child element. The embedded stanza is copied verbatim, keeping its own `from`, `to`, `id` and all
extension elements.

```xml
<direct-forward destination='gamma.example' via='beta.example'>
  <message type='chat' from='amy@alpha.example/phone' to='zed@gamma.example' id='m17'>
    <body>hello from three hops away</body>
  </message>
</direct-forward>
```

Rules:

- The embedded stanza's `from`/`to` are **claims**, and must be validated before use. See
  [09-validation.md](09-validation.md) — this is where the two most serious classes of attack live.
- The embedding element carries at most one payload. `muc-forward` takes the first child element
  regardless of name; `direct-forward` looks specifically for `<message/>`, `presence-forward` for
  `<presence/>`, `iq-forward` for `<iq/>`. Send the matching type.
- Namespace prefixes and extension elements inside the payload MUST be preserved. An implementation
  that re-serialises the payload through a lossy model will break avatars (`vcard-temp:x:update`),
  file shares (`jabber:x:oob`), and MUC semantics (`http://jabber.org/protocol/muc#user`).

### The `fed-origin` marker

```xml
<fed-origin xmlns='urn:xmpp:federation:1'/>
```

An empty marker element this implementation attaches to a stanza before delivering it locally, so its
own outbound interceptor does not pick the stanza up and relay it back out. It is mostly an internal
loop guard — but it **does cross the wire in one case**, and there it is protocol-significant:

> A `muc-forward` carrying a `<presence/>` that contains `<fed-origin/>` is a **roster-sync presence**,
> not a fresh join. A receiver MUST NOT respond to it by syncing its own occupant list back.
> Without that check, two servers sync occupant lists at each other forever.

See [05-muc-traffic.md](05-muc-traffic.md#occupant-list-synchronisation). If you emit roster-sync
presences, mark them. If you receive one, do not reciprocate.

## A minimal receiver

```
on iq(type=set) with <federation xmlns='urn:xmpp:federation:1'>:
    if no <federation/> child          → error bad-request
    action := first child element
    if no action                       → error bad-request
    fromDomain := iq.from.domain

    if peerAllowlistEnabled and fromDomain not approved:
        log; return result             # see 09-validation

    dispatch on action.name            # unknown → log and ignore
    return result                      # always
```

Note the allowlist check sits **before dispatch**, so it covers every action uniformly. That is the
single most important structural decision in the receiver; everything else is per-action.

---

Next: [02-peering.md](02-peering.md) — bringing a link up.
