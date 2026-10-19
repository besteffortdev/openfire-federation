package com.igniterealtime.openfire.plugin.federation.files;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Download endpoint for relayed files at {@code /federation-files/<id>/<name>} on the HTTP-bind
 * port. Answers 200 with the file, 503 while it is still arriving, 403 with a generic text when it
 * was rejected, and 404 otherwise; the reason for a rejection is only in the server log.
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
                // Sandboxed, and other types forced to download, so a peer's HTML or SVG cannot run script here.
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
                // Not sendError(), which some containers use to reset headers such as Retry-After.
                resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                resp.setHeader("Retry-After", "3");
                resp.setContentType("text/plain");
                if (withBody) {
                    resp.getWriter().write("File transfer in progress - retry shortly");
                }
            }
            case REJECTED -> {
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
