# Security — full trust model

This is the long-form companion to the [Security table in the README](../README.md#security). Every control
below maps to a row there; here each is spelled out with its enforcement point, failure mode, and upgrade
behavior.

## Threat model in one line

A federation peer is a *separate administrative domain you chose to link with*. The plugin assumes a peer
represents **its own** users honestly, and defends against everything else: a peer reaching rooms it was
never granted, speaking for users that aren't its own, advertising topology you didn't ask for, being
silently swapped for a different server, or driving admin actions through a logged-in browser. Traffic that
violates any of these is **dropped and logged with a `SECURITY:` tag** (grep your Openfire log for
`SECURITY:` to audit).

---

## Rooms are opt-in

A remote peer can only map — or inject presence/messages into — a local room an admin has explicitly toggled
**Federated**. Forwarded traffic aimed at any non-federated room is dropped and logged with a `SECURITY:`
tag. This stops a peer from siphoning the roster of, or injecting into, a room it was never granted
(injection otherwise bypasses MUC's own non-occupant check).

## Peer allowlist (default on)

`plugin.federation.peerAllowlist` defaults to `true`: only configured peers (those you **Add** in the admin
console) may drive federation; every action from any other server is rejected. A peer is "configured" if you
added it, so **both ends must add each other** — with the allowlist on, auto-registration of unknown peers is
suppressed. Set it to `false` (or use the **Security** toggle on the Peer Servers tab) for open federation,
where any server that can connect is accepted and auto-registered.

## Untrusted peers (filtered exposure)

Mark a peer **Untrusted** (the checkbox next to *Add peer*, or the *Make untrusted* button in its settings
panel) and it receives **no** routing updates and **no** room advertisements at all. You then pick — per
peer, via its settings panel (the expand arrow on its row) — exactly which **servers** it may see, chosen
from this server itself (its federated local rooms) *and* any server reachable through it. The untrusted peer
is sent only the federated rooms homed on those servers, plus a route to each, so it learns nothing about the
rest of your topology. Enforcement is two-way: inbound `room-mapping`/`muc-forward` from an untrusted peer
aimed at a room homed on a server it was **not** exposed to is dropped and logged with a `SECURITY:` tag.
This is the **edge-server** pattern: federate with a partner organisation through one gateway that exposes
only a curated set of servers.

- **Trust is a property of the link.** Each end announces its stance (trusted/untrusted) in `peer-announce`;
  if the two disagree, the link is **blocked** (status *Trust mismatch*) and no federation flows until
  **both** admins set the same trust level. It then comes up automatically — no reconnect needed. The peer's
  settings panel shows both directions: the servers you expose to that peer (editable), and the servers that
  peer is **advertising through** to you (each deniable per-link).
- **Untrusted by default for foreign peers.** When you add a peer whose **parent domain** differs from this
  server's (the last two DNS labels, e.g. `example.net`; adjustable via `plugin.federation.trustDomainLabels`),
  the *Untrusted* box is ticked automatically — a stranger shares nothing until you choose what it may see.
  Same-parent peers default trusted.

## Deniable route advertisements (per-link inbound filter)

The exposure controls above govern what you *send*; each side can also refuse what it *receives*. If a peer
advertises a route (and rooms) for a destination you don't want, click **Deny** — on the Routing Table row,
or next to that server in the peer's settings panel (right column). The destination is refused whenever
**that** peer advertises it: any installed route via that peer is torn down immediately (with room/ghost
clean-up) and future advertisements are dropped on receive. A route to the same destination via a *different*
peer is unaffected, and the deny is one-sided — nothing is negotiated with the peer. The denied entry stays
listed in the Routing Table as a disabled (struck-through) row, and the deny is remembered even if the peer
withdraws the route and advertises it again later. **Allow** (on the disabled row, or in the peer's settings
panel) lifts it and re-solicits the peer so the route re-appears. Denies are persisted per peer
(`federation.peer.deniedroutes.<domain>`).

- **End-to-end mapping probe (mapping-ping).** A deny mid-path is invisible to servers on the far side —
  their routing tables still show a route while replies silently die at the denying hop. So every active room
  mapping is probed end-to-end (default every 30 s, `plugin.federation.mappingPingSeconds`, 0 = off): the
  mapped domain answers with a pong routed back across the overlay. Three unanswered probes in a row (≈2
  minutes at the default cadence) flip the mapping to **⚠ not responding**, its remote occupants are dropped
  (no more stale ghosts), and when pongs resume the flag clears and rosters re-sync automatically. Peers
  running an older plugin never answer probes and are simply never flagged: a domain is only eligible for the
  *not responding* verdict once it has provably answered (or sent) a probe, and that proof is **persisted**
  (`plugin.federation.probeCapableDomains`) so it survives restarts — a path already broken when the plugin
  starts is still detected. Each mapping row in the room's settings panel shows the probe's round-trip time
  and the age of the last answer (`ping 12 ms · 3s ago`; `ping —` = no answer yet).

## Mutual-add handshake (Pending status)

A configured peer whose S2S link is up shows **Pending** — not *Reachable* — until its federation plugin
sends us a `peer-announce`, i.e. until the remote has added us back (instantly, in open-federation mode, via
auto-registration). No routes or gossip flow toward a pending peer; we keep announcing ourselves on the
reconnect back-off so the link confirms promptly once the remote adds us.

## S2S key pinning (trust-on-first-use)

The first time a peer's S2S link comes up, the plugin pins the SHA-256 of the **public key** (SPKI) of the
leaf certificate it presents. If that key later **changes** — e.g. a server is re-created under the same
domain name with a new key, even one signed by the same CA — the peer is **auto-marked untrusted** and
flagged in the Peers list (*⚠ cert changed*); federation toward it is blocked until you review and click
*Trust new cert* to pin the new one. Pinning the key (rather than the CA chain) means an impersonator with a
certificate from the same public CA is still caught; renewals that reuse the key pass silently, while a key
rotation raises the flag for review. Requires a TLS-secured S2S link (a plain server-dialback link presents
no certificate, so nothing is pinned). Pins made by versions before 1.7.13 (top-of-chain cert hashes) are
upgraded in place on the next sighting.

## Sender-identity (anti-spoofing) checks

Every stanza forwarded over the overlay has its claimed `from` validated at each hop: no peer may deliver a
stanza pretending to come from **this server's own users**, and an **untrusted** peer may only speak for
itself or for servers reached *through* it — a forged identity from the wrong direction is dropped and logged
with a `SECURITY:` tag. Remote users are injected under their home-qualified nick. As with any federation,
peers are trusted to represent **their own** users honestly — a compromised peer can still misrepresent users
of domains it relays.

## Per-room visibility

Each federated room has a **Visible** control (next to its toggle) listing the servers allowed to see it —
chosen from the routable peers, plus servers you can **add manually before they're reachable** (the room
advertises to them automatically once a route appears). A newly-federated room defaults to **visible to no
one** — pick specific servers, or tick **Visible to all peers** to share with everyone. The visibility set
travels with the advertisement, so every relay confines the room to the path toward its allowed destinations
— off-path servers never learn it exists. The ACL is persisted and **survives a listed server being
offline**. (Enforcement assumes on-path servers run this plugin version or newer. Rooms that were already
federated when you upgraded are migrated to *visible to all* so existing federation keeps working.)

## Mapping consent

Mapping onto a remote room sends a **request** the other admin must **Accept** (shown in a *Pending mapping
requests* panel and inline on the room) before any traffic flows; **Reject** declines it. On accept, a
per-mapping **token** is shared and re-checked on every later lifecycle message, so a third party can't forge
one and a remove+re-add forces a fresh acceptance. A mapping can be **Disabled** instead of removed — the
peer shows *"disabled by peer"* and it can be re-enabled later. A per-room **Auto-accept** toggle makes a
room free to join — incoming requests are accepted automatically (still subject to the
federation/untrusted/visibility gates). **On upgrade, existing mappings drop to pending and must be
re-accepted** (the lower-domain side auto-re-requests on reconnect; the other side just accepts).

## Admin API CSRF

The Federation tab's API uses a double-submit token (a `fed-csrf` cookie echoed back as a request
parameter), so a forged request from another site cannot trigger peer/room changes in a logged-in admin's
browser. After upgrading, reload an already-open Federation tab once so its scripts pick up the token.

