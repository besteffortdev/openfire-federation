package com.igniterealtime.openfire.plugin.federation.files;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Public download endpoint for relayed federation files, mounted at
 * {@code /federation-files/<id>/<name>} on the HTTP-bind port (the same port clients already use
 * for BOSH/websocket and HTTP File Upload, so no new firewall surface).  Rewritten share URLs
 * point here; each client only ever downloads from its OWN server.
 *
 * Responses: 200 with the content once the overlay pull completed; 503 + Retry-After while the
 * transfer is still in flight (clients/users simply retry — content usually lands within seconds);
 * 403 with a fixed, generic explanation when the file was definitively rejected by a policy or
 * security check (extension not allowed, content-sniff mismatch, hash mismatch, AV) — deliberately
 * no detail beyond "see server logs" in this response, since the requester may be on a peer server
 * we don't fully trust; the specific reason is for a local admin only (Files tab → Activity Log →
 * Rejected files); 404 for ids this server was never told about. Content is immutable (id = hash
 * of the source URL), so successful responses are cacheable forever.
 */
public class FederationFileServlet extends HttpServlet {

    private final transient FileRelayManager relay;

    public FederationFileServlet(FileRelayManager relay) {
        this.relay = relay;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        serve(req, resp, true);
    }

    @Override
    protected void doHead(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        serve(req, resp, false);
    }

    private void serve(HttpServletRequest req, HttpServletResponse resp, boolean withBody)
            throws IOException {
        String path = req.getPathInfo();
        if (path == null || path.length() < 2) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        String[] parts = path.substring(1).split("/", 2);
        String id = parts[0].toLowerCase(Locale.ROOT);

        FileRelayManager.ServeInfo info = relay.lookup(id);
        switch (info.state()) {
            case OK -> {
                var sf = info.file();
                String mime = sf.mime() == null ? "" : sf.mime().toLowerCase(Locale.ROOT);
                boolean inline = mime.startsWith("image/") || mime.startsWith("video/")
                        || mime.startsWith("audio/") || mime.equals("application/pdf")
                        || mime.equals("text/plain");
                // Non-previewable types are forced to a download as application/octet-stream, and
                // everything is served sandboxed — a peer organisation's HTML/SVG must never be able
                // to run script on this server's HTTP origin.
                resp.setContentType(inline ? sf.mime() : "application/octet-stream");
                resp.setHeader("X-Content-Type-Options", "nosniff");
                resp.setHeader("Content-Security-Policy", "sandbox");
                resp.setHeader("Cache-Control", "private, max-age=31536000, immutable");
                String safeName = FileRelayManager.sanitizeFileName(sf.name());
                resp.setHeader("Content-Disposition",
                        (inline ? "inline" : "attachment") + "; filename=\"" + safeName + "\"");
                resp.setContentLengthLong(sf.size());
                if (withBody) {
                    Files.copy(info.path(), resp.getOutputStream());
                }
            }
            case PENDING -> {
                // Not sendError(): some containers reset headers there, and Retry-After must survive.
                resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                resp.setHeader("Retry-After", "3");
                resp.setContentType("text/plain");
                if (withBody) {
                    resp.getWriter().write("File transfer in progress - retry shortly");
                }
            }
            case REJECTED -> {
                // Not sendError(): some containers reset headers/body there.
                resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                resp.setContentType("text/plain");
                if (withBody) {
                    resp.getWriter().write("File failed upload check - see server logs");
                }
            }
            default -> resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
