package com.igniterealtime.openfire.plugin.federation.files;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.LogSafe;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.http.HttpBindManager;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * Relays HTTP File Upload (XEP-0363) shares across the federation without client changes. The
 * origin annotates a forwarded share and stages its content; each server that delivers it rewrites
 * the URL to its own download endpoint and pulls the content over the overlay, scanning it on
 * arrival. Transit servers relay the chunks without storing them. See docs/file-federation.md.
 */
public class FileRelayManager {

    private static final Logger Log = LoggerFactory.getLogger(FileRelayManager.class);

    public static final String ANNOTATION = "fed-file";

    private static final long RECEIVE_IDLE_TIMEOUT_MS = 120_000L;
    private static final long FETCH_IDLE_TIMEOUT_MS   = 180_000L;
    private static final long REQUEST_RETRY_MS        = 30_000L;
    private static final int  MAX_REQUEST_ATTEMPTS    = 3;
    private static final long FAILED_ENTRY_TTL_MS     = 600_000L;

    /** Workers for staging fetches and outbound streams, kept apart from the maintenance sweep that times them out. */
    private static final int  IO_WORKERS              = 4;
    /** How long {@link #stop()} waits for the workers to finish. */
    private static final long WORKER_SHUTDOWN_MS      = 5_000L;
    /** Fixed part of a staging fetch's absolute time budget; socket timeouts alone allow a trickle forever. */
    private static final long STAGE_BASE_BUDGET_MS    = 60_000L;
    /** Assumed minimum throughput used to size the rest of the staging budget. */
    private static final long STAGE_FLOOR_BYTES_PER_S = 256L * 1024L;
    /** Bytes of randomness in a share capability token. */
    private static final int  SHARE_TOKEN_BYTES       = 16;
    /** Concurrent outbound sends across all peers; a further request is refused, not queued. */
    private static final int  MAX_INFLIGHT_SENDS      = 8;
    /** Most transfers tracked at once, so a flood of distinct file links cannot grow the map or open files. */
    private static final int  MAX_TRANSFERS           = 512;
    private volatile long transferCapLoggedAt = 0;
    /** Chunk sizes both ends accept: a sender clamps to them and a receiver rejects anything outside. */
    private static final int  MIN_CHUNK_BYTES         = 16 * 1024;
    private static final int  MAX_CHUNK_BYTES         = 1024 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Lifecycle of content we do not have yet; complete content lives in the store. */
    private enum State { FETCHING, REQUESTED, RECEIVING, FAILED }

    /** One file being staged or pulled. {@code received} and {@code out} are guarded by the instance lock. */
    private static final class Transfer {
        final String id;
        volatile State state;
        /** The original upload URL; set on the origin side only. */
        volatile String url;
        volatile String name = "file";
        volatile String mime = "application/octet-stream";
        /** The server expected to hold the content. */
        volatile String origin = "";
        /** The neighbour the request went through; responses are accepted only from that direction. */
        volatile String expectedFrom = "";
        /** The share capability: minted here at the origin, otherwise read from the annotation. */
        volatile String token = "";
        volatile List<String> hints = List.of();
        volatile long size = -1;
        volatile String sha256 = "";
        /** Set for a policy or security rejection, which the same bytes would fail again, so it is never retried. */
        volatile boolean rejected = false;
        volatile int chunkSize;
        volatile int totalChunks;
        BitSet received;
        RandomAccessFile out;
        volatile long lastActivity = System.currentTimeMillis();
        volatile long lastRequestAt;
        final AtomicInteger requestAttempts = new AtomicInteger();

        Transfer(String id, State state) { this.id = id; this.state = state; }
        void touch() { lastActivity = System.currentTimeMillis(); }
    }

    /** Which end of a relay a scan or rejection happened at; a relayed file is checked at both. */
    public enum RelayStage {
        /** Checked here before the file was offered to a peer. */
        EGRESS,
        /** Checked on arrival from a peer, before delivery to a local recipient. */
        INGRESS;

        /** The lowercase token used in the admin JSON and the activity logs. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Decodes a {@link #token()}; anything else, including old rows without a stage, is INGRESS. */
        static RelayStage fromToken(String token) {
            return EGRESS.token().equals(token) ? EGRESS : INGRESS;
        }
    }

    /**
     * Why a file was blocked: extension allowlist, content sniffing, hash check or AV scan. Each
     * carries the {@code file-error} reason it normally puts on the wire.
     */
    public enum RejectionReason {
        EXTENSION_NOT_ALLOWED("extension-not-allowed"),
        CONTENT_MISMATCH("content-mismatch"),
        HASH_MISMATCH("hash-mismatch"),
        AV_INFECTED("av-infected"),
        AV_ERROR("av-error");

        private final String wireReason;

        RejectionReason(String wireReason) {
            this.wireReason = wireReason;
        }

        /** The {@code file-error} reason code peers see for this class of rejection. */
        public String wireReason() {
            return wireReason;
        }

        /** Decodes a persisted reason; null for one this build does not know. */
        static RejectionReason fromName(String name) {
            for (RejectionReason r : values()) {
                if (r.name().equals(name)) return r;
            }
            return null;
        }
    }

    /** One AV scan outcome for the admin console; {@code verdict} is a {@code ClamAvClient.Verdict} name. */
    public record ScanLogEntry(long when, String fileName, long sizeBytes, String origin,
                                RelayStage stage, String verdict, String detail) { }

    /** Copy buffer for staging and hashing files. */
    private static final int IO_BUFFER_BYTES = 64 * 1024;

    /** Scan entries kept in memory for the admin console; the log file keeps the full history. */
    private static final int SCAN_LOG_MAX = 200;

    /** One rejected file for the admin console. */
    public record RejectionEntry(long when, String fileName, long sizeBytes, String origin,
                                  RelayStage stage, RejectionReason reason, String detail) { }

    private static final int REJECTION_LOG_MAX = 200;

    /**
     * {@code file-error} reasons that mean a permanent rejection, which the same bytes would hit
     * again; any other reason is transient and retried. {@code policy-rejected} is the egress code.
     */
    private static final Set<String> PERMANENT_REJECT_REASONS =
            Stream.concat(Stream.of("policy-rejected"),
                          Arrays.stream(RejectionReason.values()).map(RejectionReason::wireReason))
                  .collect(Collectors.toUnmodifiableSet());

    /** A local room or user waiting on a pulled file, to be told if the file is rejected. */
    private record LocalDest(String jid, boolean room) { }

    /** How long a rejection is remembered, so a clicked link keeps answering 403. */
    private static final long REJECTION_TOMBSTONE_TTL_MS = 24 * 3600_000L;
    /** Most rejections remembered, so a flood of rejected ids cannot grow the map. */
    private static final int REJECTION_TOMBSTONE_MAX = 10_000;

