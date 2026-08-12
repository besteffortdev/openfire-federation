# 06 — Direct traffic: 1:1 messages, presence, IQ

Three actions with an identical envelope, differing only in which stanza type they carry:

| Action | Payload | Carries |
|--------|---------|---------|
| `direct-forward` | `<message/>` | 1:1 chat, and groupchat aimed at a room reached directly |
| `presence-forward` | `<presence/>` | subscriptions, directed presence, probes |
| `iq-forward` | `<iq/>` | vCard, PEP, disco, caps, version, ping, Jingle signalling |

Together these make a user on `alpha.example` able to talk to a user on `gamma.example` with no direct
S2S link between them, using an unmodified client.

## Shared envelope

```xml
<iq type='set' from='alpha.example' to='beta.example' id='f12'>
  <federation xmlns='urn:xmpp:federation:1'>
    <direct-forward destination='gamma.example' via=''>
      <message type='chat' from='amy@alpha.example/phone' to='zed@gamma.example' id='m17'>
        <body>hello from three hops away</body>
      </message>
    </direct-forward>
  </federation>
</iq>
```

| Attribute | Meaning |
|-----------|---------|
| `destination` | Final destination server. Routed action ([01](01-transport.md#the-routed-action-decision)). |
| `via` | Loop trail. Sent as `via=''` initially, appended by each relay. |

**The embedded stanza keeps its real `from`, `to` and `id` unchanged, at every hop.** This is the
whole design: the destination hands the payload to its own router, the recipient's client sees an
ordinary stanza from an ordinary JID, and its reply is a fresh outbound stanza that the *other*
server's federation module picks up and relays back symmetrically. Neither client knows the overlay
exists.

Preserving `id` is what makes IQ request/reply correlation work across the overlay.

### Relay behaviour

Identical for all three: check `via` for your own domain, drop on loop; validate the payload origin
([09](09-validation.md)); if you are not the destination, append yourself to `via` and re-emit toward
the next hop; if there is no route, drop and log.

### No untrusted-peer exposure gate

Unlike `muc-forward` and the mapping actions, these three are **not** gated on the untrusted-peer
exposure list. That is deliberate: 1:1 messaging crossing an untrusted edge is the entire point of
having one, and reachability is already bounded by the routing table, which an untrusted peer only
sees a filtered view of.

The `from`-spoofing checks still apply in full.

## Recipient binding

> **A receiver that is the final destination MUST verify that the embedded stanza's `to` resolves to
> something it actually serves**, before delivering it.

The envelope's `destination` says where the overlay hop ends. It says nothing about who the payload is
addressed to, and the two are independent claims. A peer can name you as the destination while
embedding a recipient somewhere else entirely — and since the delivery branch hands the payload to
your own router, your server will happily emit it over native S2S, under its own identity, to a party
it never authorised anything for. You become an open relay.

The `from`-validation described in [09-validation.md](09-validation.md) does **not** catch this: it
validates the sender, not the recipient.

Accept the payload only if its `to` domain is:

- this server's own XMPP domain, or
- one of this server's MUC service domains, or
- a component registered with this server.

Otherwise drop and log. In this implementation the log line reads
`SECURITY: dropping <action> from <peer> — final destination is us but the payload is addressed to
<domain>, which is not served here (refusing to relay it onward)`. A payload with a missing or blank
`to` is likewise dropped.

This check was added in **1.10.6**. Peers older than that do not perform it.

## `direct-forward`

At the destination, after the recipient-binding check:

```
if payload/@to's domain is one of OUR MUC services:
    route it normally, UNMARKED
    → a remote occupant addressing a local room directly; the room broadcasts it
      and the re-broadcast to other remote occupants relays back cleanly
else:
    rewrite any federated file share in place            (07-file-relay.md)
    mark as forwarded (fed-origin)
    deliver to the recipient
```

Delivering directly to the session — rather than routing — means the recipient's reply is a genuinely
fresh outbound stanza, which the sending server's own module then catches and relays back. That
symmetry is what makes conversation work without any per-conversation state.

The MUC branch is routed *unmarked* on purpose: the room must broadcast it normally, and the resulting
re-broadcast has to stay eligible for relay back out to other remote occupants. There is no re-relay
risk because a stanza addressed to a local conference domain is not a candidate for outbound
forwarding.

## `presence-forward`

Carries subscription management (`subscribe`, `subscribed`, `unsubscribe`, `unsubscribed`), directed
presence (available/unavailable), and probes.

```xml
<presence-forward destination='gamma.example' via='beta.example'>
  <presence type='subscribe' from='amy@alpha.example' to='zed@gamma.example'/>
</presence-forward>
```

At the destination:

```
recipient-binding check                                    → drop on failure

if payload type == 'probe':
    answer explicitly with the target user's current presence, relayed back
    over the overlay; do NOT hand the probe to the local presence engine
    → return

if payload/@to's domain is one of OUR MUC services:
    route normally, UNMARKED     (remote user joining/leaving a local room)
    → return

mark as forwarded (fed-origin)
route through the NORMAL packet router
    → the roster/subscription engine must process this as if it arrived over S2S
```

Three things to note:

**Presence is routed, not direct-delivered.** Unlike `direct-forward`, presence has to pass through
the server's own roster and subscription machinery so subscriptions are recorded and presence is
broadcast to the right resources. Bypassing that would deliver a `subscribed` that changes no roster
state.

**Probes are answered explicitly.** A server's native probe response is generated internally and
handed to native S2S — which for a multi-hop peer does not exist, so the answer is lost. Build the
response yourself and send it back as a `presence-forward`. The recipient-binding check runs
**before** this branch: answering a probe addressed to another server's user would disclose local
presence to a peer that had no standing to ask.

**Avatar hashes must survive.** `<x xmlns='vcard-temp:x:update'/>` is the trigger that makes a client
fetch a contact's photo. Rebuild presence lossily and avatars silently never appear.

### Subscription side effects

An implementation that intercepts outbound subscription presence to relay it must be careful not to
suppress its **own** server's roster processing for the local sender. If the local roster row is never
updated, everything downstream that keys off subscription state — presence broadcast, PEP
auto-subscription, avatar and OMEMO delivery — never fires, and the symptom looks like a remote
problem.

This is an implementation hazard rather than a wire requirement, but it is the single most common way
to get `presence-forward` "working" while nothing actually functions. If you suppress native delivery,
replicate the roster-state and event side effects locally.

## `iq-forward`

```xml
<iq-forward destination='gamma.example' via='beta.example'>
  <iq type='get' from='amy@alpha.example/phone' to='zed@gamma.example' id='vc1'>
    <vCard xmlns='vcard-temp'/>
  </iq>
</iq-forward>
```

At the destination, after the recipient-binding check:

```
if payload type is get or set:
    answer it, and RELAY THE ANSWER BACK over the overlay
else (result or error):
    deliver to the local client — it is the reply to something we relayed out
```

### The reply contract

**A request that arrived as `iq-forward` MUST have its reply sent back as `iq-forward`.**

The reply keeps the request's `id`, with `from`/`to` swapped, and is routed toward the requester's
domain as a fresh routed action. Nothing correlates at the overlay layer — the requesting client's own
IQ tracking does the correlation, which is exactly why `id` must be preserved end to end.

The trap on the Openfire side, worth understanding because it will bite any server with a similar
architecture: several built-in handlers answer a `get` by delivering the reply through an *internal*
path that bypasses the outbound interception point. For a multi-hop requester there is no native S2S
link, so the reply is handed to nothing and silently vanishes. The request looks answered locally and
times out remotely.

This implementation therefore answers two namespaces itself, rather than letting the native handler do
it, and relays the result explicitly:

- **`vcard-temp`** — XEP-0054 vCard `get` against a local user. Covers profile fields and the
  `PHOTO`/XEP-0153 avatar.
- **`http://jabber.org/protocol/pubsub`** — XEP-0060 `<items node='…'/>` `get` against a local user's
  bare JID, i.e. PEP. Deliberately generic across node names, not avatar-specific: the same reply-loss
  applies to every PEP node, so this also unblocks XEP-0084 avatars, XEP-0172 nicknames and XEP-0384
  OMEMO device lists and bundles, which all ride XEP-0163.

Everything else — disco, entity caps, version, ping, Jingle signalling — either targets a specific
local session (so the session's own reply is a fresh outbound stanza and relays back symmetrically) or
does not cross federation as a request/reply pair at all.

If your server's IQ handlers reply through a path your federation module can observe, you do not need
any of this — just route the payload and let the reply relay back like any other outbound stanza.

### PEP replies

Keep the three XEP-0060 outcomes distinct; clients read them differently.

```xml
<!-- node exists, has an item -->
<iq type='result' from='zed@gamma.example' to='amy@alpha.example/phone' id='p1'>
  <pubsub xmlns='http://jabber.org/protocol/pubsub'>
    <items node='urn:xmpp:avatar:metadata'>
      <item id='a1b2c3'>…</item>
    </items>
  </pubsub>
</iq>

<!-- node exists but is empty -->
<iq type='result' …>
  <pubsub xmlns='http://jabber.org/protocol/pubsub'>
    <items node='urn:xmpp:avatar:metadata'/>
  </pubsub>
</iq>

<!-- node does not exist -->
<iq type='error' …>
  <pubsub xmlns='http://jabber.org/protocol/pubsub'><items node='urn:xmpp:avatar:metadata'/></pubsub>
  <error type='cancel'><item-not-found xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error>
</iq>
```

An empty `<items/>` asserts *"this contact has an avatar node and it is empty"*, which tells a client
to render no avatar. `item-not-found` leaves it free to fall back to the `vcard-temp` photo. Collapsing
the two breaks avatars for clients that try PEP first and vCard second. Use `wait`-type
`internal-server-error` when you could not determine the node's state — "ask again later", not "it is
not there".

Per RFC 6120 §8.3.1, an error reply echoes the request's own child element, as shown.

### PEP publish push

A client publishing a PEP item (new avatar, changed nickname, rotated OMEMO device list) normally
notifies subscribers through the server's own pubsub machinery. Over the overlay that notification
does not reach federated contacts — the subscription state that would drive it lives on the wrong
server.

This implementation detects a local publish to a federated node and re-pushes the item to each remote
subscriber as a **headline** `<message/>` carrying a `pubsub#event`, relayed as `direct-forward`.

```xml
<direct-forward destination='alpha.example' via=''>
  <message type='headline' from='zed@gamma.example' to='amy@alpha.example'>
    <event xmlns='http://jabber.org/protocol/pubsub#event'>
      <items node='urn:xmpp:avatar:metadata'>
        <item id='a1b2c3'>…</item>
      </items>
    </event>
  </message>
</direct-forward>
```

`type='headline'` is load-bearing. A message with no type addressed to a bare JID is delivered to a
single resource — typically whichever client has the highest priority, which may well be one that
ignores PEP avatars. Headline fans out to **all** of a contact's connected resources, which is what
this needs.

---

Previous: [05-muc-traffic.md](05-muc-traffic.md) · Next: [07-file-relay.md](07-file-relay.md)
