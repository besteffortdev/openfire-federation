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
- **Transparent file sharing** — HTTP File Upload (XEP‑0363) shares work across the federation with zero
  client changes: the file's content is relayed over the overlay to exactly the servers whose users can see
  the message (mapped‑room peers, a 1:1 recipient's home — never a broadcast), re‑hosted there, and the link
  is rewritten so every client downloads from its **own** server. Local‑only traffic never leaves; transit
  hops forward chunks without storing a copy.
- **File type filtering & content verification** — an admin‑configured extension allowlist gates what a
  server will stage for outbound relay (origin) and accept from a peer (destination); received content is
  additionally sniffed by magic number (Apache Tika) and rejected if it doesn't match its claimed extension
  (e.g. an executable renamed to `.jpg`). Optional ClamAV integration scans received content before it's
  servable to a local recipient. All of this applies only at the origin/destination — a transit hop relays
  file chunks without ever decoding them, so it's completely unaffected. See [Security](#security).

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

## Declarative config (openfire.xml)

Peers, room federation, sharing, visibility, and mappings can also be declared directly in this server's
own `conf/openfire.xml` — the same bootstrap file Openfire itself uses for setup/database settings — as a
`<federation>` block under the root `<jive>` element:

```xml
<jive>
  ...
  <federation>
    <!-- optional: file-share federation settings (only declared attributes are applied) -->
    <files enabled="true" maxSizeMB="25" retentionDays="90" storageDir="/var/lib/openfire/federation-files"/>
    <peers>
      <peer domain="2502-xmpp.example.net" untrusted="false"/>
      <peer domain="2506-xmpp.example.net" untrusted="true">
        <exposedServers><server>2505-xmpp.example.net</server></exposedServers>
      </peer>
    </peers>
    <rooms>
      <room jid="team@conference.2501-xmpp.example.net" federated="true" autoAccept="true">
        <visibleTo>
          <server>2502-xmpp.example.net</server>
          <!-- a single <server>*</server> means "visible to every peer" -->
        </visibleTo>
        <mappings>
          <mapping remoteJid="team@conference.2502-xmpp.example.net" remoteDomain="2502-xmpp.example.net"/>
        </mappings>
      </room>
    </rooms>
  </federation>
</jive>
```

The block is read **once on every plugin start**, and again on demand from the **Reload now** button in
Settings → *Config file (openfire.xml)*. It is **safe‑upsert, never destructive**: it only adds peers/
mappings that don't exist yet and updates the specific fields it declares (`untrusted`, `exposedServers`,
`federated`, `autoAccept`, `visibleTo`, and the `<files>` attributes `enabled`/`maxSizeMB`/`retentionDays`/`storageDir`);
anything already in the database but absent from the file — a peer added by hand, a mapping not listed —
is left alone. Re‑running it (restart or **Reload now**) is idempotent.

Notes:
- `autoAccept="true"` is the "room‑sharing" toggle — the same one the Rooms tab labels *Sharing
  (auto‑accept)*: the room accepts incoming mapping requests without an admin click.
