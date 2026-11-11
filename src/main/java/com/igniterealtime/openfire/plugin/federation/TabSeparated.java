package com.igniterealtime.openfire.plugin.federation;

/**
 * The field escaping of the plugin's tab-separated files: a backslash, tab or line break inside a
 * field is written as {@code \\}, {@code \t}, {@code \n} or {@code \r}, so one record is always one
 * line and splits on tabs.
 */
public final class TabSeparated {

    private TabSeparated() {}

    /** {@code v} escaped for one field; null is written as an empty field. */
    public static String escape(String v) {
        if (v == null || v.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The field {@link #escape} wrote; an unknown escape is kept as written. */
    public static String unescape(String v) {
        if (v == null || v.indexOf('\\') < 0) return v == null ? "" : v;
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c != '\\' || i == v.length() - 1) {
                sb.append(c);
                continue;
            }
            char next = v.charAt(++i);
            switch (next) {
                case '\\' -> sb.append('\\');
                case 't'  -> sb.append('\t');
                case 'n'  -> sb.append('\n');
                case 'r'  -> sb.append('\r');
                default   -> sb.append('\\').append(next);
            }
        }
        return sb.toString();
    }
}
