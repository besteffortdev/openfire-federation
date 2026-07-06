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

    /**
     * Suppresses the spurious local {@code not-allowed} (405) error that Openfire's IQ/Presence router
     * synchronously bounces back to the sender whenever we relay-and-reject a stanza aimed at an
     * overlay-reachable peer. The genuine reply arrives later over the overlay; without this, the
     * client matches the faster local 405 first and the request fails (e.g. a multi-hop MUC-join
     * disco surfacing as "not allowed" / "you do not have permission to create room").
     *
     * We record each relayed IQ/presence as {@code id|from|to} with a short expiry, then drop the
     * first matching error bounce (which swaps from/to and keeps the id). Consume-once: any genuine
     * error the room sends afterwards over the overlay is not matched and reaches the client normally.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> pendingBounces =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BOUNCE_TTL_MS = 30_000L;

    public FederationPacketInterceptor(FederationManager manager) {
        this.manager = manager;
    }

    /** Records a just-relayed IQ/presence so its imminent local not-allowed bounce can be swallowed. */
    private void rememberRelayedForBounce(Packet packet) {
        if (!(packet instanceof IQ) && !(packet instanceof Presence)) return;
        JID from = packet.getFrom();
        JID to   = packet.getTo();
        String id = packet.getID();
        if (id == null || from == null || to == null) return;
        long now = System.currentTimeMillis();
        if (pendingBounces.size() > 256) {
            pendingBounces.values().removeIf(exp -> exp < now);
        }
        pendingBounces.put(bounceKey(id, from.toString(), to.toString()), now + BOUNCE_TTL_MS);
    }

    /** True if {@code packet} is the local not-allowed bounce for a stanza we just relayed (consumed once). */
    private boolean consumeSpuriousBounce(Packet packet) {
        boolean isError = (packet instanceof IQ iq && iq.getType() == IQ.Type.error)
                       || (packet instanceof Presence pr && pr.getType() == Presence.Type.error);
        if (!isError) return false;
        JID from = packet.getFrom();
        JID to   = packet.getTo();
        String id = packet.getID();
        if (id == null || from == null || to == null) return false;
        // The bounce swaps from/to relative to the relayed stanza but keeps the same id.
        Long exp = pendingBounces.remove(bounceKey(id, to.toString(), from.toString()));
        return exp != null && exp >= System.currentTimeMillis();
    }

    private String bounceKey(String id, String from, String to) {
        return id + '|' + from + '|' + to;
    }

    @Override
    public void interceptPacket(Packet packet, Session session,
                                boolean incoming, boolean processed)
            throws PacketRejectedException {

        if (packet.getTo() == null) return;
        if (FederationStanzaFactory.isMarkedAsForwarded(packet)) return;

        if (processed) {
            // Post-processing only feeds the mapped-room forwarders. The relay/policy checks
            // below run pre-processing ONLY: a rejection is only honored before processing,
            // and re-running a relay here could deliver the same stanza twice if a route
            // appeared between the two phases (pre-phase found no route, native processing
            // ran, post-phase would relay it again).
            if (incoming) {
                if (packet instanceof Message msg)       handleMessage(msg);
                else if (packet instanceof Presence pres) handlePresence(pres);
            }
            return;
        }

        // Swallow the spurious local not-allowed bounce Openfire generates for a stanza we relayed over
        // the overlay (the real reply rides the overlay home). Done first so the bounce never reaches
        // the client. The error type means the router does not bounce again for this rejection.
        if (consumeSpuriousBounce(packet)) {
            throw new PacketRejectedException("Suppressed spurious not-allowed bounce for an overlay-relayed stanza");
        }

        // Computed once per packet: every check below needs it, and each lookup streams over
        // the MUC services — this interceptor runs on every stanza the server touches.
        boolean toLocalConference = isConferenceDomain(packet.getTo().getDomain());

        // Room lock-down (opt-in): when remote-room traversal is disabled, reject any direct
        // remote-origin packet aimed at a local MUC room. Done as early as possible (before the
        // room processes it) so a remote join never takes effect.
        enforceRoomTraversalPolicy(packet, toLocalConference);

        // 1:1 private messaging: relay an outbound message bound for a MULTI-HOP peer user
        // through the federation overlay (and suppress native S2S). Runs before the MUC
        // forwarding branches below since it targets user JIDs, not rooms.
        relayDirectMessage(packet, session, toLocalConference);

        // 1:1 presence: relay an outbound directed presence / subscription stanza to a
        // multi-hop peer user so contacts + live presence work across the overlay.
        relayDirectPresence(packet, session, toLocalConference);

        // 1:1 IQ: relay a user-addressed IQ (vCard, disco, caps, version, ping, PEP) to a
        // multi-hop peer user so profile/avatar/discovery work across the overlay.
        relayDirectIq(packet, session, toLocalConference);

        // Remote MUC: relay a stanza addressed to a room on a MULTI-HOP peer's conference service
        // (join/leave presence, groupchat/PM, MUC IQ) over the overlay so a user can reach a remote
        // room directly — even an ad-hoc/private one that was never advertised as a mapping.
        relayRemoteMuc(packet, session, toLocalConference);
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
    private void enforceRoomTraversalPolicy(Packet packet, boolean toLocalConference)
            throws PacketRejectedException {
        if (FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL.getValue()) return;  // traversal permitted

        if (!toLocalConference) return;

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
    private void relayDirectMessage(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof Message msg)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        // Leave error stanzas alone so we never bounce-loop a delivery failure.
        Message.Type type = msg.getType();
        if (type == Message.Type.error) return;

        JID to = msg.getTo();
        if (to == null || to.getNode() == null) return;                 // must address a real user
        if (toLocalConference) return;                                  // to a LOCAL room — not this path
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours
        String toDomain = to.getDomain();
        if (!isMultiHopPeer(toDomain)) return;                          // direct peers use native S2S

        // groupchat on this (user-addressed) path is the room→occupant leg of a federated remote-room
        // join: a local MUC broadcasting to a remote occupant who joined directly across the overlay.
        // Relay only those — and only for UNMAPPED rooms, so we never double up with the mapping
        // (mucForward) path that owns propagation for mapped rooms.
        if (type == Message.Type.groupchat) {
            JID from = msg.getFrom();
            if (from == null || from.getNode() == null || !isConferenceDomain(from.getDomain())) return;
            String roomJid = from.getNode() + "@" + from.getDomain();
            if (!manager.getRoomManager().getMappingsForLocal(roomJid).isEmpty()) return;
        }

        stampFrom(msg, session);
        if (manager.forwardDirectMessage(msg)) {
            Log.info("Relayed message {} -> {} over overlay (multi-hop)", msg.getFrom(), to);
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
    private void relayDirectPresence(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof Presence pres)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = pres.getTo();
        if (to == null || to.getNode() == null) return;                 // directed presence to a user
        if (toLocalConference) return;                                  // MUC join/leave, handled below
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours
        String toDomain = to.getDomain();
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
            rememberRelayedForBounce(pres);
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
    private void relayDirectIq(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof IQ iq)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = iq.getTo();
        if (to == null || to.getNode() == null) return;                 // user-addressed IQ only
        if (toLocalConference) return;                                  // MUC service IQ, not a user
        if (XMPPServer.getInstance().isLocal(to)) return;               // local delivery — not ours
        String toDomain = to.getDomain();
        if (!isMultiHopPeer(toDomain)) return;                          // direct peers use native S2S

        stampFrom(iq, session);
        if (manager.forwardDirectIq(iq)) {
            Log.info("Relayed 1:1 IQ {} {} -> {} over overlay (multi-hop)", iq.getType(), iq.getFrom(), to);
            rememberRelayedForBounce(iq);
            throw new PacketRejectedException("Relayed IQ over federation overlay to " + toDomain);
        }
    }

    /**
     * Relays a stanza addressed to a room on a MULTI-HOP peer's conference service over the overlay,
     * then rejects it to suppress native S2S.  This is what lets a local user reach a remote MUC room
     * directly across federation (join/leave presence, groupchat/PM, room IQ) without a per-room
     * mapping — including ad-hoc and members-only rooms that were never advertised.  A no-op when the
     * feature is disabled, when the target is not a remote room JID, or when the room's host server is
     * local or only a direct peer (native S2S already reaches it).  Replies (the room's presence and
     * IQ results back to the user) are user-addressed and ride the existing 1:1 relay home.
     */
    private void relayRemoteMuc(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = packet.getTo();
        if (to == null) return;
        if (toLocalConference) return;                                  // a LOCAL conference — handled elsewhere
        if (XMPPServer.getInstance().isLocal(to)) return;               // local target — not ours
        String toDomain = to.getDomain();
        if (manager.getRoutingTable().findNextHop(toDomain).isPresent()) return; // a known server (user JID) — 1:1 relay owns it

        // Map the target component domain (e.g. conference.<peer>) to its host server and relay ANYTHING
        // aimed at a multi-hop peer's component over the overlay — room-addressed (room@conf[/nick]) AND
        // service-addressed (the bare conf domain, e.g. a client's disco#info on the MUC service before
        // it joins) AND bare-room directed presence (no nick). Native S2S cannot reach a multi-hop peer's
        // subdomain in a chain topology, so an un-relayed stanza just stalls there (the client then sees
        // "No response from server" and never gets to send its join presence).
        String serverDomain = mucServerDomain(toDomain);
        if (serverDomain == null) return;
        if (!isMultiHopPeer(serverDomain)) return;                      // unknown, or a direct peer (native S2S)

        stampFrom(packet, session);
        boolean relayed;
        if (packet instanceof Presence pres) {
            relayed = manager.forwardDirectPresence(pres, serverDomain);
        } else if (packet instanceof Message msg) {
            if (msg.getType() == Message.Type.error) return;
            relayed = manager.forwardDirectMessage(msg, serverDomain);
        } else if (packet instanceof IQ iq) {
            relayed = manager.forwardDirectIq(iq, serverDomain);
        } else {
            return;
        }
        if (relayed) {
            Log.info("Relayed remote-MUC {} {} -> {} over overlay (host {} multi-hop)",
                     packet.getClass().getSimpleName(), packet.getFrom(), to, serverDomain);
            rememberRelayedForBounce(packet);
            throw new PacketRejectedException("Relayed remote MUC stanza over federation overlay to " + serverDomain);
        }
    }

    /**
     * Maps a remote conference domain to its host SERVER domain by stripping the service label
     * (e.g. {@code conference.2503-xmpp.example.net} → {@code 2503-xmpp.example.net}), or null if there is
     * no parent label.  The federation routing table is keyed on server domains; if the stripped
     * domain is not actually a known peer the caller's {@link #isMultiHopPeer} guard rejects it, so a
     * non-standard MUC subdomain simply falls back to native S2S (no behaviour change).
     */
    private String mucServerDomain(String confDomain) {
        if (confDomain == null) return null;
        int dot = confDomain.indexOf('.');
        if (dot <= 0 || dot >= confDomain.length() - 1) return null;
        return confDomain.substring(dot + 1);
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
