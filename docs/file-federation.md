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
>    upload service, it attaches a `<fed-file id url name origin/>` annotation (`id` = SHA-256 of the URL) to
>    the forwarded copies, and the content is fetched into the local `FileRelayStore` so it can be served to
>    peers.
> 2. **Destination (rewrite + pull):** every server that DELIVERS the message to local users rewrites the URL
>    to its own `/federation-files/<id>/<name>` endpoint (served on the HTTP-bind port clients already reach),
>    strips the annotation, and pulls the content over the overlay: `file-request` toward the origin, answered
>    by `file-offer` + a stream of base64 `file-chunk`s … Intermediate hops relay without storing — a transit
>    organisation never keeps a usable copy on disk.

**Why a pull model?** It gives privacy scoping for free: a file moves to *exactly* the servers that deliver
the announcing message (mapped-room peers, a 1:1 recipient's home), never a broadcast. Purely local traffic
is never annotated, so it never leaves. Peers on an older plugin just ignore the annotation and show the
original URL.

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
        route(FederationStanzaFactory.fileRequest(nextHop.get(), target, localDomain(), t.id, ""));
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

- **FETCHING** — origin is pulling its own upload into the store.
- **REQUESTED** — a `file-request` is in flight (retried up to `MAX_REQUEST_ATTEMPTS`, `REQUEST_RETRY_MS`
  apart).
- **RECEIVING** — chunks are landing into a `RandomAccessFile`, tracked by a `BitSet` until complete.
- **FAILED** — no routable holder, or a definitive rejection.

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
    ...
    if (store.has(id)) { startSend(id, requester); return; }            // have it → serve now
    Transfer t = transfers.get(id);
    if (t != null && t.state != State.FAILED) {                         // still arriving → park
        parkedRequests.computeIfAbsent(id, k -> newKeySet()).add(requester);
        return;
    }
    if (t != null && t.rejected) { sendError(requester, id, "policy-rejected"); return; }  // known-bad → refuse fast
}
```

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

Every block is recorded in the in-memory `RejectionEntry` log (and AV outcomes in `ScanLogEntry`) surfaced by
the *Files* tab. A definitive rejection also notifies the waiting local room/user in-chat via the
`localDestinations` map.

## Serving downloads

The rewritten `/federation-files/<id>/<name>` URL is served by
[`FederationFileServlet`](../src/main/java/com/igniterealtime/openfire/plugin/federation/files/FederationFileServlet.java)
on Openfire's HTTP-bind port (the one clients already reach), streaming from the `FileRelayStore`. A rejected
id returns a fixed generic 403 rather than leaking why.

## Related

- **Which neighbour** each `file-request`/`file-chunk` is routed to: [routing.md](routing.md).
- **The full filtering/AV threat model** and `SECURITY:` gates: [security.md](security.md).
