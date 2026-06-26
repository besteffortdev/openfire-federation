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

import java.util.List;

/**
 * Intercepts MUC packets and forwards them to all mapped federation peers.
 *
 * A local room can be mapped to multiple remote rooms on different peer domains
 * (hub topology).  Every mapping for the room receives a copy of each
 * message/presence that passes through the local MUC service.
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

        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(roomJid);
        for (RoomMapping mapping : mappings) {
            forwardToMapped(msg, mapping);
        }
    }

    // ── Presence forwarding (join / leave) ────────────────────────────────────

    private void handlePresence(Presence pres) {
        String toDomain = pres.getTo().getDomain();
        if (!isConferenceDomain(toDomain)) return;

        // Must have a nick resource — that's the join/leave form.
        if (pres.getTo().getResource() == null) return;

        String roomJid = roomJidFromTo(pres.getTo());
        if (roomJid == null) return;

        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(roomJid);
        for (RoomMapping mapping : mappings) {
            forwardToMapped(pres, mapping);
        }
    }

    // ── Shared forwarding logic ────────────────────────────────────────────────

    private void forwardToMapped(Packet packet, RoomMapping mapping) {
        String localDomain  = XMPPServer.getInstance().getServerInfo().getXMPPDomain();
        String remoteDomain = mapping.remoteDomain();

        String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

        try {
            Packet copy = copyPacket(packet);
            XMPPServer.getInstance().getPacketRouter().route(
                FederationStanzaFactory.mucForward(
                    nextHop, remoteDomain, mapping.remoteRoomJid(), localDomain, localDomain, copy)
            );
            Log.debug("Forwarded {} to {} via {}",
                      packet.getClass().getSimpleName(), mapping.remoteRoomJid(), nextHop);
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

    private String roomJidFromTo(org.xmpp.packet.JID to) {
        if (to == null || to.getNode() == null) return null;
        return to.getNode() + "@" + to.getDomain();
    }

    private Packet copyPacket(Packet p) {
        if (p instanceof Message m)   return new Message(m.getElement().createCopy());
        if (p instanceof Presence pr) return new Presence(pr.getElement().createCopy());
        return p;
    }
}
