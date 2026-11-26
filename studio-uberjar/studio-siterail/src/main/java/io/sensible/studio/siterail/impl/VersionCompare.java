/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.version.Version;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * Compare to Current of the Sites timeline: what differs between two states of the page content — the current one or
 * any version — as components added or removed and properties added, removed or changed. (Preview Version renders a
 * version in place, see {@link VersionPreview}.)
 */
final class VersionCompare {

    static final String CURRENT = "current";

    /** Properties that change with every save or version, or describe the node rather than the content. */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("jcr:uuid", "jcr:primaryType", "jcr:mixinTypes",
            "jcr:created", "jcr:createdBy", "jcr:lastModified", "jcr:lastModifiedBy", "cq:lastModified", "cq:lastModifiedBy",
            "jcr:baseVersion", "jcr:predecessors", "jcr:versionHistory", "jcr:isCheckedOut", "jcr:mergeFailed",
            "jcr:activity", "jcr:configuration", "cq:versionComment", "cq:versionCreator", "cq:lastReplicated",
            "cq:lastReplicatedBy", "cq:lastReplicationAction", "cq:lastRolledout", "cq:lastRolledoutBy", "studio:lockOwner",
            "cq:lockOwner"));

    private final ResourceResolver resolver;
    private final VersionRestore versions;

    VersionCompare(ResourceResolver resolver) {
        this.resolver = resolver;
        this.versions = new VersionRestore(resolver);
    }

    // ------------------------------------------------------------------------------------------ compare

    /**
     * The differences from one state of the page content to another ({@code current} or a version), as a list of
     * changes ordered by component path: {@code added} / {@code removed} components (with their resource type) and
     * {@code changed} / {@code added} / {@code removed} properties (with the values before and after).
     */
    Map<String, Object> diff(String pagePath, String from, String to) throws RepositoryException {
        Map<String, Map<String, String>> a = flatten(state(pagePath, from == null || from.isEmpty() ? CURRENT : from));
        Map<String, Map<String, String>> b = flatten(state(pagePath, to == null || to.isEmpty() ? CURRENT : to));
        List<Map<String, Object>> changes = new ArrayList<>();
        Set<String> nodes = new java.util.TreeSet<>(a.keySet());
        nodes.addAll(b.keySet());
        for (String n : nodes) {
            Map<String, String> before = a.get(n);
            Map<String, String> after = b.get(n);
            if (before == null || after == null) {
                // a component appears or goes; its parent being new or gone too is enough to say once
                if (nodes.contains(parentOf(n)) && (before == null ? a.get(parentOf(n)) == null : b.get(parentOf(n)) == null)
                        && !n.isEmpty()) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("node", n);
                c.put("kind", before == null ? "added" : "removed");
                c.put("resourceType", (before == null ? after : before).get("sling:resourceType"));
                c.put("properties", new TreeMap<>(before == null ? after : before));
                changes.add(c);
                continue;
            }
            Set<String> props = new java.util.TreeSet<>(before.keySet());
            props.addAll(after.keySet());
            for (String p : props) {
                String x = before.get(p);
                String y = after.get(p);
                if (x != null && x.equals(y)) {
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("node", n);
                c.put("kind", x == null ? "propertyAdded" : y == null ? "propertyRemoved" : "changed");
                c.put("resourceType", after.getOrDefault("sling:resourceType", before.get("sling:resourceType")));
                c.put("property", p);
                c.put("before", x);
                c.put("after", y);
                changes.add(c);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from == null || from.isEmpty() ? CURRENT : from);
        out.put("to", to == null || to.isEmpty() ? CURRENT : to);
        out.put("changes", changes);
        return out;
    }

    private static String parentOf(String n) {
        int slash = n.lastIndexOf('/');
        return slash < 0 ? "" : n.substring(0, slash);
    }

    /** The content node of the current page, or the frozen node of a version. */
    private Node state(String pagePath, String which) throws RepositoryException {
        if (CURRENT.equals(which)) {
            Session s = versions.session();
            String content = pagePath + "/jcr:content";
            if (!s.nodeExists(content)) {
                throw new IllegalArgumentException("No page content at " + content);
            }
            return s.getNode(content);
        }
        Version v = versions.versionOf(pagePath, which);
        if (v == null) {
            throw new IllegalArgumentException("No version " + which + " of " + pagePath);
        }
        return v.getFrozenNode();
    }

    /** relative node path ("" for jcr:content itself) → property → value as text */
    private static Map<String, Map<String, String>> flatten(Node root) throws RepositoryException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return out;
    }

    private static void flatten(Node n, String rel, Map<String, Map<String, String>> out) throws RepositoryException {
        Map<String, String> props = new TreeMap<>();
        for (PropertyIterator it = n.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            String name = p.getName();
            if (name.startsWith("jcr:frozen") || IGNORED.contains(name) || p.getType() == PropertyType.BINARY) {
                continue;
            }
            props.put(name, p.isMultiple() ? text(p.getValues()) : p.getValue().getString());
        }
        out.put(rel, props);
        for (NodeIterator it = n.getNodes(); it.hasNext();) {
            Node c = it.nextNode();
            if (c.isNodeType("nt:versionedChild")) {
                continue;
            }
            flatten(c, rel.isEmpty() ? c.getName() : rel + "/" + c.getName(), out);
        }
    }

    private static String text(Value[] values) throws RepositoryException {
        List<String> parts = new ArrayList<>();
        for (Value v : values) {
            parts.add(v.getString());
        }
        return "[" + String.join(", ", parts) + "]";
    }
}
