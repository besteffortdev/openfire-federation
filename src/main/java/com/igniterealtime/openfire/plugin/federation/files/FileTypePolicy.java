package com.igniterealtime.openfire.plugin.federation.files;

import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypeException;
import org.apache.tika.mime.MimeTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Type-filtering policy for the file relay.
 *
 * <ul>
 *   <li><b>Extension allowlist</b> ({@link #isExtensionAllowed}) — enforced at the origin
 *       (egress: a file whose extension isn't on the list is never staged, so it never leaves
 *       this server) and again at the destination (ingress: defense in depth against a peer whose
 *       own egress filter is absent, bypassed, or an older plugin version).</li>
 *   <li><b>Content verification</b> ({@link #matchesContent}) — ingress only. Sniffs the received
 *       bytes by magic number (Apache Tika, no filename hint) and rejects a confident, specific
 *       mismatch against the claimed extension (e.g. a Windows executable renamed to {@code .jpg}).
 *       Tika's generic fallback answers ({@code application/octet-stream}, {@code text/plain} —
 *       "I can't tell") are treated as inconclusive, not a mismatch: byte-signature detection
 *       cannot distinguish plain text from a text-based script, so that class of disguise is left
 *       to the servlet's existing controls ({@code Content-Security-Policy: sandbox} +
 *       {@code X-Content-Type-Options: nosniff} on every response — see
 *       {@link FederationFileServlet}), not re-solved here.</li>
 * </ul>
 *
 * <p>Deliberately reuses Tika's own {@code MimeTypes} registry for the extension↔MIME-type
 * relationship rather than hand-maintaining a parallel table — one allowlist (extensions, the
 * form an admin actually wants to type) covers both nouns in "extension / mime type filtering".
 *
 * <p>Transit hops never call either method: {@link FileRelayManager#relayToward} forwards file-*
 * elements without ever decoding their content, so a purely-relaying server is unaffected by this
 * policy regardless of configuration (matches the file relay's existing "transit never stores"
 * design).
 */
final class FileTypePolicy {

    private static final Logger Log = LoggerFactory.getLogger(FileTypePolicy.class);

    private static final Tika TIKA = new Tika();
    private static final MimeTypes MIME_TYPES = MimeTypes.getDefaultMimeTypes();

    /** Tika's "I couldn't determine anything more specific" answers — not a basis for rejection. */
    private static final Set<String> GENERIC_FALLBACK_MIMES =
            Set.of("application/octet-stream", "text/plain");

    /**
     * ISO-BMFF container family: {@code .m4a}/{@code .m4b}/{@code .m4p}/{@code .m4r} (audio),
     * {@code .mp4}/{@code .m4v} (video), and {@code .mov} (QuickTime) are all boxes of the same
     * underlying container format. Tika's magic-byte sniff picks a MIME type from the {@code ftyp}
     * box's brand, which is not a reliable audio-vs-video signal in practice — real voice-memo
     * recordings routinely sniff as {@code video/mp4} or {@code video/quicktime} despite carrying
     * no video track. Treat any extension in this family as consistent with any detected MIME type
     * in this family, rather than flagging genuine recordings as forged/renamed files.
     */
    private static final Set<String> ISO_BMFF_EXTENSIONS =
            Set.of("mp4", "m4v", "m4a", "m4b", "m4p", "m4r", "mov");
    private static final Set<String> ISO_BMFF_MIMES =
            Set.of("video/mp4", "audio/mp4", "video/quicktime");

    private FileTypePolicy() {}

    /** Extension of {@code fileName}, lowercased and without the dot; "" when there isn't one. */
    static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (dot <= slash || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> configuredExtensions() {
        String raw = FederationProperties.FILES_ALLOWED_EXTENSIONS.getValue();
        if (raw == null) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Is this file's extension on the configured allowlist? A blank/empty allowlist allows
     * nothing (secure by default — an admin who clears the field is turning file types off, not
     * opening them up); the literal entry {@code *} allows everything.
     */
    static boolean isExtensionAllowed(String fileName) {
        Set<String> allowed = configuredExtensions();
        if (allowed.contains("*")) return true;
        return allowed.contains(extensionOf(fileName));
    }

    /**
     * Sniffs {@code file}'s actual bytes (magic number only — no filename hint) and checks the
     * result is consistent with {@code claimedFileName}'s extension. Returns false only on a
     * confident, specific mismatch or an unreadable file; an inconclusive (generic-fallback)
     * detection returns true.
     */
    static boolean matchesContent(Path file, String claimedFileName) {
        String ext = extensionOf(claimedFileName);
        if (ext.isEmpty()) {
            Log.warn("File relay: '{}' has no extension to verify content against", claimedFileName);
            return false;
        }
        String detected;
        try (InputStream in = Files.newInputStream(file)) {
            detected = TIKA.detect(in);
        } catch (IOException e) {
            Log.warn("File relay: content sniff failed for {}: {}", file, e.getMessage());
            return false;
        }
        if (detected == null || GENERIC_FALLBACK_MIMES.contains(detected)) {
            return true;
        }
        if (ISO_BMFF_MIMES.contains(detected) && ISO_BMFF_EXTENSIONS.contains(ext)) {
            return true;
        }
        try {
            MimeType mt = MIME_TYPES.forName(detected);
            for (String candidateExt : mt.getExtensions()) {
                String stripped = candidateExt.startsWith(".") ? candidateExt.substring(1) : candidateExt;
                if (stripped.equalsIgnoreCase(ext)) return true;
            }
        } catch (MimeTypeException e) {
            // Detected type isn't itself in Tika's registry (rare) — no extension list to compare
            // against, so there's nothing to contradict the claim with.
            return true;
        }
        Log.warn("File relay: content mismatch for '{}' — claims .{} but sniffs as '{}'",
                 claimedFileName, ext, detected);
        return false;
    }
}
