package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.Element;
import org.dom4j.QName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.PacketError;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decisions behind refusing user searches from other servers. */
class RemoteSearchGuardTest {

    private static IQ search(IQ.Type type) {
        IQ iq = new IQ(type, "s1");
        iq.setFrom("amy@2503-xmpp.example.net/phone");
        iq.setTo("search.2501-xmpp.example.net");
        iq.setChildElement("query", RemoteSearchGuard.NS_SEARCH);
        return iq;
    }

    @Test
    @DisplayName("asking for the form or submitting it is a search request")
    void searchRequests() {
        assertTrue(RemoteSearchGuard.isSearchRequest(search(IQ.Type.get)));
        assertTrue(RemoteSearchGuard.isSearchRequest(search(IQ.Type.set)));
    }

    @Test
    @DisplayName("a search result, another namespace or an empty IQ is not")
    void notSearchRequests() {
        assertFalse(RemoteSearchGuard.isSearchRequest(search(IQ.Type.result)));
        assertFalse(RemoteSearchGuard.isSearchRequest(search(IQ.Type.error)));

        IQ vcard = new IQ(IQ.Type.get);
        vcard.setChildElement("vCard", "vcard-temp");
        assertFalse(RemoteSearchGuard.isSearchRequest(vcard));

        assertFalse(RemoteSearchGuard.isSearchRequest(new IQ(IQ.Type.get)));
    }

    @Test
    @DisplayName("an allowed server and its subdomains may search; a look-alike domain may not")
    void allowedServers() {
        Set<String> allowed = Set.of("2503-xmpp.example.net");

        assertTrue(RemoteSearchGuard.isAllowed("2503-xmpp.example.net", allowed));
        assertTrue(RemoteSearchGuard.isAllowed("gateway.2503-xmpp.example.net", allowed));

        assertFalse(RemoteSearchGuard.isAllowed("evil2503-xmpp.example.net", allowed));
        assertFalse(RemoteSearchGuard.isAllowed("example.net", allowed));
        assertFalse(RemoteSearchGuard.isAllowed("2503-xmpp.example.net", Set.of()));
    }

    @Test
    @DisplayName("the refusal is a forbidden error back to the searcher, echoing the query")
    void forbiddenReply() {
        IQ request = search(IQ.Type.set);

        IQ reply = RemoteSearchGuard.forbidden(request, "no");

        assertEquals(IQ.Type.error, reply.getType());
        assertEquals("s1", reply.getID());
        assertEquals(request.getFrom(), reply.getTo());
        assertEquals(request.getTo(), reply.getFrom());
        assertEquals(PacketError.Condition.forbidden, reply.getError().getCondition());
        assertEquals(PacketError.Type.auth, reply.getError().getType());
        Element echoed = reply.getElement().element(QName.get("query", RemoteSearchGuard.NS_SEARCH));
        assertNotNull(echoed);
    }

    @Test
    @DisplayName("the allowed-servers property is read lowercased, sorted and without blanks")
    void parseServers() {
        assertEquals(Set.of("a.example", "b.example"),
                     RemoteSearchGuard.parseServers(" B.example, ,a.example,b.example "));
        assertTrue(RemoteSearchGuard.parseServers("").isEmpty());
        assertTrue(RemoteSearchGuard.parseServers(null).isEmpty());
    }
}
