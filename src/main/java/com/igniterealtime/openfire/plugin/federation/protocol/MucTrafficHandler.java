package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.LogSafe;
import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.muc.MUCEventDispatcher;
import org.jivesoftware.openfire.muc.MUCOccupant;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.jivesoftware.util.XMPPDateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.Presence;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_DESTINATION;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_HUBS;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_SRC;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_TARGET_ROOM;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_VIA;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.appendToTrail;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.viaContains;

/**
 * Handles relayed room traffic ({@code muc-forward}, docs/protocol/05): delivers it into the local
 * room as a virtual occupant's message or presence and fans it out to the room's other mappings, or
 * relays it toward the room's server. Ring and mesh topologies are stopped by the hub trail and the
 * duplicate check.
 */
public final class MucTrafficHandler {

    private static final Logger Log = LoggerFactory.getLogger(MucTrafficHandler.class);

    static final String NS_STANZA_ID   = "urn:xmpp:sid:0";
    static final String NS_OCCUPANT_ID = "urn:xmpp:occupant-id:0";
    /** A peer-supplied stanza-id that may be reused as is; long enough not to collide with MAM's numeric ids. */
    private static final Pattern REUSABLE_STANZA_ID = Pattern.compile("[A-Za-z0-9._:-]{16,128}");

    /** A message for a room that had no occupants, held briefly so a reconnecting client still gets it. */
    private record PendingDelivery(Element deliverEl, String virtualFrom, long queuedAt) { }

    private static final long PENDING_DELIVERY_TTL_MS = 60_000;
    private static final int  PENDING_DELIVERY_MAX_PER_ROOM = 20;

    /** A moderation relayed from another server and applied here, kept to replay to late joiners. */
    private record StoredModeration(String targetId, Element announcement, long at) { }

    private static final int MODERATIONS_KEPT_PER_ROOM = 200;

    /** What a forwarded packet turned out to be for the local room it was delivered into. */
    private enum Arrival {
        /** New here, including when delivery failed, so a local fault never stops the other spokes. */
        NEW,
        /** A second copy of something the room already has, which reached it by another path. */
        DUPLICATE,
        /** One of our own users' packets coming back round. */
        OWN
    }

    private final FederationManager manager;
    private final InboundValidation validation;
    private final ConcurrentHashMap<String, Deque<PendingDelivery>> pendingRoomDeliveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<StoredModeration>> federatedModerations = new ConcurrentHashMap<>();

    MucTrafficHandler(FederationManager manager, InboundValidation validation) {
        this.manager = manager;
        this.validation = validation;
    }

