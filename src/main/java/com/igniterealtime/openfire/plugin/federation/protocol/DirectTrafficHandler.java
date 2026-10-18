package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.LogSafe;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.pep.PEPService;
import org.jivesoftware.openfire.pep.PEPServiceManager;
import org.jivesoftware.openfire.pubsub.Node;
import org.jivesoftware.openfire.pubsub.PublishedItem;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.vcard.VCardManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;
import org.xmpp.packet.Presence;

import java.util.Collection;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_DESTINATION;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.ATTR_VIA;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.appendToTrail;
import static com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory.viaContains;

/**
 * Handles relayed 1:1 traffic ({@code direct-forward}, {@code presence-forward} and
 * {@code iq-forward}, docs/protocol/06): delivers it to the local recipient or relays it on. vCard and
 * PEP requests are answered here, because Openfire's own replies would not cross a multi-hop path.
 */
public final class DirectTrafficHandler {

    private static final Logger Log = LoggerFactory.getLogger(DirectTrafficHandler.class);

    private static final String NS_PUBSUB = "http://jabber.org/protocol/pubsub";

    private final FederationManager manager;
    private final InboundValidation validation;

    DirectTrafficHandler(FederationManager manager, InboundValidation validation) {
        this.manager = manager;
        this.validation = validation;
    }

    /** Delivers a relayed 1:1 message to its local recipient, or relays it toward its destination. */
    void handleDirectForward(String fromDomain, Element el) {
        Element payloadEl = checkedPayload("direct-forward", "message", fromDomain, el);
        if (payloadEl == null) return;
        if (!InboundValidation.isForUs(el.attributeValue(ATTR_DESTINATION))) {
            relay("direct-forward", el, payloadEl);
            return;
        }
        Message msg = new Message(payloadEl.createCopy());
        JID mto = msg.getTo();
        if (!validation.deliverableHere(mto, "direct-forward", fromDomain)) return;
        if (InboundValidation.isLocalConferenceDomain(mto.getDomain())) {
            // Unmarked, so the room's re-broadcast to other remote occupants is relayed normally.
            XMPPServer.getInstance().getPacketRouter().route(msg);
            Log.info("direct-forward: delivered MUC message {} -> {} (from {})", msg.getFrom(), mto, fromDomain);
            return;
        }
        if (manager.getFileRelay() != null) {
            manager.getFileRelay().rewriteInPlace(msg, fromDomain);
        }
        FederationStanzaFactory.markAsForwarded(msg);
        logRecipientCarbonState(msg.getTo());
        if (FederationSender.directDeliver(msg)) {
            // Direct delivery skipped the interceptors, so the message archiver has to be run here.
            FederationPacketInterceptor.runArchiveCapturePass(msg, null);
        }
        Log.info("direct-forward: delivered 1:1 {} -> {} (from {})", msg.getFrom(), msg.getTo(), fromDomain);
    }

    /**
     * Delivers a relayed 1:1 presence through Openfire's router, so its roster and subscription
     * logic applies, or relays it toward its destination.
     */
    void handlePresenceForward(String fromDomain, Element el) {
        Element payloadEl = checkedPayload("presence-forward", "presence", fromDomain, el);
        if (payloadEl == null) return;
        if (!InboundValidation.isForUs(el.attributeValue(ATTR_DESTINATION))) {
            relay("presence-forward", el, payloadEl);
            return;
        }
        Presence pres = new Presence(payloadEl.createCopy());
        JID pto = pres.getTo();
        // Checked before the probe branch, so a probe cannot reveal a user who does not live here.
        if (!validation.deliverableHere(pto, "presence-forward", fromDomain)) return;
        if (manager.getContactLists().handleInboundSubscription(pres)) {
            Log.info("presence-forward: answered {} {} -> {} for a shared contact (from {})",
                     pres.getType(), pres.getFrom(), pto, fromDomain);
            return;
        }
        // Openfire's own probe answer would bypass the interceptor and never cross the overlay.
        if (pres.getType() == Presence.Type.probe) {
            manager.getPresenceRelay().answerPresenceProbe(pres.getFrom(), pto);
            Log.info("presence-forward: answered probe {} -> {} (from {})", pres.getFrom(), pto, fromDomain);
            return;
        }
        String type = pres.getType() == null ? "available" : pres.getType().toString();
        if (InboundValidation.isLocalConferenceDomain(pto.getDomain())) {
            XMPPServer.getInstance().getPacketRouter().route(pres);
            Log.info("presence-forward: delivered MUC {} {} -> {} (from {})", type, pres.getFrom(), pto, fromDomain);
            return;
        }
        FederationStanzaFactory.markAsForwarded(pres);
        XMPPServer.getInstance().getPacketRouter().route(pres);
        Log.info("presence-forward: delivered 1:1 {} {} -> {} (from {}) avatarHash={}",
                 type, pres.getFrom(), pres.getTo(), fromDomain, FederationManager.avatarHashOf(pres));
    }

