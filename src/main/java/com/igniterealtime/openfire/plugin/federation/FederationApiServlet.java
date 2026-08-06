package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.files.FileRelayManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.util.CookieUtils;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.SystemProperty;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class FederationApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");
        ensureCsrfCookie(req, resp);   // hand the page a token it can echo on POSTs

        FederationPlugin plugin = FederationPlugin.getInstance();
        PrintWriter out = resp.getWriter();

        if (plugin == null) {
            out.print(error("Plugin not loaded"));
            return;
        }

        if ("poll".equals(req.getParameter("action"))) {
            out.print(longPoll(req.getParameter("hash")));
        } else {
            out.print(buildStatusJson(plugin));
        }
    }

    // ── Long-poll: hold the request until the status actually changes ─────────

    /** How long a poll request is held open before answering "unchanged". */
    private static final long POLL_TIMEOUT_MS = 25_000;
    /** How often a held request re-checks the state for a change. */
    private static final long POLL_CHECK_MS = 1_000;
    /** Each held request occupies an admin-console thread; extra tabs degrade to plain polling. */
    private static final int POLL_MAX_HELD = 8;
    private static final AtomicInteger heldPolls = new AtomicInteger();

    /**
     * Holds the request until the status fingerprint no longer matches {@code clientHash}
     * (or the timeout passes), then answers with the full status JSON plus the new
     * fingerprint under a {@code hash} key. The fingerprint ignores fields that merely age
     * with the clock (probe age/RTT, and a down peer's reconnect countdown) — the page advances
     * those locally between updates, so they must not by themselves wake a held poll.
     */
    private String longPoll(String clientHash) {
        String json = buildStatusJson(FederationPlugin.getInstance());
        String hash = fingerprint(json);
        if (clientHash != null && !clientHash.isEmpty() && hash.equals(clientHash)) {
            boolean hold = heldPolls.incrementAndGet() <= POLL_MAX_HELD;
            try {
                long checkMs = hold ? POLL_CHECK_MS : POLL_CHECK_MS * 5;
                long deadline = System.currentTimeMillis() + (hold ? POLL_TIMEOUT_MS : checkMs);
                while (hash.equals(clientHash) && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(checkMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    FederationPlugin plugin = FederationPlugin.getInstance();
                    if (plugin == null) return error("Plugin not loaded");
                    json = buildStatusJson(plugin);
                    hash = fingerprint(json);
                }
            } finally {
                heldPolls.decrementAndGet();
            }
        }
        return "{\"hash\":\"" + hash + "\"," + json.substring(1);
    }

    /** SHA-256 (hex, truncated) of the status JSON with clock-derived fields blanked. */
    private static String fingerprint(String json) {
        // Blank the fields that advance on their own without a real state change: probe age/RTT,
        // and nextRetryAt (an UNREACHABLE peer's back-off pushes this forward every few seconds).
        // The page re-derives all of these locally from absolute timestamps (see tickClocks), and a
        // genuine transition still moves an un-blanked field (e.g. the peer's status), so stripping
        // these only stops a held long-poll from returning — and the whole page re-rendering — on a
        // change no admin would notice.
        String stable = json.replaceAll("\"(?:pongAgeSecs|pingMs|nextRetryAt)\":-?\\d+", "");
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(stable.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(stable.hashCode());
        }
    }

    /** Builds the full status document the admin page renders (one JSON object). */
    private String buildStatusJson(FederationPlugin plugin) {
        FederationManager mgr = plugin.getManager();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"localDomain\":\"").append(esc(localDomain)).append("\",");

        // Candidate servers for the per-room visibility ACL (routing-table destinations).
        sb.append("\"routableServers\":");
        strings(sb, mgr.routableServers());
        sb.append(",");

        // ── peers ─────────────────────────────────────────────────────────────
        sb.append("\"peers\":");
        array(sb, mgr.getPeerRegistry().getPeers(), (b, p) -> {
            b.append("{")
             .append("\"domain\":\"").append(esc(p.getDomain())).append("\",")
             .append("\"status\":\"").append(p.getStatus().name()).append("\",")
             .append("\"lastSeen\":").append(p.getLastSeenMillis()).append(",")
             .append("\"nextRetryAt\":").append(mgr.getNextRetryAt(p.getDomain())).append(",")
             .append("\"untrusted\":").append(p.isUntrusted()).append(",")
             .append("\"foreign\":").append(mgr.isForeignDomain(p.getDomain())).append(",")
             .append("\"certPinned\":").append(p.getPinnedCertFp() != null).append(",")
             .append("\"certMismatch\":").append(p.isCertMismatch()).append(",")
             .append("\"exposedServers\":");
            strings(b, p.getExposedServers());

            // Candidate servers we may expose, and the servers this peer advertises THROUGH to us
            // (right-hand column of the per-link view); only computed for untrusted peers to keep
            // the trusted-peer payload small.
            b.append(",\"exposableServers\":");
            strings(b, p.isUntrusted() ? mgr.exposableServers(p.getDomain()) : List.of());

            b.append(",\"advertisedVia\":");
            Set<String> via = new LinkedHashSet<>();
            if (p.isUntrusted()) {
                for (FederatedRoom room : mgr.getRoomManager().getRemoteRoomsViaPeer(p.getDomain())) {
                    via.add(room.originServer());
                }
            }
            strings(b, via);

            // Destinations currently routed THROUGH this peer, and the destinations whose
            // advertisements from this peer the admin has denied (per-link inbound filter).
            List<String> through = new ArrayList<>();
            for (RouteEntry r : mgr.getRoutingTable().getAll()) {
                if (r.nextHop().equals(p.getDomain()) && !r.destination().equals(p.getDomain())) {
                    through.add(r.destination());
                }
            }
            b.append(",\"advertisedRoutes\":");
            strings(b, through);
            b.append(",\"deniedRoutes\":");
            strings(b, p.getDeniedRoutes());
            b.append("}");
        });
        sb.append(",");

        // ── routing (local domain excluded) ───────────────────────────────────
        // Denied advertisements stay listed as disabled entries (never installed as
        // real routes) so the admin can see and lift the deny even while the peer
        // isn't currently advertising the destination.
        List<String> routes = new ArrayList<>();
        for (RouteEntry r : mgr.getRoutingTable().getAll()) {
            if (r.destination().equals(localDomain)) continue;
            routes.add("{\"destination\":\"" + esc(r.destination()) + "\","
                     + "\"nextHop\":\"" + esc(r.nextHop()) + "\","
                     + "\"hops\":" + r.hops() + ","
                     + "\"updatedAt\":" + r.updatedAt() + "}");
        }
        for (PeerServer p : mgr.getPeerRegistry().getPeers()) {
            for (String dest : p.getDeniedRoutes()) {
                if (dest.equals(localDomain)) continue;
                routes.add("{\"destination\":\"" + esc(dest) + "\","
                         + "\"nextHop\":\"" + esc(p.getDomain()) + "\","
                         + "\"denied\":true}");
            }
        }
        sb.append("\"routing\":");
        array(sb, routes, StringBuilder::append);
        sb.append(",");

        // ── local rooms ───────────────────────────────────────────────────────
        sb.append("\"localRooms\":");
        array(sb, mgr.getRoomManager().getAllLocalRoomsWithTag(), (b, room) -> {
            @SuppressWarnings("unchecked")
            List<Map<String, String>> roomMappings = (List<Map<String, String>>) room.get("mappings");
            @SuppressWarnings("unchecked")
            List<String> visTo = (List<String>) room.getOrDefault("visibleTo", Collections.emptyList());

            b.append("{")
             .append("\"jid\":\"").append(esc(str(room.get("jid")))).append("\",")
             .append("\"name\":\"").append(esc(str(room.get("name")))).append("\",")
             .append("\"description\":\"").append(esc(str(room.get("description")))).append("\",")
             .append("\"federated\":").append(room.get("federated")).append(",")
             .append("\"autoAccept\":").append(room.get("autoAccept")).append(",")
             .append("\"filesEnabled\":").append(room.get("filesEnabled")).append(",")
             .append("\"occupants\":").append(room.get("occupants")).append(",")
             .append("\"visibleTo\":");
            strings(b, visTo);

            // Occupant roster (local + remote virtual) with live presence for the tracking view.
            b.append(",\"occupantList\":");
            array(b, mgr.getRoomOccupants(str(room.get("jid"))), (ob, occ) ->
                ob.append("{\"name\":\"").append(esc(occ.get("name")))
                  .append("\",\"jid\":\"").append(esc(occ.get("jid")))
                  .append("\",\"kind\":\"").append(esc(occ.get("kind")))
                  .append("\",\"show\":\"").append(esc(occ.get("show")))
                  .append("\",\"status\":\"").append(esc(occ.get("status"))).append("\"}"));

            b.append(",\"mappings\":");
            array(b, roomMappings, (mb, m) -> {
                // pathBroken: a route exists but end-to-end probes go unanswered (deny mid-path).
                boolean pathBroken = mgr.getRoutingTable().isReachable(m.get("remoteDomain"))
                        && mgr.isMappingPathBroken(m.get("remoteDomain"));
                boolean connected = mgr.getRoutingTable().isReachable(m.get("remoteDomain")) && !pathBroken;
                boolean routeMissing = !connected && !pathBroken
                        && mgr.getPeerRegistry().getPeer(m.get("remoteDomain")).isEmpty();
                mb.append("{")
                  .append("\"remoteRoomJid\":\"").append(esc(m.get("remoteRoomJid"))).append("\",")
                  .append("\"remoteDomain\":\"").append(esc(m.get("remoteDomain"))).append("\",")
                  .append("\"state\":\"").append(esc(m.get("state"))).append("\",")
                  .append("\"connected\":").append(connected).append(",")
                  .append("\"routeMissing\":").append(routeMissing).append(",")
                  .append("\"pathBroken\":").append(pathBroken).append(",")
                  .append("\"pingMs\":").append(mgr.getMappingPingRttMs(m.get("remoteDomain"))).append(",")
                  .append("\"pongAgeSecs\":").append(mgr.getMappingPongAgeSecs(m.get("remoteDomain")))
                  .append("}");
            });
            b.append("}");
        });
        sb.append(",");

        // ── incoming pending mapping requests (for the Pending requests panel) ──
        sb.append("\"pendingRequests\":");
        array(sb, mgr.getRoomManager().getMappingsByState(RoomMapping.State.PENDING_IN), (b, m) ->
            b.append("{")
             .append("\"localJid\":\"").append(esc(m.localRoomJid())).append("\",")
             .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
             .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\"}"));
        sb.append(",");

        // ── remote rooms ──────────────────────────────────────────────────────
        sb.append("\"remoteRooms\":");
        objectOfArrays(sb, mgr.getRoomManager().getRemoteRoomsVisibleToUs(), (b, room) ->
            b.append("{")
             .append("\"jid\":\"").append(esc(room.jid())).append("\",")
             .append("\"name\":\"").append(esc(room.name())).append("\",")
             .append("\"description\":\"").append(esc(room.description())).append("\"")
             .append("}"));
        sb.append(",");

        // ── room mappings (localJid → array of {remoteRoomJid, remoteDomain}) ─
        sb.append("\"mappings\":");
        objectOfArrays(sb, mgr.getRoomManager().getLocalMappings(), (b, m) -> {
            boolean pathBroken = mgr.getRoutingTable().isReachable(m.remoteDomain())
                    && mgr.isMappingPathBroken(m.remoteDomain());
            boolean connected = mgr.getRoutingTable().isReachable(m.remoteDomain()) && !pathBroken;
            boolean routeMissing = !connected && !pathBroken
                    && mgr.getPeerRegistry().getPeer(m.remoteDomain()).isEmpty();
            b.append("{")
             .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
             .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\",")
             .append("\"connected\":").append(connected).append(",")
             .append("\"routeMissing\":").append(routeMissing).append(",")
             .append("\"pathBroken\":").append(pathBroken).append(",")
             .append("\"pingMs\":").append(mgr.getMappingPingRttMs(m.remoteDomain())).append(",")
             .append("\"pongAgeSecs\":").append(mgr.getMappingPongAgeSecs(m.remoteDomain()))
             .append("}");
        });
        sb.append(",");

        // ── active S2S sessions ───────────────────────────────────────────────
        sb.append("\"s2sSessions\":");
        array(sb, mgr.getS2SSessions(), (b, sess) ->
            b.append("{")
             .append("\"domain\":\"").append(esc(str(sess.get("domain")))).append("\",")
             .append("\"direction\":\"").append(esc(str(sess.get("direction")))).append("\",")
             .append("\"since\":").append(sess.get("since")).append(",")
             .append("\"encrypted\":").append(sess.get("encrypted")).append(",")
             .append("\"fedPeer\":").append(sess.get("fedPeer"))
             .append("}"));
        sb.append(",");

        // ── settings ──────────────────────────────────────────────────────────
        sb.append("\"keepaliveSeconds\":").append(mgr.getKeepaliveSeconds()).append(",");
        sb.append("\"effectiveKeepaliveSeconds\":").append(mgr.getEffectiveKeepaliveSeconds()).append(",");
        sb.append("\"reconnectSeconds\":").append(mgr.getReconnectSeconds()).append(",");
        sb.append("\"mappingPingSeconds\":").append(mgr.getMappingPingSeconds()).append(",");
        sb.append("\"peerAllowlist\":").append(FederationProperties.PEER_ALLOWLIST.getValue()).append(",");
        sb.append("\"allowRemoteRoomTraversal\":").append(FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL.getValue()).append(",");
        sb.append("\"directMsgRelay\":").append(FederationProperties.DIRECT_MSG_RELAY.getValue()).append(",");
        sb.append("\"directoryPublish\":").append(FederationProperties.DIRECTORY_PUBLISH.getValue()).append(",");
        sb.append("\"bookmarkPush\":").append(FederationProperties.BOOKMARK_PUSH.getValue()).append(",");
        sb.append("\"probeOnSubscribe\":").append(FederationProperties.PROBE_ON_SUBSCRIBE.getValue()).append(",");
        sb.append("\"filesEnabled\":").append(FederationProperties.FILES_ENABLED.getValue()).append(",");
        sb.append("\"filesMaxSizeMB\":").append(FederationProperties.FILES_MAX_MB.getValue()).append(",");
        sb.append("\"filesRetentionDays\":").append(FederationProperties.FILES_RETENTION_DAYS.getValue()).append(",");
        sb.append("\"filesStorageDir\":\"").append(esc(FederationProperties.FILES_STORAGE_DIR.getValue())).append("\",");
        sb.append("\"filesAllowedExtensions\":\"").append(esc(FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue())).append("\",");
        sb.append("\"filesAvEnabled\":").append(FederationProperties.FILES_AV_ENABLED.getValue()).append(",");
        sb.append("\"filesAvHost\":\"").append(esc(FederationProperties.FILES_AV_HOST.getValue())).append("\",");
        sb.append("\"filesAvPort\":").append(FederationProperties.FILES_AV_PORT.getValue()).append(",");
        sb.append("\"filesAvTimeoutMs\":").append(FederationProperties.FILES_AV_TIMEOUT_MS.getValue()).append(",");
        sb.append("\"filesLogRetentionDays\":").append(FederationProperties.FILES_LOG_RETENTION_DAYS.getValue()).append(",");
        sb.append("\"filesScanLogPath\":\"")
          .append(mgr.getFileRelay() == null ? "" : esc(mgr.getFileRelay().scanLogPath())).append("\",");
        sb.append("\"filesRejectionLogPath\":\"")
          .append(mgr.getFileRelay() == null ? "" : esc(mgr.getFileRelay().rejectionLogPath())).append("\",");

        // ── AV scan log (files recently examined by ClamAV, newest first) ───────
        sb.append("\"avScanLog\":");
        array(sb, mgr.getFileRelay() == null ? List.<FileRelayManager.ScanLogEntry>of()
                                             : mgr.getFileRelay().recentAvScans(), (b, e) ->
            b.append("{")
             .append("\"when\":").append(e.when()).append(",")
             .append("\"name\":\"").append(esc(e.fileName())).append("\",")
             .append("\"size\":").append(e.sizeBytes()).append(",")
             .append("\"origin\":\"").append(esc(e.origin())).append("\",")
             .append("\"verdict\":\"").append(esc(e.verdict())).append("\",")
             .append("\"detail\":\"").append(esc(e.detail())).append("\"")
             .append("}"));
        sb.append(",");

        // ── Rejected files (extension/content/hash/AV — egress + ingress, newest first) ──
        sb.append("\"rejectedFiles\":");
        array(sb, mgr.getFileRelay() == null ? List.<FileRelayManager.RejectionEntry>of()
                                             : mgr.getFileRelay().recentRejections(), (b, e) ->
            b.append("{")
             .append("\"when\":").append(e.when()).append(",")
             .append("\"name\":\"").append(esc(e.fileName())).append("\",")
             .append("\"size\":").append(e.sizeBytes()).append(",")
             .append("\"origin\":\"").append(esc(e.origin())).append("\",")
             .append("\"stage\":\"").append(esc(e.stage())).append("\",")
             .append("\"reason\":\"").append(esc(e.reason())).append("\",")
             .append("\"detail\":\"").append(esc(e.detail())).append("\"")
             .append("}"));
        sb.append(",");

        // ── file-based config (openfire.xml <federation> block) ─────────────────
        FederationFileConfig.IngestResult fcResult = mgr.getFileConfig().lastResult();
        Long fcLoadedAt = mgr.getFileConfig().lastLoadedAtMillis();
        sb.append("\"fileConfig\":{")
          .append("\"path\":\"").append(esc(mgr.getFileConfig().configFile().toString())).append("\",")
          .append("\"loadedAt\":").append(fcLoadedAt == null ? "null" : fcLoadedAt).append(",")
          .append("\"blockPresent\":").append(fcResult.blockPresent()).append(",")
          .append("\"peersAdded\":").append(fcResult.peersAdded()).append(",")
          .append("\"peersUpdated\":").append(fcResult.peersUpdated()).append(",")
          .append("\"roomsUpdated\":").append(fcResult.roomsUpdated()).append(",")
          .append("\"mappingsRequested\":").append(fcResult.mappingsRequested()).append(",")
          .append("\"settingsUpdated\":").append(fcResult.settingsUpdated()).append(",")
          .append("\"warnings\":");
        strings(sb, fcResult.warnings());
        sb.append("},");

        // ── this server's connected clients (local online users) ───────────────
        sb.append("\"localUsers\":");
        array(sb, mgr.getUserDirectory().localOnlineUsers(), FederationApiServlet::appendPresence);
        sb.append(",");

        // ── user directory (origin server domain → [{jid,show,status}]) ────────
        sb.append("\"directory\":");
        objectOfArrays(sb, mgr.getUserDirectory().getRemoteUsers(), FederationApiServlet::appendPresence);
        sb.append(",");

        // ── bookmarks advertised to us by peers (origin domain → [jid]) ────────
        sb.append("\"advertisedBookmarks\":");
        objectOfArrays(sb, mgr.getBookmarkInjector().getAdvertised(),
                       (b, jid) -> b.append("\"").append(esc(jid)).append("\""));
        sb.append(",");

        // ── default-settings rules for newly-created rooms (by name pattern) ───
        sb.append("\"roomDefaults\":");
        array(sb, mgr.getRoomDefaults().getRules(), (b, r) -> {
            b.append("{\"pattern\":\"").append(esc(r.pattern()))
             .append("\",\"federated\":").append(r.federated())
             .append(",\"autoAccept\":").append(r.autoAccept())
             .append(",\"autoMap\":").append(r.autoMap())
             .append(",\"filesEnabled\":").append(r.filesEnabled())
             .append(",\"visible\":");
            strings(b, r.visible());
            b.append("}");
        });

        sb.append("}");
        return sb.toString();
    }

    // ── JSON assembly ────────────────────────────────────────────────────────
    //
    // The status document is written straight into a StringBuilder rather than built as a tree:
    // it is produced on nearly every long-poll, so the allocation matters. These three helpers
    // own all the separator bookkeeping, which is why nothing above tracks "is this the first
    // element" by hand.

    /** Appends {@code [e1,e2,…]}, delegating each element to {@code writer}. */
    private static <T> void array(StringBuilder sb, Iterable<T> values, BiConsumer<StringBuilder, T> writer) {
        sb.append("[");
        boolean first = true;
        for (T v : values) {
            if (!first) sb.append(",");
            first = false;
            writer.accept(sb, v);
        }
        sb.append("]");
    }

    /** {@link #array} for a plain list of strings, each quoted and JSON-escaped. */
    private static void strings(StringBuilder sb, Iterable<String> values) {
        array(sb, values, (b, v) -> b.append("\"").append(esc(v)).append("\""));
    }

    /** Appends {@code {"k1":[…],"k2":[…]}} — the shape of the domain-keyed maps the page renders. */
    private static <T> void objectOfArrays(StringBuilder sb, Map<String, ? extends Iterable<T>> map,
                                           BiConsumer<StringBuilder, T> writer) {
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, ? extends Iterable<T>> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(e.getKey())).append("\":");
            array(sb, e.getValue(), writer);
        }
        sb.append("}");
    }

    /** The {@code {jid,show,status}} element shared by the local-user list and the remote directory. */
    private static void appendPresence(StringBuilder sb, UserDirectory.UserPresence u) {
        sb.append("{\"jid\":\"").append(esc(u.jid()))
          .append("\",\"show\":\"").append(esc(u.show()))
          .append("\",\"status\":\"").append(esc(u.status())).append("\"}");
    }


    /**
     * Dispatches one admin action. Each action answers with exactly one JSON document — either
     * {@code {"ok":true,…}} or {@code {"error":"…"}} — so the page can branch on those two keys
     * without knowing the action. The work itself lives in the {@code handle*} groups below, each
     * of which returns {@code null} for an action it doesn't own so the next group gets a look.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");

        PrintWriter out = resp.getWriter();

        // CSRF (double-submit): the page echoes the fed-csrf cookie as a parameter. A cross-site
        // attacker can't read the cookie (same-origin) nor forge a matching parameter, so a
        // forged POST from an admin's browser is rejected even though the admin session is valid.
        if (!validateCsrf(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.print(error("CSRF validation failed"));
            return;
        }

        FederationPlugin plugin = FederationPlugin.getInstance();
        if (plugin == null) {
            out.print(error("Plugin not loaded"));
            return;
        }

        FederationManager mgr = plugin.getManager();
        String action = req.getParameter("action");
        if (action == null) action = "";

        String reply = peerAction(action, req, mgr);
        if (reply == null) reply = roomAction(action, req, mgr);
        if (reply == null) reply = mappingAction(action, req, mgr);
        if (reply == null) reply = fileAction(action, req, mgr);
        if (reply == null) reply = settingAction(action, req, mgr);
        if (reply == null) reply = error("unknown action");
        out.print(reply);
    }

    // ── Action groups ────────────────────────────────────────────────────────
    //
    // Every handler returns the JSON to send, or null when the action isn't one of its own.

    /** Peer lifecycle, per-link trust, and the routes/servers a peer is allowed to advertise. */
    private String peerAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "add-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!isPlausibleDomain(d)) return error("invalid domain");
                mgr.addPeer(d);
                // Default the untrusted flag from the parent-domain rule when the caller didn't
                // specify it (the UI always sends the checkbox; this guards API callers).
                String untrustedParam = req.getParameter("untrusted");
                boolean untrusted = untrustedParam != null
                        ? Boolean.parseBoolean(untrustedParam.strip())
                        : mgr.isForeignDomain(d);
                if (untrusted) {
                    mgr.getPeerRegistry().setUntrusted(d, true);
                    mgr.applyLocalTrustChange(d);   // announce our untrusted stance to the peer
                }
                return OK;
            }
            case "remove-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                // Reports false for a domain that wasn't a peer, so the page can say so.
                return "{\"ok\":" + mgr.removePeer(d) + "}";
            }
            case "disable-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                mgr.disablePeer(d);
                return OK;
            }
            case "enable-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                mgr.enablePeer(d);
                return OK;
            }
            case "retry-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.retryPeer(d);
                return OK;
            }
            case "kill-session": {
                String d = domainParam(req, "domain");
                String direction = req.getParameter("direction");
                if (d == null || direction == null) return required("domain", "direction");
                mgr.killSession(d, direction.strip().toLowerCase());
                return OK;
            }
            case "accept-cert": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.acceptNewCertificate(d);
                return OK;
            }
            case "set-untrusted": {
                String d = domainParam(req, "domain");
                String untrusted = req.getParameter("untrusted");
                if (d == null || untrusted == null) return required("domain", "untrusted");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.getPeerRegistry().setUntrusted(d, Boolean.parseBoolean(untrusted.strip()));
                // Negotiate the new stance over the link: announce it, and block or re-push
                // immediately based on the remote's last-known stance (trust is per-link).
                mgr.applyLocalTrustChange(d);
                return OK;
            }
            case "set-exposed-servers": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                // Constrain to the legitimate candidate set: this server plus destinations not
                // reachable via the peer itself. This drops the peer's own domain / echoed servers
                // as defense-in-depth, so the persisted set stays meaningful.
                Set<String> allowed = mgr.exposableServers(d);
                List<String> list = new ArrayList<>();
                for (String srv : csvDomains(req, "servers")) {
                    if (allowed.contains(srv)) list.add(srv);
                }
                mgr.getPeerRegistry().setExposedServers(d, list);
                // Re-advertise the new exposed set + matching routes immediately.
                if (isReachable(mgr, d)) {
                    mgr.sendRoutingUpdate(d);
                    mgr.sendRoomState(d);
                }
                return OK;
            }
            case "deny-route": case "allow-route": {
                String d = domainParam(req, "domain");             // the advertising peer
                String dest = domainParam(req, "destination");     // the advertised server
                if (d == null || dest == null) return required("domain", "destination");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
                if (dest.equals(localDomain) || dest.equals(d)) {
                    return error("cannot deny the peer itself or this server");
                }
                if ("deny-route".equals(action)) mgr.denyRouteFromPeer(d, dest);
                else                             mgr.allowRouteFromPeer(d, dest);
                return OK;
            }
            default:
                return null;
        }
    }

    /** Per-room federation settings and the pattern-matched defaults applied to new rooms. */
    private String roomAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "set-room": {
                String jid = param(req, "jid");
                String fedParam = req.getParameter("federated");
                if (jid == null || fedParam == null) return required("jid", "federated");
                mgr.setRoomFederated(jid, Boolean.parseBoolean(fedParam));
                return OK;
            }
            case "set-room-visibility": {
                String jid = param(req, "jid");
                if (jid == null) return required("jid");
                mgr.setRoomVisibility(jid, csvDomains(req, "servers"));   // empty list = all
                return OK;
            }
            case "set-room-autoaccept": {
                String jid = param(req, "jid");
                String enable = req.getParameter("autoAccept");
                if (jid == null || enable == null) return required("jid", "autoAccept");
                mgr.getRoomManager().setAutoAccept(jid, Boolean.parseBoolean(enable.strip()));
                return OK;
            }
            case "set-room-files": {
                String jid = param(req, "jid");
                String enable = req.getParameter("filesEnabled");
                if (jid == null || enable == null) return required("jid", "filesEnabled");
                mgr.getRoomManager().setFilesEnabled(jid, Boolean.parseBoolean(enable.strip()));
                return OK;
            }
            case "save-room-default": {
                String pattern = param(req, "pattern");
                if (pattern == null) return required("pattern");
                boolean federated  = Boolean.parseBoolean(req.getParameter("federated"));
                boolean autoAccept = Boolean.parseBoolean(req.getParameter("autoAccept"));
                boolean autoMap    = Boolean.parseBoolean(req.getParameter("autoMap"));
                String filesParam  = req.getParameter("filesEnabled");
                boolean filesEnabled = filesParam == null || Boolean.parseBoolean(filesParam.strip()); // default on
                mgr.getRoomDefaults().save(pattern, federated, autoAccept,
                                           csvDomains(req, "visible"), autoMap, filesEnabled);
                return OK;
            }
            case "delete-room-default": {
                String pattern = param(req, "pattern");
                if (pattern == null) return required("pattern");
                mgr.getRoomDefaults().delete(pattern);
                return OK;
            }
            case "apply-room-defaults-now":
                return ok("applied", mgr.applyDefaultsToAllRooms());
            default:
                return null;
        }
    }

    /** Requesting, answering, suspending and tearing down local-room ↔ remote-room mappings. */
    private String mappingAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "map-room": {
                String localJid = param(req, "localJid");
                String remoteJid = param(req, "remoteJid");
                String remoteDomain = domainParam(req, "remoteDomain");
                if (localJid == null || remoteJid == null || remoteDomain == null) {
                    return error("localJid, remoteJid, remoteDomain required");
                }
                // Reciprocity: you may only map your room to a peer's room if you've shared THAT room
                // with the peer (federation enabled + the peer is in the room's visibility ACL).
                if (!mgr.roomSharedWith(localJid, remoteDomain)) {
                    return error("You must share this room with " + remoteDomain
                            + " before mapping it to a room there. Enable federation on the room and add "
                            + remoteDomain + " to its visibility.");
                }
                mgr.requestMapping(localJid, remoteJid, remoteDomain);
                return OK;
            }
            case "accept-mapping": case "reject-mapping":
            case "disable-mapping": case "enable-mapping": {
                String localJid = param(req, "localJid");
                String remoteDomain = domainParam(req, "remoteDomain");
                if (localJid == null || remoteDomain == null) return required("localJid", "remoteDomain");
                switch (action) {
                    case "accept-mapping"  -> mgr.acceptMapping(localJid, remoteDomain);
                    case "reject-mapping"  -> mgr.rejectMapping(localJid, remoteDomain);
                    case "disable-mapping" -> mgr.disableMapping(localJid, remoteDomain);
                    case "enable-mapping"  -> mgr.enableMapping(localJid, remoteDomain);
                }
                return OK;
            }
            case "unmap-room": {
                String localJid = param(req, "localJid");
                if (localJid == null) return required("localJid");
                String remoteDomain = param(req, "remoteDomain");
                if (remoteDomain != null) mgr.unmapRoom(localJid, remoteDomain);   // just this peer
                else                      mgr.unmapRooms(localJid);                // every mapping
                return OK;
            }
            default:
                return null;
        }
    }

    /** File-relay switches: the global gate, size/retention limits, storage, and the AV scanner. */
    private String fileAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "set-files-enabled":
                return applyToggle(req, FederationProperties.FILES_ENABLED, "filesEnabled");
            case "set-files-av-enabled":
                return applyToggle(req, FederationProperties.FILES_AV_ENABLED, "filesAvEnabled");
            case "set-files-max-size":
                return applyBoundedInt(req, "mb", FederationProperties.FILES_MAX_MB,
                                       "filesMaxSizeMB", 1, Integer.MAX_VALUE, "mb must be at least 1");
            case "set-files-retention":
                return applyBoundedInt(req, "days", FederationProperties.FILES_RETENTION_DAYS,
                                       "filesRetentionDays", 1, Integer.MAX_VALUE, "days must be at least 1");
            case "set-files-av-port":
                return applyBoundedInt(req, "port", FederationProperties.FILES_AV_PORT,
                                       "filesAvPort", 1, 65535, "port must be between 1 and 65535");
            case "set-files-av-timeout":
                return applyBoundedInt(req, "ms", FederationProperties.FILES_AV_TIMEOUT_MS,
                                       "filesAvTimeoutMs", MIN_AV_TIMEOUT_MS, Integer.MAX_VALUE,
                                       "timeout must be at least " + MIN_AV_TIMEOUT_MS + " ms");
            case "set-files-log-retention": {
                String reply = applyBoundedInt(req, "days", FederationProperties.FILES_LOG_RETENTION_DAYS,
                                               "filesLogRetentionDays", 1, Integer.MAX_VALUE,
                                               "days must be at least 1");
                // Apply the new window now, so a shortened retention takes visible effect
                // instead of waiting for the next six-hourly prune.
                if (isOk(reply) && mgr.getFileRelay() != null) mgr.getFileRelay().logRetentionChanged();
                return reply;
            }
            case "set-files-av-host": {
                String host = param(req, "host");
                if (host == null) return required("host");
                FederationProperties.FILES_AV_HOST.setValue(host);
                return okText("filesAvHost", FederationProperties.FILES_AV_HOST.getValue());
            }
            case "set-files-allowed-extensions": {
                String extensions = req.getParameter("extensions");
                if (extensions == null) return required("extensions");
                FederationProperties.FILES_ALLOWED_EXTENSIONS.setValue(extensions.strip());
                return okText("filesAllowedExtensions",
                              FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue());
            }
            case "set-files-storage-dir": {
                String dir = param(req, "dir");
                if (dir == null) return required("dir");
                if (!Path.of(dir).isAbsolute()) {
                    return error("dir must be a full path (e.g. /var/lib/openfire/federation-files)");
                }
                String previous = FederationProperties.FILES_STORAGE_DIR.getValue();
                FederationProperties.FILES_STORAGE_DIR.setValue(dir);
                // Move the store now; if the new directory is unusable, roll the property back
                // so the UI keeps showing where files are actually served from.
                String moveError = mgr.getFileRelay() != null ? mgr.getFileRelay().storageDirChanged() : null;
                if (moveError != null) {
                    FederationProperties.FILES_STORAGE_DIR.setValue(previous);
                    return error(moveError);
                }
                return okText("filesStorageDir", FederationProperties.FILES_STORAGE_DIR.getValue());
            }
            case "test-av-connection":
                return ok("reachable", mgr.getFileRelay() != null && mgr.getFileRelay().testAvConnection());
            default:
                return null;
        }
    }

    /** Server-wide federation switches, timers, and the one-shot pushes the admin can trigger. */
    private String settingAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "set-allowlist":
                return applyToggle(req, FederationProperties.PEER_ALLOWLIST, "peerAllowlist");
            case "set-allow-traversal":
                return applyToggle(req, FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL,
                                   "allowRemoteRoomTraversal");
            case "set-direct-relay":
                return applyToggle(req, FederationProperties.DIRECT_MSG_RELAY, "directMsgRelay");
            case "set-probe-on-subscribe":
                return applyToggle(req, FederationProperties.PROBE_ON_SUBSCRIBE, "probeOnSubscribe");
            case "set-directory-publish": {
                String reply = applyToggle(req, FederationProperties.DIRECTORY_PUBLISH, "directoryPublish");
                // Push (or, when turning off, withdraw with an empty list) to peers immediately.
                if (isOk(reply)) mgr.publishDirectory();
                return reply;
            }
            case "set-bookmark-push": {
                String reply = applyToggle(req, FederationProperties.BOOKMARK_PUSH, "bookmarkPush");
                // Push (or, when turning off, withdraw with an empty list) to peers immediately.
                if (isOk(reply)) mgr.pushBookmarks();
                return reply;
            }
            case "push-bookmarks":
                // Manual one-shot advertisement of our connected clients (independent of the toggle).
                mgr.pushBookmarksNow();
                return OK;
            case "set-keepalive": {
                Integer seconds = intParam(req, "seconds");
                if (seconds == null) return secondsError(req);
                mgr.setKeepaliveSeconds(seconds);
                return ok("keepaliveSeconds", mgr.getKeepaliveSeconds());
            }
            case "set-reconnect": {
                Integer seconds = intParam(req, "seconds");
                if (seconds == null) return secondsError(req);
                mgr.setReconnectSeconds(seconds);
                return ok("reconnectSeconds", mgr.getReconnectSeconds());
            }
            case "set-mapping-ping": {
                Integer seconds = intParam(req, "seconds");
                if (seconds == null) return secondsError(req);
                mgr.setMappingPingSeconds(seconds);
                return ok("mappingPingSeconds", mgr.getMappingPingSeconds());
            }
            case "reload-fed-config": {
                FederationFileConfig.IngestResult r = mgr.reloadConfigFile();
                StringBuilder sb = new StringBuilder();
                sb.append("{\"ok\":true,")
                  .append("\"blockPresent\":").append(r.blockPresent()).append(",")
                  .append("\"peersAdded\":").append(r.peersAdded()).append(",")
                  .append("\"peersUpdated\":").append(r.peersUpdated()).append(",")
                  .append("\"roomsUpdated\":").append(r.roomsUpdated()).append(",")
                  .append("\"mappingsRequested\":").append(r.mappingsRequested()).append(",")
                  .append("\"settingsUpdated\":").append(r.settingsUpdated()).append(",")
                  .append("\"warnings\":[");
                boolean first = true;
                for (String w : r.warnings()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(esc(w)).append("\"");
                }
                sb.append("]}");
                return sb.toString();
            }
            default:
                return null;
        }
    }

    // ── JSON replies ─────────────────────────────────────────────────────────

    /** The reply for an action that succeeded and has nothing to report back. */
    private static final String OK = "{\"ok\":true}";

    /** {@code {"ok":true,"<key>":<value>}} — for numbers and booleans, which need no quoting. */
    private static String ok(String key, Object value) {
        return "{\"ok\":true,\"" + key + "\":" + value + "}";
    }

    /** {@code {"ok":true,"<key>":"<value>"}}, with the value JSON-escaped. */
    private static String okText(String key, String value) {
        return "{\"ok\":true,\"" + key + "\":\"" + esc(value) + "\"}";
    }

    /**
     * {@code {"error":"<message>"}}, with the message JSON-escaped — so a message may safely quote
     * admin- or peer-supplied text (a domain, a path, a filesystem error) without breaking the reply.
     */
    private static String error(String message) {
        return "{\"error\":\"" + esc(message) + "\"}";
    }

    /** The missing-parameter reply: {@code required("a", "b")} → {@code {"error":"a and b required"}}. */
    private static String required(String... names) {
        return error(String.join(" and ", names) + " required");
    }

    /** True when {@code reply} reports success — the gate for a side effect that must not run on error. */
    private static boolean isOk(String reply) {
        return reply.startsWith("{\"ok\":true");
    }

    // ── Request parameters ───────────────────────────────────────────────────

    /** Trimmed value of {@code name}, or null when the parameter is absent or blank. */
    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** {@link #param} lowercased: domains are stored and compared in lower case throughout. */
    private static String domainParam(HttpServletRequest req, String name) {
        String v = param(req, name);
        return v == null ? null : v.toLowerCase();
    }

    /** Integer value of {@code name}, or null when it is absent, blank, or not a number. */
    private static Integer intParam(HttpServletRequest req, String name) {
        String v = param(req, name);
        if (v == null) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Comma-separated domains, lowercased and blank-free. An absent parameter yields an empty list. */
    private static List<String> csvDomains(HttpServletRequest req, String name) {
        List<String> list = new ArrayList<>();
        String raw = req.getParameter(name);
        if (raw == null) return list;
        for (String s : raw.split(",")) {
            if (!s.isBlank()) list.add(s.strip().toLowerCase());
        }
        return list;
    }

    /** Tells "you didn't send it" apart from "what you sent isn't a number" for a `seconds` parameter. */
    private static String secondsError(HttpServletRequest req) {
        return param(req, "seconds") == null ? required("seconds") : error("seconds must be an integer");
    }

    // ── Property writers ─────────────────────────────────────────────────────

    /**
     * Applies the {@code enabled} parameter to a boolean property and answers with the value that
     * was actually stored, so the page renders what persisted rather than what it sent.
     */
    private static String applyToggle(HttpServletRequest req, SystemProperty<Boolean> prop, String jsonKey) {
        String enabled = req.getParameter("enabled");
        if (enabled == null) return required("enabled");
        prop.setValue(Boolean.parseBoolean(enabled.strip()));
        return ok(jsonKey, prop.getValue());
    }

    /**
     * Applies an integer parameter to a property once it is present, parseable, and inside
     * {@code [min, max]}; otherwise answers with the matching error and leaves the property alone.
     * {@code rangeMessage} is the operator-facing wording for an out-of-range value, which differs
     * per setting ("port must be between 1 and 65535", "days must be at least 1", …).
     */
    private static String applyBoundedInt(HttpServletRequest req, String name, SystemProperty<Integer> prop,
                                          String jsonKey, int min, int max, String rangeMessage) {
        if (param(req, name) == null) return required(name);
        Integer value = intParam(req, name);
        if (value == null) return error(name + " must be an integer");
        if (value < min || value > max) return error(rangeMessage);
        prop.setValue(value);
        return ok(jsonKey, prop.getValue());
    }

    /** Smallest AV scan timeout that leaves clamd room to answer; below this the scan just flaps. */
    private static final int MIN_AV_TIMEOUT_MS = 1000;

    private static final String CSRF_COOKIE = "fed-csrf";

    /** Issues a readable (non-HttpOnly) CSRF token cookie if the client doesn't have one yet. */
    private void ensureCsrfCookie(HttpServletRequest req, HttpServletResponse resp) {
        Cookie existing = CookieUtils.getCookie(req, CSRF_COOKIE);
        if (existing != null && existing.getValue() != null && !existing.getValue().isEmpty()) return;
        Cookie c = new Cookie(CSRF_COOKIE, StringUtils.randomString(24));
        c.setPath("/");        // available to the whole admin origin (page + /api)
        c.setMaxAge(-1);       // session cookie
        // Deliberately NOT HttpOnly: the double-submit pattern needs the page JS to read it.
        resp.addCookie(c);
    }

    /** Double-submit check: the 'csrf' POST parameter must equal the fed-csrf cookie. */
    private boolean validateCsrf(HttpServletRequest req) {
        Cookie c = CookieUtils.getCookie(req, CSRF_COOKIE);
        String param = req.getParameter("csrf");
        return c != null && c.getValue() != null && !c.getValue().isEmpty()
            && c.getValue().equals(param);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    // Escape any remaining control character so peer-supplied strings (presence
                    // status/show, room names, JIDs) can never produce invalid JSON in the admin feed.
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else          b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /**
     * Minimal syntactic check for an admin-entered peer domain: lowercase host characters only
     * (letters, digits, {@code . - _}). Single-label lab hostnames like {@code 2501} are allowed;
     * quotes, whitespace, and angle brackets — which could break the status JSON or the admin UI's
     * inline handlers if persisted and rendered — are rejected. Not a full hostname validation.
     */
    private static boolean isPlausibleDomain(String d) {
        if (d == null || d.isEmpty() || d.length() > 253) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                       || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** True if the peer currently has a live (REACHABLE) federation link. */
    private boolean isReachable(FederationManager mgr, String domain) {
        return mgr.getPeerRegistry().getPeer(domain)
                  .map(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                  .orElse(false);
    }
}
