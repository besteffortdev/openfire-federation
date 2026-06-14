package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.FederatedRoom;
import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.model.RouteEntry;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jivesoftware.openfire.XMPPServer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

public class FederationApiServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");

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

        // ── peers ─────────────────────────────────────────────────────────────
        sb.append("\"peers\":[");
        boolean first = true;
        for (PeerServer p : mgr.getPeerRegistry().getPeers()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{")
              .append("\"domain\":\"").append(esc(p.getDomain())).append("\",")
              .append("\"status\":\"").append(p.getStatus().name()).append("\",")
              .append("\"lastSeen\":").append(p.getLastSeenMillis())
              .append("}");
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
              .append("\"occupants\":").append(room.get("occupants")).append(",")
              .append("\"mappings\":[");

            boolean fm = true;
            for (Map<String, String> m : roomMappings) {
                if (!fm) sb.append(",");
                fm = false;
                sb.append("{")
                  .append("\"remoteRoomJid\":\"").append(esc(m.get("remoteRoomJid"))).append("\",")
                  .append("\"remoteDomain\":\"").append(esc(m.get("remoteDomain"))).append("\"")
                  .append("}");
            }
            sb.append("]}");
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
                sb.append("{")
                  .append("\"remoteRoomJid\":\"").append(esc(m.remoteRoomJid())).append("\",")
                  .append("\"remoteDomain\":\"").append(esc(m.remoteDomain())).append("\"")
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
        sb.append("]");

        sb.append("}");
        out.print(sb.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache, no-store");

        FederationPlugin plugin = FederationPlugin.getInstance();
        PrintWriter out = resp.getWriter();

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
                mgr.addPeer(domain.strip().toLowerCase());
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
            case "map-room": {
                String localJid     = req.getParameter("localJid");
                String remoteJid    = req.getParameter("remoteJid");
                String remoteDomain = req.getParameter("remoteDomain");
                if (localJid == null || remoteJid == null || remoteDomain == null) {
                    out.print("{\"error\":\"localJid, remoteJid, remoteDomain required\"}");
                    return;
                }
                mgr.mapRooms(localJid.strip(), remoteJid.strip(), remoteDomain.strip());
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
            default:
                out.print("{\"error\":\"unknown action\"}");
        }
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
}
