package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

/**
 * Intercepts MUC packets and forwards them to federation peers using confirmed
 * room mappings.
 *
 * A room mapping (localRoomJid ↔ remoteRoomJid) must exist before any traffic
 * is forwarded.  The admin creates mappings in the Federation admin UI; both
 * servers exchange a room-mapping stanza to confirm the pairing.
 *
 * Messages: forwarded after MUC processing (incoming=true, processed=true) so
 * we relay the server-decorated groupchat packet.
 *
 * Presence: join/leave forwarded so remote room members appear as occupants.
 * Incoming presence before processing (processed=false) captures the join intent.
 *
 * Loop prevention: packets already marked with <fed-origin/> are silently skipped.
 */
public class FederationPacketInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(FederationPacketInterceptor.class);

    private final FederationManager manager;

    public FederationPacketInterceptor(FederationManager manager) {
        this.manager = manager;
    }

    @Override
    public void interceptPacket(Packet packet, Session session,
                                boolean incoming, boolean processed)
            throws PacketRejectedException {

        if (packet.getTo() == null) return;
        if (FederationStanzaFactory.isMarkedAsForwarded(packet)) return;

        if (packet instanceof Message msg && incoming && processed) {
            handleMessage(msg);
        } else if (packet instanceof Presence pres && incoming && processed) {
            handlePresence(pres);
        }
    }

    // ── Message forwarding ────────────────────────────────────────────────────

    private void handleMessage(Message msg) {
        if (msg.getType() != Message.Type.groupchat) return;

        String toDomain = msg.getTo().getDomain();
        if (!isConferenceDomain(toDomain)) return;

        String roomJid = roomJidFromTo(msg.getTo());
        if (roomJid == null) return;

        RoomMapping mapping = manager.getRoomManager().getMappingForLocal(roomJid);
        if (mapping == null) return;

        forwardToMapped(msg, mapping);
    }

    // ── Presence forwarding (join / leave) ────────────────────────────────────

    private void handlePresence(Presence pres) {
        String toDomain = pres.getTo().getDomain();
        if (!isConferenceDomain(toDomain)) return;

        // Must have a nick resource — that's the join/leave form
        if (pres.getTo().getResource() == null) return;

        String roomJid = roomJidFromTo(pres.getTo());
        if (roomJid == null) return;

        RoomMapping mapping = manager.getRoomManager().getMappingForLocal(roomJid);
        if (mapping == null) return;

        forwardToMapped(pres, mapping);
    }

    // ── Shared forwarding logic ────────────────────────────────────────────────

    private void forwardToMapped(Packet packet, RoomMapping mapping) {
        String localDomain  = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String remoteDomain = mapping.remoteDomain();

        // Prefer routing through the federation overlay (multi-hop) if we have a
        // route.  Fall back to direct Openfire routing (triggers S2S) if not.
        String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

        try {
            Packet copy = copyPacket(packet);
            XMPPServer.getInstance().getPacketRouter().route(
                FederationStanzaFactory.mucForward(
                    nextHop, remoteDomain, mapping.remoteRoomJid(), localDomain, copy)
            );
            Log.debug("Forwarded {} to {} via {}", packet.getClass().getSimpleName(),
                      mapping.remoteRoomJid(), nextHop);
        } catch (Exception e) {
            Log.warn("Failed to forward to {}: {}", remoteDomain, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isConferenceDomain(String domain) {
        return XMPPServer.getInstance().getMultiUserChatManager()
                         .getMultiUserChatServices().stream()
                         .anyMatch(svc -> svc.getServiceDomain().equals(domain));
    }

    /** Strips the /nick resource to get the bare room JID. */
    private String roomJidFromTo(org.xmpp.packet.JID to) {
        if (to == null || to.getNode() == null) return null;
        return to.getNode() + "@" + to.getDomain();
    }

    private Packet copyPacket(Packet p) {
        if (p instanceof Message m)  return new Message(m.getElement().createCopy());
        if (p instanceof Presence pr) return new Presence(pr.getElement().createCopy());
        return p;
    }
}
