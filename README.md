# Openfire Federation Plugin

Better MUC (group chat) federation for [Openfire](https://www.igniterealtime.org/projects/openfire/) — a
dynamic routing overlay that lets multi‑user chat rooms span many Openfire servers, including servers that
are **not directly connected** to each other.

Standard XMPP server‑to‑server (S2S) already lets a user on one server join a room on another. This plugin
goes further: it builds a **federation overlay** on top of S2S so that a single logical room can be
**mapped across several servers at once**, with messages and presence relayed **multi‑hop** between servers
that have no direct link. End users do nothing special — they just join their local room.

---

## Features

- **Per‑room federation** — flip a toggle to federate any local MUC room; no client changes.
- **Room defaults by name** — define glob rules (e.g. `*_ext`) that auto-apply federation, visibility,
  auto-accept and same-name auto-mapping to a room the moment it's created; most-specific pattern wins,
  `*` is the catch-all. Two servers sharing a naming rule auto-link with zero clicks.
- **Room mapping** — connect a local room to a room advertised by a peer; occupants and messages merge.
- **Multi‑hop forwarding** — rooms federate across servers that aren't directly connected, relayed hop‑by‑hop.
- **Dynamic routing** — a distance‑vector (Bellman‑Ford) routing table is learned automatically via gossip
  when peers connect, and reconverges when the topology changes.
- **Live activation** — peers, mappings and settings take effect immediately; no Openfire restart.
- **Self‑healing S2S** — automatic reconnect with exponential back‑off, keepalives, and idle‑reaper handling
  so links stay up and rosters re‑sync without users rejoining.
- **Ghost‑occupant cleanup** — when a peer or route drops, *or a remote user disconnects (including several
  hops away)*, that user is cleanly removed from every local room across the federation.
- **Access control** — federation traffic only ever touches rooms you explicitly enable; an optional peer
  allowlist restricts who may federate at all; **untrusted peers** see only the servers you expose to them
  (ideal for an edge server fronting a partner network); the admin API is CSRF‑protected. See [Security](#security).
- **Admin console UI** — manage peers, watch the routing table and S2S sessions, and federate rooms from a
  dedicated **Federation** tab.

---

## Requirements

- Openfire **5.0.5+**
- Java **17**
- Working **S2S** between the servers you want to federate (DNS/SRV resolvable domains, ports open,
  TLS as configured). The plugin establishes the S2S connection for you once a peer is added.

---

## Build

```bash
mvn -q package -DskipTests
```

The Openfire plugin archive is produced at **`target/federation.jar`**.

## Install

Copy `federation.jar` into each server's Openfire plugins directory (default
`/var/lib/openfire/plugins`). Openfire hot‑loads it — no restart needed. Then open the admin console and look
for the new **Federation** tab.

Install the plugin on **every** server you want to participate in the federation.

> A `deploy.sh` is included for pushing the jar to a multi‑server lab over SSH. It is environment‑specific
> (host list, container name, credentials) — adapt it before use.

---

## Quick start

1. **Install** the plugin on each server and open **Admin Console → Federation**.
2. **Add a peer** (Peer Servers tab → *Add peer server*): enter the other server's XMPP domain. The peer
   shows **Pending** while it waits for the other side to add you back; the dot turns green (*Reachable*)
   only once the remote's federation plugin confirms the mutual add. Repeat so every server knows its
   neighbours — they don't all need to be directly connected; routes propagate. A non‑federated server
   appearing under *Active S2S sessions* can be added as a peer directly from that list (**Add peer**
   button on its row).
3. **Federate a room** (Rooms tab → *Local rooms*): toggle **Federated** on for a room. It is now advertised
   to your peers and appears in their *Remote rooms* list.
4. **Map a room**: on another server, find that room under *Remote rooms* (or map your local room to it) to
   link the two. Occupants and messages now flow between them.
5. Users join their **local** room as usual and see everyone across the federation.

The in‑console **documentation/readme** link has a fuller, screenshot‑level walkthrough — see
[`src/main/resources/readme.html`](src/main/resources/readme.html).

---

## Admin console reference

The **Federation** tab has three sub‑views:

| Tab | What it shows |
|-----|----------------|
| **Peer Servers** | Add/remove/disable peers, configured peer status & last‑seen (*Pending* = waiting for the remote to add us back), live S2S sessions (with one‑click **Add peer** for non‑federated servers), and connection settings (keepalive & reconnect). |
| **Routing Table** | Learned destinations with next hop, hop count, and last update. Hop count `1` = directly connected. **Deny** refuses a destination whenever its next‑hop peer advertises it (per‑link); the entry stays listed as a disabled row — surviving withdrawals and re‑advertisements — until **Allow** lifts it. |
| **Rooms** | Local rooms with a per‑room *Federated* toggle and current mappings; remote rooms advertised by peers. |

The page auto‑refreshes every 5 seconds.

---

## Configuration properties

Set under **Admin Console → Server → System Properties** (or via the Connection settings UI for the first two).

| Property | Default | Meaning |
|----------|---------|---------|
| `plugin.federation.keepaliveSeconds` | `240` | Interval for lightweight keepalive pings to reachable peers. Min 30. Auto‑clamped below Openfire's S2S idle timeout. |
| `plugin.federation.reconnectSeconds` | `30` | Back‑off **cap** for reconnecting UNREACHABLE peers. Retries grow 5→10→20→… up to this cap, then reset on reconnect. Min 5. |
| `plugin.federation.disableS2SIdle` | `true` | On startup, disable Openfire's server‑wide S2S idle reaper (`xmpp.server.idle`). See note below. |
| `plugin.federation.peerAllowlist` | `true` | Secure‑by‑default trust mode. Only configured peers may drive federation; every action from any other peer is rejected. Set `false` for open federation. See [Security](#security). |

### Note on `disableS2SIdle`

XMPP S2S (RFC 6120) uses a **separate one‑way socket per direction**, and Openfire 5.x does **not** implement
bidirectional S2S (XEP‑0288). An outgoing federation session therefore only ever *writes*; the peer's replies
return on its own separate socket. Openfire's idle reaper closes a socket that has received no **inbound**
bytes for `xmpp.server.idle`, so these write‑only sockets get reaped at exactly the idle timeout no matter how
often the keepalive fires — producing repeated `Connection has been idle` reconnects.

Because the plugin manages liveness itself (poll + reconnect back‑off), it disables that reaper on startup.
This is **server‑wide** — it affects *all* S2S connections, not just federation peers. Set the property to
`false` to leave Openfire's idle timeout untouched.

---

## Security

The federation trust boundary is enforced at several points:

- **Rooms are opt‑in.** A remote peer can only map — or inject presence/messages into — a local room an admin
  has explicitly toggled **Federated**. Forwarded traffic aimed at any non‑federated room is dropped and logged
  with a `SECURITY:` tag. This stops a peer from siphoning the roster of, or injecting into, a room it was never
  granted (injection otherwise bypasses MUC's own non‑occupant check).
- **Peer allowlist (default on).** `plugin.federation.peerAllowlist` defaults to `true`: only configured peers
  (those you **Add** in the admin console) may drive federation; every action from any other server is rejected.
  A peer is "configured" if you added it, so **both ends must add each other** — with the allowlist on,
  auto‑registration of unknown peers is suppressed. Set it to `false` (or use the **Security** toggle on the
  Peer Servers tab) for open federation, where any server that can connect is accepted and auto‑registered.
- **Untrusted peers (filtered exposure).** Mark a peer **Untrusted** (the checkbox next to *Add peer*, or the
  *Make untrusted* button on its row) and it receives **no** routing updates and **no** room advertisements at
  all. You then pick — per peer, via its *Servers* editor — exactly which **servers** it may see, chosen from
  this server itself (its federated local rooms) *and* any server reachable through it. The untrusted peer is sent
  only the federated rooms homed on those servers, plus a route to each, so it learns nothing about the rest of
  your topology. Enforcement is two‑way: inbound `room-mapping`/`muc-forward` from an untrusted peer aimed at a
  room homed on a server it was **not** exposed to is dropped and logged with a `SECURITY:` tag. This is the
  **edge‑server** pattern: federate with a partner organisation through one gateway that exposes only a curated
  set of servers.
  - **Trust is a property of the link.** Each end announces its stance (trusted/untrusted) in `peer-announce`;
    if the two disagree, the link is **blocked** (status *Trust mismatch*) and no federation flows until **both**
    admins set the same trust level. It then comes up automatically — no reconnect needed. The *Servers* editor
    shows both directions: on the left, the servers you expose to that peer (editable); on the right, the servers
    that peer is **advertising through** to you (read‑only).
  - **Untrusted by default for foreign peers.** When you add a peer whose **parent domain** differs from this
    server's (the last two DNS labels, e.g. `example.net`; adjustable via `plugin.federation.trustDomainLabels`),
    the *Untrusted* box is ticked automatically — a stranger shares nothing until you choose what it may see.
    Same‑parent peers default trusted.
- **Deniable route advertisements (per‑link inbound filter).** The exposure controls above govern what you *send*;
  each side can also refuse what it *receives*. If a peer advertises a route (and rooms) for a destination you
  don't want, click **Deny** — on the Routing Table row, or next to that server in the peer's *Servers* editor
  (right column). The destination is refused whenever **that** peer advertises it: any installed route via that
  peer is torn down immediately (with room/ghost clean‑up) and future advertisements are dropped on receive. A
  route to the same destination via a *different* peer is unaffected, and the deny is one‑sided — nothing is
  negotiated with the peer. The denied entry stays listed in the Routing Table as a disabled (struck‑through)
  row, and the deny is remembered even if the peer withdraws the route and advertises it again later.
  **Allow** (on the disabled row, or in the peer's *Servers* editor) lifts it and re‑solicits the peer so the
  route re‑appears. Denies are persisted per peer (`federation.peer.deniedroutes.<domain>`).
- **Mutual‑add handshake (Pending status).** A configured peer whose S2S link is up shows **Pending** — not
  *Reachable* — until its federation plugin sends us a `peer-announce`, i.e. until the remote has added us back
  (instantly, in open‑federation mode, via auto‑registration). No routes or gossip flow toward a pending peer;
  we keep announcing ourselves on the reconnect back‑off so the link confirms promptly once the remote adds us.
- **S2S key pinning (trust‑on‑first‑use).** The first time a peer's S2S link comes up, the plugin pins the
  SHA‑256 of the **public key** (SPKI) of the leaf certificate it presents. If that key later **changes** — e.g. a
  server is re‑created under the same domain name with a new key, even one signed by the same CA — the peer is
  **auto‑marked untrusted** and flagged in the Peers list (*⚠ cert changed*); federation toward it is blocked until
  you review and click *Trust new cert* to pin the new one. Pinning the key (rather than the CA chain) means an
  impersonator with a certificate from the same public CA is still caught; renewals that reuse the key pass
  silently, while a key rotation raises the flag for review. Requires a TLS‑secured S2S link (a plain
  server‑dialback link presents no certificate, so nothing is pinned). Pins made by versions before 1.7.13
  (top‑of‑chain cert hashes) are upgraded in place on the next sighting.
- **Sender‑identity (anti‑spoofing) checks.** Every stanza forwarded over the overlay has its claimed `from`
  validated at each hop: no peer may deliver a stanza pretending to come from **this server's own users**, and an
  **untrusted** peer may only speak for itself or for servers reached *through* it — a forged identity from the
  wrong direction is dropped and logged with a `SECURITY:` tag.
- **Per‑room visibility.** Each federated room has a **Visible** control (next to its toggle) listing the servers
  allowed to see it — chosen from the routable peers, plus servers you can **add manually before they're reachable**
  (the room advertises to them automatically once a route appears). A newly‑federated room defaults to **visible to
  no one** — pick specific servers, or tick **Visible to all peers** to share with everyone. The visibility set
  travels with the advertisement, so every relay confines the room to the path toward its allowed destinations —
  off‑path servers never learn it exists. The ACL is persisted and **survives a listed server being offline**.
  (Enforcement assumes on‑path servers run this plugin version or newer. Rooms that were already federated when you
  upgraded are migrated to *visible to all* so existing federation keeps working.)
- **Mapping consent.** Mapping onto a remote room now sends a **request** the other admin must **Accept** (shown in
  a *Pending mapping requests* panel and inline on the room) before any traffic flows; **Reject** declines it. On
  accept, a per‑mapping **token** is shared and re‑checked on every later lifecycle message, so a third party can't
  forge one and a remove+re‑add forces a fresh acceptance. A mapping can be **Disabled** instead of removed — the
  peer shows *"disabled by peer"* and it can be re‑enabled later. A per‑room **Auto‑accept** toggle makes a room
  free to join — incoming requests are accepted automatically (still subject to the federation/untrusted/visibility
  gates). **On upgrade, existing mappings drop to pending and must be re‑accepted** (the lower‑domain side
  auto‑re‑requests on reconnect; the other side just accepts).
- **Admin API CSRF.** The Federation tab's API uses a double‑submit token (a `fed-csrf` cookie echoed back as a
  request parameter), so a forged request from another site cannot trigger peer/room changes in a logged‑in
  admin's browser. After upgrading, reload an already‑open Federation tab once so its scripts pick up the token.
- **Identity.** Remote users are injected under their home‑qualified nick, and the plugin drops any forwarded
  stanza claiming to originate from a **local** user (anti‑spoofing). As with any federation, peers are trusted
  to represent **their own** users honestly — a compromised peer can still misrepresent users of domains it relays.

---

## How it works

The plugin exchanges control messages with peers using IQ stanzas in the `urn:xmpp:federation:1` namespace:

| Stanza | Purpose |
|--------|---------|
| `peer-announce` | Hello / keepalive; advertises this server to a peer. |
| `peer-withdraw` | Graceful disconnect of a peer relationship. |
| `peer-disable` | Administrative, authoritative block of a peer. |
| `routing-update` | Distance‑vector routing gossip (split‑horizon). |
| `routing-solicit` | Ask peers to re‑send routing + room info after topology loss. |
| `room-advertisement` | Announce a federated local room (relayed multi‑hop). |
| `room-mapping` / `room-unmap` | Link / unlink a local room to a remote room (relayed multi‑hop). |
| `muc-forward` | Carries the actual MUC presence/message traffic between mapped rooms. |
| `direct-forward` | Carries 1:1 chat messages to a multi‑hop contact (and message‑embedded XEPs: typing, receipts, reactions, OOB/upload links). |
| `presence-forward` | Carries 1:1 presence and subscription stanzas to a multi‑hop contact. |
| `iq-forward` | Carries user‑addressed IQs to a multi‑hop contact — **vCard/avatar (XEP‑0054/0153)**, plus disco/version/ping when answered by the contact's client. The vCard reply is built at the contact's server and relayed back, correlated by `id`. |
| `user-directory` | Opt‑in gossip of online users reachable on a domain. |
| `bookmark-push` | Opt‑in advertisement of a server's connected clients, injected into each peer user's bookmark storage as **XEP‑0048** `<url>` bookmarks so they appear in a normal chat client. |

Remote users appear in local rooms as **virtual occupants**, tracked by their home origin (for reachability
cleanup) and by the neighbour they arrived through (for per‑mapping teardown). Loop prevention uses a
`fed-origin` marker so forwarded traffic doesn't bounce.

**vCard, avatar, disco and caps over the overlay (1.6.0).** For multi‑hop contacts (no direct S2S link),
the plugin relays user‑addressed IQs so a contact's avatar and profile load, service discovery resolves,
and entity capabilities are learned. Forwarded presence now carries its full extension set — the avatar
hash (`vcard-temp:x:update`) and entity‑caps `<c/>` — so clients know there is an avatar to fetch and what
the contact supports. Adjacent (directly‑linked) peers continue to use native S2S for all of this.
**Jingle audio/video and file transfer (0166/0167/0176/0234) remain out of scope** — they need a separate
media/TURN path.

---

## Project layout

```
src/main/java/.../federation/
  FederationPlugin.java         Openfire entry point
  FederationManager.java        orchestrator; sending side of the protocol
  FederatedRoomManager.java     local/remote room state + virtual occupants
  FederationRoutingTable.java   distance-vector routing
  S2SMonitor.java               peer poll, keepalive, reconnect, idle-reaper handling
  protocol/                     IQ handler + stanza factory
src/main/resources/
  plugin.xml                    Openfire plugin descriptor
  readme.html                   in-console usage documentation
src/main/webapp/                admin console UI (index.html, federation.js)
src/assembly/openfire-plugin.xml  packaging descriptor
```

---

## License

Apache License 2.0.
