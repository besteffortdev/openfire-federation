package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.Element;
import org.jivesoftware.openfire.SharedGroupException;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.handler.PresenceSubscribeHandler;
import org.jivesoftware.openfire.pep.PEPService;
import org.jivesoftware.openfire.pep.PEPServiceManager;
import org.jivesoftware.openfire.pubsub.Node;
import org.jivesoftware.openfire.pubsub.PublishedItem;
import org.jivesoftware.openfire.roster.Roster;
import org.jivesoftware.openfire.roster.RosterItem;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.user.PresenceEventDispatcher;
import org.jivesoftware.openfire.user.PresenceEventListener;
import org.jivesoftware.openfire.user.UserAlreadyExistsException;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Presence;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays local users' presence, subscriptions and PEP data to their federated contacts, where
 * Openfire's own delivery would bypass the interceptor or fail on a multi-hop path
 * (docs/protocol/06). It listens to Openfire's presence events for local sessions.
 */
public final class PresenceRelay implements PresenceEventListener {

    private static final Logger Log = LoggerFactory.getLogger(PresenceRelay.class);

    /** PEP nodes mirrored to federated subscribers: avatar metadata, nickname and the OMEMO device list. */
    private static final List<String> PUSH_PEP_NODES = List.of(
        "urn:xmpp:avatar:metadata",
        "http://jabber.org/protocol/nick",
        "eu.siacs.conversations.axolotl.devicelist"
    );

    private final FederationManager manager;

    /** Remote subscribers of each local user's presence, tracked from relayed subscriptions. */
    private final Map<String, Set<String>> remoteSubscribers = new ConcurrentHashMap<>();

    PresenceRelay(FederationManager manager) {
        this.manager = manager;
    }

    @Override
    public void availableSession(ClientSession session, Presence presence) {
        onLocalPresence(session, presence);
        try {
            if (session != null && session.getAddress() != null) probeRemoteContacts(session.getAddress());
        } catch (RuntimeException e) {
            Log.warn("Could not probe the multi-hop contacts of {}", session.getAddress(), e);
        }
    }

    @Override
    public void unavailableSession(ClientSession session, Presence presence) {
        onLocalPresence(session, presence);
    }

    @Override
    public void presenceChanged(ClientSession session, Presence presence) {
        onLocalPresence(session, presence);
    }

    /** A local user subscribed to a contact mid-session: Openfire's own probe to a multi-hop contact would fail. */
    @Override
    public void subscribedToPresence(JID subscriberJID, JID userJID) {
        probeRemoteContact(subscriberJID, userJID);
    }

    @Override
    public void unsubscribedToPresence(JID unsubscriberJID, JID recipientJID) { }

    /** Relays a local user's presence change to their federated rooms and contacts. */
    private void onLocalPresence(ClientSession session, Presence presence) {
        if (session == null || session.getAddress() == null) return;
        try {
            manager.getOccupantSync().forwardLocalOccupantPresence(session.getAddress(), presence);
            forwardPresenceToRemoteSubscribers(session.getAddress(), presence);
        } catch (RuntimeException e) {
            Log.warn("Could not relay the presence change of {}", session.getAddress(), e);
        }
    }

    /**
     * Sends a local user's broadcast presence to subscribers on multi-hop peers, and to contacts who
     * see the user through a shared contact list; Openfire's own copies bypass the interceptor.
     */
    public void forwardPresenceToRemoteSubscribers(JID user, Presence presence) {
        if (user == null || user.getNode() == null) return;
        if (!XMPPServer.getInstance().isLocal(user)) return;

        Set<String> done = new HashSet<>();
        if (FederationProperties.DIRECT_MSG_RELAY.getValue()) {
            for (String bare : remoteSubscriberTargets(user)) {
                JID contact = new JID(bare);
                if (!manager.isMultiHopDomain(contact.getDomain())) continue;
                manager.forwardDirectPresence(directedPresence(user, contact, presence));
                done.add(bare);
            }
        }
        for (String bare : manager.getContactLists().subscribersOf(user)) {
            if (done.contains(bare)) continue;
            manager.forwardDirectPresence(directedPresence(user, new JID(bare), presence));
        }
    }

