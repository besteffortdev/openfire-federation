package com.igniterealtime.openfire.plugin.federation.protocol;

import com.igniterealtime.openfire.plugin.federation.FederationManager;
import com.igniterealtime.openfire.plugin.federation.FederationProperties;
import com.igniterealtime.openfire.plugin.federation.LogSafe;
import org.dom4j.Attribute;
import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.util.List;
import java.util.Optional;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * The checks applied to inbound federation traffic before it is acted on, as specified in
 * {@code docs/protocol/09-validation.md}. Every failed check logs why and tells the caller to drop
 * the stanza; untrusted peers are held to the stricter rules.
 */
final class InboundValidation {

    private static final Logger Log = LoggerFactory.getLogger(InboundValidation.class);

    /** The attributes of a forwarded stanza that are logged and so checked for control characters. */
    private static final List<String> FORWARDED_STANZA_ROUTING = List.of("from", "to", "type");

    private final FederationManager manager;

    InboundValidation(FederationManager manager) {
        this.manager = manager;
    }

    /**
     * The peer domain that sent a federation IQ, or null to drop it: federation traffic must come
     * from a server's bare domain other than ours, and from an approved peer when the allowlist is on.
     */
    String senderDomain(JID sender, String action) {
        if (sender == null || sender.getNode() != null || sender.getResource() != null
                || sender.getDomain() == null || sender.getDomain().equals(localDomain())) {
            Log.warn("SECURITY: dropping federation '{}' from {} — federation traffic must come from a "
                   + "peer server's bare domain, not a user, resource, or this server", action, sender);
            return null;
        }
        String domain = sender.getDomain();
        if (FederationProperties.PEER_ALLOWLIST.getValue() && !manager.getPeerRegistry().isApproved(domain)) {
            Log.warn("SECURITY: dropping federation '{}' from non-allowlisted peer {} "
                   + "(approve it with Add peer, or set plugin.federation.peerAllowlist=false)", action, domain);
            return null;
        }
        return domain;
    }

    /**
     * Whether a routing attribute contains a control character: one of the action's own, of its
     * {@code map} children, or the from, to or type of a forwarded stanza. No valid domain or JID
     * contains one, and such a value could forge lines in the log.
     */
    static boolean hasControlCharacters(Element action) {
        for (Attribute a : action.attributes()) {
            if (containsControlCharacter(a.getValue())) return true;
        }
        for (Element child : action.elements()) {
            switch (child.getName()) {
                case "map" -> {
                    for (Attribute a : child.attributes()) {
                        if (containsControlCharacter(a.getValue())) return true;
                    }
                }
                case "message", "presence", "iq" -> {
                    for (String name : FORWARDED_STANZA_ROUTING) {
                        if (containsControlCharacter(child.attributeValue(name))) return true;
                    }
                }
                default -> { }
            }
        }
        return false;
    }

