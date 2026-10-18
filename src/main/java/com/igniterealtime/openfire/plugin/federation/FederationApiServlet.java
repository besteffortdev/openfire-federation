package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.files.FileRelayManager;
import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import com.igniterealtime.openfire.plugin.federation.protocol.RedundantArrivals;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jivesoftware.util.CookieUtils;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.isPlausibleDomain;
import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * JSON API behind the federation admin console. A GET returns the full status document, optionally
 * as a long poll that waits for a change; a POST runs one admin action, guarded by a CSRF token.
 */
public class FederationApiServlet extends HttpServlet {

    private static final Logger Log = LoggerFactory.getLogger(FederationApiServlet.class);

    /** What the page is told when a request fails unexpectedly; the details go to the Openfire log only. */
    private static final String INTERNAL_ERROR = "Internal error; see the Openfire log for details";

    /** Answers with the status document, or with a generic error when building it fails. */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");
        ensureCsrfCookie(req, resp);

        FederationPlugin plugin = FederationPlugin.getInstance();
        PrintWriter out = resp.getWriter();

        if (plugin == null) {
            out.print(error("Plugin not loaded"));
            return;
        }

        try {
            boolean poll = "poll".equals(req.getParameter("action"));
            out.print(poll ? longPoll(req.getParameter("hash")) : buildStatusJson(plugin));
        } catch (RuntimeException e) {
            Log.error("Admin API: could not build the status document", e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(error(INTERNAL_ERROR));
        }
    }

    /** How long a poll request is held open before answering "unchanged". */
    private static final long POLL_TIMEOUT_MS = 25_000;
    /** How often a held request re-checks the state for a change. */
    private static final long POLL_CHECK_MS = 1_000;
    /** Each held request occupies an admin-console thread; extra tabs degrade to plain polling. */
    private static final int POLL_MAX_HELD = 8;
    private static final AtomicInteger heldPolls = new AtomicInteger();

    /**
     * Holds the request until the status no longer matches {@code clientHash} or the timeout passes,
     * then answers with the status JSON and its new fingerprint under {@code hash}.
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

    /**
     * A truncated SHA-256 of the status JSON, ignoring the fields that change with the clock alone;
     * the page advances those itself, so they must not wake a held poll.
     */
    static String fingerprint(String json) {
        String stable = json.replaceAll(
                "\"(?:pongAgeSecs|pingMs|nextRetryAt|dupCount|dupSinceAgeSecs|dupLastAgeSecs)\":-?\\d+", "");
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

    /** The full status document the admin page renders, as one JSON object. */
    private String buildStatusJson(FederationPlugin plugin) {
        FederationManager mgr = plugin.getManager();
        String localDomain = localDomain();
        FederatedRoomManager rooms = mgr.getRoomManager();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"localDomain\":\"").append(esc(localDomain)).append("\",");
        sb.append("\"routableServers\":");
        strings(sb, mgr.routableServers());
        sb.append(",\"peers\":");
        array(sb, mgr.getPeerRegistry().getPeers(), (b, p) -> appendPeer(b, p, mgr));
        sb.append(",\"routing\":");
        array(sb, routeRows(mgr, localDomain), StringBuilder::append);
        sb.append(",\"localRooms\":");
        array(sb, rooms.getAllLocalRoomsWithTag(), (b, room) -> appendLocalRoom(b, room, mgr));
        sb.append(",\"pendingRequests\":");
        array(sb, rooms.getMappingsByState(RoomMapping.State.PENDING_IN), FederationApiServlet::appendPendingRequest);
        sb.append(",\"remoteRooms\":");
        objectOfArrays(sb, rooms.getRemoteRoomsVisibleToUs(), FederationApiServlet::appendRemoteRoom);
        sb.append(",\"mappings\":");
        objectOfArrays(sb, rooms.getLocalMappings(), (b, m) -> appendMapping(b, m, mgr));
        sb.append(",\"s2sSessions\":");
        array(sb, mgr.getS2SSessions(), FederationApiServlet::appendSession);
        sb.append(",");
        appendSettings(sb, mgr);
        appendFileRelay(sb, mgr.getFileRelay());
        sb.append("\"fileConfig\":");
        appendFileConfig(sb, mgr.getFileConfig());
        sb.append(",\"contactLists\":");
        appendContactLists(sb, mgr);
        sb.append(",\"roomDefaults\":");
        array(sb, mgr.getRoomDefaults().getRules(), FederationApiServlet::appendRoomDefault);
        sb.append("}");
        return sb.toString();
    }

