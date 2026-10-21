package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The federated directory's matching, and the XEP-0055 forms it reads and writes. */
class FederatedDirectoryTest {

    private static final String NS = RemoteSearchGuard.NS_SEARCH;
    private static final String S3 = "2503-xmpp.example.net";
    private static final String S4 = "2504-xmpp.example.net";

    private static ContactListManager.Contact contact(String jid, String name) {
        return new ContactListManager.Contact(jid, name);
    }

    private static Map<String, List<ContactListManager.Contact>> lists() {
        Map<String, List<ContactListManager.Contact>> lists = new TreeMap<>();
        lists.put(S3, List.of(contact("ann@" + S3, "Ann Archer"), contact("bob@" + S3, ""),
                              contact("zed@" + S3, "Zed Adams")));
        lists.put(S4, List.of(contact("anna@" + S4, "anna Brown")));
        return lists;
    }

    private static List<String> jids(FederatedDirectory.Matches m) {
        return m.shown().stream().map(FederatedDirectory.Entry::jid).toList();
    }

    @Test
    @DisplayName("search words are lowercased, split on spaces and stripped of wildcards")
    void words() {
        assertEquals(List.of("ann", "arch"), FederatedDirectory.words("  Ann  ARCH* "));
        assertEquals(List.of(), FederatedDirectory.words("*"));
        assertEquals(List.of(), FederatedDirectory.words(""));
        assertEquals(List.of(), FederatedDirectory.words(null));
    }

    @Test
    @DisplayName("a very long search is cut short, so one search stays cheap")
    void wordsCapped() {
        assertEquals(FederatedDirectory.MAX_WORDS, FederatedDirectory.words("a b c d e f g h i j k").size());
        String longWord = "x".repeat(FederatedDirectory.MAX_QUERY_LENGTH + 100);
        assertEquals(FederatedDirectory.MAX_QUERY_LENGTH, FederatedDirectory.words(longWord).get(0).length());
    }

    @Test
    @DisplayName("every word must appear in the name or the address, in any case")
    void matchesAllWords() {
        assertEquals(List.of("ann@" + S3, "anna@" + S4),
                     jids(FederatedDirectory.find(lists(), List.of("ann"), 10)));
        assertEquals(List.of("ann@" + S3),
                     jids(FederatedDirectory.find(lists(), List.of("archer", "ann"), 10)));
        assertEquals(List.of("bob@" + S3),
                     jids(FederatedDirectory.find(lists(), List.of("bob"), 10)));
        assertEquals(List.of(), jids(FederatedDirectory.find(lists(), List.of("ann", "zzz"), 10)));
    }

    @Test
    @DisplayName("part of a server's address finds everyone it shares")
    void matchesServer() {
        assertEquals(List.of("anna@" + S4), jids(FederatedDirectory.find(lists(), List.of("2504"), 10)));
    }

    @Test
    @DisplayName("no words lists everyone, by name, people without one last, each with its server")
    void everyoneSorted() {
        FederatedDirectory.Matches m = FederatedDirectory.find(lists(), List.of(), 10);

        assertEquals(List.of("ann@" + S3, "anna@" + S4, "zed@" + S3, "bob@" + S3), jids(m));
        assertEquals(S4, m.shown().get(1).server());
        assertEquals(4, m.total());
    }

    @Test
    @DisplayName("the result stops at the limit but counts every match")
    void capped() {
        List<ContactListManager.Contact> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add(contact("u" + i + "@" + S3, "User " + i));

        FederatedDirectory.Matches m = FederatedDirectory.find(Map.of(S3, many), List.of("user"), 200);

        assertEquals(200, m.shown().size());
        assertEquals(250, m.total());
    }

    @Test
    @DisplayName("the search form offers a data form and the legacy nick field, all in the search namespace")
    void searchForm() {
        Element query = FederatedDirectory.searchForm();

        assertEquals(NS, query.getNamespaceURI());
        assertEquals(NS, query.element("instructions").getNamespaceURI());
        assertEquals(NS, query.element("nick").getNamespaceURI());
        DataForm form = new DataForm(query.element(QName.get(DataForm.ELEMENT_NAME, DataForm.NAMESPACE)));
        assertEquals(DataForm.Type.form, form.getType());
        assertEquals(NS, form.getField("FORM_TYPE").getFirstValue());
        assertEquals(FormField.Type.text_single, form.getField(FederatedDirectory.FIELD_SEARCH).getType());
    }

    @Test
    @DisplayName("a submitted data form is read from its search field")
    void formWords() {
        DataForm submitted = new DataForm(DataForm.Type.submit);
        submitted.addField(FederatedDirectory.FIELD_SEARCH, null, null).addValue("Ann Arch");

        assertEquals(List.of("ann", "arch"), FederatedDirectory.formWords(submitted.getElement()));
        assertEquals(List.of(), FederatedDirectory.formWords(new DataForm(DataForm.Type.submit).getElement()));
    }

    @Test
    @DisplayName("a legacy search is read from whichever of first, last, nick and email were filled")
    void legacyWords() {
        Element query = DocumentHelper.createElement(QName.get("query", NS));
        query.addElement("first", NS).setText("Ann");
        query.addElement("email", NS).setText("example");
        query.addElement("nick", NS);

        assertEquals(List.of("ann", "example"), FederatedDirectory.legacyWords(query));
    }

    @Test
    @DisplayName("the data-form result reports address, name and server for each match")
    void formResult() {
        FederatedDirectory.Matches m = FederatedDirectory.find(lists(), List.of("ann"), 10);

        Element query = FederatedDirectory.formResult(m);

        assertEquals(NS, query.getNamespaceURI());
        Element x = query.element(QName.get(DataForm.ELEMENT_NAME, DataForm.NAMESPACE));
        assertNotNull(x.element("reported"));
        List<Element> items = x.elements("item");
        assertEquals(2, items.size());
        String firstJid = items.get(0).elements("field").stream()
                .filter(f -> "jid".equals(f.attributeValue("var")))
                .findFirst().orElseThrow().elementText("value");
        assertEquals("ann@" + S3, firstJid);
        assertNull(new DataForm(x).getTitle());
    }

    @Test
    @DisplayName("a result cut at the limit says so in its title")
    void formResultTruncated() {
        FederatedDirectory.Matches m = FederatedDirectory.find(lists(), List.of(), 2);

        DataForm form = new DataForm(FederatedDirectory.formResult(m)
                .element(QName.get(DataForm.ELEMENT_NAME, DataForm.NAMESPACE)));

        assertTrue(form.getTitle().startsWith("First 2 of 4 matches"));
    }

    @Test
    @DisplayName("the legacy result has one item per match, named in its nick")
    void legacyResult() {
        Element query = FederatedDirectory.legacyResult(FederatedDirectory.find(lists(), List.of("archer"), 10));

        List<Element> items = query.elements("item");
        assertEquals(1, items.size());
        assertEquals("ann@" + S3, items.get(0).attributeValue("jid"));
        assertEquals("Ann Archer", items.get(0).elementText("nick"));
        assertFalse(query.asXML().contains("xmlns=\"\""));
    }
}
