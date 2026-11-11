package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.SafeXml;
import com.igniterealtime.openfire.plugin.federation.TabSeparated;
import org.dom4j.Element;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The room journal of docs/protocol/05 § Catch-up after a split: the groupchat payloads each mapped
 * room accepted, in the order it accepted them, over the catch-up window. It answers catch-up
 * requests, gives a requester its watermark, and lets the duplicate check outlive a restart.
 *
 * <p>Held in memory and in one append-only file under Openfire's home directory, readable by the
 * Openfire user only, since it holds message text. Pruning rewrites the file atomically. All methods
 * are synchronized: the I/O inside the lock is one short append per message, and the lock is what
 * stops an append interleaving with a prune's rewrite (CERT LCK09-J, as in the file activity log).</p>
 */
public final class RoomJournal {

    private static final Logger Log = LoggerFactory.getLogger(RoomJournal.class);

    private static final String DIRECTORY = "federation-journal";
    private static final String FILE_NAME = "journal.tsv";
    private static final String HEADER = "# federation room journal 1 started=%d fields: "
        + "seq kind room originRoom originId localId author src hubs accepted sent payload";
    private static final Pattern STARTED = Pattern.compile("started=(\\d+)");
    private static final int FIELDS = 12;

    /** Entries kept per room however busy it is, so one room cannot grow the journal without bound. */
    static final int MAX_PER_ROOM = 10_000;
    /** Entries dropped at once when a room passes its bound, so a busy room does not shift its list per message. */
    private static final int DROP_BATCH = 500;
    /** Longest payload journaled, in characters; a longer one is delivered live but never replayed. */
    static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    /** Payload characters kept per room, and across all rooms; the oldest entries go first. */
    static final long MAX_ROOM_CHARS = 16L * 1024 * 1024;
    static final long MAX_TOTAL_CHARS = 128L * 1024 * 1024;

    /** How many entries one page may look at, per item it may send, so a page of skipped entries still ends. */
    static final int EXAMINED_PER_ITEM = 10;
    /** The longest window, whatever the property says: a week of traffic. */
    private static final int MAX_WINDOW_HOURS = 168;

    /** Minimum time between repeated I/O warnings, so a full disk cannot flood openfire.log. */
    private static final long FAILURE_LOG_INTERVAL_MS = 3_600_000L;

    /** What an entry records. */
    public enum Kind {
        /** A groupchat message: a body, a reaction or a retraction. */
        MESSAGE,
        /** A XEP-0425 moderation announced by the room it names; its origin id is the moderated message's. */
        MODERATION
    }

    /**
     * One accepted payload. {@code originRoom} and {@code originId} name the message on every server
     * (for a moderation, the moderated message); {@code localId} is the stanza-id this room gave it.
     * {@code seq} orders the journal and survives a restart, so it can serve as a paging cursor.
     */
    public record Entry(long seq, String room, Kind kind, String originRoom, String originId, String localId,
                        String author, String src, String hubs, long accepted, long sent, String payload) {

        /** The payload as an element, or null if it no longer parses. */
        public Element payloadElement() {
            return SafeXml.parseElement(payload).orElse(null);
        }
    }

    /** One page of a catch-up answer: what to send, the cursor after the last entry examined, and whether the journal ended. */
    public record Page(List<Entry> items, long cursor, boolean complete) { }

    /** One room's entries, oldest first, and their index by key. */
    private static final class RoomLog {
        final List<Entry> entries = new ArrayList<>();
        final Map<String, Entry> byKey = new HashMap<>();
        /** Payload characters held in {@link #entries}. */
        long chars;
    }

    private final Supplier<Path> directory;
    private final LongSupplier windowMs;
    private final LongSupplier clock;
    private final Map<String, RoomLog> rooms = new HashMap<>();
    private Path resolvedDirectory;
    private long nextSeq = 1;
    private long startedAt;
    private long lastFailureLoggedAt;
    /** Payload characters held across all rooms. */
    private long totalChars;
    /** The open journal file, so an append is one write and not an open, a write and a close. */
    private Writer writer;
    /** Serializes prunes; the monitor is not held while a prune writes the new file. */
    private final Object pruneLock = new Object();

