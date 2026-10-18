package com.igniterealtime.openfire.plugin.federation.protocol;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inbound gate that drops a federation action whose routing values could forge log lines. */
class InboundValidationTest {

    private static Element mucForward() {
        Element action = DocumentHelper.createElement("muc-forward");
        action.addAttribute("destination", "2503-xmpp.example.net");
        action.addAttribute("via", "2501-xmpp.example.net");
        Element message = action.addElement("message");
        message.addAttribute("from", "lobby@conference.2501-xmpp.example.net/alice");
        message.addAttribute("to", "lobby@conference.2503-xmpp.example.net");
        message.addAttribute("type", "groupchat");
        return action;
    }

    @Test
    @DisplayName("an ordinary forward passes")
    void cleanActionPasses() {
        assertFalse(InboundValidation.hasControlCharacters(mucForward()));
    }

    @Test
    @DisplayName("a line break in one of the action's own attributes is caught")
    void actionAttribute() {
        Element action = mucForward();
        action.addAttribute("via", "2501-xmpp.example.net\nWARN SECURITY: forged line");

        assertTrue(InboundValidation.hasControlCharacters(action));
    }

    @Test
    @DisplayName("a control character in a map entry is caught")
    void mapAttribute() {
        Element action = DocumentHelper.createElement("room-unmapping");
        action.addElement("map").addAttribute("localRoom", "lobby@conference.peer.example\r");

        assertTrue(InboundValidation.hasControlCharacters(action));
    }

    @Test
    @DisplayName("a control character in a forwarded stanza's from, to or type is caught")
    void forwardedStanzaRouting() {
        for (String attribute : new String[] {"from", "to", "type"}) {
            Element action = mucForward();
            action.element("message").addAttribute(attribute, "x\u001b[31m");

            assertTrue(InboundValidation.hasControlCharacters(action), attribute);
        }
    }

    @Test
    @DisplayName("line breaks in content are left alone: message bodies and room descriptions may have them")
    void contentIsNotChecked() {
        Element forward = mucForward();
        forward.element("message").addElement("body").setText("first line\nsecond line");

        Element advertisement = DocumentHelper.createElement("room-advertisement");
        advertisement.addElement("room").addAttribute("description", "Team room\nAll welcome");

        assertFalse(InboundValidation.hasControlCharacters(forward));
        assertFalse(InboundValidation.hasControlCharacters(advertisement));
    }
}