    /**
     * A local user's federated presence subscribers: those tracked from relayed subscriptions plus
     * the roster's FROM and BOTH items.
     */
    private Set<String> remoteSubscriberTargets(JID user) {
        Set<String> targets = new LinkedHashSet<>(
                remoteSubscribers.getOrDefault(user.toBareJID(), Collections.emptySet()));
        try {
            Roster roster = XMPPServer.getInstance().getRosterManager().getRoster(user.getNode());
            if (roster != null) {
                for (RosterItem item : roster.getRosterItems()) {
                    JID c = item.getJid();
                    if (c == null || c.getNode() == null) continue;
                    RosterItem.SubType sub = item.getSubStatus();
                    if (sub == RosterItem.SUB_FROM || sub == RosterItem.SUB_BOTH) targets.add(c.toBareJID());
                }
            }
        } catch (UserNotFoundException e) {
            Log.debug("No roster for {}; using the tracked subscribers only", user);
        }
        return targets;
    }

    /** Records that {@code subscriber} is now subscribed to local {@code localUser}'s presence. */
    public void addRemoteSubscriber(JID localUser, JID subscriber) {
        if (localUser == null || subscriber == null || subscriber.getNode() == null) return;
        remoteSubscribers.computeIfAbsent(localUser.toBareJID(),
                k -> ConcurrentHashMap.newKeySet()).add(subscriber.toBareJID());
    }

    /** Forgets a subscriber after a relayed unsubscribed. */
    public void removeRemoteSubscriber(JID localUser, JID subscriber) {
        if (localUser == null || subscriber == null) return;
        Set<String> subs = remoteSubscribers.get(localUser.toBareJID());
        if (subs != null) {
            subs.remove(subscriber.toBareJID());
            if (subs.isEmpty()) remoteSubscribers.remove(localUser.toBareJID());
        }
    }

    /** Sends a local user's current presence to a multi-hop {@code target} over the overlay. */
    public void pushUserPresenceTo(JID target, JID localUser) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (localUser == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (target == null || target.getNode() == null) return;
        if (!manager.isMultiHopDomain(target.getDomain())) return;
        Presence cur = currentPresenceOf(localUser);
        if (cur == null) return;
        manager.forwardDirectPresence(directedPresence(localUser, target, cur));
    }

    /** Answers a relayed presence probe, but only for a presence subscriber (RFC 6121 §4.3.2). */
    public void answerPresenceProbe(JID prober, JID localUser) {
        if (prober == null || localUser == null || !isPresenceSubscriber(localUser, prober)) {
            Log.info("SECURITY: not answering presence probe from {} for {} — not a presence subscriber",
                     prober, localUser);
            return;
        }
        pushUserPresenceTo(prober, localUser);
    }

    /** Whether {@code contact} is one of {@code localUser}'s presence subscribers. */
    public boolean isPresenceSubscriber(JID localUser, JID contact) {
        if (contact == null || contact.getNode() == null) return false;
        return remoteSubscriberTargets(localUser).contains(contact.toBareJID())
            || manager.getContactLists().isAuthorizedContact(localUser, contact);
    }