    private static boolean containsControlCharacter(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Checks the claimed sender of a relayed payload, standing in for native S2S's stream-level check.
     * A peer may never speak for our own users, and an untrusted peer only for servers behind it.
     */
    boolean payloadOriginOk(String fromDomain, String payloadFrom, String what,
                            boolean rejectLocalClaim, String claimedEntry) {
        if (payloadFrom == null || payloadFrom.isEmpty()) return true;
        boolean untrusted = manager.getPeerRegistry().isUntrusted(fromDomain);
        String domain = domainOf(payloadFrom);
        if (domain == null || domain.isEmpty()) {
            if (!untrusted) return true;
            Log.warn("SECURITY: dropping {} from untrusted peer {} — unparseable payload from '{}'",
                     what, fromDomain, LogSafe.text(payloadFrom));
            return false;
        }
        String localDomain = localDomain();
        String host = originHost(domain, fromDomain, localDomain);
        if (host.equals(localDomain) || isLocalConferenceDomain(domain)) {
            if (rejectLocalClaim || untrusted) {
                Log.warn("SECURITY: dropping {} from {} — payload from '{}' claims to originate on "
                       + "THIS server; a peer never speaks for our own users", what, fromDomain, payloadFrom);
                return false;
            }
            return true;
        }
        if (!untrusted || host.equals(fromDomain)) return true;
        Optional<String> hop = manager.getRoutingTable().findNextHop(host);
        // An origin we cannot route to sits behind an exposure-filtered edge and cannot be verified.
        if (hop.isEmpty() || hop.get().equals(fromDomain)) return true;
        if (claimedEntry != null && !claimedEntry.equals(fromDomain)
                && manager.getRoutingTable().findNextHop(claimedEntry).map(fromDomain::equals).orElse(false)) {
            Log.debug("{} from untrusted peer {}: off-route origin {} allowed — claimed entry {} "
                    + "routes via the sender (hub fan-out)", what, fromDomain, host, claimedEntry);
            return true;
        }
        Log.warn("SECURITY: dropping {} from untrusted peer {} — payload from '{}' claims origin {} "
               + "which routes via {} (forged identity from the wrong direction)",
                 what, fromDomain, payloadFrom, host, hop.get());
        return false;
    }

    /** The server a payload's domain belongs to: itself, or its parent for a component such as conference.X. */
    private String originHost(String domain, String fromDomain, String localDomain) {
        if (isKnownHost(domain, fromDomain, localDomain)) return domain;
        int dot = domain.indexOf('.');
        if (dot > 0 && dot < domain.length() - 1) {
            String parent = domain.substring(dot + 1);
            if (isKnownHost(parent, fromDomain, localDomain)) return parent;
        }
        return domain;
    }

    private boolean isKnownHost(String domain, String fromDomain, String localDomain) {
        return domain.equals(fromDomain) || domain.equals(localDomain)
            || manager.getRoutingTable().isReachable(domain);
    }

    /**
     * Whether an untrusted peer may act for {@code origin} on the control plane: only for itself or a
     * server we route through it. Trusted peers are not checked.
     */
    boolean claimedOriginOk(String fromDomain, String origin, String what) {
        if (!manager.getPeerRegistry().isUntrusted(fromDomain)) return true;
        if (origin != null && origin.equals(fromDomain)) return true;
        if (origin != null && manager.getRoutingTable().findNextHop(origin)
                                     .map(fromDomain::equals).orElse(false)) {
            return true;
        }
        Log.warn("SECURITY: dropping {} from untrusted peer {} — it claims origin {}, which is not that "
               + "peer or a server reached through it", what, fromDomain, origin);
        return false;
    }

    /** Whether a peer may send traffic for rooms on {@code serverDomain}: always for a trusted peer. */
    boolean untrustedAllowsServer(String fromDomain, String serverDomain) {
        if (!manager.getPeerRegistry().isUntrusted(fromDomain)) return true;
        return serverDomain != null && manager.getPeerRegistry().getExposedServers(fromDomain).contains(serverDomain);
    }

    /**
     * Exposure gate for traffic from an untrusted peer: the server it is headed for, the destination
     * when relaying or this server when delivering, must be exposed to that peer.
     */
    boolean exposureOk(String fromDomain, String finalDest, String what) {
        String target = isForUs(finalDest) ? localDomain() : finalDest;
        if (untrustedAllowsServer(fromDomain, target)) return true;
        Log.warn("SECURITY: dropping {} from untrusted peer {} toward non-exposed server {}",
                 what, fromDomain, target);
        return false;
    }

    /**
     * Whether a stanza in an envelope addressed to us is itself for a user, MUC service or component
     * served here. Without this check a peer could use this server to relay traffic anywhere.
     */
    boolean deliverableHere(JID to, String stanza, String fromDomain) {
        if (to == null || to.getDomain() == null || to.getDomain().isBlank()) {
            Log.warn("SECURITY: dropping {} from {} — final destination is us but the payload has no "
                   + "recipient", stanza, fromDomain);
            return false;
        }
        XMPPServer server = XMPPServer.getInstance();
        if (server.isLocal(to) || isLocalConferenceDomain(to.getDomain()) || server.matchesComponent(to)) {
            return true;
        }
        Log.warn("SECURITY: dropping {} from {} — final destination is us but the payload is "
               + "addressed to {}, which is not served here (refusing to relay it onward)",
                 stanza, fromDomain, to.getDomain());
        return false;
    }

    /** Whether {@code domain} is one of this server's MUC service domains. */
    static boolean isLocalConferenceDomain(String domain) {
        if (domain == null) return false;
        return XMPPServer.getInstance().getMultiUserChatManager()
                         .getMultiUserChatServices().stream()
                         .anyMatch(svc -> svc.getServiceDomain().equals(domain));
    }

    /** Whether a routed payload is addressed to this server: no destination, or our own domain. */
    static boolean isForUs(String destination) {
        return destination == null || localDomain().equals(destination);
    }

    /** The domain of {@code jid}, or null when it does not parse. */
    private static String domainOf(String jid) {
        try {
            return new JID(jid).getDomain();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
