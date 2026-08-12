# 02 — Peering: bringing a link up

Three link-local actions establish, tear down and administratively block a federation link:
`peer-announce`, `peer-withdraw`, `peer-disable`. None of them are routed — they describe the sender's
own stance to one directly-connected neighbour.

The underlying S2S connection is assumed to exist already. This protocol never opens one; it *uses*
one, and in practice sending the first `peer-announce` is what causes the local server to dial the
peer.

## `peer-announce`

The only stanza that is strictly required to federate. It means: *"my federation module is running, I
have you configured as a peer, and here is my trust stance toward you."*

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce>
      <server>alpha.example</server>
      <version>1</version>
    </peer-announce>
  </federation>
</iq>
```

### Attributes

| Attribute | Values | Meaning when absent |
|-----------|--------|---------------------|
| `reply` | `"true"` | Not a reply — the receiver may answer with one. |
| `untrusted` | `"true"` | **Trusted.** This is the back-compat default for peers predating the trust feature. |

### The redundant `<server/>` element

`<server/>` repeats the sender's own domain and `<version/>` states protocol version `1`. **This
implementation reads neither.** Identity comes from the stanza's `from` domain, and no version
negotiation is performed.

Emit both anyway — they cost nothing and a future revision may start using `<version/>`. Never make a
security or routing decision based on `<server/>`: a peer can put anything in it, and the receiving
side that matters uses `from`.

### What the receiver does

In order:

1. **If we have this peer administratively DISABLED**, answer with `peer-disable` and stop. A disabled
   peer that removes and re-creates its side still gets re-blocked.
2. **If we had it marked REMOTE_DISABLED**, clear that. A peer only announces after re-enabling, so
   the announce itself is the signal that the remote lifted its block.
3. **Auto-register** if unknown. (In allowlist mode — the default — an unknown peer never reaches this
   point; see [09-validation.md](09-validation.md#the-peer-allowlist).)
4. **Record "remote confirmed"** — proof the other side has us configured. This is what moves a link
   from *pending* to *reachable*.
5. **Compare trust stances.** If ours and theirs disagree, block the link and stop.
6. **Install a direct route** to the peer at hop count 1 ([03](03-routing.md)).
7. **Gossip**, per the reply rules below.

### Reply rules

Getting this wrong produces either an infinite announce loop or a link that never converges. The rule:

```
if the peer was NOT already reachable:
    send full gossip           (see below) — and do NOT set reply='true'
else if the incoming announce did NOT carry reply='true':
    send exactly one peer-announce with reply='true'
else:
    send nothing
```

> **A reply never triggers a reply.** That single invariant is what terminates the exchange.

The asymmetry exists because S2S is two independent sockets, one per direction, each with its own idle
timer. One side's keepalive only warms the socket it writes on. The `reply` bounce warms the other.

### Full gossip

When a link first comes up, this implementation sends, in order:

1. `peer-announce` (ours, with our trust stance)
2. `routing-update` — our routing table, split-horizon filtered for this peer ([03](03-routing.md))
3. `room-advertisement` — our own federatable rooms, visibility filtered ([04](04-rooms.md))
4. one `room-advertisement` **per known remote origin** — replaying our cached knowledge of everyone
   else's rooms, so a server joining mid-session learns about rooms advertised before it arrived

and then runs the peer-up sequence: solicit routing, re-sync mapped room rosters, re-send pending
mapping requests, publish the user directory and bookmarks if enabled.

Order matters only in that routing SHOULD precede room advertisements — a receiver that learns about a
room on a server it cannot yet route to will cache it, but cannot act on it.

### Keepalive

`peer-announce` doubles as the keepalive. This implementation sends one to every reachable **and every
pending** peer every `plugin.federation.keepaliveSeconds` (default **240 s**, minimum 30 s), clamped
to 80% of the S2S idle timeout when that timeout is shorter.

Sending it to pending peers matters: a link where the remote has not yet added us back is exactly the
one that needs to keep re-asserting itself, so it confirms the moment the remote admin acts.

For your implementation: pick any interval comfortably inside your own S2S idle timeout. There is no
negotiated value and no timeout enforced by the receiver — a peer that stops announcing is detected by
the S2S link dropping, not by a protocol timer.

## Trust negotiation

Trust is **a property of the link, not of either endpoint**. Both sides must declare the same stance or
no federation flows.

```xml
<peer-announce untrusted='true'>
  <server>edge.partner.example</server>
  <version>1</version>
