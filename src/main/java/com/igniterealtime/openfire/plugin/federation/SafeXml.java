package com.igniterealtime.openfire.plugin.federation;

import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.xml.sax.SAXException;

import java.io.StringReader;
import java.util.Optional;

/**
 * XML parsing hardened against external entities (CERT IDS17-J): a DOCTYPE is refused outright,
 * which rules out external entities and entity expansion together.
 */
public final class SafeXml {

    private SafeXml() {}

    /** A {@link SAXReader} that refuses DOCTYPEs and external entities. */
    public static SAXReader hardenedReader() throws SAXException {
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return reader;
    }

    /** The root element of {@code xml}, or empty when it is blank or does not parse. */
    public static Optional<Element> parseElement(String xml) {
        if (xml == null || xml.isBlank()) return Optional.empty();
        try {
            return Optional.of(hardenedReader().read(new StringReader(xml)).getRootElement());
        } catch (DocumentException | SAXException e) {
            return Optional.empty();
        }
    }
}
