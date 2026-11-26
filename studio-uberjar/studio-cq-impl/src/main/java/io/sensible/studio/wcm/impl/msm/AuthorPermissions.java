/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.security.Principal;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.JackrabbitAccessControlManager;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.resource.ResourceResolver;

/**
 * What the author of a blueprint change may do, evaluated with a service session: push on modify runs as a service
 * user, so it only rolls out to live copies whose target the author could change and whose source the author could
 * read. Admins (and members of {@code administrators}) may do everything. Without a JCR session nothing is allowed.
 */
final class AuthorPermissions {

    private final JackrabbitAccessControlManager acm;
    private final Set<Principal> principals;
    private final boolean admin;
    private final Session session;

    private AuthorPermissions(Session session, JackrabbitAccessControlManager acm, Set<Principal> principals, boolean admin) {
        this.session = session;
        this.acm = acm;
        this.principals = principals;
        this.admin = admin;
    }

    /** @return the permissions of {@code userId}, or {@code null} if they cannot be evaluated (then: deny) */
    static AuthorPermissions of(ResourceResolver serviceResolver, String userId) {
        Session session = serviceResolver.adaptTo(Session.class);
        if (userId == null || !(session instanceof JackrabbitSession)) {
            return null;
        }
        try {
            JackrabbitSession js = (JackrabbitSession) session;
            Authorizable a = js.getUserManager().getAuthorizable(userId);
            if (a == null || a.isGroup()) {
                return null;
            }
            Set<Principal> principals = new LinkedHashSet<>();
            principals.add(a.getPrincipal());
            boolean admin = a instanceof User && ((User) a).isAdmin();
            for (Iterator<Group> it = a.memberOf(); it.hasNext();) {
                Group g = it.next();
                principals.add(g.getPrincipal());
                admin |= "administrators".equals(g.getID());
            }
            PrincipalManager pm = js.getPrincipalManager();
            principals.add(pm.getEveryone());
            AccessControlManager acm = session.getAccessControlManager();
            if (!(acm instanceof JackrabbitAccessControlManager)) {
                return null;
            }
            return new AuthorPermissions(session, (JackrabbitAccessControlManager) acm, principals, admin);
        } catch (RepositoryException e) {
            return null;
        }
    }

    boolean canRead(String path) {
        return has(path, Privilege.JCR_READ);
    }

    /** Add, change and remove content at {@code path}. */
    boolean canWrite(String path) {
        return has(path, Privilege.JCR_MODIFY_PROPERTIES) && has(path, Privilege.JCR_ADD_CHILD_NODES)
                && has(path, Privilege.JCR_REMOVE_CHILD_NODES);
    }

    private boolean has(String path, String privilege) {
        if (admin) {
            return true;
        }
        try {
            String p = existing(path);
            return p != null && acm.hasPrivileges(p, principals, new Privilege[] {acm.privilegeFromName(privilege)});
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** The path itself or its nearest existing ancestor (a page about to be created or already deleted). */
    private String existing(String path) throws RepositoryException {
        String p = path;
        while (p != null && !p.isEmpty() && !session.nodeExists(p)) {
            int i = p.lastIndexOf('/');
            p = i <= 0 ? null : p.substring(0, i);
        }
        return p;
    }
}