</peer-announce>
```

- Both sides say trusted (attribute absent) → link comes up, full gossip flows.
- Both sides say `untrusted='true'` → link comes up in **filtered** mode. The untrusted peer receives
  no routing updates and no room advertisements at all, except for servers its operator has explicitly
  exposed to it. Inbound traffic from it is gated the same way.
- **They disagree** → the link is **blocked**. No federation flows in either direction until both
  admins set the same value. It recovers automatically on the next announce once they match — no
  reconnect needed.

What "untrusted" actually changes on the wire is covered per-action throughout this specification and
collected in [09-validation.md](09-validation.md#untrusted-peer-exposure-gate). The short version: an
untrusted peer is told about, and may only act on, an explicit list of server domains.

If your implementation has no concept of untrusted peers, **omit the attribute and treat every
inbound peer as trusted**. You will interoperate with a peer that considers you trusted, and you will
be blocked by one that marks you untrusted — which is the correct outcome, since you could not honour
the exposure restrictions that stance implies.

## Link states

The states this implementation tracks. You do not have to model all of them, but you do have to
produce the wire behaviour each implies.

| State | Meaning | On the wire |
|-------|---------|-------------|
| `UNKNOWN` | Configured, never contacted | — |
| `PENDING` | S2S link is up, but **no `peer-announce` received yet** | We keep announcing; no routes or gossip sent toward it |
| `REACHABLE` | Link up and mutually confirmed | Full federation |
| `UNREACHABLE` | Link down | Routes through it purged and withdrawal gossiped |
| `WITHDRAWN` | Peer sent `peer-withdraw` | As unreachable; requires a fresh announce to recover |
| `DISABLED` | *We* blocked it | Any inbound announce answered with `peer-disable` |
| `REMOTE_DISABLED` | *They* blocked us | Cleared only by their next `peer-announce` |
| `TRUST_MISMATCH` | Stances disagree | Nothing flows; cleared automatically when they match |

### The mutual-add handshake

`PENDING` is the important one for interop. A live S2S link is **not** sufficient — this
implementation will not send routes or room knowledge to a peer that has not announced back. Both
sides must have added each other.

Practical consequence: **if you never send `peer-announce`, you will receive nothing.** A peer will
sit at PENDING, quietly re-announcing at every keepalive, and you will conclude the protocol does not
work. Send the announce.

(In open-federation mode — `plugin.federation.peerAllowlist=false` — an inbound announce from an
unknown server auto-registers it, so confirmation is instant. The default is the allowlist.)

## `peer-withdraw`

*"I am removing you as a peer."* Voluntary, symmetric, recoverable.

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f9'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-withdraw/>
  </federation>
</iq>
```

No attributes, no children. The receiver:

1. Removes every room mapping toward the withdrawing domain.
2. Purges the direct route **and every route learned through it**, collecting what was removed.
3. Marks the peer WITHDRAWN and clears "remote confirmed", so a future link goes to PENDING rather
   than straight to REACHABLE.
4. Evicts virtual occupants for every now-unreachable destination and drops their cached rooms — local
   clients see leave presences and stale rooms disappear.
5. Gossips the topology change onward, so servers two hops away also converge.

**Send your `room-unmap` stanzas before `peer-withdraw`, not after.** The receiver defensively tears
mappings down anyway, but doing it explicitly gives the other side's users a clean leave sequence
instead of a bulk eviction.

## `peer-disable`

*"I have administratively blocked this link, and you cannot unblock it from your side."*

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f10'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-disable/>
  </federation>
</iq>
```

Same teardown as `peer-withdraw`, plus one difference that matters: the receiver records a
**persistent** `REMOTE_DISABLED` state it cannot lift itself. Re-adding the peer locally does not
help. The block clears only when the disabling server sends a `peer-announce` again — which it will do
only after its own admin re-enables the link.

The disabling side re-asserts: any `peer-announce` arriving from a peer we have DISABLED is answered
with another `peer-disable` rather than with gossip. A peer that removes and re-creates its
configuration gets re-blocked immediately.

If you implement only one of the two teardown actions, implement `peer-withdraw`. Treat an inbound
`peer-disable` as a withdrawal that you additionally refuse to re-establish automatically.

## Worked example: a link coming up

`alpha` and `beta` have both been configured with each other; neither has announced yet.

```xml
<!-- alpha's S2S link to beta comes up; alpha announces. -->
<iq type='set' from='alpha.example' to='beta.example' id='a1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce><server>alpha.example</server><version>1</version></peer-announce>
  </federation>
</iq>

<!-- beta acknowledges the transport... -->
<iq type='result' from='beta.example' to='alpha.example' id='a1'/>

<!-- ...and, since alpha was not previously reachable, sends full gossip.
     Note: no reply='true' — this is a first-contact gossip, not a keepalive bounce. -->
<iq type='set' from='beta.example' to='alpha.example' id='b1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce><server>beta.example</server><version>1</version></peer-announce>
  </federation>
</iq>
<iq type='set' from='beta.example' to='alpha.example' id='b2'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update>
      <entry destination='gamma.example' hops='1' via='gamma.example'/>
    </routing-update>
  </federation>
</iq>
<iq type='set' from='beta.example' to='alpha.example' id='b3'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-advertisement>
      <room jid='ops@conference.beta.example' name='Ops' description='' visibleto='*'/>
    </room-advertisement>
  </federation>
</iq>

<!-- alpha receives beta's announce. beta was not yet reachable for alpha either,
     so alpha also sends full gossip (not a reply). Both sides converge. -->
```

Four minutes later, the keepalive:

```xml
<iq type='set' from='alpha.example' to='beta.example' id='a2'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce><server>alpha.example</server><version>1</version></peer-announce>
  </federation>
</iq>

<!-- beta is already reachable and this carried no reply flag → exactly one bounce back,
     which warms the reverse socket. It ends here. -->
<iq type='set' from='beta.example' to='alpha.example' id='b4'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce reply='true'><server>beta.example</server><version>1</version></peer-announce>
  </federation>
</iq>
```

---

Previous: [01-transport.md](01-transport.md) · Next: [03-routing.md](03-routing.md)
