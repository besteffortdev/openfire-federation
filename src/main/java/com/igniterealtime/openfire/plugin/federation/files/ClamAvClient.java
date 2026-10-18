package com.igniterealtime.openfire.plugin.federation.files;

import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client for clamd's
 * <a href="https://docs.clamav.net/manual/Usage/Scanning.html#stream-scanning">INSTREAM protocol</a>:
 * the file goes as length-prefixed chunks ending with an empty one, and clamd answers with one
 * status line. Any failure counts as not clean.
 */
final class ClamAvClient {

    private static final Logger Log = LoggerFactory.getLogger(ClamAvClient.class);

    /** Chunk size on the INSTREAM socket; unrelated to the relay's own chunk size. */
    private static final int STREAM_CHUNK = 8192;

    /** The outcome of a scan; ERROR means the scan could not complete. */
    enum Verdict { CLEAN, INFECTED, ERROR }

    /** A verdict with clamd's detail: the signature found, or the error. */
    record ScanResult(Verdict verdict, String detail) {
        static ScanResult clean() { return new ScanResult(Verdict.CLEAN, null); }
    }

    private ClamAvClient() {}

    /**
     * Streams {@code file} to clamd and returns the verdict; any problem is {@link Verdict#ERROR}. A
     * watchdog closes the socket at an absolute deadline, since socket timeouts bound neither writes
     * nor the whole exchange.
     */
    static ScanResult scan(Path file) {
        String host = FederationProperties.FILES_AV_HOST.getValue();
        int port = FederationProperties.FILES_AV_PORT.getValue();
        int timeoutMs = FederationProperties.FILES_AV_TIMEOUT_MS.getValue();
        long overallMs = overallDeadlineMs(file, timeoutMs);
        long deadline = System.currentTimeMillis() + overallMs;
        Socket socket = new Socket();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(Math.max(1L, deadline - System.currentTimeMillis()));
                if (!socket.isClosed()) {
                    Log.warn("File relay: AV scan of {} exceeded {} ms — closing clamd socket", file, overallMs);
                    closeQuietly(socket);
                }
            } catch (InterruptedException e) {
                // The scan finished first and stopped the watchdog, which now ends.
                Thread.currentThread().interrupt();
            }
        }, "federation-av-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (socket) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeBytes("zINSTREAM\0");
            try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[STREAM_CHUNK];
                int n;
                while ((n = fileIn.read(buf)) > 0) {
                    out.writeInt(n);
                    out.write(buf, 0, n);
                }
            }
            out.writeInt(0);
            out.flush();
            return interpret(readNulTerminated(socket.getInputStream()));
        } catch (IOException | RuntimeException e) {
            Log.warn("File relay: AV scan of {} failed (clamd {}:{})", file, host, port, e);
            return new ScanResult(Verdict.ERROR, e.getMessage());
        } finally {
            watchdog.interrupt();
        }
    }

    /** The absolute scan budget: the per-operation timeout plus the file size at a 256 KiB/s floor. */
    private static long overallDeadlineMs(Path file, int perOpTimeoutMs) {
        long size = 0;
        try {
            size = Files.size(file);
        } catch (IOException ignored) {
            // Unreadable: the per-operation timeout alone bounds the scan, which then reports the error.
        }
        long streamingBudgetMs = (size / (256L * 1024L)) * 1000L;
        return perOpTimeoutMs + streamingBudgetMs;
    }

    /** Whether clamd answers a PING, for the admin console's connection test. */
    static boolean ping() {
        String host = FederationProperties.FILES_AV_HOST.getValue();
        int port = FederationProperties.FILES_AV_PORT.getValue();
        int timeoutMs = FederationProperties.FILES_AV_TIMEOUT_MS.getValue();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return "PONG".equals(readNulTerminated(socket.getInputStream()).strip());
        } catch (IOException | RuntimeException e) {
            Log.debug("File relay: AV ping to {}:{} failed", host, port, e);
            return false;
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The point is to break the scan's blocked read or write, which a failed close does too.
        }
    }

    private static String readNulTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static ScanResult interpret(String response) {
        String r = response.strip();
        if (r.endsWith("OK")) return ScanResult.clean();
        if (r.contains("FOUND")) {
            String signature = r.replaceFirst("^stream:\\s*", "").replace("FOUND", "").strip();
            return new ScanResult(Verdict.INFECTED, signature);
        }
        return new ScanResult(Verdict.ERROR, r.isEmpty() ? "empty response from clamd" : r);
    }
}
