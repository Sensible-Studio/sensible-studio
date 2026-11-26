/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads the simple properties of an XMP packet (RDF/XML) into JCR-style names.
 *
 * <ul>
 * <li>simple values, as attributes or elements: {@code dc:format = "image/jpeg"}</li>
 * <li>language alternatives ({@code rdf:Alt}): the {@code x-default} entry, as AEM shows it</li>
 * <li>{@code rdf:Bag} / {@code rdf:Seq}: a list, plus {@code <name>_xmpArrayType = rdf:Bag|rdf:Seq}</li>
 * </ul>
 * Structures (nested resources) are skipped. Only namespaces in {@link #PREFIXES} are kept,
 * because a JCR property can only use a registered namespace prefix.
 */
final class XmpReader {

    static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    /** Namespace URI → prefix as used in AEM-style metadata (and registered by Sensible Studio's repoinit). */
    static final Map<String, String> PREFIXES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("http://purl.org/dc/elements/1.1/", "dc");
        m.put("http://ns.adobe.com/xap/1.0/", "xmp");
        m.put("http://ns.adobe.com/xap/1.0/mm/", "xmpMM");
        m.put("http://ns.adobe.com/xap/1.0/rights/", "xmpRights");
        m.put("http://ns.adobe.com/tiff/1.0/", "tiff");
        m.put("http://ns.adobe.com/exif/1.0/", "exif");
        m.put("http://cipa.jp/exif/1.0/", "exifEX");
        m.put("http://ns.adobe.com/exif/1.0/aux/", "psAux");
        m.put("http://ns.adobe.com/photoshop/1.0/", "photoshop");
        m.put("http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/", "Iptc4xmpCore");
        m.put("http://iptc.org/std/Iptc4xmpExt/2008-02-29/", "Iptc4xmpExt");
        m.put("http://prismstandard.org/namespaces/prismusagerights/2.1/", "prismusagerights");
        m.put("http://ns.adobe.com/pdf/1.3/", "pdf");
        PREFIXES = Collections.unmodifiableMap(m);
    }

    /** Bounds on what a file can write into the repository through XMP. */
    static final int MAX_PROPERTIES = 300;
    static final int MAX_VALUE_LENGTH = 4000;
    static final int MAX_LIST_ITEMS = 200;

    private XmpReader() {
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (out.size() >= MAX_PROPERTIES && !out.containsKey(key)) {
            return;
        }
        if (value instanceof String && ((String) value).length() > MAX_VALUE_LENGTH) {
            value = ((String) value).substring(0, MAX_VALUE_LENGTH);
        }
        out.put(key, value);
    }

    /** @return properties in packet order; empty if the packet is missing or not parseable */
    static Map<String, Object> read(String packet) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (packet == null || packet.isEmpty()) {
            return out;
        }
        Document doc;
        try {
            doc = builder().parse(new InputSource(new StringReader(stripPacketWrapper(packet))));
        } catch (Exception e) {
            return out;
        }
        NodeList descriptions = doc.getElementsByTagNameNS(RDF, "Description");
        for (int d = 0; d < descriptions.getLength(); d++) {
            Element desc = (Element) descriptions.item(d);
            if (isNested(desc)) {
                continue;
            }
            NamedNodeMap attrs = desc.getAttributes();
            for (int a = 0; a < attrs.getLength(); a++) {
                Attr attr = (Attr) attrs.item(a);
                String key = key(attr.getNamespaceURI(), attr.getLocalName());
                if (key != null) {
                    put(out, key, attr.getValue());
                }
            }
            for (Node n = desc.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element) {
                    readProperty((Element) n, out);
                }
            }
        }
        return out;
    }

    private static void readProperty(Element prop, Map<String, Object> out) {
        String key = key(prop.getNamespaceURI(), prop.getLocalName());
        if (key == null || "Resource".equals(prop.getAttributeNS(RDF, "parseType"))) {
            return;
        }
        Element container = firstElement(prop);
        if (container == null) {
            String text = prop.getTextContent().trim();
            if (!text.isEmpty()) {
                put(out, key, text);
            }
            return;
        }
        if (!RDF.equals(container.getNamespaceURI())) {
            return;
        }
        switch (container.getLocalName()) {
            case "Alt": {
                String value = null;
                for (Element li : items(container)) {
                    String lang = li.getAttributeNS(XML_NS, "lang");
                    if (value == null || "x-default".equals(lang)) {
                        value = li.getTextContent().trim();
                    }
                    if ("x-default".equals(lang)) {
                        break;
                    }
                }
                if (value != null && !value.isEmpty()) {
                    put(out, key, value);
                }
                break;
            }
            case "Bag":
            case "Seq": {
                List<String> values = new ArrayList<>();
                for (Element li : items(container)) {
                    // Structures (e.g. xmpMM:History events) carry fields as attributes or children.
                    if (firstElement(li) != null || hasFieldAttributes(li)) {
                        continue;
                    }
                    String v = li.getTextContent().trim();
                    if (!v.isEmpty() && values.size() < MAX_LIST_ITEMS) {
                        values.add(v.length() > MAX_VALUE_LENGTH ? v.substring(0, MAX_VALUE_LENGTH) : v);
                    }
                }
                if (!values.isEmpty() && out.size() + 2 <= MAX_PROPERTIES) {
                    out.put(key, values);
                    out.put(key + "_xmpArrayType", "rdf:" + container.getLocalName());
                }
                break;
            }
            default:
                break;
        }
    }

    private static boolean hasFieldAttributes(Element li) {
        NamedNodeMap attrs = li.getAttributes();
        for (int a = 0; a < attrs.getLength(); a++) {
            String ns = attrs.item(a).getNamespaceURI();
            if (!XML_NS.equals(ns) && !RDF.equals(ns) && !XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(ns)) {
                return true;
            }
        }
        return false;
    }

    private static String key(String ns, String local) {
        if (ns == null || local == null) {
            return null;
        }
        String prefix = PREFIXES.get(ns);
        return prefix == null ? null : prefix + ":" + local;
    }

    private static boolean isNested(Element desc) {
        for (Node p = desc.getParentNode(); p instanceof Element; p = p.getParentNode()) {
            if (RDF.equals(p.getNamespaceURI()) && "RDF".equals(p.getLocalName())) {
                return false;
            }
            if (RDF.equals(p.getNamespaceURI()) && "Description".equals(p.getLocalName())) {
                return true;
            }
            if (!RDF.equals(p.getNamespaceURI())) {
                return true;
            }
        }
        return false;
    }

    private static Element firstElement(Element e) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> items(Element container) {
        List<Element> out = new ArrayList<>();
        for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && RDF.equals(n.getNamespaceURI()) && "li".equals(n.getLocalName())) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** {@code <?xpacket ...?>} processing instructions are fine for the parser; trailing padding is not. */
    private static String stripPacketWrapper(String packet) {
        String p = packet.replace("\u0000", "");
        int end = p.lastIndexOf('>');
        return end < 0 ? p : p.substring(0, end + 1);
    }

    /** XXE-safe parser: no DTDs, no external entities. */
    private static DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }
}
