/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.props;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.AccessControlEntry;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlEntry;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;

/**
 * The Permissions tab of the page properties, as in AEM: five permissions per user or group on the page, written as
 * access control entries on the page node, the page's closed user group (Oak CUG, {@code rep:cugPolicy}), and the
 * effective permissions of everyone the ACLs of the page and its ancestors name.
 *
 * <table>
 *   <caption>Permissions and the entries they stand for</caption>
 *   <tr><th>Browse page</th><td>allow {@code jcr:read}</td></tr>
 *   <tr><th>Edit content</th><td>allow modify, add and remove nodes, node types, versions and locks on
 *       {@code jcr:content} and below ({@code rep:glob = /jcr:content*})</td></tr>
 *   <tr><th>Delete page</th><td>allow {@code jcr:removeNode}, {@code jcr:removeChildNodes}</td></tr>
 *   <tr><th>Publish/unpublish page</th><td>allow {@code crx:replicate}</td></tr>
 *   <tr><th>Create sub-pages</th><td>allow {@code jcr:addChildNodes}, {@code jcr:nodeTypeManagement}, and
 *       {@code jcr:modifyProperties} below the page ({@code rep:glob = /*})</td></tr>
 * </table>
 * Saving the permissions of a principal replaces its allow entries of these shapes and keeps every other entry.
 */
public final class PagePermissions {

    public static final String[] FLAGS = {"read", "modify", "delete", "replicate", "create"};

    static final String CONTENT_GLOB = "/jcr:content*";
    static final String BELOW_GLOB = "/*";
    static final String GLOB = "rep:glob";
    static final String OTHER = "other";

    static final List<String> READ = Collections.singletonList(Privilege.JCR_READ);
    static final List<String> MODIFY = Arrays.asList(Privilege.JCR_MODIFY_PROPERTIES, Privilege.JCR_ADD_CHILD_NODES,
            Privilege.JCR_REMOVE_CHILD_NODES, Privilege.JCR_REMOVE_NODE, Privilege.JCR_NODE_TYPE_MANAGEMENT,
            Privilege.JCR_VERSION_MANAGEMENT, Privilege.JCR_LOCK_MANAGEMENT);
    static final List<String> DELETE = Arrays.asList(Privilege.JCR_REMOVE_NODE, Privilege.JCR_REMOVE_CHILD_NODES);
    static final List<String> REPLICATE = Collections.singletonList("crx:replicate");
    static final List<String> CREATE = Arrays.asList(Privilege.JCR_ADD_CHILD_NODES, Privilege.JCR_NODE_TYPE_MANAGEMENT);
    static final List<String> CREATE_BELOW = Collections.singletonList(Privilege.JCR_MODIFY_PROPERTIES);

    private final Session session;
    private final JackrabbitAccessControlManager acm;
    private final PrincipalManager principals;
    private final UserManager users;

    public PagePermissions(Session session) throws RepositoryException {
        if (!(session instanceof JackrabbitSession) || !(session.getAccessControlManager() instanceof JackrabbitAccessControlManager)) {
            throw new RepositoryException("Permissions need a Jackrabbit / Oak repository");
        }
        this.session = session;
        this.acm = (JackrabbitAccessControlManager) session.getAccessControlManager();
        this.principals = ((JackrabbitSession) session).getPrincipalManager();
        this.users = ((JackrabbitSession) session).getUserManager();
    }

    // ------------------------------------------------------------------ reading

    /** The permissions set on the page itself, per principal. */
    public List<Map<String, Object>> acl(String path) throws RepositoryException {
        Map<String, Map<String, Object>> rows = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        JackrabbitAccessControlList list = list(path, false);
        if (list != null) {
            for (AccessControlEntry e : list.getAccessControlEntries()) {
                JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
                String name = je.getPrincipal().getName();
                Map<String, Object> row = rows.computeIfAbsent(name, n -> row(n));
                String kind = je.isAllow() ? classify(je) : null;
                if (kind == null) {
                    row.put("other", true);
                } else {
                    for (String flag : kind.split(",")) {
                        row.put(flag, true);
                    }
                }
            }
        }
        // "create" needs both of its entries
        for (Map<String, Object> row : rows.values()) {
            boolean create = Boolean.TRUE.equals(row.remove("create:here")) && Boolean.TRUE.equals(row.remove("create:below"));
            row.put("create", create || Boolean.TRUE.equals(row.get("create")));
            row.remove("create:here");
            row.remove("create:below");
        }
        return new ArrayList<>(rows.values());
    }