    /** Sends a shared user's current presence, or unavailable, to a contact on a server it is shared with. */
    public void pushContactPresenceTo(JID contact, JID localUser) {
        if (localUser == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (contact == null || contact.getNode() == null) return;
        Presence cur = currentPresenceOf(localUser);
        Presence p;
        if (cur != null) {
            p = directedPresence(localUser, contact, cur);
        } else {
            p = new Presence(Presence.Type.unavailable);
            p.setFrom(localUser.asBareJID());
            p.setTo(contact);
        }
        manager.forwardDirectPresence(p);
    }

    /** {@link #pushLocalPepItemsTo} for a shared-list contact, whatever the hop count. */
    public void pushContactPepItemsTo(JID contact, JID localUser) {
        if (localUser == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (contact == null || contact.getNode() == null) return;
        pushPepItems(contact, localUser);
    }

    /**
     * Pushes a local user's current {@link #PUSH_PEP_NODES} items to a newly approved multi-hop
     * subscriber. Openfire's own push after a relayed approval races its async subscription and sends nothing.
     */
    public void pushLocalPepItemsTo(JID subscriber, JID localUser) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (localUser == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (subscriber == null || subscriber.getNode() == null) return;
        if (!manager.isMultiHopDomain(subscriber.getDomain())) return;
        pushPepItems(subscriber, localUser);
    }

    /**
     * Sends the last item of each mirrored PEP node of {@code localUser} to {@code subscriber}. Never
     * throws, since the interceptor calls it just before rejecting the stanza it relayed.
     */
    private void pushPepItems(JID subscriber, JID localUser) {
        PEPService pep;
        try {
            PEPServiceManager pepServices = XMPPServer.getInstance().getIQPEPHandler().getServiceManager();
            pep = pepServices.getPEPService(localUser.asBareJID(), false);
        } catch (RuntimeException e) {
            Log.warn("Could not look up the PEP service of {}", localUser, e);
            return;
        }
        if (pep == null) return;

        for (String nodeId : PUSH_PEP_NODES) {
            try {
                Node node = pep.getNodes().stream()
                        .filter(n -> nodeId.equals(n.getUniqueIdentifier().getNodeId()))
                        .findFirst().orElse(null);
                if (node == null) continue;
                PublishedItem item = node.getLastPublishedItem();
                if (item == null) continue;

                Message event = pepEvent(localUser.asBareJID(), subscriber, nodeId, item.getID(), item.getPayload());
                if (manager.forwardDirectMessage(event)) {
                    Log.info("pushed PEP node '{}' (item {}) of {} to new remote subscriber {}",
                             nodeId, item.getID(), localUser, subscriber);
                }
            } catch (RuntimeException e) {
                Log.warn("Could not push PEP node '{}' of {} to {}", nodeId, localUser, subscriber, e);
            }
        }
    }

    /**
     * A pubsub event carrying one PEP item to {@code to}. It is a headline, so the destination
     * delivers it to every resource rather than to a single one.
     */
    private static Message pepEvent(JID from, JID to, String nodeId, String itemId, Element payload) {
        Message event = new Message();
        event.setType(Message.Type.headline);
        event.setFrom(from);
        event.setTo(to);
        Element items = event.getElement()
                .addElement("event", "http://jabber.org/protocol/pubsub#event")
                .addElement("items");
        items.addAttribute("node", nodeId);
        Element itemEl = items.addElement("item");
        if (itemId != null) itemEl.addAttribute("id", itemId);
        if (payload != null) itemEl.add(payload.createCopy());
        return event;
    }

    /**
     * Re-pushes an item a local user just published to one of the {@link #PUSH_PEP_NODES} to every
     * federated subscriber, since Openfire's own notification is unreliable for remote subscribers.
     */
    public void relayLocalPepPublish(JID publisher, String nodeId, String itemId, Element payload) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (publisher == null || publisher.getNode() == null) return;
        if (!XMPPServer.getInstance().isLocal(publisher)) return;
        if (nodeId == null || !PUSH_PEP_NODES.contains(nodeId)) return;

        Set<String> targets = new LinkedHashSet<>();
        for (String bare : remoteSubscriberTargets(publisher)) {
            if (manager.isMultiHopDomain(new JID(bare).getDomain())) targets.add(bare);
        }
        targets.addAll(manager.getContactLists().subscribersOf(publisher));
        for (String bare : targets) {
            JID subscriber = new JID(bare);
            Message event = pepEvent(publisher.asBareJID(), subscriber, nodeId, itemId, payload);
            if (manager.forwardDirectMessage(event)) {
                Log.info("re-pushed PEP node '{}' (item {}) of {} to subscriber {} on publish",
                         nodeId, itemId, publisher, subscriber);
            }
        }
    }

    /**
     * Applies to the local sender's roster the subscription change Openfire would have made, had the
     * interceptor not consumed the presence to keep it off native S2S. Never throws, for the same reason.
     */
    public void syncLocalRosterOnSubscriptionRelay(Presence pres) {
        Presence.Type type = pres.getType();
        if (type != Presence.Type.subscribe && type != Presence.Type.subscribed
                && type != Presence.Type.unsubscribe && type != Presence.Type.unsubscribed) {
            return;
        }
        JID sender = pres.getFrom();
        JID target = pres.getTo();
        if (sender == null || sender.getNode() == null || target == null) return;
        if (!XMPPServer.getInstance().isLocal(sender)) return;

        try {
            Roster roster = XMPPServer.getInstance().getRosterManager().getRoster(sender.getNode());
            if (roster == null) return;
            RosterItem item;
            if (roster.isRosterItem(target)) {
                item = roster.getRosterItem(target);
            } else if (type == Presence.Type.subscribe) {
                item = roster.createRosterItem(target, false, true);
            } else {
                return;
            }
            if (!applySubscriptionChange(roster, item, type)) return;

            if (type == Presence.Type.subscribed) {
                PresenceEventDispatcher.subscribedToPresence(target, sender);
            } else if (type == Presence.Type.unsubscribed) {
                PresenceEventDispatcher.unsubscribedToPresence(sender, target);
            }
            Log.debug("Synced local roster for {} -> {} on relayed {} (sub now {})",
                      sender, target, type, item.getSubStatus());
        } catch (UserNotFoundException | UserAlreadyExistsException | SharedGroupException | RuntimeException e) {
            // Caught whatever the cause: a failure must not stop the caller rejecting the stanza.
            Log.warn("Could not sync the roster of {} -> {} on a relayed {}", sender, target, type, e);
        }
    }

    /** Updates a roster item by Openfire's own subscription state table and saves it; false when nothing changes. */
    private static boolean applySubscriptionChange(Roster roster, RosterItem item, Presence.Type type)
            throws UserNotFoundException {
        PresenceSubscribeHandler.Change change =
                PresenceSubscribeHandler.getStateChange(item.getSubStatus(), type, true);
        if (change == null) return false;
        if (change.getNewAsk() != null && change.getNewAsk() != item.getAskStatus()) {
            item.setAskStatus(change.getNewAsk());
        }
        if (change.getNewSub() != null && change.getNewSub() != item.getSubStatus()) {
            item.setSubStatus(change.getNewSub());
        }
        if (change.getNewRecv() != null && change.getNewRecv() != item.getRecvStatus()) {
            item.setRecvStatus(change.getNewRecv());
        }
        roster.updateRosterItem(item);
        return true;
    }

    /** On login, probes the user's multi-hop contacts over the overlay to pull their current presence. */
    public void probeRemoteContacts(JID user) {
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (user == null || user.getNode() == null || !XMPPServer.getInstance().isLocal(user)) return;
        Roster roster;
        try {
            roster = XMPPServer.getInstance().getRosterManager().getRoster(user.getNode());
        } catch (UserNotFoundException e) {
            Log.debug("No roster for {} — nothing to probe", user);
            return;
        }
        if (roster == null) return;
        for (RosterItem item : roster.getRosterItems()) {
            JID contact = item.getJid();
            if (contact == null || contact.getNode() == null) continue;
            if (!manager.isMultiHopDomain(contact.getDomain())) continue;
            RosterItem.SubType sub = item.getSubStatus();
            if (sub != RosterItem.SUB_TO && sub != RosterItem.SUB_BOTH) continue;
            manager.forwardDirectPresence(probe(user.asBareJID(), contact));
        }
    }

    /** Probes one multi-hop contact over the overlay when a local user subscribes to it mid-session. */
    public void probeRemoteContact(JID localUser, JID contact) {
        if (!FederationProperties.PROBE_ON_SUBSCRIBE.getValue()) return;
        if (!FederationProperties.DIRECT_MSG_RELAY.getValue()) return;
        if (localUser == null || localUser.getNode() == null || !XMPPServer.getInstance().isLocal(localUser)) return;
        if (contact == null || contact.getNode() == null) return;
        if (!manager.isMultiHopDomain(contact.getDomain())) return;
        manager.forwardDirectPresence(probe(localUser.asBareJID(), contact.asBareJID()));
        Log.info("subscription-probe: probing multi-hop contact {} for {} over overlay",
                 contact.asBareJID(), localUser.asBareJID());
    }

    private static Presence probe(JID from, JID to) {
        Presence probe = new Presence();
        probe.setType(Presence.Type.probe);
        probe.setFrom(from);
        probe.setTo(to);
        return probe;
    }

    /** The first available presence among a local user's sessions, or null when offline. */
    private static Presence currentPresenceOf(JID user) {
        for (ClientSession s : XMPPServer.getInstance().getSessionManager().getSessions(user.getNode())) {
            Presence p = s.getPresence();
            if (p != null && p.isAvailable()) return p;
        }
        return null;
    }

    /** A copy of {@code src} redirected to {@code to}, keeping its extensions such as the avatar hash and caps. */
    private static Presence directedPresence(JID from, JID to, Presence src) {
        Presence p = (src != null) ? new Presence(src.getElement().createCopy()) : new Presence();
        p.setFrom(src != null && src.getFrom() != null ? src.getFrom() : from);
        p.setTo(to);
        return p;
    }
}
