package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.jivesoftware.openfire.PrivateStorage;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Injects a peer's advertised connected clients into this server's local users as XEP-0048
 * {@code <url>} bookmarks, delivered through XEP-0049 private storage (the {@code storage:bookmarks}
 * element that clients such as Spark read at login).
 *
 * <p>Each injected bookmark carries a {@code fed-origin} marker attribute naming the advertising
 * server, so a re-push is idempotent (we replace only our own entries for that origin) and a
 * withdrawal is surgical (we remove only our entries, never the user's own bookmarks).
 *
 * <p>An in-memory per-origin snapshot of the last applied set short-circuits unchanged pushes, so
 * presence flaps on a remote server don't rewrite every local user's storage. Each <em>changed</em>
 * apply still enumerates all local users — fine for small/LDAP deployments and the reason bookmark
 * publishing is opt-in.
 */
public class BookmarkInjector {

    private static final Logger Log = LoggerFactory.getLogger(BookmarkInjector.class);

    private static final String BOOKMARKS_NS = "storage:bookmarks";
    private static final String MARKER_ATTR  = "fed-origin";

    /** origin domain → the set of advertised bare JIDs last applied (sorted, for cheap equality). */
    private final Map<String, List<String>> lastApplied = new ConcurrentHashMap<>();

    /**
     * Runs the per-user storage rewrites off the caller's thread.  applyForOrigin is invoked
     * from the S2S IQ-processing thread, and a changed push is a DB read+write for EVERY local
     * user — on a large user base that would stall federation packet processing for the whole
     * rewrite.  Single-threaded so apply/withdraw for the same origin keep their submission
     * order; daemon so a hung write can never block plugin unload.
     */
    private final java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "federation-bookmark-injector");
            t.setDaemon(true);
            return t;
        });

    /**
     * Replaces this server's injected bookmarks for {@code originDomain} with the supplied users.
     * An empty (or null) collection withdraws the origin's bookmarks entirely.  The payload is
     * snapshotted here; the storage rewrite itself runs asynchronously on {@link #executor}.
     */
    public void applyForOrigin(String originDomain,
                               Collection<UserDirectory.UserPresence> users) {
        if (originDomain == null || originDomain.isBlank()) return;

        List<String> jids = (users == null) ? List.of()
                : users.stream()
                       .map(UserDirectory.UserPresence::jid)
                       .filter(j -> j != null && !j.isBlank())
                       .sorted()
                       .collect(Collectors.toList());

        try {
            executor.execute(() -> applyJidsForOrigin(originDomain, jids));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Executor already shut down — the plugin is stopping; nothing to inject.
        }
    }

    private synchronized void applyJidsForOrigin(String originDomain, List<String> jids) {
        // No-op guard: skip the (potentially expensive) per-user rewrite when nothing changed.
        if (jids.equals(lastApplied.getOrDefault(originDomain, List.of()))) return;

        PrivateStorage ps = XMPPServer.getInstance().getPrivateStorage();
        if (ps == null || !ps.isEnabled()) {
            Log.warn("bookmark-push: private storage is disabled — cannot inject bookmarks for {}", originDomain);
            return;
        }

        Collection<String> usernames;
        try {
            usernames = XMPPServer.getInstance().getUserManager().getUsernames();
        } catch (Exception e) {
            Log.warn("bookmark-push: could not enumerate local users: {}", e.getMessage());
            return;
        }

        int touched = 0;
        for (String username : usernames) {
            try {
                if (applyToUser(ps, username, originDomain, jids)) touched++;
            } catch (Exception e) {
                Log.warn("bookmark-push: failed to update bookmarks for {}: {}", username, e.getMessage());
            }
        }

        if (jids.isEmpty()) lastApplied.remove(originDomain);
        else                lastApplied.put(originDomain, jids);

        Log.info("bookmark-push: {} {} bookmark(s) from {} across {} local user(s)",
                 jids.isEmpty() ? "withdrew" : "applied", jids.size(), originDomain, touched);
    }

    /** Merges the origin's bookmarks into one user's storage. Returns true if the storage changed. */
    private boolean applyToUser(PrivateStorage ps, String username, String originDomain, List<String> jids) {
        Element defaultStorage = DocumentHelper.createElement(
                QName.get("storage", Namespace.get(BOOKMARKS_NS)));
        Element current = ps.get(username, defaultStorage);
        // get() may hand back our default (no stored data) or the persisted element; copy either way
        // so we never mutate shared state and always write a self-contained document.
        Element updated = current.createCopy();

        // Strip our previous entries for this origin (idempotent re-push / withdrawal).
        boolean removed = false;
        for (Iterator<Element> it = updated.elementIterator(); it.hasNext(); ) {
            Element child = it.next();
            if ("url".equals(child.getName()) && originDomain.equals(child.attributeValue(MARKER_ATTR))) {
                it.remove();
                removed = true;
            }
        }

        // Append the current advertised set (skipped entirely on withdrawal).
        for (String jid : jids) {
            Element url = updated.addElement("url");
            url.addAttribute("name", jid);
            url.addAttribute("url", "xmpp:" + jid + "?message");
            url.addAttribute(MARKER_ATTR, originDomain);
        }

        if (!removed && jids.isEmpty()) return false;   // nothing to remove and nothing to add
        ps.add(username, updated);
        return true;
    }

    /** Drops all in-memory state (e.g. on plugin shutdown). Does not touch persisted storage. */
    public void clear() {
        lastApplied.clear();
    }

    /**
     * Stops the background writer (pending rewrites are discarded) and drops in-memory state.
     * Called on plugin shutdown; injected bookmarks remain in storage and are reconciled by the
     * next push after a restart.
     */
    public void shutdown() {
        executor.shutdownNow();
        lastApplied.clear();
    }

    /** Origins we currently have bookmarks injected for (diagnostics). */
    public Collection<String> activeOrigins() {
        return new ArrayList<>(lastApplied.keySet());
    }

    /**
     * Snapshot of what each peer currently advertises to us (origin domain → injected bare JIDs).
     * Reflects the live injected state — an origin disappears when it withdraws. For the admin UI.
     */
    public synchronized Map<String, List<String>> getAdvertised() {
        Map<String, List<String>> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : lastApplied.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }
}
