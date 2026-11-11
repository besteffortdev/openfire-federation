package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.SafeXml;
import com.igniterealtime.openfire.plugin.federation.protocol.RoomJournal.Entry;
import com.igniterealtime.openfire.plugin.federation.protocol.RoomJournal.Kind;
import com.igniterealtime.openfire.plugin.federation.protocol.RoomJournal.Page;
import org.dom4j.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a mapped room's journal replays to a server that was cut off, and what it leaves out. */
class RoomJournalTest {

    private static final String ROOM  = "ops@conference.b.example";
    private static final String OWN   = ROOM;
    private static final String A_ROOM = "ops@conference.a.example";
    private static final String C_ROOM = "ops@conference.c.example";
    private static final long HOUR = 3_600_000L;
    private static final long T0 = 1_760_000_000_000L;

    @TempDir
    Path dir;

    private final AtomicLong now = new AtomicLong(T0);

    private RoomJournal journal() {
        return new RoomJournal(() -> dir, () -> 24 * HOUR, now::get);
    }

    private static Element message(String from, String to, String id) {
        return SafeXml.parseElement("<message xmlns='jabber:client' type='groupchat' from='" + from + "' to='" + to
                + "'><body>hi</body><stanza-id xmlns='urn:xmpp:sid:0' by='" + to + "' id='" + id + "'/></message>")
                .orElseThrow();
    }

    /** A local user's message, as the interceptor records it. */
    private void local(RoomJournal j, String id) {
        now.addAndGet(1000);
        j.record(ROOM, Kind.MESSAGE, OWN, id, id, "bea@b.example", "", "", 0, message("bea@b.example/x", OWN, id));
    }

    /** A message that arrived over the mapping with {@code src}, as injection records it. */
    private void relayed(RoomJournal j, String originRoom, String id, String author, String src, String hubs) {
        now.addAndGet(1000);
        j.record(ROOM, Kind.MESSAGE, originRoom, id, id, author, src, hubs, 0, message(author + "/x", originRoom, id));
    }

    private void moderation(RoomJournal j, String originRoom, String targetId, String src) {
        now.addAndGet(1000);
        Element announcement = SafeXml.parseElement("<message xmlns='jabber:client' type='groupchat' from='"
                + originRoom + "' to='" + originRoom + "'><retract xmlns='urn:xmpp:message-retract:1' id='"
                + targetId + "'><moderated xmlns='urn:xmpp:message-moderate:1'/></retract></message>").orElseThrow();
        j.record(ROOM, Kind.MODERATION, originRoom, targetId, targetId, "", src, "", 0, announcement);
    }

    private static List<String> ids(Page page) {
        return page.items().stream().map(Entry::originId).toList();
    }

    @Test
    @DisplayName("a page starts after the watermark message and holds what came after it, oldest first")
    void resumesAfterTheWatermark() {
        RoomJournal j = journal();
        local(j, "m1");
        local(j, "m2");
        local(j, "m3");

        Page page = j.page(ROOM, "a.example", 0, OWN, "m1", 0, 0, 50);

        assertEquals(List.of("m2", "m3"), ids(page));
        assertTrue(page.complete());
    }

    @Test
    @DisplayName("an unknown watermark falls back to the since time")
    void unknownWatermarkFallsBackToSince() {
        RoomJournal j = journal();
        local(j, "m1");
        long afterFirst = now.get() + 1;
        local(j, "m2");

        Page page = j.page(ROOM, "a.example", 0, OWN, "gone", afterFirst, 0, 50);

        assertEquals(List.of("m2"), ids(page));
    }

