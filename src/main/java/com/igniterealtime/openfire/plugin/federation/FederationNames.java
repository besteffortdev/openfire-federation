package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.XMPPServer;

/**
 * The local domain and the syntax checks applied to domains and JIDs that come from admins, the
 * config file or peers before they are stored, routed on or rendered.
 */
public final class FederationNames {

    /** RFC 7622 limit on the length of a JID. */
    private static final int MAX_JID_LENGTH = 3071;

    /** RFC 1035 limit on the length of a domain name. */
    private static final int MAX_DOMAIN_LENGTH = 253;

    private FederationNames() {}

    /** This server's XMPP domain. */
    public static String localDomain() {
        return XMPPServer.getInstance().getServerInfo().getXMPPDomain();
    }

    /**
     * Whether {@code d} is a plausible lowercase domain: letters, digits, dots, hyphens and
     * underscores only. Not full hostname validation; single-label names are allowed.
     */
    public static boolean isPlausibleDomain(String d) {
        if (d == null || d.isEmpty() || d.length() > MAX_DOMAIN_LENGTH) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                       || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Whether {@code jid} is safe to cache, route on and render: no control characters, spaces or
     * HTML-significant characters, and a node or a dot. Not full RFC 7622 validation.
     */
    public static boolean isSafeJid(String jid) {
        if (jid == null || jid.isEmpty() || jid.length() > MAX_JID_LENGTH) return false;
        for (int i = 0; i < jid.length(); i++) {
            char c = jid.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
            if (c == '"' || c == '\'' || c == '<' || c == '>'
                    || c == '&' || c == '\\' || c == ' ') return false;
        }
        return jid.indexOf('@') > 0 || jid.indexOf('.') > 0;
    }
}
