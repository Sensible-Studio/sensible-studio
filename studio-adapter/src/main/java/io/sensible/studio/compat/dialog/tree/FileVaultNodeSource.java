package io.sensible.studio.compat.dialog.tree;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link NodeSource} over one or more unpacked FileVault {@code jcr_root} folders.
 *
 * <p>Implements the parts of the FileVault serialization that component dialogs use: folders as nodes,
 * {@code .content.xml} and full-coverage {@code *.xml} docview files, platform name escaping
 * ({@code _cq_dialog} → {@code cq:dialog}) and typed property values ({@code {Boolean}true}, {@code [a,b]}).
 * Binary files become bare {@code nt:file} nodes. Roots are layered in the order given; later roots add to and
 * override earlier ones, like installing packages one after the other.</p>
 */
public final class FileVaultNodeSource implements NodeSource {

    private static final Pattern NAMESPACE_ESCAPE = Pattern.compile("^_([A-Za-z0-9]+)_(.+)$");
    private static final Pattern XML_NAME_ESCAPE = Pattern.compile("_x([0-9A-Fa-f]{4})_");
    private static final Pattern TYPE_HINT = Pattern.compile("^\\{([A-Za-z]+)\\}(.*)$", Pattern.DOTALL);

    private final DialogNode root = new DialogNode("");
    private final List<String> searchPaths;

    public FileVaultNodeSource(List<Path> jcrRoots) throws IOException {
        this(jcrRoots, Arrays.asList("/apps/", "/libs/"));
    }

    public FileVaultNodeSource(List<Path> jcrRoots, List<String> searchPaths) throws IOException {
        this.searchPaths = searchPaths;
        for (Path jcrRoot : jcrRoots) {
            readFolder(jcrRoot, root);
        }
    }

    @Override
    public DialogNode load(String absolutePath) {
        DialogNode node = root.getDescendant(absolutePath.startsWith("/") ? absolutePath.substring(1) : absolutePath);
        return node == null ? null : node.deepCopy();
    }

    @Override
    public List<String> searchPaths() {
        return searchPaths;
    }

    // ------------------------------------------------------------------------------------------------------

    private void readFolder(Path folder, DialogNode target) throws IOException {
        Path content = folder.resolve(".content.xml");
        if (Files.isRegularFile(content)) {
            Element docRoot = parse(content);
            if (docRoot != null) mergeElement(docRoot, target);
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path p : stream) entries.add(p);
        }
        entries.sort(null);
        for (Path entry : entries) {
            String fileName = entry.getFileName().toString();
            if (fileName.startsWith(".")) continue; // .content.xml handled above, .DS_Store & co. ignored
            if (Files.isDirectory(entry)) {
                String name = decodeFileName(fileName);
                DialogNode child = childOrCreate(target, name);
                readFolder(entry, child);
            } else if (fileName.endsWith(".xml")) {
                Element docRoot = parse(entry);
                if (docRoot != null && "jcr:root".equals(docRoot.getNodeName())) {
                    String name = decodeFileName(fileName.substring(0, fileName.length() - 4));
                    mergeElement(docRoot, childOrCreate(target, name));
                } else {
                    childOrCreate(target, decodeFileName(fileName)).put("jcr:primaryType", "nt:file");
                }
            } else {
                DialogNode file = childOrCreate(target, decodeFileName(fileName));
                if (file.get("jcr:primaryType") == null) file.put("jcr:primaryType", "nt:file");
            }
        }
    }

    private static DialogNode childOrCreate(DialogNode parent, String name) {
        DialogNode child = parent.getChild(name);
        if (child == null) {
            child = new DialogNode(name);
            parent.addChild(child);
        }
        return child;
    }

    private static void mergeElement(Element element, DialogNode target) {
        NamedNodeMap attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String attrName = attr.getNodeName();
            if (attrName.equals("xmlns") || attrName.startsWith("xmlns:")) continue;
            target.put(decodeXmlName(attrName), parseValue(attr.getNodeValue()));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element childElement = (Element) n;
            String name = decodeXmlName(childElement.getNodeName());
            mergeElement(childElement, childOrCreate(target, name));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // value and name decoding

    static Object parseValue(String raw) {
        if (raw == null) return null;
        String type = null;
        String value = raw;
        Matcher m = TYPE_HINT.matcher(raw);
        if (m.matches()) {
            type = m.group(1);
            value = m.group(2);
        } else if (raw.startsWith("\\{")) {
            value = raw.substring(1);
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            List<String> parts = splitArray(value.substring(1, value.length() - 1));
            Object[] out = new Object[parts.size()];
            for (int i = 0; i < parts.size(); i++) out[i] = convert(type, parts.get(i));
            return out;
        }
        return convert(type, unescape(value));
    }

    /** DocView escapes backslashes (and a leading [ or {) in single values with a backslash. */
    static String unescape(String value) {
        if (value.indexOf('\\') < 0) return value;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) c = value.charAt(++i);
            sb.append(c);
        }
        return sb.toString();
    }

    private static List<String> splitArray(String body) {
        List<String> parts = new ArrayList<>();
        if (body.isEmpty()) return parts;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                current.append(body.charAt(++i));
            } else if (c == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static Object convert(String type, String value) {
        if (type == null) return value;
        try {
            switch (type) {
                case "Boolean":
                    return Boolean.parseBoolean(value);
                case "Long":
                    return Long.parseLong(value.trim());
                case "Double":
                case "Decimal":
                    return Double.parseDouble(value.trim());
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }

    static String decodeFileName(String fileName) {
        String name = fileName;
        try {
            name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            // keep raw name
        }
        if (name.startsWith("__")) return name.substring(1);
        Matcher m = NAMESPACE_ESCAPE.matcher(name);
        if (m.matches()) return m.group(1) + ":" + m.group(2);
        return name;
    }

    static String decodeXmlName(String xmlName) {
        Matcher m = XML_NAME_ESCAPE.matcher(xmlName);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Element parse(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(in);
            return doc.getDocumentElement();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            return null; // not a docview file
        }
    }
}
