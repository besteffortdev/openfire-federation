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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed store for federated file content, addressed by the relay id (SHA-256 hex of the
 * original upload URL).  Lives in the absolute directory named by
 * {@code plugin.federation.files.storageDir} (default {@code /var/lib/openfire/federation-files}):
 * each entry is a content file named {@code <id>} plus a {@code <id>.meta} properties sidecar
 * (name, mime, size, sha256, storedAt).  In-flight transfers assemble into {@code <id>.part} and
 * are atomically moved into place on completion, so the index only ever sees verified, complete
 * entries.
 */
final class FileRelayStore {

    private static final Logger Log = LoggerFactory.getLogger(FileRelayStore.class);

    record StoredFile(String id, String name, String mime, long size, String sha256, long storedAt) {}

    private final Map<String, StoredFile> index = new ConcurrentHashMap<>();
    private volatile Path baseDir;

    static Path resolveConfiguredDir() {
        String configured = FederationProperties.FILES_STORAGE_DIR.getValue();
        String fallback = FederationProperties.FILES_STORAGE_DIR.getDefaultValue();
        if (configured == null || configured.isBlank()) configured = fallback;
        Path p = Path.of(configured.strip());
        if (!p.isAbsolute()) {
            // The setting is defined as a full path; a relative value can only get here by being
            // written straight into the system property, bypassing the validated surfaces.
            Log.warn("files.storageDir '{}' is not an absolute path — using default {}", configured, fallback);
            p = Path.of(fallback);
        }
        return p;
    }

    synchronized void init() throws IOException {
        Path dir = resolveConfiguredDir();
        Files.createDirectories(dir);
        baseDir = dir;
        int loaded = reindex();
        Log.info("File relay store initialised at {} — {} file(s)", baseDir, loaded);
    }

    /**
     * Re-resolves the configured directory and, when it changed, migrates every complete entry
     * there and re-indexes. In-flight {@code .part} files stay behind (those transfers fail and
     * re-request into the new location). Returns null on success, else an error message.
     *
     * <p>The migration is transactional from a reader's point of view: entries are <em>copied</em>
     * to the new directory first, and {@code baseDir} only switches once every copy has succeeded.
     * If any copy fails part-way (disk full, cross-filesystem error, permissions), the partial
     * copies in the new directory are rolled back and the store keeps serving from the old
     * directory with all entries intact — a failed migration can never strand or lose a file. Once
     * the switch is committed, the old originals are deleted best-effort (leftover old files are
     * harmless; the new directory is already authoritative).
     */
    synchronized String reopenIfMoved() {
        Path newDir = resolveConfiguredDir();
        Path oldDir = baseDir;
        if (oldDir == null || newDir.equals(oldDir)) return null;
        List<Path> copied = new ArrayList<>();   // new-dir paths written, for rollback on failure
        try {
            Files.createDirectories(newDir);
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
                        migrated++;
                    }
                }
            }
            // Every entry is now safely in newDir — commit the switch, then reclaim the old copies.
            baseDir = newDir;
            int loaded = reindex();
            int reclaimed = deleteMigratedOriginals(oldDir);
            Log.info("File relay store moved from {} to {} — {} entrie(s) migrated, {} indexed, "
                    + "{} old file(s) reclaimed", oldDir, newDir, migrated, loaded, reclaimed);
            return null;
        } catch (Exception e) {
            // baseDir untouched — roll back the partial copies so newDir isn't left half-populated.
            for (Path p : copied) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
            Log.error("Could not move file relay store from {} to {} (rolled back, still serving {}): {}",
                    oldDir, newDir, oldDir, e.getMessage(), e);
            return "Could not use directory '" + newDir + "': " + e.getMessage();
        }
    }

    /** Best-effort deletion of the migrated content/meta pairs from the old directory. */
    private int deleteMigratedOriginals(Path oldDir) {
        int reclaimed = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(oldDir, "*.meta")) {
            for (Path meta : ds) {
                String metaName = meta.getFileName().toString();
                String id = metaName.substring(0, metaName.length() - ".meta".length());
                try {
                    boolean removed = Files.deleteIfExists(oldDir.resolve(id));
                    Files.deleteIfExists(meta);
                    if (removed) reclaimed++;
                } catch (IOException e) {
                    Log.debug("Could not reclaim old relay file {}: {}", id, e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.debug("Could not enumerate old relay dir {} for reclaim: {}", oldDir, e.getMessage());
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

    boolean has(String id)      { return index.containsKey(id); }
    StoredFile get(String id)   { return index.get(id); }
    Path contentPath(String id) { return baseDir.resolve(id); }
    Path partPath(String id)    { return baseDir.resolve(id + ".part"); }

    /** Promotes an assembled {@code .part} file to a complete, indexed entry. */
    StoredFile finalizePart(String id, String name, String mime, long size, String sha256) throws IOException {
        Files.move(partPath(id), contentPath(id), StandardCopyOption.REPLACE_EXISTING);
        StoredFile sf = new StoredFile(id, name, mime, size, sha256, System.currentTimeMillis());
        writeMeta(sf);
        index.put(id, sf);
        return sf;
    }

    void deletePart(String id) {
        try {
            Files.deleteIfExists(partPath(id));
        } catch (IOException e) {
            Log.debug("Could not delete part file for {}: {}", id, e.getMessage());
        }
    }

    /** Removes entries older than {@code maxAgeMillis} and stray part files older than one day. */
    int purgeOlderThan(long maxAgeMillis) {
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
                    Log.warn("Could not purge relay file {}: {}", sf.id(), e.getMessage());
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
            Log.debug("Part-file sweep failed: {}", e.getMessage());
        }
        return removed;
    }

    private void writeMeta(StoredFile sf) throws IOException {
        Properties p = new Properties();
        p.setProperty("name",     sf.name());
        p.setProperty("mime",     sf.mime());
        p.setProperty("size",     Long.toString(sf.size()));
        p.setProperty("sha256",   sf.sha256() != null ? sf.sha256() : "");
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
                    Long.parseLong(p.getProperty("storedAt", "0")));
        } catch (Exception e) {
            Log.warn("Unreadable relay meta {} — skipping: {}", metaPath, e.getMessage());
            return null;
        }
    }
}
