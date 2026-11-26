/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.jcr.security.AccessControlPolicyIterator;
import javax.jcr.security.Privilege;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries a closed user group (Oak's {@code rep:cugPolicy}: who may see a page or a folder on the
 * published site) from the author to the publish with the content it is set on.
 *
 * <p>The policy node is protected: it cannot be written like content, only through the access
 * control API. So a pack names the principals ({@code "cug": [...]} on the node) and the
 * receiving side sets the policy. Oak's CUG API is found by its methods; the module is not a
 * build dependency of this bundle.</p>
 *
 * <p>Two cautions, both on the side of not opening what was closed:
 * a pack says nothing about the closed user group when the packing session may not read access
 * control, and then the publish keeps what it has; and when none of the named principals exists
 * on the publish, the node is closed to everybody but administrators rather than left open.</p>
 */
final class CugTransfer {

    private static final Logger LOG = LoggerFactory.getLogger(CugTransfer.class);
    static final String POLICY_NODE = "rep:cugPolicy";
    static final String KEY = "cug";
    /** Who a node is closed for when none of the wanted principals exists here. */
    static final String FALLBACK = "administrators";

    /** Sets the closed user group of a node; replaceable in tests (the test repository has no CUG module). */
    interface Applier {
        void apply(Session session, String path, List<String> names) throws RepositoryException;
    }

    static volatile Applier applier = CugTransfer::apply;

    private CugTransfer() {
    }

    /**
     * The principals of the node's closed user group (empty: none), or null when this session
     * cannot tell (it may not read access control there).
     */
    static List<String> read(Node node) throws RepositoryException {
        Session session = node.getSession();
        AccessControlManager acm = session.getAccessControlManager();
        if (!acm.hasPrivileges(node.getPath(), new Privilege[] {acm.privilegeFromName(Privilege.JCR_READ_ACCESS_CONTROL)})) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (node.hasNode(POLICY_NODE)) {
            Node policy = node.getNode(POLICY_NODE);
            if (policy.hasProperty("rep:principalNames")) {
                Property p = policy.getProperty("rep:principalNames");
                for (Value v : p.isMultiple() ? p.getValues() : new Value[] {p.getValue()}) {
                    names.add(v.getString());
                }
            }
        }
        return names;
    }

    /** Sets the closed user group at {@code path} to {@code names}; none removes it. Nothing is saved. */
    static void apply(Session session, String path, List<String> names) throws RepositoryException {
        AccessControlManager acm = session.getAccessControlManager();
        AccessControlPolicy policy = null;
        for (AccessControlPolicy p : acm.getPolicies(path)) {
            if (isCug(p)) {
                policy = p;
            }
        }
        if (names.isEmpty()) {
            if (policy != null) {
                acm.removePolicy(path, policy);
            }
            return;
        }
        if (policy == null) {
            for (AccessControlPolicyIterator it = acm.getApplicablePolicies(path); it.hasNext();) {
                AccessControlPolicy p = it.nextAccessControlPolicy();
                if (isCug(p)) {
                    policy = p;
                }
            }
        }
        if (policy == null) {
            // no CUG module here, or a path it does not cover: say so, the content itself is still delivered
            LOG.warn("The closed user group of {} ({}) cannot be set on this instance: closed user groups are not available at that path",
                    path, names);
            return;
        }
        Set<Principal> wanted = new LinkedHashSet<>();
        for (String n : names) {
            Principal p = principal(session, n);
            if (p != null) {
                wanted.add(p);
            } else {
                LOG.warn("Closed user group of {}: there is no user or group '{}' on this instance (publish it first)", path, n);
            }
        }
        if (wanted.isEmpty()) {
            Principal admins = principal(session, FALLBACK);
            if (admins == null) {
                throw new RepositoryException("The closed user group of " + path + " names nobody who exists on this instance");
            }
            LOG.warn("Closed user group of {}: none of {} exists here; the node is closed to everybody but {}", path, names, FALLBACK);
            wanted.add(admins);
        }
        Set<String> wantedNames = new LinkedHashSet<>();
        for (Principal p : wanted) {
            wantedNames.add(p.getName());
        }
        List<Principal> drop = new ArrayList<>();
        Object current = invoke(policy, "getPrincipals");
        if (current instanceof Iterable) {
            for (Object o : (Iterable<?>) current) {
                if (o instanceof Principal && !wantedNames.contains(((Principal) o).getName())) {
                    drop.add((Principal) o);
                }
            }
        }
        // the array is ONE argument (a Principal... parameter), not one argument per principal
        if (!drop.isEmpty()) {
            invoke(policy, "removePrincipals", (Object) drop.toArray(new Principal[0]));
        }
        invoke(policy, "addPrincipals", (Object) wanted.toArray(new Principal[0]));
        acm.setPolicy(path, policy);
    }

    /**
     * The user or group of that name here, or null. Asked of the session's principal manager by
     * name (a Jackrabbit session has one): the Jackrabbit API is not imported, so this bundle
     * resolves whatever version of it the instance carries.
     */
    private static Principal principal(Session session, String name) {
        try {
            Object manager = session.getClass().getMethod("getPrincipalManager").invoke(session);
            Method get = manager.getClass().getMethod("getPrincipal", String.class);
            get.setAccessible(true);
            Object p = get.invoke(manager, name);
            return p instanceof Principal ? (Principal) p : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Users and groups cannot be looked up on this instance: {}", e.toString());
            return null;
        }
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
