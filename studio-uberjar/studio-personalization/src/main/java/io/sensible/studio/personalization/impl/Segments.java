/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * Audiences: ContextHub segments, kept where and how AEM keeps them, {@code /conf/<site>/settings/wcm/segments/<name>}
 * (a {@code cq:Page} whose {@code jcr:content} is a {@code cq/contexthub/components/segment-page}) with the
 * definition under {@code jcr:content/traits}: logic nodes ({@code traits/logic/operator-and|or}, their
 * operands in an {@code andpar}/{@code orpar} paragraph system) and comparisons
 * ({@code traits/generic-comparison/variants/property-value}: {@code left/property}, {@code operator},
 * {@code right/value}; {@code property-property} compares two store properties). Read into a small
 * expression tree the page's client script evaluates against the visitor's stores.
 * <p>
 * Expression JSON: {@code {"type":"and"|"or","items":[...]}} or
 * {@code {"type":"comparison","property":"profile/age","operator":"greater-than","value":"30","dataType":"number"}}
 * ({@code "otherProperty"} instead of {@code "value"} for a property-to-property comparison).
 */
public final class Segments {

    public static final String SEGMENT_PAGE_TYPE = "cq/contexthub/components/segment-page";
    public static final String SEGMENTS = "/settings/wcm/segments";
    public static final String GLOBAL = "/conf/global";
    public static final String TRAITS = "traits";
    static final String TRAIT_TYPES = "cq/contexthub/components/traits/";
    static final String LOGIC_AND = TRAIT_TYPES + "logic/operator-and";
    static final String LOGIC_OR = TRAIT_TYPES + "logic/operator-or";
    static final String PROPERTY_VALUE = TRAIT_TYPES + "generic-comparison/variants/property-value";
    static final String PROPERTY_PROPERTY = TRAIT_TYPES + "generic-comparison/variants/property-property";
    static final String LEFT_PROPERTY = TRAIT_TYPES + "generic-comparison/type/property";
    static final String RIGHT_VALUE = TRAIT_TYPES + "generic-comparison/type/value";
    static final String PARSYS = "foundation/components/parsys";

    public static final String[] OPERATORS = {"equals", "not-equals", "contains", "not-contains", "starts-with", "ends-with",
        "greater-than", "greater-than-or-equal", "less-than", "less-than-or-equal", "exists", "not-exists"};

    private Segments() {
    }

    /** One audience. */
    public static final class Segment {
        public final String path;
        public final String name;
        public final String title;
        public final String description;
        public final long boost;
        /** The expression tree, or null for a segment without conditions. */
        public final Map<String, Object> expression;

        Segment(String path, String name, String title, String description, long boost, Map<String, Object> expression) {
            this.path = path;
            this.name = name;
            this.title = title;
            this.description = description;
            this.boost = boost;
            this.expression = expression;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("name", name);
            m.put("title", title);
            m.put("description", description);
            m.put("boost", boost);
            m.put("expression", expression);
            m.put("conf", path.substring(0, path.indexOf(SEGMENTS)));
            return m;
        }
    }

    /**
     * The configuration a page uses: its {@code cq:conf} (looked up through the page's ancestors, as
     * AEM resolves it), else {@code /conf/<site>} when it exists, else {@code /conf/global}.
     */
    public static String confOf(ResourceResolver rr, String path) {
        if (path == null) {
            return GLOBAL;
        }
        if (path.startsWith("/conf/")) {
            String[] parts = path.split("/");
            return parts.length > 2 ? "/conf/" + parts[2] : GLOBAL;
        }
        Resource r = rr.getResource(path);
        while (r != null && !"/".equals(r.getPath())) {
            Resource jc = r.getChild("jcr:content");
            ValueMap vm = jc != null ? jc.getValueMap() : r.getValueMap();
            String conf = vm.get("cq:conf", String.class);
            if (conf != null && rr.getResource(conf) != null) {
                return conf;
            }
            r = r.getParent();
        }
        String[] parts = path.split("/");
        if (parts.length > 2 && "content".equals(parts[1]) && rr.getResource("/conf/" + parts[2]) != null) {
            return "/conf/" + parts[2];
        }
        return GLOBAL;
    }

    /** The segments a page can target: its configuration's, then the global ones. */
    public static List<Segment> forPage(ResourceResolver rr, String pagePath) {
        String conf = confOf(rr, pagePath);
        List<Segment> out = new ArrayList<>(list(rr, conf));
        if (!GLOBAL.equals(conf)) {
            out.addAll(list(rr, GLOBAL));
        }
        return out;
    }