    RoomJournal(Supplier<Path> directory, LongSupplier windowMs, LongSupplier clock) {
        this.directory = directory;
        this.windowMs = windowMs;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    /** The journal in Openfire's data directory, with the window from the plugin's properties. */
    public static RoomJournal inDataDirectory() {
        return new RoomJournal(RoomJournal::dataDirectory, RoomJournal::configuredWindowMs, System::currentTimeMillis);
    }

    /**
     * Beside Openfire's plugins directory, followed through symlinks: Openfire must be able to write
     * there, and it lasts as long as the installed plugins do. That is {@code /var/lib/openfire} in
     * the Docker image and the Debian package, whose home directory is not kept, and the home
     * directory itself in a tarball install.
     */
    static Path dataDirectory() {
        Path home = JiveGlobals.getHomePath();
        try {
            Path parent = home.resolve("plugins").toRealPath().getParent();
            return (parent != null ? parent : home).resolve(DIRECTORY);
        } catch (IOException | SecurityException e) {
            Log.debug("Could not resolve Openfire's plugins directory; keeping the room journal in {}", home, e);
            return home.resolve(DIRECTORY);
        }
    }

    /**
     * When this journal began: the oldest moment it can vouch for. A server asks for no replay from
     * before then, and accepts none, since it could not tell whether it already has the message.
     */
    public synchronized long startedAt() {
        return startedAt;
    }

    /** The catch-up window from {@code plugin.federation.catchup.windowHours}, at most a week, in milliseconds. */
    public static long configuredWindowMs() {
        return Math.min(FederationProperties.CATCHUP_WINDOW_HOURS.getValue(), MAX_WINDOW_HOURS) * 3_600_000L;
    }

    /** The current window, in milliseconds. */
    long windowMs() {
        return windowMs.getAsLong();
    }

    /**
     * Records that {@code room} accepted {@code payload}. A payload already recorded under the same
     * kind, origin room and origin id is ignored, so recording twice is harmless. True when it was new.
     *
     * @param sent when the message was first sent, or 0 for now
     */
    public synchronized boolean record(String room, Kind kind, String originRoom, String originId, String localId,
                                       String author, String src, String hubs, long sent, Element payload) {
        if (room == null || kind == null || originRoom == null || originId == null || payload == null) return false;
        RoomLog log = rooms.computeIfAbsent(room, k -> new RoomLog());
        String key = key(kind, originRoom, originId);
        if (log.byKey.containsKey(key)) return false;
        String xml = payload.asXML();
        if (xml.length() > MAX_PAYLOAD_CHARS) return false;
        long now = clock.getAsLong();
        Entry e = new Entry(nextSeq++, room, kind, originRoom, originId, orEmpty(localId), orEmpty(author),
                            orEmpty(src), orEmpty(hubs), now, sent > 0 ? sent : now, xml);
        add(log, e);
        if (log.entries.size() > MAX_PER_ROOM + DROP_BATCH) dropOldest(log, log.entries.size() - MAX_PER_ROOM);
        enforceSizeBudgets(log);
        append(e);
        return true;
    }

    /** Whether {@code room} has accepted the entry of this kind for that origin message. */
    public synchronized boolean has(String room, Kind kind, String originRoom, String originId) {
        return find(room, kind, originRoom, originId) != null;
    }

    /** The entry of this kind {@code room} holds for that origin message, or null. */
    public synchronized Entry find(String room, Kind kind, String originRoom, String originId) {
        RoomLog log = rooms.get(room);
        return log == null ? null : log.byKey.get(key(kind, originRoom, originId));
    }

    /**
     * The watermark of a mapping: the newest message {@code room} accepted over the mapping with
     * {@code remoteDomain}, no older than {@code notBefore}; null if there is none.
     */
    public synchronized Entry newestArrival(String room, String remoteDomain, long notBefore) {
        RoomLog log = rooms.get(room);
        if (log == null) return null;
        for (int i = log.entries.size() - 1; i >= 0; i--) {
            Entry e = log.entries.get(i);
            if (e.accepted() < notBefore) return null;
            if (e.kind() == Kind.MESSAGE && remoteDomain.equals(e.src())) return e;
        }
        return null;
    }

    /**
     * One page of the answer to {@code requester}: from just after {@code cursor} when one is given,
     * else just after the watermark message if the journal holds it, else from {@code since}; never before
     * {@code notBefore} or the window. It skips what the requester already has and anything moderated
     * within the range, and looks at no more than {@value #EXAMINED_PER_ITEM} entries per item.
     *
     * @param cursor a cursor from an earlier page, or 0
     */
    public synchronized Page page(String room, String requester, long cursor, String afterRoom, String after,
                                  long since, long notBefore, int max) {
        RoomLog log = rooms.get(room);
        if (log == null || log.entries.isEmpty() || max < 1) return new Page(List.of(), cursor, true);
        int start = startIndex(log, cursor, afterRoom, after, since);
        long floor = Math.max(notBefore, clock.getAsLong() - windowMs.getAsLong());
        while (start < log.entries.size() && log.entries.get(start).accepted() < floor) start++;

        List<Entry> items = new ArrayList<>();
        int limit = (int) Math.min(log.entries.size(), (long) start + (long) max * EXAMINED_PER_ITEM);
        long last = start > 0 ? log.entries.get(start - 1).seq() : cursor;
        int i = start;
        for (; i < limit && items.size() < max; i++) {
            Entry e = log.entries.get(i);
            last = e.seq();
            if (!alreadyHeld(log, e, requester, start)) items.add(e);
        }
        return new Page(items, last, i >= log.entries.size());
    }

    private static int startIndex(RoomLog log, long cursor, String afterRoom, String after, long since) {
        // A cursor whose entry was pruned since still orders correctly: everything left is after it.
        if (cursor > 0) return indexAfterSeq(log, cursor);
        if (afterRoom != null && after != null) {
            Entry mark = log.byKey.get(key(Kind.MESSAGE, afterRoom, after));
            if (mark != null) return indexAfterSeq(log, mark.seq());
        }
        int i = 0;
        while (i < log.entries.size() && log.entries.get(i).accepted() < since) i++;
        return i;
    }

    /** The index of the first entry whose seq is above {@code seq}; entries are in seq order. */
    private static int indexAfterSeq(RoomLog log, long seq) {
        int lo = 0;
        int hi = log.entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (log.entries.get(mid).seq() <= seq) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Whether a page must leave {@code e} out: the requester sent it, wrote it, or already had it by
     * its hub trail; or it is a message moderated since, or the moderation of a message in the range,
     * which the requester should see neither of.
     */
    private static boolean alreadyHeld(RoomLog log, Entry e, String requester, int start) {
        if (requester.equals(e.src()) || requester.equals(domainOf(e.author()))
                || FederationStanzaFactory.viaContains(e.hubs(), requester)) {
            return true;
        }
        if (e.kind() == Kind.MESSAGE) {
            return log.byKey.containsKey(key(Kind.MODERATION, e.originRoom(), e.originId()));
        }
        Entry target = log.byKey.get(key(Kind.MESSAGE, e.originRoom(), e.originId()));
        return target != null && indexAfterSeq(log, target.seq()) > start;
    }

    /**
     * Drops entries older than the window and rewrites the file if any went; returns how many. The
     * new file is written without the monitor, so room traffic is not held up meanwhile; what was
     * recorded during the write is added before the file is swapped in.
     */
    public int prune() {
        synchronized (pruneLock) {
            List<Entry> snapshot;
            long snapshotSeq;
            int dropped = 0;
            Path file;
            String header;
            synchronized (this) {
                long floor = clock.getAsLong() - windowMs.getAsLong();
                for (Iterator<RoomLog> it = rooms.values().iterator(); it.hasNext(); ) {
                    RoomLog log = it.next();
                    int n = 0;
                    while (n < log.entries.size() && log.entries.get(n).accepted() < floor) n++;
                    dropOldest(log, n);
                    dropped += n;
                    if (log.entries.isEmpty()) it.remove();
                }
                if (dropped == 0) return 0;
                file = file();
                if (file == null) return dropped;
                snapshot = allEntries();
                snapshotSeq = nextSeq - 1;
                header = header();
            }
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            try {
                writeTo(tmp, header, snapshot);
                synchronized (this) {
                    List<Entry> since = new ArrayList<>();
                    for (Entry e : allEntries()) if (e.seq() > snapshotSeq) since.add(e);
                    StringBuilder late = new StringBuilder();
                    for (Entry e : since) late.append(line(e));
                    if (late.length() > 0) {
                        Files.writeString(tmp, late, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    }
                    closeWriter();
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException ex) {
                synchronized (this) {
                    failed("rewrite", file, ex);
                }
                deleteQuietly(tmp);
            }
            return dropped;
        }
    }

    /** Closes the journal file; appends reopen it. Called when the plugin stops. */
    public synchronized void close() {
        closeWriter();
    }

    /**
     * Reads the journal file, keeping the entries still inside the window; never throws. Without a
     * file, the journal starts now.
     */
    public synchronized void load() {
        closeWriter();
        rooms.clear();
        totalChars = 0;
        resolvedDirectory = null;
        startedAt = clock.getAsLong();
        Path file = file();
        if (file == null || !Files.isRegularFile(file)) return;
        long floor = clock.getAsLong() - windowMs.getAsLong();
        long firstAccepted = 0;
        boolean headerStarted = false;
        int kept = 0;
        int dropped = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) {
                    Matcher started = STARTED.matcher(line);
                    if (!headerStarted && started.find()) {
                        startedAt = Long.parseLong(started.group(1));
                        headerStarted = true;
                    }
                    continue;
                }
                Entry e = parse(line);
                if (e != null && firstAccepted == 0) firstAccepted = e.accepted();
                if (e == null) {
                    // A line cut short by a crash: rewriting drops it, or the next append would join it.
                    if (!line.isBlank() && !line.startsWith("#")) dropped++;
                    continue;
                }
                nextSeq = Math.max(nextSeq, e.seq() + 1);
                if (e.accepted() < floor) {
                    dropped++;
                    continue;
                }
                RoomLog log = rooms.computeIfAbsent(e.room(), k -> new RoomLog());
                if (e.payload().length() > MAX_PAYLOAD_CHARS) {
                    dropped++;
                } else if (!log.byKey.containsKey(key(e.kind(), e.originRoom(), e.originId()))) {
                    add(log, e);
                    kept++;
                }
            }
        } catch (IOException e) {
            Log.warn("Could not read the room journal {}; catch-up starts from what arrives from now on", file, e);
            rooms.clear();
            totalChars = 0;
            return;
        }
        // A file from before the header recorded it began no later than its first entry.
        if (!headerStarted && firstAccepted > 0) startedAt = Math.min(startedAt, firstAccepted);
        for (RoomLog log : rooms.values()) {
            if (log.entries.size() > MAX_PER_ROOM) dropOldest(log, log.entries.size() - MAX_PER_ROOM);
        }
        for (RoomLog log : new ArrayList<>(rooms.values())) enforceSizeBudgets(log);
        if (dropped > 0 || !headerStarted) rewrite();
        Log.info("Room journal {} loaded: {} entr(ies) in {} room(s), {} past the window dropped", file,
                 kept, rooms.size(), dropped);
    }

    private void add(RoomLog log, Entry e) {
        log.entries.add(e);
        log.byKey.put(key(e.kind(), e.originRoom(), e.originId()), e);
        log.chars += e.payload().length();
        totalChars += e.payload().length();
    }

    private void dropOldest(RoomLog log, int n) {
        if (n <= 0) return;
        List<Entry> oldest = log.entries.subList(0, n);
        for (Entry e : oldest) {
            log.byKey.remove(key(e.kind(), e.originRoom(), e.originId()));
            log.chars -= e.payload().length();
            totalChars -= e.payload().length();
        }
        oldest.clear();
    }

    /**
     * Keeps the payload text in memory bounded: {@code log} within its own budget, then the whole
     * journal within the total by trimming whichever room holds most. The newest entry always stays.
     */
    private void enforceSizeBudgets(RoomLog log) {
        trimTo(log, MAX_ROOM_CHARS);
        while (totalChars > MAX_TOTAL_CHARS) {
            RoomLog largest = null;
            for (RoomLog l : rooms.values()) {
                if (l.entries.size() > 1 && (largest == null || l.chars > largest.chars)) largest = l;
            }
            if (largest == null) return;
            trimTo(largest, Math.max(0, largest.chars - (totalChars - MAX_TOTAL_CHARS)));
        }
    }

    /** Drops the oldest entries of {@code log} until it holds at most {@code budget} characters or one entry. */
    private void trimTo(RoomLog log, long budget) {
        if (log.chars <= budget) return;
        int n = 0;
        long freed = 0;
        while (n < log.entries.size() - 1 && log.chars - freed > budget) {
            freed += log.entries.get(n).payload().length();
            n++;
        }
        dropOldest(log, n);
    }

    private List<Entry> allEntries() {
        List<Entry> all = new ArrayList<>();
        rooms.values().forEach(log -> all.addAll(log.entries));
        all.sort(Comparator.comparingLong(Entry::seq));
        return all;
    }

    /** The journal file; its directory is resolved once per load, since resolving it touches the file system. */
    private Path file() {
        if (resolvedDirectory == null) resolvedDirectory = directory.get();
        return resolvedDirectory == null ? null : resolvedDirectory.resolve(FILE_NAME);
    }

    private String header() {
        return String.format(Locale.ROOT, HEADER, startedAt);
    }

    /** Appends one entry, creating the file with its header; a failure is logged and the memory kept. */
    private void append(Entry e) {
        Path file = file();
        if (file == null) return;
        try {
            // One stat per message: a file removed underneath us is started again rather than written unseen.
            boolean exists = Files.exists(file);
            if (!exists) closeWriter();
            if (writer == null) {
                if (!exists) {
                    createPrivately(file);
                    Files.writeString(file, header() + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                }
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
            writer.write(line(e));
            writer.flush();
        } catch (IOException ex) {
            closeWriter();
            failed("append to", file, ex);
        }
    }

    private void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException e) {
            Log.debug("Could not close the room journal", e);
        }
        writer = null;
    }

    /** Writes every entry in memory to a new file and moves it over the old one; a failure leaves the old one. */
    private void rewrite() {
        Path file = file();
        if (file == null) return;
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            writeTo(tmp, header(), allEntries());
            closeWriter();
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            failed("rewrite", file, ex);
            deleteQuietly(tmp);
        }
    }