    /** Delivers a relayed user-addressed IQ, answering vCard and PEP requests here, or relays it on. */
    void handleIqForward(String fromDomain, Element el) {
        Element payloadEl = checkedPayload("iq-forward", "iq", fromDomain, el);
        if (payloadEl == null) return;
        if (!InboundValidation.isForUs(el.attributeValue(ATTR_DESTINATION))) {
            relay("iq-forward", el, payloadEl);
            return;
        }
        IQ iq = new IQ(payloadEl.createCopy());
        if (!validation.deliverableHere(iq.getTo(), "iq-forward", fromDomain)) return;
        IQ.Type type = iq.getType();
        if (type == IQ.Type.get || type == IQ.Type.set) {
            if (!answerVCardLocally(iq, fromDomain) && !answerPepItemsLocally(iq, fromDomain)) {
                XMPPServer.getInstance().getPacketRouter().route(iq);
                Log.info("iq-forward: delivered 1:1 {} {} -> {} (from {}) [reply relay best-effort]",
                         type, iq.getFrom(), iq.getTo(), fromDomain);
            }
        } else {
            FederationPacketInterceptor.deliverOverlayReply(iq);
            Log.info("iq-forward: delivered 1:1 reply {} {} -> {} (from {})",
                     type, iq.getFrom(), iq.getTo(), fromDomain);
        }
    }

    /**
     * The payload of a 1:1 forward that passed the loop, origin and exposure checks, or null after
     * logging why it is dropped. A peer may never speak for our own users here.
     */
    private Element checkedPayload(String action, String payloadName, String fromDomain, Element el) {
        String via = el.attributeValue(ATTR_VIA, "");
        if (viaContains(via, localDomain())) {
            Log.warn("{} loop detected (via={}), dropping", action, via);
            return null;
        }
        Element payloadEl = el.element(payloadName);
        if (payloadEl == null) {
            Log.warn("{} from {} has no {} payload", action, fromDomain, payloadName);
            return null;
        }
        if (!validation.payloadOriginOk(fromDomain, payloadEl.attributeValue("from"), action, true, null)) return null;
        if (!validation.exposureOk(fromDomain, el.attributeValue(ATTR_DESTINATION), action)) return null;
        return payloadEl;
    }

    /** Relays a 1:1 forward toward its destination, applying the egress gate for an untrusted next hop. */
    private void relay(String action, Element el, Element payloadEl) {
        String finalDest = el.attributeValue(ATTR_DESTINATION);
        String newVia = appendToTrail(el.attributeValue(ATTR_VIA, ""), localDomain());
        String nextHop = manager.getRoutingTable().findNextHop(finalDest).orElse(null);
        if (nextHop == null) {
            Log.warn("{}: no route to {}, dropping", action, finalDest);
            return;
        }
        FederationSender.send(action, finalDest, () -> {
            Packet embedded = oneToOneStanza(payloadEl);
            if (!manager.egressExposureOk(nextHop, embedded.getFrom(), action)) return null;
            return FederationStanzaFactory.oneToOneForward(action, nextHop, finalDest, newVia, embedded);
        });
    }

    /** A copy of a forwarded 1:1 stanza as a packet of its own type. */
    private static Packet oneToOneStanza(Element el) {
        return switch (el.getName()) {
            case "message"  -> new Message(el.createCopy());
            case "presence" -> new Presence(el.createCopy());
            case "iq"       -> new IQ(el.createCopy());
            default -> throw new IllegalArgumentException("Not a 1:1 stanza: " + el.getName());
        };
    }

