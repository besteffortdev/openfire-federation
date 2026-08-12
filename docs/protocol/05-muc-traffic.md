# 05 — MUC traffic over a mapping

Once two rooms have an ACTIVE mapping ([04](04-rooms.md)), their traffic crosses the overlay as
`muc-forward`. One action carries everything: messages, joins, leaves, presence changes, roster syncs.

## The model: virtual occupants

Nobody actually joins the far room. Each server **injects** the other side's participants into its own
room as *virtual occupants* — presence and messages fabricated locally, delivered straight to each
real occupant's session.

```
   alpha.example                                   beta.example
   ┌────────────────────────┐                     ┌────────────────────────┐
   │ chat@conference.alpha  │                     │ chat@conference.beta   │
   │                        │    muc-forward      │                        │
   │  amy      (real)       │ ◀─────────────────▶ │  zed      (real)       │
   │  zed@beta.example (v)  │                     │  amy@alpha.example (v) │
   └────────────────────────┘                     └────────────────────────┘
```

Consequences you must design around:

- A virtual occupant's **nick is `user@homedomain`** — the bare JID of the real user, used verbatim as
  a MUC nickname. This makes nicks globally unique and lets any server derive a user's home domain
  from the nick alone, which the loop guards depend on.
- Injected stanzas **bypass the MUC service's own non-occupant check**, because the sender genuinely
  is not an occupant. That is why the authorisation checks below are not optional — the room's own
  protections are not in the path.
- A server's occupant list is authoritative only for its *real* occupants. Virtual ones are cached
  state that must be re-synced on reconnect and evicted on teardown.

## `muc-forward`

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f11'>
  <federation xmlns='urn:xmpp:federation:1'>
    <muc-forward destination='beta.example'
                 targetRoom='chat@conference.beta.example'
                 via=''
                 src='alpha.example'>
      <message type='groupchat'
               from='amy@alpha.example/phone'
               to='chat@conference.alpha.example'>
        <body>morning</body>
      </message>
    </muc-forward>
  </federation>
</iq>
```

| Attribute | Required | Meaning |
|-----------|----------|---------|
| `destination` | yes | Final destination server. Routed action. |
| `targetRoom` | effectively | The room JID **on the destination server** to inject into. Absent disables injection and fan-out entirely. |
| `via` | yes | Loop trail. Sent as `via=''` on first emission. |
| `src` | no | The **mapped server this traffic enters the destination through** — see below. Absent falls back to the sending domain. |

The payload is the first child element and MUST be a `<message/>` or a `<presence/>`.

### The `src` attribute

`src` is not the sender and not the user's home server. It is *"which of the destination's mappings
does this arrival belong to"* — the far end of the destination's own mapping, or a hub's domain when
the hub is re-originating a fan-out.

It is recorded against each virtual occupant as its `arrivedVia`, so that disabling one mapping evicts
exactly the occupants that came through it — including cross-spoke users relayed by a hub, whose home
domain is neither the hub nor the sender.

Rules:

- **A pure relay hop preserves `src` unchanged.** It is not the relay's domain.
- **A hub performing fan-out overwrites `src` with its own domain**, because from the receiving
  spoke's point of view the occupant genuinely enters through the hub.
- Absent → the receiver falls back to the sending domain, which degrades safely for older peers.

### Payload addressing

The embedded stanza keeps the **sender's own** addressing: `from` is the real user's full JID on their
home server, `to` is the room JID *on the sending server*. The receiver ignores the payload's `to` and
uses `targetRoom` instead, then rewrites `from` during injection.

Do not rewrite the payload's `from` before sending. Everything downstream — nick derivation, self-echo
guards, origin validation, the `jid` attribute in the injected `muc#user` item — reads it.

## Receiver algorithm

```
if via contains our own domain                     → drop (loop)
if no payload child                                → drop
if payloadOriginOk fails                           → drop (09-validation)

targetServer := destination, or our own domain if destination absent/ours
if untrusted-peer exposure denies targetServer     → drop

if destination is absent or ours:
    ── WE ARE THE DESTINATION ──
    if targetRoom is not a federation-enabled local room       → drop, log SECURITY
    if targetRoom has NO ACTIVE mapping                        → drop, log SECURITY
    inject into targetRoom
    fan out to every other ACTIVE mapping on that room
else:
    ── WE ARE A RELAY ──
    newVia := via + our own domain
    if we have an ACTIVE mapping whose REMOTE room == targetRoom
       and whose local room is federation-enabled:
        inject into that local room too          ← see below
    relay toward destination, preserving src, with newVia
```

