# 08 — Contact-list sharing

One optional action pair that lets a server put its users into another server's rosters, modelled on
Openfire's Contact List (Roster) Sharing. Nothing else in the protocol depends on it: 1:1 messaging
and room federation work without it, because a user can always be addressed by typed JID.

Both actions are **routed** ([01](01-transport.md#the-two-addressing-layers)): each carries a
`destination`, and only that server consumes it. A contact list is never flooded. What one server
shares with another is invisible to every server in between.

## `contact-list`

The complete set of contacts `origin` shares with `destination`.

```xml
<iq type='set' from='beta.example' to='gamma.example' id='f30'>
  <federation xmlns='urn:xmpp:federation:1'>
    <contact-list destination='gamma.example' origin='alpha.example' via='alpha.example,beta.example'>
      <contact jid='ann@alpha.example' name='Ann Archer'/>
      <contact jid='bob@alpha.example'/>
    </contact-list>
  </federation>
</iq>
```

| On | Attribute | Meaning |
|----|-----------|---------|
| `contact-list` | `destination` | Required. The server the list is shared with. |
| | `origin` | Required. The sharing server. Every contact is one of its users. |
| | `via` | Loop trail. |
| `contact` | `jid` | Required. A bare JID on `origin`. |
| | `name` | The contact's display name on its own server. Omitted when empty. |

Each send carries **complete state**: it replaces whatever the destination held for that origin.
**An empty `<contact-list/>` withdraws** the list, and the destination removes those contacts from
every roster it put them in.

The sender re-sends when the list changes (a group shared or unshared, a shared group's membership
changes, a display name changes), when the destination becomes reachable again, and on a periodic
refresh as a backstop for a lost IQ.

## `contact-list-request`

Asks `destination` to re-send the list it shares with `origin`. The answer is a `contact-list`, which
is empty if nothing is shared.

```xml
<iq type='set' from='gamma.example' to='beta.example' id='f31'>
  <federation xmlns='urn:xmpp:federation:1'>
    <contact-list-request destination='alpha.example' origin='gamma.example' via='gamma.example'/>
  </federation>
</iq>
```

A receiver sends this when an origin it holds a list for, or has a mapping for, becomes reachable
again. That is what clears a list the origin withdrew while the receiver could not hear it. For
example, the origin restarts after its admin stopped sharing, and so no longer knows the receiver
still holds the old list.

## Receiver algorithm

```
if destination or origin is missing, or origin == our own domain → drop
if the sending link is UNTRUSTED:
    if origin is not the sender or a server routed through it → drop, log SECURITY
    if (destination, or our own domain when we are it) is not exposed to the sender → drop, log SECURITY
if via contains our own domain                  → drop (loop)
if destination != our own domain:
    next := next hop toward destination; none   → drop
    if next is an UNTRUSTED peer:
        if origin is not exposed to next, or (contact-list and sending contact lists is not
           allowed on that link here)           → drop, log SECURITY
    relay to next with via + our own domain
    stop
contact-list-request → send our list for `origin` (empty if nothing is shared with it)
contact-list:
    keep only bare JIDs whose domain is `origin`; cap the count (5000 here)
    replace the stored list for `origin` (empty = withdrawn)
```

**By default a contact list never crosses an untrusted link**, in either direction and at any hop.
Sending, relaying and accepting all refuse one, and a sender only targets servers whose route is
trusted end to end (no *edge* flag, [03](03-routing.md#untrusted-peers)).

A server MAY let contact lists cross its untrusted links. This implementation decides per link: an
untrusted peer's settings carry a *send shared contact lists* flag, off by default. The flag gates
only a `contact-list` **leaving** over that link. Receiving needs no flag: sharing is one-way, and the
receiving admin still decides what a list does by mapping it. A `contact-list-request` carries no
contacts, so it needs no flag either. The link's exposure model applies in both directions, the same
rule as the 1:1 forwards ([09](09-validation.md)):

- **Leaving over an untrusted link:** the `origin` must be a server exposed to that peer, and for a
  `contact-list` the link's flag must be set. A sender whose own next hop is untrusted checks this
  before sending. A relay checks it before re-emitting.
- **Arriving over an untrusted link:** the `origin` must be the peer, or a server routed through it.
  The destination must be one exposed to that peer: this server when it is the destination, or the
  server being relayed to.

So a list crosses an untrusted link when the admin on the sending side of that link allows it. A
sender whose route crosses an untrusted link further away cannot see those checks. Its list is
dropped (and logged) there if they fail.

**A server may only share its own users.** A `contact` whose domain is not `origin` is dropped, so a
server cannot insert another server's users into your rosters.

## What sharing authorizes

Sharing a user with a server authorizes **that server's users** to see the user's presence and
personal-eventing data (avatar, nickname, OMEMO device list). The sharing server does not know which
of the destination's users were given the list. That is the destination admin's choice, and the
authorization is per server.

On the sharing server, for a local user shared with server S:

- A `presence` of type `probe` or `subscribe` from a user on S is **answered by the server**: it
  sends the user's current presence, and pushes the user's PEP items. The user is not asked to
  approve. The admin approved the contact by sharing it. The user's roster is not changed.
- An `unsubscribe` from a user on S is absorbed silently.
- Later presence changes and PEP publishes are pushed to every contact on S that probed or subscribed.
- A PEP items fetch from a user on S is answered under the node's access model, with S's users
  counted as presence subscribers.

These stanzas reach the sharing server either over native S2S (a direct peer) or inside a
`presence-forward`/`iq-forward` ([06](06-direct-traffic.md)). Both paths must apply the rule.
Answers always travel inside `presence-forward` and `direct-forward`, even to a direct peer: the
user's roster has no entry for the contact, so a native presence broadcast would not include them.

Presence is **one-way**. A user on S does not appear in the shared user's roster, and the shared user
does not see their presence, unless S shares that user back.

## What the receiver does with a list

Entirely local; nothing about it crosses the wire. This implementation does nothing until the admin
**maps** a received list to local groups. It then keeps an Openfire shared group, named
`federation-contacts-<origin>`, whose members are the remote contacts, shown in the rosters of the
mapped groups (or of every user). Openfire's roster engine gives each recipient a roster item with
subscription `to`. Unmapping deletes the group, which removes the contacts from every roster.

A receiver MAY keep a list after the origin becomes unreachable (this one does, so a link flap does
not empty everyone's contact list) and MUST apply a withdrawal when it arrives.

## Implementing it

- **To skip receiving:** ignore both action names. Unknown actions are acknowledged normally
  ([01](01-transport.md#acknowledgement)). The sender sees nothing wrong.
- **To skip sending:** send nothing.
- **To receive without the roster integration:** storing the list and showing it to users some other
  way is a conforming use. The protocol only defines what is shared, not how it is presented.

Retired in 1.10.13: `user-directory` and `bookmark-push`, the earlier online-user gossip that this
replaces. Ignore them if a peer on an older build still sends them.

---

Previous: [07-file-relay.md](07-file-relay.md) · Next: [09-validation.md](09-validation.md)
