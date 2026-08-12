# 10 — Conformance and interop

What you actually have to build, in what order, and what to expect when your implementation meets a
peer running a different version of this one.

## Conformance profiles

Each level is useful on its own and each is a superset of the one above it.

### Level 0 — Transport

`<iq type='set'/>` with `<federation xmlns='urn:xmpp:federation:1'/>`, dispatch on the first child,
always answer `<iq type='result'/>`, `bad-request` on a missing or empty federation element. Apply the
peer allowlist before dispatch. Ignore unknown actions.

A Level 0 node is silent but well-behaved. Nothing breaks because of it.

### Level 1 — Reachable node

Add `peer-announce` (send, receive, and the reply rules), plus `routing-update` and `routing-solicit`.
Maintain a distance-vector table.

You are now a named, reachable destination. Peers can route toward you. **This is the minimum for
anything else to work** — a peer that never receives your `peer-announce` stays PENDING and sends you
nothing at all.

Also implement `peer-withdraw` receipt, so a peer leaving cleanly does not leave you with dead routes.

### Level 2 — Relay

Add the routed-action relay for every routed action — including ones you do not otherwise support.
Relaying is mechanical: check the loop trail, look up the next hop for `destination`, re-emit with
your domain appended to `via`, preserving every other attribute and the payload verbatim.

Add flooding for `room-advertisement`: cache by `origin`, re-emit to your other peers with `via`
extended, honour the `visibleto` filter.

A Level 2 node is a **useful transit organisation** even with no local users participating in
anything. Most of the value of a hub is here.

### Level 3 — 1:1 endpoint

Add `direct-forward`, `presence-forward`, `iq-forward` delivery at the final hop, plus the outbound
side: catch a local user's stanza addressed to an overlay-reachable domain with no direct S2S link and
relay it instead.