### Two authorisation gates, both required

**Federation-enabled** and **has an active mapping** are separate checks, and skipping either opens a
hole:

- Injection bypasses the MUC non-occupant check, so without the *federation-enabled* test any reachable
  peer could inject messages or spoofed presence into **any** local room it knows the JID of.
- *Federation-enabled* alone is not consent. A room tagged for federation whose mappings are all
  pending, rejected or disabled must stay closed. Check for at least one **ACTIVE** mapping.

### Why a relay may also inject

A server can be both a transit hop for a destination further along *and* a mapped participant in the
same room. It injects locally on the way through, because it will not receive the hub's fan-out later —
its own domain will already be in the `via` trail by then.

The mapping consulted here is keyed by **remote** room JID (`targetRoom` names a room elsewhere), and
it must be ACTIVE and its local side federation-enabled.

## Injection

### Messages

```
room   := local room named by targetRoom              (missing → drop)
nick   := bare JID from payload/@from                 ("amy@alpha.example/phone" → "amy@alpha.example")

if nick's domain == our own domain                    → drop (self-echo, see below)

for each real occupant of room:
    copy the payload
    set from = "<room node>@<room domain>/<nick>"
    set to   = that occupant's real full JID
    mark as forwarded (fed-origin)
    deliver directly to the session
```

This implementation additionally writes the message into the room's history and fires its MUC
message-received event so archiving works — local concerns, invisible on the wire, but worth knowing
if you wonder why injected messages appear in MAM.

If the room currently has **zero** occupants, this implementation buffers the delivery briefly (60 s,
20 per room) and flushes it when someone joins, so a client reconnecting from a network blip still
receives it live. Optional; the archive path covers longer gaps.

### Presence

Presence is **rebuilt**, not copied, because the MUC semantics have to be constructed:

```xml
<!-- what arrives -->
<presence from='zed@beta.example/desktop' to='chat@conference.beta.example'>
  <show>away</show>
  <status>lunch</status>
  <x xmlns='vcard-temp:x:update'><photo>a1b2c3d4…</photo></x>
</presence>

<!-- what each local occupant receives -->
<presence from='chat@conference.alpha.example/zed@beta.example'
          to='amy@alpha.example/phone'>
  <show>away</show>
  <status>lunch</status>
  <x xmlns='vcard-temp:x:update'><photo>a1b2c3d4…</photo></x>
  <x xmlns='http://jabber.org/protocol/muc#user'>
    <item affiliation='none' role='participant' jid='zed@beta.example/desktop'/>
  </x>
  <fed-origin xmlns='urn:xmpp:federation:1'/>
</presence>
```

Rules:

- `type='unavailable'` means leaving: role becomes `none`, and `show`/`status`/avatar are not copied.
- Copy `<show/>`, every `<status/>`, and **every `<x xmlns='vcard-temp:x:update'/>`**. Dropping the
  avatar hash is a silent failure with a confusing symptom: the client never learns the occupant has a
  photo worth fetching, so it never issues the vCard request that would retrieve it, and avatars
  appear broken for reasons that look like a vCard bug.
- The `muc#user` `<item/>` carries `affiliation='none'`, `role='participant'` (or `none`), and
  `jid` set to the payload's **original full `from`** — that is how a local client learns the real JID
  behind the nick.

### The self-echo guard

> **Never inject a user whose home domain is your own.**

On a cyclic or diamond topology, a local user's own presence or message can come back around through a
hub's fan-out — which deliberately resets the `via` trail (see below) and so cannot be caught by the
loop check. The result is a user seeing a ghost copy of themselves in the room.

Compare the derived nick's domain against your own and drop. This is a **boundary** guard: it catches
the loop no matter which forwarding path produced it, which is why it is implemented at injection
rather than in any one relay path.

## Hub fan-out

A server with several spokes mapped onto the same local room bridges them automatically: after
injecting, it re-forwards the payload to every *other* ACTIVE mapping on that room.

