# 08 — Directory and bookmark gossip

Two optional, structurally identical actions that advertise *which users are online here*. Both are
**off by default** in this implementation, for privacy reasons, and neither is required for anything
else in the protocol to work — 1:1 messaging and room federation function perfectly with both
disabled, because a user can always be addressed by typed JID.

They are flooded like `room-advertisement` ([04](04-rooms.md#room-advertisement)): `origin` plus a
`via` trail, no `destination`, re-emitted by each receiver.

## `user-directory`

Populates a peer's "who is reachable over there" view.

```xml
<iq type='set' from='beta.example' to='alpha.example' id='f30'>
  <federation xmlns='urn:xmpp:federation:1'>
    <user-directory origin='gamma.example' via='gamma.example,beta.example'>
      <user jid='zed@gamma.example' show='away' status='in a meeting'/>
      <user jid='kim@gamma.example'/>
    </user-directory>
  </federation>
</iq>
```

| On | Attribute | Meaning |
|----|-----------|---------|
| `user-directory` | `origin` | Server these users are on. Absent → the sender. |
| | `via` | Loop trail. Omitted when empty. |
| `user` | `jid` | Required. A blank or missing `jid` skips that entry. |
| | `show` | RFC 6121 presence show value. Omitted when empty. |
| | `status` | Free-text status. Omitted when empty. |

**An empty `<user-directory/>` is a withdrawal** — it clears the cached users for that origin.

### Receiver algorithm

```
if via contains our own domain                  → drop (loop)
source := origin, or sender if absent
if source == our own domain                     → drop
if the sending link is UNTRUSTED                → drop, log SECURITY
cache the user list under `source` (empty list = clear)
relay to every reachable, TRUSTED peer that is not the sender
    and is not already in the via trail, with via + our own domain
```

Two trust rules, in opposite directions, and both are required:

- **Never send** a directory across an untrusted link. Your user list is exactly the kind of thing an
  untrusted edge exists to keep from a partner organisation.
- **Never accept** one from an untrusted link. Legitimate directory data never crosses an untrusted
  edge, so anything that arrives over one is a peer trying to poison your view. Drop it and log.

## `bookmark-push`

Identical wire shape, different consumption: the receiver injects the advertised users into its own
local users' bookmark storage as [XEP-0048](https://xmpp.org/extensions/xep-0048.html) `<url/>`
bookmarks, so federated contacts appear in an ordinary client without anyone typing a JID.

```xml
<iq type='set' from='beta.example' to='alpha.example' id='f31'>
  <federation xmlns='urn:xmpp:federation:1'>
    <bookmark-push origin='gamma.example' via='gamma.example,beta.example'>
      <user jid='zed@gamma.example' show='chat' status=''/>
    </bookmark-push>
  </federation>
</iq>
```

Same attributes, same withdrawal-by-empty-list semantics, same trust rules, same relay algorithm.
It is independent of `user-directory`: a deployment may run either, both, or neither.

The bookmark injection itself is entirely local — nothing about how a receiver stores or presents
these users crosses the wire.

## Implementing them

Cheap to support and cheap to skip:

- **To skip receiving:** ignore both action names. Unknown and ignored actions are acknowledged
  normally ([01](01-transport.md#acknowledgement)) and no peer will notice or care.
- **To skip sending:** send nothing. Peers see an empty view of your users, which is exactly what this
  implementation does by default.
- **To support them:** implement one and you have implemented both — they differ only in what you do
  with the parsed list.

Re-send the full list on any change, and on peer-up as part of the gossip sequence. Like
`routing-update`, these carry complete state rather than deltas: what you send replaces what the
receiver had cached for that origin.

---

Previous: [07-file-relay.md](07-file-relay.md) · Next: [09-validation.md](09-validation.md)
