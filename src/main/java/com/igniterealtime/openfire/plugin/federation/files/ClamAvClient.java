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
 * Client for clamd's INSTREAM protocol (see
 * <a href="https://docs.clamav.net/manual/Usage/Scanning.html#stream-scanning">ClamAV docs</a>).
 *
 * <p>This is wire glue, not a virus-detection reimplementation — ClamAV itself is the "wheel"
 * being reused, running as a local {@code clamd} daemon (the {@code clamav/clamav} sidecar added
 * to each server's docker-compose.yml, reachable only from the Openfire container on the same
 * bridge network). Scanning is fully offline: clamd scans against whichever signature database it
 * already has cached on disk regardless of current connectivity — the sidecar's bundled freshclam
 * only needs the network to REFRESH those definitions later, never to perform a scan.
 *
 * <p>Protocol: {@code zINSTREAM\0}, then the file as 4-byte-big-endian-length-prefixed chunks,
 * terminated by a zero-length chunk; clamd replies with a NUL-terminated status line.
 */
final class ClamAvClient {

    private static final Logger Log = LoggerFactory.getLogger(ClamAvClient.class);

    /** Chunk size for the INSTREAM wire protocol — independent of the relay's own chunkBytes setting. */
    private static final int STREAM_CHUNK = 8192;

    enum Verdict { CLEAN, INFECTED, ERROR }

    record ScanResult(Verdict verdict, String detail) {
        static ScanResult clean() { return new ScanResult(Verdict.CLEAN, null); }
    }

    private ClamAvClient() {}

    /**
     * Streams {@code file} to clamd and returns the verdict. Fails closed: any connection,
     * protocol, or I/O problem comes back as {@link Verdict#ERROR} rather than silently passing
     * the file through — a caller that can't confirm a file is clean must treat it as not clean.
     */
    static ScanResult scan(Path file) {
        String host = FederationProperties.FILES_AV_HOST.getValue();
        int port = FederationProperties.FILES_AV_PORT.getValue();
        int timeoutMs = FederationProperties.FILES_AV_TIMEOUT_MS.getValue();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeBytes("zINSTREAM\0");
            try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buf = new byte[STREAM_CHUNK];
                int n;
                while ((n = fileIn.read(buf)) > 0) {
                    out.writeInt(n);   // 4-byte big-endian length prefix, per clamd's INSTREAM framing
                    out.write(buf, 0, n);
                }
            }
            out.writeInt(0);          // zero-length chunk terminates the stream
            out.flush();
            return interpret(readNulTerminated(socket.getInputStream()));
        } catch (Exception e) {
            Log.warn("File relay: AV scan of {} failed (clamd {}:{}): {}", file, host, port, e.getMessage());
            return new ScanResult(Verdict.ERROR, e.getMessage());
        }
    }

    /** Confirms clamd is reachable and responsive (used by the admin UI's "test connection" action). */
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
        } catch (Exception e) {
            Log.debug("File relay: AV ping to {}:{} failed: {}", host, port, e.getMessage());
            return false;
        }
    }

    private static String readNulTerminated(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0) {   // clamd NUL-terminates z-command replies; 0 and -1(EOF) both stop
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static ScanResult interpret(String response) {
        String r = response.strip();
        // Typical replies: "stream: OK", "stream: Eicar-Test-Signature FOUND", "stream: <msg> ERROR".
        if (r.endsWith("OK")) return ScanResult.clean();
        if (r.contains("FOUND")) {
            String signature = r.replaceFirst("^stream:\\s*", "").replace("FOUND", "").strip();
            return new ScanResult(Verdict.INFECTED, signature);
        }
        return new ScanResult(Verdict.ERROR, r.isEmpty() ? "empty response from clamd" : r);
    }
}