    @Test
    @DisplayName("what the requester sent, wrote, or already had by its hub trail is left out")
    void leavesOutWhatTheRequesterHas() {
        RoomJournal j = journal();
        relayed(j, A_ROOM, "from-a", "amy@a.example", "a.example", "a.example");
        relayed(j, C_ROOM, "via-a", "cat@c.example", "a.example", "c.example,a.example");
        relayed(j, C_ROOM, "a-had-it", "cat@c.example", "c.example", "c.example,a.example");
        relayed(j, A_ROOM, "amy-elsewhere", "amy@a.example", "c.example", "");
        relayed(j, C_ROOM, "new-to-a", "cat@c.example", "c.example", "c.example");
        local(j, "ours");

        Page page = j.page(ROOM, "a.example", 0, null, null, 0, 0, 50);

        assertEquals(List.of("new-to-a", "ours"), ids(page));
    }

    @Test
    @DisplayName("a message moderated within the range is sent as neither message nor moderation")
    void moderatedInRangeIsSentAsNeither() {
        RoomJournal j = journal();
        local(j, "m1");
        local(j, "m2");
        moderation(j, OWN, "m2", "");
        local(j, "m3");

        Page page = j.page(ROOM, "a.example", 0, OWN, "m1", 0, 0, 50);

        assertEquals(List.of("m3"), ids(page));
    }

    @Test
    @DisplayName("the moderation of a message the requester already had is sent")
    void moderationOfAnOlderMessageIsSent() {
        RoomJournal j = journal();
        local(j, "m1");
        local(j, "m2");
        moderation(j, OWN, "m1", "");

        Page page = j.page(ROOM, "a.example", 0, OWN, "m1", 0, 0, 50);

        assertEquals(List.of("m2", "m1"), ids(page));
        assertEquals(Kind.MODERATION, page.items().get(1).kind());
    }

    @Test
    @DisplayName("nothing from before the mapping became ACTIVE, or from before the window, is replayed")
    void floorIsActivationAndWindow() {
        RoomJournal j = journal();
        local(j, "before");
        long activated = now.get() + 1;
        local(j, "after");

        assertEquals(List.of("after"), ids(j.page(ROOM, "a.example", 0, null, null, 0, activated, 50)));

        now.addAndGet(24 * HOUR - 500);
        assertEquals(List.of("after"), ids(j.page(ROOM, "a.example", 0, null, null, 0, 0, 50)));
    }

    @Test
    @DisplayName("pages chain on the cursor, and a page of skipped entries still moves forward")
    void cursorAdvancesOverSkippedEntries() {
        RoomJournal j = journal();
        // Two items per page may look at 20 entries, so the first two pages see only skipped ones.
        for (int i = 0; i < 45; i++) relayed(j, A_ROOM, "skip-" + i, "amy@a.example", "a.example", "a.example");
        local(j, "wanted");

        Page first = j.page(ROOM, "a.example", 0, null, null, 0, 0, 2);
        assertTrue(first.items().isEmpty());
        assertFalse(first.complete());

        Page second = j.page(ROOM, "a.example", first.cursor(), null, null, 0, 0, 2);
        assertTrue(second.items().isEmpty());
        assertFalse(second.complete());

        Page third = j.page(ROOM, "a.example", second.cursor(), null, null, 0, 0, 2);
        assertEquals(List.of("wanted"), ids(third));
        assertTrue(third.complete());
    }

    @Test
    @DisplayName("a page sends at most max items and is incomplete when more remain")
    void pageIsBounded() {
        RoomJournal j = journal();
        for (int i = 0; i < 5; i++) local(j, "m" + i);

        Page first = j.page(ROOM, "a.example", 0, null, null, 0, 0, 2);
        assertEquals(List.of("m0", "m1"), ids(first));
        assertFalse(first.complete());
        assertEquals(List.of("m2", "m3"), ids(j.page(ROOM, "a.example", first.cursor(), null, null, 0, 0, 2)));
    }

    @Test
    @DisplayName("the watermark is the newest message that arrived over that mapping, not over another")
    void watermarkIsPerMapping() {
        RoomJournal j = journal();
        relayed(j, A_ROOM, "a1", "amy@a.example", "a.example", "a.example");
        relayed(j, C_ROOM, "c1", "cat@c.example", "c.example", "c.example");
        local(j, "ours");

        assertEquals("a1", j.newestArrival(ROOM, "a.example", 0).originId());
        assertEquals("c1", j.newestArrival(ROOM, "c.example", 0).originId());
        assertNull(j.newestArrival(ROOM, "a.example", now.get()), "older than notBefore");
    }

