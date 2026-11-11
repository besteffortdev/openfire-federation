package com.igniterealtime.openfire.plugin.federation.files;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The checks between a peer-supplied id or file name and a path in the storage directory (CERT FIO16-J). */
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

    @Test
    @DisplayName("an upload URL may only name the scheme's default port or one of the upload service's")
    void uploadPorts() {
        assertTrue(FileRelayManager.portAllowed(-1, Set.of(7070, 7443)));
        assertTrue(FileRelayManager.portAllowed(7443, Set.of(7070, 7443)));

        assertFalse(FileRelayManager.portAllowed(9090, Set.of(7070, 7443)), "the admin console");
        assertFalse(FileRelayManager.portAllowed(5432, Set.of(7070, 7443)));
    }

    @Test
    @DisplayName("a storage directory is ours to restrict when it holds relay files only")
    void recognisesRelayDirectories(@TempDir Path dir) throws IOException {
        assertTrue(FileRelayStore.holdsOnlyRelayFiles(dir), "empty");

        Files.createFile(dir.resolve(ID));
        Files.createFile(dir.resolve(ID + ".meta"));
        Files.createFile(dir.resolve(ID + ".part"));
        assertTrue(FileRelayStore.holdsOnlyRelayFiles(dir));

        Files.createFile(dir.resolve("openfire.script"));
        assertFalse(FileRelayStore.holdsOnlyRelayFiles(dir), "someone else's directory");
    }

    @Test
    @DisplayName("a Range header yields the bytes to serve, clipped to the file")
    void parsesRanges() {
        assertArrayEquals(new long[] {0, 99}, FederationFileServlet.parseRange("bytes=0-99", 1000));
        assertArrayEquals(new long[] {500, 999}, FederationFileServlet.parseRange("bytes=500-", 1000));
        assertArrayEquals(new long[] {900, 999}, FederationFileServlet.parseRange("bytes=-100", 1000));
        assertArrayEquals(new long[] {990, 999}, FederationFileServlet.parseRange("bytes=990-5000", 1000));
    }

    @Test
    @DisplayName("no Range, several ranges or nonsense serve the whole file; a range past the end is refused")
    void rangeEdgeCases() {
        assertNull(FederationFileServlet.parseRange(null, 1000));
        assertNull(FederationFileServlet.parseRange("bytes=0-1,5-9", 1000));
        assertNull(FederationFileServlet.parseRange("bytes=abc-", 1000));
        assertNull(FederationFileServlet.parseRange("items=0-1", 1000));

        assertSame(FederationFileServlet.UNSATISFIABLE, FederationFileServlet.parseRange("bytes=1000-", 1000));
        assertSame(FederationFileServlet.UNSATISFIABLE, FederationFileServlet.parseRange("bytes=9-3", 1000));
        assertSame(FederationFileServlet.UNSATISFIABLE, FederationFileServlet.parseRange("bytes=0-", 0));
    }
}
