package com.igniterealtime.openfire.plugin.federation.files;

import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Append-only, disk-backed activity log for the file relay (AV scans, rejected files), living in
 * Openfire's own log directory next to {@code openfire.log} so an operator can read, grep, ship, or
 * archive it straight from the file system. It is also what makes those tables survive a restart:
 * {@link #readRecent} reloads the newest entries back into the admin UI's in-memory view at start.
 *
 * <p>Format is one tab-separated record per line, prefixed by a {@code #} header line describing the
 * columns. The first column is always an ISO-8601 UTC instant; the rest are the caller's fields,
 * with backslash, tab, CR and LF escaped ({@code \\}, {@code \t}, {@code \r}, {@code \n}) so a file
 * name or an AV detail string can never break the line/column structure. That keeps it usable with
 * ordinary tools ({@code grep}, {@code awk}, {@code cut}) while still round-tripping exactly.
 *
 * <p>Entries older than the configured retention are dropped by {@link #prune}, which rewrites the
 * file through a sibling temp file and an atomic move — a reader never sees a half-written log, and
 * a failure part-way leaves the existing log untouched.
 *
 * <p>Writes open, append and close per record: volume is low (a rejection or a scan, not a chat
 * message), and holding no descriptor means an operator may rotate, truncate or delete the file at
 * any time without the plugin noticing or blocking on it. All public methods are synchronized, so
 * an append can never interleave with a prune's rewrite.
 */
final class FileActivityLog {

    private static final Logger Log = LoggerFactory.getLogger(FileActivityLog.class);

    /** Suppress repeat I/O warnings (a full or read-only disk would otherwise flood openfire.log). */
    private static final long FAILURE_LOG_INTERVAL_MS = 3600_000L;

    /** One parsed record: its timestamp and the caller's fields, in written order. */
    record Row(long when, List<String> fields) { }

    private final String fileName;
    private final String header;
    private long lastFailureLoggedAt;

    /**
     * @param fileName log file name, resolved inside Openfire's log directory
     * @param header   {@code #}-prefixed column description, written when the file is created
     */
    FileActivityLog(String fileName, String header) {
        this.fileName = fileName;
        this.header = header;
    }

    /**
     * Absolute path of this log. Resolved per call rather than cached so it still points at the
     * right place if Openfire's home is re-resolved during a reload.
     */
    synchronized Path path() {
        return JiveGlobals.getHomePath().resolve("logs").resolve(fileName);
    }

    /**
     * Creates the log (directory + header line) if it isn't there yet, so an operator can find and
     * {@code tail -F} it from the moment the plugin starts rather than only after the first entry.
     * Never throws.
     */
    synchronized void ensureExists() {
        Path file = path();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, header + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            Log.warn("Could not create file activity log {}: {}", file, e.getMessage());
        }
    }

    /** Appends one record, stamped with the current time. Never throws — logging must not break a relay. */
    synchronized void append(String... values) {
        // Millisecond precision: matches what the admin UI shows and keeps the column narrow —
        // Instant.now()'s native nanoseconds would add nine digits nobody reads.
        StringBuilder sb = new StringBuilder(160).append(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        for (String v : values) sb.append('\t').append(escape(v));
        sb.append('\n');
        Path file = path();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, header + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            long now = System.currentTimeMillis();
            if (now - lastFailureLoggedAt > FAILURE_LOG_INTERVAL_MS) {
                lastFailureLoggedAt = now;
                Log.warn("Could not append to file activity log {}: {} (the admin UI's in-memory "
                       + "view is unaffected; further failures suppressed for an hour)", file, e.getMessage());
            }
        }
    }

    /**
     * The last {@code max} records, newest first — the order the admin UI's tables want. Reads the
     * whole file but only ever holds {@code max} rows, so a long-retained log costs one pass, not
     * heap. Unparseable lines are skipped.
     */
    synchronized List<Row> readRecent(int max) {
        List<Row> newestFirst = new ArrayList<>();
        Path file = path();
        if (max <= 0 || !Files.isRegularFile(file)) return newestFirst;
        Deque<Row> tail = new ArrayDeque<>();
        try (BufferedReader r = reader(file)) {
            String line;
            while ((line = r.readLine()) != null) {
                Row row = parse(line);
                if (row == null) continue;
                tail.addLast(row);
                if (tail.size() > max) tail.removeFirst();
            }
        } catch (IOException e) {
            Log.warn("Could not read file activity log {}: {}", file, e.getMessage());
        }
        while (!tail.isEmpty()) newestFirst.add(tail.removeLast());
        return newestFirst;
    }

    /**
     * Drops records older than {@code retentionDays}. Returns how many were dropped (0 when there
     * was nothing to do, or when the rewrite failed and the log was left exactly as it was).
     * A line whose timestamp can't be parsed has no age to judge, so it is kept rather than
     * silently destroyed.
     */
    synchronized int prune(int retentionDays) {
        Path file = path();
        if (retentionDays < 1 || !Files.isRegularFile(file)) return 0;
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        Path tmp = file.resolveSibling(fileName + ".tmp");
        int dropped = 0;
        try (BufferedReader r = reader(file);
             BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            w.write(header);
            w.newLine();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) continue;
                Row row = parse(line);
                if (row != null && row.when() < cutoff) {
                    dropped++;
                    continue;
                }
                w.write(line);
                w.newLine();
            }
        } catch (IOException e) {
            Log.warn("Could not prune file activity log {} (left unchanged): {}", file, e.getMessage());
            deleteQuietly(tmp);
            return 0;
        }
        if (dropped == 0) {          // nothing expired — keep the original file and its inode
            deleteQuietly(tmp);
            return 0;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Could not replace file activity log {} after pruning (left unchanged): {}",
                     file, e.getMessage());
            deleteQuietly(tmp);
            return 0;
        }
        Log.info("Pruned {} entrie(s) older than {} day(s) from {}", dropped, retentionDays, file);
        return dropped;
    }

    /** Malformed bytes decode to U+FFFD rather than aborting the read — a corrupt line must not hide the rest. */
    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    /** Parses one written line back into a {@link Row}; null when it isn't one (header, blank, bad timestamp). */
    private static Row parse(String line) {
        if (line == null || line.isBlank() || line.startsWith("#")) return null;
        String[] parts = line.split("\t", -1);
        long when;
        try {
            when = Instant.parse(parts[0]).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
        List<String> fields = new ArrayList<>(parts.length - 1);
        for (String p : Arrays.asList(parts).subList(1, parts.length)) fields.add(unescape(p));
        return new Row(when, fields);
    }

    private static String escape(String v) {
        if (v == null || v.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String v) {
        if (v == null || v.indexOf('\\') < 0) return v == null ? "" : v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i == v.length() - 1) {
                sb.append(c);
                continue;
            }
            char next = v.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 't'  -> sb.append('\t');
                case 'n'  -> sb.append('\n');
                case 'r'  -> sb.append('\r');
                default   -> sb.append('\\').append(next);   // not one of ours — leave it as written
            }
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
    }
}
