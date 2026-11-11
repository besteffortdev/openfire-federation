package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import org.dom4j.Element;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses user searches (XEP-0055) that reach this server from another one, unless the admin allowed
 * that server. Openfire's Search plugin answers any requester with every user, whatever contact lists
 * this server shares, so the check stands in front of every local search service on both arrival paths.
 */
public class RemoteSearchGuard {

    private static final Logger Log = LoggerFactory.getLogger(RemoteSearchGuard.class);

    static final String NS_SEARCH = "jabber:iq:search";

    /** Most servers whose refused searches are remembered for the admin page. */
    static final int MAX_TRACKED = 64;
    /** A refusal is logged at INFO at most this often per server. */
    private static final long LOG_EVERY_MS = 60_000L;

    /** The searches refused from one server: how many, when the last was, who sent it and to what. */
    public record Refusal(int count, long lastAt, String lastFrom, String lastTo) {}

    private final Map<String, Refusal> refusals = new ConcurrentHashMap<>();
    /** Server to when one of its refusals was last logged at INFO. */
    private final Map<String, Long> loggedAt = new ConcurrentHashMap<>();

    /** The servers whose users may search this server. */
    public Set<String> allowedServers() {
        return parseServers(FederationProperties.SEARCH_ALLOWED_SERVERS.getValue());
    }

    /** Replaces the servers whose users may search this server; their past refusals are forgotten. */
    public synchronized void setAllowedServers(Collection<String> servers) {
        Set<String> allowed = new TreeSet<>(servers);
        FederationProperties.SEARCH_ALLOWED_SERVERS.setValue(String.join(",", allowed));
        refusals.keySet().removeIf(s -> isAllowed(s, allowed));
        loggedAt.keySet().removeIf(s -> isAllowed(s, allowed));
        Log.info("User searches from other servers now allowed from: {}", allowed.isEmpty() ? "none" : allowed);
    }

    /** The searches refused per server, by server. */
    public Map<String, Refusal> refusals() {
        return new TreeMap<>(refusals);
    }

    /**
     * Whether {@code packet} is a search from another server's user that this server does not allow.
     * The sender is then answered {@code forbidden}, and the caller rejects the stanza.
     */
    public boolean refuse(Packet packet) {
        if (!(packet instanceof IQ iq) || !isSearchRequest(iq)) return false;
        JID from = iq.getFrom();
        JID to = iq.getTo();
        XMPPServer server = XMPPServer.getInstance();
        if (from == null || to == null || !server.isRemote(from) || server.isRemote(to)) return false;
        if (isAllowed(from.getDomain(), allowedServers())) return false;
        record(from, to);
        FederationSender.send("search refusal", from.toString(),
                forbidden(iq, "This server does not take user searches from your server."));
        return true;
    }

    private synchronized void record(JID from, JID to) {
        String sender = from.getDomain();
        long now = System.currentTimeMillis();
        if (!refusals.containsKey(sender) && refusals.size() >= MAX_TRACKED) forgetOldest();
        Refusal prev = refusals.get(sender);
        refusals.put(sender, new Refusal(prev == null ? 1 : prev.count() + 1, now, from.toBareJID(), to.toString()));
        Long last = loggedAt.get(sender);
        if (last == null || now - last >= LOG_EVERY_MS) {
            loggedAt.put(sender, now);
            Log.info("Refused a user search from {} to {}: {} is not allowed to search this server. Further "
                   + "refusals from it are logged at most once a minute.", from, to, sender);
        } else {
            Log.debug("Refused a user search from {} to {}", from, to);
        }
    }

    private void forgetOldest() {
        refusals.entrySet().stream()
                .min(Comparator.comparingLong(e -> e.getValue().lastAt()))
                .map(Map.Entry::getKey)
                .ifPresent(s -> {
                    refusals.remove(s);
                    loggedAt.remove(s);
                });
    }

    /** Whether {@code iq} asks a search service for its form or its results. */
    static boolean isSearchRequest(IQ iq) {
        if (iq.getType() != IQ.Type.get && iq.getType() != IQ.Type.set) return false;
        Element child = iq.getChildElement();
        return child != null && NS_SEARCH.equals(child.getNamespaceURI());
    }

    /** Whether {@code senderDomain} is one of {@code allowed}, or a subdomain of one. */
    static boolean isAllowed(String senderDomain, Set<String> allowed) {
        for (String s : allowed) {
            if (senderDomain.equals(s) || senderDomain.endsWith("." + s)) return true;
        }
        return false;
    }

    /** The {@code forbidden} answer to a request, echoing its query as RFC 6120 §8.3.1 asks. */
    static IQ forbidden(IQ request, String text) {
        IQ reply = IQ.createResultIQ(request);
        reply.setChildElement(request.getChildElement().createCopy());
        reply.setError(new PacketError(PacketError.Condition.forbidden, PacketError.Type.auth, text));
        return reply;
    }

    /** Comma-separated server domains, lowercased and sorted, without blanks. */
    static Set<String> parseServers(String csv) {
        Set<String> out = new TreeSet<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            if (!s.isBlank()) out.add(s.strip().toLowerCase());
        }
        return out;
    }
}
