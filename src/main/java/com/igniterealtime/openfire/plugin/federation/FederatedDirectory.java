package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.XMPPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.AbstractComponent;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static com.igniterealtime.openfire.plugin.federation.RemoteSearchGuard.NS_SEARCH;

/**
 * The federated directory: a user search service (XEP-0055) at {@code directory.<domain>} over the
 * contact lists other servers share with this one that the admin made searchable. Only local users may
 * search it, and nothing it finds is added to anyone's roster.
 */
public class FederatedDirectory extends AbstractComponent {

    private static final Logger Log = LoggerFactory.getLogger(FederatedDirectory.class);

    /** The subdomain the service is registered at. */
    public static final String SUBDOMAIN = "directory";

    /** Most matches one search returns; the result says how many there were in all. */
    static final int MAX_RESULTS = 200;
    /** Longest search text read, and most words taken from it, so one search stays cheap. */
    static final int MAX_QUERY_LENGTH = 256;
    static final int MAX_WORDS = 8;

    static final String FIELD_SEARCH = "search";
    private static final String FORM_TYPE = "FORM_TYPE";
    /** The XEP-0055 fields a client without data forms may fill; all are matched the same way. */
    private static final List<String> LEGACY_FIELDS = List.of("first", "last", "nick", "email");
    private static final String INSTRUCTIONS = "Find people on other servers by part of their name or address. "
            + "Leave it blank, or type *, to list everyone.";

    /** One searchable contact and the server that shares it. */
    record Entry(String jid, String name, String server) {}

    /** The matches of one search: the first {@link #MAX_RESULTS} in order, and how many there were in all. */
    record Matches(List<Entry> shown, int total) {}

