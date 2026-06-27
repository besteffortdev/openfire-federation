package com.igniterealtime.openfire.plugin.federation;

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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

public class FederationApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");
        ensureCsrfCookie(req, resp);   // hand the page a token it can echo on POSTs

        FederationPlugin plugin = FederationPlugin.getInstance();
        PrintWriter out = resp.getWriter();

        if (plugin == null) {
            out.print("{\"error\":\"Plugin not loaded\"}");
            return;
        }

        FederationManager mgr = plugin.getManager();
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"localDomain\":\"").append(esc(localDomain)).append("\",");

        // Candidate servers for the per-room visibility ACL (routing-table destinations).
        sb.append("\"routableServers\":[");
        boolean frs = true;
        for (String s : mgr.routableServers()) { if (!frs) sb.append(","); frs = false; sb.append("\"").append(esc(s)).append("\""); }
        sb.append("],");

        // ── peers ─────────────────────────────────────────────────────────────
        sb.append("\"peers\":[");
        boolean first = true;
        for (PeerServer p : mgr.getPeerRegistry().getPeers()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"domain\":\"").append(esc(p.getDomain())).append("\",")
              .append("\"status\":\"").append(p.getStatus().name()).append("\",")
              .append("\"lastSeen\":").append(p.getLastSeenMillis()).append(",")
              .append("\"nextRetryAt\":").append(mgr.getNextRetryAt(p.getDomain())).append(",")
              .append("\"untrusted\":").append(p.isUntrusted()).append(",")
              .append("\"foreign\":").append(mgr.isForeignDomain(p.getDomain())).append(",")
              .append("\"certPinned\":").append(p.getPinnedCertFp() != null).append(",")
              .append("\"certMismatch\":").append(p.isCertMismatch()).append(",")
              .append("\"exposedServers\":[");
            boolean fe = true;
            for (String srv : p.getExposedServers()) {
                if (!fe) sb.append(",");
                fe = false;
                sb.append("\"").append(esc(srv)).append("\"");
            }
            // Candidate servers we may expose, and the servers this peer advertises THROUGH to us
            // (right-hand column of the per-link view); only computed for untrusted peers to keep
            // the trusted-peer payload small.
            sb.append("],\"exposableServers\":[");
            if (p.isUntrusted()) {
                boolean fx = true;
                for (String srv : mgr.exposableServers(p.getDomain())) {
                    if (!fx) sb.append(",");
                    fx = false;
                    sb.append("\"").append(esc(srv)).append("\"");
                }
            }
            sb.append("],\"advertisedVia\":[");
            if (p.isUntrusted()) {
                java.util.Set<String> via = new java.util.LinkedHashSet<>();
                for (FederatedRoom room : mgr.getRoomManager().getRemoteRoomsViaPeer(p.getDomain())) {
                    via.add(room.originServer());
                }
                boolean fa = true;
                for (String srv : via) {
                    if (!fa) sb.append(",");
                    fa = false;
                    sb.append("\"").append(esc(srv)).append("\"");
                }
            }
            sb.append("]}");
        }
        sb.append("],");

        // ── routing (local domain excluded) ───────────────────────────────────
        sb.append("\"routing\":[");
        first = true;
        for (RouteEntry r : mgr.getRoutingTable().getAll()) {
            if (r.destination().equals(localDomain)) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"destination\":\"").append(esc(r.destination())).append("\",")
              .append("\"nextHop\":\"").append(esc(r.nextHop())).append("\",")
              .append("\"hops\":").append(r.hops()).append(",")
              .append("\"updatedAt\":").append(r.updatedAt())
              .append("}");
        }
        sb.append("],");

        // ── local rooms ───────────────────────────────────────────────────────
        sb.append("\"localRooms\":[");
        first = true;
        for (Map<String, Object> room : mgr.getRoomManager().getAllLocalRoomsWithTag()) {
            if (!first) sb.append(",");
            first = false;
            @SuppressWarnings("unchecked")
            List<Map<String, String>> roomMappings = (List<Map<String, String>>) room.get("mappings");

            sb.append("{")
              .append("\"jid\":\"").append(esc(str(room.get("jid")))).append("\",")
              .append("\"name\":\"").append(esc(str(room.get("name")))).append("\",")
              .append("\"description\":\"").append(esc(str(room.get("description")))).append("\",")
              .append("\"federated\":").append(room.get("federated")).append(",")
              .append("\"autoAccept\":").append(room.get("autoAccept")).append(",")
              .append("\"occupants\":").append(room.get("occupants")).append(",")
              .append("\"visibleTo\":[");
            @SuppressWarnings("unchecked")
            List<String> visTo = (List<String>) room.getOrDefault("visibleTo", java.util.Collections.emptyList());
            boolean fv = true;
            for (String s : visTo) { if (!fv) sb.append(","); fv = false; sb.append("\"").append(esc(s)).append("\""); }
            sb.append("],");

            // Occupant roster (local + remote virtual) with live presence for the tracking view.
            sb.append("\"occupantList\":[");
            boolean fo = true;
            for (Map<String, String> occ : mgr.getRoomOccupants(str(room.get("jid")))) {
                if (!fo) sb.append(","); fo = false;
                sb.append("{\"name\":\"").append(esc(occ.get("name")))
                  .append("\",\"jid\":\"").append(esc(occ.get("jid")))
                  .append("\",\"kind\":\"").append(esc(occ.get("kind")))
                  .append("\",\"show\":\"").append(esc(occ.get("show")))
                  .append("\",\"status\":\"").append(esc(occ.get("status"))).append("\"}");
            }
            sb.append("],");

            sb.append("\"mappings\":[");

            boolean fm = true;
            for (Map<String, String> m : roomMappings) {
                if (!fm) sb.append(",");
                fm = false;
                boolean connected = mgr.getRoutingTable().isReachable(m.get("remoteDomain"));
                boolean routeMissing = !connected
                        && mgr.getPeerRegistry().getPeer(m.get("remoteDomain")).isEmpty();
                sb.append("{")
                  .append("\"remoteRoomJid\":\"").append(esc(m.get("remoteRoomJid"))).append("\",")
                  .append("\"remoteDomain\":\"").append(esc(m.get("remoteDomain"))).append("\",")
                  .append("\"state\":\"").append(esc(m.get("state"))).append("\",")
                  .append("\"connected\":").append(connected).append(",")
                  .append("\"routeMissing\":").append(routeMissing)
                  .append("}");
            }
            sb.append("]}");
        }
        sb.append("],");

        // ── incoming pending mapping requests (for the Pending requests panel) ──
        sb.append("\"pendingRequests\":[");
        boolean fp = true;
        for (com.igniterealtime.openfire.plugin.federation.model.RoomMapping m
                : mgr.getRoomManager().getMappingsByState(
                      com.igniterealtime.openfire.plugin.federation.model.RoomMapping.State.PENDING_IN)) {
            if (!fp) sb.append(",");
            fp = false;
            sb.append("{")
              .append("\"localJid\":\"").append(esc(m.localRoomJid())).append("\",")
              .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
              .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\"}");
        }
        sb.append("],");

        // ── remote rooms ──────────────────────────────────────────────────────
        sb.append("\"remoteRooms\":{");
        first = true;
        for (Map.Entry<String, List<FederatedRoom>> entry :
                mgr.getRoomManager().getRemoteRooms().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(entry.getKey())).append("\":[");
            boolean fr = true;
            for (FederatedRoom room : entry.getValue()) {
                if (!fr) sb.append(",");
                fr = false;
                sb.append("{")
                  .append("\"jid\":\"").append(esc(room.jid())).append("\",")
                  .append("\"name\":\"").append(esc(room.name())).append("\",")
                  .append("\"description\":\"").append(esc(room.description())).append("\"")
                  .append("}");
            }
            sb.append("]");
        }
        sb.append("},");

        // ── room mappings (localJid → array of {remoteRoomJid, remoteDomain}) ─
        sb.append("\"mappings\":{");
        first = true;
        for (Map.Entry<String, List<RoomMapping>> entry :
                mgr.getRoomManager().getLocalMappings().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(entry.getKey())).append("\":[");
            boolean fm = true;
            for (RoomMapping m : entry.getValue()) {
                if (!fm) sb.append(",");
                fm = false;
                boolean connected = mgr.getRoutingTable().isReachable(m.remoteDomain());
                boolean routeMissing = !connected
                        && mgr.getPeerRegistry().getPeer(m.remoteDomain()).isEmpty();
                sb.append("{")
                  .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
                  .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\",")
                  .append("\"connected\":").append(connected).append(",")
                  .append("\"routeMissing\":").append(routeMissing)
                  .append("}");
            }
            sb.append("]");
        }
        sb.append("},");

        // ── active S2S sessions ───────────────────────────────────────────────
        sb.append("\"s2sSessions\":[");
        first = true;
        for (Map<String, Object> sess : mgr.getS2SSessions()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"domain\":\"").append(esc(str(sess.get("domain")))).append("\",")
              .append("\"direction\":\"").append(esc(str(sess.get("direction")))).append("\",")
              .append("\"since\":").append(sess.get("since")).append(",")
              .append("\"encrypted\":").append(sess.get("encrypted")).append(",")
              .append("\"fedPeer\":").append(sess.get("fedPeer"))
              .append("}");
        }
        sb.append("],");

        // ── settings ──────────────────────────────────────────────────────────
        sb.append("\"keepaliveSeconds\":").append(mgr.getKeepaliveSeconds()).append(",");
        sb.append("\"effectiveKeepaliveSeconds\":").append(mgr.getEffectiveKeepaliveSeconds()).append(",");
        sb.append("\"reconnectSeconds\":").append(mgr.getReconnectSeconds()).append(",");
        sb.append("\"peerAllowlist\":").append(FederationProperties.PEER_ALLOWLIST.getValue()).append(",");
        sb.append("\"blockDirectMuc\":").append(FederationProperties.BLOCK_DIRECT_MUC.getValue()).append(",");
        sb.append("\"directMsgRelay\":").append(FederationProperties.DIRECT_MSG_RELAY.getValue()).append(",");
        sb.append("\"directoryPublish\":").append(FederationProperties.DIRECTORY_PUBLISH.getValue()).append(",");
        sb.append("\"bookmarkPush\":").append(FederationProperties.BOOKMARK_PUSH.getValue()).append(",");
        sb.append("\"probeOnSubscribe\":").append(FederationProperties.PROBE_ON_SUBSCRIBE.getValue()).append(",");

        // ── this server's connected clients (local online users) ───────────────
        sb.append("\"localUsers\":[");
        first = true;
        for (com.igniterealtime.openfire.plugin.federation.UserDirectory.UserPresence u
                : mgr.getUserDirectory().localOnlineUsers()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"jid\":\"").append(esc(u.jid()))
              .append("\",\"show\":\"").append(esc(u.show()))
              .append("\",\"status\":\"").append(esc(u.status())).append("\"}");
        }
        sb.append("],");

        // ── user directory (origin server domain → [{jid,show,status}]) ────────
        sb.append("\"directory\":{");
        first = true;
        for (java.util.Map.Entry<String, java.util.List<com.igniterealtime.openfire.plugin.federation.UserDirectory.UserPresence>> e
                : mgr.getUserDirectory().getRemoteUsers().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(e.getKey())).append("\":[");
            boolean fu = true;
            for (com.igniterealtime.openfire.plugin.federation.UserDirectory.UserPresence u : e.getValue()) {
                if (!fu) sb.append(","); fu = false;
                sb.append("{\"jid\":\"").append(esc(u.jid()))
                  .append("\",\"show\":\"").append(esc(u.show()))
                  .append("\",\"status\":\"").append(esc(u.status())).append("\"}");
            }
            sb.append("]");
        }
        sb.append("},");

        // ── bookmarks advertised to us by peers (origin domain → [jid]) ────────
        sb.append("\"advertisedBookmarks\":{");
        first = true;
        for (java.util.Map.Entry<String, java.util.List<String>> e
                : mgr.getBookmarkInjector().getAdvertised().entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(esc(e.getKey())).append("\":[");
            boolean fj = true;
            for (String jid : e.getValue()) {
                if (!fj) sb.append(","); fj = false;
                sb.append("\"").append(esc(jid)).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");

        sb.append("}");
        out.print(sb.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");

        FederationPlugin plugin = FederationPlugin.getInstance();
        PrintWriter out = resp.getWriter();

        // CSRF (double-submit): the page echoes the fed-csrf cookie as a parameter. A cross-site
        // attacker can't read the cookie (same-origin) nor forge a matching parameter, so a
        // forged POST from an admin's browser is rejected even though the admin session is valid.
        if (!validateCsrf(req)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            out.print("{\"error\":\"CSRF validation failed\"}");
            return;
        }

        if (plugin == null) {
            out.print("{\"error\":\"Plugin not loaded\"}");
            return;
        }

        FederationManager mgr = plugin.getManager();
        String action = req.getParameter("action");

        switch (action != null ? action : "") {
            case "add-peer": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                String d = domain.strip().toLowerCase();
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
                out.print("{\"ok\":true}");
                return;
            }
            case "remove-peer": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                out.print("{\"ok\":" + mgr.removePeer(domain.strip().toLowerCase()) + "}");
                return;
            }
            case "set-room": {
                String jid      = req.getParameter("jid");
                String fedParam = req.getParameter("federated");
                if (jid == null || fedParam == null) {
                    out.print("{\"error\":\"jid and federated required\"}");
                    return;
                }
                mgr.setRoomFederated(jid.strip(), Boolean.parseBoolean(fedParam));
                out.print("{\"ok\":true}");
                return;
            }
            case "set-room-visibility": {
                String jid     = req.getParameter("jid");
                String servers = req.getParameter("servers");   // csv of server domains ("" = all)
                if (jid == null || jid.isBlank()) {
                    out.print("{\"error\":\"jid required\"}");
                    return;
                }
                java.util.List<String> list = new java.util.ArrayList<>();
                if (servers != null) {
                    for (String s : servers.split(",")) {
                        if (!s.isBlank()) list.add(s.strip().toLowerCase());
                    }
                }
                mgr.setRoomVisibility(jid.strip(), list);
                out.print("{\"ok\":true}");
                return;
            }
            case "map-room": {
                String localJid     = req.getParameter("localJid");
                String remoteJid    = req.getParameter("remoteJid");
                String remoteDomain = req.getParameter("remoteDomain");
                if (localJid == null || remoteJid == null || remoteDomain == null) {
                    out.print("{\"error\":\"localJid, remoteJid, remoteDomain required\"}");
                    return;
                }
                String mlj = localJid.strip(), mrj = remoteJid.strip(), mrd = remoteDomain.strip().toLowerCase();
                // Reciprocity: you may only map your room to a peer's room if you've shared THAT room
                // with the peer (federation enabled + the peer is in the room's visibility ACL).
                if (!mgr.roomSharedWith(mlj, mrd)) {
                    out.print("{\"error\":\"You must share this room with " + esc(mrd)
                            + " before mapping it to a room there. Enable federation on the room and add "
                            + esc(mrd) + " to its visibility.\"}");
                    return;
                }
                mgr.requestMapping(mlj, mrj, mrd);
                out.print("{\"ok\":true}");
                return;
            }
            case "accept-mapping": case "reject-mapping":
            case "disable-mapping": case "enable-mapping": {
                String localJid     = req.getParameter("localJid");
                String remoteDomain = req.getParameter("remoteDomain");
                if (localJid == null || localJid.isBlank() || remoteDomain == null || remoteDomain.isBlank()) {
                    out.print("{\"error\":\"localJid and remoteDomain required\"}");
                    return;
                }
                String lj = localJid.strip(), rd = remoteDomain.strip().toLowerCase();
                switch (action) {
                    case "accept-mapping"  -> mgr.acceptMapping(lj, rd);
                    case "reject-mapping"  -> mgr.rejectMapping(lj, rd);
                    case "disable-mapping" -> mgr.disableMapping(lj, rd);
                    case "enable-mapping"  -> mgr.enableMapping(lj, rd);
                }
                out.print("{\"ok\":true}");
                return;
            }
            case "set-room-autoaccept": {
                String jid    = req.getParameter("jid");
                String enable = req.getParameter("autoAccept");
                if (jid == null || jid.isBlank() || enable == null) {
                    out.print("{\"error\":\"jid and autoAccept required\"}");
                    return;
                }
                mgr.getRoomManager().setAutoAccept(jid.strip(), Boolean.parseBoolean(enable.strip()));
                out.print("{\"ok\":true}");
                return;
            }
            case "unmap-room": {
                String localJid     = req.getParameter("localJid");
                String remoteDomain = req.getParameter("remoteDomain");
                if (localJid == null || localJid.isBlank()) {
                    out.print("{\"error\":\"localJid required\"}");
                    return;
                }
                if (remoteDomain != null && !remoteDomain.isBlank()) {
                    // Remove only the mapping toward a specific remote domain.
                    mgr.unmapRoom(localJid.strip(), remoteDomain.strip());
                } else {
                    // Remove all mappings for this local room.
                    mgr.unmapRooms(localJid.strip());
                }
                out.print("{\"ok\":true}");
                return;
            }
            case "disable-peer": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                mgr.disablePeer(domain.strip().toLowerCase());
                out.print("{\"ok\":true}");
                return;
            }
            case "enable-peer": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                mgr.enablePeer(domain.strip().toLowerCase());
                out.print("{\"ok\":true}");
                return;
            }
            case "retry-peer": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                if (!mgr.getPeerRegistry().contains(domain.strip().toLowerCase())) {
                    out.print("{\"error\":\"not a configured peer\"}");
                    return;
                }
                mgr.retryPeer(domain.strip().toLowerCase());
                out.print("{\"ok\":true}");
                return;
            }
            case "kill-session": {
                String domain    = req.getParameter("domain");
                String direction = req.getParameter("direction");
                if (domain == null || domain.isBlank() || direction == null) {
                    out.print("{\"error\":\"domain and direction required\"}");
                    return;
                }
                mgr.killSession(domain.strip().toLowerCase(), direction.strip().toLowerCase());
                out.print("{\"ok\":true}");
                return;
            }
            case "set-keepalive": {
                String secParam = req.getParameter("seconds");
                if (secParam == null || secParam.isBlank()) {
                    out.print("{\"error\":\"seconds required\"}");
                    return;
                }
                try {
                    int seconds = Integer.parseInt(secParam.strip());
                    mgr.setKeepaliveSeconds(seconds);
                    out.print("{\"ok\":true,\"keepaliveSeconds\":" + mgr.getKeepaliveSeconds() + "}");
                } catch (NumberFormatException e) {
                    out.print("{\"error\":\"seconds must be an integer\"}");
                }
                return;
            }
            case "set-reconnect": {
                String secParam = req.getParameter("seconds");
                if (secParam == null || secParam.isBlank()) {
                    out.print("{\"error\":\"seconds required\"}");
                    return;
                }
                try {
                    int seconds = Integer.parseInt(secParam.strip());
                    mgr.setReconnectSeconds(seconds);
                    out.print("{\"ok\":true,\"reconnectSeconds\":" + mgr.getReconnectSeconds() + "}");
                } catch (NumberFormatException e) {
                    out.print("{\"error\":\"seconds must be an integer\"}");
                }
                return;
            }
            case "set-allowlist": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.PEER_ALLOWLIST.setValue(Boolean.parseBoolean(enabled.strip()));
                out.print("{\"ok\":true,\"peerAllowlist\":" + FederationProperties.PEER_ALLOWLIST.getValue() + "}");
                return;
            }
            case "set-block-direct-muc": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.BLOCK_DIRECT_MUC.setValue(Boolean.parseBoolean(enabled.strip()));
                out.print("{\"ok\":true,\"blockDirectMuc\":" + FederationProperties.BLOCK_DIRECT_MUC.getValue() + "}");
                return;
            }
            case "set-direct-relay": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.DIRECT_MSG_RELAY.setValue(Boolean.parseBoolean(enabled.strip()));
                out.print("{\"ok\":true,\"directMsgRelay\":" + FederationProperties.DIRECT_MSG_RELAY.getValue() + "}");
                return;
            }
            case "set-probe-on-subscribe": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.PROBE_ON_SUBSCRIBE.setValue(Boolean.parseBoolean(enabled.strip()));
                out.print("{\"ok\":true,\"probeOnSubscribe\":" + FederationProperties.PROBE_ON_SUBSCRIBE.getValue() + "}");
                return;
            }
            case "set-directory-publish": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.DIRECTORY_PUBLISH.setValue(Boolean.parseBoolean(enabled.strip()));
                // Push (or, when turning off, withdraw with an empty list) to peers immediately.
                mgr.publishDirectory();
                out.print("{\"ok\":true,\"directoryPublish\":" + FederationProperties.DIRECTORY_PUBLISH.getValue() + "}");
                return;
            }
            case "set-bookmark-push": {
                String enabled = req.getParameter("enabled");
                if (enabled == null) {
                    out.print("{\"error\":\"enabled required\"}");
                    return;
                }
                FederationProperties.BOOKMARK_PUSH.setValue(Boolean.parseBoolean(enabled.strip()));
                // Push (or, when turning off, withdraw with an empty list) to peers immediately.
                mgr.pushBookmarks();
                out.print("{\"ok\":true,\"bookmarkPush\":" + FederationProperties.BOOKMARK_PUSH.getValue() + "}");
                return;
            }
            case "push-bookmarks": {
                // Manual one-shot advertisement of our connected clients (independent of the toggle).
                mgr.pushBookmarksNow();
                out.print("{\"ok\":true}");
                return;
            }
            case "set-untrusted": {
                String domain    = req.getParameter("domain");
                String untrusted = req.getParameter("untrusted");
                if (domain == null || domain.isBlank() || untrusted == null) {
                    out.print("{\"error\":\"domain and untrusted required\"}");
                    return;
                }
                String d = domain.strip().toLowerCase();
                if (!mgr.getPeerRegistry().contains(d)) {
                    out.print("{\"error\":\"not a configured peer\"}");
                    return;
                }
                mgr.getPeerRegistry().setUntrusted(d, Boolean.parseBoolean(untrusted.strip()));
                // Negotiate the new stance over the link: announce it, and block or re-push
                // immediately based on the remote's last-known stance (trust is per-link).
                mgr.applyLocalTrustChange(d);
                out.print("{\"ok\":true}");
                return;
            }
            case "set-exposed-servers": {
                String domain  = req.getParameter("domain");
                String servers = req.getParameter("servers");  // comma-separated server domains ("" = none)
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                String d = domain.strip().toLowerCase();
                if (!mgr.getPeerRegistry().contains(d)) {
                    out.print("{\"error\":\"not a configured peer\"}");
                    return;
                }
                // Constrain to the legitimate candidate set: this server plus destinations not
                // reachable via the peer itself. This drops the peer's own domain / echoed servers
                // as defense-in-depth, so the persisted set stays meaningful.
                java.util.Set<String> allowed = mgr.exposableServers(d);
                java.util.List<String> list = new java.util.ArrayList<>();
                if (servers != null) {
                    for (String s : servers.split(",")) {
                        String srv = s.strip().toLowerCase();
                        if (!srv.isBlank() && allowed.contains(srv)) list.add(srv);
                    }
                }
                mgr.getPeerRegistry().setExposedServers(d, list);
                // Re-advertise the new exposed set + matching routes immediately.
                if (isReachable(mgr, d)) { mgr.sendRoutingUpdate(d); mgr.sendRoomState(d); }
                out.print("{\"ok\":true}");
                return;
            }
            case "accept-cert": {
                String domain = req.getParameter("domain");
                if (domain == null || domain.isBlank()) {
                    out.print("{\"error\":\"domain required\"}");
                    return;
                }
                String d = domain.strip().toLowerCase();
                if (!mgr.getPeerRegistry().contains(d)) {
                    out.print("{\"error\":\"not a configured peer\"}");
                    return;
                }
                mgr.acceptNewCertificate(d);
                out.print("{\"ok\":true}");
                return;
            }
            default:
                out.print("{\"error\":\"unknown action\"}");
        }
    }

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

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }

    /** True if the peer currently has a live (REACHABLE) federation link. */
    private boolean isReachable(FederationManager mgr, String domain) {
        return mgr.getPeerRegistry().getPeer(domain)
                  .map(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                  .orElse(false);
    }
}