    @Test
    @DisplayName("recording the same message twice keeps one entry")
    void recordIsIdempotent() {
        RoomJournal j = journal();
        local(j, "m1");
        local(j, "m1");

        assertEquals(1, j.page(ROOM, "a.example", 0, null, null, 0, 0, 50).items().size());
        assertTrue(j.has(ROOM, Kind.MESSAGE, OWN, "m1"));
        assertFalse(j.has(ROOM, Kind.MODERATION, OWN, "m1"));
    }

    @Test
    @DisplayName("a restart keeps the entries, their cursors and the payloads")
    void survivesARestart() {
        RoomJournal j = journal();
        local(j, "m1");
        local(j, "m2");
        long cursor = j.page(ROOM, "a.example", 0, null, null, 0, 0, 1).cursor();

        RoomJournal reloaded = journal();
        reloaded.load();
        local(reloaded, "m3");

        Page page = reloaded.page(ROOM, "a.example", cursor, null, null, 0, 0, 50);
        assertEquals(List.of("m2", "m3"), ids(page));
        Element payload = page.items().get(0).payloadElement();
        assertNotNull(payload);
        assertEquals("hi", payload.elementText("body"));
    }

    @Test
    @DisplayName("pruning drops entries past the window from memory and from the file")
    void pruneDropsOldEntries() throws IOException {
        RoomJournal j = journal();
        local(j, "old");
        now.addAndGet(23 * HOUR);
        local(j, "new");
        now.addAndGet(2 * HOUR);

        assertEquals(1, j.prune());
        assertFalse(j.has(ROOM, Kind.MESSAGE, OWN, "old"));

        RoomJournal reloaded = journal();
        reloaded.load();
        assertFalse(reloaded.has(ROOM, Kind.MESSAGE, OWN, "old"));
        assertTrue(reloaded.has(ROOM, Kind.MESSAGE, OWN, "new"));
        String file = Files.readString(dir.resolve("journal.tsv"), StandardCharsets.UTF_8);
        assertFalse(file.contains("\told\t"));
    }

    @Test
    @DisplayName("a line cut short by a crash is skipped and the rest still loads")
    void damagedLineIsSkipped() throws IOException {
        RoomJournal j = journal();
        local(j, "m1");
        Files.writeString(dir.resolve("journal.tsv"), "7\tMESSAGE\tops@conf", StandardCharsets.UTF_8,
                          java.nio.file.StandardOpenOption.APPEND);

        RoomJournal reloaded = journal();
        reloaded.load();
        local(reloaded, "m2");

        assertTrue(reloaded.has(ROOM, Kind.MESSAGE, OWN, "m1"));
        assertNull(RoomJournal.parse("7\tMESSAGE\tops@conf"));
        RoomJournal again = journal();
        again.load();
        assertTrue(again.has(ROOM, Kind.MESSAGE, OWN, "m2"), "the next append must not join the damaged line");
    }

    @Test
    @DisplayName("the journal's start survives a restart; a lost file starts again from now")
    void startIsKept() throws IOException {
        RoomJournal j = journal();
        j.load();
        long began = j.startedAt();
        local(j, "m1");
        now.addAndGet(HOUR);

        RoomJournal reloaded = journal();
        reloaded.load();
        assertEquals(began, reloaded.startedAt());

        Files.delete(dir.resolve("journal.tsv"));
        RoomJournal lost = journal();
        lost.load();
        assertEquals(now.get(), lost.startedAt(), "a server that lost its journal cannot vouch for anything older");
    }

