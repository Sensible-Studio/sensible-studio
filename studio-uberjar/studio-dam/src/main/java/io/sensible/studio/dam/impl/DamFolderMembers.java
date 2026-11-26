/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;

/**
 * The members of a Media folder, as the Permissions tab of a folder's properties shows them: users
 * and groups with a role on the folder and everything in it, and whether the folder is private.
 *
 * <table>
 *   <caption>Roles and the access control entries they stand for, on the folder node</caption>
 *   <tr><th>Owner</th><td>allow {@code jcr:all}: everything, including changing these members</td></tr>
 *   <tr><th>Editor</th><td>allow {@code jcr:read}, {@code rep:write}, {@code jcr:versionManagement},
 *       {@code jcr:lockManagement}: add, edit, move and delete files and folders</td></tr>
 *   <tr><th>Viewer</th><td>allow {@code jcr:read}</td></tr>
 *   <tr><th>Private</th><td>deny {@code jcr:read} to {@code everyone}, first in the list; the members'
 *       entries and an allow {@code jcr:all} for {@code administrators} follow it, so they win</td></tr>
 * </table>
 * Entries are written directly for the users and groups chosen (no group per folder): the folder's
 * list is the record, it moves with the folder and goes with it when it is deleted. Saving replaces
 * the allow entries of these three shapes and the private entry, and keeps every other entry.
 * Everything runs with the session of the person asking: changing members needs
 * {@code jcr:modifyAccessControl} on the folder (an owner, an administrator).
 */
final class DamFolderMembers {

    static final String OWNER = "owner";
    static final String EDITOR = "editor";
    static final String VIEWER = "viewer";
    static final List<String> ROLES = Arrays.asList(OWNER, EDITOR, VIEWER);

    static final String EVERYONE = "everyone";
    static final String ADMINISTRATORS = "administrators";

    // the prefixed names entries report (Privilege.JCR_* are the expanded {uri}name forms)
    private static final List<String> OWNER_PRIVILEGES = Collections.singletonList("jcr:all");
    private static final List<String> EDITOR_PRIVILEGES = Arrays.asList("jcr:read", "rep:write", "jcr:versionManagement", "jcr:lockManagement");
    private static final List<String> VIEWER_PRIVILEGES = Collections.singletonList("jcr:read");

    /** At most this many members on one folder. */
    static final int MAX_MEMBERS = 200;

    private final Session session;
    private final AccessControlManager acm;

    DamFolderMembers(Session session) throws RepositoryException {
        this.session = session;
        this.acm = session.getAccessControlManager();
    }

    /** A user or a group with a role. */
    static final class Member {
        final String id;
        final String role;

        Member(String id, String role) {
            this.id = id;
            this.role = role;
        }
    }

    /** Whether this session may see and change the members of {@code path}. */
    boolean canManage(String path) throws RepositoryException {
        return session.hasPermission(path, Session.ACTION_READ)
                && acm.hasPrivileges(path, privileges(Privilege.JCR_READ_ACCESS_CONTROL, Privilege.JCR_MODIFY_ACCESS_CONTROL));
    }

    /** Whether this session may read the members (read access control). */
    boolean canRead(String path) throws RepositoryException {
        return acm.hasPrivileges(path, privileges(Privilege.JCR_READ_ACCESS_CONTROL));
    }

    /** The members and the private flag, for the folder's properties. */
    Map<String, Object> describe(String path) throws RepositoryException {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean readable = canRead(path);
        out.put("canManage", canManage(path));
        if (!readable) {
            out.put("members", Collections.emptyList());
            out.put("private", false);
            out.put("readable", false);
            return out;
        }
        JackrabbitAccessControlList list = list(path, false);
        List<Map<String, Object>> members = new ArrayList<>();
        boolean isPrivate = false;
        if (list != null) {
            for (AccessControlEntry e : list.getAccessControlEntries()) {
                JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
                if (je.getRestrictionNames().length > 0) {
                    continue; // entries with restrictions are someone else's
                }
                String name = je.getPrincipal().getName();
                if (isPrivateEntry(je)) {
                    isPrivate = true;
                    continue;
                }
                String role = je.isAllow() && !EVERYONE.equals(name) ? roleOf(je) : null;
                if (role == null || ADMINISTRATORS.equals(name) && OWNER.equals(role) && isPrivate) {
                    continue; // the administrators' entry goes with "private"
                }
                members.add(member(name, role));
            }
        }
        out.put("members", members);
        out.put("private", isPrivate);
        out.put("readable", true);
        return out;
    }

