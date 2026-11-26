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
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

/**
 * Targeted components, laid out as AEM lays them out: the component node becomes a
 * {@code cq/personalization/components/target} whose {@code default} child is the original
 * component and whose other children are the experiences, one per audience ({@code cq:segment}
 * points at the segment page). The layout settings ({@code cq:responsive}) stay on the wrapper,
 * which is what the layout container renders.
 */
public final class Targeting {

    public static final String TARGET_TYPE = "cq/personalization/components/target";
    public static final String DEFAULT = "default";
    public static final String PROP_SEGMENT = "cq:segment";
    /** The experience's own title (not {@code jcr:title}, which components such as Title render). */
    public static final String PROP_TITLE = "experienceTitle";
    /** The goal of a targeted component (AEM's "Goal & Settings"): how a conversion is counted. */
    public static final String PROP_GOAL_TYPE = "goalType";
    /** click: a CSS selector (empty: anywhere in the experience); pageview: a page path; custom: an event name. */
    public static final String PROP_GOAL_VALUE = "goalValue";
    public static final String PROP_ACTIVITY = "activityTitle";
    /** A page's audience redirects: {@code <segment path>|<page path>} entries on its jcr:content. */
    public static final String PROP_REDIRECTS = "audienceRedirects";
    private static final String RESPONSIVE = "cq:responsive";
    /**
     * How a target chooses what a visitor sees: {@code targeting} (by audience, the default) or {@code abtest}:
     * the visitors are split among the experiences by {@link #PROP_SHARE} (an A/B test), whatever their audience.
     */
    public static final String PROP_MODE = "activityType";
    public static final String MODE_TARGETING = "targeting";
    public static final String MODE_ABTEST = "abtest";
    /** An experience's share of the visitors in an A/B test, in percent (0–100; the shares add up to 100). */
    public static final String PROP_SHARE = "trafficShare";

    private Targeting() {
    }

    public static boolean isTarget(Node n) throws RepositoryException {
        return n.hasProperty("sling:resourceType") && TARGET_TYPE.equals(n.getProperty("sling:resourceType").getString());
    }

    /** Wraps a component; a no-op when it is a target already. Returns the wrapper (same path). */
    public static Node target(Session session, String path) throws RepositoryException {
        Node x = session.getNode(path);
        if (isTarget(x)) {
            return x;
        }
        Node parent = x.getParent();
        String name = x.getName();
        String next = nextSibling(parent, name);
        String tmp = name + "__target";
        Node wrapper = parent.addNode(tmp, "nt:unstructured");
        wrapper.setProperty("sling:resourceType", TARGET_TYPE);
        session.move(x.getPath(), wrapper.getPath() + "/" + DEFAULT);
        Node dflt = wrapper.getNode(DEFAULT);
        if (dflt.hasNode(RESPONSIVE)) {
            session.move(dflt.getNode(RESPONSIVE).getPath(), wrapper.getPath() + "/" + RESPONSIVE);
        }
        session.move(wrapper.getPath(), parent.getPath() + "/" + name);
        if (next != null && parent.getPrimaryNodeType().hasOrderableChildNodes()) {
            parent.orderBefore(name, next);
        }
        return parent.getNode(name);
    }

    /** Unwraps a target, keeping one of its experiences (the default unless told otherwise) in its place. */
    public static Node untarget(Session session, String path, String keep) throws RepositoryException {
        Node x = session.getNode(path);
        if (!isTarget(x)) {
            return x;
        }
        String keepName = keep == null || keep.isEmpty() || !x.hasNode(keep) ? DEFAULT : keep;
        if (!x.hasNode(keepName)) {
            throw new IllegalArgumentException("The target has no " + keepName + " experience");
        }
        Node parent = x.getParent();
        String name = x.getName();
        String next = nextSibling(parent, name);
        String tmp = name + "__untarget";
        session.move(x.getNode(keepName).getPath(), parent.getPath() + "/" + tmp);
        Node kept = parent.getNode(tmp);
        for (String p : new String[] {PROP_SEGMENT, PROP_TITLE, PROP_SHARE}) {
            if (kept.hasProperty(p)) {
                kept.getProperty(p).remove();
            }
        }
        if (x.hasNode(RESPONSIVE)) {
            if (kept.hasNode(RESPONSIVE)) {
                kept.getNode(RESPONSIVE).remove();
            }
            session.move(x.getNode(RESPONSIVE).getPath(), kept.getPath() + "/" + RESPONSIVE);
        }
        x.remove();
        session.move(kept.getPath(), parent.getPath() + "/" + name);
        if (next != null && parent.getPrimaryNodeType().hasOrderableChildNodes()) {
            parent.orderBefore(name, next);
        }
        return parent.getNode(name);
    }