## File type filtering & scanning

`plugin.federation.files.allowedExtensions` (the *Files* tab) gates what a server will stage for outbound
relay (egress, at the origin) and accept from a peer (ingress, at the destination — defense in depth against
a peer whose own filter is absent, bypassed, or an older plugin version). At the destination, received
content is additionally sniffed by magic number (no filename hint) and rejected on a confident mismatch
against the claimed extension — e.g. an executable renamed to `.jpg` is caught even though `.exe` was never
on the allowlist to begin with; a generic sniff result (plain text, unrecognized binary) is inconclusive and
not treated as a mismatch. Optional ClamAV scanning (`avEnabled`, off by default) adds a real
signature-based scan of received content before it becomes servable; a scan that can't complete (clamd
unreachable) fails closed rather than serving unscanned content. None of this touches a transit hop —
`relayToward` forwards `file-*` elements without ever decoding their content, so a purely-relaying server is
unaffected regardless of configuration.

Every scan and every rejection is appended to a tab-separated log in Openfire's log directory
(`logs/federation-file-scans.log`, `logs/federation-file-rejections.log`), so the audit trail outlives a
plugin reload or a server restart and can be collected by whatever ships the rest of the server's logs.
Entries are pruned after `plugin.federation.files.logRetentionDays` (default 180). Note the rejection log
records the file name, size, peer, stage and reason — not the content, which is deleted on rejection.