    public static List<Segment> list(ResourceResolver rr, String conf) {
        List<Segment> out = new ArrayList<>();
        Resource root = rr.getResource(conf + SEGMENTS);
        if (root == null) {
            return out;
        }
        for (Resource r : root.getChildren()) {
            Segment s = read(r);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public static Segment read(Resource page) {
        if (page == null) {
            return null;
        }
        Resource jc = page.getChild("jcr:content");
        if (jc == null) {
            return null;
        }
        ValueMap vm = jc.getValueMap();
        return new Segment(page.getPath(), page.getName(), vm.get("jcr:title", page.getName()), vm.get("jcr:description", String.class),
                vm.get("segmentBoost", 0L), expression(jc.getChild(TRAITS)));
    }

    /** The expression a traits node (or an operand inside one) stands for; null when it has none. */
    static Map<String, Object> expression(Resource r) {
        if (r == null) {
            return null;
        }
        String type = r.getValueMap().get("sling:resourceType", "");
        if (LOGIC_AND.equals(type) || LOGIC_OR.equals(type)) {
            List<Object> items = new ArrayList<>();
            for (Resource par : r.getChildren()) {
                // the operands sit in a paragraph system (andpar/orpar); tolerate operands placed directly
                if (par.getValueMap().get("sling:resourceType", "").startsWith(TRAIT_TYPES)) {
                    Map<String, Object> e = expression(par);
                    if (e != null) {
                        items.add(e);
                    }
                } else {
                    for (Resource operand : par.getChildren()) {
                        Map<String, Object> e = expression(operand);
                        if (e != null) {
                            items.add(e);
                        }
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", LOGIC_AND.equals(type) ? "and" : "or");
            m.put("items", items);
            return m;
        }
        if (PROPERTY_VALUE.equals(type) || PROPERTY_PROPERTY.equals(type)) {
            ValueMap vm = r.getValueMap();
            Resource left = r.getChild("left");
            Resource right = r.getChild("right");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "comparison");
            m.put("property", left == null ? "" : left.getValueMap().get("property", ""));
            m.put("operator", vm.get("operator", "equals"));
            m.put("dataType", vm.get("dataType", "string"));
            if (PROPERTY_PROPERTY.equals(type)) {
                m.put("otherProperty", right == null ? "" : right.getValueMap().get("property", ""));
            } else {
                m.put("value", right == null ? "" : right.getValueMap().get("value", ""));
            }
            return m;
        }
        return null;
    }

    /** Creates or replaces a segment from an expression tree; the caller saves. */
    public static Segment save(Session session, String conf, String name, String title, String description, Long boost,
            Map<String, Object> expression) throws RepositoryException {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("A segment name is letters, digits, - and _");
        }
        Node root = mkdirs(session, conf + SEGMENTS, "sling:Folder");
        Node page = root.hasNode(name) ? root.getNode(name) : root.addNode(name, "cq:Page");
        Node jc = page.hasNode("jcr:content") ? page.getNode("jcr:content") : page.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("sling:resourceType", SEGMENT_PAGE_TYPE);
        jc.setProperty("jcr:title", title == null || title.isEmpty() ? name : title);
        jc.setProperty("segmentName", title == null || title.isEmpty() ? name : title);
        if (description != null) {
            jc.setProperty("jcr:description", description.isEmpty() ? null : description);
        }
        if (boost != null) {
            jc.setProperty("segmentBoost", boost);
        }
        jc.setProperty("cq:lastModified", java.util.Calendar.getInstance());
        jc.setProperty("cq:lastModifiedBy", session.getUserID());
        if (jc.hasNode(TRAITS)) {
            jc.getNode(TRAITS).remove();
        }
        if (expression != null) {
            Map<String, Object> rootExpr = expression;
            if (!"and".equals(rootExpr.get("type")) && !"or".equals(rootExpr.get("type"))) {
                // AEM's traits root is always a logic node
                Map<String, Object> wrap = new LinkedHashMap<>();
                wrap.put("type", "and");
                List<Object> items = new ArrayList<>();
                items.add(rootExpr);
                wrap.put("items", items);
                rootExpr = wrap;
            }
            writeExpression(jc, TRAITS, rootExpr);
        }
        return readSaved(session, page.getPath());
    }

    @SuppressWarnings("unchecked")
    private static void writeExpression(Node parent, String name, Map<String, Object> e) throws RepositoryException {
        Node n = parent.addNode(name, "nt:unstructured");
        String type = String.valueOf(e.get("type"));
        if ("and".equals(type) || "or".equals(type)) {
            n.setProperty("sling:resourceType", "and".equals(type) ? LOGIC_AND : LOGIC_OR);
            Node par = n.addNode("and".equals(type) ? "andpar" : "orpar", "nt:unstructured");
            par.setProperty("sling:resourceType", PARSYS);
            Object items = e.get("items");
            int i = 0;
            if (items instanceof List) {
                for (Object item : (List<Object>) items) {
                    if (item instanceof Map) {
                        writeExpression(par, "item_" + (i++), (Map<String, Object>) item);
                    }
                }
            }
            return;
        }
        boolean propertyProperty = e.get("otherProperty") != null;
        n.setProperty("sling:resourceType", propertyProperty ? PROPERTY_PROPERTY : PROPERTY_VALUE);
        n.setProperty("operator", str(e.get("operator"), "equals"));
        n.setProperty("dataType", str(e.get("dataType"), "string"));
        Node left = n.addNode("left", "nt:unstructured");
        left.setProperty("sling:resourceType", LEFT_PROPERTY);
        left.setProperty("property", str(e.get("property"), ""));
        Node right = n.addNode("right", "nt:unstructured");
        if (propertyProperty) {
            right.setProperty("sling:resourceType", LEFT_PROPERTY);
            right.setProperty("property", str(e.get("otherProperty"), ""));
        } else {
            right.setProperty("sling:resourceType", RIGHT_VALUE);
            right.setProperty("value", str(e.get("value"), ""));
        }
    }

    private static Segment readSaved(Session session, String path) throws RepositoryException {
        Node page = session.getNode(path);
        Node jc = page.getNode("jcr:content");
        return new Segment(path, page.getName(), prop(jc, "jcr:title", page.getName()), prop(jc, "jcr:description", null),
                jc.hasProperty("segmentBoost") ? jc.getProperty("segmentBoost").getLong() : 0L,
                jc.hasNode(TRAITS) ? expression(jc.getNode(TRAITS)) : null);
    }

    /** The expression of a transient (unsaved) traits node, read through JCR. */
    static Map<String, Object> expression(Node r) throws RepositoryException {
        String type = prop(r, "sling:resourceType", "");
        if (LOGIC_AND.equals(type) || LOGIC_OR.equals(type)) {
            List<Object> items = new ArrayList<>();
            for (NodeIterator it = r.getNodes(); it.hasNext();) {
                Node par = it.nextNode();
                if (prop(par, "sling:resourceType", "").startsWith(TRAIT_TYPES)) {
                    Map<String, Object> e = expression(par);
                    if (e != null) {
                        items.add(e);
                    }
                } else {
                    for (NodeIterator ops = par.getNodes(); ops.hasNext();) {
                        Map<String, Object> e = expression(ops.nextNode());
                        if (e != null) {
                            items.add(e);
                        }
                    }
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", LOGIC_AND.equals(type) ? "and" : "or");
            m.put("items", items);
            return m;
        }
        if (PROPERTY_VALUE.equals(type) || PROPERTY_PROPERTY.equals(type)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "comparison");
            m.put("property", r.hasNode("left") ? prop(r.getNode("left"), "property", "") : "");
            m.put("operator", prop(r, "operator", "equals"));
            m.put("dataType", prop(r, "dataType", "string"));
            if (PROPERTY_PROPERTY.equals(type)) {
                m.put("otherProperty", r.hasNode("right") ? prop(r.getNode("right"), "property", "") : "");
            } else {
                m.put("value", r.hasNode("right") ? prop(r.getNode("right"), "value", "") : "");
            }
            return m;
        }
        return null;
    }

    public static void remove(Session session, String path) throws RepositoryException {
        if (path == null || !path.contains(SEGMENTS + "/") || !session.nodeExists(path)) {
            throw new IllegalArgumentException("Not a segment: " + path);
        }
        session.getNode(path).remove();
    }

    static String prop(Node n, String name, String dflt) throws RepositoryException {
        return n.hasProperty(name) ? n.getProperty(name).getString() : dflt;
    }

    static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }

    static Node mkdirs(Session session, String path, String type) throws RepositoryException {
        Node n = session.getRootNode();
        for (String seg : path.substring(1).split("/")) {
            n = n.hasNode(seg) ? n.getNode(seg) : n.addNode(seg, type);
        }
        return n;
    }
}
