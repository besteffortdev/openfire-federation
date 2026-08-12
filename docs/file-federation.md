# File federation (HTTP Upload relay)

How a file share travels the overlay so every client downloads from **its own** server — even though the
original upload URL only exists on the sharer's server. Snippets are from
[`FileRelayManager.java`](../src/main/java/com/igniterealtime/openfire/plugin/federation/files/FileRelayManager.java).

## The problem

An [XEP-0363](https://xmpp.org/extensions/xep-0363.html) share is just a message whose body (and
`jabber:x:oob` extension) carries a URL on the **sharer's own** upload service. A client on another federation
organisation — especially across multi-hop paths — can't reach that host. (This is exactly the interop gap a
client like TAK Chat hits: it uploads to its local component and shares that URL.) The relay closes it with
zero client changes.

## The design in two steps

Straight from the class javadoc:

> 1. **Origin (annotate + stage):** when the interceptor forwards a message whose URL points at the local
>    upload service, it attaches a `<fed-file id url name origin token/>` annotation (`id` = SHA-256 of the
>    URL, `token` = the share capability) to the forwarded copies, and the content is fetched into the local
>    `FileRelayStore` so it can be served to peers.
> 2. **Destination (rewrite + pull):** every server that DELIVERS the message to local users rewrites the URL
>    to its own `/federation-files/<id>/<name>` endpoint (served on the HTTP-bind port clients already reach),
>    strips the annotation, and pulls the content over the overlay: `file-request` toward the origin, answered
>    by `file-offer` + a stream of base64 `file-chunk`s … Intermediate hops relay without storing — a transit
>    organisation never keeps a usable copy on disk.

