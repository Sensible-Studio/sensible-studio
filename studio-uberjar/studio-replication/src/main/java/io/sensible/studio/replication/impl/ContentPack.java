/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.nodetype.NodeType;

import org.apache.jackrabbit.util.ISO8601;

/**
 * What one replication request carries: a zip with {@code content.json} (the node tree with typed
 * properties, in document order) and {@code bin/<n>} entries for binary properties. The format is
 * Sensible Studio's own; both ends are Sensible Studio.
 *
 * <pre>
 * { "path": "/content/site/en", "action": "Activate", "keepChildPages": true,
 *   "ancestors": [{"path":"/content","type":"sling:Folder"}, {"path":"/content/site","type":"cq:Page"}],
 *   "node": { "name":"en", "type":"cq:Page", "mixins":["cq:ReplicationStatus"],
 *             "props": { "jcr:title": {"t":"String","v":"English"}, "tags": {"t":"String","m":["a","b"]} },
 *             "children": [ … ] } }
 * </pre>
 */
public final class ContentPack {

    public static final String CONTENT_ENTRY = "content.json";
    public static final String BIN_PREFIX = "bin/";

    /** Properties the repository manages: never carried, never written. */
    static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:primaryType", "jcr:mixinTypes", "jcr:versionHistory",
            "jcr:baseVersion", "jcr:predecessors", "jcr:isCheckedOut", "jcr:frozenPrimaryType", "jcr:frozenMixinTypes",
            "jcr:frozenUuid", "jcr:lockOwner", "jcr:lockIsDeep", "rep:policy", "studio:lockOwner"));
    /** Mixins that only make sense on the author. */
    static final Set<String> SKIPPED_MIXINS = new HashSet<>(Arrays.asList(
            "mix:versionable", "mix:simpleVersionable", "rep:AccessControllable", "mix:lockable"));
    static final Set<String> SKIPPED_NODES = new HashSet<>(Arrays.asList("rep:policy", "rep:cugPolicy", "cq:annotations"));

    private ContentPack() {
    }

    // ------------------------------------------------------------------ writing

    /**
     * Packs the node at {@code path}. Pages: child pages are left out unless {@code deep}; everything
     * else (assets, folders, configurations) always goes whole.
     */
    public static byte[] pack(Session session, String path, String action, boolean deep) throws RepositoryException, IOException {
        Node node = session.getNode(path);
        boolean page = node.isNodeType("cq:Page");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            List<byte[]> binaries = new ArrayList<>();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("path", path);
            root.put("action", action);
            root.put("keepChildPages", page && !deep);
            root.put("ancestors", ancestors(node));
            Map<String, Object> written = write(node, page && !deep, binaries, true);
            root.put("node", written);
            // the namespaces the names use (stRef, xmpMM… from imported packages): the receiver registers
            // the ones it does not know, or Oak refuses the commit with "Invalid namespace prefix"
            root.put("namespaces", namespaces(session, written));
            zip.putNextEntry(new ZipEntry(CONTENT_ENTRY));
            zip.write(Json.write(root).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i < binaries.size(); i++) {
                zip.putNextEntry(new ZipEntry(BIN_PREFIX + i));
                zip.write(binaries.get(i));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** prefix → URI for every prefixed name in the written tree (node and property names). */
    @SuppressWarnings("unchecked")
    static Map<String, String> namespaces(Session session, Map<String, Object> node) throws RepositoryException {
        Map<String, String> out = new java.util.TreeMap<>();
        java.util.Set<String> prefixes = new java.util.HashSet<>();
        collectPrefixes(node, prefixes);
        for (String prefix : prefixes) {
            try {
                out.put(prefix, session.getNamespaceURI(prefix));
            } catch (RepositoryException e) {
                // a name with a colon that is not a namespace prefix: nothing to carry
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collectPrefixes(Map<String, Object> node, java.util.Set<String> prefixes) {
        prefix(String.valueOf(node.get("name")), prefixes);
        prefix(String.valueOf(node.get("type")), prefixes);
        Object mixins = node.get("mixins");
        if (mixins instanceof List) {
            for (Object m : (List<Object>) mixins) {
                prefix(String.valueOf(m), prefixes);
            }
        }
        Object props = node.get("props");
        if (props instanceof Map) {
            for (String k : ((Map<String, Object>) props).keySet()) {
                prefix(k, prefixes);
            }
        }
        Object children = node.get("children");
        if (children instanceof List) {
            for (Object c : (List<Object>) children) {
                if (c instanceof Map) {
                    collectPrefixes((Map<String, Object>) c, prefixes);
                }
            }
        }
    }

    private static void prefix(String name, java.util.Set<String> prefixes) {
        int i = name.indexOf(':');
        if (i > 0 && !name.startsWith("{")) {
            prefixes.add(name.substring(0, i));
        }
    }

    /** Registers the pack's namespaces this repository does not have (the author's imported packages brought them). */
    @SuppressWarnings("unchecked")
    public static void registerNamespaces(Session session, Unpacked pack) throws RepositoryException {
        Object ns = pack.header.get("namespaces");
        if (!(ns instanceof Map)) {
            return;
        }
        javax.jcr.NamespaceRegistry registry = session.getWorkspace().getNamespaceRegistry();
        java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(registry.getPrefixes()));
        for (Map.Entry<String, Object> e : ((Map<String, Object>) ns).entrySet()) {
            if (!known.contains(e.getKey())) {
                registry.registerNamespace(e.getKey(), String.valueOf(e.getValue()));
            }
        }
    }

    private static List<Map<String, Object>> ancestors(Node node) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Node n = node.getDepth() > 1 ? node.getParent() : null; n != null && n.getDepth() > 0; n = n.getDepth() > 1 ? n.getParent() : null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", n.getPath());
            m.put("type", n.getPrimaryNodeType().getName());
            out.add(0, m);
        }
        return out;
    }

    private static Map<String, Object> write(Node node, boolean skipChildPages, List<byte[]> binaries, boolean root)
            throws RepositoryException, IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", node.getName());
        m.put("type", node.getPrimaryNodeType().getName());
        List<String> mixins = new ArrayList<>();
        for (NodeType t : node.getMixinNodeTypes()) {
            if (!SKIPPED_MIXINS.contains(t.getName())) {
                mixins.add(t.getName());
            }
        }
        m.put("mixins", mixins);
        Map<String, Object> props = new LinkedHashMap<>();
        for (PropertyIterator it = node.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (PROTECTED.contains(p.getName()) || p.getDefinition().isProtected()) {
                continue;
            }
            props.put(p.getName(), writeProperty(p, binaries));
        }
        m.put("props", props);
        // the closed user group travels as the names of its principals (see CugTransfer); absent when
        // this session cannot tell, and then the receiving side keeps what it has
        List<String> cug = CugTransfer.read(node);
        if (cug != null) {
            m.put(CugTransfer.KEY, cug);
        }
        List<Object> children = new ArrayList<>();
        for (NodeIterator it = node.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (SKIPPED_NODES.contains(c.getName())) {
                continue;
            }
            if (skipChildPages && c.isNodeType("cq:Page")) {
                continue;
            }
            children.add(write(c, skipChildPages, binaries, false));
        }
        m.put("children", children);
        return m;
    }

    private static Map<String, Object> writeProperty(Property p, List<byte[]> binaries) throws RepositoryException, IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        String type = PropertyType.nameFromValue(p.getType());
        m.put("t", type);
        if (p.isMultiple()) {
            List<Object> values = new ArrayList<>();
            for (Value v : p.getValues()) {
                values.add(valueOf(v, binaries));
            }
            m.put("m", values);
        } else {
            m.put("v", valueOf(p.getValue(), binaries));
        }
        return m;
    }

    private static Object valueOf(Value v, List<byte[]> binaries) throws RepositoryException, IOException {
        switch (v.getType()) {
            case PropertyType.BINARY: {
                Binary b = v.getBinary();
                try (InputStream in = b.getStream()) {
                    binaries.add(readAll(in));
                } finally {
                    b.dispose();
                }
                return binaries.size() - 1;
            }
            case PropertyType.DATE:
                return ISO8601.format(v.getDate());
            case PropertyType.BOOLEAN:
                return v.getBoolean();
            case PropertyType.LONG:
                return v.getLong();
            case PropertyType.DOUBLE:
                return v.getDouble();
            default:
                return v.getString();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ reading

    /** A pack read back: its header and the binaries. */
    public static final class Unpacked {
        public final Map<String, Object> header;
        public final List<byte[]> binaries;

        Unpacked(Map<String, Object> header, List<byte[]> binaries) {
            this.header = header;
            this.binaries = binaries;
        }

        public String path() {
            return String.valueOf(header.get("path"));
        }

        public String action() {
            return String.valueOf(header.get("action"));
        }
    }

    @SuppressWarnings("unchecked")
    public static Unpacked unpack(InputStream in) throws IOException {
        Map<String, Object> header = null;
        Map<Integer, byte[]> bins = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                byte[] data = readAll(zip);
                if (CONTENT_ENTRY.equals(e.getName())) {
                    header = (Map<String, Object>) Json.parse(new String(data, StandardCharsets.UTF_8));
                } else if (e.getName().startsWith(BIN_PREFIX)) {
                    bins.put(Integer.parseInt(e.getName().substring(BIN_PREFIX.length())), data);
                }
            }
        }
        if (header == null) {
            throw new IOException("Not a content pack: no " + CONTENT_ENTRY);
        }
        List<byte[]> list = new ArrayList<>();
        for (int i = 0; i < bins.size(); i++) {
            list.add(bins.get(i));
        }
        return new Unpacked(header, list);
    }

    /**
     * Installs a pack: missing ancestors are created with their type, the node is replaced by the
     * pack's (child pages of a page are kept when the pack says so), children end up in pack order.
     * Nothing is saved.
     */
    @SuppressWarnings("unchecked")
    public static void install(Session session, Unpacked pack) throws RepositoryException {
        registerNamespaces(session, pack);
        String path = pack.path();
        boolean keepChildPages = Boolean.TRUE.equals(pack.header.get("keepChildPages"));
        for (Map<String, Object> a : (List<Map<String, Object>>) pack.header.get("ancestors")) {
            String ap = String.valueOf(a.get("path"));
            if (!session.nodeExists(ap)) {
                Node parent = session.getNode(parentPath(ap));
                parent.addNode(name(ap), String.valueOf(a.get("type")));
            }
        }
        Map<String, Object> node = (Map<String, Object>) pack.header.get("node");
        Node parent = session.getNode(parentPath(path));
        writeNode(session, parent, node, keepChildPages, pack.binaries, true);
    }

    /**
     * Removes the node at {@code path} (deactivation). Nothing is saved. Folders and pages that {@link #install} made
     * only to hold it (bare ancestors: a type and nothing else) go with their last content, so that a folder deleted
     * on the author does not stay behind here as an empty one.
     */
    public static boolean remove(Session session, String path) throws RepositoryException {
        if (!session.nodeExists(path)) {
            return false;
        }
        Node node = session.getNode(path);
        Node parent = node.getParent();
        node.remove();
        while (parent != null && parent.getDepth() >= 3 && isShell(parent)) {
            Node up = parent.getParent();
            parent.remove();
            parent = up;
        }
        return true;
    }

    private static final java.util.Set<String> SHELL_TYPES = new java.util.HashSet<>(
            java.util.Arrays.asList("cq:Page", "sling:Folder", "sling:OrderedFolder", "nt:folder"));

    /** A bare ancestor: nothing below it, and nothing on it but what the repository writes itself. */
    static boolean isShell(Node n) throws RepositoryException {
        if (n.hasNodes() || !SHELL_TYPES.contains(n.getPrimaryNodeType().getName())) {
            return false;
        }
        for (javax.jcr.PropertyIterator it = n.getProperties(); it.hasNext();) {
            String name = it.nextProperty().getName();
            if (!"jcr:primaryType".equals(name) && !"jcr:created".equals(name) && !"jcr:createdBy".equals(name)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static void writeNode(Session session, Node parent, Map<String, Object> m, boolean keepChildPages,
            List<byte[]> binaries, boolean root) throws RepositoryException {
        String name = String.valueOf(m.get("name"));
        String type = String.valueOf(m.get("type"));
        Node node;
        if (parent.hasNode(name)) {
            node = parent.getNode(name);
            if (!node.getPrimaryNodeType().getName().equals(type)) {
                try {
                    node.setPrimaryType(type);
                } catch (RepositoryException e) {
                    node.remove();
                    node = parent.addNode(name, type);
                }
            }
        } else {
            node = parent.addNode(name, type);
        }
        // mixins: the pack's, and no others
        Set<String> wanted = new HashSet<>((List<String>) m.get("mixins"));
        for (NodeType t : node.getMixinNodeTypes()) {
            if (!wanted.contains(t.getName()) && !SKIPPED_MIXINS.contains(t.getName())) {
                node.removeMixin(t.getName());
            }
        }
        for (String mixin : wanted) {
            if (!node.isNodeType(mixin)) {
                node.addMixin(mixin);
            }
        }
        // properties: the pack's, and no others
        Map<String, Object> props = (Map<String, Object>) m.get("props");
        for (PropertyIterator it = node.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (!props.containsKey(p.getName()) && !PROTECTED.contains(p.getName()) && !p.getDefinition().isProtected()) {
                p.remove();
            }
        }
        ValueFactory vf = session.getValueFactory();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Map<String, Object> pm = (Map<String, Object>) e.getValue();
            int ptype = PropertyType.valueFromName(String.valueOf(pm.get("t")));
            if (pm.containsKey("m")) {
                List<Object> vals = (List<Object>) pm.get("m");
                Value[] values = new Value[vals.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = value(vf, ptype, vals.get(i), binaries);
                }
                node.setProperty(e.getKey(), values, ptype);
            } else {
                node.setProperty(e.getKey(), value(vf, ptype, pm.get("v"), binaries));
            }
        }
        // children: the pack's replace what is there; others go, except child pages when asked
        List<Map<String, Object>> children = (List<Map<String, Object>>) m.get("children");
        Set<String> names = new HashSet<>();
        for (Map<String, Object> c : children) {
            names.add(String.valueOf(c.get("name")));
        }
        List<Node> toRemove = new ArrayList<>();
        for (NodeIterator it = node.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (names.contains(c.getName()) || SKIPPED_NODES.contains(c.getName())) {
                continue;
            }
            if (root && keepChildPages && c.isNodeType("cq:Page")) {
                continue;
            }
            toRemove.add(c);
        }
        for (Node c : toRemove) {
            c.remove();
        }
        for (Map<String, Object> c : children) {
            writeNode(session, node, c, false, binaries, false);
        }
        Object cug = m.get(CugTransfer.KEY);
        if (cug instanceof List) {
            List<String> principals = new ArrayList<>();
            for (Object n : (List<?>) cug) {
                principals.add(String.valueOf(n));
            }
            // only where something changes: most nodes have none here and none in the pack
            if (!principals.isEmpty() || node.hasNode(CugTransfer.POLICY_NODE)) {
                CugTransfer.applier.apply(session, node.getPath(), principals);
            }
        }
        if (node.getPrimaryNodeType().hasOrderableChildNodes()) {
            String previous = null;
            for (Map<String, Object> c : children) {
                String cn = String.valueOf(c.get("name"));
                if (previous != null) {
                    // put each pack child right after the previous one
                    moveAfter(node, cn, previous);
                }
                previous = cn;
            }
            if (!children.isEmpty()) {
                // the pack's first child comes first among the pack's; kept children (child pages) stay where they are
                String first = String.valueOf(children.get(0).get("name"));
                Node firstExisting = null;
                for (NodeIterator it = node.getNodes(); it.hasNext();) {
                    Node c = it.nextNode();
                    if (names.contains(c.getName())) {
                        firstExisting = c;
                        break;
                    }
                }
                if (firstExisting != null && !firstExisting.getName().equals(first)) {
                    node.orderBefore(first, firstExisting.getName());
                    String prev = first;
                    for (int i = 1; i < children.size(); i++) {
                        String cn = String.valueOf(children.get(i).get("name"));
                        moveAfter(node, cn, prev);
                        prev = cn;
                    }
                }
            }
        }
    }

    /** Orders {@code name} right after {@code after}. */
    private static void moveAfter(Node parent, String name, String after) throws RepositoryException {
        String next = null;
        boolean seen = false;
        for (NodeIterator it = parent.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (seen) {
                next = c.getName();
                break;
            }
            if (c.getName().equals(after)) {
                seen = true;
            }
        }
        if (name.equals(next)) {
            return;
        }
        parent.orderBefore(name, next);
    }

    private static Value value(ValueFactory vf, int type, Object raw, List<byte[]> binaries) throws RepositoryException {
        switch (type) {
            case PropertyType.BINARY: {
                int i = ((Number) raw).intValue();
                return vf.createValue(vf.createBinary(new ByteArrayInputStream(binaries.get(i))));
            }
            case PropertyType.DATE: {
                Calendar c = ISO8601.parse(String.valueOf(raw));
                if (c == null) {
                    throw new RepositoryException("Bad date " + raw);
                }
                return vf.createValue(c);
            }
            case PropertyType.BOOLEAN:
                return vf.createValue(raw instanceof Boolean ? (Boolean) raw : Boolean.parseBoolean(String.valueOf(raw)));
            case PropertyType.LONG:
                return vf.createValue(raw instanceof Number ? ((Number) raw).longValue() : Long.parseLong(String.valueOf(raw)));
            case PropertyType.DOUBLE:
                return vf.createValue(raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw)));
            case PropertyType.DECIMAL:
                return vf.createValue(new java.math.BigDecimal(String.valueOf(raw)));
            case PropertyType.REFERENCE:
            case PropertyType.WEAKREFERENCE:
                // references by identifier may not exist on the target: keep them as strings
                return vf.createValue(String.valueOf(raw), PropertyType.STRING);
            default:
                return vf.createValue(String.valueOf(raw), type);
        }
    }

    static String parentPath(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
