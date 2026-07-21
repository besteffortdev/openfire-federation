package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.muc.MUCEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

/**
 * Two unrelated jobs share this single {@link MUCEventListener} registration (Openfire dispatches
 * to every registered listener regardless of how many events it actually cares about, so there is
 * no benefit to two separate listener objects):
 * <ul>
 *   <li>{@link #roomCreated} applies the admin's {@link RoomDefaultsManager} rules to a MUC room the
 *       moment it is created, so rooms named by a convention (e.g. {@code mychat_ext}) come up
 *       already federated/visible/auto-accepting/auto-mapping without any manual step.</li>
 *   <li>{@link #occupantJoined} flushes {@code FederationIQHandler}'s buffered deliveries for that
 *       room — messages (and any file share riding with one) that arrived while the room had zero
 *       local occupants, most often a brief client reconnect blip. See
 *       {@code FederationIQHandler.PendingDelivery}.</li>
 * </ul>
 * Every other {@link MUCEventListener} callback is a deliberate no-op.
 */
public class RoomCreationListener implements MUCEventListener {

    private static final Logger Log = LoggerFactory.getLogger(RoomCreationListener.class);

    private final FederationManager manager;

    public RoomCreationListener(FederationManager manager) {
        this.manager = manager;
    }

    @Override
    public void roomCreated(long timestamp, JID roomJID) {
        if (roomJID == null || roomJID.getNode() == null) return;
        String roomJid = roomJID.getNode() + "@" + roomJID.getDomain();
        try {
            manager.applyDefaultsTo(roomJid);
        } catch (Exception e) {
            Log.warn("Failed to apply default federation settings to {}: {}", roomJid, e.getMessage());
        }
    }

    @Override
    public void occupantJoined(JID roomJID, JID user, String nickname) {
        if (roomJID == null || roomJID.getNode() == null) return;
        String roomJid = roomJID.getNode() + "@" + roomJID.getDomain();
        try {
            if (manager.getIQHandler() != null) manager.getIQHandler().flushPendingDeliveries(roomJid);
        } catch (Exception e) {
            Log.warn("Failed to flush pending deliveries for {}: {}", roomJid, e.getMessage());
        }
    }

    // ── no-ops ──────────────────────────────────────────────────────────────────
    @Override public void roomDestroyed(long timestamp, JID roomJID) { }
    @Override public void roomClearChatHistory(long timestamp, JID roomJID) { }
    @Override public void occupantLeft(JID roomJID, JID user, String nickname) { }
    @Override public void occupantNickKicked(JID roomJID, String nickname) { }
    @Override public void nicknameChanged(JID roomJID, JID user, String oldNickname, String newNickname) { }
    @Override public void messageReceived(JID roomJID, JID user, String nickname, Message message) { }
    @Override public void privateMessageRecieved(JID toJID, JID fromJID, Message message) { }
    @Override public void roomSubjectChanged(JID roomJID, JID user, String newSubject) { }
}
