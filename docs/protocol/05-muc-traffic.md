# 05 — MUC traffic over a mapping

Once two rooms have an ACTIVE mapping ([04](04-rooms.md)), their traffic crosses the overlay as
`muc-forward`. One action carries everything: messages, joins, leaves, presence changes, roster syncs.
A second pair, `room-catchup-request` and `room-catchup-done`, replays what a broken path lost
([Catch-up after a split](#catch-up-after-a-split), since 1.13.0).

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
                 src='alpha.example'
                 hubs='alpha.example'>
      <message type='groupchat'
               from='amy@alpha.example/phone'
               to='chat@conference.alpha.example'>
        <body>morning</body>
        <stanza-id xmlns='urn:xmpp:sid:0'
                   by='chat@conference.alpha.example'
                   id='5f1c9a2e-0b7d-4c1e-9f3a-6d2b8e4a7c10'/>
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
| `hubs` | no | Servers whose copy of the room already has the payload and sent it on to their own mappings: the originating server, then each hub. **Appended, never reset.** See [Rings and meshes](#rings-and-meshes). Absent = empty. At most 32 entries. (Since 1.11.2.) |
| `sent` | no | Marks a **replay**: when the message was first sent, as an XEP-0082 timestamp. Absent on live traffic. (Since 1.13.0; see [Catch-up after a split](#catch-up-after-a-split).) |
| `catchup` | no | On a replay that answers a `room-catchup-request`: that request's id. (Since 1.13.0; same section.) |

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

Do not rewrite the payload's `to` either, and **do not strip the origin room's `<stanza-id/>`**
(XEP-0359) from a message payload. A groupchat message SHOULD carry exactly the stanza-id its origin
room assigned, with `by` equal to the payload's `to`. Receivers reuse that id so the message has the
same id in every copy of the room (see [Room identity](#room-identity-stanza-id-and-occupant-id)). A
relay or hub forwards the payload unchanged, so the id survives any number of hops.

### Forward only what the room accepted

The sending server MUST forward a groupchat message or a join only after its **own room accepted it**.

- **Message**: forward it only if the room broadcast it to its own occupants. A message from a sender
  who is not an occupant, or who was banned or kicked, or who has no voice, is refused by the room and
  MUST NOT be forwarded.
- **Subject change**: forward it only if the sender is an occupant and the room's subject now equals
  the requested one.
- **Join or status change** (presence with no `type`): forward it only if the sender is an occupant of
  the room after processing. A refused join (banned, members-only, wrong password, room full) MUST NOT
  be forwarded.
- **Leave** (`type='unavailable'`): forwarded as is. It only ever removes the sender's own nick.

The receiver cannot enforce this: the sender's room rules are not visible across the overlay, and the
injected copy bypasses the receiving room's own checks by design. An implementation that forwards
whatever its clients send lets a banned or never-joined user post into, or appear in, every federated
copy of the room.

This implementation forwards from a post-processing packet interceptor, so it sees refused stanzas
too. It gates public messages on Openfire's message-received event, which fires only after the room
broadcast the message, and joins on occupancy after processing. (Since 1.10.11; earlier versions
forwarded refused messages and joins.)

## Receiver algorithm

```
if via contains our own domain                     → drop (loop)
if hubs has more than 32 entries                   → drop
if no payload child                                → drop
if payloadOriginOk fails                           → drop (09-validation)
if sender is untrusted AND src is neither the sender
   nor a server routed through it                  → drop (09-validation)

targetServer := destination, or our own domain if destination absent/ours
if untrusted-peer exposure denies targetServer     → drop

if destination is absent or ours:
    ── WE ARE THE DESTINATION ──
    if targetRoom is not a federation-enabled local room       → drop, log SECURITY
    if targetRoom has NO ACTIVE mapping                        → drop, log SECURITY
    if targetRoom has no ACTIVE mapping whose remote domain == src → drop, log SECURITY
    if hubs contains our own domain                            → drop (been round)
    if sent is present                                         → the replay checks
                                                                 (Catch-up after a split)
    inject into targetRoom
    if the room took it as new (not a duplicate, not a self-echo):
        fan out to every other ACTIVE mapping on that room
else:
    ── WE ARE A RELAY ──
    newVia := via + our own domain
    if we have an ACTIVE mapping whose REMOTE room == targetRoom,
       whose remote domain == destination, whose local room is federation-enabled,
       hubs does not contain our own domain,
       and (sender trusted OR our own domain is exposed to it):
        inject into that local room too          ← see below
        if it took it as new: fan out to that room's other mappings, as a hub,
                              listing destination as a hub too; add our domain to hubs
    relay toward destination, preserving src and hubs, with newVia
```

### Two authorisation gates, both required

**Federation-enabled** and **has an active mapping** are separate checks, and skipping either opens a
hole:

- Injection bypasses the MUC non-occupant check, so without the *federation-enabled* test any reachable
  peer could inject messages or spoofed presence into **any** local room it knows the JID of.
- *Federation-enabled* alone is not consent. A room tagged for federation whose mappings are all
  pending, rejected or disabled must stay closed. Check for at least one **ACTIVE** mapping.
- And that mapping must be with **the server the traffic enters through** — `src`. Every legitimate
  sender stamps `src` with its own domain and sends only along its own active mappings (a hub stamps
  itself on fan-out), so `src` names the far end of exactly one of your mappings. "Some active mapping
  exists" let any peer write into a room that was mapped only to somebody else. (Since 1.10.8.)

### Why a relay may also inject

A server can be both a transit hop for a destination further along *and* a mapped participant in the
same room. It injects locally on the way through, because it will not receive the hub's fan-out later —
its own domain will already be in the `via` trail by then.

Having taken the payload, it is also a hub for its own other mappings on that room, and fans out to
them. Before 1.11.2 it did not, and those spokes relied on a second copy arriving later; that copy is
now dropped as a duplicate, so the relay must send it on itself. It lists `destination` in the hub
trail of what it fans out, since the copy it relays is already on its way there.

The mapping consulted here is keyed by **remote** room JID (`targetRoom` names a room elsewhere), and
it must be ACTIVE and its local side federation-enabled.

## Injection

### Messages

```
room   := local room named by targetRoom              (missing → drop)
nick   := bare JID from payload/@from                 ("amy@alpha.example/phone" → "amy@alpha.example")

if nick's domain == our own domain                    → drop (self-echo, see below)

deliverable := copy of the payload
stamp deliverable with this room's stanza-id and the nick's occupant-id   (see below)

for each real occupant of room:
    copy deliverable
    set from = "<room node>@<room domain>/<nick>"
    set to   = that occupant's real full JID
    mark as forwarded (fed-origin)
    deliver directly to the session
```

Stamp a copy. The payload itself continues to fan out with the origin's stanza-id intact.

This implementation additionally writes the message into the room's history and fires its MUC
message-received event so archiving works — local concerns, invisible on the wire, but worth knowing
if you wonder why injected messages appear in MAM.

If the room currently has **zero** occupants, this implementation buffers the delivery briefly (60 s,
20 per room) and flushes it when someone joins, so a client reconnecting from a network blip still
receives it live. Optional; the archive path covers longer gaps.

### Room identity: stanza-id and occupant-id

A MUC service gives every message it broadcasts a `<stanza-id by='room'/>` (XEP-0359) and, if it
advertises `urn:xmpp:occupant-id:0`, an `<occupant-id/>` (XEP-0421). Clients depend on both:

- In a group chat, a client addresses an existing message by the **room-assigned stanza-id**, and only
  trusts one whose `by` is the room it is in. That covers reactions (XEP-0444), replies (XEP-0461),
  retraction (XEP-0424) and moderation (XEP-0425). Without it, Conversations refuses to react ("Could
  not add reaction").
- Conversations ignores a reaction in a channel that carries no `<occupant-id/>`.

An injected message bypasses the room's own broadcast, so the injecting server MUST add both itself.

**stanza-id.** Reuse the origin room's id so one message has the same id in every copy of the room. A
reaction made on any server then names a message every other server knows, with no id translation
table:

```
originRoom := bare JID of the payload's `to`
id         := the id of the payload's <stanza-id/> whose `by` == originRoom,
              if originRoom is not this room
              and the id matches [A-Za-z0-9._:-]{16,128}
              and this room has not used that id yet
              otherwise a fresh random id
remove every <stanza-id/> from the copy
add <stanza-id xmlns='urn:xmpp:sid:0' by='<this room>' id='<id>'/>
```

Each condition closes a specific abuse; see [09-validation.md](09-validation.md#room-identity-elements).
In short: only the origin room's own stamp is unforgeable by users, an id the room already holds would
let two messages share one, and a short numeric id could collide with an archive's fallback ids.

**occupant-id.** Remove every `<occupant-id/>` from the copy and add one computed locally for the
virtual occupant. It MUST be stable for that remote user in that room, across messages, presence and
reconnects, and MUST NOT collide with a real occupant's. This implementation uses Openfire's own
derivation for a real occupant (an HMAC over the room and the user's bare JID, taken from the nick).
The value is local to each room; servers do not need to agree on it.

Write the **stamped** copy into history and archives, so a client catching up via join history or MAM
sees the same stanza-id it would have seen live. (Since 1.10.10.)

```xml
<!-- what amy receives on alpha, for a message zed sent in gamma's room -->
<message type='groupchat'
         from='ops@conference.alpha.example/zed@gamma.example'
         to='amy@alpha.example/phone'>
  <body>deploy is green</body>
  <stanza-id xmlns='urn:xmpp:sid:0' by='ops@conference.alpha.example'
             id='5f1c9a2e-0b7d-4c1e-9f3a-6d2b8e4a7c10'/>
  <occupant-id xmlns='urn:xmpp:occupant-id:0' id='d756fbaca69a80d24314d370fa790da46bc7440f'/>
  <fed-origin xmlns='urn:xmpp:federation:1'/>
</message>
```

A reaction is an ordinary groupchat message (`<reactions xmlns='urn:xmpp:reactions:0' id='…'/>`, no
body) and takes the same path as any other, so it needs no special handling once the ids line up. So
is a user retracting their own message (XEP-0424): clients accept it only when its occupant-id matches
the original's, and the receiver computes both.

### Moderation (XEP-0425)

A moderator's retraction of someone else's message is different. The room announces it from its
**bare JID**, and clients obey that envelope without checking who wrote the message. Relayed as is, a
moderator on any mapped server could remove anyone's message from every copy of the room. So:

> **Only the author's own server may moderate a message across the federation.** A moderation of a
> message written by another server's user applies on the moderating server only.

Both ends enforce this. The sender filters, and every receiver checks again, because a modified peer
would not filter.

**Sending side.** When a mapped room announces a moderation (a groupchat from the room's bare JID
carrying `<retract xmlns='urn:xmpp:message-retract:1'><moderated xmlns='urn:xmpp:message-moderate:1'/>`),
forward it only if the retracted stanza-id belongs to a message **one of this server's users posted in
this room** and that was forwarded from it. Skip one carrying a `<delay/>`, which is a replay to a late
joiner, and forward each moderation once however many occupants receive a copy. The payload is a fresh
announcement addressed from and to the origin room's bare JID:

```xml
<muc-forward destination='alpha.example' targetRoom='ops@conference.alpha.example'
             via='gamma.example' src='gamma.example'>
  <message xmlns='jabber:client' type='groupchat' id='…'
           from='ops@conference.gamma.example' to='ops@conference.gamma.example'>
    <retract xmlns='urn:xmpp:message-retract:1' id='5f1c9a2e-0b7d-4c1e-9f3a-6d2b8e4a7c10'>
      <moderated xmlns='urn:xmpp:message-moderate:1'
                 by='ops@conference.gamma.example/mod@gamma.example'/>
      <reason>off topic</reason>
    </retract>
  </message>
</muc-forward>
```

`id` on `<retract/>` is the origin room's stanza-id for the message. The resource of `by` is the
moderator's bare JID, the same `user@home` form their own messages' virtual nick takes. `<reason/>` is
optional and capped at 1,024 characters. No occupant-id: the receiver computes its own.

**Receiving side.** An injected payload that carries `<retract><moderated/></retract>` is handled as a
moderation, never delivered as an ordinary message:

```
originRoom := bare JID of the payload's `to`
require payload `from` == originRoom, and originRoom is not this room        → else drop, SECURITY
drop if originRoom is one of our own rooms (an echo)
target     := the message this room received from originRoom with that stanza-id
require target is known (this room remembers the last 2,000)                → else drop
require target's author belongs to originRoom's server                       → else drop, SECURITY
  (author's domain == originRoom's domain or its parent: conference.gamma → gamma)
drop if target was already moderated (a second path through a diamond)
announce to this room's occupants, from this room's bare JID:
  <retract id='<this room's id for target>'>
    <moderated by='<this room>/<moderator user@home>'>  ← only if that user belongs to originRoom's server
      <occupant-id id='<computed locally for the moderator>'/>
    </moderated>
    <reason/>                                          ← if present
  </retract>
keep it, and replay it (with <delay/>) to each local occupant who joins while the
  target is still in the room's history
```

The payload `from` is validated like any other (rule 3, [09](09-validation.md#3-payload-origin--the-from-spoofing-gate)),
so `originRoom` is a room behind the sending path. A hub fans the payload out unchanged and each spoke
checks it again, so a hub cannot moderate a spoke's users either.

An ordinary relayed message has any `<moderated/>` removed before delivery. Clients already ignore
one that does not come from the room's bare JID, which a relayed message never does; this keeps it so.

What this implementation does not cover:

- The moderation itself. Openfire has no XEP-0425 handler, so a moderation plugin is needed on the
  moderating server. Receiving servers need none: they deliver the standard announcement.
- An admin clearing a room's history. Openfire's bulk retraction has no `<moderated/>` and stays local.
- Moderations of messages older than the last 2,000 in the room, or received before a restart: the
  bookkeeping is in memory, so both ends refuse them. For the same reason a receiving server forgets
  its replays on restart, and a late joiner then sees those messages again in the join history.
- Archives. The announcement has no body, so Openfire's history and Monitoring's MAM do not store it.
  The original stays in the receiving server's archive.

(Since 1.10.12.)

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
  <occupant-id xmlns='urn:xmpp:occupant-id:0' id='d756fbaca69a80d24314d370fa790da46bc7440f'/>
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
- Add the virtual occupant's `<occupant-id/>`, the same value as on its messages (see
  [Room identity](#room-identity-stanza-id-and-occupant-id)). XEP-0421 requires it on presence when
  the room advertises the feature. Never copy one from the payload.

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
    its remote domain is in the INCOMING hubs   (already has it, and sent it on)

for each surviving mapping:
    via  := OUR OWN DOMAIN ONLY        ← reset, not appended
    src  := OUR OWN DOMAIN             ← we are the mapped server for this spoke
    hubs := INCOMING hubs + OUR DOMAIN ← appended, never reset
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

### Rings and meshes

Neither guard is enough once rooms are mapped in a cycle that does not pass through the author's home.
In a full mesh of four (alpha, beta, gamma, delta, each mapped to every other), a message from alpha
reaches beta, which fans out to gamma and delta; gamma fans out to delta, delta to beta, beta to gamma
again, and it circles indefinitely. A ring of four does stop, because every path ends at alpha, but the
message goes both ways round and arrives twice. Two rules, since 1.11.2:

- **Drop a second copy of a message.** The room it was posted to and the stanza-id that room gave it
  (the `<stanza-id/>` whose `by` is the payload's `to`) name one message on every path. Remember, per
  local room, which (origin room, origin id) pairs have arrived (this implementation keeps the last
  2000); deliver only the first, and **do not fan out** a copy you dropped. Make check-and-record
  atomic: two copies can arrive together. A copy of a message that is new is delivered as before; a
  stanza-id reused by a *different* message is still replaced with a fresh one.
- **Carry a hub trail.** `hubs` names every server whose copy of the room already has the payload and
  sent it on. A hub appends itself and skips those already listed; a destination that finds itself
  listed drops the payload. Unlike `via` it is never reset, so it ends the cycle for everything —
  including presences and messages from a server with stanza-ids switched off, which carry no id to
  match. It is per server, like every other fan-out rule here.

Between them a message reaches each copy of the room exactly once on a ring or mesh, and a presence
always stops, though a mesh can still deliver it to a room more than once.

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

## Catch-up after a split

> **Since 1.13.0.** Earlier versions neither send nor answer these actions, and ignore the new
> `muc-forward` attributes ([10](10-conformance.md#version-compatibility)).

`muc-forward` is best effort ([01](01-transport.md#ordering-and-reliability)). While a mapping's path
is down, each copy of the room keeps working for its own server's users. Everything sent across the
mapping in that time is lost, though: a relay with no route drops it, and nothing sends it again. When
the path comes back, [occupant sync](#occupant-list-synchronisation) rebuilds who is present. The
messages stay lost.

Catch-up closes that gap. When a mapping's path comes back, each side asks the other for the room
traffic it missed. The answer arrives as ordinary `muc-forward`s, marked as replays and stamped with
the time they were first sent. Two existing rules make this work:

- A message keeps its origin room's stanza-id in every copy of the room
  ([Room identity](#room-identity-stanza-id-and-occupant-id)). "Everything after message X" therefore
  names the same point on both servers.
- Each copy of the room delivers a message once ([Rings and meshes](#rings-and-meshes)). A replay that
  overlaps what arrived live costs nothing.

```
   alpha                                beta
     │        ── path lost ──             │   both copies keep chatting locally
     │        ── path back ──             │   route re-learned, S2S reconnected, probe recovered
     │  room-catchup-request ───────────▶ │   "after message X"
     │  ◀────────────── muc-forward × n   │   sent=…, catchup=…
     │  ◀───────────  room-catchup-done   │   complete='true'
     │  ◀─────────  room-catchup-request  │   beta asks too
     │  muc-forward × m ────────────────▶ │
     │  room-catchup-done ──────────────▶ │
```

### The room journal

A server answers catch-up requests from a **journal**: the groupchat payloads its copy of a mapped
room accepted, in the order the room accepted them. Each entry holds:

| Field | Meaning |
|-------|---------|
| payload | The message as it is forwarded. A local user's message is kept exactly as it went out live ([Payload addressing](#payload-addressing)); an injected one is kept as it arrived, unchanged. |
| origin room, origin id | The payload's `to`, and the stanza-id that room gave the message: the pair the duplicate check uses. |
| author | The bare JID of the payload's `from`. |
| src | The mapping the message arrived over. None for a local user's message. |
| hubs | The `hubs` trail it arrived with. Empty for a local user's message. |
| accepted | When this room accepted it, on this server's clock. |
| sent | When it was first sent: the `sent` it arrived with if it was itself a replay, otherwise `accepted`. |

Journal the groupchat payloads a live `muc-forward` carries: messages, reactions, retractions and
[moderations](#moderation-xep-0425). Leave out three kinds:

- **Subject changes.** A replayed one could overwrite a newer subject, and the room's subject is not
  what catch-up repairs.
- **Messages without an origin stanza-id.** Nothing could tell a replay of one from a new message.
- **Presence.** Occupant sync already rebuilds it.

Keep entries for at least the **catch-up window**. Older traffic is never replayed. This
implementation:

- **Window:** 24 hours, from `plugin.federation.catchup.windowHours` (at most 168), with at most 10,000
  entries per room.
- **Storage:** in memory and in one append-only file, `federation-journal/journal.tsv`, readable by the
  Openfire user only because it holds message text. The file sits beside Openfire's plugins directory,
  followed through symlinks. That is `/var/lib/openfire` in the Docker image and the Debian package,
  whose home directory does not survive a reinstall or a new container. It is reloaded at
  start and rewritten when an hourly prune drops expired entries.
- **Duplicate check:** the journal backs it as well, so the check survives a restart.

### The watermark

For each mapping, the requester keeps a **watermark**: the origin room and origin id of the newest
message that arrived over that mapping, and when it arrived, on the requester's clock. Persist it. A
restart is the most common split of all, and a watermark held only in memory would forget where to
resume.

- Every message that arrives over the mapping moves the watermark forward, except during a catch-up on
  that mapping ([Requesting](#requesting)).
- When the mapping becomes ACTIVE ([04](04-rooms.md#the-mapping-lifecycle)), by accept or by enable,
  the watermark is reset to no message and the time of activation. Traffic from before the mapping
  existed, or from while an admin had it disabled, is never replayed.

**Nothing from before the requester's journal began.** A server asks for no replay older than its own
journal, and accepts none, because it cannot tell whether it already has the message. Losing the
journal then costs the catch-up for the outage it was in, not a flood of duplicates: the alternative is
a server that asks for the whole window again and delivers every message a second time. This
implementation records when its journal began in the file's header.

This implementation reads the watermark from its journal: the newest message that arrived over the
mapping since it became ACTIVE. That makes it as durable as the journal. It stores the activation time
with the mapping. A mapping that was ACTIVE before 1.13.0 has no recorded activation time, so its floor
is the window alone.

### `room-catchup-request`

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f40'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-catchup-request destination='beta.example' origin='alpha.example'
                          token='7f3aa1c9e04b8d62f5a0…'
                          request='cu-0c4e7d1a9b2f'
                          afterRoom='chat@conference.delta.example'
                          after='9b3e61c0-58aa-4f0e-b1d2-7e4c0a9f2d65'
                          since='2026-10-25T14:02:11Z'
                          max='50'>
      <map local='chat@conference.alpha.example' remote='chat@conference.beta.example'/>
    </room-catchup-request>
  </federation>
</iq>
```

| Attribute | Required | Meaning |
|-----------|----------|---------|
| `destination` | yes | The far end of the mapping, which answers. Routed action. |
| `origin` | yes | The requester. Answers are routed back to it. |
| `token` | yes | The mapping's consent token ([04](04-rooms.md#token-validation)). |
| `request` | yes | Chosen by the requester, unique per page, matching `[A-Za-z0-9._:-]{8,64}`. Echoed on everything sent in answer to this page. |
| `cursor` | no | On every page after the first: the `next` of the previous page's `room-catchup-done`, copied as is. Digits only, at most 18. |
| `afterRoom`, `after` | together | On the first page: the watermark, resume after this message. Absent when the watermark names none. |
| `since` | yes | XEP-0082 timestamp where the responder starts when neither `cursor` nor `after` places it. It is the watermark's arrival time less a margin for clock skew (5 minutes here), or the activation time. |
| `max` | no | The most items to send in this page. Default 50; the responder clamps it to 1–100. |

The single `<map/>` names the mapping from the sender's point of view, and the receiver inverts it as
for every mapping action ([04](04-rooms.md#the-localremote-inversion)). `local` is the requester's
room, `remote` the responder's. Like the mapping actions, it carries no `via` trail.

### Answering

```
on room-catchup-request, destination == us:
    R := origin;  room := map/@remote;  theirRoom := map/@local
    if R fails the claimed-origin check (09 rule 3b)               → drop, SECURITY
    if the sending link is untrusted and our domain is not exposed
       to it                                                       → drop, SECURITY
    if room is not federation-enabled, or has no ACTIVE mapping
       with R whose remote room is theirRoom                       → drop, SECURITY
    if the token check fails (04)                                  → drop, SECURITY
    if request, cursor, since or max is malformed                  → drop
    if we are still sending a page on this mapping, or sent 2,000
       items on it in the last hour                                → drop

    start := the entry after cursor, if there is one,
             else the entry after (afterRoom, after) if the journal holds it,
             else the first entry accepted at or after since
    never start before the window, or before the mapping last became ACTIVE here

    from start, oldest first, examining at most 10 × max entries:
        skip if its src is R                    (R sent it to us)
        skip if its author's home is R          (R has the original)
        skip if its hubs names R                (R's copy already had it)
        skip a message moderated since, and that moderation,
             when the message is itself in range   (R should see neither)
        send it to R as a replay (below); stop once max are sent

    send room-catchup-done
    if no catch-up of ours is in progress on this mapping
       and none finished in the last 60 s                          → send our own request
```

The last step makes a heal work both ways even when only one side noticed it.

On moderations: a moderation of a message R already had, older than the range, is sent, because R must
apply it. A message both posted and moderated during the split is sent as neither.

Each item is a `muc-forward` built from the journal entry:

```xml
<iq type='set' from='beta.example' to='alpha.example' id='g41'>
  <federation xmlns='urn:xmpp:federation:1'>
    <muc-forward destination='alpha.example'
                 targetRoom='chat@conference.alpha.example'
                 via=''
                 src='beta.example'
                 hubs='gamma.example,beta.example'
                 sent='2026-10-25T14:07:52Z'
                 catchup='cu-0c4e7d1a9b2f'>
      <message type='groupchat'
               from='zed@gamma.example/desktop'
               to='chat@conference.gamma.example'>
        <body>deploy is green</body>
        <stanza-id xmlns='urn:xmpp:sid:0'
                   by='chat@conference.gamma.example'
                   id='5f1c9a2e-0b7d-4c1e-9f3a-6d2b8e4a7c10'/>
      </message>
    </muc-forward>
  </federation>
</iq>
```

- `targetRoom` is the requester's room. `src` is the responder, the mapped server for this mapping.
  `via` is empty.
- `hubs` is the entry's recorded trail with the responder appended. The requester's own fan-out then
  skips the servers that already have the message.
- `sent` is the entry's `sent`. `catchup` is the request's id.
- The payload is the journaled one, unchanged. Its origin `from`, `to` and stanza-id are what make the
  receiver's checks and its duplicate test work exactly as they do on live traffic.

Items pass every outbound check a live `muc-forward` does, including the untrusted exposure gate. Send
them oldest first along the route, paced so that a long replay does not starve live traffic on the
link. This implementation paces them at the file relay's chunk delay, `plugin.federation.files.chunkDelayMs`
(20 ms by default).

**A relay passes `sent` and `catchup` on unchanged**, like every other attribute of a routed action. A
relay that dropped them would hand the destination a replay that looks like live traffic. It would be
delivered as new, at the time it arrived, and the page would never count it.

### `room-catchup-done`

```xml
<room-catchup-done destination='alpha.example' origin='beta.example'
                   token='7f3aa1c9e04b8d62f5a0…'
                   request='cu-0c4e7d1a9b2f'
                   count='50' complete='false'
                   next='18342'>
  <map local='chat@conference.beta.example' remote='chat@conference.alpha.example'/>
</room-catchup-done>
```

| Attribute | Meaning |
|-----------|---------|
| `destination` | The requester. |
| `origin` | The responder. |
| `token` | The mapping's token. |
| `request` | The request this page answers. |
| `count` | How many items this page sent. |
| `complete` | `true` when the journal holds nothing after the page, `false` when there is more. |
| `next` | A cursor for the next page, opaque to the requester: the responder's position just after the last entry **examined**, sent or skipped. Digits only. |

The cursor marks the last entry examined, not the last one sent, so a page whose entries were all
skipped still moves forward. It is a position rather than a message id because a moderation has no id
of its own to resume after. This implementation uses the journal entry's sequence number, which
survives a restart.

A refused request gets no answer at all, like every other failed check
([09](09-validation.md#rule-ordering)). The requester learns of it by timing out.

### Requesting

A requester sends `room-catchup-request` on an ACTIVE mapping when:

- the mapping's far server becomes reachable again, because its route was re-learned or its S2S link
  reconnected, or when the mapping's probe recovers from *not responding*
  ([04](04-rooms.md#interpreting-results)). These are the events that re-sync occupants.
- its own server starts: for each mapping with a persisted watermark, once routes have converged. In
  this implementation the links coming up after a start are those same events.
- the responder's own request arrives and the last step of [Answering](#answering) calls for one.

At most one catch-up per mapping is in progress at a time. A trigger during one is ignored. This
implementation waits 5 s after a trigger before it asks. A reconnect raises several triggers at
once, and the wait lets the routes back settle.

```
send room-catchup-request, with after and since from the watermark
on room-catchup-done for it, once count items carrying its id have arrived
   (or after a 5 s grace):
    complete is false and fewer than the cap received   → request the next page, with a new
                                                          request id, cursor := next, and
                                                          since unchanged
    otherwise                                           → finished
no room-catchup-done within 60 s                        → abandon; the next trigger retries
```

The cap bounds one catch-up; this implementation's is 1,000 items per mapping. It also counts its own
retry as a trigger: an abandoned catch-up is tried again 30 s later, three attempts in all. A peer
older than 1.13.0 never answers, so that costs three requests per outage.

During a catch-up, live arrivals do not move the watermark; replayed ones do.

- **Finished:** the watermark becomes the newest message that arrived over the mapping, live or
  replayed.
- **Abandoned:** the watermark stays at the last replayed message, so the next trigger resumes there.
  Live messages from the meantime come back once more and are dropped as duplicates.

### Receiving a replay

A `muc-forward` with `sent` is a replay. It takes the receiver algorithm above, with these checks
added at the destination before injection:

```
if catchup is present:
    if we sent no request with that id in the last 60 s, on a mapping whose
       local room is targetRoom and whose remote domain is src                   → drop, SECURITY
    if that request has already received max items                              → drop, SECURITY
if sent is not a valid XEP-0082 timestamp                                        → drop
if sent is older than the window, older than this server's journal, or earlier
   than when targetRoom's mapping with src last became ACTIVE                    → drop
if sent is later than now + 5 minutes                                            → sent := now
```

Injection then differs from live traffic in five ways:

- **Duplicates are checked against a record covering the whole window.** A replay is old by
  definition, and may arrive after a restart has emptied an in-memory record. The check MUST cover the
  window and SHOULD survive a restart. This implementation checks the journal, for live traffic too.
- **Time.** Remove every `<delay xmlns='urn:xmpp:delay'/>` (and legacy `jabber:x:delay`) from the copy
  and add the room's own: `<delay xmlns='urn:xmpp:delay' from='<this room>' stamp='<sent>'/>`. Clients
  then show the message at the time it was written (XEP-0203). Archive the stamped copy when it
  arrives, like any other message, and do **not** move the archive row back to `sent`.
  - Openfire's join history keeps a delay the message already carries.
  - A MAM archive is read in the order it was written and paged by archive id (XEP-0313). A row moved
    back in time sits out of order. Openfire's Monitoring plugin orders a page by time but asks for the
    next one by id, so a client paging through the room skips the moved rows, or the rows they jumped
    over, depending on direction. The stamped `<delay/>` stays inside the archived message.
- **Subject changes** are not replayed. A replay carrying one is dropped.
- **Moderations** look their target up in the journal, so a moderation replayed after a restart still
  finds its message. Live moderations use the same fallback, on both the sending and the receiving
  side.
- **Fan-out** happens as it does for a live message that is new here. It keeps `sent` and drops
  `catchup`, since the other mappings did not ask. Their duplicate checks drop whatever they already
  had. This is what heals the spokes behind a hub: when the link between alpha and hub beta comes back,
  beta catches up from alpha and fans the replays out to gamma and delta.

### What catch-up does not do

- **Room traffic only.** 1:1 messages lost on a broken path are not replayed. Presence, affiliations,
  bans and room configuration changed on either side during a split are not merged. Occupant sync
  rebuilds who is present, nothing more.
- **No gap detection.** Catch-up resumes after the newest message that arrived. A message lost while
  the path otherwise worked sits before the watermark and is not repaired: one stanza dropped while
  later ones got through.
- **Nothing for a stall TCP recovers from.** When a link stalls without breaking, the stanzas queued
  on it arrive once it recovers, as live traffic: shown and archived at the time they arrive. Catch-up
  only replays what was lost, and these were not.
- **No backfill for new mappings.** The watermark resets on activation, so a newly mapped room starts
  without the other side's history.
- **MAM shows when a replay arrived.** The archive files a replay when it arrives, after the messages
  written while it was missing. A client that shows a MAM result at the archive's own time (the
  `<delay/>` on `<forwarded/>`, as Conversations does) shows it there, and the original time is only in
  the message's inner `<delay/>`. In exchange, a client resuming MAM by time or by archive id finds the
  replay either way. Occupants online when it arrives, and join history, see the original time.
- **No new trust.** A replay passes the same checks as live traffic: the payload's `from` behind the
  sending path ([09](09-validation.md#3-payload-origin--the-from-spoofing-gate)), an ACTIVE mapping
  with `src`, the exposure gate. But `sent` is the responder's word. A mapped peer can backdate the
  messages it carries by up to the window, much as a trusted peer can already misrepresent the users it
  relays ([09](09-validation.md#the-trusteduntrusted-asymmetry)).

## Direct room access, without a mapping

Separately from mapping, a user can address a remote room directly over the overlay
(`room@conference.gamma.example`) — including ad-hoc or private rooms that were never advertised. That
traffic travels as `direct-forward`/`presence-forward`/`iq-forward` ([06](06-direct-traffic.md)), not
as `muc-forward`, and is delivered to the MUC service normally rather than injected.

This implementation gates it with `plugin.federation.allowRemoteRoomTraversal` (default **on**). When
turned off, remote-origin stanzas aimed at a local room are rejected unless an explicit mapping
exists. There is no wire signalling for this: a peer with traversal disabled simply drops the traffic.

Across an **untrusted** edge the toggle does not apply: a sender whose server is an untrusted peer, or
is reached through one, may only address rooms that are federation-enabled and shared (visibility ACL)
with its server or with that edge — plus a `disco#info` query on the MUC service itself, which clients
send before joining. The toggle exists for the trusted mesh; leaving it on must not hand an untrusted
partner every public room on the server. (Since 1.10.8.)

---

Previous: [04-rooms.md](04-rooms.md) · Next: [06-direct-traffic.md](06-direct-traffic.md)
