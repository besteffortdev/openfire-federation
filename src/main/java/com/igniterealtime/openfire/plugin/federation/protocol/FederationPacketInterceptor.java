package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.LogSafe;
import com.igniterealtime.openfire.plugin.federation.PresenceRelay;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.stanzaid.StanzaIDUtil;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;
import static com.igniterealtime.openfire.plugin.federation.protocol.InboundValidation.isLocalConferenceDomain;

/**
 * Openfire packet interceptor for the federation overlay. It forwards local MUC traffic to mapped
 * rooms, relays 1:1 stanzas for multi-hop peers over the overlay instead of native S2S, and applies
 * the room-access and leak-guard policies. Packets marked {@code fed-origin} are never forwarded again.
 */
public class FederationPacketInterceptor implements PacketInterceptor {

    private static final Logger Log = LoggerFactory.getLogger(FederationPacketInterceptor.class);

    private final FederationManager manager;

    /**
     * Relayed IQs and presences, keyed {@code id|from|to}, whose local not-allowed bounce from
     * Openfire's router is dropped once; the genuine reply arrives later over the overlay.
     */
    private final ConcurrentHashMap<String, Long> pendingBounces =
            new ConcurrentHashMap<>();
    private static final long BOUNCE_TTL_MS = 30_000L;

    public FederationPacketInterceptor(FederationManager manager) {
        this.manager = manager;
    }

    /** Records a just-relayed IQ or presence so its imminent local bounce can be dropped. */
    private void rememberRelayedForBounce(Packet packet) {
        if (!(packet instanceof IQ) && !(packet instanceof Presence)) return;
        JID from = packet.getFrom();
        JID to   = packet.getTo();
        if (from == null || to == null) return;
        long now = System.currentTimeMillis();
        if (pendingBounces.size() > 256) {
            pendingBounces.values().removeIf(exp -> exp < now);
        }
        pendingBounces.put(bounceKey(packet.getID(), from.toString(), to.toString()), now + BOUNCE_TTL_MS);
    }

    /** True, once, if {@code packet} is the local bounce of a stanza we just relayed. */
    private boolean consumeSpuriousBounce(Packet packet) {
        boolean isError = (packet instanceof IQ iq && iq.getType() == IQ.Type.error)
                       || (packet instanceof Presence pr && pr.getType() == Presence.Type.error);
        if (!isError) return false;
        JID from = packet.getFrom();
        JID to   = packet.getTo();
        if (from == null || to == null) return false;
        // The bounce swaps from and to but keeps the id.
        Long exp = pendingBounces.remove(bounceKey(packet.getID(), to.toString(), from.toString()));
        return exp != null && exp >= System.currentTimeMillis();
    }

    /** The bounce key; a missing id is keyed as {@code "-"}, since id-less presences are common. */
    private String bounceKey(String id, String from, String to) {
        return (id == null ? "-" : id) + '|' + from + '|' + to;
    }

