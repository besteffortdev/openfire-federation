package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.dom4j.Element;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_DESTINATION;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_LOCAL;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_ORIGIN;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_REMOTE;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_REQUEST;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_TOKEN;

/**
 * Catch-up after a split (docs/protocol/05): when a mapping's path comes back, asks the far end for
 * the room traffic missed while it was down, and answers the same request from the
 * {@link RoomJournal}. Each mapping runs one catch-up at a time, page by page; answers are paced so a
 * long replay does not starve live traffic on the link. Timers and sends run on this class's own
 * scheduler; the requester's state is guarded by this object's lock.
 */
public final class RoomCatchup {

    private static final Logger Log = LoggerFactory.getLogger(RoomCatchup.class);

    static final String REQUEST = "room-catchup-request";
    static final String DONE    = "room-catchup-done";

    private static final String ATTR_CURSOR     = "cursor";
    private static final String ATTR_AFTER_ROOM = "afterRoom";
    private static final String ATTR_AFTER      = "after";
    private static final String ATTR_SINCE      = "since";
    private static final String ATTR_MAX        = "max";
    private static final String ATTR_COUNT      = "count";
    private static final String ATTR_COMPLETE   = "complete";
    private static final String ATTR_NEXT       = "next";

    static final int DEFAULT_PAGE = 50;
    static final int MAX_PAGE = 100;
    /** Items one catch-up may bring in, across its pages. */
    static final int MAX_ITEMS_PER_CATCHUP = 1_000;
    /** Items this server sends in answer on one mapping per hour. */
    static final int MAX_ITEMS_PER_HOUR = 2_000;
    /** Requests answered on one mapping per minute; a full catch-up needs far fewer pages. */
    static final int MAX_REQUESTS_PER_MINUTE = 60;
    /** Allowance for clock skew: a later stamp is taken as now, and resuming by time starts this much earlier. */
    static final long SKEW_MS = 5 * 60_000L;

    private static final long PAGE_TIMEOUT_MS = 60_000L;
    private static final long DONE_GRACE_MS = 5_000L;
    /** Wait after a trigger, so a burst of reconnect events makes one request and routes have settled. */
    private static final long SETTLE_MS = 5_000L;
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final int MAX_ATTEMPTS = 3;
    /** How long a page's id stays good for its items after the page ends, and how soon a peer's request is not answered with ours. */
    private static final long REQUEST_MEMORY_MS = 60_000L;
    private static final long HOUR_MS = 3_600_000L;

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{8,64}");
    private static final Pattern CURSOR = Pattern.compile("[0-9]{1,18}");

    /** Where a catch-up resumes: the newest message it got, and when that arrived here. */
    private record Resume(String originRoom, String originId, long arrivedAt) { }

    /** One catch-up of ours: a mapping being caught up, page by page. */
    private static final class Run {
        final String localRoom;
        final String remoteDomain;
        final int attempt;
        final long since;
        int received;
        int pages;
        Resume lastReplayed;

        Run(String localRoom, String remoteDomain, int attempt, long since) {
            this.localRoom = localRoom;
            this.remoteDomain = remoteDomain;
            this.attempt = attempt;
            this.since = since;
        }
    }

    /** One page we asked for. */
    private static final class Page {
        final String id;
        final Run run;
        final int max;
        final long askedAt;
        int received;
        int expected = -1;
        boolean complete;
        long next;
        boolean ended;
        long endedAt;
        ScheduledFuture<?> timeout;

        Page(String id, Run run, int max, long askedAt) {
            this.id = id;
            this.run = run;
            this.max = max;
            this.askedAt = askedAt;
        }
    }

    /** Our answers on one mapping: until when a page is still being sent, and the items sent within the hour. */
    private static final class Answering {
        long sendingUntil;
        final Deque<long[]> sentInHour = new ArrayDeque<>();
        long minuteStartedAt;
        int requestsInMinute;

