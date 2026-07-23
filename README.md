# Openfire Federation Plugin

Better MUC (group chat) federation for [Openfire](https://www.igniterealtime.org/projects/openfire/) — a
dynamic routing overlay that lets multi‑user chat rooms span many Openfire servers, including servers that
are **not directly connected** to each other.

Standard XMPP server‑to‑server (S2S) already lets a user on one server join a room on another. This plugin
goes further: it builds a **federation overlay** on top of S2S so that a single logical room can be
**mapped across several servers at once**, with messages and presence relayed **multi‑hop** between servers
that have no direct link. End users do nothing special — they just join their local room.

## Contents

- [Features](#features)
- [Requirements](#requirements)
- [Build](#build) · [Install](#install) · [Quick start](#quick-start)
- [Admin console reference](#admin-console-reference)
- [Declarative config (openfire.xml)](#declarative-config-openfirexml)
- [Configuration properties](#configuration-properties)
- [Security](#security)
- [How it works](#how-it-works)
- [Documentation](#documentation) — deep-dive guides with code
- [Project layout](#project-layout)
- [License](#license)

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

Federation traffic only ever touches rooms you explicitly enable, and every relay hop validates who it is
talking to. The controls at a glance:

| Control | What it enforces |
|---------|------------------|
| **Opt‑in rooms** | A peer can only map or inject into a room an admin toggled **Federated**; anything else is dropped and logged `SECURITY:`. |
| **Peer allowlist** *(default on)* | Only peers you added may federate — **both ends must add each other**. `plugin.federation.peerAllowlist=false` for open federation. |
| **Untrusted peers** | An untrusted peer gets no routes or rooms except the specific servers you expose to it — the edge‑server / partner‑gateway pattern. Foreign‑domain peers default untrusted. |
| **Trust is per‑link** | Both ends must declare the same trust level, or the link is blocked (*Trust mismatch*) until they agree. |
| **Deniable routes** | **Deny** a route/room a peer advertises: torn down and dropped on receive, one‑sided, persisted, survives re‑advertisement. |
| **Mapping probe** | Active mappings are pinged end‑to‑end; 3 misses → **⚠ not responding**, ghosts dropped; auto‑recovers when pongs resume. |
| **Mutual‑add handshake** | A configured peer stays **Pending** (no gossip flows) until the remote adds you back. |
| **S2S key pinning (TOFU)** | Pins each peer's leaf public key; a key change auto‑marks it untrusted (*⚠ cert changed*) until reviewed. |
| **Anti‑spoofing** | Every forwarded stanza's `from` is validated per hop — no peer may speak for your users or from the wrong direction. |
| **Per‑room visibility** | Each room lists which servers may see it (default: none); the set travels with the advertisement so off‑path servers never learn it exists. |
| **Mapping consent** | Mapping requires the other admin's **Accept**; a per‑mapping token is re‑checked on every later lifecycle message. |
| **Admin API CSRF** | A double‑submit `fed-csrf` token guards all Federation‑tab API calls. |
| **File filtering & AV** | Extension allowlist (egress + ingress) + magic‑number sniff + optional ClamAV (fails closed); transit hops never decode content. |

Full detail — threat model, per‑control enforcement points, upgrade notes, and the `SECURITY:` log tags — is
in **[docs/security.md](docs/security.md)**.

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

## Documentation

In‑repo deep‑dives (design + annotated code) for the core subsystems live under [`docs/`](docs/):

| Doc | Covers |
|-----|--------|
| [docs/security.md](docs/security.md) | Full trust model — every control summarised in the [Security](#security) table, with enforcement points and upgrade notes. |
| [docs/routing.md](docs/routing.md) | Distance‑vector (Bellman‑Ford) overlay routing: gossip merge, split‑horizon, stale‑route withdrawal, peer‑down purge — walked through the real `FederationRoutingTable` code. |
| [docs/room-mapping.md](docs/room-mapping.md) | Room mapping & multi‑hop MUC forwarding: the `muc-forward` hop decision, injection past MUC's non‑occupant check, hub fan‑out, ghost‑free teardown. |
| [docs/file-federation.md](docs/file-federation.md) | Transparent HTTP‑Upload (XEP‑0363) file relay: annotate + stage at the origin, rewrite + pull at the destination, park‑and‑serve hubs, copy‑free transit. |

The in‑console **documentation/readme** link ([`src/main/resources/readme.html`](src/main/resources/readme.html))
has a screenshot‑level admin walkthrough.

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
docs/                           in-repo deep-dive documentation (security, routing, …)
```

---

## License

Apache License 2.0.