    /** By name, people without one last, then by address. */
    private static final Comparator<Entry> BY_NAME = Comparator
            .comparing((Entry e) -> e.name().isEmpty())
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::jid);

    private final Supplier<Map<String, List<ContactListManager.Contact>>> lists;

    /** A directory over the lists {@code lists} returns, keyed by the server that shares each. */
    public FederatedDirectory(Supplier<Map<String, List<ContactListManager.Contact>>> lists) {
        this.lists = lists;
    }

    @Override
    public String getName() {
        return "Federated directory";
    }

    @Override
    public String getDescription() {
        return "Search the contacts other servers share with this one";
    }

    @Override
    protected String discoInfoIdentityCategory() {
        return "directory";
    }

    @Override
    protected String discoInfoIdentityCategoryType() {
        return "user";
    }

    @Override
    protected String[] discoInfoFeatureNamespaces() {
        return new String[] { NS_SEARCH };
    }

    @Override
    protected IQ handleIQGet(IQ iq) {
        if (!RemoteSearchGuard.isSearchRequest(iq)) return null;
        if (!fromLocalUser(iq)) return refuse(iq);
        IQ reply = IQ.createResultIQ(iq);
        reply.setChildElement(searchForm());
        return reply;
    }

    @Override
    protected IQ handleIQSet(IQ iq) {
        if (!RemoteSearchGuard.isSearchRequest(iq)) return null;
        if (!fromLocalUser(iq)) return refuse(iq);
        Element query = iq.getChildElement();
        Element form = query.element(QName.get(DataForm.ELEMENT_NAME, DataForm.NAMESPACE));
        Matches matches = find(lists.get(), form != null ? formWords(form) : legacyWords(query), MAX_RESULTS);
        IQ reply = IQ.createResultIQ(iq);
        reply.setChildElement(form != null ? formResult(matches) : legacyResult(matches));
        Log.debug("directory: search by {} matched {} contact(s)", iq.getFrom(), matches.total());
        return reply;
    }

    /** Whether the request comes from one of this server's users; the lists hold other servers' data. */
    private static boolean fromLocalUser(IQ iq) {
        JID from = iq.getFrom();
        return from != null && from.getNode() != null && XMPPServer.getInstance().isLocal(from);
    }

    private static IQ refuse(IQ iq) {
        Log.debug("directory: refused a search by {}, which is not a local user", iq.getFrom());
        return RemoteSearchGuard.forbidden(iq, "This directory is for this server's users only.");
    }

    /** The search form: a data form, plus the legacy {@code nick} field for clients without data forms. */
    static Element searchForm() {
        Element query = DocumentHelper.createElement(QName.get("query", NS_SEARCH));
        query.addElement("instructions", NS_SEARCH).setText(INSTRUCTIONS);
        query.addElement("nick", NS_SEARCH);
        DataForm form = new DataForm(DataForm.Type.form);
        form.setTitle("Federated directory");
        form.addInstruction(INSTRUCTIONS);
        form.addField(FORM_TYPE, null, FormField.Type.hidden).addValue(NS_SEARCH);
        form.addField(FIELD_SEARCH, "Name or address", FormField.Type.text_single);
        query.add(form.getElement().createCopy());
        return query;
    }

    /** The words of a submitted data form's search field. */
    static List<String> formWords(Element form) {
        FormField field = new DataForm(form).getField(FIELD_SEARCH);
        return words(field == null ? null : field.getFirstValue());
    }

    /** The words of every legacy field a client filled. */
    static List<String> legacyWords(Element query) {
        StringBuilder text = new StringBuilder();
        for (String field : LEGACY_FIELDS) {
            String value = query.elementText(field);
            if (value != null) text.append(value).append(' ');
        }
        return words(text.toString());
    }

    /** The lower-cased words of a search, without wildcards; none means everyone. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String capped = text.length() > MAX_QUERY_LENGTH ? text.substring(0, MAX_QUERY_LENGTH) : text;
        for (String w : capped.replace('*', ' ').toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isEmpty() && out.size() < MAX_WORDS) out.add(w);
        }
        return out;
    }

    /** The contacts whose name or address contains every word, sorted by name, at most {@code max}. */
    static Matches find(Map<String, List<ContactListManager.Contact>> lists, List<String> words, int max) {
        List<Entry> all = new ArrayList<>();
        lists.forEach((server, contacts) -> {
            for (ContactListManager.Contact c : contacts) {
                if (containsAll(c, words)) all.add(new Entry(c.jid(), c.name(), server));
            }
        });
        all.sort(BY_NAME);
        return new Matches(List.copyOf(all.subList(0, Math.min(max, all.size()))), all.size());
    }

    private static boolean containsAll(ContactListManager.Contact c, List<String> words) {
        String name = c.name().toLowerCase(Locale.ROOT);
        String jid = c.jid().toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (!name.contains(w) && !jid.contains(w)) return false;
        }
        return true;
    }

    /** The data-form result; its title says so when there were more matches than it lists. */
    static Element formResult(Matches matches) {
        DataForm form = new DataForm(DataForm.Type.result);
        if (matches.total() > matches.shown().size()) {
            form.setTitle("First " + matches.shown().size() + " of " + matches.total()
                    + " matches. Type more of the name to narrow the search.");
        }
        form.addField(FORM_TYPE, null, FormField.Type.hidden).addValue(NS_SEARCH);
        form.addReportedField("jid", "Address", FormField.Type.jid_single);
        form.addReportedField("name", "Name", FormField.Type.text_single);
        form.addReportedField("server", "Server", FormField.Type.text_single);
        for (Entry e : matches.shown()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jid", e.jid());
            item.put("name", e.name());
            item.put("server", e.server());
            form.addItemFields(item);
        }
        Element query = DocumentHelper.createElement(QName.get("query", NS_SEARCH));
        query.add(form.getElement().createCopy());
        return query;
    }

    /** The legacy result: one {@code <item jid=…>} per match, with the name as its {@code nick}. */
    static Element legacyResult(Matches matches) {
        Element query = DocumentHelper.createElement(QName.get("query", NS_SEARCH));
        for (Entry e : matches.shown()) {
            Element item = query.addElement("item", NS_SEARCH);
            item.addAttribute("jid", e.jid());
            item.addElement("nick", NS_SEARCH).setText(e.name());
        }
        return query;
    }
}