    /** Set during {@link #runArchiveCapturePass}, so this interceptor skips its own synthetic pass. */
    private static final ThreadLocal<Boolean> IN_CAPTURE_PASS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Set while {@link #deliverOverlayReply} routes a genuine overlay reply, which would otherwise
     * match the bounce key and be dropped in place of the fake bounce.
     */
    private static final ThreadLocal<Boolean> IN_OVERLAY_REPLY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Routes a result or error IQ that arrived over the overlay to its local requester, bypassing this interceptor. */
    public static void deliverOverlayReply(IQ reply) {
        IN_OVERLAY_REPLY.set(Boolean.TRUE);
        try {
            XMPPServer.getInstance().getPacketRouter().route(reply);
        } finally {
            IN_OVERLAY_REPLY.set(Boolean.FALSE);
        }
    }

    /**
     * Runs the post-processing interceptor chain over a 1:1 chat message that federation delivered
     * or relayed off the normal routing path, so message archivers (and MAM) record it.
     */
    public static void runArchiveCapturePass(Packet packet, Session session) {
        if (!(packet instanceof Message msg)) return;
        Message.Type type = msg.getType();
        if (type != Message.Type.chat && type != Message.Type.normal) return;
        if (msg.getBody() == null || msg.getBody().isEmpty()) return;
        IN_CAPTURE_PASS.set(Boolean.TRUE);
        try {
            InterceptorManager.getInstance().invokeInterceptors(packet, session, true, true);
        } catch (PacketRejectedException e) {
            // The stanza is already delivered, so there is nothing left for a rejection to prevent.
            Log.debug("Archive capture pass rejected for {} (ignored — already delivered)", packet.getTo(), e);
        } catch (RuntimeException e) {
            Log.warn("Archive capture pass failed for {}; the message was delivered but may not be archived",
                     packet.getTo(), e);
        } finally {
            IN_CAPTURE_PASS.remove();
        }
    }

    @Override
    public void interceptPacket(Packet packet, Session session,
                                boolean incoming, boolean processed)
            throws PacketRejectedException {

        if (IN_CAPTURE_PASS.get()) return;
        if (IN_OVERLAY_REPLY.get()) return;

        if (FederationStanzaFactory.isMarkedAsForwarded(packet)) return;

        // Ahead of the null-to check: a PEP publish is addressed to the user's own PEP service and has no "to".
        if (processed && incoming && packet instanceof IQ pepIq) {
            relayLocalPepPublish(pepIq);
        }

        if (packet.getTo() == null) return;

        if (!incoming && packet instanceof Message out) relayModeration(out);

        if (processed) {
            // Relays reject the stanza, which only takes effect before processing, so they run in the pre-pass only.
            if (incoming) {
                if (packet instanceof Message msg)       handleMessage(msg);
                else if (packet instanceof Presence pres) handlePresence(pres);
            }
            return;
        }

        if (consumeSpuriousBounce(packet)) {
            throw new PacketRejectedException("Suppressed spurious not-allowed bounce for an overlay-relayed stanza");
        }

        handleOverlayBounce(packet);

        if (incoming) answerSharedContact(packet);

        rewriteInboundFileShare(packet);

        // Computed once: this runs on every stanza, and the lookup scans the MUC services.
        boolean toLocalConference = isLocalConferenceDomain(packet.getTo().getDomain());

        enforceRoomTraversalPolicy(packet, toLocalConference);

        relayDirectMessage(packet, session, toLocalConference);

        relayDirectPresence(packet, session, toLocalConference);

        relayDirectIq(packet, session, toLocalConference);

        relayRemoteMuc(packet, session, toLocalConference);

        annotateDirectPeerFileShare(packet, session, toLocalConference);

        // No relay claimed the stanza: refuse one that native S2S cannot deliver rather than lose it silently.
        overlayLeakGuard(packet);
    }

    /** Passes a local user's PEP publish to one of the mirrored nodes on to the manager for relay. */
    private void relayLocalPepPublish(IQ iq) {
        if (iq.getType() != IQ.Type.set) return;
        JID from = iq.getFrom();
        if (from == null || from.getNode() == null) return;
        if (!XMPPServer.getInstance().isLocal(from)) return;
        JID to = iq.getTo();
        if (to != null && !to.asBareJID().equals(from.asBareJID())) return;

        Element pubsub = iq.getChildElement();
        if (pubsub == null || !"http://jabber.org/protocol/pubsub".equals(pubsub.getNamespaceURI())) return;
        Element publish = pubsub.element("publish");
        if (publish == null) return;
        String node = publish.attributeValue("node");
        if (node == null) return;
        Element item = publish.element("item");
        if (item == null) return;
        String itemId = item.attributeValue("id");
        List<Element> kids = item.elements();
        Element payload = kids.isEmpty() ? null : kids.get(0);

        manager.getPresenceRelay().relayLocalPepPublish(from.asBareJID(), node, itemId, payload);
    }

    /** Forwards a groupchat the local room accepted to each of the room's mappings. */
    private void handleMessage(Message msg) {
        if (msg.getType() != Message.Type.groupchat) return;

        if (!isLocalConferenceDomain(msg.getTo().getDomain())) return;

        String roomJid = roomJidFromTo(msg.getTo());
        if (roomJid == null) return;

        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(roomJid);
        if (mappings.isEmpty()) return;

        if (!acceptedByRoom(msg, roomJid)) return;

        var relay = manager.getFileRelay();
        boolean filesOn = manager.getRoomManager().isFilesEnabled(roomJid);

        // File federation is off for this room: peers get a notice in place of the file.
        if (!filesOn && relay != null && relay.isLocalUploadShare(msg)) {
            Message notice = new Message(msg.getElement().createCopy());
            relay.replaceWithSenderBlockedNotice(notice.getElement());
            for (RoomMapping mapping : mappings) forwardToMapped(notice, mapping, null);
            return;
        }

        Element fileAnn = (filesOn && relay != null)
                ? relay.annotationForOutbound(msg) : null;
        for (RoomMapping mapping : mappings) {
            forwardToMapped(msg, mapping, fileAnn);
        }
    }

    /**
     * Whether the local room accepted {@code msg}, as opposed to refusing a sender who is not an
     * occupant or has no voice. Openfire raises the message event only after broadcasting a message.
     */
    private boolean acceptedByRoom(Message msg, String roomJid) {
        MUCRoom room = manager.findLocalMucRoom(roomJid);
        if (room == null || msg.getFrom() == null) return false;
        boolean accepted;
        if (room.getRoomHistory().isSubjectChangeRequest(msg)) {
            accepted = room.getOccupantByFullJID(msg.getFrom()) != null
                    && Objects.equals(room.getSubject(), msg.getSubject());
        } else {
            var ids = manager.getRoomMessageIds();
            String sid = StanzaIDUtil.findFirstUniqueAndStableStanzaID(msg, roomJid);
            accepted = ids.consumeAccepted(roomJid, RoomMessageIds.keyOf(sid, msg.getID(), msg.getFrom().toString()));
            // Claimed now, so a peer cannot later hand the same id back on a different message.
            if (accepted && sid != null) {
                ids.claim(roomJid, sid, new RoomMessageIds.Origin(roomJid, sid, msg.getFrom().toBareJID()));
            }
        }
        if (!accepted) {
            Log.info("Not forwarding a groupchat from {} to {}: the room did not accept it", msg.getFrom(), roomJid);
        }
        return accepted;
    }

    /**
     * Relays a mapped room's XEP-0425 moderation to its peers when the removed message was written by
     * one of our users in this room. A replay to a late joiner carries a delay and is not relayed.
     */
    private void relayModeration(Message out) {
        if (out.getType() != Message.Type.groupchat) return;
        JID from = out.getFrom();
        if (from == null || from.getNode() == null || from.getResource() != null) return;
        Element retract = RoomModeration.moderatedRetract(out.getElement());
        if (retract == null || RoomModeration.isDelayed(out.getElement())) return;
        if (!isLocalConferenceDomain(from.getDomain())) return;

        String roomJid = from.toBareJID();
        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(roomJid);
        if (mappings.isEmpty()) return;

        String targetId = retract.attributeValue("id");
        var ids = manager.getRoomMessageIds();
        // Each occupant gets a copy of the announcement; only the first is relayed.
        if (!ids.markModerated(roomJid, targetId)) return;
        RoomMessageIds.Origin origin = ids.originOf(roomJid, targetId);
        if (origin == null || !roomJid.equals(origin.room())) {
            Log.info("Moderation of {} in {} applies on this server only: {}", targetId, roomJid,
                     origin == null ? "not a message this room forwarded (or too old to be remembered)"
                                    : "written by " + origin.author() + " on another server");
            return;
        }

        String moderator = moderatorOf(manager.findLocalMucRoom(roomJid), retract);
        Message payload = new Message(RoomModeration.announcement(roomJid, out.getID(), targetId,
                moderator == null ? null : roomJid + "/" + moderator, null, RoomModeration.reasonOf(retract)));
        for (RoomMapping mapping : mappings) {
            forwardToMapped(payload, mapping, null);
        }
        Log.info("Relayed the moderation of {}'s message {} in {} to {} mapped room(s)",
                 origin.author(), targetId, roomJid, mappings.size());
    }

    /** The bare JID of the local moderator a moderation's {@code by} names, or null. */
    private static String moderatorOf(MUCRoom room, Element retract) {
        Element moderated = retract.element(
                QName.get("moderated", RoomModeration.NS_MODERATE));
        String by = moderated == null ? null : moderated.attributeValue("by");
        if (by == null || room == null) return null;
        try {
            JID byJid = new JID(by);
            if (byJid.toBareJID().equals(room.getJID().toBareJID())) {
                if (byJid.getResource() == null) return null;
                var occupants = room.getOccupantsByNickname(byJid.getResource());
                if (occupants.isEmpty()) return null;
                JID real = occupants.get(0).getUserAddress();
                return XMPPServer.getInstance().isLocal(real) ? real.toBareJID() : null;
            }
            return XMPPServer.getInstance().isLocal(byJid) ? byJid.toBareJID() : null;
        } catch (IllegalArgumentException | UserNotFoundException ignored) {
            // A malformed "by", or a moderator who already left: the relayed moderation names nobody.
            return null;
        }
    }

    /** Forwards a local join, leave or status change in a mapped room to each mapping. */
    private void handlePresence(Presence pres) {
        if (!isLocalConferenceDomain(pres.getTo().getDomain())) return;
        if (pres.getTo().getResource() == null) return;

        String roomJid = roomJidFromTo(pres.getTo());
        if (roomJid == null) return;

        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(roomJid);
        if (mappings.isEmpty()) return;

        // A join counts only if the room admitted the sender; a leave only ever removes the sender's own nick.
        if (pres.getType() == null) {
            MUCRoom room = manager.findLocalMucRoom(roomJid);
            if (room == null || room.getOccupantByFullJID(pres.getFrom()) == null) {
                Log.info("Not forwarding presence from {} to {}: the room did not admit it", pres.getFrom(), roomJid);
                return;
            }
        }
        for (RoomMapping mapping : mappings) {
            forwardToMapped(pres, mapping, null);
        }
    }

    /** Sends a copy of a local room stanza, plus any file annotation, to one mapped room. */
    private void forwardToMapped(Packet packet, RoomMapping mapping, Element fileAnn) {
        String localDomain  = localDomain();
        String remoteDomain = mapping.remoteDomain();
        String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);
        boolean sent = FederationSender.send("muc-forward", remoteDomain, () -> {
            Packet copy = copyPacket(packet);
            if (fileAnn != null) copy.getElement().add(fileAnn.createCopy());
            return FederationStanzaFactory.mucForward(
                    nextHop, remoteDomain, mapping.remoteRoomJid(), localDomain, localDomain, copy);
        });
        if (sent) {
            Log.debug("Forwarded {} to {} via {}", packet.getClass().getSimpleName(), mapping.remoteRoomJid(), nextHop);
        }
    }

