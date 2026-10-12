package com.igniterealtime.openfire.plugin.federation.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two gates between a peer-supplied string and a path in the spool (CERT FIO16-J). A transfer
 * id reaches {@code baseDir.resolve(id)} only through {@code isHexId}; a file name is shown and
 * stored only through {@code sanitizeFileName}.
 */
class FileRelayManagerTest {

    private static final String ID = "0123456789abcdef".repeat(4);

    @Test
    @DisplayName("a transfer id is exactly 64 lowercase hex characters")
    void acceptsOnlyHexIds() {
        assertTrue(FileRelayManager.isHexId(ID));

        assertFalse(FileRelayManager.isHexId(null));
        assertFalse(FileRelayManager.isHexId(ID.substring(1)));
        assertFalse(FileRelayManager.isHexId(ID + "0"));
        assertFalse(FileRelayManager.isHexId(ID.toUpperCase()));
    }

    @Test
    @DisplayName("an id cannot carry a path, even padded to the right length")
    void refusesPathsDisguisedAsIds() {
        String traversal = "../../../../etc/passwd";
        String padded = traversal + "0".repeat(64 - traversal.length());

        assertEquals(64, padded.length());
        assertFalse(FileRelayManager.isHexId(padded));
        assertFalse(FileRelayManager.isHexId("0".repeat(63) + "/"));
    }

    @Test
    @DisplayName("a file name loses separators, quotes and control characters")
    void sanitizesFileNames() {
        String clean = FileRelayManager.sanitizeFileName("../../etc/pass\\wd");

        assertFalse(clean.contains("/"), clean);
        assertFalse(clean.contains("\\"), clean);
        assertEquals("abcd.txt", FileRelayManager.sanitizeFileName("a\"b'c\nd.txt"));
    }

    @Test
    @DisplayName("a name that would mean a directory falls back to 'file'")
    void refusesDirectoryNames() {
        assertEquals("file", FileRelayManager.sanitizeFileName(null));
        assertEquals("file", FileRelayManager.sanitizeFileName(""));
        assertEquals("file", FileRelayManager.sanitizeFileName("."));
        assertEquals("file", FileRelayManager.sanitizeFileName(".."));
        assertEquals("file", FileRelayManager.sanitizeFileName("/"));
    }

    @Test
    @DisplayName("an over-long name keeps its tail, so the extension survives")
    void capsLongNamesFromTheEnd() {
        String clean = FileRelayManager.sanitizeFileName("x".repeat(500) + ".pdf");

        assertEquals(200, clean.length());
        assertTrue(clean.endsWith(".pdf"));
    }
}
