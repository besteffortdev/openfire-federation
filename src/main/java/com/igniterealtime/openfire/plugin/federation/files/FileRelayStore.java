package com.igniterealtime.openfire.plugin.federation.files;

import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-backed store for federated file content, addressed by the relay id (SHA-256 hex of the
 * original upload URL).  Lives in {@code <openfireHome>/federation-files/}: each entry is a
 * content file named {@code <id>} plus a {@code <id>.meta} properties sidecar (name, mime, size,
 * sha256, storedAt).  In-flight transfers assemble into {@code <id>.part} and are atomically
 * moved into place on completion, so the index only ever sees verified, complete entries.
 */
final class FileRelayStore {

    private static final Logger Log = LoggerFactory.getLogger(FileRelayStore.class);

    record StoredFile(String id, String name, String mime, long size, String sha256, long storedAt) {}

    private final Map<String, StoredFile> index = new ConcurrentHashMap<>();
    private Path baseDir;

    void init() throws IOException {
        baseDir = JiveGlobals.getHomePath().resolve("federation-files");
        Files.createDirectories(baseDir);
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
        Log.info("File relay store initialised at {} — {} file(s)", baseDir, loaded);
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
