/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * Who may act for others in the DAM (check in someone else's asset, see every report and link,
 * change Smart Tags): {@code admin}, and anyone with full control ({@code jcr:all}) of
 * {@code /content/dam}, as members of {@code administrators} have. A permission rather than a
 * group name: it works whatever the groups are called, and reading one's own groups needs
 * rights a user usually does not have.
 */
final class DamAccess {

    static final String ADMIN = "admin";

    private DamAccess() {
    }

    /**
     * What the user may do at {@code path}, for the UI to show only the actions that will work
     * (the repository still decides each one): {@code canCreate} (add files and folders inside),
     * {@code canEdit} (change properties and metadata), {@code canDelete} (delete, move away).
     * Without a JCR session (tests on mocks) everything is allowed.
     */
    static java.util.Map<String, Object> permissions(ResourceResolver resolver, String path, boolean folder) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        Session session = resolver == null ? null : resolver.adaptTo(Session.class);
        if (folder) {
            out.put("canCreate", may(session, path + "/studio-probe", "add_node"));
        }
        boolean edit = may(session, path + "/jcr:content/jcr:title", "set_property");
        out.put("canEdit", edit);
        out.put("canDelete", may(session, path, "remove"));
        // publishing is a right of its own; where it is not known, whoever may change may publish
        boolean publish = edit;
        if (session != null) {
            try {
                javax.jcr.security.AccessControlManager acm = session.getAccessControlManager();
                publish = acm.hasPrivileges(path, new javax.jcr.security.Privilege[] {acm.privilegeFromName("crx:replicate")}) || edit;
            } catch (RepositoryException | RuntimeException e) {
                publish = edit;
            }
        }
        out.put("canPublish", publish);
        return out;
    }

    /** Only what is denied, for items in a listing: nothing at all in the usual case. */
    static void putDenied(java.util.Map<String, Object> item, ResourceResolver resolver, String path, boolean folder) {
        for (java.util.Map.Entry<String, Object> e : permissions(resolver, path, folder).entrySet()) {
            if (Boolean.FALSE.equals(e.getValue())) {
                item.put(e.getKey(), false);
            }
        }
    }

    private static boolean may(Session session, String path, String actions) {
        if (session == null) {
            return true;
        }
        try {
            return session.hasPermission(path, actions);
        } catch (RepositoryException | RuntimeException e) {
            return false;
        }
    }

    static boolean isAdmin(ResourceResolver resolver) {
        if (resolver == null) {
            return false;
        }
        String user = resolver.getUserID();
        if (ADMIN.equals(user)) {
            return true;
        }
        if (user == null || "anonymous".equals(user)) {
            return false;
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return false;
        }
        try {
            AccessControlManager acm = session.getAccessControlManager();
            return session.nodeExists(DamItems.ROOT)
                    && acm.hasPrivileges(DamItems.ROOT, new Privilege[] {acm.privilegeFromName(Privilege.JCR_ALL)});
        } catch (RepositoryException | RuntimeException e) {
            return false;
        }
    }
}