- A `<room jid="…">` must refer to a MUC room that **already exists**; this only tags federation metadata
  onto it (like the admin console's *Federated* toggle) — it does not create rooms. For provisioning new
  rooms by name pattern, see `RoomDefaultsManager`'s existing "Default settings for new rooms" section on
  the Rooms tab instead.
- A `<mapping>` is only requested once the room is actually shared with that domain (federated + in
  `visibleTo`, checked the same way the admin console's *Map* button does) — list `<visibleTo>` before
  `<mappings>` if you want both applied in the same pass.
- `openfire.xml` is only ever **read**, never written, by this feature.

---

## Configuration properties

Set under **Admin Console → Server → System Properties** (or via the Connection settings UI for the first two).

| Property | Default | Meaning |
|----------|---------|---------|
| `plugin.federation.keepaliveSeconds` | `240` | Interval for lightweight keepalive pings to reachable peers. Min 30. Auto‑clamped below Openfire's S2S idle timeout. |
| `plugin.federation.reconnectSeconds` | `30` | Back‑off **cap** for reconnecting UNREACHABLE peers. Retries grow 5→10→20→… up to this cap, then reset on reconnect. Min 5. |
| `plugin.federation.disableS2SIdle` | `true` | On startup, disable Openfire's server‑wide S2S idle reaper (`xmpp.server.idle`). See note below. |
| `plugin.federation.peerAllowlist` | `true` | Secure‑by‑default trust mode. Only configured peers may drive federation; every action from any other peer is rejected. Set `false` for open federation. See [Security](#security). |
| `plugin.federation.files.enabled` | `true` | Federate HTTP File Upload shares: relay content to the servers that deliver the message and rewrite the link to their local `/federation-files` endpoint (HTTP‑bind port). Also in the *Files* tab. |
| `plugin.federation.files.maxSizeMB` | `25` | Largest file the relay will stage, transfer, or accept. Also in the *Files* tab. |
| `plugin.federation.files.chunkBytes` | `131072` | Raw bytes per `file-chunk` IQ (base64 adds ~33%; keep well under the S2S stanza‑size limit). |
| `plugin.federation.files.chunkDelayMs` | `20` | Pause between chunk sends so a big file can't starve chat traffic on the link. |
| `plugin.federation.files.retentionDays` | `90` | Days a relayed file is kept in the storage directory before purge. Also in the *Files* tab. |
| `plugin.federation.files.storageDir` | `/var/lib/openfire/federation-files` | Full path of the directory where relayed file content is stored. Changing it live moves existing files. Also in the *Files* tab. |
| `plugin.federation.files.publicUrlBase` | *(auto)* | Base URL for rewritten links to this server's download endpoint. Blank derives `https://<domain>:<http-bind-secure-port>/federation-files`; set explicitly behind a proxy. |
| `plugin.federation.files.extraLocalHosts` | *(empty)* | Extra comma‑separated host names that also identify THIS server's upload URLs (when the upload plugin announces a different address). |
| `plugin.federation.files.uploadPathMarker` | `/httpfileupload/` | Path fragment identifying an upload‑service URL; blank accepts any path on a local host. |
| `plugin.federation.files.allowedExtensions` | *(curated list — see Settings)* | Comma‑separated extensions the relay will stage (egress) or accept (ingress). Blank allows nothing; `*` allows everything. Also in the *Files* tab. |
| `plugin.federation.files.avEnabled` | `false` | Scan received file content with ClamAV before it's servable to a local recipient. Requires a reachable `clamd` (see below). A scan that can't complete is treated as a failure — fails closed. Also in the *Files* tab. |
| `plugin.federation.files.avHost` | `clamav` | Hostname of the clamd INSTREAM endpoint. Default matches the sidecar service name in the [docker-compose example](#optional-clamav-sidecar-docker-compose) below. |
| `plugin.federation.files.avPort` | `3310` | Port of the clamd INSTREAM endpoint. |
| `plugin.federation.files.avTimeoutMs` | `30000` | Socket connect/read timeout (ms) for a single clamd scan. |

### Optional: ClamAV sidecar (docker-compose)

`avEnabled` needs a `clamd` daemon reachable from the Openfire container. If you run Openfire under Docker
Compose, add the official ClamAV image as a sidecar on the same network — no ports need publishing to the
host, only the Openfire container needs to reach it:

```yaml
services:
  xmpp:
    image: "openfire:5.1.0"
    # ...existing xmpp service config...

  clamav:
    image: clamav/clamav:stable
    container_name: xmpp-clamav
    restart: unless-stopped
    volumes:
      - ./clamav-db:/var/lib/clamav   # persists signature definitions across restarts
```

Scanning itself is fully offline: `clamd` scans against whatever signature database it already has on disk
regardless of current connectivity. The sidecar's bundled `freshclam` only needs the network to *refresh*
those definitions — bring the container up once while online to seed the initial database (a few hundred MB
of signatures), after that it keeps working with no network access at all. Use the *Files* tab →
*Test connection* to confirm the plugin can reach it before turning `avEnabled` on.

the *Files* tab also lists *Recently scanned files (ClamAV)* — the last 200 received files this
server has scanned (time, name, size, sending peer, verdict, and AV detail), newest first. It's in-memory
only (transit hops are never scanned or listed, and the list resets on plugin reload/restart) — a quick way
to confirm scanning is actually happening and see what, if anything, has been caught.

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
  *Make untrusted* button in its settings panel) and it receives **no** routing updates and **no** room advertisements at
  all. You then pick — per peer, via its settings panel (the expand arrow on its row) — exactly which **servers** it may see, chosen from
  this server itself (its federated local rooms) *and* any server reachable through it. The untrusted peer is sent
  only the federated rooms homed on those servers, plus a route to each, so it learns nothing about the rest of
  your topology. Enforcement is two‑way: inbound `room-mapping`/`muc-forward` from an untrusted peer aimed at a
  room homed on a server it was **not** exposed to is dropped and logged with a `SECURITY:` tag. This is the
  **edge‑server** pattern: federate with a partner organisation through one gateway that exposes only a curated
  set of servers.
  - **Trust is a property of the link.** Each end announces its stance (trusted/untrusted) in `peer-announce`;
    if the two disagree, the link is **blocked** (status *Trust mismatch*) and no federation flows until **both**
    admins set the same trust level. It then comes up automatically — no reconnect needed. The peer’s settings panel
    shows both directions: the servers you expose to that peer (editable), and the servers that peer is
    **advertising through** to you (each deniable per‑link).
  - **Untrusted by default for foreign peers.** When you add a peer whose **parent domain** differs from this
    server's (the last two DNS labels, e.g. `example.net`; adjustable via `plugin.federation.trustDomainLabels`),
    the *Untrusted* box is ticked automatically — a stranger shares nothing until you choose what it may see.
    Same‑parent peers default trusted.
- **Deniable route advertisements (per‑link inbound filter).** The exposure controls above govern what you *send*;
  each side can also refuse what it *receives*. If a peer advertises a route (and rooms) for a destination you
  don't want, click **Deny** — on the Routing Table row, or next to that server in the peer’s settings panel
  (right column). The destination is refused whenever **that** peer advertises it: any installed route via that
  peer is torn down immediately (with room/ghost clean‑up) and future advertisements are dropped on receive. A
  route to the same destination via a *different* peer is unaffected, and the deny is one‑sided — nothing is
  negotiated with the peer. The denied entry stays listed in the Routing Table as a disabled (struck‑through)
  row, and the deny is remembered even if the peer withdraws the route and advertises it again later.
  **Allow** (on the disabled row, or in the peer’s settings panel) lifts it and re‑solicits the peer so the
  route re‑appears. Denies are persisted per peer (`federation.peer.deniedroutes.<domain>`).
  - **End‑to‑end mapping probe (mapping‑ping).** A deny mid‑path is invisible to servers on the far side —
    their routing tables still show a route while replies silently die at the denying hop. So every active
    room mapping is probed end‑to‑end (default every 30 s, `plugin.federation.mappingPingSeconds`, 0 = off):
    the mapped domain answers with a pong routed back across the overlay. Three unanswered probes in a row
    (≈2 minutes at the default cadence) flip the mapping to **⚠ not responding**, its remote occupants are
    dropped (no more stale ghosts), and
    when pongs resume the flag clears and rosters re‑sync automatically. Peers running an older plugin never
    answer probes and are simply never flagged: a domain is only eligible for the *not responding* verdict
    once it has provably answered (or sent) a probe, and that proof is **persisted**
    (`plugin.federation.probeCapableDomains`) so it survives restarts — a path already broken when the
    plugin starts is still detected. Each mapping row in the room's settings panel shows the probe's
    round‑trip time and the age of the last answer (`ping 12 ms · 3s ago`; `ping —` = no answer yet).
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
- **File type filtering & scanning.** `plugin.federation.files.allowedExtensions` (the *Files* tab)
  gates what a server will stage for outbound relay (egress, at the origin) and accept from a peer (ingress, at
  the destination — defense in depth against a peer whose own filter is absent, bypassed, or an older plugin
  version). At the destination, received content is additionally sniffed by magic number (no filename hint) and
  rejected on a confident mismatch against the claimed extension — e.g. an executable renamed to `.jpg` is caught
  even though `.exe` was never on the allowlist to begin with; a generic sniff result (plain text, unrecognized
  binary) is inconclusive and not treated as a mismatch. Optional ClamAV scanning (`avEnabled`, off by default)
  adds a real signature‑based scan of received content before it becomes servable; a scan that can't complete
  (clamd unreachable) fails closed rather than serving unscanned content. None of this touches a transit hop —
  `relayToward` forwards file‑\* elements without ever decoding their content, so a purely‑relaying server is
  unaffected regardless of configuration.

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
