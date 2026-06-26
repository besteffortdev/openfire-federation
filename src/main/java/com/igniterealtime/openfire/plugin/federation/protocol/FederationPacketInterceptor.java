package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.jivesoftware.openfire.XMPPServer;
import org.xmpp.packet.JID;
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

    /**
     * When true (default), a remote user cannot join or address a local MUC room
     * directly over a raw S2S connection — they must go through a federation room
     * mapping. Federation's own traffic is unaffected: virtual occupants are injected
     * via {@code directDeliver} (and are marked-forwarded), never as real S2S presence,
     * so this gate only catches genuine direct cross-server room access.
     * Toggle via the Federation admin console, or {@link FederationProperties#BLOCK_DIRECT_MUC}.
     */
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

        // Force federation: reject any direct remote-origin packet aimed at a local
        // MUC room. Done as early as possible (before the room processes it) so a
        // remote join never takes effect.
        enforceFederationOnly(packet);

        // 1:1 private messaging: relay an outbound message bound for an overlay-reachable
        // peer user through the federation overlay (and suppress native S2S). Runs before
        // the MUC forwarding branches below since it targets user JIDs, not rooms.
        relayDirectMessage(packet);

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

    // ── Force-federation gate ──────────────────────────────────────────────────

    /**
     * Rejects a packet that originates on a remote server and targets a local MUC
     * room, so the only way for a remote user to participate is through a federation
     * room mapping. A no-op when the feature is disabled, when the target is not a
     * local conference domain, or when the sender is local.
     */
    private void enforceFederationOnly(Packet packet) throws PacketRejectedException {
        if (!FederationProperties.BLOCK_DIRECT_MUC.getValue()) return;

        String toDomain = packet.getTo().getDomain();
        if (!isConferenceDomain(toDomain)) return;

        JID from = packet.getFrom();
        if (from == null) return;
        if (!XMPPServer.getInstance().isRemote(from)) return;  // local users are allowed

        Log.info("Blocking direct S2S MUC access from {} to {} — federation required",
                 from, packet.getTo());
        throw new PacketRejectedException(
                "Direct cross-server access to this room is disabled; use federation.");
    }

    // ── 1:1 private-message relay ──────────────────────────────────────────────

    /**
     * Relays a locally-originated 1:1 message to a user on an overlay-reachable peer domain
     * through the federation overlay, then rejects it so Openfire does not also attempt native
     * S2S delivery.  A no-op when the feature is disabled, when the target is not a remote peer
     * user (local user, MUC service, or a domain not in our routing table), or for groupchat /
     * error stanzas.  Replies are handled symmetrically by the destination server's interceptor.
     */
    private void relayDirectMessage(Packet packet) throws PacketRejectedException {
        if (!(packet instanceof Message msg)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        // Only genuine 1:1 chat. groupchat is MUC (handled below); error/result are left alone so
        // we never bounce-loop a delivery failure.
        Message.Type type = msg.getType();
        if (type == Message.Type.groupchat || type == Message.Type.error) return;

        JID to = msg.getTo();
        if (to == null || to.getNode() == null) return;                 // must address a real user
        String toDomain = to.getDomain();
        if (isConferenceDomain(toDomain)) return;                       // MUC service, not a user
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours

        // Only overlay-reachable peer domains are relayed; ordinary external S2S is untouched.
        if (!manager.getRoutingTable().isReachable(toDomain)) return;

        if (manager.forwardDirectMessage(msg)) {
            throw new PacketRejectedException("Relayed over federation overlay to " + toDomain);
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
