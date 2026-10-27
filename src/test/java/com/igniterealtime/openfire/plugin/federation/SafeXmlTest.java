package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stored stanzas are parsed without entity expansion (CERT IDS17-J). */
class SafeXmlTest {

    @Test
    @DisplayName("an ordinary stanza parses")
    void parses() {
        assertEquals("hi", SafeXml.parseElement("<message xmlns='jabber:client'><body>hi</body></message>")
                                  .orElseThrow().elementText("body"));
    }

    @Test
    @DisplayName("a DOCTYPE is refused, so no entity can expand or reach outside")
    void refusesDoctype() {
        String xxe = "<?xml version='1.0'?><!DOCTYPE m [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                   + "<message><body>&x;</body></message>";
        assertTrue(SafeXml.parseElement(xxe).isEmpty());
    }

    @Test
    @DisplayName("blank or broken input is empty, not an exception")
    void blankOrBroken() {
        assertTrue(SafeXml.parseElement(null).isEmpty());
        assertTrue(SafeXml.parseElement(" ").isEmpty());
        assertTrue(SafeXml.parseElement("<message>").isEmpty());
    }
}
