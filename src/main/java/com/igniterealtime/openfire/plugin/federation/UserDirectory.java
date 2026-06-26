package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.session.ClientSession;
import org.xmpp.packet.JID;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opt-in cross-server user directory for 1:1 private messaging.
 *
 * <p>Two halves, mirroring the remote-room cache in {@link FederatedRoomManager}:
 * <ul>
 *   <li><b>Local snapshot</b> — {@link #localOnlineUsers()} returns the bare JIDs of users
 *       currently logged in to this server. Published to peers (when the admin enables
 *       publishing) as a {@code user-directory} gossip so they can show who is reachable here.</li>
 *   <li><b>Remote cache</b> — {@code origin domain → set of user JIDs} learned from inbound
 *       {@code user-directory} gossip. Purely in-memory and rebuilt from gossip; cleared per
 *       origin when that server becomes unreachable.</li>
 * </ul>
 *
 * This carries no presence: a JID listed here was online at the last gossip, nothing more.
 */
public class UserDirectory {

    /** origin server domain → bare JIDs advertised as online there. */
    private final Map<String, Set<String>> remoteUsers = new ConcurrentHashMap<>();

    /** Distinct bare JIDs of users currently authenticated to this server. */
    public Set<String> localOnlineUsers() {
        String localDomain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        SessionManager sm  = XMPPServer.getInstance().getSessionManager();
        Set<String> users  = new LinkedHashSet<>();
        for (ClientSession session : sm.getSessions()) {
            JID jid = session.getAddress();
            if (jid == null || jid.getNode() == null) continue;       // skip anonymous/no-node
            if (!localDomain.equals(jid.getDomain())) continue;       // only our own users
            users.add(jid.toBareJID());
        }
        return users;
    }

    /** Replaces the cached user set for one origin domain (empty set = clear). */
    public void setUsersForOrigin(String origin, Collection<String> userJids) {
        if (userJids == null || userJids.isEmpty()) {
            remoteUsers.remove(origin);
        } else {
            remoteUsers.put(origin, new LinkedHashSet<>(userJids));
        }
    }

    /** Drops all cached users for an origin (e.g. it became unreachable). */
    public void clearUsersForOrigin(String origin) {
        remoteUsers.remove(origin);
    }

    /** Snapshot of the remote-user cache: origin domain → bare JIDs. */
    public Map<String, Set<String>> getRemoteUsers() {
        return new ConcurrentHashMap<>(remoteUsers);
    }
}