    /** At debug level, logs each session of a local recipient and whether it has message carbons on. */
    private void logRecipientCarbonState(JID to) {
        if (to == null || !Log.isDebugEnabled()) return;
        if (to.getNode() == null || !XMPPServer.getInstance().isLocal(to)) return;
        try {
            Collection<ClientSession> sessions = SessionManager.getInstance().getSessions(to.getNode());
            StringBuilder sb = new StringBuilder();
            for (ClientSession s : sessions) {
                sb.append("\n    ").append(s.getAddress())
                  .append("  carbons=").append(s.isMessageCarbonsEnabled());
            }
            Log.debug("direct-forward recipient {} has {} session(s):{}", to.toBareJID(), sessions.size(), sb);
        } catch (RuntimeException e) {
            Log.debug("Could not list the sessions of {}", to, e);
        }
    }

    /**
     * Answers a vCard get for a local user from {@link VCardManager} and relays the result back over
     * the overlay; false when {@code request} is not a vCard get.
     */
    private boolean answerVCardLocally(IQ request, String fromDomain) {
        Element child = request.getChildElement();
        if (request.getType() != IQ.Type.get
                || child == null
                || !"vCard".equals(child.getName())
                || !"vcard-temp".equals(child.getNamespaceURI())) {
            return false;
        }
        JID contact = request.getTo();
        if (contact == null || contact.getNode() == null) return false;

        IQ result = IQ.createResultIQ(request);
        try {
            Element vcard = VCardManager.getInstance().getVCard(contact.getNode());
            result.setChildElement(vcard != null ? vcard.createCopy()
                                                  : DocumentHelper.createElement("vCard")
                                                        .addNamespace("", "vcard-temp"));
        } catch (RuntimeException e) {
            Log.warn("iq-forward: could not read the vCard of {}; answering with an empty one", contact, e);
            result.setChildElement("vCard", "vcard-temp");
        }
        if (manager.forwardDirectIq(result)) {
            Log.info("iq-forward: answered + relayed vCard for {} -> {} (from {})",
                     contact, request.getFrom(), fromDomain);
        } else {
            Log.warn("iq-forward: built vCard for {} but no route back to {}", contact, request.getFrom());
        }
        return true;
    }

    /**
     * Answers a PEP items get that arrived over native S2S from a contact the target user is shared
     * with, which Openfire would refuse; false, leaving it to Openfire, for anything else.
     */
    public boolean answerSharedContactPepFetch(IQ request) {
        JID from = request.getFrom();
        JID to   = request.getTo();
        if (from == null || to == null || !XMPPServer.getInstance().isLocal(to)) return false;
        if (!manager.getContactLists().isAuthorizedContact(to, from)) return false;
        return answerPepItemsLocally(request, from.getDomain());
    }