    /**
     * Rejects remote access to a local room when traversal is disabled. Across an untrusted edge only
     * rooms shared with that side are reachable, whatever the toggle says.
     */
    private void enforceRoomTraversalPolicy(Packet packet, boolean toLocalConference)
            throws PacketRejectedException {
        if (!toLocalConference) return;

        JID from = packet.getFrom();
        if (from == null) return;
        if (!XMPPServer.getInstance().isRemote(from)) return;

        String edge = untrustedEdgeFor(from.getDomain());
        if (edge != null) {
            if (untrustedTraversalAllowed(packet, from.getDomain(), edge)) return;
            Log.warn("SECURITY: blocking room access from {} to {} — it comes through untrusted peer {} "
                   + "and that room is not shared with it", from, packet.getTo(), edge);
            throw new PacketRejectedException("This room is not shared with your server.");
        }

        if (FederationProperties.ALLOW_REMOTE_ROOM_TRAVERSAL.getValue()) return;

        Log.info("Blocking direct S2S MUC access from {} to {} — remote-room traversal disabled",
                 from, packet.getTo());
        throw new PacketRejectedException(
                "Direct cross-server access to this room is disabled; use federation.");
    }

    /** The untrusted peer a remote sender reaches us through, or null when it comes through the trusted mesh. */
    private String untrustedEdgeFor(String senderDomain) {
        var registry = manager.getPeerRegistry();
        if (registry.isUntrusted(senderDomain)) return senderDomain;
        String hop = manager.getRoutingTable().findNextHop(senderDomain).orElse(null);
        return hop != null && registry.isUntrusted(hop) ? hop : null;
    }

