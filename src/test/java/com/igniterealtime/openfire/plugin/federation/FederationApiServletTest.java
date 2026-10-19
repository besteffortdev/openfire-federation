package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The admin API's JSON replies, whose escaping keeps a hostile string from breaking the reply. */
class FederationApiServletTest {

    @Test
    @DisplayName("quotes, backslashes and line breaks are escaped")
    void escapesJsonSpecials() {
        assertEquals("a\\\"b\\\\c\\nd\\re\\tf", FederationApiServlet.esc("a\"b\\c\nd\re\tf"));
    }

    @Test
    @DisplayName("every other control character becomes a \\u escape")
    void escapesRemainingControls() {
        assertEquals("a\\u0000b\\u001bc", FederationApiServlet.esc("a\u0000b\u001Bc"));
    }

    @Test
    @DisplayName("null escapes to the empty string, and clean text is unchanged")
    void escapeEdgeCases() {
        assertEquals("", FederationApiServlet.esc(null));
        assertEquals("Équipe — salle", FederationApiServlet.esc("Équipe — salle"));
    }

    @Test
    @DisplayName("a peer-supplied string cannot close the error message early")
    void errorQuotesSafely() {
        String reply = FederationApiServlet.error("no such room: x\",\"ok\":true,\"y\":\"");

        assertEquals("{\"error\":\"no such room: x\\\",\\\"ok\\\":true,\\\"y\\\":\\\"\"}", reply);
        assertFalse(FederationApiServlet.isOk(reply), "an injected ok:true must not read as success");
    }

    @Test
    @DisplayName("the reply shapes the page relies on")
    void replyShapes() {
        assertEquals("{\"ok\":true,\"n\":5}", FederationApiServlet.ok("n", 5));
        assertEquals("{\"ok\":true,\"path\":\"C:\\\\x\"}", FederationApiServlet.okText("path", "C:\\x"));
        assertEquals("{\"error\":\"a and b required\"}", FederationApiServlet.required("a", "b"));
    }

    @Test
    @DisplayName("isOk tells success from failure")
    void isOkGatesSideEffects() {
        assertTrue(FederationApiServlet.isOk("{\"ok\":true}"));
        assertTrue(FederationApiServlet.isOk(FederationApiServlet.ok("n", 1)));
        assertFalse(FederationApiServlet.isOk(FederationApiServlet.error("nope")));
    }

    @Test
    @DisplayName("a held poll wakes when a duplicate warning appears, not on every dropped copy")
    void fingerprintIgnoresDuplicateCountsButNotTheFlag() {
        String quiet   = "{\"mappings\":[{\"duplicates\":true,\"dupCount\":3,"
                       + "\"dupSinceAgeSecs\":90,\"dupLastAgeSecs\":2}]}";
        String busier  = "{\"mappings\":[{\"duplicates\":true,\"dupCount\":41,"
                       + "\"dupSinceAgeSecs\":95,\"dupLastAgeSecs\":0}]}";
        String cleared = "{\"mappings\":[{\"duplicates\":false,\"dupCount\":0,"
                       + "\"dupSinceAgeSecs\":-1,\"dupLastAgeSecs\":-1}]}";

        assertEquals(FederationApiServlet.fingerprint(quiet), FederationApiServlet.fingerprint(busier));
        assertFalse(FederationApiServlet.fingerprint(quiet).equals(FederationApiServlet.fingerprint(cleared)));
    }
}
