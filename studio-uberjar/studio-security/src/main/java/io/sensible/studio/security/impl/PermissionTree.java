/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.security.impl;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlException;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.principal.PrincipalIterator;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;

/**
 * The permissions of one user or group as a tree of the repository, in a few plain columns — Read, Modify,
 * Create, Delete, Read ACL, Edit ACL, Replicate — the way authors think about them rather than as JCR
 * privileges.
 *
 * <p>Reading: for every node, whether the principal <em>can</em> do each thing there (with what it gets from
 * its groups and {@code everyone}, and from entries further up), and whether an entry of its own <em>on that
 * node</em> allows or denies it.</p>
 *
 * <p>Writing a column at a node: the column's privileges are first taken out of the principal's own entries
 * on that node (entries with restrictions are left alone); if what is then inherited is not what was asked,
 * one allow or deny entry of the principal on that node gets them. So a box ticked where it was already
 * inherited adds nothing, and "inherit" simply clears what the node said.</p>
 */
final class PermissionTree {

    /** A column and the privileges behind it. The first privilege is the one checked for "can". */
    static final class Column {
        final String id;
        final String[] privileges;

        Column(String id, String... privileges) {
            this.id = id;
            this.privileges = privileges;
        }
    }

    static final List<Column> COLUMNS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Column("read", Privilege.JCR_READ),
            new Column("modify", Privilege.JCR_MODIFY_PROPERTIES, Privilege.JCR_LOCK_MANAGEMENT, Privilege.JCR_VERSION_MANAGEMENT),
            new Column("create", Privilege.JCR_ADD_CHILD_NODES, Privilege.JCR_NODE_TYPE_MANAGEMENT),
            new Column("delete", Privilege.JCR_REMOVE_NODE, Privilege.JCR_REMOVE_CHILD_NODES),
            new Column("readAcl", Privilege.JCR_READ_ACCESS_CONTROL),
            new Column("editAcl", Privilege.JCR_MODIFY_ACCESS_CONTROL),
            new Column("replicate", "crx:replicate")));

    /** Children not shown: the content of a page or file, and the repository's own bookkeeping. */
    static boolean hidden(String name) {
        return "jcr:content".equals(name) || "jcr:system".equals(name) || name.startsWith("rep:") || "oak:index".equals(name);
    }

    static final int MAX_CHILDREN = 500;

    private final Session session;
    private final JackrabbitAccessControlManager acm;
    private final PrincipalManager principalManager;
    private final List<Column> columns = new ArrayList<>();

    PermissionTree(Session session) throws RepositoryException {
        this.session = session;
        this.acm = (JackrabbitAccessControlManager) session.getAccessControlManager();
        this.principalManager = ((JackrabbitSession) session).getPrincipalManager();
        for (Column c : COLUMNS) {
            try {
                for (String p : c.privileges) {
                    acm.privilegeFromName(p);
                }
                columns.add(c);
            } catch (AccessControlException e) {
                // a privilege this repository does not have (crx:replicate before repoinit): no column
            }
        }
    }

    List<String> columnIds() {
        List<String> ids = new ArrayList<>();
        for (Column c : columns) {
            ids.add(c.id);
        }
        return ids;
    }

    // ------------------------------------------------------------------ reading

    /** The node at {@code path} and its children, each with what the principal can do and what is set there. */
    Map<String, Object> level(String principalName, String path) throws RepositoryException {
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!session.nodeExists(p)) {
            throw new IllegalArgumentException(p + " does not exist");
        }
        Principal principal = principal(principalName);
        Set<Principal> principals = principalsOf(principal);
        Node node = session.getNode(p);
        List<Object> children = new ArrayList<>();
        int shown = 0;
        boolean more = false;
        for (NodeIterator it = node.getNodes(); it.hasNext(); ) {
            Node child = it.nextNode();
            if (hidden(child.getName())) {
                continue;
            }
            if (shown++ >= MAX_CHILDREN) {
                more = true;
                break;
            }
            children.add(row(child, principal, principals));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("principal", principalName);
        out.put("columns", columnIds());
        out.put("node", row(node, principal, principals));
        out.put("children", children);
        out.put("more", more);
        return out;
    }

    private Map<String, Object> row(Node node, Principal principal, Set<Principal> principals) throws RepositoryException {
        String path = node.getPath();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("name", "/".equals(path) ? "/" : node.getName());
        String title = titleOf(node);
        if (title != null) {
            m.put("title", title);
        }
        m.put("type", node.getPrimaryNodeType().getName());
        m.put("hasChildren", hasVisibleChildren(node));
        Map<String, Object> can = new LinkedHashMap<>();
        for (Column c : columns) {
            can.put(c.id, acm.hasPrivileges(path, principals, privileges(c)));
        }
        m.put("can", can);
        Declared d = declared(path, principal);
        m.put("set", d.set);
        if (d.restricted) {
            m.put("restricted", true);
        }
        return m;
    }

    private static boolean hasVisibleChildren(Node node) throws RepositoryException {
        for (NodeIterator it = node.getNodes(); it.hasNext(); ) {
            if (!hidden(it.nextNode().getName())) {
                return true;
            }
        }
        return false;
    }

    private static String titleOf(Node node) {
        try {
            if (node.hasNode("jcr:content") && node.getNode("jcr:content").hasProperty("jcr:title")) {
                return node.getNode("jcr:content").getProperty("jcr:title").getString();
            }
            if (node.hasProperty("jcr:title")) {
                return node.getProperty("jcr:title").getString();
            }
        } catch (RepositoryException e) {
            // no title
        }
        return null;
    }

    /** What the principal's own entries on the node say, per column: "allow", "deny" or nothing. */
    private static final class Declared {
        final Map<String, Object> set = new LinkedHashMap<>();
        boolean restricted;
    }

    private Declared declared(String path, Principal principal) throws RepositoryException {
        Declared d = new Declared();
        JackrabbitAccessControlList acl = acl(path, false);
        if (acl == null) {
            return d;
        }
        for (AccessControlEntry e : acl.getAccessControlEntries()) {
            if (!e.getPrincipal().getName().equals(principal.getName())) {
                continue;
            }
            JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
            if (je.getRestrictionNames().length > 0) {
                d.restricted = true;
                continue;
            }
            Set<String> leaves = leaves(e.getPrivileges());
            for (Column c : columns) {
                Set<String> col = leaves(privileges(c));
                if (je.isAllow() ? leaves.containsAll(col) : !Collections.disjoint(leaves, col)) {
                    // the later entry wins, as in the repository's own evaluation
                    d.set.put(c.id, je.isAllow() ? "allow" : "deny");
                }
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sets columns at a node for a principal. {@code values}: column id → "allow", "deny" or "inherit"
     * ({@code true}/{@code false} are read as allow/deny). Returns the node's row as it is afterwards.
     */
    Map<String, Object> set(String principalName, String path, Map<String, String> values) throws RepositoryException {
        String p = path == null || path.isEmpty() ? "/" : path;
        if (!session.nodeExists(p)) {
            throw new IllegalArgumentException(p + " does not exist");
        }
        Principal principal = principal(principalName);
        Map<Column, String> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, String> v : values.entrySet()) {
            Column c = column(v.getKey());
            String value = "true".equals(v.getValue()) ? "allow" : "false".equals(v.getValue()) ? "deny" : v.getValue();
            if (!"allow".equals(value) && !"deny".equals(value) && !"inherit".equals(value)) {
                throw new IllegalArgumentException("A column is allow, deny or inherit, not " + v.getValue());
            }
            wanted.put(c, value);
        }
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Nothing to set");
        }

        // 1. what the node said about these columns goes; what is inherited shows through
        Set<String> clear = new LinkedHashSet<>();
        for (Column c : wanted.keySet()) {
            clear.addAll(leaves(privileges(c)));
        }
        JackrabbitAccessControlList acl = acl(p, true);
        strip(acl, principal, clear);
        acm.setPolicy(p, acl);
        session.save();

        // 2. where the inherited permission is not what was asked, the node says so
        Set<Principal> principals = principalsOf(principal);
        Set<String> allow = new LinkedHashSet<>();
        Set<String> deny = new LinkedHashSet<>();
        for (Map.Entry<Column, String> w : wanted.entrySet()) {
            if ("inherit".equals(w.getValue())) {
                continue;
            }
            boolean want = "allow".equals(w.getValue());
            boolean has = acm.hasPrivileges(p, principals, privileges(w.getKey()));
            if (want != has) {
                (want ? allow : deny).addAll(names(privileges(w.getKey())));
            }
        }
        if (!allow.isEmpty() || !deny.isEmpty()) {
            acl = acl(p, true);
            if (!allow.isEmpty()) {
                merge(acl, principal, true, allow);
            }
            if (!deny.isEmpty()) {
                merge(acl, principal, false, deny);
            }
            acm.setPolicy(p, acl);
            session.save();
        }
        return row(session.getNode(p), principal, principals);
    }

    /** Takes {@code leaves} out of the principal's unrestricted entries, keeping each entry where it was. */
    private void strip(JackrabbitAccessControlList acl, Principal principal, Set<String> leaves) throws RepositoryException {
        AccessControlEntry[] entries = acl.getAccessControlEntries();
        for (int i = 0; i < entries.length; i++) {
            AccessControlEntry e = entries[i];
            if (!e.getPrincipal().getName().equals(principal.getName()) || ((JackrabbitAccessControlEntry) e).getRestrictionNames().length > 0) {
                continue;
            }
            Set<String> have = leaves(e.getPrivileges());
            if (Collections.disjoint(have, leaves)) {
                continue;
            }
            have.removeAll(leaves);
            AccessControlEntry next = i + 1 < entries.length ? entries[i + 1] : null;
            boolean allow = ((JackrabbitAccessControlEntry) e).isAllow();
            acl.removeAccessControlEntry(e);
            if (!have.isEmpty()) {
                acl.addEntry(principal, privilegesFromNames(have), allow);
                if (next != null) {
                    AccessControlEntry[] now = acl.getAccessControlEntries();
                    acl.orderBefore(now[now.length - 1], next);
                }
            }
            entries = acl.getAccessControlEntries();
            i = -1; // start again: positions moved
        }
    }

    /** Adds {@code names} to the principal's unrestricted allow (or deny) entry on the node, or creates it. */
    private void merge(JackrabbitAccessControlList acl, Principal principal, boolean allow, Set<String> names) throws RepositoryException {
        for (AccessControlEntry e : acl.getAccessControlEntries()) {
            JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
            if (e.getPrincipal().getName().equals(principal.getName()) && je.isAllow() == allow && je.getRestrictionNames().length == 0) {
                Set<String> all = new LinkedHashSet<>(names(e.getPrivileges()));
                all.addAll(names);
                AccessControlEntry[] entries = acl.getAccessControlEntries();
                int i = java.util.Arrays.asList(entries).indexOf(e);
                AccessControlEntry next = i + 1 < entries.length ? entries[i + 1] : null;
                acl.removeAccessControlEntry(e);
                acl.addEntry(principal, privilegesFromNames(all), allow);
                if (next != null) {
                    AccessControlEntry[] now = acl.getAccessControlEntries();
                    acl.orderBefore(now[now.length - 1], next);
                }
                return;
            }
        }
        acl.addEntry(principal, privilegesFromNames(names), allow);
    }

    // ------------------------------------------------------------------ helpers

    private Column column(String id) {
        for (Column c : columns) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        throw new IllegalArgumentException("No column " + id);
    }

    private Privilege[] privileges(Column c) throws RepositoryException {
        Privilege[] out = new Privilege[c.privileges.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = acm.privilegeFromName(c.privileges[i]);
        }
        return out;
    }

    private Privilege[] privilegesFromNames(Set<String> names) throws RepositoryException {
        Privilege[] out = new Privilege[names.size()];
        int i = 0;
        for (String n : names) {
            out[i++] = acm.privilegeFromName(n);
        }
        return out;
    }

    private static Set<String> names(Privilege[] privileges) {
        Set<String> out = new LinkedHashSet<>();
        for (Privilege p : privileges) {
            out.add(p.getName());
        }
        return out;
    }

    /** The non-aggregate privileges behind these ({@code jcr:all} → every single one). */
    static Set<String> leaves(Privilege[] privileges) {
        Set<String> out = new LinkedHashSet<>();
        for (Privilege p : privileges) {
            if (p.isAggregate()) {
                for (Privilege a : p.getAggregatePrivileges()) {
                    if (!a.isAggregate()) {
                        out.add(a.getName());
                    }
                }
            } else {
                out.add(p.getName());
            }
        }
        return out;
    }

    private Set<Principal> principalsOf(Principal principal) {
        Set<Principal> principals = new LinkedHashSet<>();
        principals.add(principal);
        for (PrincipalIterator it = principalManager.getGroupMembership(principal); it.hasNext(); ) {
            principals.add(it.nextPrincipal());
        }
        principals.add(principalManager.getEveryone());
        return principals;
    }

    private Principal principal(String name) {
        Principal p = name == null ? null : principalManager.getPrincipal(name);
        if (p == null) {
            throw new IllegalArgumentException("No principal " + name);
        }
        return p;
    }

    private JackrabbitAccessControlList acl(String path, boolean create) throws RepositoryException {
        for (AccessControlPolicy p : acm.getPolicies(path)) {
            if (p instanceof JackrabbitAccessControlList) {
                return (JackrabbitAccessControlList) p;
            }
        }
        if (!create) {
            return null;
        }
        for (AccessControlPolicyIterator it = acm.getApplicablePolicies(path); it.hasNext(); ) {
            AccessControlPolicy p = it.nextAccessControlPolicy();
            if (p instanceof JackrabbitAccessControlList) {
                return (JackrabbitAccessControlList) p;
            }
        }
        throw new RepositoryException("No access control list can be set at " + path);
    }
}