    /** One peer: its link state and trust settings, what is exposed to it, and what it advertises. */
    private static void appendPeer(StringBuilder b, PeerServer p, FederationManager mgr) {
        b.append("{")
         .append("\"domain\":\"").append(esc(p.getDomain())).append("\",")
         .append("\"status\":\"").append(p.getStatus().name()).append("\",")
         .append("\"lastSeen\":").append(p.getLastSeenMillis()).append(",")
         .append("\"nextRetryAt\":").append(mgr.getNextRetryAt(p.getDomain())).append(",")
         .append("\"untrusted\":").append(p.isUntrusted()).append(",")
         .append("\"foreign\":").append(mgr.isForeignDomain(p.getDomain())).append(",")
         .append("\"certPinned\":").append(p.getPinnedCertFp() != null).append(",")
         .append("\"certMismatch\":").append(p.isCertMismatch()).append(",")
         .append("\"contactListsAllowed\":").append(p.isContactListsAllowed()).append(",")
         .append("\"contactListsRefused\":").append(p.isContactListsRefused()).append(",")
         .append("\"exposedServers\":");
        strings(b, p.getExposedServers());

        // Only untrusted peers need these lists, which keeps the payload small.
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
    }

    /** The routing table as JSON rows; denied routes stay listed, so the admin can lift a deny at any time. */
    private static List<String> routeRows(FederationManager mgr, String localDomain) {
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
        return routes;
    }

    /** One local room with its settings, its occupants and its mappings. */
    private static void appendLocalRoom(StringBuilder b, Map<String, Object> room, FederationManager mgr) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> roomMappings = (List<Map<String, String>>) room.get("mappings");
        @SuppressWarnings("unchecked")
        List<String> visTo = (List<String>) room.getOrDefault("visibleTo", Collections.emptyList());
        String roomJid = str(room.get("jid"));

        b.append("{")
         .append("\"jid\":\"").append(esc(roomJid)).append("\",")
         .append("\"name\":\"").append(esc(str(room.get("name")))).append("\",")
         .append("\"description\":\"").append(esc(str(room.get("description")))).append("\",")
         .append("\"federated\":").append(room.get("federated")).append(",")
         .append("\"autoAccept\":").append(room.get("autoAccept")).append(",")
         .append("\"filesEnabled\":").append(room.get("filesEnabled")).append(",")
         .append("\"occupants\":").append(room.get("occupants")).append(",")
         .append("\"visibleTo\":");
        strings(b, visTo);

        b.append(",\"occupantList\":");
        array(b, mgr.getOccupantSync().getRoomOccupants(roomJid), (ob, occ) ->
            ob.append("{\"name\":\"").append(esc(occ.get("name")))
              .append("\",\"jid\":\"").append(esc(occ.get("jid")))
              .append("\",\"kind\":\"").append(esc(occ.get("kind")))
              .append("\",\"show\":\"").append(esc(occ.get("show")))
              .append("\",\"status\":\"").append(esc(occ.get("status"))).append("\"}"));