    /** What every principal the ACLs of the page and its ancestors name may do on the page. */
    public List<Map<String, Object>> effective(String path) throws RepositoryException {
        Set<String> names = new LinkedHashSet<>();
        for (AccessControlPolicy p : acm.getEffectivePolicies(path)) {
            if (p instanceof JackrabbitAccessControlList) {
                for (AccessControlEntry e : ((JackrabbitAccessControlList) p).getAccessControlEntries()) {
                    names.add(e.getPrincipal().getName());
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : names) {
            Principal principal = principals.getPrincipal(name);
            if (principal == null) {
                continue;
            }
            Set<Principal> set = principalSet(principal);
            Map<String, Object> row = row(name);
            row.put("read", has(path, set, READ));
            row.put("modify", has(path + "/jcr:content", set, Arrays.asList(Privilege.JCR_MODIFY_PROPERTIES,
                    Privilege.JCR_ADD_CHILD_NODES, Privilege.JCR_REMOVE_CHILD_NODES)));
            row.put("delete", has(path, set, Collections.singletonList(Privilege.JCR_REMOVE_NODE)));
            row.put("replicate", has(path, set, REPLICATE));
            row.put("create", has(path, set, CREATE));
            out.add(row);
        }
        out.sort((a, b) -> String.valueOf(a.get("principal")).compareToIgnoreCase(String.valueOf(b.get("principal"))));
        return out;
    }

    /** The page's closed user group: whether CUGs apply here, and its principals. */
    public Map<String, Object> cug(String path) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        AccessControlPolicy existing = cugPolicy(path, false);
        AccessControlPolicy applicable = existing != null ? existing : cugPolicy(path, true);
        m.put("supported", applicable != null);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (existing != null) {
            for (String name : cugNames(existing)) {
                rows.add(row(name));
            }
        }
        m.put("principals", rows);
        return m;
    }

    // ------------------------------------------------------------------ writing

    /** Replaces the permissions of a principal on the page (see the class comment); nothing is saved. */
    public void save(String path, String principalName, Set<String> flags) throws RepositoryException {
        Principal principal = principal(principalName);
        JackrabbitAccessControlList list = list(path, true);
        Set<String> managed = new HashSet<>();
        for (List<String> l : Arrays.asList(READ, DELETE, REPLICATE, CREATE)) {
            managed.addAll(leaves(l));
        }
        List<Set<String>> keep = new ArrayList<>();
        for (AccessControlEntry e : list.getAccessControlEntries()) {
            JackrabbitAccessControlEntry je = (JackrabbitAccessControlEntry) e;
            String kind = je.isAllow() && je.getPrincipal().getName().equals(principal.getName()) ? classify(je) : null;
            if (kind == null) {
                continue;
            }
            list.removeAccessControlEntry(e);
            if (kind.endsWith(OTHER)) {
                // an entry merged with ours: keep what is not ours
                List<String> names = new ArrayList<>();
                for (Privilege p : je.getPrivileges()) {
                    names.add(p.getName());
                }
                Set<String> rest = leaves(names);
                rest.removeAll(managed);
                keep.add(rest);
            }
        }
        for (Set<String> rest : keep) {
            add(list, principal, new ArrayList<>(rest), null);
        }
        if (flags.contains("read")) {
            add(list, principal, READ, null);
        }
        if (flags.contains("modify")) {
            add(list, principal, MODIFY, CONTENT_GLOB);
        }
        if (flags.contains("delete")) {
            add(list, principal, DELETE, null);
        }
        if (flags.contains("replicate")) {
            add(list, principal, REPLICATE, null);
        }
        if (flags.contains("create")) {
            add(list, principal, CREATE, null);
            add(list, principal, CREATE_BELOW, BELOW_GLOB);
        }
        acm.setPolicy(path, list);
    }

    /** Removes every entry of a principal on the page; nothing is saved. */
    public void remove(String path, String principalName) throws RepositoryException {
        JackrabbitAccessControlList list = list(path, false);
        if (list == null) {
            return;
        }
        for (AccessControlEntry e : list.getAccessControlEntries()) {
            if (e.getPrincipal().getName().equals(principalName)) {
                list.removeAccessControlEntry(e);
            }
        }
        acm.setPolicy(path, list);
    }

    /** Sets the page's closed user group; no principals removes it. Nothing is saved. */
    public void setCug(String path, List<String> names) throws RepositoryException {
        AccessControlPolicy policy = cugPolicy(path, false);
        if (policy == null) {
            if (names.isEmpty()) {
                return;
            }
            policy = cugPolicy(path, true);
            if (policy == null) {
                throw new RepositoryException("Closed user groups are not supported at " + path);
            }
        }
        Set<Principal> wanted = new LinkedHashSet<>();
        for (String n : names) {
            wanted.add(principal(n));
        }
        Set<String> current = cugNames(policy);
        List<Principal> drop = new ArrayList<>();
        for (String n : current) {
            boolean keep = false;
            for (Principal p : wanted) {
                keep |= p.getName().equals(n);
            }
            if (!keep) {
                Principal p = principals.getPrincipal(n);
                drop.add(p != null ? p : () -> n);
            }
        }
        // each array is ONE argument (the methods take Principal...): passed bare, an array becomes the
        // argument list itself and no method matches ("Unsupported closed user group policy")
        if (!drop.isEmpty()) {
            invoke(policy, "removePrincipals", (Object) drop.toArray(new Principal[0]));
        }
        if (!wanted.isEmpty()) {
            invoke(policy, "addPrincipals", (Object) wanted.toArray(new Principal[0]));
        }
        if (names.isEmpty()) {
            acm.removePolicy(path, policy);
        } else {
            acm.setPolicy(path, policy);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> row(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("principal", name);
        try {
            Authorizable a = users.getAuthorizable(principals.getPrincipal(name) == null ? () -> name : principals.getPrincipal(name));
            m.put("group", a != null && a.isGroup());
            Value[] given = a == null ? null : a.getProperty("profile/givenName");
            Value[] family = a == null ? null : a.getProperty("profile/familyName");
            String title = ((given != null && given.length > 0 ? given[0].getString() : "") + " "
                    + (family != null && family.length > 0 ? family[0].getString() : "")).trim();
            if (title.isEmpty() && a != null && a.isGroup()) {
                Value[] gn = a.getProperty("profile/givenName");
                title = gn != null && gn.length > 0 ? gn[0].getString() : "";
            }
            if (!title.isEmpty()) {
                m.put("title", title);
            }
        } catch (RepositoryException e) {
            m.put("group", false);
        }
        for (String f : FLAGS) {
            m.put(f, false);
        }
        return m;
    }

    /** @return the permission flags an allow entry stands for (comma separated), or {@code null} if none */
    String classify(JackrabbitAccessControlEntry e) throws RepositoryException {
        List<String> names = new ArrayList<>();
        for (Privilege p : e.getPrivileges()) {
            names.add(p.getName());
        }
        Set<String> privs = leaves(names);
        String glob = glob(e);
        if (glob == null) {
            if (privs.containsAll(leaves(Collections.singletonList(Privilege.JCR_ALL)))) {
                return "read,modify,delete,replicate,create";
            }
            List<String> kinds = new ArrayList<>();
            Set<String> rest = new HashSet<>(privs);
            String[] kindNames = {"read", "delete", "replicate", "create:here"};
            List<List<String>> kindPrivs = Arrays.asList(READ, DELETE, REPLICATE, CREATE);
            for (int i = 0; i < kindNames.length; i++) {
                Set<String> k = leaves(kindPrivs.get(i));
                if (!k.isEmpty() && rest.containsAll(k)) {
                    kinds.add(kindNames[i]);
                    rest.removeAll(k);
                }
            }
            if (kinds.isEmpty()) {
                return null;
            }
            // Oak merges entries of one principal with the same restrictions: ours may share one with others
            return String.join(",", kinds) + (rest.isEmpty() ? "" : "," + OTHER);
        }
        if (CONTENT_GLOB.equals(glob) && privs.equals(leaves(MODIFY))) {
            return "modify";
        }
        if (BELOW_GLOB.equals(glob) && privs.equals(leaves(CREATE_BELOW))) {
            return "create:below";
        }
        return null;
    }

    /** the non-aggregate privileges the named ones stand for */
    private Set<String> leaves(List<String> names) {
        Set<String> out = new HashSet<>();
        for (String n : names) {
            try {
                Privilege p = acm.privilegeFromName(n);
                if (!p.isAggregate()) {
                    out.add(p.getName());
                }
                for (Privilege a : p.getAggregatePrivileges()) {
                    if (!a.isAggregate()) {
                        out.add(a.getName());
                    }
                }
            } catch (RepositoryException e) {
                // a privilege this repository does not know (crx:replicate before it is registered)
            }
        }
        return out;
    }

    private static String glob(JackrabbitAccessControlEntry e) throws RepositoryException {
        String[] names = e.getRestrictionNames();
        if (names.length == 0) {
            return null;
        }
        if (names.length > 1 || !GLOB.equals(names[0])) {
            return "\u0000other";
        }
        Value v = e.getRestriction(GLOB);
        return v == null ? null : v.getString();
    }

    private void add(JackrabbitAccessControlList list, Principal principal, List<String> privileges, String glob)
            throws RepositoryException {
        Privilege[] privs = new Privilege[privileges.size()];
        for (int i = 0; i < privs.length; i++) {
            privs[i] = acm.privilegeFromName(privileges.get(i));
        }
        Map<String, Value> restrictions = new LinkedHashMap<>();
        if (glob != null) {
            restrictions.put(GLOB, session.getValueFactory().createValue(glob));
        }
        list.addEntry(principal, privs, true, restrictions);
    }

    private boolean has(String path, Set<Principal> set, List<String> privileges) {
        try {
            if (!session.nodeExists(path)) {
                return false;
            }
            Privilege[] privs = new Privilege[privileges.size()];
            for (int i = 0; i < privs.length; i++) {
                privs[i] = acm.privilegeFromName(privileges.get(i));
            }
            return acm.hasPrivileges(path, set, privs);
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** the principal, its groups (declared and inherited) and everyone */
    private Set<Principal> principalSet(Principal principal) throws RepositoryException {
        Set<Principal> set = new LinkedHashSet<>();
        set.add(principal);
        set.add(principals.getEveryone());
        Authorizable a = users.getAuthorizable(principal);
        if (a != null) {
            for (Iterator<Group> it = a.memberOf(); it.hasNext();) {
                set.add(it.next().getPrincipal());
            }
        }
        return set;
    }

    private Principal principal(String name) throws RepositoryException {
        Principal p = name == null ? null : principals.getPrincipal(name);
        if (p == null) {
            throw new RepositoryException("No user or group " + name);
        }
        return p;
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
        for (AccessControlPolicyIterator it = acm.getApplicablePolicies(path); it.hasNext();) {
            AccessControlPolicy p = it.nextAccessControlPolicy();
            if (p instanceof JackrabbitAccessControlList) {
                return (JackrabbitAccessControlList) p;
            }
        }
        throw new RepositoryException("No access control list can be set at " + path);
    }

    /** Oak's CugPolicy, found by its methods (the CUG module's API is not a dependency of this bundle). */
    private AccessControlPolicy cugPolicy(String path, boolean applicable) throws RepositoryException {
        if (applicable) {
            for (AccessControlPolicyIterator it = acm.getApplicablePolicies(path); it.hasNext();) {
                AccessControlPolicy p = it.nextAccessControlPolicy();
                if (isCug(p)) {
                    return p;
                }
            }
            return null;
        }
        for (AccessControlPolicy p : acm.getPolicies(path)) {
            if (isCug(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean isCug(AccessControlPolicy p) {
        for (Class<?> c = p.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().endsWith(".CugPolicy")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** the names of {@code CugPolicy.getPrincipals()} */
    private static Set<String> cugNames(AccessControlPolicy policy) throws RepositoryException {
        Object principals = invoke(policy, "getPrincipals");
        Set<String> out = new LinkedHashSet<>();
        if (principals instanceof Iterable) {
            for (Object p : (Iterable<?>) principals) {
                if (p instanceof Principal) {
                    out.add(((Principal) p).getName());
                }
            }
        }
        return out;
    }

    private static Object invoke(AccessControlPolicy policy, String method, Object... args) throws RepositoryException {
        try {
            for (Class<?> c = policy.getClass(); c != null; c = c.getSuperclass()) {
                for (Class<?> i : c.getInterfaces()) {
                    if (!i.getName().endsWith(".CugPolicy")) {
                        continue;
                    }
                    for (Method m : i.getMethods()) {
                        if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                            return m.invoke(policy, args);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RepositoryException(cause.getMessage(), cause);
        }
        throw new RepositoryException("Unsupported closed user group policy");
    }
}