**Why a pull model?** It gives privacy scoping for free: a file moves to *exactly* the servers that deliver
the announcing message (mapped-room peers, a 1:1 recipient's home), never a broadcast. Purely local traffic
is never annotated, so it never leaves. Peers on an older plugin just ignore the annotation and show the
original URL.

### The share capability (`token`)

That scoping is a property of *routing*, and until 1.10.6 nothing enforced it: `id` is derived from the
upload URL and travels in the annotation through every server on the message path, so any allowlisted peer
that had merely relayed an announcement — including a transit organisation that is deliberately never given
the content — could ask a holder for it by id and be served. The id identifies content; it was never a
credential.

So the origin now mints a 128-bit random `token` per share and publishes it in the annotation. Because the
annotation travels with the message, exactly the servers in scope learn it. A `file-request` must echo it
back, and a holder that has one and is shown the wrong one (or none) answers `not-authorized` instead of
streaming. A hub that pulls a share keeps the token with the stored file, so it can authorize the same
spokes the origin would have — no extra round trip, no key distribution.

Two further bindings close the same gap from other directions: an untrusted peer's request must arrive on
the route back to the domain it claims to be requesting for (a trusted peer's need not — diamond topologies
and hub fan-out legitimately arrive off-path, which is the same asymmetry `payloadOriginOk` already makes),
and an untrusted peer addressing this server directly is now subject to the exposure gate that previously
only covered traffic passing *through*.

**Upgrading:** the check is fail-closed in one direction only. A holder with no token for an id — content
staged before 1.10.6 — serves it as before, so existing shares keep working and age out with the retention
window. But a 1.10.6 origin's shares cannot be fetched by a peer old enough to drop the attribute, so
**upgrade the whole mesh together** rather than one server at a time.

## Egress: annotate + stage

The message interceptor recognises a share whose URL is local (`isLocalUploadShare` → `isLocalUploadUrl`),
computes `id = SHA-256(url)`, and fetches the bytes into the store so peers can pull them. The forwarded
copies carry the `<fed-file>` annotation; the local delivery is left untouched. The `allowedExtensions`
allowlist is applied here too, so a disallowed file is never even offered to a peer (`egress` rejection).

## Ingress: rewrite the link, pull the bytes

A delivering server rewrites the message so its clients fetch locally, then pulls the content. The rewrite
points at this server's own endpoint:

```java
private String publicUrlFor(String id, String name) { ... }   // → https://<domain>:<bind>/federation-files/<id>/<name>
```

The pull picks a holder to ask — the annotated `origin` first, then any `hints` — and routes a `file-request`
toward it by the routing table:

```java
private void sendRequest(Transfer t) {
    LinkedHashSet<String> candidates = new LinkedHashSet<>();
    if (t.origin != null && !t.origin.isBlank()) candidates.add(t.origin);
    candidates.addAll(t.hints);
    candidates.remove(localDomain());
    for (String target : candidates) {
        var nextHop = manager.getRoutingTable().findNextHop(target);
        if (nextHop.isEmpty()) continue;
        route(FederationStanzaFactory.fileRequest(nextHop.get(), target, localDomain(), t.id, t.token, ""));
        t.expectedFrom = nextHop.get();   // legitimate offer/chunk/error must come back this way
        t.requestAttempts.incrementAndGet();
        return;
    }
    // no routable holder → State.FAILED
}
```

The origin answers with a `file-offer` (size, mime, chunk count, SHA-256) followed by a stream of base64
`file-chunk`s. Chunking is bounded and paced by `files.chunkBytes` / `files.chunkDelayMs` so a big file can't
starve chat traffic on the link.

## The content lifecycle

Content we don't have yet is a `Transfer` moving through a small state machine; complete content lives in the
`FileRelayStore`:

```java
private enum State { FETCHING, REQUESTED, RECEIVING, FAILED }
```

- **FETCHING** — origin is pulling its own upload into the store. Bounded by an *absolute* budget, not just
  the socket timeouts: a source trickling bytes below the read timeout would otherwise hold a worker
  indefinitely, since every byte resets it.
- **REQUESTED** — a `file-request` is in flight (retried up to `MAX_REQUEST_ATTEMPTS`, `REQUEST_RETRY_MS`
  apart).
- **RECEIVING** — chunks are landing into a `RandomAccessFile`, tracked by a `BitSet` until complete.
- **FAILED** — no routable holder, or a definitive rejection.

A `file-error` aborts a transfer in **either** REQUESTED or RECEIVING. Handling only REQUESTED meant an
error arriving mid-stream told the waiting room its file was rejected while the chunks already in flight
went on to complete and the file became downloadable anyway — two contradictory outcomes for one share, plus
a part file and its descriptor left open until the idle sweep noticed.

Staging fetches and outbound streams run on their own worker pool, separate from the sweep and purge
schedule. Sharing one two-thread pool meant a pair of stalled fetches could starve the very sweep that was
supposed to time them out.

## Transit hops never keep a copy

If a `file-*` element isn't for us, we relay it onward — subject to the same untrusted-peer exposure gate as
any routed stanza — without ever decoding the content:

```java
public void relayToward(String elementName, Element el, String fromDomain) {
    String destination = el.attributeValue("destination");
    if (FederationStanzaFactory.viaContains(via, local)) { /* loop → drop */ return; }
    String newVia = via.isEmpty() ? local : via + "," + local;
    manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
        nextHop -> route(FederationStanzaFactory.fileRelay(nextHop, el, newVia)),
        ()      -> Log.debug("{}: no route to {}, dropping", elementName, destination));
}
```

## Hubs: park-and-serve (store-and-forward)

When several spokes want a file that a hub is *itself* still pulling, the hub **parks** their requests and
serves them once its own copy lands — store-and-forward along the mapping topology:

```java
public void handleFileRequest(String fromDomain, Element el) {
    if (!requesterDirectionOk(fromDomain, requester)) return;           // untrusted: must be on the route back
    if (!shareTokenOk(id, token)) { sendError(requester, id, "not-authorized"); return; }
    if (store.has(id)) { startSend(id, requester); return; }            // have it → serve now
    Transfer t = transfers.get(id);
    if (t != null && t.state != State.FAILED) {                         // still arriving → park
        parkedRequests.computeIfAbsent(id, k -> newKeySet()).add(requester);
        return;
    }
    if (t != null && t.rejected) { sendError(requester, id, "policy-rejected"); return; }  // known-bad → refuse fast
}
```

Sends are bounded rather than queued: a send already running for the same peer and id is not started twice,
and past `MAX_INFLIGHT_SENDS` concurrent streams a requester gets `busy` (transient — it retries) instead of
having another full-file transfer queued on its behalf.

A permanent rejection propagates cleanly across any number of hops: `handleFileRelay` already relays a
`file-error` straight through to its true destination, so one server classifying a `reason` as permanent
(`PERMANENT_REJECT_REASONS`) and every server recognising the same code is enough — no wire changes.

## Ingress verification & scanning (destination only)

Before received content becomes servable to a local recipient, the destination applies defense-in-depth
(detail in [security.md](security.md#file-type-filtering--scanning)):

- **Extension allowlist** (again, on ingress — a peer's filter may be absent or older).
- **Magic-number sniff** (Apache Tika) — an executable renamed `.jpg` is caught even though `.exe` was never
  allowlisted; an inconclusive sniff is not treated as a mismatch.
- **SHA-256** check against the offer.
- **Optional ClamAV** (`avEnabled`, off by default) — a scan that can't complete **fails closed**.

Every block is recorded as a `RejectionEntry` (and AV outcomes as a `ScanLogEntry`) surfaced by the *Files*
tab → *Activity Log*. A definitive rejection also notifies the waiting local room/user in-chat via the
`localDestinations` map.

Both logs are disk-backed by
[`FileActivityLog`](../src/main/java/com/igniterealtime/openfire/plugin/federation/files/FileActivityLog.java):
one escaped, tab-separated record per line in `<openfireHome>/logs/federation-file-scans.log` and
`federation-file-rejections.log`, appended as each entry is recorded and read back into the (200-entry)
in-memory views at `FileRelayManager.start()`, so the tables survive a reload or restart and can equally be
read straight off the file system. `prune()` drops entries older than `files.logRetentionDays` (default 180)
at startup, every six hours, and immediately when the setting is saved — rewriting through a sibling temp
file and an atomic move, so a failed prune leaves the log exactly as it was.

## Serving downloads

The rewritten `/federation-files/<id>/<name>` URL is served by
[`FederationFileServlet`](../src/main/java/com/igniterealtime/openfire/plugin/federation/files/FederationFileServlet.java)
on Openfire's HTTP-bind port (the one clients already reach), streaming from the `FileRelayStore`. A rejected
id returns a fixed generic 403 rather than leaking why.

## When the relay can't start

Storage and the download endpoint can each fail independently (bad `storageDir` permissions, Jetty refusing
the handler). The relay then reports itself **unavailable** — visible as `filesRelayAvailable` in the admin
API status, and distinct from the `filesEnabled` switch — and behaves exactly as if file federation were
turned off: nothing is annotated on the way out, and **no inbound share URL is rewritten**.

That last part is the point. A half-built relay used to keep rewriting: it replaced a remote URL that worked
with a local `/federation-files` link that would 404 for the life of the process. Leaving the original URL
alone is a much better degraded mode than breaking the link.

Saving a usable `files.storageDir` is the recovery action — it opens the store (or moves it) and re-mounts
the endpoint if that is what failed, so a permissions mistake does not need a server restart to clear.

## Related

- **Which neighbour** each `file-request`/`file-chunk` is routed to: [routing.md](routing.md).
- **The full filtering/AV threat model** and `SECURITY:` gates: [security.md](security.md).