        b.append(",\"mappings\":");
        array(b, roomMappings, (mb, m) -> appendRoomMapping(mb, m, roomJid, mgr));
        b.append("}");
    }

    /** One mapping of a local room, with its link health and any recent duplicate arrivals. */
    private static void appendRoomMapping(StringBuilder mb, Map<String, String> m, String roomJid,
                                          FederationManager mgr) {
        String remoteDomain = m.get("remoteDomain");
        // Only the duplicates flag wakes a held poll; the count and ages are fingerprint-blanked.
        RedundantArrivals.Summary dups = mgr.getRedundantArrivals().recent(roomJid, remoteDomain);
        long now = System.currentTimeMillis();
        mb.append("{")
          .append("\"remoteRoomJid\":\"").append(esc(m.get("remoteRoomJid"))).append("\",")
          .append("\"remoteDomain\":\"").append(esc(remoteDomain)).append("\",")
          .append("\"state\":\"").append(esc(m.get("state"))).append("\",");
        appendMappingHealth(mb, mgr, remoteDomain);
        mb.append(",\"duplicates\":").append(dups != null)
          .append(",\"dupCount\":").append(dups == null ? 0 : dups.count())
          .append(",\"dupSinceAgeSecs\":").append(dups == null ? -1 : (now - dups.firstAt()) / 1000)
          .append(",\"dupLastAgeSecs\":").append(dups == null ? -1 : (now - dups.lastAt()) / 1000)
          .append("}");
    }

    private static void appendPendingRequest(StringBuilder b, RoomMapping m) {
        b.append("{")
         .append("\"localJid\":\"").append(esc(m.localRoomJid())).append("\",")
         .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
         .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\"}");
    }

    private static void appendRemoteRoom(StringBuilder b, FederatedRoom room) {
        b.append("{")
         .append("\"jid\":\"").append(esc(room.jid())).append("\",")
         .append("\"name\":\"").append(esc(room.name())).append("\",")
         .append("\"description\":\"").append(esc(room.description())).append("\"")
         .append("}");
    }

    private static void appendMapping(StringBuilder b, RoomMapping m, FederationManager mgr) {
        b.append("{")
         .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
         .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\",");
        appendMappingHealth(b, mgr, m.remoteDomain());
        b.append("}");
    }

    private static void appendSession(StringBuilder b, Map<String, Object> sess) {
        b.append("{")
         .append("\"domain\":\"").append(esc(str(sess.get("domain")))).append("\",")
         .append("\"direction\":\"").append(esc(str(sess.get("direction")))).append("\",")
         .append("\"since\":").append(sess.get("since")).append(",")
         .append("\"encrypted\":").append(sess.get("encrypted")).append(",")
         .append("\"fedPeer\":").append(sess.get("fedPeer"))
         .append("}");
    }

    /** The S2S timers and the server-wide switches, each followed by a comma. */
    private static void appendSettings(StringBuilder sb, FederationManager mgr) {
        sb.append("\"keepaliveSeconds\":").append(mgr.getKeepaliveSeconds()).append(",");
        sb.append("\"effectiveKeepaliveSeconds\":").append(mgr.getEffectiveKeepaliveSeconds()).append(",");
        sb.append("\"reconnectSeconds\":").append(mgr.getReconnectSeconds()).append(",");
        sb.append("\"mappingPingSeconds\":").append(mgr.getMappingPingSeconds()).append(",");
        sb.append("\"peerAllowlist\":").append(FederationProperties.PEER_ALLOWLIST.getValue()).append(",");
        sb.append("\"allowRemoteRoomTraversal\":")
          .append(FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL.getValue()).append(",");
        sb.append("\"directMsgRelay\":").append(FederationProperties.DIRECT_MSG_RELAY.getValue()).append(",");
        sb.append("\"probeOnSubscribe\":").append(FederationProperties.PROBE_ON_SUBSCRIBE.getValue()).append(",");
    }

    /** The file relay settings and its recent AV scans and rejections, each followed by a comma. */
    private static void appendFileRelay(StringBuilder sb, FileRelayManager relay) {
        sb.append("\"filesEnabled\":").append(FederationProperties.FILES_ENABLED.getValue()).append(",");
        // The switch can be on while the relay failed to start, in which case shares keep their URLs.
        sb.append("\"filesRelayAvailable\":").append(relay != null && relay.isAvailable()).append(",");
        sb.append("\"filesMaxSizeMB\":").append(FederationProperties.FILES_MAX_MB.getValue()).append(",");
        sb.append("\"filesRetentionDays\":").append(FederationProperties.FILES_RETENTION_DAYS.getValue()).append(",");
        sb.append("\"filesStorageDir\":\"")
          .append(esc(FederationProperties.FILES_STORAGE_DIR.getValue())).append("\",");
        sb.append("\"filesAllowedExtensions\":\"")
          .append(esc(FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue())).append("\",");
        sb.append("\"filesAvEnabled\":").append(FederationProperties.FILES_AV_ENABLED.getValue()).append(",");
        sb.append("\"filesAvHost\":\"").append(esc(FederationProperties.FILES_AV_HOST.getValue())).append("\",");
        sb.append("\"filesAvPort\":").append(FederationProperties.FILES_AV_PORT.getValue()).append(",");
        sb.append("\"filesAvTimeoutMs\":").append(FederationProperties.FILES_AV_TIMEOUT_MS.getValue()).append(",");
        sb.append("\"filesLogRetentionDays\":")
          .append(FederationProperties.FILES_LOG_RETENTION_DAYS.getValue()).append(",");
        sb.append("\"filesScanLogPath\":\"").append(relay == null ? "" : esc(relay.scanLogPath())).append("\",");
        sb.append("\"filesRejectionLogPath\":\"")
          .append(relay == null ? "" : esc(relay.rejectionLogPath())).append("\",");

        sb.append("\"avScanLog\":");
        array(sb, relay == null ? List.<FileRelayManager.ScanLogEntry>of() : relay.recentAvScans(),
              FederationApiServlet::appendScan);
        sb.append(",\"rejectedFiles\":");
        array(sb, relay == null ? List.<FileRelayManager.RejectionEntry>of() : relay.recentRejections(),
              FederationApiServlet::appendRejection);
        sb.append(",");
    }

    private static void appendScan(StringBuilder b, FileRelayManager.ScanLogEntry e) {
        b.append("{")
         .append("\"when\":").append(e.when()).append(",")
         .append("\"name\":\"").append(esc(e.fileName())).append("\",")
         .append("\"size\":").append(e.sizeBytes()).append(",")
         .append("\"origin\":\"").append(esc(e.origin())).append("\",")
         .append("\"stage\":\"").append(e.stage().token()).append("\",")
         .append("\"verdict\":\"").append(esc(e.verdict())).append("\",")
         .append("\"detail\":\"").append(esc(e.detail())).append("\"")
         .append("}");
    }

    private static void appendRejection(StringBuilder b, FileRelayManager.RejectionEntry e) {
        b.append("{")
         .append("\"when\":").append(e.when()).append(",")
         .append("\"name\":\"").append(esc(e.fileName())).append("\",")
         .append("\"size\":").append(e.sizeBytes()).append(",")
         .append("\"origin\":\"").append(esc(e.origin())).append("\",")
         .append("\"stage\":\"").append(e.stage().token()).append("\",")
         .append("\"reason\":\"").append(e.reason().name()).append("\",")
         .append("\"detail\":\"").append(esc(e.detail())).append("\"")
         .append("}");
    }

    /** What the last ingest of the openfire.xml {@code <federation>} block changed. */
    private static void appendFileConfig(StringBuilder sb, FederationFileConfig fileConfig) {
        FederationFileConfig.IngestResult fcResult = fileConfig.lastResult();
        Long fcLoadedAt = fileConfig.lastLoadedAtMillis();
        sb.append("{")
          .append("\"path\":\"").append(esc(fileConfig.configFile().toString())).append("\",")
          .append("\"loadedAt\":").append(fcLoadedAt == null ? "null" : fcLoadedAt).append(",")
          .append("\"blockPresent\":").append(fcResult.blockPresent()).append(",")
          .append("\"peersAdded\":").append(fcResult.peersAdded()).append(",")
          .append("\"peersUpdated\":").append(fcResult.peersUpdated()).append(",")
          .append("\"roomsUpdated\":").append(fcResult.roomsUpdated()).append(",")
          .append("\"mappingsRequested\":").append(fcResult.mappingsRequested()).append(",")
          .append("\"settingsUpdated\":").append(fcResult.settingsUpdated()).append(",")
          .append("\"warnings\":");
        strings(sb, fcResult.warnings());
        sb.append("}");
    }

    private static void appendRoomDefault(StringBuilder b, RoomDefaultsManager.Rule r) {
        b.append("{\"pattern\":\"").append(esc(r.pattern()))
         .append("\",\"federated\":").append(r.federated())
         .append(",\"autoAccept\":").append(r.autoAccept())
         .append(",\"autoMap\":").append(r.autoMap())
         .append(",\"filesEnabled\":").append(r.filesEnabled())
         .append(",\"visible\":");
        strings(b, r.visible());
        b.append("}");
    }

    /**
     * Appends a mapping's link health: {@code connected}, {@code routeMissing}, {@code pathBroken},
     * {@code pingMs} and {@code pongAgeSecs}, without surrounding braces.
     */
    private static void appendMappingHealth(StringBuilder b, FederationManager mgr, String remoteDomain) {
        MappingProbe probe = mgr.getMappingProbe();
        boolean reachable = mgr.getRoutingTable().isReachable(remoteDomain);
        boolean pathBroken = reachable && probe.isPathBroken(remoteDomain);
        boolean connected = reachable && !pathBroken;
        boolean routeMissing = !connected && !pathBroken && mgr.getPeerRegistry().getPeer(remoteDomain).isEmpty();
        b.append("\"connected\":").append(connected)
         .append(",\"routeMissing\":").append(routeMissing)
         .append(",\"pathBroken\":").append(pathBroken)
         .append(",\"pingMs\":").append(probe.roundTripMs(remoteDomain))
         .append(",\"pongAgeSecs\":").append(probe.pongAgeSecs(remoteDomain));
    }

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

    /** Appends {@code {"k1":[…],"k2":[…]}}, the shape of the domain-keyed maps the page renders. */
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

    /** Contacts listed per server in the status document; the page shows the count beyond this. */
    private static final int CONTACTS_SHOWN = 500;

    private static void appendContacts(StringBuilder sb, List<ContactListManager.Contact> contacts) {
        array(sb, contacts.size() > CONTACTS_SHOWN ? contacts.subList(0, CONTACTS_SHOWN) : contacts, (b, c) ->
            b.append("{\"jid\":\"").append(esc(c.jid()))
             .append("\",\"name\":\"").append(esc(c.name())).append("\"}"));
    }

    /** The Users tab: what this server shares and with whom, and the lists other servers share with us. */
    private static void appendContactLists(StringBuilder sb, FederationManager mgr) {
        ContactListManager cl = mgr.getContactLists();
        sb.append("{\"groupsReadOnly\":").append(cl.groupsReadOnly());

        sb.append(",\"shareRules\":");
        array(sb, cl.getShareRules(), (b, r) -> {
            b.append("{\"group\":\"").append(esc(r.group())).append("\",\"targets\":");
            strings(b, r.targets());
            b.append("}");
        });

        sb.append(",\"shareableServers\":");
        strings(sb, cl.shareableServers());

        sb.append(",\"untrustedPathServers\":");
        strings(sb, cl.shareableServers().stream().filter(cl::crossesUntrusted).toList());

        sb.append(",\"localGroups\":");
        strings(sb, cl.localGroupNames());

        // Configured targets are listed even if they were never reachable.
        Map<String, List<ContactListManager.Contact>> sent = cl.getSentLists();
        Set<String> targets = new TreeSet<>(sent.keySet());
        for (ContactListManager.ShareRule r : cl.getShareRules()) targets.addAll(r.targets());
        sb.append(",\"sent\":");
        array(sb, targets, (b, t) -> {
            List<ContactListManager.Contact> contacts = cl.computeSnapshot(t);
            List<ContactListManager.Contact> last = sent.get(t);
            b.append("{\"target\":\"").append(esc(t))
             .append("\",\"reachable\":").append(cl.canExchangeWith(t))
             .append(",\"delivered\":").append(contacts.equals(last))
             .append(",\"crossesUntrusted\":").append(cl.crossesUntrusted(t))
             .append(",\"count\":").append(contacts.size())
             .append(",\"contacts\":");
            appendContacts(b, contacts);
            b.append("}");
        });

        Map<String, ContactListManager.ReceivedList> received = cl.getReceived();
        Map<String, ContactListManager.ListMapping> mappings = cl.getMappings();
        Map<String, String> errors = cl.getMappingErrors();
        Set<String> origins = new TreeSet<>(received.keySet());
        origins.addAll(mappings.keySet());
        sb.append(",\"received\":");
        array(sb, origins, (b, o) -> {
            ContactListManager.ReceivedList list = received.get(o);
            ContactListManager.ListMapping m = mappings.get(o);
            b.append("{\"origin\":\"").append(esc(o))
             .append("\",\"reachable\":").append(cl.canRequestFrom(o))
             .append(",\"receivedAt\":").append(list == null ? "null" : String.valueOf(list.receivedAt()))
             .append(",\"crossedUntrusted\":").append(list != null && list.crossedUntrusted())
             .append(",\"count\":").append(list == null ? 0 : list.contacts().size())
             .append(",\"groupName\":\"").append(esc(ContactListManager.groupNameFor(o)))
             .append("\",\"error\":\"").append(esc(errors.getOrDefault(o, "")))
             .append("\",\"contacts\":");
            appendContacts(b, list == null ? List.of() : list.contacts());
            b.append(",\"mapping\":");
            if (m == null) {
                b.append("null");
            } else {
                b.append("{\"displayName\":\"").append(esc(m.displayName())).append("\",\"groups\":");
                strings(b, m.groups());
                b.append("}");
            }
            b.append("}");
        });
        sb.append("}");
    }

    /**
     * Runs one admin action and answers {@code {"ok":true,…}} or {@code {"error":"…"}}. Each action
     * group returns null for an action it does not own, so the next group gets a look.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");

        PrintWriter out = resp.getWriter();

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

        String action = req.getParameter("action");
        try {
            out.print(runAction(action == null ? "" : action, req, plugin.getManager()));
        } catch (RuntimeException e) {
            Log.error("Admin API: action '{}' failed", LogSafe.text(action), e);
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            out.print(error(INTERNAL_ERROR));
        }
    }

    private String runAction(String action, HttpServletRequest req, FederationManager mgr) {
        String reply = peerAction(action, req, mgr);
        if (reply == null) reply = roomAction(action, req, mgr);
        if (reply == null) reply = mappingAction(action, req, mgr);
        if (reply == null) reply = fileAction(action, req, mgr);
        if (reply == null) reply = contactListAction(action, req, mgr);
        if (reply == null) reply = settingAction(action, req, mgr);
        return reply != null ? reply : error("unknown action");
    }

    /** Peer lifecycle, per-link trust, and the routes and servers a peer may advertise. */
    private String peerAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "add-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!isPlausibleDomain(d)) return error("invalid domain");
                mgr.addPeer(d);
                // Without an explicit flag, a peer under a foreign parent domain starts untrusted.
                String untrustedParam = req.getParameter("untrusted");
                boolean untrusted = untrustedParam != null
                        ? Boolean.parseBoolean(untrustedParam.strip())
                        : mgr.isForeignDomain(d);
                if (untrusted) {
                    mgr.getPeerRegistry().setUntrusted(d, true);
                    mgr.applyLocalTrustChange(d);
                }
                return OK;
            }
            case "remove-peer": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
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
                mgr.getCertificatePinning().acceptNewCertificate(d);
                return OK;
            }
            case "set-untrusted": {
                String d = domainParam(req, "domain");
                String untrusted = req.getParameter("untrusted");
                if (d == null || untrusted == null) return required("domain", "untrusted");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.getPeerRegistry().setUntrusted(d, Boolean.parseBoolean(untrusted.strip()));
                mgr.applyLocalTrustChange(d);
                return OK;
            }
            case "set-exposed-servers": {
                String d = domainParam(req, "domain");
                if (d == null) return required("domain");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                Set<String> allowed = mgr.exposableServers(d);
                List<String> list = new ArrayList<>();
                for (String srv : csvDomains(req, "servers")) {
                    if (allowed.contains(srv)) list.add(srv);
                }
                mgr.getPeerRegistry().setExposedServers(d, list);
                if (isReachable(mgr, d)) {
                    mgr.sendRoutingUpdate(d);
                    mgr.sendRoomState(d);
                }
                return OK;
            }
            case "set-peer-contact-lists": {
                String d = domainParam(req, "domain");
                String enabled = req.getParameter("enabled");
                if (d == null || enabled == null) return required("domain", "enabled");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.getPeerRegistry().setContactListsAllowed(d, Boolean.parseBoolean(enabled.strip()));
                mgr.getContactLists().resendAll();
                return OK;
            }
            case "set-peer-contact-lists-refused": {
                String d = domainParam(req, "domain");
                String refused = req.getParameter("refused");
                if (d == null || refused == null) return required("domain", "refused");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                mgr.getPeerRegistry().setContactListsRefused(d, Boolean.parseBoolean(refused.strip()));
                return OK;
            }
            case "deny-route": case "allow-route": {
                String d = domainParam(req, "domain");
                String dest = domainParam(req, "destination");
                if (d == null || dest == null) return required("domain", "destination");
                if (!mgr.getPeerRegistry().contains(d)) return error("not a configured peer");
                if (dest.equals(localDomain()) || dest.equals(d)) {
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

    /** Per-room federation settings, and the defaults applied to new rooms by name pattern. */
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
                mgr.setRoomVisibility(jid, csvDomains(req, "servers"));
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
                boolean filesEnabled = filesParam == null || Boolean.parseBoolean(filesParam.strip());
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

    /** Requesting, answering, disabling and removing room mappings. */
    private String mappingAction(String action, HttpServletRequest req, FederationManager mgr) {
        switch (action) {
            case "map-room": {
                String localJid = param(req, "localJid");
                String remoteJid = param(req, "remoteJid");
                String remoteDomain = domainParam(req, "remoteDomain");
                if (localJid == null || remoteJid == null || remoteDomain == null) {
                    return error("localJid, remoteJid, remoteDomain required");
                }
                // A room may only be mapped to a peer's room once it is shared with that peer.
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
                if (remoteDomain != null) mgr.unmapRoom(localJid, remoteDomain);
                else                      mgr.unmapRooms(localJid);
                return OK;
            }
            default:
                return null;
        }
    }

    /** File relay settings: the global switch, size and retention limits, storage, and the AV scanner. */
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
                // Prune now rather than at the next scheduled pass, so a shorter window shows at once.
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
                // An unusable directory rolls the property back, so it always names where files are served from.
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

    /** Contact-list sharing: what this server shares with whom, and where received lists appear. */
    private String contactListAction(String action, HttpServletRequest req, FederationManager mgr) {
        ContactListManager cl = mgr.getContactLists();
        switch (action) {
            case "save-contact-share": {
                String group = param(req, "group");
                if (group == null) return required("group");
                if (!cl.localGroupNames().contains(group)) return error("no such group: " + group);
                // An existing target stays allowed while its route is down; a new one must be reachable.
                Set<String> allowed = new HashSet<>(cl.shareableServers());
                cl.getShareRules().stream().filter(r -> r.group().equals(group))
                  .forEach(r -> allowed.addAll(r.targets()));
                List<String> targets = new ArrayList<>();
                for (String t : csvDomains(req, "targets")) {
                    if (!allowed.contains(t)) return error(t + " is not reachable over a trusted path");
                    targets.add(t);
                }
                cl.saveShareRule(group, targets);
                return OK;
            }
            case "delete-contact-share": {
                String group = param(req, "group");
                if (group == null) return required("group");
                cl.deleteShareRule(group);
                return OK;
            }
            case "map-contact-list": {
                String origin = domainParam(req, "origin");
                if (origin == null) return required("origin");
                if (cl.groupsReadOnly()) return error("the group provider is read-only, so lists cannot be mapped");
                List<String> groups = new ArrayList<>();
                String raw = req.getParameter("groups");
                if (raw != null) {
                    List<String> known = cl.localGroupNames();
                    for (String g : raw.split("\n")) {
                        String t = g.strip();
                        if (t.isEmpty()) continue;
                        if (!t.equals(ContactListManager.EVERYBODY) && !known.contains(t)) {
                            return error("no such group: " + t);
                        }
                        groups.add(t);
                    }
                }
                if (groups.isEmpty()) return error("choose at least one group, or all users");
                cl.setMapping(origin, param(req, "displayName"), groups);
                return OK;
            }
            case "unmap-contact-list": {
                String origin = domainParam(req, "origin");
                if (origin == null) return required("origin");
                cl.removeMapping(origin);
                return OK;
            }
            case "resend-contact-lists":
                cl.resendAll();
                return OK;
            default:
                return null;
        }
    }

    /** Server-wide switches and timers, and the openfire.xml reload. */
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

    /** The reply for an action that succeeded and has nothing to report back. */
    private static final String OK = "{\"ok\":true}";

    /** {@code {"ok":true,"<key>":<value>}}, for numbers and booleans, which need no quoting. */
    static String ok(String key, Object value) {
        return "{\"ok\":true,\"" + key + "\":" + value + "}";
    }

    /** {@code {"ok":true,"<key>":"<value>"}}, with the value JSON-escaped. */
    static String okText(String key, String value) {
        return "{\"ok\":true,\"" + key + "\":\"" + esc(value) + "\"}";
    }

    /** {@code {"error":"<message>"}}, with the message JSON-escaped. */
    static String error(String message) {
        return "{\"error\":\"" + esc(message) + "\"}";
    }

    /** The missing-parameter reply: {@code required("a", "b")} → {@code {"error":"a and b required"}}. */
    static String required(String... names) {
        return error(String.join(" and ", names) + " required");
    }

    /** Whether {@code reply} reports success. */
    static boolean isOk(String reply) {
        return reply.startsWith("{\"ok\":true");
    }

    /** Trimmed value of {@code name}, or null when the parameter is absent or blank. */
    private static String param(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    /** {@link #param} lowercased, since domains are stored and compared in lower case. */
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

    /** Comma-separated domains, lowercased, without blanks; empty when the parameter is absent. */
    private static List<String> csvDomains(HttpServletRequest req, String name) {
        List<String> list = new ArrayList<>();
        String raw = req.getParameter(name);
        if (raw == null) return list;
        for (String s : raw.split(",")) {
            if (!s.isBlank()) list.add(s.strip().toLowerCase());
        }
        return list;
    }

    /** The error for a missing or non-numeric {@code seconds} parameter. */
    private static String secondsError(HttpServletRequest req) {
        return param(req, "seconds") == null ? required("seconds") : error("seconds must be an integer");
    }

    /** Applies the {@code enabled} parameter to a boolean property and answers with the value stored. */
    private static String applyToggle(HttpServletRequest req, SystemProperty<Boolean> prop, String jsonKey) {
        String enabled = req.getParameter("enabled");
        if (enabled == null) return required("enabled");
        prop.setValue(Boolean.parseBoolean(enabled.strip()));
        return ok(jsonKey, prop.getValue());
    }

    /**
     * Applies an integer parameter to a property when it is present, numeric and within
     * {@code [min, max]}; otherwise answers with the error and leaves the property unchanged.
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

    /** Smallest AV scan timeout that leaves clamd time to answer. */
    private static final int MIN_AV_TIMEOUT_MS = 1000;

    private static final String CSRF_COOKIE = "fed-csrf";

    /** Issues the double-submit CSRF cookie if the client has none; not HttpOnly, since the page reads it. */
    private void ensureCsrfCookie(HttpServletRequest req, HttpServletResponse resp) {
        Cookie existing = CookieUtils.getCookie(req, CSRF_COOKIE);
        if (existing != null && existing.getValue() != null && !existing.getValue().isEmpty()) return;
        Cookie c = new Cookie(CSRF_COOKIE, StringUtils.randomString(24));
        c.setPath("/");
        c.setMaxAge(-1);
        resp.addCookie(c);
    }

    /** Double-submit CSRF check: the {@code csrf} parameter must equal the cookie. */
    private boolean validateCsrf(HttpServletRequest req) {
        Cookie c = CookieUtils.getCookie(req, CSRF_COOKIE);
        String param = req.getParameter("csrf");
        return c != null && c.getValue() != null && !c.getValue().isEmpty()
            && c.getValue().equals(param);
    }

    /** Escapes {@code s} for a JSON string, including every control character; null becomes empty. */
    static String esc(String s) {
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
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else          b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    /** Whether the peer currently has a REACHABLE federation link. */
    private boolean isReachable(FederationManager mgr, String domain) {
        return mgr.getPeerRegistry().getPeer(domain)
                  .map(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                  .orElse(false);
    }
}