Your users can now chat, subscribe, and see presence and vCards across the mesh. Implement the
recipient-binding rule ([09](09-validation.md#5-recipient-binding)) before you ship this.

### Level 4 — Room federation

Add `room-advertisement` origination, the full mapping lifecycle, and `muc-forward` with the virtual
occupant model.

This is the largest single step, and the one with the most state. Budget for occupant tracking,
eviction on every teardown path, and the `local`/`remote` inversion.

### Level 5 — File relay

Add the `fed-file` annotation and `file-request`/`-offer`/`-chunk`/`-error`.

### Optional at any level

- `mapping-ping` / `mapping-pong` — never answering simply means peers never flag your mappings as
  broken. Costs you diagnostics, breaks nothing.
- `user-directory` / `bookmark-push` — ignore the actions and no peer notices.
- `peer-disable` — treat an inbound one as a withdrawal you refuse to re-establish automatically.
- Untrusted-peer mode — if you have no exposure model, omit the `untrusted` attribute and treat all
  peers as trusted. You will be blocked by a peer that considers you untrusted, which is the correct
  outcome, since you could not honour the restrictions that stance implies.

## Implementation checklist

Ordered by how expensive the mistake is to find later.

**Transport**
- [ ] Answer every well-formed federation IQ with `<iq type='result'/>`, including dropped ones
- [ ] Drop inbound `type='result'` and `type='error'` in this namespace without replying
- [ ] Process the first child of `<federation/>` only
- [ ] Never infer identity from anything but the stanza's `from` domain

**Routing**
- [ ] Send the **complete** split-horizon table every time — a partial update is a mass withdrawal
- [ ] Withdraw by omission when merging
- [ ] Accept a **worse** metric from the current next hop
- [ ] Reject an entry whose destination is the sending peer
- [ ] Treat metric ≥ 16 as unreachable

**Loops**
- [ ] Split `via` on `,` and compare elements — never `contains()`
- [ ] Reset the trail on hub fan-out, and skip the payload's home domain explicitly
- [ ] Never inject a user whose home domain is your own

**Mapping**
- [ ] Invert `local`/`remote` on receipt
- [ ] Mint a token on accept; carry it through relays unchanged
- [ ] Only an ACTIVE mapping forwards traffic

**MUC**
- [ ] Derive the virtual nick as `user@homedomain` from the payload's `from`
- [ ] Preserve `vcard-temp:x:update` when rebuilding presence
- [ ] Set `muc#user` `<item/>`'s `jid` to the payload's original full `from`
- [ ] Mark roster-sync presences with `<fed-origin/>` and never reciprocate one
- [ ] Preserve `src` across relay hops; overwrite it only when fanning out

**Security** — every rule in [09-validation.md](09-validation.md), especially:
- [ ] Payload `from` validation at **every** hop, relay included
- [ ] Payload `to` binding at the final hop
- [ ] Federation-enabled **and** active-mapping checks before injecting
- [ ] Constant-time comparison for the file share capability

**Files**
- [ ] `id` = lowercase hex SHA-256 of the URL's UTF-8 bytes
- [ ] Carry `token` through your annotation handling even if you never validate it
- [ ] Validate offer geometry exactly before allocating
- [ ] Abort a transfer on `file-error` in **both** the requested and receiving states

## End-to-end trace

Three servers, chained: `alpha — beta — gamma`. alpha and beta peer directly; beta and gamma peer
directly; alpha and gamma have **no** direct link. Goal: map a room on alpha to a room on gamma and
send one message.

### 1. Links come up

```xml
<iq type='set' from='alpha.example' to='beta.example' id='a1'>
  <federation xmlns='urn:xmpp:federation:1'>
    <peer-announce><server>alpha.example</server><version>1</version></peer-announce>
  </federation>
</iq>
<iq type='result' from='beta.example' to='alpha.example' id='a1'/>
```

beta had not seen alpha as reachable, so it sends full gossip back: `peer-announce`,
`routing-update`, its own `room-advertisement`, then one per cached remote origin. Same exchange
happens on the beta–gamma link.

### 2. Routing converges

beta now has direct routes to alpha and gamma (hop 1 each). It tells each about the other — split
horizon means alpha's update mentions gamma but not alpha:

```xml
<iq type='set' from='beta.example' to='alpha.example' id='b2'>
  <federation xmlns='urn:xmpp:federation:1'>
    <routing-update>
      <entry destination='gamma.example' hops='1' via='gamma.example'/>
    </routing-update>
  </federation>
</iq>
```

alpha installs `gamma.example → via beta.example, 2 hops`. It can now address gamma.

### 3. gamma advertises a room

```xml
<iq type='set' from='gamma.example' to='beta.example' id='g3'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-advertisement>
      <room jid='ops@conference.gamma.example' name='Operations' description='' visibleto='*'/>
    </room-advertisement>
  </federation>
</iq>
```

No `origin` — these are gamma's own rooms. beta caches under `gamma.example` and floods onward,
stamping the origin explicitly and starting the trail:

```xml
<iq type='set' from='beta.example' to='alpha.example' id='b4'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-advertisement origin='gamma.example' via='beta.example'>
      <room jid='ops@conference.gamma.example' name='Operations' description='' visibleto='*'/>
    </room-advertisement>
  </federation>
</iq>
```

alpha's admin now sees `ops@conference.gamma.example` as a mappable remote room.

### 4. alpha requests a mapping

alpha's admin pairs local `ops@conference.alpha.example` with gamma's room. alpha routes toward gamma
— next hop beta:

```xml
<iq type='set' from='alpha.example' to='beta.example' id='a5'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-mapping destination='gamma.example' origin='alpha.example'>
      <map local='ops@conference.alpha.example' remote='ops@conference.gamma.example'/>
    </room-mapping>
  </federation>
</iq>
```

beta is not the destination, so it relays — same action, new IQ addressing, `local`/`remote`
untouched:

```xml
<iq type='set' from='beta.example' to='gamma.example' id='b5'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-mapping destination='gamma.example' origin='alpha.example'>
      <map local='ops@conference.alpha.example' remote='ops@conference.gamma.example'/>
    </room-mapping>
  </federation>
</iq>
```

gamma inverts: its local room is `map/@remote`, the peer's room is `map/@local`, the peer is `@origin`.
It checks that `ops@conference.gamma.example` is federation-enabled and that its visibility ACL
includes `alpha.example`, then stores the request as **PENDING_IN**. Nothing flows yet.

### 5. gamma accepts

```xml
<iq type='set' from='gamma.example' to='beta.example' id='g6'>
  <federation xmlns='urn:xmpp:federation:1'>
    <room-mapping-accept destination='alpha.example' origin='gamma.example'
                         token='c41f8b7e2a9d05e6...'>
      <map local='ops@conference.gamma.example' remote='ops@conference.alpha.example'/>
    </room-mapping-accept>
  </federation>
</iq>
```

Note the inversion has flipped with the direction of travel: gamma is the sender now, so `local` is
gamma's room. beta relays it to alpha. Both ends mark the mapping **ACTIVE** and store the token.

gamma also pushes its current occupant list to alpha as `muc-forward` presences, each marked
`<fed-origin/>` so alpha does not sync back.

### 6. A user joins on alpha

`amy@alpha.example` joins `ops@conference.alpha.example`. alpha forwards her presence toward gamma:

```xml
<iq type='set' from='alpha.example' to='beta.example' id='a7'>
  <federation xmlns='urn:xmpp:federation:1'>
    <muc-forward destination='gamma.example'
                 targetRoom='ops@conference.gamma.example'
                 via=''
                 src='alpha.example'>
      <presence from='amy@alpha.example/phone' to='ops@conference.alpha.example'>
        <show>chat</show>
        <x xmlns='vcard-temp:x:update'><photo>5f2c9a1b…</photo></x>
      </presence>
    </muc-forward>
  </federation>
</iq>
```

beta relays with `via='beta.example'` and `src` **unchanged** — beta is a transit hop, not a mapped
entry server.

gamma is the destination. It checks: not a loop; payload origin OK; exposure OK;
`ops@conference.gamma.example` is federation-enabled; it has an ACTIVE mapping. Then it injects — each
real occupant of gamma's room receives:

```xml
<presence from='ops@conference.gamma.example/amy@alpha.example'
          to='zed@gamma.example/desktop'>
  <show>chat</show>
  <x xmlns='vcard-temp:x:update'><photo>5f2c9a1b…</photo></x>
  <x xmlns='http://jabber.org/protocol/muc#user'>
    <item affiliation='none' role='participant' jid='amy@alpha.example/phone'/>
  </x>
  <fed-origin xmlns='urn:xmpp:federation:1'/>
</presence>
```

Since this was a real join (no `<fed-origin/>` in the *incoming* payload), gamma syncs its own
occupants back toward amy's home — targeting the mapping for `alpha.example`, which routes through
beta.

### 7. A message

zed types in gamma's room. gamma forwards toward alpha:

```xml
<iq type='set' from='gamma.example' to='beta.example' id='g8'>
  <federation xmlns='urn:xmpp:federation:1'>
    <muc-forward destination='alpha.example'
                 targetRoom='ops@conference.alpha.example'
                 via=''
                 src='gamma.example'>
      <message type='groupchat' from='zed@gamma.example/desktop'
               to='ops@conference.gamma.example'>
        <body>deploy is green</body>
      </message>
    </muc-forward>
  </federation>
</iq>
```

alpha injects it to amy as:

```xml
<message type='groupchat'
         from='ops@conference.alpha.example/zed@gamma.example'
         to='amy@alpha.example/phone'>
  <body>deploy is green</body>
  <fed-origin xmlns='urn:xmpp:federation:1'/>
</message>
```

amy sees an ordinary groupchat message from a participant nicknamed `zed@gamma.example`. Her client
knows nothing about the overlay, and neither does zed's.

## Version compatibility

The protocol has no negotiation. Compatibility is by attribute: unknown attributes are ignored, absent
ones fall back to a documented default. The consequences that actually bite:

| Feature | Absent-attribute behaviour | Interop consequence |
|---------|---------------------------|---------------------|
| `untrusted` on `peer-announce` | treated as trusted | Safe. A pre-trust peer federates as trusted with anyone who also considers it trusted. |
| `visibleto` on `<room/>` | parsed as **empty set** = visible to nobody | **Your rooms reach direct peers and stop.** Always emit it; use `*` if you have no ACL model. |
| `src` on `muc-forward` | falls back to the sending domain | Degrades safely. Mapping-scoped eviction becomes less precise on multi-hop paths. |
| `token` on mapping lifecycle | empty stored token accepts anything | A peer that *has* a stored token rejects your untokened disable/enable/unmap. Mint tokens. |
| `mapping-ping` support | never answers | Never flagged as broken; a domain must have provably answered once to be eligible for the verdict. |
| `token` on `fed-file` | holder with no stored token serves anyway | **Fail-closed the other way**: a 1.10.6 origin's shares cannot be fetched by a peer that drops the attribute. |
| Recipient binding (rule 5) | older peers do not check | They will relay a mis-addressed payload onward. Implement it regardless of what your peers do. |

Two upgrades in this implementation's history required coordinated action, worth knowing if you meet a
mixed mesh:

- **Mapping consent tokens.** Existing mappings dropped to pending and had to be re-accepted. The
  lower-domain side auto-re-requests on reconnect; the other side accepts.
- **The file share capability (1.10.6).** Fail-closed in one direction, so a mesh must be upgraded
  together rather than one server at a time.

## Testing against this implementation

- **Turn on debug logging for the federation package.** Every drop is logged with its reason, and the
  `SECURITY:`-tagged lines tell you exactly which validation rule refused your stanza. This is by far
  the fastest way to find a mistake in a new implementation.
- **Start at Level 1.** Get `peer-announce` exchanged and confirm the peer moves from *Pending* to
  *Reachable* in its admin console. If it stays Pending, you are not being seen as announcing.
- **Then confirm routing** before anything else: your domain should appear in the peer's routing table
  at the right hop count. Every routed action depends on it, and a missing route produces a silent
  drop, not an error.
- **Watch for the empty-update trap.** If routes appear and then vanish a few seconds later, you are
  almost certainly sending partial `routing-update`s and triggering withdrawal-by-omission.
- **Room advertisements that do not propagate past one hop** are almost always a missing `visibleto`.

---

Previous: [09-validation.md](09-validation.md) · Back to [README.md](README.md)