    /**
     * Whether a stanza from across an untrusted edge may reach a local MUC address: a room shared with
     * the sender's server or the edge, or a disco#info query on the service itself.
     */
    private boolean untrustedTraversalAllowed(Packet packet, String senderDomain, String edge) {
        JID to = packet.getTo();
        if (to.getNode() == null) {
            return packet instanceof IQ iq && iq.getType() == IQ.Type.get
                && iq.getChildElement() != null
                && "http://jabber.org/protocol/disco#info".equals(iq.getChildElement().getNamespaceURI());
        }
        String room = to.toBareJID();
        return manager.roomSharedWith(room, senderDomain) || manager.roomSharedWith(room, edge);
    }

    /**
     * Relays a local 1:1 message for a user on a multi-hop peer over the overlay, then rejects it so
     * Openfire does not also try native S2S. Direct peers keep native S2S.
     */
    private void relayDirectMessage(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof Message msg)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        // Errors are never relayed, so a delivery failure cannot bounce in a loop.
        Message.Type type = msg.getType();
        if (type == Message.Type.error) return;

        JID to = msg.getTo();
        if (to == null || to.getNode() == null) return;
        if (toLocalConference) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        String toDomain = to.getDomain();
        if (!manager.isMultiHopDomain(toDomain)) return;