    /**
     * Handles a muc-forward from {@code fromDomain}. After the loop, origin and exposure checks it is
     * delivered here and fanned out when the room is ours, and relayed otherwise.
     */
    void handle(String fromDomain, Element el) {
        String finalDest  = el.attributeValue(ATTR_DESTINATION);
        String targetRoom = el.attributeValue(ATTR_TARGET_ROOM);
        String via        = el.attributeValue(ATTR_VIA, "");
        // Older peers omit src; the immediate sender is the safe fallback.
        String src        = el.attributeValue(ATTR_SRC, fromDomain);
        String hubs       = el.attributeValue(ATTR_HUBS, "");

        if (viaContains(via, localDomain())) {
            Log.warn("muc-forward loop detected (via={}), dropping", via);
            return;
        }
        if (FederationStanzaFactory.trailLength(hubs) > FederationStanzaFactory.MAX_HUBS) {
            Log.warn("muc-forward from {} names more than {} hubs, dropping", fromDomain,
                     FederationStanzaFactory.MAX_HUBS);
            return;
        }
        Element payloadEl = firstChild(el);
        if (payloadEl == null || !isRoomStanza(payloadEl)) {
            Log.warn("muc-forward from {} carries no message or presence, dropping", fromDomain);
            return;
        }
        // Local claims are allowed here: a trusted hub can echo our own users back, and delivery drops those.
        if (!validation.payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), "muc-forward", false,
                                        el.attributeValue(ATTR_SRC))) return;
        if (!validation.claimedOriginOk(fromDomain, src, "muc-forward")) return;
        if (!validation.exposureOk(fromDomain, finalDest, "muc-forward")) return;

        boolean forUs = InboundValidation.isForUs(finalDest);
        Log.debug("muc-forward: from={} finalDest={} targetRoom={} via=[{}] payload={}[type={},from={}] -> {}",
                  fromDomain, finalDest, targetRoom, via, payloadEl.getName(),
                  payloadEl.attributeValue("type"), payloadEl.attributeValue("from"),
                  forUs ? "LOCAL+fanOut" : "RELAY");
        if (forUs) {
            deliverHere(fromDomain, payloadEl, targetRoom, via, src, hubs);
        } else {
            relay(fromDomain, payloadEl, finalDest, targetRoom, via, src, hubs);
        }
    }

    /** Delivers into one of our rooms, if it is federated and mapped to {@code src}, then fans the payload out. */
    private void deliverHere(String fromDomain, Element payloadEl, String targetRoom, String via,
                             String src, String hubs) {
        if (!isFederatedLocalRoom(targetRoom)) {
            Log.warn("SECURITY: dropping muc-forward from {} into non-federated local room {} "
                   + "(type={}, from={})", fromDomain, targetRoom,
                     payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
            return;
        }
        if (manager.getRoomManager().getMappingsForLocal(targetRoom).isEmpty()) {
            Log.warn("SECURITY: dropping muc-forward from {} into federated but UNMAPPED local room {} "
                   + "(type={}, from={})", fromDomain, targetRoom,
                     payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
            return;
        }
        if (!mappedToEntry(targetRoom, src, fromDomain, payloadEl)) return;
        if (viaContains(hubs, localDomain())) {
            Log.debug("muc-forward: already a hub for this {} (hubs={}), dropping", payloadEl.getName(), hubs);
            manager.getRedundantArrivals().record(targetRoom, src);
            return;
        }
        Arrival arrival = deliver(payloadEl, targetRoom, fromDomain, src);
        if (arrival == Arrival.NEW) {
            fanOut(fromDomain, payloadEl, targetRoom, via, hubs);
        } else if (arrival == Arrival.DUPLICATE) {
            manager.getRedundantArrivals().record(targetRoom, src);
        }
    }

    /** Relays a muc-forward toward its room's server, after delivering it here too if we map that room ourselves. */
    private void relay(String fromDomain, Element payloadEl, String finalDest, String targetRoom,
                       String via, String src, String hubs) {
        String newVia = appendToTrail(via, localDomain());
        boolean hubHere = targetRoom != null
                && deliverAtRelay(fromDomain, payloadEl, finalDest, targetRoom, via, src, hubs);
        String relayHubs = hubHere ? appendToTrail(hubs, localDomain()) : hubs;

        String nextHop = manager.getRoutingTable().findNextHop(finalDest).orElse(null);
        if (nextHop == null) {
            Log.warn("muc-forward: no route to {}, dropping", finalDest);
            return;
        }
        FederationSender.send("muc-forward", finalDest, () -> FederationStanzaFactory.mucForward(
                nextHop, finalDest, targetRoom, newVia, src, relayHubs, roomStanza(payloadEl)));
    }

    /**
     * Delivers into our own room when this relay maps the target room, and fans out as a hub would.
     * True when the payload was new here, so the relayed copy must name us as a hub.
     */
    private boolean deliverAtRelay(String fromDomain, Element payloadEl, String finalDest, String targetRoom,
                                   String via, String src, String hubs) {
        RoomMapping own = manager.getRoomManager().getMappingForRemote(targetRoom);
        if (own == null || !own.isActive() || !own.remoteDomain().equals(finalDest)
                || !validation.untrustedAllowsServer(fromDomain, localDomain())
                || !isFederatedLocalRoom(own.localRoomJid())) {
            return false;
        }
        Arrival arrival = viaContains(hubs, localDomain())
                ? Arrival.DUPLICATE
                : deliver(payloadEl, own.localRoomJid(), fromDomain, src);
        if (arrival == Arrival.NEW) {
            // finalDest gets it from the relay itself, so it is listed as a hub to avoid a second copy.
            fanOut(fromDomain, payloadEl, own.localRoomJid(), via, appendToTrail(hubs, finalDest));
            return true;
        }
        if (arrival == Arrival.DUPLICATE) {
            manager.getRedundantArrivals().record(own.localRoomJid(), finalDest);
        }
        return false;
    }

    /** Whether {@code targetRoom} has an ACTIVE mapping whose far end is {@code src}, the claimed entry server. */
    private boolean mappedToEntry(String targetRoom, String src, String fromDomain, Element payloadEl) {
        boolean mapped = manager.getRoomManager().getMappingsForLocal(targetRoom).stream()
                                .anyMatch(m -> m.remoteDomain().equals(src));
        if (!mapped) {
            Log.warn("SECURITY: dropping muc-forward from {} into local room {} — it enters through {}, "
                   + "which has no active mapping on that room (type={}, from={})", fromDomain, targetRoom,
                     src, payloadEl.attributeValue("type"), payloadEl.attributeValue("from"));
        }
        return mapped;
    }

    /**
     * Delivers a forwarded message or presence into a local room. A failure is logged and counts as
     * new, so a fault here never stops the room's other spokes getting the payload.
     */
    private Arrival deliver(Element payloadEl, String targetRoom, String fromDomain, String src) {
        try {
            if ("message".equals(payloadEl.getName())) {
                return injectMessage(payloadEl, targetRoom, fromDomain, src);
            }
            injectPresence(payloadEl, targetRoom, fromDomain, src);
        } catch (RuntimeException e) {
            Log.warn("Could not deliver a relayed {} into {}", payloadEl.getName(), targetRoom, e);
        }
        return Arrival.NEW;
    }

    /**
     * Relays a payload the room took as new to its other mappings, skipping the sender, servers in
     * the via trail, the payload's home server and servers already in the hub trail.
     */
    private void fanOut(String fromDomain, Element payloadEl, String targetRoom, String viaTrail, String hubs) {
        List<RoomMapping> mappings = manager.getRoomManager().getMappingsForLocal(targetRoom);
        if (mappings.size() <= 1) {
            Log.debug("fanOut: skip — only {} mapping(s) for {} (from={})", mappings.size(), targetRoom, fromDomain);
            return;
        }
        String localDomain = localDomain();
        // The via trail restarts here so relays on the next leg can forward it; the hub trail never restarts.
        String newHubs = appendToTrail(hubs, localDomain);
        Set<String> viaServers = viaTrail.isEmpty() ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(viaTrail.split(",")));
        // With the via trail restarted, nothing else stops the payload looping back to its user's server.
        String payloadHome = originOf(virtualNick(payloadEl.attributeValue("from")), "");

        Log.debug("fanOut: enter from={} targetRoom={} via=[{}] payloadFrom={} payloadHome={} mappings={}",
                  fromDomain, targetRoom, viaTrail, payloadEl.attributeValue("from"), payloadHome,
                  mappings.stream().map(RoomMapping::remoteDomain).toList());

        for (RoomMapping m : mappings) {
            String skip = RoomFanOut.skipReason(m.remoteDomain(), fromDomain, viaServers, hubs, payloadHome);
            if (skip != null) {
                Log.debug("fanOut: skip {} — {} (via=[{}], hubs=[{}])", m.remoteDomain(), skip, viaTrail, hubs);
                continue;
            }
            String nextHop = manager.getRoutingTable().findNextHop(m.remoteDomain()).orElse(m.remoteDomain());
            // As the hub, we are the server this spoke's copy enters through.
            boolean sent = FederationSender.send("muc-forward fan-out", m.remoteDomain(),
                    () -> FederationStanzaFactory.mucForward(nextHop, m.remoteDomain(), m.remoteRoomJid(),
                            localDomain, localDomain, newHubs, roomStanza(payloadEl)));
            if (sent) Log.debug("fanOut: relayed from {} to {} via {}", fromDomain, m.remoteDomain(), nextHop);
        }
    }

    /**
     * Delivers a relayed groupchat message to each occupant of the local room, bypassing MUC's
     * occupant check, and records it in the room history. Reports whether it was new here.
     */
    private Arrival injectMessage(Element msgEl, String targetRoom, String fromDomain, String src) {
        MUCRoom room = manager.findLocalMucRoom(targetRoom);
        if (room == null) {
            Log.warn("injectMessage: room {} not found locally", targetRoom);
            return Arrival.NEW;
        }

        Element moderatedRetract = RoomModeration.moderatedRetract(msgEl);
        if (moderatedRetract != null) {
            return injectModeration(msgEl, moderatedRetract, room);
        }

        String senderNick = virtualNick(msgEl.attributeValue("from"));
        if (localDomain().equals(originOf(senderNick, localDomain()))) {
            Log.debug("injectMessage: dropping self-echo of local user {} into {}", senderNick, targetRoom);
            return Arrival.OWN;
        }

        String roomJid    = room.getJID().toBareJID();
        String originRoom = bareJidOf(msgEl.attributeValue("to"));
        String originId   = originStanzaId(msgEl, originRoom, roomJid);
        if (originId != null && !manager.getRoomMessageIds().firstArrival(roomJid, originRoom, originId)) {
            Log.debug("injectMessage: {} already has message {} from {}, dropping the copy from {}",
                      roomJid, originId, originRoom, fromDomain);
            return Arrival.DUPLICATE;
        }

        // Occupants get a copy prepared for this server; the original fans out unchanged.
        Element deliverEl = deliveryCopy(msgEl, targetRoom, src, fromDomain);
        RoomModeration.stripModeration(deliverEl);
        stampRoomIdentity(deliverEl, room, senderNick);

        JID targetJID = new JID(targetRoom);
        String virtualFrom = targetJID.getNode() + "@" + targetJID.getDomain() + "/" + senderNick;
        archiveToRoomHistory(room, deliverEl, virtualFrom, senderNick);

        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) {
            bufferPendingDelivery(targetRoom, deliverEl, virtualFrom);
            return Arrival.NEW;
        }
        deliverToOccupants(deliverEl, virtualFrom, occupants);
        Log.debug("injectMessage: delivered to {} occupant(s) in {}", occupants.size(), targetRoom);
        return Arrival.NEW;
    }

    /** The copy of a relayed message that local occupants get: its file share rewritten for this server, or blocked. */
    private Element deliveryCopy(Element msgEl, String targetRoom, String src, String fromDomain) {
        var fileRelay = manager.getFileRelay();
        Element copy = null;
        if (fileRelay != null) {
            if (!manager.getRoomManager().isFilesEnabled(targetRoom) && fileRelay.annotationOf(msgEl) != null) {
                copy = msgEl.createCopy();
                fileRelay.replaceWithLocalBlockedNotice(copy);
            } else {
                copy = fileRelay.rewriteForDelivery(msgEl, targetRoom, src, fromDomain);
            }
        }
        return copy != null ? copy : msgEl.createCopy();
    }

    /**
     * Gives a relayed message this room's stanza-id (XEP-0359) and occupant-id (XEP-0421). The origin
     * room's id is reused so every copy of the room shares it; a reused or untrustworthy one is replaced.
     */
    private void stampRoomIdentity(Element msgEl, MUCRoom room, String senderNick) {
        String roomJid = room.getJID().toBareJID();
        String originRoom = bareJidOf(msgEl.attributeValue("to"));
        String id = originStanzaId(msgEl, originRoom, roomJid);
        for (Element sid : msgEl.elements(QName.get("stanza-id", NS_STANZA_ID))) {
            msgEl.remove(sid);
        }
        var origin = new RoomMessageIds.Origin(originRoom, id, senderNick);
        if (id != null && !manager.getRoomMessageIds().claim(roomJid, id, origin)) {
            Log.warn("SECURITY: relayed message from {} into {} reuses stanza-id {} already used in the room; "
                   + "giving it a fresh one", senderNick, roomJid, id);
            id = null;
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
            manager.getRoomMessageIds().claim(roomJid, id, origin);
        }
        msgEl.addElement(QName.get("stanza-id", NS_STANZA_ID))
             .addAttribute("id", id)
             .addAttribute("by", roomJid);

        for (Element oid : msgEl.elements(QName.get("occupant-id", NS_OCCUPANT_ID))) {
            msgEl.remove(oid);
        }
        String occupantId = occupantIdFor(room, senderNick);
        if (occupantId != null) {
            msgEl.addElement(QName.get("occupant-id", NS_OCCUPANT_ID)).addAttribute("id", occupantId);
        }
    }

    /** The reusable stanza-id the origin room gave {@code msgEl}, or null if none or the origin is this room. */
    private static String originStanzaId(Element msgEl, String originRoom, String roomJid) {
        if (originRoom == null || originRoom.equals(roomJid)) return null;
        for (Element sid : msgEl.elements(QName.get("stanza-id", NS_STANZA_ID))) {
            String candidate = sid.attributeValue("id");
            if (originRoom.equals(bareJidOf(sid.attributeValue("by")))
                    && candidate != null && REUSABLE_STANZA_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Applies a XEP-0425 moderation relayed from the room it names, but only to a message that came
     * from that room and was written by a user of that room's server (see {@link RoomModeration}).
     */
    private Arrival injectModeration(Element msgEl, Element retract, MUCRoom room) {
        String roomJid    = room.getJID().toBareJID();
        String originRoom = bareJidOf(msgEl.attributeValue("to"));
        String targetId   = retract.attributeValue("id");

        if (originRoom == null || originRoom.equals(roomJid)
                || !originRoom.equals(msgEl.attributeValue("from"))) {
            Log.warn("SECURITY: dropping a moderation for {} that does not come from its origin room "
                   + "(from={}, to={})", roomJid, msgEl.attributeValue("from"), msgEl.attributeValue("to"));
            return Arrival.NEW;
        }
        if (RoomModeration.belongsToRoomServer(localDomain(), originRoom)) {
            Log.debug("injectModeration: dropping an echo of our own room {}'s moderation", originRoom);
            return Arrival.OWN;
        }
        var ids = manager.getRoomMessageIds();
        String localId = ids.localIdOf(roomJid, originRoom, targetId);
        if (localId == null) {
            Log.info("Not applying a moderation from {} in {}: message {} is not one this room received "
                   + "from there, or is too old to be remembered", originRoom, roomJid, LogSafe.text(targetId));
            return Arrival.NEW;
        }
        String author = ids.originOf(roomJid, localId).author();
        if (!RoomModeration.belongsToRoomServer(author, originRoom)) {
            Log.warn("SECURITY: refusing a moderation from {} in {}: message {} was written by {}, who is "
                   + "not a user of that room's server", originRoom, roomJid, LogSafe.text(targetId), author);
            return Arrival.NEW;
        }
        if (!ids.markModerated(roomJid, localId)) {
            Log.debug("injectModeration: {} in {} already moderated", localId, roomJid);
            return Arrival.DUPLICATE;
        }

        String moderator = moderatorOf(retract, originRoom);
        Element announcement = RoomModeration.announcement(roomJid, msgEl.attributeValue("id"), localId,
                moderator == null ? null : roomJid + "/" + moderator,
                moderator == null ? null : occupantIdFor(room, moderator),
                RoomModeration.reasonOf(retract));
        announcement.remove(announcement.attribute("to"));

        Deque<StoredModeration> kept =
                federatedModerations.computeIfAbsent(roomJid, k -> new ConcurrentLinkedDeque<>());
        kept.addLast(new StoredModeration(localId, announcement.createCopy(), System.currentTimeMillis()));
        while (kept.size() > MODERATIONS_KEPT_PER_ROOM) kept.pollFirst();

        Log.info("Applying a moderation from {} in {}: message {} by {}", originRoom, roomJid, localId, author);
        deliverToOccupants(announcement, roomJid, room.getOccupants());
        return Arrival.NEW;
    }

    /** The moderator a relayed moderation names, when it is a user of the origin room's server; otherwise null. */
    private static String moderatorOf(Element retract, String originRoom) {
        Element moderated = retract.element(QName.get("moderated", RoomModeration.NS_MODERATE));
        String by = moderated.attributeValue("by");
        if (by == null) return null;
        try {
            String claimed = new JID(by).getResource();
            return claimed != null && RoomModeration.belongsToRoomServer(claimed, originRoom) ? claimed : null;
        } catch (IllegalArgumentException e) {
            // An unparseable "by" is announced without a moderator.
            return null;
        }
    }

    /**
     * Re-sends the federated moderations of messages still in the room history to a local occupant
     * who just joined, so the history replay does not bring those messages back.
     */
    public void replayModerations(String roomJid, JID joiner) {
        Deque<StoredModeration> kept = federatedModerations.get(roomJid);
        if (kept == null || kept.isEmpty() || joiner == null) return;
        MUCRoom room = manager.findLocalMucRoom(roomJid);
        if (room == null) return;

        Set<String> inHistory = new HashSet<>();
        for (var it = room.getRoomHistory().getMessageHistory(); it.hasNext(); ) {
            Message m = it.next();
            for (Element sid : m.getElement().elements(QName.get("stanza-id", NS_STANZA_ID))) {
                if (roomJid.equals(bareJidOf(sid.attributeValue("by")))) inHistory.add(sid.attributeValue("id"));
            }
        }
        int sent = 0;
        for (StoredModeration sm : kept) {
            if (!inHistory.contains(sm.targetId())) continue;
            Element copy = sm.announcement().createCopy();
            copy.addElement(QName.get("delay", RoomModeration.NS_DELAY))
                .addAttribute("from", roomJid)
                .addAttribute("stamp", XMPPDateTimeFormat.format(new Date(sm.at())));
            copy.addAttribute("from", roomJid);
            copy.addAttribute("to", joiner.toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationSender.directDeliver(delivery);
            sent++;
        }
        if (sent > 0) Log.debug("replayModerations: sent {} to {} joining {}", sent, joiner, roomJid);
    }

    /**
     * Records a relayed message in the room's history and conversation log, and raises the MUC event
     * that MAM archivers such as Monitoring listen to. A failure here does not stop live delivery.
     */
    private void archiveToRoomHistory(MUCRoom room, Element deliverEl, String virtualFrom, String senderNick) {
        try {
            Element copy = deliverEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to", room.getJID().toString());
            Message archived = new Message(copy);
            JID sender = new JID(senderNick);
            room.getRoomHistory().addMessage(archived);
            if (room.isLogEnabled()) {
                room.getMUCService().logConversation(room, archived, sender);
            }
            MUCEventDispatcher.messageReceived(room.getJID(), sender, senderNick, archived);
        } catch (RuntimeException e) {
            Log.warn("Could not archive a relayed message into the history of {}", room.getJID(), e);
        }
    }

    /** Delivers one prepared message to every given occupant. */
    private void deliverToOccupants(Element deliverEl, String virtualFrom, Collection<MUCOccupant> occupants) {
        for (MUCOccupant occupant : occupants) {
            Element copy = deliverEl.createCopy();
            copy.addAttribute("from", virtualFrom);
            copy.addAttribute("to", occupant.getUserAddress().toString());
            Message delivery = new Message(copy);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationSender.directDeliver(delivery);
        }
    }

    /** Queues a message for an empty room until a local occupant joins or the TTL passes. */
    private void bufferPendingDelivery(String targetRoom, Element deliverEl, String virtualFrom) {
        Deque<PendingDelivery> q =
                pendingRoomDeliveries.computeIfAbsent(targetRoom, k -> new ConcurrentLinkedDeque<>());
        pruneStale(q);
        q.addLast(new PendingDelivery(deliverEl.createCopy(), virtualFrom, System.currentTimeMillis()));
        while (q.size() > PENDING_DELIVERY_MAX_PER_ROOM) q.pollFirst();
        Log.debug("injectMessage: room {} empty — buffered delivery ({} pending, {} ms TTL)",
                  targetRoom, q.size(), PENDING_DELIVERY_TTL_MS);
    }

    private void pruneStale(Deque<PendingDelivery> q) {
        long cutoff = System.currentTimeMillis() - PENDING_DELIVERY_TTL_MS;
        while (true) {
            PendingDelivery head = q.peekFirst();
            if (head == null || head.queuedAt() >= cutoff) break;
            q.pollFirst();
        }
    }

    /**
     * Delivers the messages buffered for {@code roomJid} once a local occupant joins. The queue is
     * claimed only when it can be delivered, and only one of two concurrent joins can claim it.
     */
    public void flushPendingDeliveries(String roomJid) {
        Deque<PendingDelivery> pending = pendingRoomDeliveries.get(roomJid);
        if (pending == null) return;
        pruneStale(pending);
        MUCRoom room = manager.findLocalMucRoom(roomJid);
        if (room == null) {
            pendingRoomDeliveries.remove(roomJid);
            return;
        }
        if (pending.isEmpty()) return;
        Collection<MUCOccupant> occupants = room.getOccupants();
        if (occupants.isEmpty()) return;
        Deque<PendingDelivery> q = pendingRoomDeliveries.remove(roomJid);
        if (q == null) return;
        Log.info("injectMessage: {} gained an occupant — delivering {} buffered message(s)", roomJid, q.size());
        for (PendingDelivery pd : q) {
            deliverToOccupants(pd.deliverEl(), pd.virtualFrom(), occupants);
        }
    }

    /**
     * Shows a remote user joining or leaving to each occupant of the local room and updates the
     * virtual-occupant roster; on a join, also sends our occupants back to the joiner.
     */
    private void injectPresence(Element presEl, String targetRoom, String fromDomain, String src) {
        MUCRoom room = manager.findLocalMucRoom(targetRoom);
        if (room == null) {
            Log.warn("injectPresence: room {} not found locally", targetRoom);
            return;
        }
        // Not skipped for an empty room: a relay hub with no local users still has to track occupants.
        Collection<MUCOccupant> occupants = room.getOccupants();

        String originalFrom = presEl.attributeValue("from");
        boolean leaving     = "unavailable".equals(presEl.attributeValue("type"));
        String senderNick   = virtualNick(originalFrom);
        String home         = originOf(senderNick, fromDomain);
        if (localDomain().equals(home)) {
            Log.debug("injectPresence: dropping self-echo of local user {} looped back via {} into {}",
                      senderNick, fromDomain, targetRoom);
            return;
        }

        recordPresenceForConsole(targetRoom, senderNick, presEl, leaving);

        JID targetJID      = new JID(targetRoom);
        JID virtualFromJID = new JID(targetJID.getNode(), targetJID.getDomain(), senderNick);
        String occupantId  = occupantIdFor(room, senderNick);
        for (MUCOccupant occupant : occupants) {
            Presence delivery = occupantPresence(presEl, virtualFromJID, occupant.getUserAddress(),
                                                 originalFrom, leaving, occupantId);
            FederationStanzaFactory.markAsForwarded(delivery);
            FederationSender.directDeliver(delivery);
        }
        Log.debug("injectPresence: {} virtual presence for {} (from={}, via fromDomain={}) to {} occupant(s) in {}",
                  leaving ? "leave" : "join", senderNick, originalFrom, fromDomain, occupants.size(), targetRoom);

        if (leaving) {
            untrackAndPassOnLeave(targetRoom, senderNick, home, src);
        } else {
            manager.getRoomManager().trackVirtualOccupant(targetRoom, home, src, senderNick);
        }

        // A sync presence (marked fed-origin) gets no reply, which stops ping-pong.
        if (!leaving && presEl.element("fed-origin") == null) {
            syncLocalOccupantsToRemote(targetRoom, occupants, fromDomain, home);
        }
    }

    /** Records a virtual occupant's show and status for the admin console, or forgets them on a leave. */
    private void recordPresenceForConsole(String targetRoom, String senderNick, Element presEl, boolean leaving) {
        if (leaving) {
            manager.getRoomManager().clearVirtualOccupantPresence(targetRoom, senderNick);
            return;
        }
        Element show   = presEl.element("show");
        Element status = presEl.element("status");
        manager.getRoomManager().setVirtualOccupantPresence(targetRoom, senderNick,
                show   != null ? show.getText()   : "",
                status != null ? status.getText() : "");
    }

    /** The presence a local occupant sees for a remote user's join, change or leave. */
    private static Presence occupantPresence(Element presEl, JID virtualFrom, JID occupant, String realJid,
                                             boolean leaving, String occupantId) {
        Presence delivery = new Presence();
        delivery.setFrom(virtualFrom);
        delivery.setTo(occupant);
        if (leaving) {
            delivery.setType(Presence.Type.unavailable);
        } else {
            copyAvailability(presEl, delivery.getElement());
        }
        Element item = delivery.getElement().addElement("x", "http://jabber.org/protocol/muc#user").addElement("item");
        item.addAttribute("affiliation", "none");
        item.addAttribute("role", leaving ? "none" : "participant");
        if (realJid != null) item.addAttribute("jid", realJid);
        if (occupantId != null) {
            delivery.getElement().addElement("occupant-id", NS_OCCUPANT_ID).addAttribute("id", occupantId);
        }
        return delivery;
    }

    /** Copies show, status and the XEP-0153 avatar hash, which is what makes a client fetch the vCard photo. */
    private static void copyAvailability(Element source, Element target) {
        Element show = source.element("show");
        if (show != null) target.addElement("show").setText(show.getText());
        for (Element status : source.elements("status")) {
            target.addElement("status").setText(status.getText());
        }
        for (Element x : source.elements("x")) {
            if ("vcard-temp:x:update".equals(x.getNamespaceURI())) target.add(x.createCopy());
        }
    }

    /** Stops tracking a remote user who left and passes the leave on; only the first removal propagates. */
    private void untrackAndPassOnLeave(String targetRoom, String senderNick, String home, String src) {
        Set<String> arrivedVia = manager.getRoomManager().getVirtualOccupants(targetRoom).stream()
                .filter(vo -> vo.nick().equals(senderNick))
                .findFirst()
                .map(vo -> new HashSet<>(vo.arrivedVia()))
                .orElseGet(() -> new HashSet<>(Collections.singletonList(src)));
        if (manager.getRoomManager().untrackVirtualOccupant(targetRoom, senderNick)) {
            manager.getOccupantSync().forwardVirtualLeave(targetRoom, senderNick, home, arrivedVia);
        }
    }

    /**
     * Sends a join presence for each occupant of {@code localRoom} back toward a remote joiner. It
     * goes through the mapping for the joiner's home server, else the sender's, else every mapping.
     */
    private void syncLocalOccupantsToRemote(String localRoom, Collection<MUCOccupant> occupants,
                                            String fromDomain, String joinerOrigin) {
        List<RoomMapping> allMappings = manager.getRoomManager().getMappingsForLocal(localRoom);
        if (allMappings.isEmpty()) return;

        List<RoomMapping> targets = mappingsTo(allMappings, joinerOrigin);
        if (targets.isEmpty()) targets = mappingsTo(allMappings, fromDomain);
        if (targets.isEmpty()) targets = allMappings;

        String localDomain = localDomain();
        for (RoomMapping mapping : targets) {
            String remoteDomain  = mapping.remoteDomain();
            String remoteRoomJid = mapping.remoteRoomJid();
            String nextHop = manager.getRoutingTable().findNextHop(remoteDomain).orElse(remoteDomain);

            for (MUCOccupant occupant : occupants) {
                Presence sync = new Presence();
                sync.setFrom(occupant.getUserAddress());
                copyAvailability(occupant.getPresence().getElement(), sync.getElement());
                FederationStanzaFactory.markAsForwarded(sync);
                FederationSender.send("occupant sync", remoteDomain, () -> FederationStanzaFactory.mucForward(
                        nextHop, remoteDomain, remoteRoomJid, localDomain, localDomain, sync));
            }

            manager.getOccupantSync().forwardVirtualOccupants(localRoom, remoteDomain, remoteRoomJid);
            Log.debug("syncLocalOccupantsToRemote: pushed {} occupant(s) + virtuals from {} to {}",
                      occupants.size(), localRoom, remoteRoomJid);
        }
    }

    private static List<RoomMapping> mappingsTo(List<RoomMapping> mappings, String remoteDomain) {
        return mappings.stream().filter(m -> m.remoteDomain().equals(remoteDomain)).collect(Collectors.toList());
    }

    /** The XEP-0421 occupant-id Openfire computes for a virtual occupant's bare JID, or null. */
    private String occupantIdFor(MUCRoom room, String senderNick) {
        try {
            return room.generateOccupantId(new JID(senderNick));
        } catch (RuntimeException e) {
            Log.debug("No occupant-id for {} in {}; sending without one", senderNick, room.getJID(), e);
            return null;
        }
    }

    /** Whether the admin enabled this local room for federation; peers may only map or inject into such rooms. */
    private boolean isFederatedLocalRoom(String roomJid) {
        return roomJid != null && manager.getRoomManager().isFederated(roomJid);
    }

    /** Whether {@code el} is a stanza a room can carry: a message or a presence. */
    private static boolean isRoomStanza(Element el) {
        return "message".equals(el.getName()) || "presence".equals(el.getName());
    }

    /** A copy of a forwarded room stanza as a packet. */
    private static Packet roomStanza(Element el) {
        return switch (el.getName()) {
            case "message"  -> new Message(el.createCopy());
            case "presence" -> new Presence(el.createCopy());
            default -> throw new IllegalArgumentException("Not a room stanza: " + el.getName());
        };
    }

    private static Element firstChild(Element el) {
        List<Element> children = el.elements();
        return children.isEmpty() ? null : children.get(0);
    }

    /** The stable MUC nick of a remote user: their bare JID without a resource. */
    static String virtualNick(String fromJid) {
        if (fromJid == null) return "remote";
        try {
            JID jid = new JID(fromJid);
            return (jid.getNode() != null ? jid.getNode() + "@" : "") + jid.getDomain();
        } catch (IllegalArgumentException e) {
            return "remote";
        }
    }

    /** The home domain of a virtual nick ("user@home"), or {@code fallback} if it has none. */
    static String originOf(String nick, String fallback) {
        try {
            String d = new JID(nick).getDomain();
            return (d == null || d.isEmpty()) ? fallback : d;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Normalized bare JID of {@code jid}, or null if absent or unparseable. */
    static String bareJidOf(String jid) {
        if (jid == null) return null;
        try {
            return new JID(jid).toBareJID();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