    private final FederationManager manager;
    private final FileRelayStore store = new FileRelayStore();
    private final ConcurrentHashMap<String, Transfer> transfers = new ConcurrentHashMap<>();
    /** Transfer id to the requesters waiting for our own copy to complete. */
    private final ConcurrentHashMap<String, Set<String>> parkedRequests = new ConcurrentHashMap<>();
    /** Transfer id to the local rooms and users to notify if it is rejected. */
    private final ConcurrentHashMap<String, Set<LocalDest>> localDestinations = new ConcurrentHashMap<>();
    /** Transfer id to the time of its rejection, kept after the transfer is swept so lookups still answer REJECTED. */
    private final ConcurrentHashMap<String, Long> rejectionTombstones = new ConcurrentHashMap<>();
    private final Deque<ScanLogEntry> scanLog = new ArrayDeque<>();
    private final Deque<RejectionEntry> rejectionLog = new ArrayDeque<>();
    /** On-disk logs behind the two tables above; {@link #loadPersistedLogs()} decodes these columns by position. */
    private final FileActivityLog scanFileLog = new FileActivityLog("federation-file-scans.log",
            "#time\tfile\tsizeBytes\tpeer\tstage\tverdict\tdetail");
    private final FileActivityLog rejectionFileLog = new FileActivityLog("federation-file-rejections.log",
            "#time\tfile\tsizeBytes\tpeer\tstage\treason\tdetail");
    /** Runs the sweep and purge only, so it is never blocked behind a stalled transfer. */
    private ScheduledExecutorService maintenance;
    /** Staging fetches and outbound chunk streams. */
    private ExecutorService io;
    private ServletContextHandler servletContext;
    private volatile SSLSocketFactory trustAllFactory;
    /**
     * Whether the store is open, the workers run and the download endpoint is mounted. When false,
     * shares are neither annotated nor rewritten, so they keep their original URLs.
     */
    private volatile boolean available;
    /** Open staging connections, so {@link #stop()} can break a blocked socket read. */
    private final Set<HttpURLConnection> activeFetches = ConcurrentHashMap.newKeySet();
    /** {@code requester|id} of the sends in progress, which bounds them and collapses duplicate requests. */
    private final Set<String> inFlightSends = ConcurrentHashMap.newKeySet();

    public FileRelayManager(FederationManager manager) {
        this.manager = manager;
    }

    /**
     * Starts the relay. A failed step leaves it unavailable rather than half-built; the workers
     * start anyway so {@link #storageDirChanged()} can recover without a restart.
     */
    public void start() {
        startWorkers();
        try {
            store.init();
        } catch (IOException | RuntimeException e) {
            Log.error("File relay store init failed — file federation is UNAVAILABLE (shares keep "
                    + "their original URLs). Fix files.storageDir and save it to retry", e);
            return;
        }
        try {
            loadPersistedLogs();
        } catch (RuntimeException e) {
            // The activity log is optional; a damaged file must not stop the relay.
            Log.warn("Could not restore file relay activity logs (starting with empty tables)", e);
        }
        if (!registerServlet()) return;
        available = true;
    }

