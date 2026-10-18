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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * An append-only activity log of the file relay, in Openfire's log directory: one tab-separated
 * record per line under a {@code #} header, starting with an ISO-8601 time, with tabs and line
 * breaks escaped. Pruning rewrites it atomically, and all methods are synchronized.
 */
final class FileActivityLog {

    private static final Logger Log = LoggerFactory.getLogger(FileActivityLog.class);

    /** Minimum time between repeated I/O warnings, so a full disk cannot flood openfire.log. */
    private static final long FAILURE_LOG_INTERVAL_MS = 3600_000L;

    /** One parsed record: its timestamp and the caller's fields, in written order. */
    record Row(long when, List<String> fields) { }

    private final String fileName;
    private final String header;
    private long lastFailureLoggedAt;

    /** A log named {@code fileName} in Openfire's log directory, created with the {@code #} header given. */
    FileActivityLog(String fileName, String header) {
        this.fileName = fileName;
        this.header = header;
    }

    /** Absolute path of this log, resolved on each call. */
    synchronized Path path() {
        return JiveGlobals.getHomePath().resolve("logs").resolve(fileName);
    }

    /** Creates the log with its header if it does not exist, so it can be tailed from start-up; never throws. */
    synchronized void ensureExists() {
        Path file = path();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, header + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return;
            }
        } catch (IOException e) {
            Log.warn("Could not create file activity log {}", file, e);
            return;
        }
        migrateHeader(file);
    }

    /** Replaces an outdated header after a release adds a column; the records are left as written. */
    private void migrateHeader(Path file) {
        String existing;
        try (BufferedReader r = reader(file)) {
            existing = r.readLine();
        } catch (IOException e) {
            Log.warn("Could not read header of file activity log {}", file, e);
            return;
        }
        if (existing == null || !existing.startsWith("#") || existing.equals(header)) return;

        Path tmp = file.resolveSibling(fileName + ".tmp");
        try (BufferedReader r = reader(file);
             BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            w.write(header);
            w.newLine();
            r.readLine();
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.newLine();
            }
        } catch (IOException e) {
            Log.warn("Could not rewrite header of file activity log {} (left unchanged)", file, e);
            deleteQuietly(tmp);
            return;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Log.info("Updated column header of file activity log {}", file);
        } catch (IOException e) {
            Log.warn("Could not replace file activity log {} after header update (left unchanged)", file, e);
            deleteQuietly(tmp);
        }
    }

    /** Appends one record stamped with the current time; never throws, so logging cannot break a relay. */
    synchronized void append(String... values) {
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
                Log.warn("Could not append to file activity log {} (the admin UI's in-memory view is "
                       + "unaffected; further failures suppressed for an hour)", file, e);
            }
        }
    }

    /** The last {@code max} records, newest first, holding at most {@code max} rows in memory. */
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
            Log.warn("Could not read file activity log {}", file, e);
        }
        while (!tail.isEmpty()) newestFirst.add(tail.removeLast());
        return newestFirst;
    }

    /**
     * Drops records older than {@code retentionDays}, keeping lines without a readable time, and
     * returns how many were dropped. A failed rewrite leaves the log unchanged.
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
            Log.warn("Could not prune file activity log {} (left unchanged)", file, e);
            deleteQuietly(tmp);
            return 0;
        }
        if (dropped == 0) {
            deleteQuietly(tmp);
            return 0;
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Could not replace file activity log {} after pruning (left unchanged)", file, e);
            deleteQuietly(tmp);
            return 0;
        }
        Log.info("Pruned {} entrie(s) older than {} day(s) from {}", dropped, retentionDays, file);
        return dropped;
    }

    /** A UTF-8 reader that decodes malformed bytes as U+FFFD, so one corrupt line cannot hide the rest. */
    private static BufferedReader reader(Path file) throws IOException {
        return new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8));
    }

    /** Parses a record line into a {@link Row}; null for a header, a blank line or a bad timestamp. */
    private static Row parse(String line) {
        if (line == null || line.isBlank() || line.startsWith("#")) return null;
        String[] parts = line.split("\t", -1);
        long when;
        try {
            when = Instant.parse(parts[0]).toEpochMilli();
        } catch (DateTimeParseException e) {
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
                default   -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // A leftover temporary file is replaced on the next write.
        }
    }
}
