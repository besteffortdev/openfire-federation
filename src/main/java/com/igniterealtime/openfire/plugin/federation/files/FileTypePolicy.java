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
 * File type checks of the relay: the extension allowlist, applied at both ends, and a content sniff
 * on arrival (Apache Tika) that rejects a file whose bytes clearly contradict its extension.
 * Inconclusive sniffs pass; transit servers never call either check.
 */
final class FileTypePolicy {

    private static final Logger Log = LoggerFactory.getLogger(FileTypePolicy.class);

    private static final Tika TIKA = new Tika();
    private static final MimeTypes MIME_TYPES = MimeTypes.getDefaultMimeTypes();

    /** Tika's answers when it cannot tell, which are not grounds for rejection. */
    private static final Set<String> GENERIC_FALLBACK_MIMES =
            Set.of("application/octet-stream", "text/plain");

    /** The ISO-BMFF family, whose sniffed type does not tell audio from video, so any pairing within it passes. */
    private static final Set<String> ISO_BMFF_EXTENSIONS =
            Set.of("mp4", "m4v", "m4a", "m4b", "m4p", "m4r", "mov");
    private static final Set<String> ISO_BMFF_MIMES =
            Set.of("video/mp4", "audio/mp4", "video/quicktime");

    private FileTypePolicy() {}

    /** The extension of {@code fileName}, lowercased and without the dot, or empty. */
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

    /** Whether the file's extension is allowed: an empty list allows nothing, and {@code *} everything. */
    static boolean isExtensionAllowed(String fileName) {
        Set<String> allowed = configuredExtensions();
        if (allowed.contains("*")) return true;
        return allowed.contains(extensionOf(fileName));
    }

    /** The result of a content check; {@code detectedMime} is null only when the file could not be read. */
    record ContentCheck(boolean ok, String detectedMime) { }

    /**
     * Sniffs the file's bytes and checks them against the claimed extension. Fails only on a clear
     * mismatch, an unreadable file or a missing extension.
     */
    static ContentCheck checkContent(Path file, String claimedFileName) {
        String ext = extensionOf(claimedFileName);
        if (ext.isEmpty()) {
            Log.warn("File relay: '{}' has no extension to verify content against", claimedFileName);
            return new ContentCheck(false, null);
        }
        String detected;
        try (InputStream in = Files.newInputStream(file)) {
            detected = TIKA.detect(in);
        } catch (IOException e) {
            Log.warn("File relay: content sniff failed for {}", file, e);
            return new ContentCheck(false, null);
        }
        if (detected == null || GENERIC_FALLBACK_MIMES.contains(detected)) {
            return new ContentCheck(true, detected);
        }
        if (ISO_BMFF_MIMES.contains(detected) && ISO_BMFF_EXTENSIONS.contains(ext)) {
            return new ContentCheck(true, detected);
        }
        try {
            MimeType mt = MIME_TYPES.forName(detected);
            for (String candidateExt : mt.getExtensions()) {
                String stripped = candidateExt.startsWith(".") ? candidateExt.substring(1) : candidateExt;
                if (stripped.equalsIgnoreCase(ext)) return new ContentCheck(true, detected);
            }
        } catch (MimeTypeException e) {
            // A type Tika itself does not list has no extensions to contradict the claim.
            return new ContentCheck(true, detected);
        }
        Log.warn("File relay: content mismatch for '{}' — claims .{} but sniffs as '{}'",
                 claimedFileName, ext, detected);
        return new ContentCheck(false, detected);
    }
}
