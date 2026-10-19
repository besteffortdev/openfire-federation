package com.igniterealtime.openfire.plugin.federation.protocol;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.xmpp.packet.JID;

/**
 * Parses and builds XEP-0425 moderation announcements for mapped rooms. A moderation crosses the
 * federation only for a message written by a user of the moderating room's own server, so no server
 * can remove another server's messages.
 */
public final class RoomModeration {

    public static final String NS_RETRACT  = "urn:xmpp:message-retract:1";
    public static final String NS_MODERATE = "urn:xmpp:message-moderate:1";
    static final String NS_MODERATE_0      = "urn:xmpp:message-moderate:0";
    static final String NS_FASTEN          = "urn:xmpp:fasten:0";
    static final String NS_DELAY           = "urn:xmpp:delay";
    /** Longest reason relayed; a longer one is cut. */
    static final int MAX_REASON = 1_024;

    private RoomModeration() { }

    /** The {@code <retract>} of a moderation announcement, or null if {@code msg} is not one. */
    public static Element moderatedRetract(Element msg) {
        Element retract = msg.element(QName.get("retract", NS_RETRACT));
        if (retract == null || retract.element(QName.get("moderated", NS_MODERATE)) == null) return null;
        String id = retract.attributeValue("id");
        return (id == null || id.isBlank()) ? null : retract;
    }

    /** Whether {@code msg} carries a XEP-0203 delay, as a moderation replayed to a late joiner does. */
    public static boolean isDelayed(Element msg) {
        return msg.element(QName.get("delay", NS_DELAY)) != null;
    }

    /** The reason text of a moderation's {@code <retract>}, trimmed and length-capped, or null. */
    public static String reasonOf(Element retract) {
        Element r = retract.element(QName.get("reason", NS_RETRACT));
        if (r == null) return null;
        String text = r.getTextTrim();
        if (text.isEmpty()) return null;
        return text.length() > MAX_REASON ? text.substring(0, MAX_REASON) : text;
    }

    /** A moderation announcement from {@code roomJid} retracting {@code targetId}; {@code by} may be null. */
    public static Element announcement(String roomJid, String msgId, String targetId,
                                       String by, String occupantId, String reason) {
        Element msg = DocumentHelper.createElement(QName.get("message", "jabber:client"));
        msg.addAttribute("type", "groupchat");
        msg.addAttribute("from", roomJid);
        msg.addAttribute("to", roomJid);
        if (msgId != null) msg.addAttribute("id", msgId);
        Element retract = msg.addElement(QName.get("retract", NS_RETRACT)).addAttribute("id", targetId);
        Element moderated = retract.addElement(QName.get("moderated", NS_MODERATE));
        if (by != null) moderated.addAttribute("by", by);
        if (occupantId != null) {
            moderated.addElement(QName.get("occupant-id", MucTrafficHandler.NS_OCCUPANT_ID))
                     .addAttribute("id", occupantId);
        }
        if (reason != null) retract.addElement(QName.get("reason", NS_RETRACT)).setText(reason);
        return msg;
    }

    /** Removes every moderation marker from an ordinary relayed message, so clients never act on one. */
    public static void stripModeration(Element msg) {
        for (Element retract : msg.elements(QName.get("retract", NS_RETRACT))) {
            for (Element m : retract.elements(QName.get("moderated", NS_MODERATE))) retract.remove(m);
        }
        for (Element applyTo : msg.elements(QName.get("apply-to", NS_FASTEN))) {
            for (Element m : applyTo.elements(QName.get("moderated", NS_MODERATE_0))) applyTo.remove(m);
        }
    }

    /** Whether {@code user}'s domain is the room's service domain or its parent ({@code conference.home → home}). */
    public static boolean belongsToRoomServer(String user, String roomJid) {
        try {
            String userDomain = new JID(user).getDomain();
            String roomDomain = new JID(roomJid).getDomain();
            int dot = roomDomain.indexOf('.');
            return roomDomain.equals(userDomain)
                || (dot > 0 && roomDomain.substring(dot + 1).equals(userDomain));
        } catch (IllegalArgumentException notAJid) {
            // Null or unparseable: belongs to no server.
            return false;
        }
    }
}
