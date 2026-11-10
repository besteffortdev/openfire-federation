package com.igniterealtime.openfire.plugin.federation.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** Marks a Range header that names no byte of the file. */
    static final long[] UNSATISFIABLE = new long[0];

    /**
     * The first and last byte a {@code Range} header asks for, so a player can seek and a download
     * resume. Null serves the whole file: no header, several ranges, or one that does not parse.
     */
    static long[] parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=") || header.indexOf(',') >= 0) return null;
        String spec = header.substring("bytes=".length()).strip();
        int dash = spec.indexOf('-');
        if (dash < 0) return null;
        try {
            String first = spec.substring(0, dash).strip();
            String last = spec.substring(dash + 1).strip();
            long from;
            long to;
            if (first.isEmpty()) {
                if (last.isEmpty()) return null;
                long suffix = Long.parseLong(last);
                if (suffix <= 0) return UNSATISFIABLE;
                from = Math.max(0, size - suffix);
                to = size - 1;
            } else {
                from = Long.parseLong(first);
                to = last.isEmpty() ? size - 1 : Math.min(Long.parseLong(last), size - 1);
            }
            if (from < 0 || from >= size || to < from) return UNSATISFIABLE;
            return new long[] {from, to};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void copyRange(Path file, long from, long to, OutputStream out) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(from);
            byte[] buf = new byte[64 * 1024];
            long left = to - from + 1;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
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
                resp.setHeader("Accept-Ranges", "bytes");
                long[] range = parseRange(req.getHeader("Range"), sf.size());
                if (range == UNSATISFIABLE) {
                    resp.setHeader("Content-Range", "bytes */" + sf.size());
                    resp.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                    return;
                }
                if (range == null) {
                    resp.setContentLengthLong(sf.size());
                    if (withBody) Files.copy(info.path(), resp.getOutputStream());
                    return;
                }
                resp.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                resp.setHeader("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + sf.size());
                resp.setContentLengthLong(range[1] - range[0] + 1);
                if (withBody) copyRange(info.path(), range[0], range[1], resp.getOutputStream());
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