```
   alpha ──▶ HUB(beta) ──▶ gamma
                  │
                  └──────▶ delta
```

```
skip a mapping if:
    its remote domain == the sender             (do not echo back)
    its remote domain is in the INCOMING via    (already processed, or is the source)
    its remote domain == the payload's home     (never echo a user to their own server)

for each surviving mapping:
    via  := OUR OWN DOMAIN ONLY        ← reset, not appended
    src  := OUR OWN DOMAIN             ← we are the mapped server for this spoke
    targetRoom := that mapping's remote room
    relay toward that mapping's remote domain
```

### Why the trail is reset

Carrying the incoming trail forward would block legitimate relays: a server between the hub and a
spoke may already appear in it, and would drop the stanza. Resetting is what lets fan-out cross
multi-hop paths at all.

The cost is that the reset trail no longer protects against a downstream relay sending the payload
back toward its origin — which is precisely why **the payload's home domain is skipped explicitly**,
and why the self-echo guard exists as a second line of defence. Implement both.

## Occupant list synchronisation

When a virtual occupant **joins**, the receiving server pushes its own occupant list back so the
joining user immediately sees who is already present. Each occupant is sent as a synthetic presence
inside a `muc-forward`, targeted at the joiner's room.

The sync presences are **marked with `<fed-origin/>`** ([01](01-transport.md#the-fed-origin-marker)):

> A receiver MUST NOT respond to a presence carrying `<fed-origin/>` by syncing its own occupants
> back. Two servers that both reciprocate will sync at each other indefinitely.

### Choosing where to send it

The join does not necessarily arrive from the joiner's home server — on a multi-hop path it arrives
from a relay that has no mapping back to the joiner. Sending the sync to the relay reaches nobody.
Target selection is tiered:

1. the mapping whose remote domain is the **joiner's home** (derived from their nick) — reaches them
   through any number of relays; else
2. the mapping for the domain the join actually **arrived from**; else
3. **all** mappings — true multi-hop where the joiner's home is not directly mapped.

Tier 1 first matters for more than correctness: it keeps the common case at exactly one mapping
instead of duplicating the whole roster to a relay as well.

Alongside the real occupants, forward the **virtual** occupants reached through you, excluding the
target peer's own users — otherwise a spoke sees only the hub's directly-connected clients rather than
the whole room.

## Eviction

Virtual occupants are cached state and must be torn down when the thing that justified them goes away.
Each tracked occupant carries two keys: its **home** domain (from the nick) and its **arrivedVia**
(from `src`).

Evict when:

| Event | Evict |
|-------|-------|
| Route to a destination lost ([03](03-routing.md)) | Every virtual occupant homed on it |
| `peer-withdraw` / `peer-disable` ([02](02-peering.md)) | Everything reachable through that peer |
| `room-mapping-disable` / `room-unmap` ([04](04-rooms.md)) | Occupants whose `arrivedVia` is that mapping |
| Three unanswered `mapping-ping`s ([04](04-rooms.md)) | Occupants of that mapping |

Eviction means sending `type='unavailable'` presence for each, from the virtual JID, to every real
occupant — clients then see a normal leave rather than a name that stays in the list forever.

A leave is also propagated onward to other mappings, but **only on the first removal**. A duplicate
leave — one arriving via fan-out and one via state-driven forwarding — removes nothing and must not
re-forward, or leaves multiply at every hop.

## Direct room access, without a mapping

Separately from mapping, a user can address a remote room directly over the overlay
(`room@conference.gamma.example`) — including ad-hoc or private rooms that were never advertised. That
traffic travels as `direct-forward`/`presence-forward`/`iq-forward` ([06](06-direct-traffic.md)), not
as `muc-forward`, and is delivered to the MUC service normally rather than injected.

This implementation gates it with `plugin.federation.allowRemoteRoomTraversal` (default **on**). When
turned off, remote-origin stanzas aimed at a local room are rejected unless an explicit mapping
exists. There is no wire signalling for this: a peer with traversal disabled simply drops the traffic.

---

Previous: [04-rooms.md](04-rooms.md) · Next: [06-direct-traffic.md](06-direct-traffic.md)
