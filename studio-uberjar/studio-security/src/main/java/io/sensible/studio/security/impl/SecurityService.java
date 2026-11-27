/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.security.impl;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.principal.PrincipalIterator;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.Impersonation;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;

/**
 * Users, groups and access control entries, as the Sensible Studio security consoles need them. Works with the
 * calling user's session: it can only see and change what that user may.
 *
 * <p>Profile data follows AEM's layout: {@code profile/givenName}, {@code profile/familyName},
 * {@code profile/email} ... below the authorizable's node.
 */
final class SecurityService {

    /** Profile properties shown and edited by the consoles (relative to the authorizable). */
    static final List<String> PROFILE = Collections.unmodifiableList(Arrays.asList(
            "givenName", "familyName", "email", "title", "jobTitle", "gender", "phoneNumber", "aboutMe",
            "street", "city", "postalCode", "country"));

    /** Authorizables that must never be deleted or disabled from the consoles. */
    static final Set<String> PROTECTED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "admin", "anonymous", "everyone", "administrators")));

    private final Session session;
    private final UserManager userManager;
    private final PrincipalManager principalManager;
    private final JackrabbitAccessControlManager acm;

    SecurityService(Session session) throws RepositoryException {
        if (!(session instanceof JackrabbitSession)) {
            throw new RepositoryException("The repository does not support user management");
        }
        this.session = session;
        JackrabbitSession js = (JackrabbitSession) session;
        this.userManager = js.getUserManager();
        this.principalManager = js.getPrincipalManager();
        this.acm = (JackrabbitAccessControlManager) session.getAccessControlManager();
    }

    // ------------------------------------------------------------------ reading

    /** Users or groups, sorted by name; {@code q} filters on id, name and email. */
    List<Map<String, Object>> list(boolean groups, String q, boolean includeSystem) throws RepositoryException {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        Iterator<Authorizable> it = userManager.findAuthorizables("rep:principalName", null,
                groups ? UserManager.SEARCH_TYPE_GROUP : UserManager.SEARCH_TYPE_USER);
        while (it.hasNext()) {
            Authorizable a = it.next();
            if (!includeSystem && !a.isGroup() && ((User) a).isSystemUser()) {
                continue;
            }
            Map<String, Object> s = summary(a);
            if (needle.isEmpty() || matches(s, needle)) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("name")).toLowerCase(Locale.ROOT)));
        return out;
    }

    private static boolean matches(Map<String, Object> s, String needle) {
        for (String k : new String[] {"id", "name", "email"}) {
            Object v = s.get(k);
            if (v != null && v.toString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    Map<String, Object> summary(Authorizable a) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getID());
        m.put("type", a.isGroup() ? "group" : "user");
        m.put("principal", a.getPrincipal().getName());
        m.put("path", a.getPath());
        m.put("name", displayName(a));
        m.put("email", profile(a, "email"));
        if (a.isGroup()) {
            m.put("description", profile(a, "aboutMe"));
        } else {
            User u = (User) a;
            m.put("jobTitle", profile(a, "jobTitle"));
            m.put("disabled", u.isDisabled());
            m.put("system", u.isSystemUser());
            m.put("admin", u.isAdmin());
        }
        m.put("protected", PROTECTED.contains(a.getID()));
        return m;
    }

    static String displayName(Authorizable a) throws RepositoryException {
        String given = profile(a, "givenName");
        String family = profile(a, "familyName");
        String name = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
        if (!name.isEmpty()) {
            return name;
        }
        String title = first(a.getProperty("jcr:title"));
        return title != null ? title : a.getID();
    }

    static String profile(Authorizable a, String name) throws RepositoryException {
        return first(a.getProperty("profile/" + name));
    }

    private static String first(Value[] values) throws RepositoryException {
        return values == null || values.length == 0 ? null : values[0].getString();
    }

    /** Everything the editor shows for one user or group. */
    Map<String, Object> detail(String id) throws RepositoryException {
        Authorizable a = require(id);
        Map<String, Object> m = summary(a);
        Map<String, Object> profile = new LinkedHashMap<>();
        for (String p : PROFILE) {
            profile.put(p, profile(a, p));
        }
        m.put("profile", profile);
        List<Map<String, Object>> memberOf = new ArrayList<>();
        for (Iterator<Group> it = a.declaredMemberOf(); it.hasNext(); ) {
            memberOf.add(summary(it.next()));
        }
        m.put("memberOf", memberOf);
        if (a.isGroup()) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (Iterator<Authorizable> it = ((Group) a).getDeclaredMembers(); it.hasNext(); ) {
                members.add(summary(it.next()));
            }
            m.put("members", members);
        } else {
            User u = (User) a;
            m.put("disabledReason", u.getDisabledReason());
            List<String> impersonators = new ArrayList<>();
            for (PrincipalIterator it = u.getImpersonation().getImpersonators(); it.hasNext(); ) {
                impersonators.add(it.nextPrincipal().getName());
            }
            m.put("impersonators", impersonators);
        }
        return m;
    }

    // ------------------------------------------------------------------ writing users and groups

    Map<String, Object> createUser(String id, String password, Map<String, String> profile) throws RepositoryException {
        if (userManager.getAuthorizable(id) != null) {
            throw new IllegalArgumentException("A user or group with the ID " + id + " already exists");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("A password is required");
        }
        User u = userManager.createUser(id, password);
        writeProfile(u, profile);
        session.save();
        return detail(id);
    }

    Map<String, Object> createGroup(String id, Map<String, String> profile) throws RepositoryException {
        if (userManager.getAuthorizable(id) != null) {
            throw new IllegalArgumentException("A user or group with the ID " + id + " already exists");
        }
        Group g = userManager.createGroup(id);
        writeProfile(g, profile);
        session.save();
        return detail(id);
    }

    /** Profile fields (missing keys are left alone, empty values removed), status and password. */
    Map<String, Object> update(String id, Map<String, String> profile, Boolean disabled, String reason, String password)
            throws RepositoryException {
        Authorizable a = require(id);
        writeProfile(a, profile);
        if (!a.isGroup()) {
            User u = (User) a;
            if (disabled != null) {
                if (disabled && PROTECTED.contains(id)) {
                    throw new IllegalArgumentException(id + " cannot be disabled");
                }
                u.disable(disabled ? (reason == null || reason.isEmpty() ? "Disabled in Sensible Studio" : reason) : null);
            }
            if (password != null && !password.isEmpty()) {
                u.changePassword(password);
            }
        }
        session.save();
        return detail(id);
    }

    private void writeProfile(Authorizable a, Map<String, String> profile) throws RepositoryException {
        if (profile == null) {
            return;
        }
        ValueFactory vf = session.getValueFactory();
        for (Map.Entry<String, String> e : profile.entrySet()) {
            if (!PROFILE.contains(e.getKey())) {
                continue;
            }
            String path = "profile/" + e.getKey();
            if (e.getValue() == null || e.getValue().trim().isEmpty()) {
                if (a.hasProperty(path)) {
                    a.removeProperty(path);
                }
            } else {
                a.setProperty(path, vf.createValue(e.getValue().trim()));
            }
        }
    }

    /** Makes {@code id} a declared member of exactly {@code groups}. */
    Map<String, Object> setMemberOf(String id, Collection<String> groups) throws RepositoryException {
        Authorizable a = require(id);
        Set<String> wanted = new LinkedHashSet<>(groups);
        Set<String> current = new LinkedHashSet<>();
        for (Iterator<Group> it = a.declaredMemberOf(); it.hasNext(); ) {
            current.add(it.next().getID());
        }
        for (String g : current) {
            if (!wanted.contains(g)) {
                group(g).removeMember(a);
            }
        }
        for (String g : wanted) {
            if (!current.contains(g)) {
                if (g.equals(id)) {
                    throw new IllegalArgumentException("A group cannot be a member of itself");
                }
                group(g).addMember(a);
            }
        }
        session.save();
        return detail(id);
    }

    /** Makes exactly {@code members} the declared members of group {@code id}. */
    Map<String, Object> setMembers(String id, Collection<String> members) throws RepositoryException {
        Group g = group(id);
        Set<String> wanted = new LinkedHashSet<>(members);
        Set<String> current = new LinkedHashSet<>();
        for (Iterator<Authorizable> it = g.getDeclaredMembers(); it.hasNext(); ) {
            current.add(it.next().getID());
        }
        for (String m : current) {
            if (!wanted.contains(m)) {
                g.removeMember(require(m));
            }
        }
        for (String m : wanted) {
            if (!current.contains(m)) {
                if (m.equals(id)) {
                    throw new IllegalArgumentException("A group cannot be a member of itself");
                }
                g.addMember(require(m));
            }
        }
        session.save();
        return detail(id);
    }

    Map<String, Object> setImpersonators(String id, Collection<String> principals) throws RepositoryException {
        Authorizable a = require(id);
        if (a.isGroup()) {
            throw new IllegalArgumentException("Groups have no impersonators");
        }
        Impersonation imp = ((User) a).getImpersonation();
        Set<String> wanted = new LinkedHashSet<>(principals);
        List<String> current = new ArrayList<>();
        for (PrincipalIterator it = imp.getImpersonators(); it.hasNext(); ) {
            current.add(it.nextPrincipal().getName());
        }
        for (String p : current) {
            if (!wanted.contains(p)) {
                imp.revokeImpersonation(principal(p));
            }
        }
        for (String p : wanted) {
            if (!current.contains(p)) {
                imp.grantImpersonation(principal(p));
            }
        }
        session.save();
        return detail(id);
    }

    void delete(String id) throws RepositoryException {
        Authorizable a = require(id);
        if (PROTECTED.contains(id) || !a.isGroup() && (((User) a).isSystemUser() || ((User) a).isAdmin())) {
            throw new IllegalArgumentException(id + " cannot be deleted");
        }
        forget(a);
        a.remove();
        session.save();
    }

    /**
     * Takes what a user or group was allowed, anywhere, away with it. The repository keeps the entries of a
     * principal that is gone, and they would be the rights of whoever is next given the same name.
     */
    private void forget(Authorizable a) throws RepositoryException {
        String name = a.getPrincipal().getName();
        String home = a.getPath();
        Set<String> paths = new TreeSet<>();
        String stmt = "SELECT * FROM [rep:ACE] AS a WHERE a.[rep:principalName] = '" + name.replace("'", "''") + "'";
        NodeIterator nodes = session.getWorkspace().getQueryManager().createQuery(stmt, Query.JCR_SQL2).execute().getNodes();
        while (nodes.hasNext()) {
            Node policy = nodes.nextNode().getParent();
            if ("rep:policy".equals(policy.getName())) {
                paths.add(policy.getParent().getPath());
            } else if ("rep:repoPolicy".equals(policy.getName())) {
                paths.add("");
            }
        }
        for (String p : paths) {
            // its own home goes with it
            if (p.equals(home) || p.startsWith(home + "/")) {
                continue;
            }
            String path = p.isEmpty() ? null : p;
            JackrabbitAccessControlList acl = acl(path, false);
            if (acl == null) {
                continue;
            }
            boolean changed = false;
            for (AccessControlEntry e : acl.getAccessControlEntries()) {
                if (e.getPrincipal().getName().equals(name)) {
                    acl.removeAccessControlEntry(e);
                    changed = true;
                }
            }
            if (changed) {
                acm.setPolicy(path, acl);
            }
        }
    }

    // ------------------------------------------------------------------ permissions

    /** Access control entries of a principal, wherever they are in the repository. */
    List<Map<String, Object>> aces(String principalName) throws RepositoryException {
        Principal principal = principal(principalName);
        Set<String> paths = new TreeSet<>();
        String stmt = "SELECT * FROM [rep:ACE] AS a WHERE a.[rep:principalName] = '" + principalName.replace("'", "''") + "'";
        NodeIterator nodes = session.getWorkspace().getQueryManager().createQuery(stmt, Query.JCR_SQL2).execute().getNodes();
        while (nodes.hasNext()) {
            Node policy = nodes.nextNode().getParent();
            String name = policy.getName();
            if ("rep:policy".equals(name)) {
                paths.add(policy.getParent().getPath());
            } else if ("rep:repoPolicy".equals(name)) {
                paths.add("");
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String path : paths) {
            JackrabbitAccessControlList acl = acl(path.isEmpty() ? null : path, false);
            if (acl == null) {
                continue;
            }
            AccessControlEntry[] entries = acl.getAccessControlEntries();
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] instanceof JackrabbitAccessControlEntry
                        && entries[i].getPrincipal().getName().equals(principal.getName())) {
                    out.add(entry(path.isEmpty() ? null : path, i, (JackrabbitAccessControlEntry) entries[i]));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> entry(String path, int index, JackrabbitAccessControlEntry e) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("index", index);
        m.put("allow", e.isAllow());
        List<String> privileges = new ArrayList<>();
        for (Privilege p : e.getPrivileges()) {
            privileges.add(p.getName());
        }
        m.put("privileges", privileges);
        Map<String, Object> restrictions = new LinkedHashMap<>();
        for (String r : e.getRestrictionNames()) {
            Value v = e.getRestriction(r);
            if (v != null) {
                restrictions.put(r, v.getString());
            } else {
                Value[] vs = e.getRestrictions(r);
                List<String> list = new ArrayList<>();
                if (vs != null) {
                    for (Value x : vs) {
                        list.add(x.getString());
                    }
                }
                restrictions.put(r, list);
            }
        }
        m.put("restrictions", restrictions);
        return m;
    }

    /**
     * Adds an entry for {@code principal} at {@code path}, or replaces the one at {@code replaceIndex}
     * (keeping its position). {@code glob} becomes the {@code rep:glob} restriction.
     */
    Map<String, Object> saveAce(String principalName, String path, boolean allow, Collection<String> privilegeNames,
            String glob, Integer replaceIndex) throws RepositoryException {
        if (privilegeNames.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one privilege");
        }
        if (path != null && !session.nodeExists(path)) {
            throw new IllegalArgumentException(path + " does not exist");
        }
        Principal principal = principal(principalName);
        JackrabbitAccessControlList acl = acl(path, true);
        Privilege[] privileges = new Privilege[privilegeNames.size()];
        int i = 0;
        for (String p : privilegeNames) {
            privileges[i++] = acm.privilegeFromName(p);
        }
        Map<String, Value> restrictions = new LinkedHashMap<>();
        if (glob != null && !glob.isEmpty() && path != null) {
            restrictions.put("rep:glob", session.getValueFactory().createValue(glob));
        }
        AccessControlEntry before = null;
        if (replaceIndex != null) {
            AccessControlEntry[] entries = acl.getAccessControlEntries();
            AccessControlEntry old = entryOf(entries, replaceIndex, principal);
            before = replaceIndex + 1 < entries.length ? entries[replaceIndex + 1] : null;
            acl.removeAccessControlEntry(old);
        }
        acl.addEntry(principal, privileges, allow, restrictions);
        if (before != null) {
            AccessControlEntry[] entries = acl.getAccessControlEntries();
            acl.orderBefore(entries[entries.length - 1], before);
        }
        acm.setPolicy(path, acl);
        session.save();
        return Collections.singletonMap("aces", aces(principalName));
    }

    Map<String, Object> removeAce(String principalName, String path, int index) throws RepositoryException {
        Principal principal = principal(principalName);
        JackrabbitAccessControlList acl = acl(path, false);
        if (acl == null) {
            throw new IllegalArgumentException("No access control list at " + path);
        }
        acl.removeAccessControlEntry(entryOf(acl.getAccessControlEntries(), index, principal));
        acm.setPolicy(path, acl);
        session.save();
        return Collections.singletonMap("aces", aces(principalName));
    }

    private static AccessControlEntry entryOf(AccessControlEntry[] entries, int index, Principal principal) {
        if (index < 0 || index >= entries.length || !entries[index].getPrincipal().getName().equals(principal.getName())) {
            throw new IllegalArgumentException("The entry changed in the meantime; reload and try again");
        }
        return entries[index];
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

    /**
     * What a principal may do at a path, including what it gets through its groups and
     * {@code everyone}.
     */
    Map<String, Object> effective(String principalName, String path) throws RepositoryException {
        if (!session.nodeExists(path)) {
            throw new IllegalArgumentException(path + " does not exist");
        }
        Principal principal = principal(principalName);
        Set<Principal> principals = new LinkedHashSet<>();
        principals.add(principal);
        for (PrincipalIterator it = principalManager.getGroupMembership(principal); it.hasNext(); ) {
            principals.add(it.nextPrincipal());
        }
        Set<String> names = new TreeSet<>();
        for (Privilege p : acm.getPrivileges(path, principals)) {
            names.add(p.getName());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String[] check : new String[][] {
                {"read", Privilege.JCR_READ},
                {"modify", Privilege.JCR_MODIFY_PROPERTIES},
                {"create", Privilege.JCR_ADD_CHILD_NODES},
                {"delete", Privilege.JCR_REMOVE_NODE},
                {"readAcl", Privilege.JCR_READ_ACCESS_CONTROL},
                {"editAcl", Privilege.JCR_MODIFY_ACCESS_CONTROL}}) {
            summary.put(check[0], acm.hasPrivileges(path, principals, new Privilege[] {acm.privilegeFromName(check[1])}));
        }
        List<String> via = new ArrayList<>();
        for (Principal p : principals) {
            via.add(p.getName());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("principal", principalName);
        m.put("privileges", names);
        m.put("summary", summary);
        m.put("principals", via);
        return m;
    }

    /** Privileges that can be granted at the root, aggregates first. */
    List<Map<String, Object>> privileges() throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Privilege p : acm.getSupportedPrivileges("/")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("aggregate", p.isAggregate());
            List<String> contains = new ArrayList<>();
            for (Privilege d : p.getDeclaredAggregatePrivileges()) {
                contains.add(d.getName());
            }
            m.put("contains", contains);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("aggregate"))
                .thenComparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private Authorizable require(String id) throws RepositoryException {
        Authorizable a = id == null ? null : userManager.getAuthorizable(id);
        if (a == null) {
            throw new IllegalArgumentException("No user or group " + id);
        }
        return a;
    }

    private Group group(String id) throws RepositoryException {
        Authorizable a = require(id);
        if (!a.isGroup()) {
            throw new IllegalArgumentException(id + " is not a group");
        }
        return (Group) a;
    }

    private Principal principal(String name) {
        Principal p = name == null ? null : principalManager.getPrincipal(name);
        if (p == null) {
            throw new IllegalArgumentException("No principal " + name);
        }
        return p;
    }
}
