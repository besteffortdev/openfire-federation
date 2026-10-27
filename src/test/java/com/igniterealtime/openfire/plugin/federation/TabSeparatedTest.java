package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One record per line, whatever a field contains. */
class TabSeparatedTest {

    @Test
    @DisplayName("tabs, line breaks and backslashes survive a round trip on one line")
    void roundTrip() {
        String nasty = "a\tb\nc\rd\\e\\tf";
        String escaped = TabSeparated.escape(nasty);

        assertFalse(escaped.contains("\t"));
        assertFalse(escaped.contains("\n"));
        assertEquals(nasty, TabSeparated.unescape(escaped));
    }

    @Test
    @DisplayName("null is an empty field, and an unknown escape is kept as written")
    void edges() {
        assertEquals("", TabSeparated.escape(null));
        assertEquals("", TabSeparated.unescape(null));
        assertEquals("\\q", TabSeparated.unescape("\\q"));
        assertTrue(TabSeparated.unescape("trailing\\").endsWith("\\"));
    }
}
