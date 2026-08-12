# 07 — File relay

An [XEP-0363](https://xmpp.org/extensions/xep-0363.html) share is a message whose body and
`jabber:x:oob` extension carry a URL on the **sharer's own** upload service. A client three hops away
cannot reach that host, and often cannot resolve it at all.

The relay closes that gap with no client changes: every server that delivers the message rewrites the
URL to point at **itself**, and pulls the bytes across the overlay.

## Shape of the exchange

```
  ORIGIN (alpha)                  HUB (beta)                 DESTINATION (gamma)
  ──────────────                  ──────────                 ───────────────────
  user uploads to
  alpha's HTTP service
        │
        │ message + <fed-file id url name origin token/>
        ├──────────────────────────────▶ relay ─────────────────────▶
        │                                                     rewrite URL to
        │                                                     gamma's endpoint,
        │                                                     strip annotation,
        │                                                     deliver to client
        │                                                            │
        │             ◀───── file-request (id, token) ────────────────┤
        ├────── file-offer (size, sha256, chunkSize, totalChunks) ───▶│
        ├────── file-chunk (seq=0) ──────────────────────────────────▶│
        ├────── file-chunk (seq=1) ──────────────────────────────────▶│
        │                    …                                        │
        │                                              verify, scan, serve
```

The model is **pull, not push**, and that is what gives privacy scoping for free: content moves to
exactly the servers that deliver the announcing message, never by broadcast. Purely local traffic is
never annotated, so it never leaves. A transit hop relays the stanzas onward without decoding them, so
a relaying organisation never materialises a usable copy.

## The `fed-file` annotation

Attached to the **forwarded copies** of a message whose URL points at the local upload service. The
copy delivered to local users is left untouched.

```xml
<message type='groupchat' from='amy@alpha.example/phone' to='chat@conference.alpha.example'>
  <body>https://upload.alpha.example/ab12/photo.jpg</body>
  <x xmlns='jabber:x:oob'>
    <url>https://upload.alpha.example/ab12/photo.jpg</url>
  </x>
  <fed-file xmlns='urn:xmpp:federation:1'
            id='497a2cdc4c984bf3862701aa3da269654705672b1b578842354b64c6aa5c74d1'
            url='https://upload.alpha.example/ab12/photo.jpg'
            name='photo.jpg'
            origin='alpha.example'
            token='4f2a9c1e7b3d5086af41c2e9d7b60315'/>
</message>
```

| Attribute | Meaning |
|-----------|---------|
| `id` | **Lowercase hex SHA-256 of the URL string's UTF-8 bytes.** Deterministic — every server derives the same id for the same URL. |
| `url` | The original upload URL. Carried so a server with direct reachability could use it, and for diagnostics. |
| `name` | File name, for the rewritten link and for extension checks. Sanitise before use. |
| `origin` | The server holding the content. First candidate to ask. |
| `token` | The **share capability**. See below. Omitted by peers older than 1.10.6. |

The annotation rides inside whatever action carries the message — `muc-forward` for rooms,
`direct-forward` for 1:1 — and is **not** a routed action of its own.

### The share capability (`token`)

`id` is derived from the URL and travels with the announcement through every server on the message
path. **It identifies content; it is not a credential.** Any allowlisted peer that merely relayed an
announcement — including a transit organisation that is deliberately never given the content — could
otherwise ask a holder for it by id and be served.

So the origin mints **128 bits of cryptographically random data**, hex-encoded, per share, and
publishes it in the annotation. Because the annotation travels with the message, exactly the servers
in scope learn it.

```
holder receiving a file-request:
    expected := token stored alongside this id
    if expected is empty        → serve      (content staged before capabilities existed)
    if presented == expected    → serve      (compare in constant time)
    otherwise                   → file-error reason='not-authorized'
```

A server that pulls a share **stores the token with the content**, so a hub can authorise the same
spokes the origin would have. No key distribution, no extra round trip.

> **Upgrade note.** The check is fail-closed in one direction. A holder with no token for an id serves
> it as before, so pre-existing shares keep working. But **a 1.10.6 origin's shares cannot be fetched
> by a peer that drops the attribute.** If you implement the relay, carry `token` through your
> annotation handling verbatim, even if you never validate it yourself.

## Origin: annotate and stage

```
on forwarding a message to a peer:
    url := local upload URL in the message, if any        (else: nothing to do)
    id  := sha256hex(url)
    token := existing token for id, or a fresh 128-bit random value
    stage the content into local storage, asynchronously
    attach <fed-file/> to each forwarded copy
```

Staging fetches the bytes from your own upload service into relay storage so peers can pull them. Two
implementation notes that came out of real failures:

- Bound the fetch with an **absolute deadline**, not just socket timeouts. A source trickling bytes
  below the read timeout resets that timeout on every byte and holds a worker indefinitely.
- Run staging and outbound streaming on a worker pool **separate** from your sweep/timeout scheduler.
  Sharing one small pool means a pair of stalled fetches starves the very sweep meant to time them out.

The extension allowlist is applied here too, so a disallowed file is never offered to a peer at all.

## Destination: rewrite and pull

```
on delivering a message that carries <fed-file/> to local users:
    rewrite the URL (body and jabber:x:oob) to our own endpoint
    strip the <fed-file/> annotation from the delivered copy
    leave the annotation on any copy that fans out further
    begin a pull for id
```

This implementation's endpoint is
`https://<domain>:<http-bind-port>/federation-files/<id>/<name>` — served on the port clients already
reach. Yours can be anything; the URL never crosses the wire in this protocol.

Keeping the annotation on the fan-out copy is what lets downstream servers rewrite for *themselves*.
Strip it only from what you hand to a client.

### Choosing a holder

```
candidates := [ annotation origin ] + hints, minus our own domain
for each candidate with a route:
    send file-request toward it
    remember the next hop — legitimate replies must come back that way
    stop
if none are routable → fail
```

Hints are additional holders learned from context — the mapped server the announcement entered
through, and the relay neighbour. They matter when the annotating origin is not routable from here
because of exposure filtering, but a hub in between has the content.

## `file-request`

```xml
<iq type='set' from='gamma.example' to='beta.example' id='f20'>
  <federation xmlns='urn:xmpp:federation:1'>
    <file-request destination='alpha.example'
                  origin='gamma.example'
                  id='497a2cdc4c984bf3862701aa3da269654705672b1b578842354b64c6aa5c74d1'
                  token='4f2a9c1e7b3d5086af41c2e9d7b60315'/>
  </federation>
</iq>
```

`origin` is the **requester** here — the address the content must be streamed back to. `destination`
is the holder being asked.

Holder algorithm:

```
if id is not a valid hex id, or requester is blank or is us    → ignore
if the requester direction check fails                         → ignore  (09-validation)
if the share-capability check fails                            → file-error 'not-authorized'
if we have the content                                         → start sending
if we are still fetching or receiving it                       → PARK the requester
if we know it was permanently rejected                         → file-error 'policy-rejected'
if we have a staging URL but the earlier attempt failed        → park, and re-stage
otherwise                                                      → file-error 'not-found'
```

### Park-and-serve

Parking is what makes hubs work. Several spokes may ask a hub for a file the hub is *itself* still
pulling; the hub records them and serves each the moment its own copy lands. Store-and-forward along
the mapping topology, with one pull from the origin instead of one per spoke.

### Bounding sends

Sends are **bounded, not queued**:

- A send already running for the same (peer, id) is not started twice.
- Past a concurrency cap (8 here), the requester gets `file-error` with reason `busy` — a **transient**
  reason it will retry — instead of having another full-file transfer queued on its behalf.

## `file-offer`

The transfer header. Authoritative metadata and chunk geometry.

```xml
<file-offer destination='gamma.example'
            origin='alpha.example'
            id='497a2cdc…'
            name='photo.jpg'
            mime='image/jpeg'
            size='2411904'
            sha256='c7be1ed902fb8dd4d48997c6452f5d7e509fbcdbe2808b16bcf4edce4c07d14e'
            chunkSize='131072'
            totalChunks='19'/>
```

| Attribute | Notes |
|-----------|-------|
| `name` | Defaults to `file` if the sender has none. |
| `mime` | Defaults to `application/octet-stream`. |
| `size` | Total bytes, decimal. |
| `sha256` | Lowercase hex of the whole file. Optional but strongly recommended — it is the requester's integrity check. |
| `chunkSize` | Raw bytes per chunk, **before** base64. |
| `totalChunks` | Exactly `ceil(size / chunkSize)`. |

### Geometry validation

A requester MUST validate before allocating anything:

```
size >= 0  and  size <= our configured maximum
chunkSize >= 1  and  chunkSize <= 1 MiB
totalChunks >= 0
totalChunks == ceil(size / chunkSize)          ← exact, not >=
```

Any failure fails the transfer. This is the allocation-bounding check — an offer claiming a
petabyte-sized file, or an inconsistent chunk count, must not reach the code that sizes a bitmap or a
file.

`size='0'` with `totalChunks='0'` is a valid empty file and completes immediately with no chunks.

A second offer for a transfer already receiving is a **duplicate** and must be ignored, not re-applied.

## `file-chunk`

```xml
<file-chunk destination='gamma.example' origin='alpha.example' id='497a2cdc…' seq='0'>
  /9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJ…
</file-chunk>
```

The element **text** is standard base64 of `chunkSize` raw bytes (fewer for the last chunk). `seq` is
zero-based.

Assembly is **offset-addressed**, so order does not matter: seek to `seq × chunkSize` and write.
Requester rules:

```
if we have no transfer for id, or it is not in the receiving state    → ignore
if it did not arrive from the expected direction                      → ignore, log SECURITY
if seq is out of range, or already received                           → ignore
expected length := (seq == totalChunks-1) ? size - seq*chunkSize : chunkSize
if the decoded length != expected                                     → FAIL the transfer
write at seq*chunkSize; mark seq received
when every chunk is received                                          → finalise
```

The exact-length check is not pedantry: it is what stops a peer from writing outside the region the
offer's geometry accounted for.

### Chunk geometry

Each chunk becomes one IQ stanza, and base64 inflates by roughly 33%. Defaults here: **131072 raw
bytes** per chunk (about 175 KB on the wire), clamped to `[16 KiB, 1 MiB]`, with a **20 ms** pause
between sends so a large transfer cannot starve chat traffic on the same link.

Keep chunks well under your S2S stanza-size limit. A receiver enforces the 1 MiB ceiling on the
`chunkSize` it will accept, so do not exceed it regardless of your own configuration.

## `file-error`

```xml
<file-error destination='gamma.example' origin='alpha.example' id='497a2cdc…' reason='not-authorized'/>
```

`reason` is a short code. Two classes, and the distinction is the important part:

**Transient** — the requester may retry.

| Reason | Meaning |
|--------|---------|
| `not-found` | The holder does not have it (and is not fetching it). |
| `busy` | Too many concurrent sends right now. |
| anything unrecognised | Treat as transient. |

**Permanent** — the same bytes would fail the same way; do not retry, and propagate.

| Reason | Meaning |
|--------|---------|
| `not-authorized` | Missing or wrong share capability. |
| `policy-rejected` | Deliberately vague: blocked by policy without naming which. |
| `extension-not-allowed` | File type not on the allowlist. |
| `content-mismatch` | Magic-number sniff contradicts the claimed extension. |
| `hash-mismatch` | Content did not match the offer's `sha256`. |
| `av-infected` | Antivirus found something. |
| `av-error` | Antivirus could not complete — **fails closed**, treated as permanent. |

Receiver algorithm:

```
if we have a transfer for id in state REQUESTED or RECEIVING:
    abort it; mark permanently rejected if the reason is in the permanent set
if the reason is permanent:
    tombstone the id so a clicked link keeps failing cleanly
    cascade the same reason to every requester we had PARKED for this id
    notify any local room or user that was waiting
```

Aborting in **both** REQUESTED and RECEIVING matters. Handling only REQUESTED means an error arriving
mid-stream tells the waiting room its file was rejected while the chunks already in flight go on to
complete and the file becomes downloadable anyway — two contradictory outcomes for one share.

Cascading to parked requesters is what makes a rejection propagate across a multi-level hub chain. No
wire change is needed: relays already forward `file-error` to its true destination, so one server
classifying a reason as permanent and every server recognising the same code is sufficient.

## Transit hops

If a `file-*` action is not addressed to you, relay it onward with the `via` trail extended, **without
decoding the payload**. Chunk text is copied verbatim. A purely relaying server never materialises the
file, regardless of its own file-federation configuration.

The untrusted-peer exposure gate applies, as it does to every routed action.

## Ingress verification

Before serving received content to a local recipient, a destination should apply defence in depth.
This implementation runs, in order:

1. **Extension allowlist** — again on ingress. A peer's filter may be absent, bypassed, or older.
2. **Magic-number sniff** (Apache Tika) against the claimed extension. An executable renamed `.jpg` is
   caught even though `.exe` was never allowlisted. An *inconclusive* sniff — plain text, unrecognised
   binary — is not treated as a mismatch.
3. **SHA-256** against the offer.
4. **Antivirus**, optional and off by default. A scan that cannot complete fails closed.

Each block produces a `file-error` with the matching permanent reason, and notifies the waiting local
room or user in-chat.

None of this applies to a transit hop.

## Availability

Storage and the download endpoint can each fail independently. When they do, the relay should report
itself **unavailable** and behave exactly as if file federation were switched off: annotate nothing on
the way out, and **rewrite no inbound share URL**.

That last part is the one worth copying. A half-built relay that keeps rewriting replaces a remote URL
that worked with a local link that will 404 for the life of the process. Leaving the original URL
alone is a far better degraded mode than breaking the link.

---

Previous: [06-direct-traffic.md](06-direct-traffic.md) · Next: [08-directory.md](08-directory.md)