    /**
     * Answers a PEP items get for a local user straight from their PEP service, honouring the node's
     * access model, and relays the reply back over the overlay; false when it is not such a request.
     */
    private boolean answerPepItemsLocally(IQ request, String fromDomain) {
        if (request.getType() != IQ.Type.get) return false;
        Element pubsub = request.getChildElement();
        if (pubsub == null || !"pubsub".equals(pubsub.getName()) || !NS_PUBSUB.equals(pubsub.getNamespaceURI())) {
            return false;
        }
        Element itemsEl = pubsub.element("items");
        String node = itemsEl == null ? null : itemsEl.attributeValue("node");
        if (node == null) return false;

        JID contact = request.getTo();
        if (contact == null || contact.getNode() == null) return false;

        Node pepNode;
        try {
            pepNode = findPepNode(contact, node);
        } catch (RuntimeException e) {
            Log.warn("iq-forward: could not look up PEP node '{}' of {}", LogSafe.text(node), contact, e);
            return relayPepReply(pepError(request, PacketError.Condition.internal_server_error,
                    PacketError.Type.wait), contact, node, fromDomain);
        }
        if (pepNode == null) {
            // XEP-0060: a missing node is item-not-found, which lets a client fall back to the vCard avatar.
            Log.debug("iq-forward: PEP node '{}' does not exist for {} — replying item-not-found",
                      LogSafe.text(node), contact);
            return relayPepReply(pepError(request, PacketError.Condition.item_not_found,
                    PacketError.Type.cancel), contact, node, fromDomain);
        }
        if (!pepAccessAllowed(pepNode, contact, request.getFrom())) {
            Log.info("SECURITY: refusing PEP node '{}' of {} to {} — not allowed by its access model ({})",
                     LogSafe.text(node), contact, request.getFrom(), pepNode.getAccessModel().getName());
            return relayPepReply(pepError(request, PacketError.Condition.forbidden,
                    PacketError.Type.auth), contact, node, fromDomain);
        }

        IQ result = IQ.createResultIQ(request);
        Element resultItems = result.setChildElement("pubsub", NS_PUBSUB).addElement("items");
        resultItems.addAttribute("node", node);
        try {
            Element itemFilter = itemsEl.element("item");
            String wantedId = itemFilter == null ? null : itemFilter.attributeValue("id");
            PublishedItem item = wantedId != null ? pepNode.getPublishedItem(wantedId)
                                                    : pepNode.getLastPublishedItem();
            if (item != null) {
                Element itemEl = resultItems.addElement("item");
                itemEl.addAttribute("id", item.getID());
                Element payload = item.getPayload();
                if (payload != null) itemEl.add(payload.createCopy());
            }
        } catch (RuntimeException e) {
            Log.warn("iq-forward: could not read the items of PEP node '{}' of {}", LogSafe.text(node), contact, e);
            return relayPepReply(pepError(request, PacketError.Condition.internal_server_error,
                    PacketError.Type.wait), contact, node, fromDomain);
        }
        return relayPepReply(result, contact, node, fromDomain);
    }

    /** The PEP node {@code node} of a local user, or null when the user or the node does not exist. */
    private static Node findPepNode(JID owner, String node) {
        PEPServiceManager pepServiceManager = XMPPServer.getInstance().getIQPEPHandler().getServiceManager();
        PEPService pepService = pepServiceManager.getPEPService(owner.asBareJID(), false);
        if (pepService == null) return null;
        return pepService.getNodes().stream()
                .filter(n -> node.equals(n.getUniqueIdentifier().getNodeId()))
                .findFirst().orElse(null);
    }

    /**
     * Whether {@code requester} may read items of a local user's PEP node, by the node's own access
     * model; a presence node also admits subscribers this plugin tracked from relayed subscriptions.
     */
    private boolean pepAccessAllowed(Node pepNode, JID owner, JID requester) {
        if (requester == null) return false;
        try {
            var model = pepNode.getAccessModel();
            if (model.canAccessItems(pepNode, requester.asBareJID(), requester)) return true;
            return "presence".equals(model.getName())
                && manager.getPresenceRelay().isPresenceSubscriber(owner, requester);
        } catch (RuntimeException e) {
            Log.warn("iq-forward: access check on PEP node '{}' of {} failed; refusing it",
                     pepNode.getUniqueIdentifier().getNodeId(), owner, e);
            return false;
        }
    }

    /** An error reply to a PEP items get that echoes the request's child element (RFC 6120 §8.3.1). */
    private static IQ pepError(IQ request, PacketError.Condition condition, PacketError.Type type) {
        IQ err = IQ.createResultIQ(request);
        Element child = request.getChildElement();
        if (child != null) err.setChildElement(child.createCopy());
        err.setError(new PacketError(condition, type));
        return err;
    }

    /** Relays a PEP reply, result or error, back to the remote requester; always true. */
    private boolean relayPepReply(IQ reply, JID contact, String node, String fromDomain) {
        String kind = reply.getType() == IQ.Type.error ? "error" : "result";
        if (manager.forwardDirectIq(reply)) {
            Log.info("iq-forward: answered + relayed PEP node '{}' for {} -> {} as {} (from {})",
                     LogSafe.text(node), contact, reply.getTo(), kind, fromDomain);
        } else {
            Log.warn("iq-forward: built PEP {} for {} node '{}' but no route back to {}",
                     kind, contact, LogSafe.text(node), reply.getTo());
        }
        return true;
    }
}