    private void startWorkers() {
        maintenance = Executors.newSingleThreadScheduledExecutor(daemonFactory("federation-file-maintenance"));
        io = Executors.newFixedThreadPool(IO_WORKERS, daemonFactory("federation-file-relay"));
        maintenance.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
        maintenance.scheduleWithFixedDelay(this::purge, 1, 6 * 60, TimeUnit.MINUTES);
    }

    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Whether the relay can stage, serve and download files. */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Applies a changed storage directory, moving the stored files, and retries whatever part of
     * {@link #start()} failed. Returns null on success, otherwise an error message.
     */
    public String storageDirChanged() {
        // Not synchronized: a move copies every file, and the store serialises its own callers.
        String error = store.reopenIfMoved();
        refreshAvailability();
        return error;
    }

    /** Re-evaluates {@link #available} against what is working now. */
    private void refreshAvailability() {
        boolean was = available;
        boolean ready = store.isOpen() && maintenance != null && io != null
                     && (servletContext != null || registerServlet());
        available = ready;
        if (ready && !was) {
            Log.info("File relay is now available — downloads served from /federation-files");
        } else if (!ready && was) {
            Log.warn("File relay is no longer available — shares will keep their original URLs");
        }
    }

    /** Applies a changed log retention at once: prunes both activity logs and reloads the admin tables. */
    public void logRetentionChanged() {
        loadPersistedLogs();
    }

    /** Pings the configured clamd endpoint, for the admin console's connection test. */
    public boolean testAvConnection() {
        return ClamAvClient.ping();
    }

    private void recordScan(String fileName, long sizeBytes, String origin,
                             RelayStage stage, String verdict, String detail) {
        ScanLogEntry e = new ScanLogEntry(System.currentTimeMillis(), fileName, sizeBytes,
                origin == null ? "" : origin, stage, verdict, detail == null ? "" : detail);
        remember(e);
        // Written outside the lock, so the admin console's reads never wait on disk I/O.
        scanFileLog.append(e.fileName(), Long.toString(e.sizeBytes()), e.origin(), e.stage().token(),
                e.verdict(), e.detail());
    }

    private synchronized void remember(ScanLogEntry e) {
        scanLog.addFirst(e);
        if (scanLog.size() > SCAN_LOG_MAX) scanLog.removeLast();
    }

    /** The most recent AV scan outcomes, newest first. */
    public synchronized List<ScanLogEntry> recentAvScans() {
        return new ArrayList<>(scanLog);
    }

    private void recordRejection(String fileName, long sizeBytes, String origin,
                                  RelayStage stage, RejectionReason reason, String detail) {
        RejectionEntry e = new RejectionEntry(System.currentTimeMillis(), fileName, sizeBytes,
                origin == null ? "" : origin, stage, reason, detail == null ? "" : detail);
        remember(e);
        rejectionFileLog.append(e.fileName(), Long.toString(e.sizeBytes()), e.origin(),
                e.stage().token(), e.reason().name(), e.detail());
    }

    private synchronized void remember(RejectionEntry e) {
        rejectionLog.addFirst(e);
        if (rejectionLog.size() > REJECTION_LOG_MAX) rejectionLog.removeLast();
    }

    /** The most recently rejected files, newest first. */
    public synchronized List<RejectionEntry> recentRejections() {
        return new ArrayList<>(rejectionLog);
    }

    /** Absolute path of the AV scan log file. */
    public String scanLogPath() { return scanFileLog.path().toString(); }

    /** Absolute path of the rejected-file log. */
    public String rejectionLogPath() { return rejectionFileLog.path().toString(); }

    /** Prunes both activity logs to the retention window and reloads the in-memory tables from them. */
    private void loadPersistedLogs() {
        scanFileLog.ensureExists();
        rejectionFileLog.ensureExists();
        pruneActivityLogs();
        List<FileActivityLog.Row> scans = scanFileLog.readRecent(SCAN_LOG_MAX);
        List<FileActivityLog.Row> rejections = rejectionFileLog.readRecent(REJECTION_LOG_MAX);
        synchronized (this) {
            scanLog.clear();
            for (FileActivityLog.Row r : scans) {
                List<String> f = r.fields();
                // A five-field row predates the stage column, when only the ingress scan existed.
                if (f.size() >= 6) {
                    scanLog.addLast(new ScanLogEntry(r.when(), f.get(0), parseSize(f.get(1)),
                            f.get(2), RelayStage.fromToken(f.get(3)), f.get(4), f.get(5)));
                } else if (f.size() == 5) {
                    scanLog.addLast(new ScanLogEntry(r.when(), f.get(0), parseSize(f.get(1)),
                            f.get(2), RelayStage.INGRESS, f.get(3), f.get(4)));
                }
            }
            rejectionLog.clear();
            for (FileActivityLog.Row r : rejections) {
                List<String> f = r.fields();
                if (f.size() < 6) continue;
                // A reason this build does not know came from a newer one; skipped rather than misreported.
                RejectionReason reason = RejectionReason.fromName(f.get(4));
                if (reason == null) continue;
                rejectionLog.addLast(new RejectionEntry(r.when(), f.get(0), parseSize(f.get(1)),
                        f.get(2), RelayStage.fromToken(f.get(3)), reason, f.get(5)));
            }
            Log.info("File relay activity logs restored — {} scan entrie(s), {} rejection entrie(s) "
                   + "from {} and {}", scanLog.size(), rejectionLog.size(),
                     scanFileLog.path(), rejectionFileLog.path());
        }
    }

    /** Drops activity-log entries older than the configured retention. */
    private void pruneActivityLogs() {
        int days = FederationProperties.FILES_LOG_RETENTION_DAYS.getValue();
        scanFileLog.prune(days);
        rejectionFileLog.prune(days);
    }

    private static long parseSize(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stops the relay, breaking open connections so no worker outlives a reload. */
    public void stop() {
        available = false;
        // A thread blocked on a socket read ignores interrupts; closing the connection unblocks it.
        for (HttpURLConnection conn : activeFetches) {
            try {
                conn.disconnect();
            } catch (RuntimeException ignored) {
                // Best effort: the worker reading from it fails on its own once the pool is shut down.
            }
        }
        activeFetches.clear();
        shutdownAndAwait(maintenance, "maintenance");
        shutdownAndAwait(io, "I/O");
        maintenance = null;
        io = null;
        unregisterServlet();
        for (Transfer t : transfers.values()) {
            synchronized (t) { closeQuietly(t); }
        }
        transfers.clear();
        parkedRequests.clear();
        localDestinations.clear();
        rejectionTombstones.clear();
        inFlightSends.clear();
    }

    /** Stops a pool and waits a bounded time for it, logging when it does not stop. */
    private static void shutdownAndAwait(ExecutorService pool, String what) {
        if (pool == null) return;
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(WORKER_SHUTDOWN_MS, TimeUnit.MILLISECONDS)) {
                Log.warn("File relay {} workers did not stop within {}ms — a stalled task may still "
                       + "be running against the spool", what, WORKER_SHUTDOWN_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mounts the download endpoint; true on success, since the relay is unusable without it. */
    private boolean registerServlet() {
        try {
            servletContext = new ServletContextHandler();
            servletContext.setContextPath("/federation-files");
            servletContext.addServlet(new ServletHolder(new FederationFileServlet(this)), "/*");
            HttpBindManager.getInstance().addJettyHandler(servletContext);
            Log.info("File relay download endpoint registered at /federation-files on the HTTP-bind port");
            return true;
        } catch (RuntimeException e) {
            servletContext = null;
            Log.error("Could not register file relay servlet — file federation is UNAVAILABLE "
                    + "(shares keep their original URLs rather than pointing at a dead endpoint)", e);
            return false;
        }
    }

    private void unregisterServlet() {
        if (servletContext != null) {
            try {
                HttpBindManager.getInstance().removeJettyHandler(servletContext);
            } catch (RuntimeException e) {
                Log.debug("Could not remove the file relay servlet", e);
            }
            servletContext = null;
        }
    }

    /** Whether the admin enabled file federation and the relay is available; an unavailable relay acts disabled. */
    private boolean enabled() { return available && FederationProperties.FILES_ENABLED.getValue(); }

    /** The absolute time budget of one staging fetch: a fixed part plus the size cap at the floor throughput. */
    private long stageBudgetMs() {
        return STAGE_BASE_BUDGET_MS + (maxSizeBytes() / STAGE_FLOOR_BYTES_PER_S) * 1000L;
    }

    private long maxSizeBytes() {
        return FederationProperties.FILES_MAX_MB.getValue() * 1024L * 1024L;
    }

    /**
     * The {@code fed-file} annotation for an outbound message that shares a file from our upload
     * service, or null. Also starts staging the content so peers can pull it.
     */
    public Element annotationForOutbound(Message msg) {
        if (!enabled()) return null;
        Element existing = annotationOf(msg.getElement());
        if (existing != null) return existing.createCopy();
        String url = extractLocalUploadUrl(msg);
        if (url == null) return null;
        String id = sha256Hex(url.getBytes(StandardCharsets.UTF_8));
        String name = fileNameFromUrl(url);
        String token = ensureLocalContent(id, url, name);
        Element ann = DocumentHelper.createElement(
                QName.get(ANNOTATION, Namespace.get(FederationStanzaFactory.NS)));
        ann.addAttribute(FederationStanzaFactory.ATTR_ID, id);
        ann.addAttribute("url", url);
        ann.addAttribute("name", name);
        ann.addAttribute(FederationStanzaFactory.ATTR_ORIGIN, localDomain());
        // Only servers that carry this message learn the token, which is what scopes the share.
        if (token != null && !token.isBlank()) {
            ann.addAttribute(FederationStanzaFactory.ATTR_TOKEN, token);
        }
        return ann;
    }

    /** Adds the annotation to the message itself, for relay paths that forward it as is. */
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

    /** Shown to remote occupants when the sender's room has file federation off. */
    public static final String NOTICE_SENDER_ROOM_BLOCKED = "🚫 This user's room can't share files.";
    /** Shown to local occupants when this room has file federation off and a peer sent a file. */
    public static final String NOTICE_LOCAL_ROOM_BLOCKED = "🚫 File federation is disabled for this room.";

    /** Whether {@code msg} shares a file hosted on our own upload service. */
    public boolean isLocalUploadShare(Message msg) {
        return extractLocalUploadUrl(msg) != null;
    }

    /** Replaces the file in a copy forwarded to peers with {@link #NOTICE_SENDER_ROOM_BLOCKED}. */
    public void replaceWithSenderBlockedNotice(Element messageEl) {
        replaceFileWithNotice(messageEl, NOTICE_SENDER_ROOM_BLOCKED);
    }

    /** Replaces the file in a copy delivered to local occupants with {@link #NOTICE_LOCAL_ROOM_BLOCKED}. */
    public void replaceWithLocalBlockedNotice(Element messageEl) {
        replaceFileWithNotice(messageEl, NOTICE_LOCAL_ROOM_BLOCKED);
    }

    /** Removes the out-of-band URL and the annotation, and sets the body to {@code noticeText}. */
    private void replaceFileWithNotice(Element messageEl, String noticeText) {
        for (Element x : new ArrayList<>(messageEl.elements("x"))) {
            if ("jabber:x:oob".equals(x.getNamespaceURI())) messageEl.remove(x);
        }
        Element ann = annotationOf(messageEl);
        if (ann != null) messageEl.remove(ann);
        Element body = messageEl.element("body");
        if (body != null) body.setText(noticeText);
        else messageEl.addElement("body").setText(noticeText);
    }

    /**
     * The upload URL a share carries, from the out-of-band extension or a body that is a single URL,
     * when it points at this server's upload service; otherwise null.
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
        } catch (URISyntaxException e) {
            Log.debug("File relay: '{}' is not a parseable URL, treating as non-local", LogSafe.text(url));
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
        } catch (RuntimeException ignored) {
            // Server info not ready yet: only the domain and the extra hosts count as local.
        }
        String extra = FederationProperties.FILES_EXTRA_LOCAL_HOSTS.getValue();
        if (extra != null && !extra.isBlank()) {
            for (String h : extra.split(",")) {
                if (host.equalsIgnoreCase(h.strip())) return true;
            }
        }
        return false;
    }

    /**
     * Starts staging a local upload unless it is stored already, and returns its share token. The
     * token is minted once per file and kept across retries, so annotations already sent stay valid.
     */
    private String ensureLocalContent(String id, String url, String name) {
        FileRelayStore.StoredFile stored = store.get(id);
        if (stored != null) return stored.token();
        Transfer t = transfers.compute(id, (k, cur) -> {
            if (cur != null && cur.state != State.FAILED) return cur;
            Transfer nt = new Transfer(k, State.FETCHING);
            nt.url = url;
            nt.name = name;
            nt.origin = localDomain();
            nt.token = (cur != null && !cur.token.isBlank()) ? cur.token : newShareToken();
            return nt;
        });
        ExecutorService pool = io;
        if (t.state == State.FETCHING && t.url != null && pool != null) {
            pool.execute(() -> fetchLocalUpload(t));
        }
        return t.token;
    }

    /** Downloads a local upload into the store, checks it, and serves any parked requests. */
    private void fetchLocalUpload(Transfer t) {
        if (store.has(t.id)) { transfers.remove(t.id, t); return; }
        if (!FileTypePolicy.isExtensionAllowed(t.name)) {
            Log.warn("File relay: refusing to stage '{}' for outbound relay — extension not on "
                   + "the allowed list ({})", t.name, FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
            recordRejection(t.name, 0, t.origin, RelayStage.EGRESS,
                    RejectionReason.EXTENSION_NOT_ALLOWED, "not on allowed list");
            rejectStaging(t, "policy-rejected");
            return;
        }
        StagedUpload staged;
        try {
            staged = downloadToPart(t);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            Log.warn("File relay: could not stage local upload {} ({})", t.name, LogSafe.text(t.url), e);
            store.deletePart(t.id);
            t.state = State.FAILED;
            t.touch();
            failParked(t.id, "stage-failed");
            return;
        }
        if (FederationProperties.FILES_AV_ENABLED.getValue() && !passesEgressScan(t, staged.size())) return;
        try {
            store.finalizePart(t.id, t.name, staged.mime(), staged.size(), staged.sha256(), t.token);
            transfers.remove(t.id, t);
            Log.info("File relay: staged local upload {} ({} bytes) as {}", t.name, staged.size(), t.id);
            serveParked(t.id);
        } catch (IOException | RuntimeException e) {
            Log.warn("File relay: could not finalize staged upload {}", t.id, e);
            t.state = State.FAILED;
            failParked(t.id, "stage-failed");
        }
    }

    /** What staging a local upload produced. */
    private record StagedUpload(long size, String sha256, String mime) { }

    /**
     * Downloads {@code t}'s upload into its part file, hashing it on the way. Stops at the absolute
     * budget, the size cap, on shutdown, and when the sweep has given up on the transfer.
     */
    private StagedUpload downloadToPart(Transfer t) throws IOException, GeneralSecurityException {
        long deadline = System.currentTimeMillis() + stageBudgetMs();
        HttpURLConnection conn = (HttpURLConnection) new URL(t.url).openConnection();
        activeFetches.add(conn);
        try {
            if (conn instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(trustAllFactory());
                https.setHostnameVerifier(trustAllHostnames());
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "openfire-federation-file-relay");
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            String mime = mediaTypeOf(conn.getContentType());
            long cap = maxSizeBytes();
            long total = 0;
            MessageDigest digest = sha256Digest();
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(store.partPath(t.id))) {
                byte[] buf = new byte[IO_BUFFER_BYTES];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (System.currentTimeMillis() > deadline) {
                        throw new IOException("staging exceeded its " + stageBudgetMs() + "ms budget");
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("file relay is shutting down");
                    }
                    if (t.state != State.FETCHING) {
                        throw new IOException("transfer is no longer staging (" + t.state + ")");
                    }
                    total += n;
                    if (total > cap) throw new IOException("exceeds size cap (" + cap + " bytes)");
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                    t.touch();
                }
            }
            return new StagedUpload(total, toHex(digest.digest()), mime);
        } finally {
            activeFetches.remove(conn);
            conn.disconnect();
        }
    }

    /** The media type of a Content-Type header, without parameters; octet-stream when there is none. */
    private static String mediaTypeOf(String contentType) {
        if (contentType == null || contentType.isBlank()) return "application/octet-stream";
        int semi = contentType.indexOf(';');
        return (semi > 0 ? contentType.substring(0, semi) : contentType).strip();
    }

    /**
     * The egress AV scan of a staged upload. It fails closed: a file the scanner never saw is not
     * known to be clean, so it is rejected like an infected one.
     */
    private boolean passesEgressScan(Transfer t, long size) {
        ClamAvClient.ScanResult scan = ClamAvClient.scan(store.partPath(t.id));
        recordScan(t.name, size, t.origin, RelayStage.EGRESS, scan.verdict().name(), scan.detail());
        if (scan.verdict() == ClamAvClient.Verdict.CLEAN) return true;
        boolean infected = scan.verdict() == ClamAvClient.Verdict.INFECTED;
        if (infected) {
            Log.warn("File relay: AV detected '{}' in locally-uploaded file {} — "
                   + "not offering it to peers", scan.detail(), t.name);
        } else {
            Log.warn("File relay: AV scan unavailable for locally-uploaded file {} ({}) — "
                   + "not offering it to peers (fails closed)", t.name, scan.detail());
        }
        RejectionReason reason = infected ? RejectionReason.AV_INFECTED : RejectionReason.AV_ERROR;
        recordRejection(t.name, size, t.origin, RelayStage.EGRESS, reason, scan.detail());
        store.deletePart(t.id);
        rejectStaging(t, reason.wireReason());
        return false;
    }

    /** Marks a staging permanently rejected, so it is never retried, and tells parked requesters why. */
    private void rejectStaging(Transfer t, String wireReason) {
        t.state = State.FAILED;
        t.rejected = true;
        tombstoneRejection(t.id);
        t.touch();
        failParked(t.id, wireReason);
    }

    /**
     * A copy of an annotated share for delivery into {@code targetRoomJid}, with the URL pointing at
     * our download endpoint, and starts the pull. Null when there is nothing to rewrite.
     */
    public Element rewriteForDelivery(Element msgEl, String targetRoomJid, String... hints) {
        if (!enabled() || annotationOf(msgEl) == null) return null;
        Element copy = msgEl.createCopy();
        rewriteElement(copy, new LocalDest(targetRoomJid, true), hints);
        return copy;
    }

    /** Rewrites an annotated share in place, for delivery to a local 1:1 recipient. */
    public void rewriteInPlace(Message msg, String... hints) {
        if (!enabled() || annotationOf(msg.getElement()) == null) return;
        JID to = msg.getTo();
        LocalDest dest = to != null ? new LocalDest(to.toBareJID(), false) : null;
        rewriteElement(msg.getElement(), dest, hints);
    }

    private void rewriteElement(Element messageEl, LocalDest dest, String... hints) {
        Element ann = annotationOf(messageEl);
        if (ann == null) return;
        String id     = ann.attributeValue(FederationStanzaFactory.ATTR_ID);
        String url    = ann.attributeValue("url");
        String name   = sanitizeFileName(ann.attributeValue("name", "file"));
        String origin = ann.attributeValue(FederationStanzaFactory.ATTR_ORIGIN, "");
        String token  = ann.attributeValue(FederationStanzaFactory.ATTR_TOKEN, "");
        messageEl.remove(ann);
        if (id == null || !isHexId(id) || url == null || url.isBlank()) return;
        // Our own share coming back keeps its original URL.
        if (origin.equals(localDomain())) return;

        registerExpected(id, name, origin, token, dest, hints);

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

    /** Records that {@code id} is expected here and starts or retries the overlay pull. */
    private void registerExpected(String id, String name, String origin, String token,
                                  LocalDest dest, String... hints) {
        if (store.has(id)) return;
        if (!transfers.containsKey(id) && transfers.size() >= MAX_TRANSFERS) {
            long now = System.currentTimeMillis();
            if (now - transferCapLoggedAt >= 60_000L) {
                transferCapLoggedAt = now;
                Log.warn("File relay: not pulling {} from {} — {} transfers already tracked. Further cases are "
                       + "logged at most once a minute.", id, LogSafe.text(origin), MAX_TRANSFERS);
            }
            return;
        }
        List<String> hintList = new ArrayList<>();
        for (String h : hints) {
            if (h != null && !h.isBlank() && !h.equals(localDomain())) hintList.add(h);
        }
        Transfer t = transfers.compute(id, (k, cur) -> {
            if (cur != null && cur.state != State.FAILED) return cur;
            Transfer nt = new Transfer(k, State.REQUESTED);
            nt.name = name;
            nt.origin = origin;
            nt.token = token == null ? "" : token;
            nt.hints = List.copyOf(hintList);
            return nt;
        });
        // Only after the transfer exists: the sweep drops destinations that have no transfer.
        if (dest != null) {
            localDestinations.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(dest);
        }
        synchronized (t) {
            if (t.state == State.REQUESTED && t.lastRequestAt == 0) {
                sendRequest(t);
            }
        }
    }

    /** Sends the file-request toward the first routable server that may hold the content. */
    private void sendRequest(Transfer t) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (t.origin != null && !t.origin.isBlank()) candidates.add(t.origin);
        candidates.addAll(t.hints);
        candidates.remove(localDomain());
        for (String target : candidates) {
            String nextHop = manager.getRoutingTable().findNextHop(target).orElse(null);
            if (nextHop == null) continue;
            boolean sent = FederationSender.send("file-request", target, () -> FederationStanzaFactory.fileRequest(
                    nextHop, target, localDomain(), t.id, t.token, ""));
            if (!sent) continue;
            t.expectedFrom = nextHop;
            t.lastRequestAt = System.currentTimeMillis();
            t.requestAttempts.incrementAndGet();
            t.touch();
            Log.debug("File relay: requested {} from {} via {} (attempt {})",
                      t.id, target, nextHop, t.requestAttempts.get());
            return;
        }
        Log.warn("File relay: no routable holder for {} (origin={}, hints={}) — marking failed",
                 t.id, t.origin, t.hints);
        t.state = State.FAILED;
        t.touch();
    }

    /**
     * Whether a response for {@code t} may come from {@code fromDomain}: the current next hop toward
     * a holder, or the one the request went through. Stops an off-path peer substituting content.
     */
    private boolean isFromExpectedDirection(Transfer t, String fromDomain) {
        if (fromDomain == null || fromDomain.isBlank()) return false;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (t.origin != null && !t.origin.isBlank()) candidates.add(t.origin);
        candidates.addAll(t.hints);
        for (String c : candidates) {
            var hop = manager.getRoutingTable().findNextHop(c);
            if (hop.isPresent() && hop.get().equalsIgnoreCase(fromDomain)) return true;
        }
        return t.expectedFrom != null && t.expectedFrom.equalsIgnoreCase(fromDomain);
    }

    /** A fresh share capability: 128 random bits in hex. */
    private static String newShareToken() {
        byte[] raw = new byte[SHARE_TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        return toHex(raw);
    }

    /**
     * Whether a file-request presents the share's capability token, compared in constant time.
     * Content staged before tokens existed has none and passes until it expires.
     */
    private boolean shareTokenOk(String id, String presented) {
        String expected = expectedToken(id);
        if (expected == null || expected.isBlank()) return true;
        if (presented == null || presented.isBlank()) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                     presented.getBytes(StandardCharsets.US_ASCII));
    }

    /** The capability for {@code id}, from the stored file or else the transfer in progress. */
    private String expectedToken(String id) {
        FileRelayStore.StoredFile sf = store.get(id);
        if (sf != null && sf.token() != null && !sf.token().isBlank()) return sf.token();
        Transfer t = transfers.get(id);
        return t != null ? t.token : null;
    }

    /**
     * Whether a request from an untrusted peer may name {@code requester}: the peer itself or a server
     * behind it. Trusted peers are not checked, since diamonds and hubs legitimately route around.
     */
    private boolean requesterDirectionOk(String fromDomain, String requester) {
        if (fromDomain == null || fromDomain.isBlank()) return false;
        if (!manager.getPeerRegistry().isUntrusted(fromDomain)) return true;
        if (requester.equalsIgnoreCase(fromDomain)) return true;
        return manager.getRoutingTable().findNextHop(requester)
                      .map(hop -> hop.equalsIgnoreCase(fromDomain)).orElse(false);
    }

    /** Relays a file-* element toward its destination, as a transit hop. */
    public void relayToward(String elementName, Element el, String fromDomain) {
        String destination = el.attributeValue(FederationStanzaFactory.ATTR_DESTINATION);
        String via         = el.attributeValue(FederationStanzaFactory.ATTR_VIA, "");
        String local       = localDomain();
        if (FederationStanzaFactory.viaContains(via, local)) {
            Log.warn("{} loop detected (via={}), dropping", elementName, via);
            return;
        }
        String newVia = via.isEmpty() ? local : via + "," + local;
        manager.getRoutingTable().findNextHop(destination).ifPresentOrElse(
            nextHop -> FederationSender.send(elementName, destination,
                                             () -> FederationStanzaFactory.relay(nextHop, el, newVia)),
            () -> Log.debug("{}: no route to {}, dropping", elementName, destination));
    }

    /** Serves a peer's file-request, parks it while our own copy arrives, or refuses it. */
    public void handleFileRequest(String fromDomain, Element el) {
        if (!enabled()) return;
        String id        = el.attributeValue(FederationStanzaFactory.ATTR_ID);
        String requester = el.attributeValue(FederationStanzaFactory.ATTR_ORIGIN);
        String token     = el.attributeValue(FederationStanzaFactory.ATTR_TOKEN);
        if (id == null || !isHexId(id) || requester == null || requester.isBlank()
                || requester.equals(localDomain())) {
            return;
        }
        if (!requesterDirectionOk(fromDomain, requester)) {
            Log.warn("SECURITY: ignoring file-request for {} claiming to come from {} — it arrived "
                   + "from {}, which is not on the route back to that domain", id, LogSafe.text(requester),
                     fromDomain);
            return;
        }
        if (!shareTokenOk(id, token)) {
            Log.warn("SECURITY: refusing file-request for {} from {} — {} share capability",
                     id, LogSafe.text(requester), token == null || token.isBlank() ? "no" : "wrong");
            sendError(requester, id, "not-authorized");
            return;
        }
        if (store.has(id)) {
            startSend(id, requester);
            return;
        }
        Transfer t = transfers.get(id);
        if (t != null && t.state != State.FAILED) {
            parkedRequests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(requester);
            Log.debug("File relay: parked request for {} from {} (local copy {})",
                      id, LogSafe.text(requester), t.state);
            return;
        }
        if (t != null && t.rejected) {
            sendError(requester, id, "policy-rejected");
            return;
        }
        if (t != null && t.url != null) {
            // Our own upload failed to stage earlier; the file may still exist, so try again.
            parkedRequests.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(requester);
            ensureLocalContent(id, t.url, t.name);
            return;
        }
        sendError(requester, id, "not-found");
    }

    /** Accepts the header of a transfer we requested and prepares to assemble its chunks. */
    public void handleFileOffer(String fromDomain, Element el) {
        if (!available) return;
        String id = el.attributeValue(FederationStanzaFactory.ATTR_ID);
        if (id == null || store.has(id)) return;
        Transfer t = transfers.get(id);
        if (t == null) {
            Log.debug("File relay: unsolicited file-offer for {} from {} — ignoring", id, fromDomain);
            return;
        }
        if (!isFromExpectedDirection(t, fromDomain)) {
            Log.warn("SECURITY: ignoring file-offer for {} from unexpected peer {} (expected via {})",
                     id, fromDomain, t.expectedFrom);
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
        // A chunk below the minimum is only valid as the single chunk of a small file.
        if (size < 0 || size > cap || chunkSize < 1 || chunkSize > MAX_CHUNK_BYTES
                || (chunkSize < MIN_CHUNK_BYTES && totalChunks > 1)
                || totalChunks < 0 || totalChunks != (int) ((size + chunkSize - 1) / chunkSize)) {
            Log.warn("File relay: rejecting file-offer for {} from {} — implausible geometry "
                   + "(size={}, chunkSize={}, totalChunks={}, cap={})",
                     id, fromDomain, size, chunkSize, totalChunks, cap);
            synchronized (t) { failTransfer(t); }
            return;
        }
        synchronized (t) {
            if (t.state == State.RECEIVING) return;
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
            } catch (IOException e) {
                Log.warn("File relay: cannot open part file for {}", id, e);
                failTransfer(t);
                return;
            }
            t.received = new BitSet(Math.max(totalChunks, 1));
            t.state = State.RECEIVING;
            t.touch();
            if (totalChunks == 0) finalizeReceive(t);
        }
    }

    /** Writes one chunk of an inbound transfer at its offset, so chunks may arrive in any order. */
    public void handleFileChunk(String fromDomain, Element el) {
        if (!available) return;
        String id = el.attributeValue(FederationStanzaFactory.ATTR_ID);
        if (id == null) return;
        Transfer t = transfers.get(id);
        if (t == null || t.state != State.RECEIVING) return;
        if (!isFromExpectedDirection(t, fromDomain)) {
            Log.warn("SECURITY: ignoring file-chunk for {} from unexpected peer {} (expected via {})",
                     id, fromDomain, t.expectedFrom);
            return;
        }
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
            } catch (IOException e) {
                Log.warn("File relay: write failed for {}", id, e);
                failTransfer(t);
                return;
            }
            t.received.set(seq);
            t.touch();
            if (t.received.cardinality() == t.totalChunks) finalizeReceive(t);
        }
    }

    /**
     * Aborts a transfer the holder refused. A permanent rejection is also passed on to our own parked
     * requesters and to the local recipients, so it reaches every hop of a hub chain.
     */
    public void handleFileError(String fromDomain, Element el) {
        if (!available) return;
        String id = el.attributeValue(FederationStanzaFactory.ATTR_ID);
        if (id == null) return;
        String reason = el.attributeValue("reason", "unspecified");
        Transfer t = transfers.get(id);
        if (t != null && !isFromExpectedDirection(t, fromDomain)) {
            Log.warn("SECURITY: ignoring file-error for {} from unexpected peer {} (expected via {})",
                     id, fromDomain, t.expectedFrom);
            return;
        }
        // An error mid-stream must abort too, or the file would complete after recipients were told it failed.
        if (t != null && (t.state == State.REQUESTED || t.state == State.RECEIVING)) {
            Log.info("File relay: holder refused {} ({}) — aborting transfer in state {}",
                     id, LogSafe.text(reason), t.state);
            synchronized (t) {
                failTransfer(t, PERMANENT_REJECT_REASONS.contains(reason));
            }
        }
        if (PERMANENT_REJECT_REASONS.contains(reason)) {
            failParked(id, reason);
            notifyLocalDestinationsOfRejection(id, t != null ? t.name : null);
        }
    }

    /** Verifies and scans a fully received file, then stores it; call with the transfer lock held. */
    private void finalizeReceive(Transfer t) {
        closeQuietly(t);
        try {
            if (t.sha256 != null && !t.sha256.isBlank()) {
                String actual = sha256OfFile(store.partPath(t.id));
                if (!t.sha256.equalsIgnoreCase(actual)) {
                    Log.warn("File relay: hash mismatch for {} — expected {}, got {}; discarding",
                             t.id, t.sha256, actual);
                    recordRejection(t.name, t.size, t.origin, RelayStage.INGRESS,
                            RejectionReason.HASH_MISMATCH, "expected " + t.sha256 + ", got " + actual);
                    failTransfer(t, true);
                    failParked(t.id, RejectionReason.HASH_MISMATCH.wireReason());
                    notifyLocalDestinationsOfRejection(t.id, t.name);
                    return;
                }
            }
            if (!FileTypePolicy.isExtensionAllowed(t.name)) {
                Log.warn("File relay: rejecting received file '{}' — extension not on the "
                       + "allowed list ({})", t.name, FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
                recordRejection(t.name, t.size, t.origin, RelayStage.INGRESS,
                        RejectionReason.EXTENSION_NOT_ALLOWED, "not on allowed list");
                failTransfer(t, true);
                failParked(t.id, RejectionReason.EXTENSION_NOT_ALLOWED.wireReason());
                notifyLocalDestinationsOfRejection(t.id, t.name);
                return;
            }
            FileTypePolicy.ContentCheck contentCheck = FileTypePolicy.checkContent(store.partPath(t.id), t.name);
            if (!contentCheck.ok()) {
                String detail = contentCheck.detectedMime() != null
                        ? "sniffs as '" + contentCheck.detectedMime() + "'"
                        : FileTypePolicy.extensionOf(t.name).isEmpty()
                                ? "no file extension to verify content against"
                                : "unreadable";
                recordRejection(t.name, t.size, t.origin, RelayStage.INGRESS,
                        RejectionReason.CONTENT_MISMATCH, detail);
                failTransfer(t, true);
                failParked(t.id, RejectionReason.CONTENT_MISMATCH.wireReason());
                notifyLocalDestinationsOfRejection(t.id, t.name);
                return;
            }
            if (FederationProperties.FILES_AV_ENABLED.getValue()) {
                ClamAvClient.ScanResult scan = ClamAvClient.scan(store.partPath(t.id));
                recordScan(t.name, t.size, t.origin, RelayStage.INGRESS, scan.verdict().name(), scan.detail());
                RejectionReason avReason = switch (scan.verdict()) {
                    case INFECTED -> {
                        Log.warn("File relay: AV detected '{}' in received file {} — discarding",
                                 scan.detail(), t.name);
                        yield RejectionReason.AV_INFECTED;
                    }
                    case ERROR -> {
                        Log.warn("File relay: AV scan unavailable for {} ({}) — "
                               + "discarding (fails closed)", t.name, scan.detail());
                        yield RejectionReason.AV_ERROR;
                    }
                    case CLEAN -> null;
                };
                if (avReason != null) {
                    recordRejection(t.name, t.size, t.origin, RelayStage.INGRESS, avReason, scan.detail());
                    failTransfer(t, true);
                    failParked(t.id, avReason.wireReason());
                    notifyLocalDestinationsOfRejection(t.id, t.name);
                    return;
                }
            }
            // Keeping the token lets this server serve the same spokes the origin would.
            store.finalizePart(t.id, t.name, t.mime, t.size, t.sha256, t.token);
            transfers.remove(t.id, t);
            localDestinations.remove(t.id);
            Log.info("File relay: received {} ({} bytes) as {}", t.name, t.size, t.id);
        } catch (IOException | RuntimeException e) {
            Log.warn("File relay: could not finalize {}", t.id, e);
            failTransfer(t);
            return;
        }
        serveParked(t.id);
    }

    /** Marks a transfer failed and deletes its part file; call with the transfer lock held. */
    private void failTransfer(Transfer t) {
        failTransfer(t, false);
    }

    /** As {@link #failTransfer(Transfer)}; {@code rejected} marks a permanent rejection that is never retried. */
    private void failTransfer(Transfer t, boolean rejected) {
        closeQuietly(t);
        store.deletePart(t.id);
        t.state = State.FAILED;
        if (rejected) {
            t.rejected = true;
            tombstoneRejection(t.id);
        }
        t.touch();
    }

    private static void closeQuietly(Transfer t) {
        if (t.out != null) {
            try {
                t.out.close();
            } catch (IOException ignored) {
                // The part file is deleted or finalized next; a failed close loses nothing.
            }
            t.out = null;
        }
    }

    /**
     * Streams a stored file to {@code requester} on a worker. A repeated request joins the send in
     * progress, and past {@link #MAX_INFLIGHT_SENDS} the requester is told the relay is busy.
     */
    private void startSend(String id, String requester) {
        ExecutorService pool = io;
        if (pool == null) return;
        String sendKey = requester + '|' + id;
        if (!inFlightSends.add(sendKey)) {
            Log.debug("File relay: already streaming {} to {} — ignoring repeat request",
                      id, LogSafe.text(requester));
            return;
        }
        if (inFlightSends.size() > MAX_INFLIGHT_SENDS) {
            inFlightSends.remove(sendKey);
            Log.warn("File relay: {} concurrent sends already running — refusing {} for {}",
                     MAX_INFLIGHT_SENDS, id, LogSafe.text(requester));
            sendError(requester, id, "busy");
            return;
        }
        pool.execute(() -> {
            try {
                streamTo(id, requester);
            } finally {
                inFlightSends.remove(sendKey);
            }
        });
    }

    /** Sends the file-offer and then every chunk of a stored file to {@code requester}. */
    private void streamTo(String id, String requester) {
        FileRelayStore.StoredFile sf = store.get(id);
        if (sf == null) {
            sendError(requester, id, "not-found");
            return;
        }
        int chunkSize = chunkBytes();
        int totalChunks = (int) ((sf.size() + chunkSize - 1) / chunkSize);
        int delayMs = Math.max(0, FederationProperties.FILES_CHUNK_DELAY_MS.getValue());
        String local = localDomain();
        var hop = manager.getRoutingTable().findNextHop(requester);
        if (hop.isEmpty()) {
            Log.debug("File relay: no route to requester {} for {} — dropping", LogSafe.text(requester), id);
            return;
        }
        try {
            boolean offered = FederationSender.send("file-offer", requester, () -> FederationStanzaFactory.fileOffer(
                    hop.get(), requester, local, id, sf.name(), sf.mime(), sf.size(), sf.sha256(),
                    chunkSize, totalChunks, ""));
            if (!offered) return;
            try (InputStream in = Files.newInputStream(store.contentPath(id))) {
                byte[] buf = new byte[chunkSize];
                for (int seq = 0; seq < totalChunks; seq++) {
                    int read = in.readNBytes(buf, 0, chunkSize);
                    String chunkHop = manager.getRoutingTable().findNextHop(requester).orElse(null);
                    if (chunkHop == null) {
                        Log.warn("File relay: lost route to {} mid-transfer of {} — aborting",
                                 LogSafe.text(requester), id);
                        return;
                    }
                    String b64 = Base64.getEncoder().encodeToString(
                            read == buf.length ? buf : Arrays.copyOf(buf, read));
                    int chunk = seq;
                    boolean sent = FederationSender.send("file-chunk", requester,
                            () -> FederationStanzaFactory.fileChunk(chunkHop, requester, local, id, chunk, b64, ""));
                    if (!sent) return;
                    if (delayMs > 0) Thread.sleep(delayMs);
                }
            }
            Log.info("File relay: served {} ({} bytes, {} chunk(s)) to {}",
                     sf.name(), sf.size(), totalChunks, requester);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            Log.warn("File relay: send of {} to {} failed", id, LogSafe.text(requester), e);
        }
    }

    /** The configured chunk size, clamped to the range every receiver accepts. */
    private static int chunkBytes() {
        return Math.min(MAX_CHUNK_BYTES,
                        Math.max(MIN_CHUNK_BYTES, FederationProperties.FILES_CHUNK_BYTES.getValue()));
    }

    private void sendError(String requester, String id, String reason) {
        manager.getRoutingTable().findNextHop(requester).ifPresent(nextHop -> FederationSender.send(
                "file-error", requester,
                () -> FederationStanzaFactory.fileError(nextHop, requester, localDomain(), id, reason, "")));
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

    /** Tells the local rooms and users that were waiting on {@code id} that the file was rejected. */
    private void notifyLocalDestinationsOfRejection(String id, String fileName) {
        Set<LocalDest> dests = localDestinations.remove(id);
        if (dests == null || dests.isEmpty()) return;
        String text = "File failed upload check - see server logs"
                + (fileName != null && !fileName.isBlank() ? " (" + fileName + ")" : "");
        for (LocalDest dest : dests) {
            try {
                if (dest.room()) {
                    notifyRoomOccupants(dest.jid(), text);
                } else {
                    notifyUser(dest.jid(), text);
                }
            } catch (RuntimeException e) {
                Log.warn("File relay: could not notify {} of rejected file {}", dest.jid(), id, e);
            }
        }
    }

    /** Delivers a notice from the room's "System" nick to every current occupant. */
    private void notifyRoomOccupants(String roomJid, String text) {
        MUCRoom room = manager.findLocalMucRoom(roomJid);
        if (room == null) return;
        JID target = new JID(roomJid);
        JID from = new JID(target.getNode(), target.getDomain(), "System");
        Collection<MUCOccupant> occupants = room.getOccupants();
        for (MUCOccupant occupant : occupants) {
            Message notice = new Message();
            notice.setType(Message.Type.groupchat);
            notice.setFrom(from);
            notice.setTo(occupant.getUserAddress());
            notice.setBody(text);
            FederationStanzaFactory.markAsForwarded(notice);
            FederationSender.directDeliver(notice);
        }
        Log.info("File relay: notified {} occupant(s) of {} — rejected file", occupants.size(), roomJid);
    }

    /** Delivers a normal 1:1 chat notice to a local recipient. */
    private void notifyUser(String userJid, String text) {
        Message notice = new Message();
        notice.setType(Message.Type.chat);
        notice.setFrom(new JID(localDomain()));
        notice.setTo(new JID(userJid));
        notice.setBody(text);
        FederationStanzaFactory.markAsForwarded(notice);
        FederationSender.directDeliver(notice);
        Log.info("File relay: notified {} — rejected file", userJid);
    }

    /** What the download servlet can do for an id. */
    public enum ServeState { OK, PENDING, REJECTED, NOT_FOUND }
    /** The answer to {@link #lookup}: the state, plus the stored file and its path when OK. */
    public record ServeInfo(ServeState state, FileRelayStore.StoredFile file, Path path) {}

    /**
     * Resolves an id for the download servlet. A failed pull is retried, at most every
     * {@link #REQUEST_RETRY_MS}, when its link is opened; a rejected one never is.
     */
    public ServeInfo lookup(String id) {
        if (!isHexId(id)) return new ServeInfo(ServeState.NOT_FOUND, null, null);
        FileRelayStore.StoredFile sf = store.get(id);
        if (sf != null) return new ServeInfo(ServeState.OK, sf, store.contentPath(id));
        Transfer t = transfers.get(id);
        if (t == null) {
            return rejectionTombstones.containsKey(id)
                    ? new ServeInfo(ServeState.REJECTED, null, null)
                    : new ServeInfo(ServeState.NOT_FOUND, null, null);
        }
        if (t.state == State.FAILED && t.rejected) return new ServeInfo(ServeState.REJECTED, null, null);
        if (t.state == State.FAILED
                && System.currentTimeMillis() - t.lastRequestAt > REQUEST_RETRY_MS) {
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

    /** Runs {@link #sweepTransfers()}, catching what would otherwise cancel the scheduled task (CERT TPS03-J). */
    private void sweep() {
        try {
            sweepTransfers();
        } catch (RuntimeException e) {
            Log.error("File relay sweep failed; it runs again on schedule", e);
        }
    }

    /** Times out stalled transfers, retries unanswered requests, and drops expired bookkeeping. */
    private void sweepTransfers() {
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
        localDestinations.keySet().removeIf(id -> store.has(id) || !transfers.containsKey(id));
        purgeRejectionTombstones();
    }

    /** Deletes stored files past the retention window and prunes the activity logs. */
    private void purge() {
        try {
            long maxAge = FederationProperties.FILES_RETENTION_DAYS.getValue() * 24L * 3600_000L;
            int removed = store.purgeOlderThan(maxAge);
            if (removed > 0) Log.info("File relay: purged {} expired file(s)", removed);
        } catch (RuntimeException e) {
            Log.warn("File relay purge failed", e);
        }
        try {
            pruneActivityLogs();
        } catch (RuntimeException e) {
            Log.warn("File relay activity-log prune failed", e);
        }
    }

    /** The URL local clients download {@code id} from: the configured public URL, or the HTTP-bind port. */
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
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            // An unparseable URL or escape falls back to the generic name.
        }
        return "file";
    }

    /** A file name stripped of path separators, quotes and control characters, at most 200 characters. */
    static String sanitizeFileName(String name) {
        if (name == null) return "file";
        String clean = name.replaceAll("[\\\\/\\p{Cntrl}\"']", "").strip();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) return "file";
        return clean.length() > 200 ? clean.substring(clean.length() - 200) : clean;
    }

    /** Whether {@code id} is a transfer id: exactly 64 lowercase hex characters. */
    static boolean isHexId(String id) {
        if (id == null || id.length() != 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
        }
        return true;
    }

    /** Remembers that {@code id} was rejected, keeping the map bounded. */
    private void tombstoneRejection(String id) {
        if (id == null) return;
        rejectionTombstones.put(id, System.currentTimeMillis());
        if (rejectionTombstones.size() > REJECTION_TOMBSTONE_MAX) {
            purgeRejectionTombstones();
            var it = rejectionTombstones.keySet().iterator();
            while (rejectionTombstones.size() > REJECTION_TOMBSTONE_MAX && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    /** Forgets rejections older than {@link #REJECTION_TOMBSTONE_TTL_MS}. */
    private void purgeRejectionTombstones() {
        long cutoff = System.currentTimeMillis() - REJECTION_TOMBSTONE_TTL_MS;
        rejectionTombstones.values().removeIf(ts -> ts < cutoff);
    }

    /** A new SHA-256 digest; every Java platform is required to provide one. */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        return toHex(sha256Digest().digest(data));
    }

    private static String sha256OfFile(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[IO_BUFFER_BYTES];
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
     * A socket factory that trusts any certificate, for staging fetches from this server's own upload
     * service, whose URL was already checked to point here and which may use a self-signed certificate.
     */
    private SSLSocketFactory trustAllFactory() throws GeneralSecurityException {
        SSLSocketFactory f = trustAllFactory;
        if (f == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            } }, new SecureRandom());
            trustAllFactory = f = ctx.getSocketFactory();
        }
        return f;
    }

    private HostnameVerifier trustAllHostnames() {
        return (hostname, session) -> true;
    }
}
