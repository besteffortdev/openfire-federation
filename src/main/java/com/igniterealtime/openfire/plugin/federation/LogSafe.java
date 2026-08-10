package com.igniterealtime.openfire.plugin.federation;

/**
 * Renders peer-supplied strings safe to write into {@code openfire.log} (CERT IDS03-J, "do not log
 * unsanitized user input").
 *
 * <p>The concern is log forging. Anything a remote server puts in a stanza attribute reaches us
 * verbatim, and a log line is newline-delimited, so a value containing a line break can append what
 * looks like a second, genuine log entry — a fabricated {@code SECURITY:} line, or a plausible
 * "transfer completed" that never happened. XML attribute-value normalization is not a defence:
 * it folds a <em>literal</em> newline to a space, but a {@code &#10;} character reference survives
 * it intact and arrives as a real newline.
 *
 * <p>Two things are done, and only these two — this is not an escaper and the output is not meant
 * to round-trip:
 * <ul>
 *   <li>every ISO control character becomes {@code ?}, so the value can occupy exactly one line
 *       and cannot smuggle a terminal escape sequence past an operator reading with {@code cat};</li>
 *   <li>the result is capped, so a peer cannot bloat the log with one enormous attribute.</li>
 * </ul>
 *
 * <p>Substitution rather than deletion is deliberate: a stripped value looks like something the
 * peer legitimately sent, whereas {@code av-infected??2026-01-01} makes it obvious on sight that
 * the peer sent something it had no business sending.
 *
 * <p>Only for values that crossed a trust boundary. Locally-built messages and values already
 * cleaned at ingest (a file name through {@code FileRelayManager.sanitizeFileName}, a JID Openfire
 * itself parsed) do not need it, and wrapping them would only obscure where the real boundary is.
 */
public final class LogSafe {

    /**
     * Cap on a single logged value. Long enough for a real URL or clamd detail string, short enough
     * that a hostile peer cannot turn one stanza into megabytes of log.
     */
    private static final int MAX_CHARS = 300;

    private static final String TRUNCATION_MARKER = "…[truncated]";

    private LogSafe() {}

    /**
     * @param value an untrusted string, typically a stanza attribute from a peer; may be null
     * @return a single-line, length-capped rendering safe to pass as an SLF4J {@code {}} argument.
     *         Null becomes {@code "null"}, matching what SLF4J would have printed anyway.
     */
    public static String text(String value) {
        if (value == null) return "null";
        boolean truncated = value.length() > MAX_CHARS;
        String head = truncated ? value.substring(0, MAX_CHARS) : value;

        StringBuilder sb = null;                 // allocate only when there is something to change
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (Character.isISOControl(c)) {
                if (sb == null) sb = new StringBuilder(head.length() + 12).append(head, 0, i);
                sb.append('?');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        String clean = sb == null ? head : sb.toString();
        return truncated ? clean + TRUNCATION_MARKER : clean;
    }
}
