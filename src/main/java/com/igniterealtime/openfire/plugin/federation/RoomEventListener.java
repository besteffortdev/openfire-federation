package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.protocol.MucTrafficHandler;
import com.igniterealtime.openfire.plugin.federation.protocol.RoomMessageIds;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.muc.MUCEventListener;
import org.jivesoftware.openfire.stanzaid.StanzaIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

/**
 * Listens to MUC events: applies the room defaults to a new room, delivers buffered messages and
 * federated moderations to a joining occupant, and records which local messages a mapped room
 * accepted, so only those are forwarded.
 */
public class RoomEventListener implements MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(RoomEventListener.class);

    private final FederationManager manager;

    public RoomEventListener(FederationManager manager) {
        this.manager = manager;
    }

    @Override
    public void roomCreated(long timestamp, JID roomJID) {
        if (roomJID == null || roomJID.getNode() == null) return;
        String roomJid = roomJID.getNode() + "@" + roomJID.getDomain();
        try {
            manager.applyDefaultsTo(roomJid);
        } catch (RuntimeException e) {
            Log.warn("Could not apply the room defaults to {}", roomJid, e);
        }
    }

    /** Each step has its own barrier, so a failed flush still lets the moderations replay. */
    @Override
    public void occupantJoined(JID roomJID, JID user, String nickname) {
        if (roomJID == null || roomJID.getNode() == null) return;
        MucTrafficHandler muc = manager.getMucTraffic();
        if (muc == null) return;
        String roomJid = roomJID.getNode() + "@" + roomJID.getDomain();
        try {
            muc.flushPendingDeliveries(roomJid);
        } catch (RuntimeException e) {
            Log.warn("Could not flush the pending deliveries for {}", roomJid, e);
        }
        if (user == null || !XMPPServer.getInstance().isLocal(user)) return;
        try {
            muc.replayModerations(roomJid, user);
        } catch (RuntimeException e) {
            Log.warn("Could not replay the federated moderations in {}", roomJid, e);
        }
    }

    /** Marks a local occupant's message as accepted by its mapped room; injected remote messages are skipped. */
    @Override
    public void messageReceived(JID roomJID, JID user, String nickname, Message message) {
        if (roomJID == null || roomJID.getNode() == null || user == null || message == null) return;
        if (!XMPPServer.getInstance().isLocal(user)) return;
        String roomJid = roomJID.toBareJID();
        try {
            if (manager.getRoomManager().getMappingsForLocal(roomJid).isEmpty()) return;
            String sid = StanzaIDUtil.findFirstUniqueAndStableStanzaID(message, roomJid);
            String key = RoomMessageIds.keyOf(sid, message.getID(), user.toString());
            manager.getRoomMessageIds().markAccepted(roomJid, key);
        } catch (RuntimeException e) {
            Log.warn("Could not record an accepted message in {}", roomJid, e);
        }
    }

    @Override public void roomDestroyed(long timestamp, JID roomJID) { }
    @Override public void roomClearChatHistory(long timestamp, JID roomJID) { }
    @Override public void occupantLeft(JID roomJID, JID user, String nickname) { }
    @Override public void occupantNickKicked(JID roomJID, String nickname) { }
    @Override public void nicknameChanged(JID roomJID, JID user, String oldNickname, String newNickname) { }
    @Override public void privateMessageRecieved(JID toJID, JID fromJID, Message message) { }
    @Override public void roomSubjectChanged(JID roomJID, JID user, String newSubject) { }
}