    /** Adds an experience: a copy of the default (or of {@code from}) for an audience. */
    public static Node addExperience(Session session, String targetPath, String name, String title, String segment, String from)
            throws RepositoryException {
        Node x = session.getNode(targetPath);
        if (!isTarget(x)) {
            throw new IllegalArgumentException("Not a targeted component: " + targetPath);
        }
        if (name == null || !name.matches("[A-Za-z0-9_-]+") || DEFAULT.equals(name) || RESPONSIVE.equals(name)) {
            throw new IllegalArgumentException("An experience name is letters, digits, - and _");
        }
        if (x.hasNode(name)) {
            throw new IllegalArgumentException("There is an experience named " + name + " already");
        }
        Node src = x.getNode(from == null || from.isEmpty() || !x.hasNode(from) ? DEFAULT : from);
        Node exp = copy(src, x, name);
        if (exp.hasNode(RESPONSIVE)) {
            exp.getNode(RESPONSIVE).remove();
        }
        exp.setProperty(PROP_TITLE, title == null || title.isEmpty() ? name : title);
        // a new experience takes no visitors of a running A/B test until it is given a share
        if (exp.hasProperty(PROP_SHARE)) {
            exp.getProperty(PROP_SHARE).remove();
        }
        if (segment != null && !segment.isEmpty()) {
            exp.setProperty(PROP_SEGMENT, segment);
        }
        return exp;
    }

    public static void updateExperience(Session session, String targetPath, String name, String title, String segment) throws RepositoryException {
        Node x = session.getNode(targetPath);
        if (!isTarget(x) || !x.hasNode(name) || DEFAULT.equals(name)) {
            throw new IllegalArgumentException("No experience " + name + " at " + targetPath);
        }
        Node exp = x.getNode(name);
        if (title != null) {
            exp.setProperty(PROP_TITLE, title.isEmpty() ? name : title);
        }
        if (segment != null) {
            exp.setProperty(PROP_SEGMENT, segment.isEmpty() ? null : segment);
        }
    }

    public static void removeExperience(Session session, String targetPath, String name) throws RepositoryException {
        Node x = session.getNode(targetPath);
        if (!isTarget(x) || DEFAULT.equals(name) || !x.hasNode(name)) {
            throw new IllegalArgumentException("No experience " + name + " at " + targetPath);
        }
        x.getNode(name).remove();
    }

    public static void setGoal(Session session, String targetPath, String type, String value, String activity) throws RepositoryException {
        Node x = session.getNode(targetPath);
        if (!isTarget(x)) {
            throw new IllegalArgumentException("Not a targeted component: " + targetPath);
        }
        if (type == null || type.isEmpty() || "none".equals(type)) {
            for (String p : new String[] {PROP_GOAL_TYPE, PROP_GOAL_VALUE}) {
                if (x.hasProperty(p)) {
                    x.getProperty(p).remove();
                }
            }
        } else {
            if (!type.matches("click|pageview|custom")) {
                throw new IllegalArgumentException("The goal is click, pageview or custom");
            }
            x.setProperty(PROP_GOAL_TYPE, type);
            x.setProperty(PROP_GOAL_VALUE, value == null ? "" : value);
        }
        if (activity != null) {
            x.setProperty(PROP_ACTIVITY, activity.isEmpty() ? null : activity);
        }
    }

    /** {@code abtest} or {@code targeting}. */
    public static String mode(Node target) throws RepositoryException {
        return MODE_ABTEST.equals(Segments.prop(target, PROP_MODE, MODE_TARGETING)) ? MODE_ABTEST : MODE_TARGETING;
    }