        int sentWithinHour(long now) {
            while (!sentInHour.isEmpty() && sentInHour.peekFirst()[0] < now - HOUR_MS) sentInHour.pollFirst();
            int n = 0;
            for (long[] s : sentInHour) n += (int) s[1];
            return n;
        }
    }

    private final FederationManager manager;
    private final InboundValidation validation;
    private final Map<String, Run> running = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Long> finishedAt = new LinkedHashMap<>();
    private final Map<String, Resume> resumeFrom = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, Answering> answering = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService scheduler;

    RoomCatchup(FederationManager manager, InboundValidation validation) {
        this.manager = manager;
        this.validation = validation;
    }

    /** Starts the scheduler and the hourly journal prune. */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "federation-catchup");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::housekeeping, 1, 1, TimeUnit.HOURS);
    }

    public void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) s.shutdownNow();
    }

    private static boolean enabled() {
        return FederationProperties.CATCHUP_ENABLED.getValue();
    }

    private static String key(String localRoom, String remoteDomain) {
        return localRoom + '\n' + remoteDomain;
    }

    // ── Requesting ──────────────────────────────────────────────────────────────

    /**
     * The path of an ACTIVE mapping came back: catches it up after a short settle, unless a catch-up
     * on it is already in progress. Called from the same events that re-sync occupants.
     */
    public void pathBack(RoomMapping m) {
        if (!enabled() || !m.isActive()) return;
        schedule(() -> begin(m.localRoomJid(), m.remoteDomain(), 1), SETTLE_MS);
    }

    private synchronized void begin(String localRoom, String remoteDomain, int attempt) {
        String k = key(localRoom, remoteDomain);
        Long finished = finishedAt.get(k);
        // The rest of a burst of reconnect events, arriving after a quick catch-up already ran.
        boolean justDone = finished != null && System.currentTimeMillis() - finished < 2 * SETTLE_MS;
        if (running.containsKey(k) || (attempt == 1 && justDone)) return;
        RoomMapping m = manager.getRoomManager().getMappingForLocal(localRoom, remoteDomain);
        if (!enabled() || m == null || !m.isActive() || !manager.getRoutingTable().isReachable(remoteDomain)) return;

        long now = System.currentTimeMillis();
        long notBefore = floor(m, now);
        Resume from = resumeFrom.get(k);
        if (from == null || from.arrivedAt() < notBefore) {
            RoomJournal.Entry e = journal().newestArrival(localRoom, remoteDomain, notBefore);
            from = e == null ? null : new Resume(e.originRoom(), e.originId(), e.accepted());
        }
        long since = from == null ? notBefore : Math.max(notBefore, from.arrivedAt() - SKEW_MS);
        Run run = new Run(localRoom, remoteDomain, attempt, since);
        running.put(k, run);
        Log.debug("Catching up {} from {} (attempt {}), after {} since {}", localRoom, remoteDomain, attempt,
                  from == null ? "nothing" : from.originId(), Instant.ofEpochMilli(since));
        askPage(run, m, 0, from);
    }

    private void askPage(Run run, RoomMapping m, long cursor, Resume after) {
        forgetOldPages();
        String id = "cu-" + StringUtils.randomString(16);
        Page page = new Page(id, run, DEFAULT_PAGE, System.currentTimeMillis());
        pages.put(id, page);
        run.pages++;
        page.timeout = schedule(() -> timedOut(id), PAGE_TIMEOUT_MS);

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_REQUEST, id);
        if (cursor > 0) attrs.put(ATTR_CURSOR, Long.toString(cursor));
        if (after != null) {
            attrs.put(ATTR_AFTER_ROOM, after.originRoom());
            attrs.put(ATTR_AFTER, after.originId());
        }
        attrs.put(ATTR_SINCE, XMPPDateTimeFormat.format(new Date(run.since)));
        attrs.put(ATTR_MAX, Integer.toString(page.max));
        String dest = m.remoteDomain();
        String nextHop = manager.getRoutingTable().findNextHop(dest).orElse(dest);
        FederationSender.send(REQUEST, dest, () -> FederationStanzaFactory.roomCatchup(
                REQUEST, nextHop, dest, m.token(), m.localRoomJid(), m.remoteRoomJid(), attrs));
    }

    /**
     * Whether a replay naming {@code requestId} answers a page we asked for in the last minute, on the
     * mapping of {@code targetRoom} with {@code src}, and is within the page's size. Counts it if so.
     */
    synchronized boolean acceptReplay(String requestId, String targetRoom, String src,
                                      String originRoom, String originId) {
        Page page = pages.get(requestId);
        long now = System.currentTimeMillis();
        if (page == null || (page.ended && now - page.endedAt > REQUEST_MEMORY_MS)
                || !page.run.localRoom.equals(targetRoom) || !page.run.remoteDomain.equals(src)
                || page.received >= page.max) {
            return false;
        }
        page.received++;
        page.run.received++;
        if (originRoom != null && originId != null) page.run.lastReplayed = new Resume(originRoom, originId, now);
        if (!page.ended && page.expected >= 0 && page.received >= page.expected) endPage(page);
        return true;
    }

    /** A room-catchup-done for one of our pages: ends the page once its items are in, or after a grace. */
    private synchronized void onDone(RoomMapping m, Element el) {
        Page page = pages.get(el.attributeValue(ATTR_REQUEST));
        if (page == null || page.ended || page.expected >= 0
                || !page.run.localRoom.equals(m.localRoomJid()) || !page.run.remoteDomain.equals(m.remoteDomain())) {
            Log.debug("{} from {} for an unknown or finished page, ignoring", DONE, m.remoteDomain());
            return;
        }
        page.expected = Math.max(0, parseInt(el.attributeValue(ATTR_COUNT), 0));
        page.complete = "true".equals(el.attributeValue(ATTR_COMPLETE));
        page.next = parseCursor(el.attributeValue(ATTR_NEXT));
        if (page.received >= page.expected) {
            endPage(page);
        } else {
            schedule(() -> endPageAfterGrace(page.id), DONE_GRACE_MS);
        }
    }

    private synchronized void endPageAfterGrace(String id) {
        Page page = pages.get(id);
        if (page != null && !page.ended) endPage(page);
    }

    /** Ends a page and asks for the next one, or finishes the catch-up. */
    private void endPage(Page page) {
        page.ended = true;
        page.endedAt = System.currentTimeMillis();
        if (page.timeout != null) page.timeout.cancel(false);
        Run run = page.run;
        String k = key(run.localRoom, run.remoteDomain);
        if (running.get(k) != run) return;
        RoomMapping m = manager.getRoomManager().getMappingForLocal(run.localRoom, run.remoteDomain);
        if (!page.complete && page.next > 0 && run.received < MAX_ITEMS_PER_CATCHUP && m != null && m.isActive()) {
            askPage(run, m, page.next, null);
            return;
        }
        running.remove(k);
        resumeFrom.remove(k);
        finishedAt.put(k, page.endedAt);
        if (run.received > 0 || Log.isDebugEnabled()) {
            Log.info("Caught up {} from {}: {} replayed message(s) in {} page(s){}", run.localRoom, run.remoteDomain,
                     run.received, run.pages, page.complete ? "" : ", stopping at the limit");
        }
    }

    /** A page got no done within the timeout: abandons the catch-up, keeping where it got to, and retries. */
    private synchronized void timedOut(String id) {
        Page page = pages.get(id);
        if (page == null || page.ended || page.expected >= 0) return;
        page.ended = true;
        page.endedAt = System.currentTimeMillis();
        Run run = page.run;
        String k = key(run.localRoom, run.remoteDomain);
        if (running.get(k) != run) return;
        running.remove(k);
        if (run.lastReplayed != null) resumeFrom.put(k, run.lastReplayed);
        if (run.attempt < MAX_ATTEMPTS) {
            Log.debug("Catch-up of {} from {} got no answer within {} s; retrying in {} s", run.localRoom,
                      run.remoteDomain, PAGE_TIMEOUT_MS / 1000, RETRY_DELAY_MS / 1000);
            schedule(() -> begin(run.localRoom, run.remoteDomain, run.attempt + 1), RETRY_DELAY_MS);
        } else {
            Log.info("Catch-up of {} from {} got no answer after {} attempts; {} may not support catch-up "
                   + "(1.13.0+), or the path is still broken", run.localRoom, run.remoteDomain, MAX_ATTEMPTS,
                     run.remoteDomain);
        }
    }

    private void forgetOldPages() {
        long now = System.currentTimeMillis();
        pages.values().removeIf(p -> p.ended && now - p.endedAt > REQUEST_MEMORY_MS);
        finishedAt.values().removeIf(t -> now - t > REQUEST_MEMORY_MS);
    }

    // ── Answering ───────────────────────────────────────────────────────────────

    /**
     * Handles a {@code room-catchup-request} or {@code room-catchup-done}: relays one for another
     * server, and checks one for us like a mapping lifecycle action (docs/protocol/09 § Room catch-up).
     */
    void handle(String element, String fromDomain, Element el) {
        String origin = el.attributeValue(ATTR_ORIGIN);
        String destination = el.attributeValue(ATTR_DESTINATION);
        if (origin == null || origin.isEmpty() || origin.equals(localDomain())) {
            Log.debug("{} from {} without a usable origin, dropping", element, fromDomain);
            return;
        }
        if (!validation.claimedOriginOk(fromDomain, origin, element)) return;
        if (!InboundValidation.isForUs(destination)) {
            relay(element, fromDomain, el, destination);
            return;
        }
        if (!validation.untrustedAllowsServer(fromDomain, localDomain())) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} addressed to this server, which it has not "
                   + "been exposed to", element, fromDomain);
            return;
        }
        RoomMapping m = mappingNamed(element, origin, el);
        if (m == null || !enabled()) return;
        if (REQUEST.equals(element)) {
            answer(m, el);
        } else {
            onDone(m, el);
        }
    }

    private void relay(String element, String fromDomain, Element el, String destination) {
        if (!validation.untrustedAllowsServer(fromDomain, destination)) {
            Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                     element, fromDomain, destination);
            return;
        }
        String nextHop = manager.getRoutingTable().findNextHop(destination).orElse(null);
        if (nextHop == null) {
            Log.debug("{}: no route to {}, dropping", element, destination);
            return;
        }
        FederationSender.send(element, destination, () -> FederationStanzaFactory.relayUnchanged(nextHop, el));
    }

    /**
     * The ACTIVE mapping a catch-up action speaks for, or null after logging why not: our room must be
     * federated and mapped to the sender's room on {@code origin}, and the token must match.
     */
    private RoomMapping mappingNamed(String element, String origin, Element el) {
        Element map = el.element("map");
        String theirRoom = map == null ? null : map.attributeValue(ATTR_LOCAL);
        String ourRoom   = map == null ? null : map.attributeValue(ATTR_REMOTE);
        RoomMapping m = ourRoom == null ? null : manager.getRoomManager().getMappingForLocal(ourRoom, origin);
        if (m == null || !m.isActive() || theirRoom == null || !theirRoom.equalsIgnoreCase(m.remoteRoomJid())
                || !manager.getRoomManager().isFederated(ourRoom)) {
            Log.warn("SECURITY: dropping {} from {} for local room {} — no ACTIVE mapping of a federated room "
                   + "here with that server's room {}", element, origin, ourRoom, theirRoom);
            return null;
        }
        if (!m.acceptsToken(el.attributeValue(ATTR_TOKEN, ""))) {
            Log.warn("SECURITY: dropping {} for {} from {} — token mismatch", element, ourRoom, origin);
            return null;
        }
        return m;
    }

    /** Answers a valid request with one paced page from the journal and a room-catchup-done. */
    private void answer(RoomMapping m, Element el) {
        String requestId = el.attributeValue(ATTR_REQUEST);
        Long since = parseTime(el.attributeValue(ATTR_SINCE));
        int max = parseInt(el.attributeValue(ATTR_MAX), DEFAULT_PAGE);
        String cursorAttr = el.attributeValue(ATTR_CURSOR);
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches() || since == null || max < 1
                || (cursorAttr != null && !CURSOR.matcher(cursorAttr).matches())) {
            Log.debug("{} from {} is malformed, dropping", REQUEST, m.remoteDomain());
            return;
        }
        String afterRoom = el.attributeValue(ATTR_AFTER_ROOM);
        String after = el.attributeValue(ATTR_AFTER);
        if (afterRoom == null || after == null) afterRoom = after = null;

        Answering a = answering.computeIfAbsent(key(m.localRoomJid(), m.remoteDomain()), k -> new Answering());
        long now = System.currentTimeMillis();
        int allowance;
        synchronized (a) {
            // Each request scans the journal, and an empty page is not paced by anything else.
            if (now - a.minuteStartedAt >= 60_000L) {
                a.minuteStartedAt = now;
                a.requestsInMinute = 0;
            }
            if (++a.requestsInMinute > MAX_REQUESTS_PER_MINUTE) {
                Log.debug("{} from {} for {} refused: more than {} requests in a minute", REQUEST,
                          m.remoteDomain(), m.localRoomJid(), MAX_REQUESTS_PER_MINUTE);
                return;
            }
            allowance = MAX_ITEMS_PER_HOUR - a.sentWithinHour(now);
            if (a.sendingUntil > now || allowance <= 0) {
                Log.debug("{} from {} for {} refused: a page is still being sent, or the hourly limit is reached",
                          REQUEST, m.remoteDomain(), m.localRoomJid());
                return;
            }
        }
        RoomJournal.Page page = journal().page(m.localRoomJid(), m.remoteDomain(), parseCursor(cursorAttr),
                afterRoom, after, since, m.activatedAt(), Math.min(Math.min(max, MAX_PAGE), allowance));
        List<Element> payloads = new ArrayList<>();
        List<RoomJournal.Entry> items = new ArrayList<>();
        for (RoomJournal.Entry e : page.items()) {
            Element payload = e.payloadElement();
            if (payload == null) continue;
            items.add(e);
            payloads.add(payload);
        }
        long delay = Math.max(0, FederationProperties.FILES_CHUNK_DELAY_MS.getValue());
        synchronized (a) {
            a.sendingUntil = now + delay * (items.size() + 1) + DONE_GRACE_MS;
            a.sentInHour.addLast(new long[] {now, items.size()});
        }
        for (int i = 0; i < items.size(); i++) {
            RoomJournal.Entry e = items.get(i);
            Element payload = payloads.get(i);
            schedule(() -> sendReplay(m, requestId, e, payload), delay * i);
        }
        boolean complete = page.complete();
        schedule(() -> sendDone(m, requestId, items.size(), complete, page.cursor(), a), delay * items.size());
        if (!items.isEmpty()) {
            Log.info("Answering a catch-up request from {} for {}: replaying {} message(s){}", m.remoteDomain(),
                     m.localRoomJid(), items.size(), complete ? "" : ", more to follow");
        } else {
            Log.debug("Answering {} from {} for {}: nothing to replay", requestId, m.remoteDomain(), m.localRoomJid());
        }
        askBackIfIdle(m);
    }

    private void sendReplay(RoomMapping m, String requestId, RoomJournal.Entry e, Element payload) {
        String dest = m.remoteDomain();
        String nextHop = manager.getRoutingTable().findNextHop(dest).orElse(dest);
        String hubs = FederationStanzaFactory.appendToTrail(e.hubs(), localDomain());
        String sent = XMPPDateTimeFormat.format(new Date(e.sent()));
        FederationSender.send("catch-up replay", dest, () -> FederationStanzaFactory.mucForward(nextHop, dest,
                m.remoteRoomJid(), "", localDomain(), hubs, sent, requestId, new Message(payload)));
    }

    private void sendDone(RoomMapping m, String requestId, int count, boolean complete, long next, Answering a) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_REQUEST, requestId);
        attrs.put(ATTR_COUNT, Integer.toString(count));
        attrs.put(ATTR_COMPLETE, Boolean.toString(complete));
        if (next > 0) attrs.put(ATTR_NEXT, Long.toString(next));
        String dest = m.remoteDomain();
        String nextHop = manager.getRoutingTable().findNextHop(dest).orElse(dest);
        FederationSender.send(DONE, dest, () -> FederationStanzaFactory.roomCatchup(
                DONE, nextHop, dest, m.token(), m.localRoomJid(), m.remoteRoomJid(), attrs));
        synchronized (a) {
            a.sendingUntil = 0;
        }
    }

    /** Asks the peer for what we missed too, unless we are already catching up on that mapping or just did. */
    private synchronized void askBackIfIdle(RoomMapping m) {
        String k = key(m.localRoomJid(), m.remoteDomain());
        Long finished = finishedAt.get(k);
        if (running.containsKey(k) || (finished != null && System.currentTimeMillis() - finished < REQUEST_MEMORY_MS)) {
            return;
        }
        pathBack(m);
    }

    // ── Replays and archives ────────────────────────────────────────────────────

    /**
     * The time a replay was first sent, or null to drop it: {@code sent} must parse, fall inside the
     * window and after the mapping with {@code src} last became ACTIVE. A stamp more than the skew
     * allowance ahead is taken as now.
     */
    Long replayTime(String sentAttr, String targetRoom, String src) {
        Long sent = parseTime(sentAttr);
        if (sent == null) {
            Log.debug("Replay into {} via {} has an unreadable sent time, dropping", targetRoom, src);
            return null;
        }
        long now = System.currentTimeMillis();
        if (sent < floor(manager.getRoomManager().getMappingForLocal(targetRoom, src), now)) {
            Log.debug("Replay into {} via {} was sent before the catch-up window, the mapping or this server's "
                    + "journal, dropping", targetRoom, src);
            return null;
        }
        return sent > now + SKEW_MS ? now : sent;
    }

    /**
     * The oldest traffic a catch-up on {@code m} may ask for or accept: the window, the mapping's last
     * activation, and the start of our journal, before which we cannot tell what we already have.
     */
    private long floor(RoomMapping m, long now) {
        long floor = Math.max(now - journal().windowMs(), journal().startedAt());
        return m == null ? floor : Math.max(floor, m.activatedAt());
    }

    private void housekeeping() {
        try {
            int dropped = journal().prune();
            if (dropped > 0) Log.debug("Room journal: pruned {} entr(ies) past the catch-up window", dropped);
            synchronized (this) {
                forgetOldPages();
            }
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again (CERT TPS03-J).
            Log.warn("Room journal housekeeping failed; it runs again in an hour", e);
        }
    }

    private RoomJournal journal() {
        return manager.getRoomJournal();
    }

    /** Runs {@code task} after {@code delayMs} on the scheduler; a stopped scheduler drops it. */
    private ScheduledFuture<?> schedule(Runnable task, long delayMs) {
        ScheduledExecutorService s = scheduler;
        if (s == null) return null;
        try {
            return s.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    Log.warn("Room catch-up task failed", e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Shutting down: nothing is left to catch up.
            return null;
        }
    }

    /** An XEP-0082 timestamp as epoch milliseconds, or null. */
    static Long parseTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long parseCursor(String value) {
        if (value == null || !CURSOR.matcher(value).matches()) return 0;
        return Long.parseLong(value);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