    /** Writes a private new file holding {@code header} and {@code entries}, replacing any leftover. */
    private static void writeTo(Path tmp, String header, List<Entry> entries) throws IOException {
        Files.deleteIfExists(tmp);
        createPrivately(tmp);
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
            w.write(header);
            w.newLine();
            for (Entry e : entries) w.write(line(e));
        }
    }

    private static void deleteQuietly(Path tmp) {
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
            // A leftover temporary file is replaced by the next rewrite.
        }
    }

    /** Creates {@code file} and its directory readable by the Openfire user only, where the file system allows. */
    private static void createPrivately(Path file) throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        restrict(dir, "rwx------");
        Files.createFile(file);
        restrict(file, "rw-------");
    }

    private static void restrict(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException e) {
            Log.debug("Could not restrict permissions on {}", p, e);
        }
    }

    private void failed(String what, Path file, IOException e) {
        long now = clock.getAsLong();
        if (now - lastFailureLoggedAt < FAILURE_LOG_INTERVAL_MS) return;
        lastFailureLoggedAt = now;
        Log.warn("Could not {} the room journal {} (catch-up still works from memory until a restart; "
               + "further failures suppressed for an hour)", what, file, e);
    }

    private static String line(Entry e) {
        String[] fields = {
            Long.toString(e.seq()), e.kind().name(), e.room(), e.originRoom(), e.originId(), e.localId(),
            e.author(), e.src(), e.hubs(), Long.toString(e.accepted()), Long.toString(e.sent()), e.payload()
        };
        StringBuilder sb = new StringBuilder(e.payload().length() + 160);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('\t');
            sb.append(TabSeparated.escape(fields[i]));
        }
        return sb.append('\n').toString();
    }

    /** One entry from a line of the file; null for the header, a blank or a damaged line. */
    static Entry parse(String line) {
        if (line == null || line.isBlank() || line.startsWith("#")) return null;
        String[] f = line.split("\t", -1);
        if (f.length != FIELDS) return null;
        try {
            Kind kind = Kind.valueOf(f[1]);
            String payload = TabSeparated.unescape(f[11]);
            if (payload.isEmpty()) return null;
            return new Entry(Long.parseLong(f[0]), TabSeparated.unescape(f[2]), kind, TabSeparated.unescape(f[3]),
                             TabSeparated.unescape(f[4]), TabSeparated.unescape(f[5]), TabSeparated.unescape(f[6]),
                             TabSeparated.unescape(f[7]), TabSeparated.unescape(f[8]),
                             Long.parseLong(f[9]), Long.parseLong(f[10]), payload);
        } catch (IllegalArgumentException e) {
            // Covers NumberFormatException: a line cut short by a crash mid-append.
            return null;
        }
    }

    private static String key(Kind kind, String originRoom, String originId) {
        return (kind == Kind.MESSAGE ? "m\n" : "x\n") + originRoom + '\n' + originId;
    }

    /** The domain of a bare JID, or the empty string. */
    private static String domainOf(String bareJid) {
        if (bareJid == null || bareJid.isEmpty()) return "";
        int at = bareJid.indexOf('@');
        return at < 0 ? bareJid : bareJid.substring(at + 1);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