    /**
     * Sets how the target chooses: by audience, or an A/B test with these shares (experience name → percent,
     * the default included; an experience not named gets 0). The shares must be whole numbers from 0 to 100
     * that add up to 100, with at least two experiences taking part.
     */
    public static void setSplit(Session session, String targetPath, String mode, Map<String, Object> shares) throws RepositoryException {
        Node x = session.getNode(targetPath);
        if (!isTarget(x)) {
            throw new IllegalArgumentException("Not a targeted component: " + targetPath);
        }
        if (mode == null || MODE_TARGETING.equals(mode)) {
            if (x.hasProperty(PROP_MODE)) {
                x.getProperty(PROP_MODE).remove();
            }
            return;
        }
        if (!MODE_ABTEST.equals(mode)) {
            throw new IllegalArgumentException("The mode is targeting or abtest");
        }
        List<Map<String, Object>> exps = experiences(x);
        Map<String, Long> values = new LinkedHashMap<>();
        long sum = 0;
        int taking = 0;
        for (Map<String, Object> e : exps) {
            String name = String.valueOf(e.get("name"));
            Object v = shares == null ? null : shares.get(name);
            if (v != null && !(v instanceof Number)) {
                throw new IllegalArgumentException("The share of " + name + " is a number");
            }
            double d = v == null ? 0 : ((Number) v).doubleValue();
            if (d < 0 || d > 100 || d != Math.rint(d)) {
                throw new IllegalArgumentException("A share is a whole number from 0 to 100");
            }
            values.put(name, (long) d);
            sum += (long) d;
            if (d > 0) {
                taking++;
            }
        }
        if (shares != null) {
            for (String name : shares.keySet()) {
                if (!values.containsKey(name)) {
                    throw new IllegalArgumentException("No experience " + name + " at " + targetPath);
                }
            }
        }
        if (sum != 100) {
            throw new IllegalArgumentException("The shares add up to " + sum + "%; they must add up to 100%");
        }
        if (taking < 2) {
            throw new IllegalArgumentException("An A/B test needs at least two experiences with a share");
        }
        for (Map.Entry<String, Long> e : values.entrySet()) {
            x.getNode(e.getKey()).setProperty(PROP_SHARE, e.getValue());
        }
        x.setProperty(PROP_MODE, MODE_ABTEST);
    }