    /**
     * Replaces the members and the private flag of {@code path}; other entries are kept. Not saved:
     * the caller saves the session.
     *
     * @throws IllegalArgumentException for an unknown role or user/group, or too many members
     */
    void save(String path, List<Member> members, boolean isPrivate) throws RepositoryException {
        if (members.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("at most " + MAX_MEMBERS + " members");
        }
        PrincipalManager pm = ((JackrabbitSession) session).getPrincipalManager();
        Map<String, Principal> principals = new LinkedHashMap<>();
        Map<String, String> roles = new LinkedHashMap<>();
        for (Member m : members) {
            String role = m.role == null ? "" : m.role.toLowerCase(Locale.ROOT);
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("unknown role " + m.role);
            }
            if (EVERYONE.equals(m.id)) {
                throw new IllegalArgumentException("everyone cannot be a member; leave the folder open instead");
            }
            Principal p = pm.getPrincipal(m.id);
            if (p == null) {
                throw new IllegalArgumentException("no user or group " + m.id);
            }
            principals.put(m.id, p);
            roles.put(m.id, role);
        }
        if (isPrivate && !roles.containsValue(OWNER) && !isAdministrator()) {
            // a private folder needs someone who can open it again
            throw new IllegalArgumentException("a private folder needs an owner");
        }
        JackrabbitAccessControlList list = list(path, true);
        if (list == null) {
            throw new IllegalArgumentException("access cannot be set on " + path);
        }
        // drop what this tab wrote before: the private entry, the administrators' entry that goes with it,
        // and every allow entry of a role's shape
        for (AccessControlEntry e : list.getAccessControlEntries()) {
            JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
            if (je.getRestrictionNames().length > 0) {
                continue;
            }
            if (isPrivateEntry(je) || je.isAllow() && !EVERYONE.equals(je.getPrincipal().getName()) && roleOf(je) != null) {
                list.removeAccessControlEntry(e);
            }
        }
        Principal everyone = pm.getEveryone();
        if (isPrivate) {
            list.addEntry(everyone, privileges(Privilege.JCR_READ), false);
            orderFirst(list, everyone);
            Principal admins = pm.getPrincipal(ADMINISTRATORS);
            if (admins != null) {
                list.addEntry(admins, privileges(Privilege.JCR_ALL), true);
            }
        }
        for (Map.Entry<String, Principal> e : principals.entrySet()) {
            list.addEntry(e.getValue(), privileges(privilegesOf(roles.get(e.getKey()))), true);
        }
        acm.setPolicy(path, list);
    }

    private boolean isAdministrator() throws RepositoryException {
        if ("admin".equals(session.getUserID())) {
            return true;
        }
        UserManager um = ((JackrabbitSession) session).getUserManager();
        Authorizable me = um.getAuthorizable(session.getUserID());
        Authorizable admins = um.getAuthorizable(ADMINISTRATORS);
        return me != null && admins instanceof org.apache.jackrabbit.api.security.user.Group
                && ((org.apache.jackrabbit.api.security.user.Group) admins).isMember(me);
    }

    /** Moves the entries of {@code principal} before every other entry. */
    private static void orderFirst(JackrabbitAccessControlList list, Principal principal) throws RepositoryException {
        AccessControlEntry[] all = list.getAccessControlEntries();
        if (all.length == 0) {
            return;
        }
        AccessControlEntry first = all[0];
        for (AccessControlEntry e : all) {
            if (e.getPrincipal().getName().equals(principal.getName()) && e != first) {
                list.orderBefore(e, first);
                return;
            }
        }
    }

    private static boolean isPrivateEntry(JackrabbitAccessControlEntry e) throws RepositoryException {
        return !e.isAllow() && EVERYONE.equals(e.getPrincipal().getName()) && names(e).equals(set(VIEWER_PRIVILEGES));
    }

    /** The role an allow entry stands for, or null when it has another shape. */
    static String roleOf(AccessControlEntry e) {
        Set<String> names = names(e);
        if (names.equals(set(OWNER_PRIVILEGES))) {
            return OWNER;
        }
        if (names.equals(set(EDITOR_PRIVILEGES))) {
            return EDITOR;
        }
        if (names.equals(set(VIEWER_PRIVILEGES))) {
            return VIEWER;
        }
        return null;
    }

    static List<String> privilegesOf(String role) {
        switch (role) {
            case OWNER:
                return OWNER_PRIVILEGES;
            case EDITOR:
                return EDITOR_PRIVILEGES;
            default:
                return VIEWER_PRIVILEGES;
        }
    }

    private static Set<String> names(AccessControlEntry e) {
        Set<String> out = new HashSet<>();
        for (Privilege p : e.getPrivileges()) {
            out.add(p.getName());
        }
        return out;
    }

    private static Set<String> set(List<String> names) {
        return new HashSet<>(names);
    }

    private Privilege[] privileges(String... names) throws RepositoryException {
        Privilege[] out = new Privilege[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = acm.privilegeFromName(names[i]);
        }
        return out;
    }

    private Privilege[] privileges(List<String> names) throws RepositoryException {
        return privileges(names.toArray(new String[0]));
    }

    private Map<String, Object> member(String id, String role) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("role", role);
        String title = id;
        boolean group = false;
        UserManager um = ((JackrabbitSession) session).getUserManager();
        Authorizable a = um.getAuthorizable(id);
        if (a != null) {
            group = a.isGroup();
            String given = value(a, "profile/givenName");
            String family = value(a, "profile/familyName");
            String full = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
            if (!full.isEmpty()) {
                title = full;
            } else if (value(a, "profile/givenName") == null && value(a, "jcr:title") != null) {
                title = value(a, "jcr:title");
            }
        }
        m.put("title", title);
        m.put("group", group);
        return m;
    }

    private static String value(Authorizable a, String name) {
        try {
            javax.jcr.Value[] v = a.getProperty(name);
            return v == null || v.length == 0 ? null : v[0].getString();
        } catch (RepositoryException e) {
            return null;
        }
    }

    private JackrabbitAccessControlList list(String path, boolean create) throws RepositoryException {
        for (AccessControlPolicy p : acm.getPolicies(path)) {
            if (p instanceof JackrabbitAccessControlList) {
                return (JackrabbitAccessControlList) p;
            }
        }
        if (!create) {
            return null;
        }
        AccessControlPolicyIterator it = acm.getApplicablePolicies(path);
        while (it.hasNext()) {
            AccessControlPolicy p = it.nextAccessControlPolicy();
            if (p instanceof JackrabbitAccessControlList) {
                return (JackrabbitAccessControlList) p;
            }
        }
        return null;
    }
}
