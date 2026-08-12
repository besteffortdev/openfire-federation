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

    /**
     * @param token per-share capability minted by the origin and carried in the {@code fed-file}
     *              annotation, so only a server that received the announcing message can request the
     *              content. Empty for entries staged by a build older than 1.10.6 — see
     *              {@code FileRelayManager.shareTokenOk}.
     */
    record StoredFile(String id, String name, String mime, long size, String sha256, String token,
                      long storedAt) {}

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
        restrictToOwner(dir);
        baseDir = dir;
        int loaded = reindex();
        Log.info("File relay store initialised at {} — {} file(s)", baseDir, loaded);
    }

    /**
     * Narrows the spool directory to owner-only (CERT FIO01-J, "create files with appropriate
     * access permissions").
     *
     * <p>What sits here is the actual content of other people's files in transit, so the process
     * umask is the wrong thing to leave it to — a default of {@code 022} makes every relayed file
     * world-readable to any account on the host. Applied on every {@link #init()} rather than only
     * at creation, so a directory that already exists with looser bits gets tightened too.
     *
     * <p>Deliberately not extended to the activity logs: those live in Openfire's own {@code logs/}
     * directory beside {@code openfire.log}, hold metadata rather than content, and are meant to be
     * tailed and shipped by whatever the operator already runs there. Locking them down while the
     * far more detailed {@code openfire.log} stays readable would cost real operational convenience
     * and buy close to nothing.
     *
     * <p>Never fatal: a non-POSIX filesystem simply doesn't support this, which is not a reason to
     * refuse to start.
     */
    private static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            Log.debug("Could not restrict permissions on file relay store {}: {}", dir, e.getMessage());
        }
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
        if (oldDir == null) {
            // The store never opened — its directory was unusable at start. A newly-configured
            // directory is then a chance to initialise, not a migration. Returning "success"
            // without doing so (the pre-1.10.6 behaviour) reported the setting as applied while
            // the store stayed dead and every relayed file 404ed for the life of the process.
            try {
                init();
                return null;
            } catch (IOException e) {
                Log.error("Could not open file relay store at {}: {}", newDir, e.getMessage(), e);
                return "Could not use directory '" + newDir + "': " + e.getMessage();
            }
        }
        if (newDir.equals(oldDir)) return null;
        List<Path> copied = new ArrayList<>();    // new-dir paths written, for rollback on failure
        List<Path> originals = new ArrayList<>(); // old-dir paths actually copied, for reclaim on success
        try {
            Files.createDirectories(newDir);
            restrictToOwner(newDir);          // a relocated spool must be no more open than the old one
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
            // Every entry is now safely in newDir — commit the switch, then reclaim the old copies.
            baseDir = newDir;
            int loaded = reindex();
            int reclaimed = deleteMigratedOriginals(originals);
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

    /**
     * Best-effort deletion of exactly the old-directory files that were copied above — never a fresh
     * re-scan of the old directory. Anything that appeared there after the copy loop (a transfer that
     * won the race to {@link #finalizePart} while {@code baseDir} still pointed at the old directory)
     * was never copied, so deleting it would destroy the only copy; leaving it behind merely orphans a
     * file the sender can re-request. Returns how many content files were actually removed.
     */
    private int deleteMigratedOriginals(List<Path> originals) {
        int reclaimed = 0;
        for (Path p : originals) {
            try {
                if (Files.deleteIfExists(p) && !p.getFileName().toString().endsWith(".meta")) reclaimed++;
            } catch (IOException e) {
                Log.debug("Could not reclaim old relay file {}: {}", p, e.getMessage());
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

    /** True once a directory has been opened successfully — the relay is unusable until then. */
    boolean isOpen()            { return baseDir != null; }
    boolean has(String id)      { return index.containsKey(id); }
    StoredFile get(String id)   { return index.get(id); }
    Path contentPath(String id) { return baseDir.resolve(id); }
    Path partPath(String id)    { return baseDir.resolve(id + ".part"); }

    /**
     * Promotes an assembled {@code .part} file to a complete, indexed entry.
     *
     * <p>Synchronized on the same monitor as {@link #reopenIfMoved}: both the part and the content
     * path must resolve against ONE {@code baseDir}. Without that, a relocation landing between the
     * two {@code resolve} calls moved a part across directories, and a transfer finishing just after
     * the migration's copy loop was dropped from the rebuilt index with its only bytes orphaned in
     * the old directory — a completed share that could never be downloaded again.
     *
     * <p>A transfer that was assembling when a relocation committed now fails here deterministically
     * (its {@code .part} is in the old directory and was deliberately not migrated) and re-requests
     * into the new location, which is the documented behaviour for in-flight transfers — rather than
     * sometimes succeeding into an unreachable orphan.
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

    void deletePart(String id) {
        try {
            Files.deleteIfExists(partPath(id));
        } catch (IOException e) {
            Log.debug("Could not delete part file for {}: {}", id, e.getMessage());
        }
    }

    /**
     * Removes entries older than {@code maxAgeMillis} and stray part files older than one day.
     * Synchronized with {@link #reopenIfMoved} so a purge can never enumerate one directory and
     * delete out of another.
     */
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
        // The share capability. It is why the spool is owner-only (see restrictToOwner): a local
        // account able to read these sidecars could request any relayed file from this server.
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
                    p.getProperty("token", ""),     // absent in sidecars written before 1.10.6
                    Long.parseLong(p.getProperty("storedAt", "0")));
        } catch (IOException | NumberFormatException e) {
            Log.warn("Unreadable relay meta {} — skipping: {}", metaPath, e.getMessage());
            return null;
        }
    }
}
