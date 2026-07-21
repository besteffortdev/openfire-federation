package com.igniterealtime.openfire.plugin.federation.files;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transparent federation of HTTP File Upload (XEP-0363) shares.
 *
 * <p>A file shared by a client is just a message whose body (and {@code jabber:x:oob} extension)
 * carries a URL on the sharer's OWN server — unreachable from clients of other federation
 * organisations, especially across multi-hop paths.  This subsystem makes those shares work
 * end-to-end with zero client changes:
 *
 * <ol>
 *   <li><b>Origin (annotate + stage):</b> when the interceptor forwards a message whose URL points
 *       at the local upload service, it attaches a {@code <fed-file id url name origin/>} annotation
 *       ({@code id} = SHA-256 of the URL) to the forwarded copies, and the content is fetched into
 *       the local {@link FileRelayStore} so it can be served to peers.</li>
 *   <li><b>Destination (rewrite + pull):</b> every server that DELIVERS the message to local users
 *       rewrites the URL to its own {@code /federation-files/<id>/<name>} endpoint (served on the
 *       HTTP-bind port clients already reach), strips the annotation, and pulls the content over the
 *       overlay: {@code file-request} toward the origin, answered by {@code file-offer} + a stream of
 *       base64 {@code file-chunk}s, all routed hop-by-hop with the usual destination/origin/via
 *       envelope.  Intermediate hops relay without storing — a transit organisation never keeps a
 *       usable copy on disk.</li>
 * </ol>
 *
 * <p>The pull model gives the privacy scoping for free: a file is transferred to exactly the servers
 * that deliver the announcing message (mapped-room peers, a 1:1 recipient's home), never broadcast.
 * Purely local traffic is never annotated, so it never leaves.  A hub that fans a mapped room out to
 * other spokes parks their requests until its own copy completes, then serves them — store-and-forward
 * along the mapping topology.  Peers running an older plugin simply ignore the annotation and show
 * the original URL (today's behaviour).
 */
public class FileRelayManager {

    private static final Logger Log = LoggerFactory.getLogger(FileRelayManager.class);

    public static final String ANNOTATION = "fed-file";

    private static final long RECEIVE_IDLE_TIMEOUT_MS = 120_000L;
    private static final long FETCH_IDLE_TIMEOUT_MS   = 180_000L;
    private static final long REQUEST_RETRY_MS        = 30_000L;
    private static final int  MAX_REQUEST_ATTEMPTS    = 3;
    private static final long FAILED_ENTRY_TTL_MS     = 600_000L;

    /** Lifecycle of content we do not (yet) have locally. Complete content lives in the store. */
    private enum State { FETCHING, REQUESTED, RECEIVING, FAILED }

    private static final class Transfer {
        final String id;
        volatile State state;
        volatile String url;              // original upload URL (origin side only)
        volatile String name = "file";
        volatile String mime = "application/octet-stream";
        volatile String origin = "";      // domain expected to hold the content
        volatile List<String> hints = List.of();
        volatile long size = -1;
        volatile String sha256 = "";
        volatile int chunkSize;
        volatile int totalChunks;
        BitSet received;                  // guarded by synchronized(this)
        RandomAccessFile out;             // guarded by synchronized(this)
        volatile long lastActivity = System.currentTimeMillis();
        volatile long lastRequestAt;
        final AtomicInteger requestAttempts = new AtomicInteger();

        Transfer(String id, State state) { this.id = id; this.state = state; }
        void touch() { lastActivity = System.currentTimeMillis(); }
    }

    /**
     * One AV scan outcome for the admin UI's "recently scanned files" table — newest first,
     * in-memory only (reset on plugin reload/restart, same as the rest of this class's state).
     * {@code verdict} is {@link ClamAvClient.Verdict#name()}.
     */
    public record ScanLogEntry(long when, String fileName, long sizeBytes, String origin,
                                String verdict, String detail) { }

    private static final int SCAN_LOG_MAX = 200;

    /**
     * One rejected file for the admin UI's "Rejected files" table — newest first, in-memory only
     * (reset on plugin reload/restart, same as {@link #scanLog}). Covers every way a file can be
     * blocked: the extension allowlist (checked at both egress and ingress), the ingress content-
     * sniff check, a SHA-256 mismatch, or a positive/failed AV scan. {@code stage} is
     * {@code "egress"} (blocked here before ever being offered to a peer) or {@code "ingress"}
     * (blocked on receipt from a peer); {@code reason} is a short machine code
     * ({@code EXTENSION_NOT_ALLOWED}, {@code CONTENT_MISMATCH}, {@code HASH_MISMATCH},
     * {@code AV_INFECTED}, {@code AV_ERROR}).
     */
    public record RejectionEntry(long when, String fileName, long sizeBytes, String origin,
                                  String stage, String reason, String detail) { }

    private static final int REJECTION_LOG_MAX = 200;

    private final FederationManager manager;
    private final FileRelayStore store = new FileRelayStore();
    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    /** id → requester domains waiting for our copy to complete (hub store-and-forward). */
    private final ConcurrentHashMap<String, Set<String>> parkedRequests = new ConcurrentHashMap<>();
    private final Deque<ScanLogEntry> scanLog = new ArrayDeque<>();
    private final Deque<RejectionEntry> rejectionLog = new ArrayDeque<>();
    private ScheduledExecutorService exec;
    private ServletContextHandler servletContext;
    private volatile SSLSocketFactory trustAllFactory;

    public FileRelayManager(FederationManager manager) {
        this.manager = manager;
    }

    public void start() {
        try {
            store.init();
        } catch (Exception e) {
            Log.error("File relay store init failed — file federation disabled: {}", e.getMessage(), e);
            return;
        }
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "federation-file-relay");
            t.setDaemon(true);
            return t;
        };
        exec = Executors.newScheduledThreadPool(2, tf);
        exec.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
        exec.scheduleWithFixedDelay(this::purge, 1, 6 * 60, TimeUnit.MINUTES);
        registerServlet();
    }

    /**
     * Applies a changed {@code files.storageDir} property: moves the store (and its complete
     * entries) to the newly-configured directory. Returns null on success, else an error message
     * (the store then keeps serving from its previous directory).
     */
    public String storageDirChanged() {
        return store.reopenIfMoved();
    }

    /** Pings the configured clamd endpoint (admin UI "Test connection" action). */
    public boolean testAvConnection() {
        return ClamAvClient.ping();
    }

    private synchronized void recordScan(String fileName, long sizeBytes, String origin,
                                          String verdict, String detail) {
        scanLog.addFirst(new ScanLogEntry(System.currentTimeMillis(), fileName, sizeBytes,
                origin == null ? "" : origin, verdict, detail == null ? "" : detail));
        if (scanLog.size() > SCAN_LOG_MAX) scanLog.removeLast();
    }

    /** Most recent AV scan outcomes, newest first (admin UI). */
    public synchronized List<ScanLogEntry> recentAvScans() {
        return new ArrayList<>(scanLog);
    }

    private synchronized void recordRejection(String fileName, long sizeBytes, String origin,
                                               String stage, String reason, String detail) {
        rejectionLog.addFirst(new RejectionEntry(System.currentTimeMillis(), fileName, sizeBytes,
                origin == null ? "" : origin, stage, reason, detail == null ? "" : detail));
        if (rejectionLog.size() > REJECTION_LOG_MAX) rejectionLog.removeLast();
    }

    /** Most recently rejected files, newest first (admin UI). */
    public synchronized List<RejectionEntry> recentRejections() {
        return new ArrayList<>(rejectionLog);
    }

    public void stop() {
        if (exec != null) exec.shutdownNow();
        unregisterServlet();
        for (Transfer t : transfers.values()) {
            synchronized (t) { closeQuietly(t); }
        }
        transfers.clear();
        parkedRequests.clear();
    }

    private void registerServlet() {
        try {
            servletContext = new ServletContextHandler();
            servletContext.setContextPath("/federation-files");
            servletContext.addServlet(new ServletHolder(new FederationFileServlet(this)), "/*");
            HttpBindManager.getInstance().addJettyHandler(servletContext);
            Log.info("File relay download endpoint registered at /federation-files on the HTTP-bind port");
        } catch (Exception e) {
            servletContext = null;
            Log.error("Could not register file relay servlet — downloads will 404: {}", e.getMessage(), e);
        }
    }

    private void unregisterServlet() {
        if (servletContext != null) {
            try {
                HttpBindManager.getInstance().removeJettyHandler(servletContext);
            } catch (Exception e) {
                Log.debug("Could not remove file relay servlet: {}", e.getMessage());
            }
            servletContext = null;
        }
    }

    private boolean enabled() { return FederationProperties.FILES_ENABLED.getValue(); }

    private String localDomain() {
        return XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    }

    private long maxSizeBytes() {
        return FederationProperties.FILES_MAX_MB.getValue() * 1024L * 1024L;
    }

    // ── Origin side: detect a local upload share and stage its content ─────────

    /**
     * Returns the {@code <fed-file/>} annotation for an outbound federated message, or null when the
     * message does not carry a URL on the local HTTP upload service (or file federation is off).
     * As a side effect the content is staged into the local store (async) so peers can pull it.
     * The returned element is unattached — callers add a copy to each forwarded stanza.
     */
    public Element annotationForOutbound(Message msg) {
        if (!enabled()) return null;
        Element existing = annotationOf(msg.getElement());
        if (existing != null) return existing.createCopy();
        String url = extractLocalUploadUrl(msg);
        if (url == null) return null;
        String id = sha256Hex(url.getBytes(StandardCharsets.UTF_8));
        String name = fileNameFromUrl(url);
        ensureLocalContent(id, url, name);
        Element ann = DocumentHelper.createElement(
                QName.get(ANNOTATION, Namespace.get(FederationStanzaFactory.NS)));
        ann.addAttribute("id",     id);
        ann.addAttribute("url",    url);
        ann.addAttribute("name",   name);
        ann.addAttribute("origin", localDomain());
        return ann;
    }

    /** In-place variant for relay paths that forward the message object itself (1:1 legs). */
    public void annotateOutboundInPlace(Message msg) {
        if (!enabled() || annotationOf(msg.getElement()) != null) return;
        Element ann = annotationForOutbound(msg);
        if (ann != null) msg.getElement().add(ann);
    }

    /** The message's {@code fed-file} annotation in our namespace, or null. */
    public Element annotationOf(Element messageEl) {
        Element ann = messageEl.element(ANNOTATION);
        return (ann != null && FederationStanzaFactory.NS.equals(ann.getNamespaceURI())) ? ann : null;
    }

    /**
     * The single upload URL a share message carries: the {@code jabber:x:oob} extension's
     * {@code <url>} when present, else a body that is exactly one http(s) URL token.  Returns it
     * only when it points at THIS server's upload service (host is our XMPP domain / hostname /
     * a configured extra host, path contains the upload marker) — anything else is not ours to relay.
     */
    private String extractLocalUploadUrl(Message msg) {
        String candidate = null;
        for (Element x : msg.getElement().elements("x")) {
            if ("jabber:x:oob".equals(x.getNamespaceURI())) {
                Element u = x.element("url");
                if (u != null && !u.getTextTrim().isEmpty()) candidate = u.getTextTrim();
            }
        }
        if (candidate == null) {
            String body = msg.getBody();
            if (body != null) {
                String trimmed = body.strip();
                if ((trimmed.startsWith("https://") || trimmed.startsWith("http://"))
                        && trimmed.indexOf(' ') < 0 && trimmed.indexOf('\n') < 0) {
                    candidate = trimmed;
                }
            }
        }
        return (candidate != null && isLocalUploadUrl(candidate)) ? candidate : null;
    }

    private boolean isLocalUploadUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        String host = uri.getHost();
        if (host == null) return false;
        String marker = FederationProperties.FILES_UPLOAD_PATH_MARKER.getValue();
        if (marker != null && !marker.isBlank()
                && (uri.getPath() == null || !uri.getPath().contains(marker.strip()))) {
            return false;
        }
        if (host.equalsIgnoreCase(localDomain())) return true;
        try {
            if (host.equalsIgnoreCase(XMPPServer.getInstance().getServerInfo().getHostname())) return true;
        } catch (Exception ignored) { }
        String extra = FederationProperties.FILES_EXTRA_LOCAL_HOSTS.getValue();
        if (extra != null && !extra.isBlank()) {
            for (String h : extra.split(",")) {
                if (host.equalsIgnoreCase(h.strip())) return true;
            }
        }
        return false;
    }

    /** Stages the content of a locally-uploaded file into the relay store (no-op when present). */
    private void ensureLocalContent(String id, String url, String name) {
        if (store.has(id)) return;
        Transfer t = transfers.compute(id, (k, cur) -> {
            if (cur != null && cur.state != State.FAILED) return cur;      // fetch/receive in flight
            Transfer nt = new Transfer(k, State.FETCHING);
            nt.url = url;
            nt.name = name;
            nt.origin = localDomain();
            return nt;
        });
        if (t.state == State.FETCHING && t.url != null && exec != null) {
            exec.execute(() -> fetchLocalUpload(t));
        }
    }

    /** Downloads the upload-service URL (loopback, self-signed certs accepted) into the store. */
    private void fetchLocalUpload(Transfer t) {
        if (store.has(t.id)) { transfers.remove(t.id, t); return; }
        if (!FileTypePolicy.isExtensionAllowed(t.name)) {
            Log.warn("File relay: refusing to stage '{}' for outbound relay — extension not on "
                   + "the allowed list ({})", t.name, FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
            recordRejection(t.name, 0, t.origin, "egress", "EXTENSION_NOT_ALLOWED",
                    "not on allowed list: " + FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
            t.state = State.FAILED;
            t.touch();
            failParked(t.id, "policy-rejected");
            return;
        }
        String sha256;
        long total = 0;
        String mime = "application/octet-stream";
        try {
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(t.url).openConnection();
            if (conn instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustAllFactory());
                https.setHostnameVerifier(trustAllHostnames());
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "openfire-federation-file-relay");
            int status = conn.getResponseCode();
            if (status != 200) throw new java.io.IOException("HTTP " + status);
            String ct = conn.getContentType();
            if (ct != null && !ct.isBlank()) {
                int semi = ct.indexOf(';');
                mime = (semi > 0 ? ct.substring(0, semi) : ct).strip();
            }
            long cap = maxSizeBytes();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(store.partPath(t.id))) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > cap) throw new java.io.IOException("exceeds size cap (" + cap + " bytes)");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                    t.touch();
                }
            }
            sha256 = toHex(digest.digest());
        } catch (Exception e) {
            Log.warn("File relay: could not stage local upload {} ({}): {}", t.name, t.url, e.getMessage());
            store.deletePart(t.id);
            t.state = State.FAILED;
            t.touch();
            failParked(t.id, "stage-failed");
            return;
        }
        try {
            store.finalizePart(t.id, t.name, mime, total, sha256);
            transfers.remove(t.id, t);
            Log.info("File relay: staged local upload {} ({} bytes) as {}", t.name, total, t.id);
            serveParked(t.id);
        } catch (Exception e) {
            Log.warn("File relay: could not finalize staged upload {}: {}", t.id, e.getMessage());
            t.state = State.FAILED;
            failParked(t.id, "stage-failed");
        }
    }

    // ── Destination side: rewrite the share and pull the content ───────────────

    /**
     * If {@code msgEl} carries a {@code fed-file} annotation, returns a copy with the annotation
     * stripped and the upload URL rewritten to this server's own download endpoint, registering the
     * expected content (which triggers the overlay pull).  Returns null when there is nothing to do —
     * callers keep using the original element (which fan-out/relay paths must, so the annotation
     * survives toward other servers).
     */
    public Element rewriteForDelivery(Element msgEl, String... hints) {
        if (!enabled() || annotationOf(msgEl) == null) return null;
        Element copy = msgEl.createCopy();
        rewriteElement(copy, hints);
        return copy;
    }

    /** In-place variant for delivery paths that own their message object. */
    public void rewriteInPlace(Message msg, String... hints) {
        if (!enabled() || annotationOf(msg.getElement()) == null) return;
        rewriteElement(msg.getElement(), hints);
    }

    private void rewriteElement(Element messageEl, String... hints) {
        Element ann = annotationOf(messageEl);
        if (ann == null) return;
        String id     = ann.attributeValue("id");
        String url    = ann.attributeValue("url");
        String name   = sanitizeFileName(ann.attributeValue("name", "file"));
        String origin = ann.attributeValue("origin", "");
        messageEl.remove(ann);
        if (id == null || !isHexId(id) || url == null || url.isBlank()) return;
        if (origin.equals(localDomain())) return;   // our own share echoed back — original URL is right

        registerExpected(id, name, origin, hints);

        String newUrl = publicUrlFor(id, name);
        for (Element body : messageEl.elements("body")) {
            String text = body.getText();
            if (text != null && text.contains(url)) {
                body.setText(text.replace(url, newUrl));
            }
        }
        for (Element x : messageEl.elements("x")) {
            if ("jabber:x:oob".equals(x.getNamespaceURI())) {
                Element u = x.element("url");
                if (u != null && u.getTextTrim().equals(url)) {
                    u.setText(newUrl);
                }
            }
        }
    }

    /** Records that {@code id} should exist here and starts (or retries) the overlay pull. */
    private void registerExpected(String id, String name, String origin, String... hints) {
        if (store.has(id)) return;
        List<String> hintList = new ArrayList<>();
        for (String h : hints) {
            if (h != null && !h.isBlank() && !h.equals(localDomain())) hintList.add(h);
        }
        Transfer t = transfers.compute(id, (k, cur) -> {
            if (cur != null && cur.state != State.FAILED) return cur;
            Transfer nt = new Transfer(k, State.REQUESTED);
            nt.name = name;
            nt.origin = origin;
            nt.hints = List.copyOf(hintList);
            return nt;
        });
        synchronized (t) {
            if (t.state == State.REQUESTED && t.lastRequestAt == 0) {
                sendRequest(t);
            }
        }
    }

    /** Sends (or re-sends) the file-request toward the first routable holder candidate. */
    private void sendRequest(Transfer t) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (t.origin != null && !t.origin.isBlank()) candidates.add(t.origin);
        candidates.addAll(t.hints);
        candidates.remove(localDomain());
        for (String target : candidates) {
            var nextHop = manager.getRoutingTable().findNextHop(target);
            if (nextHop.isEmpty()) continue;
            try {
                XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.fileRequest(nextHop.get(), target, localDomain(), t.id, ""));
                t.lastRequestAt = System.currentTimeMillis();
                t.requestAttempts.incrementAndGet();
                t.touch();
                Log.debug("File relay: requested {} from {} via {} (attempt {})",
                          t.id, target, nextHop.get(), t.requestAttempts.get());
                return;
            } catch (Exception e) {
                Log.warn("File relay: could not send file-request for {} toward {}: {}",
                         t.id, target, e.getMessage());
            }
        }
        Log.warn("File relay: no routable holder for {} (origin={}, hints={}) — marking failed",
                 t.id, t.origin, t.hints);
        t.state = State.FAILED;
        t.touch();
    }

    // ── Overlay protocol: request / offer / chunk / error ──────────────────────

    /** Relays a file-* element toward its destination (we are a transit hop). */
    public void relayToward(String elementName, Element el, String fromDomain) {
        String destination = el.attributeValue("destination");
        String via         = el.attributeValue("via", "");
        String local       = localDomain();
        if (FederationStanzaFactory.viaContains(via, local)) {
            Log.warn("{} loop detected (via={}), dropping", elementName, via);
            return;
        }
        String newVia = via.isEmpty() ? local : via + "," + local;
        manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
            nextHop -> {
                try {
                    XMPPServer.getInstance().getPacketRouter()
                              .route(FederationStanzaFactory.fileRelay(nextHop, el, newVia));
                } catch (Exception e) {
                    Log.warn("Could not relay {} toward {}: {}", elementName, destination, e.getMessage());
                }
            },
            () -> Log.debug("{}: no route to {}, dropping", elementName, destination));
    }

    /** A peer asks for content: serve it, park the request while our own copy is in flight, or refuse. */
    public void handleFileRequest(String fromDomain, Element el) {
        if (!enabled()) return;
        String id        = el.attributeValue("id");
        String requester = el.attributeValue("origin");
        if (id == null || !isHexId(id) || requester == null || requester.isBlank()
                || requester.equals(localDomain())) {
            return;
        }
        if (store.has(id)) {
            startSend(id, requester);
            return;
        }
        Transfer t = transfers.get(id);
        if (t != null && t.state != State.FAILED) {
            // Our copy is still staging/arriving (hub fan-out): serve this peer when it lands.
            parkedRequests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(requester);
            Log.debug("File relay: parked request for {} from {} (local copy {})", id, requester, t.state);
            return;
        }
        if (t != null && t.url != null) {
            // A staging attempt of OUR OWN upload failed earlier (upload service momentarily down?)
            // but the content may well still exist — re-stage and serve the peer when it lands.
            parkedRequests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(requester);
            ensureLocalContent(id, t.url, t.name);
            return;
        }
        sendError(requester, id, "not-found");
    }

    /** A holder announces an inbound transfer we asked for — set up chunk assembly. */
    public void handleFileOffer(String fromDomain, Element el) {
        String id = el.attributeValue("id");
        if (id == null || store.has(id)) return;
        Transfer t = transfers.get(id);
        if (t == null) {
            Log.debug("File relay: unsolicited file-offer for {} from {} — ignoring", id, fromDomain);
            return;
        }
        long size;
        int chunkSize, totalChunks;
        try {
            size        = Long.parseLong(el.attributeValue("size", "-1"));
            chunkSize   = Integer.parseInt(el.attributeValue("chunkSize", "0"));
            totalChunks = Integer.parseInt(el.attributeValue("totalChunks", "-1"));
        } catch (NumberFormatException e) {
            return;
        }
        long cap = maxSizeBytes();
        if (size < 0 || size > cap || chunkSize < 1 || chunkSize > 1024 * 1024
                || totalChunks < 0 || totalChunks != (int) ((size + chunkSize - 1) / chunkSize)) {
            Log.warn("File relay: rejecting file-offer for {} from {} — implausible geometry "
                   + "(size={}, chunkSize={}, totalChunks={}, cap={})",
                     id, fromDomain, size, chunkSize, totalChunks, cap);
            synchronized (t) { failTransfer(t); }
            return;
        }
        synchronized (t) {
            if (t.state == State.RECEIVING) return;   // duplicate offer
            String name = el.attributeValue("name");
            if (name != null && !name.isBlank()) t.name = sanitizeFileName(name);
            String mime = el.attributeValue("mime");
            if (mime != null && !mime.isBlank()) t.mime = mime.strip();
            t.sha256      = el.attributeValue("sha256", "");
            t.size        = size;
            t.chunkSize   = chunkSize;
            t.totalChunks = totalChunks;
            try {
                t.out = new RandomAccessFile(store.partPath(id).toFile(), "rw");
                t.out.setLength(0);
            } catch (Exception e) {
                Log.warn("File relay: cannot open part file for {}: {}", id, e.getMessage());
                failTransfer(t);
                return;
            }
            t.received = new BitSet(Math.max(totalChunks, 1));
            t.state = State.RECEIVING;
            t.touch();
            if (totalChunks == 0) finalizeReceive(t);   // empty file
        }
    }

    /** One base64 slice of an inbound transfer; assembly is offset-addressed so order is irrelevant. */
    public void handleFileChunk(String fromDomain, Element el) {
        String id = el.attributeValue("id");
        if (id == null) return;
        Transfer t = transfers.get(id);
        if (t == null || t.state != State.RECEIVING) return;
        int seq;
        try {
            seq = Integer.parseInt(el.attributeValue("seq", "-1"));
        } catch (NumberFormatException e) {
            return;
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(el.getText());
        } catch (IllegalArgumentException e) {
            return;
        }
        synchronized (t) {
            if (t.state != State.RECEIVING || seq < 0 || seq >= t.totalChunks || t.received.get(seq)) return;
            long expected = (seq == t.totalChunks - 1)
                    ? t.size - (long) seq * t.chunkSize
                    : t.chunkSize;
            if (data.length != expected) {
                Log.warn("File relay: chunk {}/{} for {} has {} bytes, expected {} — aborting",
                         seq, t.totalChunks, id, data.length, expected);
                failTransfer(t);
                return;
            }
            try {
                t.out.seek((long) seq * t.chunkSize);
                t.out.write(data);
            } catch (Exception e) {
                Log.warn("File relay: write failed for {}: {}", id, e.getMessage());
                failTransfer(t);
                return;
            }
            t.received.set(seq);
            t.touch();
            if (t.received.cardinality() == t.totalChunks) finalizeReceive(t);
        }
    }

    /** The holder refused (usually not-found): stop waiting so the servlet can 404 quickly. */
    public void handleFileError(String fromDomain, Element el) {
        String id = el.attributeValue("id");
        if (id == null) return;
        Transfer t = transfers.get(id);
        if (t != null && t.state == State.REQUESTED) {
            Log.info("File relay: holder refused {} ({})", id, el.attributeValue("reason", "unspecified"));
            t.state = State.FAILED;
            t.touch();
        }
    }

    /** Must be called with the transfer lock held. */
    private void finalizeReceive(Transfer t) {
        closeQuietly(t);
        try {
            if (t.sha256 != null && !t.sha256.isBlank()) {
                String actual = sha256OfFile(store.partPath(t.id));
                if (!t.sha256.equalsIgnoreCase(actual)) {
                    Log.warn("File relay: hash mismatch for {} — expected {}, got {}; discarding",
                             t.id, t.sha256, actual);
                    recordRejection(t.name, t.size, t.origin, "ingress", "HASH_MISMATCH",
                            "expected " + t.sha256 + ", got " + actual);
                    failTransfer(t);
                    return;
                }
            }
            // Ingress gates — apply only here, at the actual point of delivery to a local
            // recipient. Never reached by a pure transit hop (relayToward never decodes content).
            if (!FileTypePolicy.isExtensionAllowed(t.name)) {
                Log.warn("File relay: rejecting received file '{}' — extension not on the "
                       + "allowed list ({})", t.name, FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
                recordRejection(t.name, t.size, t.origin, "ingress", "EXTENSION_NOT_ALLOWED",
                        "not on allowed list: " + FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
                failTransfer(t);
                return;
            }
            FileTypePolicy.ContentCheck contentCheck = FileTypePolicy.checkContent(store.partPath(t.id), t.name);
            if (!contentCheck.ok()) {
                recordRejection(t.name, t.size, t.origin, "ingress", "CONTENT_MISMATCH",
                        contentCheck.detectedMime() == null ? "unreadable"
                                : "sniffs as '" + contentCheck.detectedMime() + "'");
                failTransfer(t);   // checkContent already logged the specific mismatch
                return;
            }
            if (FederationProperties.FILES_AV_ENABLED.getValue()) {
                ClamAvClient.ScanResult scan = ClamAvClient.scan(store.partPath(t.id));
                recordScan(t.name, t.size, t.origin, scan.verdict().name(), scan.detail());
                switch (scan.verdict()) {
                    case INFECTED -> {
                        Log.warn("File relay: AV detected '{}' in received file {} — discarding",
                                 scan.detail(), t.name);
                        recordRejection(t.name, t.size, t.origin, "ingress", "AV_INFECTED", scan.detail());
                    }
                    case ERROR    -> {
                        Log.warn("File relay: AV scan unavailable for {} ({}) — "
                               + "discarding (fails closed)", t.name, scan.detail());
                        recordRejection(t.name, t.size, t.origin, "ingress", "AV_ERROR", scan.detail());
                    }
                    case CLEAN    -> { }
                }
                if (scan.verdict() != ClamAvClient.Verdict.CLEAN) {
                    failTransfer(t);
                    return;
                }
            }
            store.finalizePart(t.id, t.name, t.mime, t.size, t.sha256);
            transfers.remove(t.id, t);
            Log.info("File relay: received {} ({} bytes) as {}", t.name, t.size, t.id);
        } catch (Exception e) {
            Log.warn("File relay: could not finalize {}: {}", t.id, e.getMessage());
            failTransfer(t);
            return;
        }
        serveParked(t.id);
    }

    /** Must be called with the transfer lock held (or before the transfer is shared). */
    private void failTransfer(Transfer t) {
        closeQuietly(t);
        store.deletePart(t.id);
        t.state = State.FAILED;
        t.touch();
    }

    private void closeQuietly(Transfer t) {
        if (t.out != null) {
            try { t.out.close(); } catch (Exception ignored) { }
            t.out = null;
        }
    }

    // ── Serving content to a peer ──────────────────────────────────────────────

    /** Streams a stored file to {@code requester} as file-offer + file-chunk IQs (async, throttled). */
    private void startSend(String id, String requester) {
        if (exec == null) return;
        exec.execute(() -> {
            FileRelayStore.StoredFile sf = store.get(id);
            if (sf == null) {
                sendError(requester, id, "not-found");
                return;
            }
            int chunkSize = Math.max(16 * 1024, FederationProperties.FILES_CHUNK_BYTES.getValue());
            int totalChunks = (int) ((sf.size() + chunkSize - 1) / chunkSize);
            int delayMs = Math.max(0, FederationProperties.FILES_CHUNK_DELAY_MS.getValue());
            String local = localDomain();
            var hop = manager.getRoutingTable().findNextHop(requester);
            if (hop.isEmpty()) {
                Log.debug("File relay: no route to requester {} for {} — dropping", requester, id);
                return;
            }
            try {
                XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.fileOffer(hop.get(), requester, local, id,
                                sf.name(), sf.mime(), sf.size(), sf.sha256(), chunkSize, totalChunks, ""));
                try (InputStream in = Files.newInputStream(store.contentPath(id))) {
                    byte[] buf = new byte[chunkSize];
                    for (int seq = 0; seq < totalChunks; seq++) {
                        int read = 0;
                        while (read < chunkSize) {
                            int n = in.read(buf, read, chunkSize - read);
                            if (n < 0) break;
                            read += n;
                        }
                        var seqHop = manager.getRoutingTable().findNextHop(requester);
                        if (seqHop.isEmpty()) {
                            Log.warn("File relay: lost route to {} mid-transfer of {} — aborting", requester, id);
                            return;
                        }
                        String b64 = Base64.getEncoder().encodeToString(
                                read == buf.length ? buf : java.util.Arrays.copyOf(buf, read));
                        XMPPServer.getInstance().getPacketRouter().route(
                                FederationStanzaFactory.fileChunk(seqHop.get(), requester, local, id, seq, b64, ""));
                        if (delayMs > 0) Thread.sleep(delayMs);
                    }
                }
                Log.info("File relay: served {} ({} bytes, {} chunk(s)) to {}",
                         sf.name(), sf.size(), totalChunks, requester);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.warn("File relay: send of {} to {} failed: {}", id, requester, e.getMessage());
            }
        });
    }

    private void sendError(String requester, String id, String reason) {
        manager.getRoutingTable().findNextHop(requester).ifPresent(nextHop -> {
            try {
                XMPPServer.getInstance().getPacketRouter().route(
                        FederationStanzaFactory.fileError(nextHop, requester, localDomain(), id, reason, ""));
            } catch (Exception e) {
                Log.debug("File relay: could not send file-error to {}: {}", requester, e.getMessage());
            }
        });
    }

    private void serveParked(String id) {
        Set<String> waiting = parkedRequests.remove(id);
        if (waiting != null) {
            for (String requester : waiting) startSend(id, requester);
        }
    }

    private void failParked(String id, String reason) {
        Set<String> waiting = parkedRequests.remove(id);
        if (waiting != null) {
            for (String requester : waiting) sendError(requester, id, reason);
        }
    }

    // ── Servlet support ────────────────────────────────────────────────────────

    public enum ServeState { OK, PENDING, NOT_FOUND }
    public record ServeInfo(ServeState state, FileRelayStore.StoredFile file, Path path) {}

    /** Resolves an id for the download servlet; a failed pull is retried (throttled) on access. */
    public ServeInfo lookup(String id) {
        if (!isHexId(id)) return new ServeInfo(ServeState.NOT_FOUND, null, null);
        FileRelayStore.StoredFile sf = store.get(id);
        if (sf != null) return new ServeInfo(ServeState.OK, sf, store.contentPath(id));
        Transfer t = transfers.get(id);
        if (t == null) return new ServeInfo(ServeState.NOT_FOUND, null, null);
        if (t.state == State.FAILED
                && System.currentTimeMillis() - t.lastRequestAt > REQUEST_RETRY_MS) {
            // A user clicked a link whose pull failed (origin was down, path was broken): give the
            // pull another life instead of a permanent dead link.
            if (t.url != null && t.origin.equals(localDomain())) {
                ensureLocalContent(id, t.url, t.name);
            } else if (!t.origin.isBlank()) {
                synchronized (t) {
                    t.state = State.REQUESTED;
                    t.requestAttempts.set(0);
                    sendRequest(t);
                }
            }
        }
        return new ServeInfo(ServeState.PENDING, null, null);
    }

    // ── Maintenance ────────────────────────────────────────────────────────────

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Transfer t : transfers.values()) {
            switch (t.state) {
                case RECEIVING -> {
                    if (now - t.lastActivity > RECEIVE_IDLE_TIMEOUT_MS) {
                        Log.warn("File relay: inbound transfer {} stalled — aborting", t.id);
                        synchronized (t) { failTransfer(t); }
                    }
                }
                case FETCHING -> {
                    if (now - t.lastActivity > FETCH_IDLE_TIMEOUT_MS) {
                        t.state = State.FAILED;
                        t.touch();
                    }
                }
                case REQUESTED -> {
                    if (now - t.lastRequestAt > REQUEST_RETRY_MS) {
                        if (t.requestAttempts.get() >= MAX_REQUEST_ATTEMPTS) {
                            Log.warn("File relay: no answer for {} after {} request(s) — marking failed "
                                   + "(peer without file support?)", t.id, t.requestAttempts.get());
                            t.state = State.FAILED;
                            t.touch();
                        } else {
                            sendRequest(t);
                        }
                    }
                }
                case FAILED -> {
                    if (now - t.lastActivity > FAILED_ENTRY_TTL_MS) {
                        transfers.remove(t.id, t);
                    }
                }
            }
        }
        parkedRequests.keySet().removeIf(id -> !store.has(id) && !transfers.containsKey(id));
    }

    private void purge() {
        try {
            long maxAge = FederationProperties.FILES_RETENTION_DAYS.getValue() * 24L * 3600_000L;
            int removed = store.purgeOlderThan(maxAge);
            if (removed > 0) Log.info("File relay: purged {} expired file(s)", removed);
        } catch (Exception e) {
            Log.warn("File relay purge failed: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String publicUrlFor(String id, String name) {
        String base = FederationProperties.FILES_PUBLIC_URL.getValue();
        if (base == null || base.isBlank()) {
            base = "https://" + localDomain() + ":" + HttpBindManager.HTTP_BIND_SECURE_PORT.getValue()
                 + "/federation-files";
        }
        base = base.strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + id + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String fileNameFromUrl(String url) {
        try {
            String path = new URI(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                String last = slash >= 0 ? path.substring(slash + 1) : path;
                String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8);
                String clean = sanitizeFileName(decoded);
                if (!clean.isBlank()) return clean;
            }
        } catch (Exception ignored) { }
        return "file";
    }

    static String sanitizeFileName(String name) {
        if (name == null) return "file";
        String clean = name.replaceAll("[\\\\/\\p{Cntrl}\"']", "").strip();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "file";
        return clean.length() > 200 ? clean.substring(clean.length() - 200) : clean;
    }

    private static boolean isHexId(String id) {
        if (id == null || id.length() != 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256OfFile(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /**
     * The staging fetch talks to OUR OWN upload service, which in the lab runs on a self-signed
     * cert — a permissive trust manager is acceptable because the URL was already validated as
     * pointing at this server, and content integrity is re-hashed for the relay anyway.
     */
    private SSLSocketFactory trustAllFactory() throws Exception {
        SSLSocketFactory f = trustAllFactory;
        if (f == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new java.security.SecureRandom());
            trustAllFactory = f = ctx.getSocketFactory();
        }
        return f;
    }

    private HostnameVerifier trustAllHostnames() {
        return (hostname, session) -> true;
    }
}