    /** The goal of a target: type, value, activity. */
    public static Map<String, Object> goal(Node target) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", Segments.prop(target, PROP_GOAL_TYPE, "none"));
        m.put("value", Segments.prop(target, PROP_GOAL_VALUE, ""));
        m.put("activity", Segments.prop(target, PROP_ACTIVITY, null));
        return m;
    }

    /** The audience redirects of a page: [{segment, target}]. */
    public static List<Map<String, Object>> redirects(Node pageContent) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!pageContent.hasProperty(PROP_REDIRECTS)) {
            return out;
        }
        javax.jcr.Property p = pageContent.getProperty(PROP_REDIRECTS);
        javax.jcr.Value[] values = p.isMultiple() ? p.getValues() : new javax.jcr.Value[] {p.getValue()};
        for (javax.jcr.Value v : values) {
            String s = v.getString();
            int bar = s.indexOf('|');
            if (bar > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("segment", s.substring(0, bar));
                m.put("target", s.substring(bar + 1));
                out.add(m);
            }
        }
        return out;
    }

    public static void setRedirects(Session session, String pagePath, List<Map<String, Object>> redirects) throws RepositoryException {
        if (pagePath == null || !session.nodeExists(pagePath + "/jcr:content")) {
            throw new IllegalArgumentException("No page at " + pagePath);
        }
        Node jc = session.getNode(pagePath + "/jcr:content");
        List<String> values = new ArrayList<>();
        for (Map<String, Object> r : redirects) {
            String segment = Segments.str(r.get("segment"), "");
            String target = Segments.str(r.get("target"), "");
            if (segment.startsWith("/conf/") && target.startsWith("/content/") && !target.contains("|")) {
                values.add(segment + "|" + target);
            }
        }
        if (values.isEmpty()) {
            if (jc.hasProperty(PROP_REDIRECTS)) {
                jc.getProperty(PROP_REDIRECTS).remove();
            }
        } else {
            jc.setProperty(PROP_REDIRECTS, values.toArray(new String[0]));
        }
    }

    /** Every targeted component below a path (a page or a site), by query; at most {@code limit}. */
    public static List<Map<String, Object>> findTargets(Session session, String root, int limit) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        String scope = root == null || root.isEmpty() ? "/content" : root;
        javax.jcr.query.Query q = session.getWorkspace().getQueryManager().createQuery(
                "SELECT * FROM [nt:unstructured] AS n WHERE ISDESCENDANTNODE(n, [" + scope.replace("]", "\\]") + "]) AND n.[sling:resourceType] = '" + TARGET_TYPE + "'",
                javax.jcr.query.Query.JCR_SQL2);
        q.setLimit(limit);
        for (NodeIterator it = q.execute().getNodes(); it.hasNext();) {
            Node t = it.nextNode();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", t.getPath());
            String page = t.getPath();
            int jc = page.indexOf("/jcr:content/");
            m.put("page", jc > 0 ? page.substring(0, jc) : null);
            Node pageNode = jc > 0 && session.nodeExists(page.substring(0, jc) + "/jcr:content") ? session.getNode(page.substring(0, jc) + "/jcr:content") : null;
            m.put("pageTitle", pageNode == null ? null : Segments.prop(pageNode, "jcr:title", null));
            m.put("goal", goal(t));
            m.put("mode", mode(t));
            m.put("experiences", experiences(t));
            Node dflt = t.hasNode(DEFAULT) ? t.getNode(DEFAULT) : null;
            m.put("type", dflt == null ? null : Segments.prop(dflt, "sling:resourceType", null));
            out.add(m);
        }
        return out;
    }

    /** The target's experiences, default first: name, title, segment, path, type. */
    public static List<Map<String, Object>> experiences(Node target) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!isTarget(target)) {
            return out;
        }
        if (target.hasNode(DEFAULT)) {
            out.add(experience(target.getNode(DEFAULT)));
        }
        for (NodeIterator it = target.getNodes(); it.hasNext();) {
            Node n = it.nextNode();
            if (DEFAULT.equals(n.getName()) || RESPONSIVE.equals(n.getName()) || n.getName().startsWith("jcr:") || n.getName().startsWith("rep:")) {
                continue;
            }
            out.add(experience(n));
        }
        return out;
    }

    private static Map<String, Object> experience(Node n) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", n.getName());
        m.put("path", n.getPath());
        m.put("title", DEFAULT.equals(n.getName()) ? "Default" : Segments.prop(n, PROP_TITLE, n.getName()));
        m.put("segment", Segments.prop(n, PROP_SEGMENT, null));
        m.put("type", Segments.prop(n, "sling:resourceType", null));
        m.put("share", n.hasProperty(PROP_SHARE) ? n.getProperty(PROP_SHARE).getLong() : 0L);
        return m;
    }

    static String nextSibling(Node parent, String name) throws RepositoryException {
        boolean found = false;
        for (NodeIterator it = parent.getNodes(); it.hasNext();) {
            Node n = it.nextNode();
            if (found) {
                return n.getName();
            }
            if (n.getName().equals(name)) {
                found = true;
            }
        }
        return null;
    }

    /** A transient deep copy (the session's, not the workspace's, so it saves with everything else). */
    static Node copy(Node src, Node parent, String name) throws RepositoryException {
        Node dst = parent.addNode(name, src.getPrimaryNodeType().getName());
        for (NodeType mixin : src.getMixinNodeTypes()) {
            dst.addMixin(mixin.getName());
        }
        for (PropertyIterator it = src.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getDefinition().isProtected()) {
                continue;
            }
            if (p.isMultiple()) {
                dst.setProperty(p.getName(), p.getValues(), p.getType());
            } else {
                dst.setProperty(p.getName(), p.getValue());
            }
        }
        for (NodeIterator it = src.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            copy(c, dst, c.getName());
        }
        return dst;
    }
}
