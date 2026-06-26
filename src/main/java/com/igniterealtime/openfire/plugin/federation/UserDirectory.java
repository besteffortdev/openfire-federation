package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.xmpp.packet.JID;
import org.xmpp.packet.Presence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in cross-server user directory for 1:1 private messaging, now carrying live presence.
 *
 * <p>Two halves, mirroring the remote-room cache in {@link FederatedRoomManager}:
 * <ul>
 *   <li><b>Local snapshot</b> — {@link #localOnlineUsers()} returns this server's logged-in users
 *       with their current presence (show/status). Published to peers (when the admin enables
 *       publishing) as a {@code user-directory} gossip so they can show who is reachable here and
 *       at what availability.</li>
 *   <li><b>Remote cache</b> — {@code origin domain → users} learned from inbound {@code user-directory}
 *       gossip. Purely in-memory and rebuilt from gossip; cleared per origin when that server becomes
 *       unreachable.</li>
 * </ul>
 *
 * The presence here is whatever a peer last gossiped — a lightweight status board, distinct from the
 * real per-contact subscription presence that flows through Openfire's roster engine.
 */
public class UserDirectory {

    /** A directory entry: a bare JID plus its last-known presence. Empty show = plain available. */
    public record UserPresence(String jid, String show, String status) {}

    /** origin server domain → users advertised there. */
    private final Map<String, List<UserPresence>> remoteUsers = new ConcurrentHashMap<>();

    /** This server's logged-in users with current presence (deduped to one entry per bare JID). */
    public List<UserPresence> localOnlineUsers() {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        SessionManager sm  = XMPPServer.getInstance().getSessionManager();
        Map<String, UserPresence> byJid = new LinkedHashMap<>();
        for (ClientSession session : sm.getSessions()) {
            JID jid = session.getAddress();
            if (jid == null || jid.getNode() == null) continue;       // skip anonymous/no-node
            if (!localDomain.equals(jid.getDomain())) continue;       // only our own users
            String show = "", status = "";
            Presence p = session.getPresence();
            if (p != null && p.isAvailable()) {
                if (p.getShow() != null)   show   = p.getShow().name();   // away / xa / dnd / chat
                if (p.getStatus() != null) status = p.getStatus();
            }
            // One entry per user; keep the first session seen (good enough for a status board).
            byJid.putIfAbsent(jid.toBareJID(), new UserPresence(jid.toBareJID(), show, status));
        }
        return new ArrayList<>(byJid.values());
    }

    /** Replaces the cached user set for one origin domain (empty = clear). */
    public void setUsersForOrigin(String origin, Collection<UserPresence> users) {
        if (users == null || users.isEmpty()) {
            remoteUsers.remove(origin);
        } else {
            remoteUsers.put(origin, new ArrayList<>(users));
        }
    }

    /** Drops all cached users for an origin (e.g. it became unreachable). */
    public void clearUsersForOrigin(String origin) {
        remoteUsers.remove(origin);
    }

    /** Snapshot of the remote-user cache: origin domain → users (with presence). */
    public Map<String, List<UserPresence>> getRemoteUsers() {
        return new LinkedHashMap<>(remoteUsers);
    }
}
