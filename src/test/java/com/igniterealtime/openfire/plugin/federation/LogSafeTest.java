package com.igniterealtime.openfire.plugin.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The log-forging defence (CERT IDS03-J): any peer value ends up as one log line with no terminal escape. */
class LogSafeTest {

    /** ESC, as an ANSI sequence would start. */
    private static final String ESC = "\u001B";

    @Test
    @DisplayName("a newline from a peer cannot forge a second log line")
    void foldsNewlines() {
        String forged = "av-infected\n2026-01-01 INFO File relay: transfer completed";
        String safe = LogSafe.text(forged);

        assertFalse(safe.contains("\n"), "a forged line break survived: " + safe);
        assertEquals("av-infected?2026-01-01 INFO File relay: transfer completed", safe);
    }

    @Test
    @DisplayName("CR, tab and every other ISO control is folded too")
    void foldsEveryControlCharacter() {
        assertEquals("a?b", LogSafe.text("a\rb"));
        assertEquals("a?b", LogSafe.text("a\tb"));
        assertEquals("a??b", LogSafe.text("a\r\nb"));
        assertEquals("a?b", LogSafe.text("a\u0000b"));
        assertEquals("a?b", LogSafe.text("a\u0007b"));
        assertEquals("a?b", LogSafe.text("a\u001Bb"));
    }

    @Test
    @DisplayName("an ANSI escape cannot reach an operator's terminal")
    void stripsAnsiEscapes() {
        String safe = LogSafe.text(ESC + "[31mabc" + ESC + "[0m");

        assertFalse(safe.contains(ESC), "an escape byte survived");
        assertEquals("?[31mabc?[0m", safe);
    }

    @Test
    @DisplayName("one hostile attribute cannot become an unbounded log entry")
    void capsLength() {
        String safe = LogSafe.text("A".repeat(10_000));

        assertTrue(safe.length() < 400, "not capped: " + safe.length() + " chars");
        assertTrue(safe.endsWith("…[truncated]"), "truncation is not announced");
        assertTrue(safe.startsWith("A".repeat(300)));
    }

    @Test
    @DisplayName("clean text is passed through untouched, and without copying")
    void leavesCleanTextAlone() {
        String clean = "https://files.peer.example/relay/0123456789abcdef";

        // Same instance, not merely equal: the common case must not allocate.
        assertSame(clean, LogSafe.text(clean));
    }

    @Test
    @DisplayName("null renders as SLF4J would have rendered it")
    void handlesNull() {
        assertEquals("null", LogSafe.text(null));
    }

    @Test
    @DisplayName("unicode that is not a control character is preserved")
    void keepsRealText() {
        assertEquals("Équipe Rouge — relais", LogSafe.text("Équipe Rouge — relais"));
    }
}
