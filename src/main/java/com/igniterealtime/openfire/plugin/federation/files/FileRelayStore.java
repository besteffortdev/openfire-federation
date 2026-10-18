package com.igniterealtime.openfire.plugin.federation.files;

import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk store for relayed file content, keyed by transfer id, in the configured storage directory.
 * Each file has a {@code <id>.meta} sidecar; transfers assemble into {@code <id>.part} and are moved
 * into place when complete, so the index only ever holds complete files.
 */
final class FileRelayStore {

    private static final Logger Log = LoggerFactory.getLogger(FileRelayStore.class);

    /** One stored file; {@code token} is its share capability, empty for files stored before tokens existed. */
    record StoredFile(String id, String name, String mime, long size, String sha256, String token,
                      long storedAt) {}

    private final Map<String, StoredFile> index = new ConcurrentHashMap<>();
    private volatile Path baseDir;

    /** The configured storage directory, or the default when the setting is not an absolute path. */
    static Path resolveConfiguredDir() {
        String configured = FederationProperties.FILES_STORAGE_DIR.getValue();
        String fallback = FederationProperties.FILES_STORAGE_DIR.getDefaultValue();
        if (configured == null || configured.isBlank()) configured = fallback;
        Path p = Path.of(configured.strip());
        if (!p.isAbsolute()) {
            Log.warn("files.storageDir '{}' is not an absolute path — using default {}", configured, fallback);
            p = Path.of(fallback);
        }
        return p;
    }

    /** Opens the configured directory, restricts it to the owner and indexes the stored files. */
    synchronized void init() throws IOException {
        Path dir = resolveConfiguredDir();
        Files.createDirectories(dir);
        restrictToOwner(dir);
        baseDir = dir;
        int loaded = reindex();
        Log.info("File relay store initialised at {} — {} file(s)", baseDir, loaded);
    }

