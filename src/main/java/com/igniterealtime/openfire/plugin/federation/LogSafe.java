package com.igniterealtime.openfire.plugin.federation;

/**
 * Makes peer-supplied strings safe to write to the log (CERT IDS03-J). Control characters become
 * {@code ?}, so a value cannot forge extra log lines, and long values are cut short.
 */
public final class LogSafe {

    /** Longest value written to the log; anything longer is cut and marked. */
    private static final int MAX_CHARS = 300;

    private static final String TRUNCATION_MARKER = "…[truncated]";

    private LogSafe() {}

    /** A single-line, length-capped copy of an untrusted value; null becomes {@code "null"}. */
    public static String text(String value) {
        if (value == null) return "null";
        boolean truncated = value.length() > MAX_CHARS;
        String head = truncated ? value.substring(0, MAX_CHARS) : value;

        StringBuilder sb = null;
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