    @Test
    @DisplayName("a file whose header has no start began no later than its first entry, and is rewritten with one")
    void legacyHeaderFallsBackToTheFirstEntry() throws IOException {
        RoomJournal j = journal();
        local(j, "m1");
        long first = now.get();
        Path file = dir.resolve("journal.tsv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(0, "# federation room journal 1: seq kind room originRoom originId localId author src hubs");
        Files.write(file, lines, StandardCharsets.UTF_8);
        now.addAndGet(HOUR);

        RoomJournal reloaded = journal();
        reloaded.load();

        assertEquals(first, reloaded.startedAt());
        assertTrue(Files.readAllLines(file, StandardCharsets.UTF_8).get(0).contains("started=" + first));
    }

    @Test
    @DisplayName("a busy room keeps a bounded number of entries, oldest dropped first")
    void boundedPerRoom() {
        RoomJournal j = new RoomJournal(() -> null, () -> 24 * HOUR, now::get);
        int total = RoomJournal.MAX_PER_ROOM + 600;
        for (int i = 0; i < total; i++) {
            j.record(ROOM, Kind.MESSAGE, OWN, "m" + i, "m" + i, "bea@b.example", "", "", 0,
                     message("bea@b.example/x", OWN, "m" + i));
        }

        assertFalse(j.has(ROOM, Kind.MESSAGE, OWN, "m0"));
        assertTrue(j.has(ROOM, Kind.MESSAGE, OWN, "m" + (total - 1)));
    }

    private static Element bigMessage(String id, int bodyChars) {
        return SafeXml.parseElement("<message xmlns='jabber:client' type='groupchat' from='bea@b.example/x' to='"
                + OWN + "'><body>" + "x".repeat(bodyChars) + "</body><stanza-id xmlns='urn:xmpp:sid:0' by='" + OWN
                + "' id='" + id + "'/></message>").orElseThrow();
    }

    @Test
    @DisplayName("a payload past the size limit is not journaled, so it cannot fill the heap")
    void oversizedPayloadIsLeftOut() {
        RoomJournal j = journal();

        assertFalse(j.record(ROOM, Kind.MESSAGE, OWN, "big-0000000000000001", "big-0000000000000001", "bea@b.example",
                "", "", 0, bigMessage("big-0000000000000001", RoomJournal.MAX_PAYLOAD_CHARS)));
        assertFalse(j.has(ROOM, Kind.MESSAGE, OWN, "big-0000000000000001"));
    }

    @Test
    @DisplayName("a room past its size budget loses its oldest entries and keeps the newest")
    void roomSizeBudget() {
        RoomJournal j = journal();
        int each = RoomJournal.MAX_PAYLOAD_CHARS - 1024;
        int fits = (int) (RoomJournal.MAX_ROOM_CHARS / each);
        for (int i = 0; i < fits + 20; i++) {
            now.addAndGet(1000);
            String id = String.format("budget-%012d", i);
            j.record(ROOM, Kind.MESSAGE, OWN, id, id, "bea@b.example", "", "", 0, bigMessage(id, each));
        }

        assertFalse(j.has(ROOM, Kind.MESSAGE, OWN, String.format("budget-%012d", 0)), "the oldest went");
        assertTrue(j.has(ROOM, Kind.MESSAGE, OWN, String.format("budget-%012d", fits + 19)), "the newest stays");
    }

    @Test
    @DisplayName("what is recorded after a prune lands in the rewritten file")
    void appendsAfterPruneReachTheNewFile() throws IOException {
        RoomJournal j = journal();
        local(j, "old-0000000000000001");
        now.addAndGet(25 * HOUR);
        local(j, "new-0000000000000001");
        assertEquals(1, j.prune());
        local(j, "new-0000000000000002");
        j.close();

        RoomJournal reloaded = journal();
        reloaded.load();
        assertFalse(reloaded.has(ROOM, Kind.MESSAGE, OWN, "old-0000000000000001"));
        assertTrue(reloaded.has(ROOM, Kind.MESSAGE, OWN, "new-0000000000000001"));
        assertTrue(reloaded.has(ROOM, Kind.MESSAGE, OWN, "new-0000000000000002"));
    }
}