    /**
     * Restricts the storage directory to its owner (CERT FIO01-J), since it holds other people's
     * files. A filesystem without POSIX permissions is not an error.
     */
    private static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            Log.debug("Could not restrict permissions on file relay store {}", dir, e);
        }
    }

    /**
     * Moves the complete files to a newly configured directory, or opens the store if it never
     * opened. All files are copied before the switch, so a failed move loses nothing. Returns null on success.
     */
    synchronized String reopenIfMoved() {
        Path newDir = resolveConfiguredDir();
        Path oldDir = baseDir;
        if (oldDir == null) {
            try {
                init();
                return null;
            } catch (IOException e) {
                Log.error("Could not open file relay store at {}", newDir, e);
                return "Could not use directory '" + newDir + "': " + e.getMessage();
            }
        }
        if (newDir.equals(oldDir)) return null;
        List<Path> copied = new ArrayList<>();
        List<Path> originals = new ArrayList<>();
        try {
            Files.createDirectories(newDir);
            restrictToOwner(newDir);
            int migrated = 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(oldDir, "*.meta")) {
                for (Path meta : ds) {
                    String metaName = meta.getFileName().toString();
                    String id = metaName.substring(0, metaName.length() - ".meta".length());
                    Path content = oldDir.resolve(id);
                    if (Files.isRegularFile(content)) {
                        Path newContent = newDir.resolve(id);
                        Path newMeta = newDir.resolve(metaName);
                        Files.copy(content, newContent, StandardCopyOption.REPLACE_EXISTING);
                        copied.add(newContent);
                        Files.copy(meta, newMeta, StandardCopyOption.REPLACE_EXISTING);
                        copied.add(newMeta);
                        originals.add(content);
                        originals.add(meta);
                        migrated++;
                    }
                }
            }
            baseDir = newDir;
            int loaded = reindex();
            int reclaimed = deleteMigratedOriginals(originals);
            Log.info("File relay store moved from {} to {} — {} entrie(s) migrated, {} indexed, "
                    + "{} old file(s) reclaimed", oldDir, newDir, migrated, loaded, reclaimed);
            return null;
        } catch (IOException | RuntimeException e) {
            for (Path p : copied) deleteQuietly(p);
            Log.error("Could not move file relay store from {} to {} (rolled back, still serving {})",
                    oldDir, newDir, oldDir, e);
            return "Could not use directory '" + newDir + "': " + e.getMessage();
        }
    }

    /**
     * Deletes the old copies of the files that were moved, and only those, since a file finalized
     * during the move exists only there. Returns how many content files were deleted.
     */
    private int deleteMigratedOriginals(List<Path> originals) {
        int reclaimed = 0;
        for (Path p : originals) {
            try {
                if (Files.deleteIfExists(p) && !p.getFileName().toString().endsWith(".meta")) reclaimed++;
            } catch (IOException e) {
                Log.debug("Could not reclaim old relay file {}", p, e);
            }
        }
        return reclaimed;
    }

    private int reindex() throws IOException {
        index.clear();
        int loaded = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.meta")) {
            for (Path meta : ds) {
                StoredFile sf = readMeta(meta);
                if (sf != null && Files.isRegularFile(contentPath(sf.id()))) {
                    index.put(sf.id(), sf);
                    loaded++;
                }
            }
        }
        return loaded;
    }

    /** Whether a directory has been opened; the relay is unusable until then. */
    boolean isOpen()            { return baseDir != null; }
    boolean has(String id)      { return index.containsKey(id); }
    StoredFile get(String id)   { return index.get(id); }
    Path contentPath(String id) { return baseDir.resolve(id); }
    Path partPath(String id)    { return baseDir.resolve(id + ".part"); }

    /**
     * Moves an assembled part file into place and indexes it. Synchronized with
     * {@link #reopenIfMoved}, so both paths resolve against the same directory.
     */
    synchronized StoredFile finalizePart(String id, String name, String mime, long size,
                                         String sha256, String token) throws IOException {
        Files.move(partPath(id), contentPath(id), StandardCopyOption.REPLACE_EXISTING);
        StoredFile sf = new StoredFile(id, name, mime, size, sha256,
                token == null ? "" : token, System.currentTimeMillis());
        writeMeta(sf);
        index.put(id, sf);
        return sf;
    }

    /** Deletes a file copied by a move that then failed; a leftover only wastes space. */
    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            Log.debug("Could not delete {} after a failed move", p, e);
        }
    }

    /** Deletes a transfer's part file, if any. */
    void deletePart(String id) {
        try {
            Files.deleteIfExists(partPath(id));
        } catch (IOException e) {
            Log.debug("Could not delete part file for {}", id, e);
        }
    }

    /** Deletes files older than {@code maxAgeMillis} and part files older than a day; returns how many files went. */
    synchronized int purgeOlderThan(long maxAgeMillis) {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (StoredFile sf : index.values().toArray(new StoredFile[0])) {
            if (now - sf.storedAt() > maxAgeMillis) {
                index.remove(sf.id());
                try {
                    Files.deleteIfExists(contentPath(sf.id()));
                    Files.deleteIfExists(baseDir.resolve(sf.id() + ".meta"));
                    removed++;
                } catch (IOException e) {
                    Log.warn("Could not purge relay file {}", sf.id(), e);
                }
            }
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.part")) {
            for (Path part : ds) {
                if (now - Files.getLastModifiedTime(part).toMillis() > 24 * 3600_000L) {
                    Files.deleteIfExists(part);
                }
            }
        } catch (IOException e) {
            Log.debug("Part-file sweep failed", e);
        }
        return removed;
    }

    private void writeMeta(StoredFile sf) throws IOException {
        Properties p = new Properties();
        p.setProperty("name",     sf.name());
        p.setProperty("mime",     sf.mime());
        p.setProperty("size",     Long.toString(sf.size()));
        p.setProperty("sha256",   sf.sha256() != null ? sf.sha256() : "");
        // The token is why the directory is owner-only: anyone reading it could request the file.
        p.setProperty("token",    sf.token() != null ? sf.token() : "");
        p.setProperty("storedAt", Long.toString(sf.storedAt()));
        try (OutputStream out = Files.newOutputStream(baseDir.resolve(sf.id() + ".meta"))) {
            p.store(out, null);
        }
    }

    private StoredFile readMeta(Path metaPath) {
        String fileName = metaPath.getFileName().toString();
        String id = fileName.substring(0, fileName.length() - ".meta".length());
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(metaPath)) {
            p.load(in);
            return new StoredFile(id,
                    p.getProperty("name", "file"),
                    p.getProperty("mime", "application/octet-stream"),
                    Long.parseLong(p.getProperty("size", "0")),
                    p.getProperty("sha256", ""),
                    p.getProperty("token", ""),
                    Long.parseLong(p.getProperty("storedAt", "0")));
        } catch (IOException | NumberFormatException e) {
            Log.warn("Unreadable relay meta {} — skipping", metaPath, e);
            return null;
        }
    }
}
