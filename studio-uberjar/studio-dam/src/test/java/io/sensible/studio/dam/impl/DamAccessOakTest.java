/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import javax.jcr.Repository;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.jcr.Jcr;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.Test;

/** Admin rights by group and what a user may do, with a real Oak user manager. */
class DamAccessOakTest {

    private static ResourceResolver resolverOf(Session s) {
        return (ResourceResolver) Proxy.newProxyInstance(ResourceResolver.class.getClassLoader(), new Class<?>[] {ResourceResolver.class},
                (p, m, a) -> {
                    switch (m.getName()) {
                        case "getUserID":
                            return s.getUserID();
                        case "adaptTo":
                            return a[0] == Session.class ? s : null;
                        default:
                            return null;
                    }
                });
    }

    @Test
    void administratorsAreAdminsToo() throws Exception {
        Repository repo = new Jcr(new Oak(new MemoryNodeStore())).createRepository();
        Session admin = repo.login(new SimpleCredentials("admin", "admin".toCharArray()));
        UserManager um = ((JackrabbitSession) admin).getUserManager();
        um.createUser("ana", "ana-secret-1");
        um.createUser("bob", "bob-secret-1");
        Group g = um.createGroup("administrators");
        g.addMember(um.getAuthorizable("ana"));
        admin.getRootNode().addNode("content").addNode("dam");
        admin.save();
        // what administrators have on an instance: everything
        javax.jcr.security.AccessControlManager acm = admin.getAccessControlManager();
        org.apache.jackrabbit.api.security.JackrabbitAccessControlList acl =
                org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils.getAccessControlList(admin, "/");
        acl.addEntry(g.getPrincipal(), new javax.jcr.security.Privilege[] {acm.privilegeFromName("jcr:all")}, true);
        acm.setPolicy("/", acl);
        org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils.addAccessControlEntry(admin, "/",
                org.apache.jackrabbit.oak.spi.security.principal.EveryonePrincipal.getInstance(), new String[] {"jcr:read"}, true);
        admin.save();
        assertTrue(DamAccess.isAdmin(resolverOf(admin)));
        Session ana = repo.login(new SimpleCredentials("ana", "ana-secret-1".toCharArray()));
        Session bob = repo.login(new SimpleCredentials("bob", "bob-secret-1".toCharArray()));
        assertTrue(DamAccess.isAdmin(resolverOf(ana)), "member of administrators");
        assertFalse(DamAccess.isAdmin(resolverOf(bob)));
        assertFalse(DamAccess.isAdmin(null));
        // what the UI may offer: ana everything, bob (read only) nothing
        java.util.Map<String, Object> all = new java.util.LinkedHashMap<>();
        all.put("canCreate", true);
        all.put("canEdit", true);
        all.put("canDelete", true);
        all.put("canPublish", true);
        org.junit.jupiter.api.Assertions.assertEquals(all, DamAccess.permissions(resolverOf(ana), "/content/dam", true));
        java.util.Map<String, Object> none = new java.util.LinkedHashMap<>();
        none.put("canCreate", false);
        none.put("canEdit", false);
        none.put("canDelete", false);
        none.put("canPublish", false);
        org.junit.jupiter.api.Assertions.assertEquals(none, DamAccess.permissions(resolverOf(bob), "/content/dam", true));
        java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
        DamAccess.putDenied(item, resolverOf(ana), "/content/dam", false);
        assertTrue(item.isEmpty(), "nothing denied, nothing written");
        ana.logout();
        bob.logout();
        admin.logout();
        if (repo instanceof org.apache.jackrabbit.api.JackrabbitRepository) {
            ((org.apache.jackrabbit.api.JackrabbitRepository) repo).shutdown();
        }
    }
}
