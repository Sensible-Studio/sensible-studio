package io.sensible.studio.translation.impl;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * XLIFF 1.2 for people who translate outside Sensible Studio (a translation agency, a CAT tool): one {@code <file>} per language
 * copy ({@code original} = its path), one {@code <trans-unit>} per text ({@code id} = the segment's id, {@code resname}
 * = its label) with the source and the copy's current text as target. HTML texts are kept as escaped markup.
 * XLIFF 2.0 the same way: {@code <unit name>} carries the segment's id (unit ids are NMTOKENs), one {@code <segment>};
 * on import a unit's segments (a tool may split them) are joined again.
 */
final class Xliff {

    static final String NS = "urn:oasis:names:tc:xliff:document:1.2";
    static final String NS2 = "urn:oasis:names:tc:xliff:document:2.0";

    private Xliff() {
    }

    /** One copy to export: path, title, and segments (id, label, html, source, translation). */
    static String write(String project, String sourceLanguage, String targetLanguage, List<Map<String, Object>> copies) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xliff version=\"1.2\" xmlns=\"").append(NS).append("\">\n");
        for (Map<String, Object> copy : copies) {
            sb.append("  <file original=\"").append(esc(String.valueOf(copy.get("path")))).append("\" source-language=\"")
                    .append(esc(tag(sourceLanguage))).append("\" target-language=\"").append(esc(tag(targetLanguage)))
                    .append("\" datatype=\"html\" product-name=\"Sensible Studio\" tool-id=\"studio-translation\">\n");
            sb.append("    <header>\n");
            sb.append("      <tool tool-id=\"studio-translation\" tool-name=\"Sensible Studio Translation\"/>\n");
            sb.append("      <note>").append(esc(project + " · " + copy.get("title"))).append("</note>\n");
            sb.append("    </header>\n");
            sb.append("    <body>\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segments = (List<Map<String, Object>>) copy.get("segments");
            for (Map<String, Object> s : segments) {
                Object translation = s.get("translation");
                boolean done = translation != null && !String.valueOf(translation).isEmpty() && !Boolean.TRUE.equals(s.get("failed"));
                sb.append("      <trans-unit id=\"").append(esc(String.valueOf(s.get("id")))).append("\" resname=\"")
                        .append(esc(String.valueOf(s.get("label")))).append("\" xml:space=\"preserve\"")
                        .append(Boolean.TRUE.equals(s.get("html")) ? " extype=\"html\"" : "").append(">\n");
                sb.append("        <source>").append(esc(String.valueOf(s.get("source")))).append("</source>\n");
                sb.append("        <target state=\"").append(done ? "translated" : "needs-translation").append("\">")
                        .append(done ? esc(String.valueOf(translation)) : "").append("</target>\n");
                if (Boolean.TRUE.equals(s.get("changed"))) sb.append("        <note>The source changed since it was translated</note>\n");
                sb.append("      </trans-unit>\n");
            }
            sb.append("    </body>\n");
            sb.append("  </file>\n");
        }
        sb.append("</xliff>\n");
        return sb.toString();
    }

    /** The same in XLIFF 2.0. */
    static String write2(String project, String sourceLanguage, String targetLanguage, List<Map<String, Object>> copies) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<xliff xmlns=\"").append(NS2).append("\" version=\"2.0\" srcLang=\"").append(esc(tag(sourceLanguage)))
                .append("\" trgLang=\"").append(esc(tag(targetLanguage))).append("\">\n");
        int f = 0;
        for (Map<String, Object> copy : copies) {
            sb.append("  <file id=\"f").append(++f).append("\" original=\"").append(esc(String.valueOf(copy.get("path")))).append("\">\n");
            sb.append("    <notes><note>").append(esc(project + " · " + copy.get("title"))).append("</note></notes>\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> segments = (List<Map<String, Object>>) copy.get("segments");
            int u = 0;
            for (Map<String, Object> s : segments) {
                Object translation = s.get("translation");
                boolean done = translation != null && !String.valueOf(translation).isEmpty() && !Boolean.TRUE.equals(s.get("failed"));
                sb.append("    <unit id=\"u").append(++u).append("\" name=\"").append(esc(String.valueOf(s.get("id")))).append("\">\n");
                sb.append("      <notes><note category=\"label\">").append(esc(String.valueOf(s.get("label")))).append("</note>");
                if (Boolean.TRUE.equals(s.get("html"))) sb.append("<note category=\"format\">html</note>");
                if (Boolean.TRUE.equals(s.get("changed"))) sb.append("<note>The source changed since it was translated</note>");
                sb.append("</notes>\n");
                sb.append("      <segment state=\"").append(done ? "translated" : "initial").append("\">\n");
                sb.append("        <source xml:space=\"preserve\">").append(esc(String.valueOf(s.get("source")))).append("</source>\n");
                if (done) sb.append("        <target xml:space=\"preserve\">").append(esc(String.valueOf(translation))).append("</target>\n");
                sb.append("      </segment>\n");
                sb.append("    </unit>\n");
            }
            sb.append("  </file>\n");
        }
        sb.append("</xliff>\n");
        return sb.toString();
    }

    /** A parsed file: its target language and, per copy path, the targets by unit id (empty targets left out). */
    static final class Parsed {
        final String targetLanguage;
        final Map<String, Map<String, String>> targets;

        Parsed(String targetLanguage, Map<String, Map<String, String>> targets) {
            this.targetLanguage = targetLanguage;
            this.targets = targets;
        }
    }

    static Parsed read(InputStream in) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // no DTDs or external entities: an uploaded file must not reach files or hosts
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            doc = b.parse(in);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not an XLIFF file: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        if (root == null || !"xliff".equals(root.getLocalName())) throw new IllegalArgumentException("Not an XLIFF file: the root is not <xliff>");
        String version = root.getAttribute("version");
        if (version.startsWith("2.")) return read2(root);
        if (!version.isEmpty() && !version.startsWith("1.")) throw new IllegalArgumentException("XLIFF " + version + " is not supported: use XLIFF 1.2 or 2.0");
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String target = null;
        NodeList files = root.getElementsByTagNameNS("*", "file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            String original = file.getAttribute("original");
            if (original.isEmpty()) continue;
            String tl = untag(file.getAttribute("target-language"));
            if (target == null) target = tl;
            else if (!tl.isEmpty() && !tl.equals(target)) throw new IllegalArgumentException("The file mixes target languages: " + target + " and " + tl);
            Map<String, String> units = out.computeIfAbsent(original, k -> new LinkedHashMap<>());
            NodeList tus = file.getElementsByTagNameNS("*", "trans-unit");
            for (int j = 0; j < tus.getLength(); j++) {
                Element tu = (Element) tus.item(j);
                String id = tu.getAttribute("id");
                Element t = child(tu, "target");
                if (id.isEmpty() || t == null) continue;
                String text = t.getTextContent();
                if (text != null && !text.isEmpty()) units.put(id, text);
            }
        }
        return new Parsed(target, out);
    }

    /** XLIFF 2.x: trgLang on the root; per file, units by name (or id); a unit's segments and ignorables joined. */
    private static Parsed read2(Element root) {
        String target = untag(root.getAttribute("trgLang"));
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        NodeList files = root.getElementsByTagNameNS("*", "file");
        for (int i = 0; i < files.getLength(); i++) {
            Element file = (Element) files.item(i);
            String original = file.getAttribute("original");
            if (original.isEmpty()) continue;
            Map<String, String> units = out.computeIfAbsent(original, k -> new LinkedHashMap<>());
            NodeList us = file.getElementsByTagNameNS("*", "unit");
            for (int j = 0; j < us.getLength(); j++) {
                Element unit = (Element) us.item(j);
                String id = unit.getAttribute("name").isEmpty() ? unit.getAttribute("id") : unit.getAttribute("name");
                if (id.isEmpty()) continue;
                StringBuilder text = new StringBuilder();
                boolean any = false;
                for (Node n = unit.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (!(n instanceof Element)) continue;
                    String ln = n.getLocalName();
                    if (!"segment".equals(ln) && !"ignorable".equals(ln)) continue;
                    Element t = child((Element) n, "target");
                    if (t != null) {
                        text.append(t.getTextContent());
                        if ("segment".equals(ln)) any = true;
                    } else if ("ignorable".equals(ln)) {
                        Element src = child((Element) n, "source");
                        if (src != null) text.append(src.getTextContent());
                    } else {
                        // a segment without a target: the unit is not translated
                        any = false;
                        text.setLength(0);
                        break;
                    }
                }
                if (any && text.length() > 0) units.put(id, text.toString());
            }
        }
        return new Parsed(target, out);
    }

    private static Element child(Element e, String name) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && name.equals(n.getLocalName())) return (Element) n;
        }
        return null;
    }

    /** ro → ro, pt_br → pt-BR (BCP 47, as XLIFF wants). */
    static String tag(String code) {
        if (code == null) return "";
        String[] p = code.split("[_-]");
        return p.length > 1 ? p[0] + "-" + p[1].toUpperCase(java.util.Locale.ROOT) : p[0];
    }

    /** pt-BR → pt_br (Sensible Studio's language codes). */
    static String untag(String tag) {
        return tag == null ? "" : tag.trim().replace('-', '_').toLowerCase(java.util.Locale.ROOT);
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                // a parser turns CR LF into LF: kept as a reference, the text comes back as it went
                case '\r': sb.append("&#13;"); break;
                default:
                    // characters XML 1.0 cannot hold
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
