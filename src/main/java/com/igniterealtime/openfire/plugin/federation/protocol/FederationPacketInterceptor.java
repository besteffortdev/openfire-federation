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
import org.xmpp.packet.IQ;
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
     * By default (see {@link FederationProperties#ALLOW_REMOTE_ROOM_TRAVERSAL}) a remote user may
     * traverse federation to join or address a local MUC room directly over S2S, so ad-hoc / private
     * rooms that are never advertised stay reachable. Federation's own traffic is unaffected either
     * way: virtual occupants are injected via {@code directDeliver} (and are marked-forwarded), never
     * as real S2S presence, so the lock-down only ever catches genuine direct cross-server room access.
     * Toggle via the Federation admin console.
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

        // Room lock-down (opt-in): when remote-room traversal is disabled, reject any direct
        // remote-origin packet aimed at a local MUC room. Done as early as possible (before the
        // room processes it) so a remote join never takes effect.
        enforceRoomTraversalPolicy(packet);

        // 1:1 private messaging: relay an outbound message bound for a MULTI-HOP peer user
        // through the federation overlay (and suppress native S2S). Runs before the MUC
        // forwarding branches below since it targets user JIDs, not rooms.
        relayDirectMessage(packet, session);

        // 1:1 presence: relay an outbound directed presence / subscription stanza to a
        // multi-hop peer user so contacts + live presence work across the overlay.
        relayDirectPresence(packet, session);

        // 1:1 IQ: relay a user-addressed IQ (vCard, disco, caps, version, ping, PEP) to a
        // multi-hop peer user so profile/avatar/discovery work across the overlay.
        relayDirectIq(packet, session);

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
     * When remote-room traversal is disabled, rejects a packet that originates on a remote server
     * and targets a local MUC room, so the only way for a remote user to participate is through a
     * federation room mapping. A no-op when traversal is allowed (the default), when the target is
     * not a local conference domain, or when the sender is local.
     */
    private void enforceRoomTraversalPolicy(Packet packet) throws PacketRejectedException {
        if (FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL.getValue()) return;  // traversal permitted

        String toDomain = packet.getTo().getDomain();
        if (!isConferenceDomain(toDomain)) return;

        JID from = packet.getFrom();
        if (from == null) return;
        if (!XMPPServer.getInstance().isRemote(from)) return;  // local users are allowed

        Log.info("Blocking direct S2S MUC access from {} to {} — remote-room traversal disabled",
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
    private void relayDirectMessage(Packet packet, Session session) throws PacketRejectedException {
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
        if (!isMultiHopPeer(toDomain)) return;                          // direct peers use native S2S

        stampFrom(msg, session);
        if (manager.forwardDirectMessage(msg)) {
            Log.info("Relayed 1:1 message {} -> {} over overlay (multi-hop)", msg.getFrom(), to);
            throw new PacketRejectedException("Relayed over federation overlay to " + toDomain);
        }
    }

    /**
     * Relays a locally-originated directed presence (subscribe/subscribed/unsubscribe/unsubscribed/
     * probe/available/unavailable addressed to a specific user) to an overlay-reachable peer user,
     * then rejects it to suppress native S2S.  This is what carries the subscription handshake and
     * live presence for on-demand federated contacts.  Untouched: MUC presence (conference domain),
     * broadcast presence (no addressed user), local delivery, and non-overlay domains.
     */
    private void relayDirectPresence(Packet packet, Session session) throws PacketRejectedException {
        if (!(packet instanceof Presence pres)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = pres.getTo();
        if (to == null || to.getNode() == null) return;                 // directed presence to a user
        String toDomain = to.getDomain();
        if (isConferenceDomain(toDomain)) return;                       // MUC join/leave, handled below
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours
        if (!isMultiHopPeer(toDomain)) return;                          // direct peers use native S2S

        stampFrom(pres, session);
        if (manager.forwardDirectPresence(pres)) {
            Log.info("Relayed 1:1 presence {} {} -> {} over overlay (multi-hop)",
                     pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), to);
            // We just approved a remote contact — record them as a subscriber (so later status
            // changes get forwarded) and push our user's current presence now, since Openfire's own
            // push to a new remote subscriber is routed past this interceptor.
            if (pres.getType() == Presence.Type.subscribed) {
                manager.addRemoteSubscriber(pres.getFrom(), to);
                manager.pushUserPresenceTo(to, pres.getFrom());
            } else if (pres.getType() == Presence.Type.unsubscribed) {
                manager.removeRemoteSubscriber(pres.getFrom(), to);
            }
            throw new PacketRejectedException("Relayed presence over federation overlay to " + toDomain);
        }
    }

    /**
     * Relays a locally-originated user-addressed IQ (vCard, disco#info, entity-caps, version, ping,
     * PEP …) to a multi-hop peer user, then rejects it to suppress native S2S.  Covers get/set/result/
     * error uniformly, so a reply (an outbound IQ from the answering server to the remote requester)
     * relays back the same way, correlated by id.  Untouched: server-addressed IQs (no node), MUC
     * service IQs, local delivery, and direct peers (native S2S).
     */
    private void relayDirectIq(Packet packet, Session session) throws PacketRejectedException {
        if (!(packet instanceof IQ iq)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = iq.getTo();
        if (to == null || to.getNode() == null) return;                 // user-addressed IQ only
        String toDomain = to.getDomain();
        if (isConferenceDomain(toDomain)) return;                       // MUC service IQ, not a user
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours
        if (!isMultiHopPeer(toDomain)) return;                          // direct peers use native S2S

        stampFrom(iq, session);
        if (manager.forwardDirectIq(iq)) {
            Log.info("Relayed 1:1 IQ {} {} -> {} over overlay (multi-hop)", iq.getType(), iq.getFrom(), to);
            throw new PacketRejectedException("Relayed IQ over federation overlay to " + toDomain);
        }
    }

    /**
     * True when {@code toDomain} is reachable over the federation overlay but NOT a directly-connected
     * peer — i.e. its route's next hop is some intermediate server.  Direct peers have a working
     * native S2S link that already handles 1:1 messages, presence and subscriptions correctly, so we
     * leave those alone and only relay where there is no direct link (the overlay's reason to exist).
     */
    private boolean isMultiHopPeer(String toDomain) {
        return manager.getRoutingTable().findNextHop(toDomain)
                      .map(nextHop -> !nextHop.equals(toDomain))
                      .orElse(false);
    }

    /** Ensures a relayed stanza carries a {@code from} (clients often omit it; the recipient needs it). */
    private void stampFrom(Packet packet, Session session) {
        if (packet.getFrom() == null && session != null && session.getAddress() != null) {
            packet.setFrom(session.getAddress());
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
