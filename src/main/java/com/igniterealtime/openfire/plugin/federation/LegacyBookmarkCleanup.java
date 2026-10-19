package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Namespace;
import org.dom4j.QName;
import org.jivesoftware.openfire.PrivateStorage;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * One-time removal of the bookmarks the retired bookmark push wrote into users' XEP-0048 storage,
 * recognised by their {@code fed-origin} marker, and of its two retired settings.
 */
final class LegacyBookmarkCleanup {

    private static final Logger Log = LoggerFactory.getLogger(LegacyBookmarkCleanup.class);

    private static final String DONE_PROP    = "federation.legacyBookmarksPurged";
    private static final String BOOKMARKS_NS = "storage:bookmarks";
    private static final String MARKER_ATTR  = "fed-origin";

    private LegacyBookmarkCleanup() {}

    /** Runs the cleanup on a daemon thread unless it already ran: it reads and may rewrite every user's storage. */
    static void runOnceInBackground() {
        if (JiveGlobals.getBooleanProperty(DONE_PROP, false)) return;
        Thread t = new Thread(LegacyBookmarkCleanup::run, "federation-legacy-bookmark-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            JiveGlobals.deleteProperty("plugin.federation.directoryPublish");
            JiveGlobals.deleteProperty("plugin.federation.bookmarkPush");

            PrivateStorage ps = XMPPServer.getInstance().getPrivateStorage();
            if (ps == null || !ps.isEnabled()) {
                JiveGlobals.setProperty(DONE_PROP, "true");
                return;
            }
            int cleaned = 0;
            for (String username : XMPPServer.getInstance().getUserManager().getUsernames()) {
                try {
                    if (strip(ps, username)) cleaned++;
                } catch (RuntimeException e) {
                    Log.warn("Legacy bookmark cleanup: could not update {}", username, e);
                }
            }
            JiveGlobals.setProperty(DONE_PROP, "true");
            Log.info("Legacy bookmark cleanup: removed federation-injected bookmarks from {} user(s)", cleaned);
        } catch (RuntimeException e) {
            Log.warn("Legacy bookmark cleanup failed (will retry at next start)", e);
        }
    }

    /** Removes the marked entries from one user's storage; true if any were removed. */
    private static boolean strip(PrivateStorage ps, String username) {
        Element empty = DocumentHelper.createElement(QName.get("storage", Namespace.get(BOOKMARKS_NS)));
        Element updated = ps.get(username, empty).createCopy();
        boolean removed = false;
        for (Iterator<Element> it = updated.elementIterator(); it.hasNext(); ) {
            Element child = it.next();
            if ("url".equals(child.getName()) && child.attributeValue(MARKER_ATTR) != null) {
                it.remove();
                removed = true;
            }
        }
        if (removed) ps.add(username, updated);
        return removed;
    }
}