        // A groupchat here is a local room's broadcast to a remote occupant; mapped rooms use muc-forward instead.
        if (type == Message.Type.groupchat) {
            JID from = msg.getFrom();
            if (from == null || from.getNode() == null || !isLocalConferenceDomain(from.getDomain())) return;
            String roomJid = from.getNode() + "@" + from.getDomain();
            if (!manager.getRoomManager().getMappingsForLocal(roomJid).isEmpty()) return;
        }

        stampFrom(msg, session);
        if (manager.getFileRelay() != null) {
            manager.getFileRelay().annotateOutboundInPlace(msg);
        }
        if (manager.forwardDirectMessage(msg)) {
            Log.info("Relayed message {} -> {} over overlay (multi-hop)", msg.getFrom(), to);
            // The rejection skips the post-processing pass where archivers record a 1:1, so run it here.
            runArchiveCapturePass(msg, session);
            throw new PacketRejectedException("Relayed over federation overlay to " + toDomain);
        }
    }

    /**
     * Annotates a local 1:1 file share to a user on a direct peer. The message travels over native
     * S2S, and the annotation lets the peer pull the file over the overlay.
     */
    private void annotateDirectPeerFileShare(Packet packet, Session session, boolean toLocalConference) {
        if (!(packet instanceof Message msg)) return;
        if (manager.getFileRelay() == null) return;
        Message.Type type = msg.getType();
        if (type != Message.Type.chat && type != Message.Type.normal) return;
        JID to = msg.getTo();
        if (to == null || to.getNode() == null) return;
        if (toLocalConference) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        String toDomain = to.getDomain();
        boolean directPeer = manager.getRoutingTable().findNextHop(toDomain)
                                    .map(nextHop -> nextHop.equals(toDomain))
                                    .orElse(false);
        if (!directPeer) return;
        stampFrom(msg, session);
        JID from = msg.getFrom();
        if (from == null || !XMPPServer.getInstance().isLocal(from)) return;
        manager.getFileRelay().annotateOutboundInPlace(msg);
    }

    /**
     * Rewrites a file share annotated by another server that is about to reach a local user, so its
     * URL points at our download endpoint. A copy bound for a remote user keeps its annotation.
     */
    private void rewriteInboundFileShare(Packet packet) {
        if (!(packet instanceof Message msg)) return;
        if (manager.getFileRelay() == null) return;
        JID to = msg.getTo();
        if (to == null || to.getNode() == null) return;
        if (!XMPPServer.getInstance().isLocal(to)) return;
        if (manager.getFileRelay().annotationOf(msg.getElement()) == null) return;
        String fromDomain = msg.getFrom() != null ? msg.getFrom().getDomain() : null;
        manager.getFileRelay().rewriteInPlace(msg, fromDomain);
    }

    /**
     * Answers, for a shared local user, a probe, subscription request or PEP fetch sent over native
     * S2S by a contact on a server the user is shared with; Openfire itself would refuse them.
     */
    private void answerSharedContact(Packet packet) throws PacketRejectedException {
        JID from = packet.getFrom();
        if (from == null || from.getNode() == null || XMPPServer.getInstance().isLocal(from)) return;
        if (packet instanceof Presence pres) {
            if (manager.getContactLists().handleInboundSubscription(pres)) {
                rememberRelayedForBounce(pres);
                throw new PacketRejectedException("Answered " + pres.getType() + " from shared contact "
                        + from + " for " + pres.getTo());
            }
        } else if (packet instanceof IQ iq && iq.getType() == IQ.Type.get
                && manager.getDirectTraffic().answerSharedContactPepFetch(iq)) {
            rememberRelayedForBounce(iq);
            throw new PacketRejectedException("Answered PEP fetch from shared contact " + from + " for " + iq.getTo());
        }
    }

    /**
     * Relays a local directed presence or subscription for a user on a multi-hop peer over the
     * overlay, then rejects it; also applies the subscription side effects Openfire would have.
     */
    private void relayDirectPresence(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof Presence pres)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = pres.getTo();
        if (to == null || to.getNode() == null) return;
        if (toLocalConference) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        String toDomain = to.getDomain();
        if (!manager.isMultiHopDomain(toDomain)) return;

        stampFrom(pres, session);
        if (manager.forwardDirectPresence(pres)) {
            Log.info("Relayed 1:1 presence {} {} -> {} over overlay (multi-hop)",
                     pres.getType() == null ? "available" : pres.getType(), pres.getFrom(), to);
            PresenceRelay presenceRelay = manager.getPresenceRelay();
            presenceRelay.syncLocalRosterOnSubscriptionRelay(pres);
            if (pres.getType() == Presence.Type.subscribed) {
                presenceRelay.addRemoteSubscriber(pres.getFrom(), to);
                presenceRelay.pushUserPresenceTo(to, pres.getFrom());
                presenceRelay.pushLocalPepItemsTo(to, pres.getFrom());
            } else if (pres.getType() == Presence.Type.unsubscribed) {
                presenceRelay.removeRemoteSubscriber(pres.getFrom(), to);
            }
            rememberRelayedForBounce(pres);
            throw new PacketRejectedException("Relayed presence over federation overlay to " + toDomain);
        }
    }

    /**
     * Relays a local IQ, user- or server-addressed, to a multi-hop peer over the overlay, then rejects
     * it. The plugin's own protocol IQs always use native S2S, so reconnect probes test the real link.
     */
    private void relayDirectIq(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!(packet instanceof IQ iq)) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (isFederationProtocolStanza(iq)) return;

        JID to = iq.getTo();
        if (to == null) return;
        if (toLocalConference) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        String toDomain = to.getDomain();
        if (!manager.isMultiHopDomain(toDomain)) return;

        stampFrom(iq, session);
        if (manager.forwardDirectIq(iq)) {
            Log.info("Relayed 1:1 IQ {} {} -> {} over overlay (multi-hop)", iq.getType(), iq.getFrom(), to);
            rememberRelayedForBounce(iq);
            throw new PacketRejectedException("Relayed IQ over federation overlay to " + toDomain);
        }
    }

    /**
     * Relays a stanza for a room or MUC service on a multi-hop peer over the overlay, then rejects it,
     * so users can reach remote rooms that were never mapped. Replies come back on the 1:1 relay.
     */
    private void relayRemoteMuc(Packet packet, Session session, boolean toLocalConference)
            throws PacketRejectedException {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;

        JID to = packet.getTo();
        if (to == null) return;
        if (toLocalConference) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        String toDomain = to.getDomain();
        // A routable domain is a server, so its users go through the 1:1 relays.
        if (manager.getRoutingTable().findNextHop(toDomain).isPresent()) return;

        String serverDomain = mucServerDomain(toDomain);
        if (serverDomain == null) return;
        if (!manager.isMultiHopDomain(serverDomain)) return;

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

    /** The host server of a conference domain ({@code conference.X → X}), or null without a parent label. */
    private static String mucServerDomain(String confDomain) {
        if (confDomain == null) return null;
        int dot = confDomain.indexOf('.');
        if (dot <= 0 || dot >= confDomain.length() - 1) return null;
        return confDomain.substring(dot + 1);
    }

    /** Fills in a relayed stanza's missing {@code from} from the session; clients often omit it. */
    private static void stampFrom(Packet packet, Session session) {
        if (packet.getFrom() == null && session != null && session.getAddress() != null) {
            packet.setFrom(session.getAddress());
        }
    }

    /** Whether {@code packet} is one of the plugin's own protocol IQs, which use native S2S by design. */
    private static boolean isFederationProtocolStanza(Packet packet) {
        return packet.getElement()
                     .element(QName.get(FederationStanzaFactory.ELEMENT, FederationStanzaFactory.NS)) != null;
    }

    /** The overlay server a domain belongs to, itself or its conference host, or null when it has none. */
    private String overlayDomainOf(String domain) {
        if (domain == null) return null;
        if (isKnownOverlayDomain(domain)) return domain;
        String host = mucServerDomain(domain);
        return (host != null && isKnownOverlayDomain(host)) ? host : null;
    }

    /** Whether a domain ever had an overlay route or is a configured peer. */
    private boolean isKnownOverlayDomain(String domain) {
        return manager.getRoutingTable().wasEverRoutable(domain)
            || manager.getPeerRegistry().contains(domain);
    }

    /**
     * Rejects a stanza no relay claimed that is addressed to an overlay server native S2S cannot reach,
     * and bounces an error to a local message sender. A configured peer without a route keeps native S2S.
     */
    private void overlayLeakGuard(Packet packet) throws PacketRejectedException {
        JID to = packet.getTo();
        if (to == null) return;
        if (XMPPServer.getInstance().isLocal(to)) return;
        if (isFederationProtocolStanza(packet)) return;
        String overlayDomain = overlayDomainOf(to.getDomain());
        if (overlayDomain == null) return;
        Optional<String> hop = manager.getRoutingTable().findNextHop(overlayDomain);
        if (hop.isPresent() && hop.get().equals(overlayDomain)) return;
        if (hop.isEmpty() && manager.getPeerRegistry().contains(overlayDomain)) return;
        Log.warn("FED-LEAK: {} {} -> {} had no relay path — rejected to prevent silent native-S2S loss "
               + "(overlay dest {}, nextHop={})", packet.getClass().getSimpleName(), packet.getFrom(), to,
                 overlayDomain, hop.orElse("NONE"));
        if (Log.isDebugEnabled()) Log.debug("FED-LEAK stanza: {}", LogSafe.text(packet.toXML()));
        if (packet instanceof Message msg && msg.getType() != Message.Type.error
                && msg.getFrom() != null && XMPPServer.getInstance().isLocal(msg.getFrom())) {
            Message err = new Message();
            err.setID(msg.getID());
            err.setTo(msg.getFrom());
            err.setFrom(msg.getTo());
            err.setType(Message.Type.error);
            err.setError(PacketError.Condition.service_unavailable);
            FederationStanzaFactory.markAsForwarded(err);
            FederationSender.send("leak-guard error", msg.getFrom().toString(), err);
        }
        throw new PacketRejectedException("Rejected stanza to overlay domain " + overlayDomain
                + " — no direct link; native S2S would silently drop it");
    }

    /**
     * Logs an error coming back from an overlay server, which usually means a native S2S send
     * failed. A presence bounce for a server the overlay reaches is dropped as noise for the client.
     */
    private void handleOverlayBounce(Packet packet) throws PacketRejectedException {
        boolean isError = (packet instanceof IQ iq && iq.getType() == IQ.Type.error)
                || (packet instanceof Message m && m.getType() == Message.Type.error)
                || (packet instanceof Presence p && p.getType() == Presence.Type.error);
        if (!isError || packet.getFrom() == null) return;
        if (isFederationProtocolStanza(packet)) return;
        String overlayDomain = overlayDomainOf(packet.getFrom().getDomain());
        if (overlayDomain == null) return;
        Element err = packet.getElement().element("error");
        String condition = "?";
        if (err != null) {
            for (Element child : err.elements()) {
                if (!"text".equals(child.getName())) { condition = child.getName(); break; }
            }
        }
        Log.warn("FED-LEAK-BOUNCE: error '{}' from overlay domain {} back to {} — native S2S delivery failed",
                 LogSafe.text(condition), overlayDomain, packet.getTo());
        if (Log.isDebugEnabled()) Log.debug("FED-LEAK-BOUNCE stanza: {}", LogSafe.text(packet.toXML()));
        if (packet instanceof Presence
                && ("remote-server-not-found".equals(condition) || "remote-server-timeout".equals(condition))
                && manager.getRoutingTable().isReachable(overlayDomain)) {
            throw new PacketRejectedException("Swallowed native-S2S failure bounce for overlay-reachable domain "
                    + overlayDomain);
        }
    }

    private static String roomJidFromTo(JID to) {
        if (to == null || to.getNode() == null) return null;
        return to.getNode() + "@" + to.getDomain();
    }

    private static Packet copyPacket(Packet p) {
        if (p instanceof Message m)   return new Message(m.getElement().createCopy());
        if (p instanceof Presence pr) return new Presence(pr.getElement().createCopy());
        return p;
    }
}
