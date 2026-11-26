/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.security.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.jcr.security.AccessControlEntry;

import org.apache.jackrabbit.api.security.JackrabbitAccessControlList;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class PermissionTreeTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;
    private SecurityService service;
    private PermissionTree tree;

    @BeforeEach
    void setUp() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        Node content = session.getRootNode().addNode("content", "nt:unstructured");
        Node site = content.addNode("site", "nt:unstructured");
        Node en = site.addNode("en", "nt:unstructured");
        en.addNode("jcr:content", "nt:unstructured").setProperty("jcr:title", "English");
        site.addNode("fr", "nt:unstructured");
        session.save();
        service = new SecurityService(session);
        service.createGroup("authors", Map.of());
        service.createUser("jdoe", "secret", Map.of());
        service.setMemberOf("jdoe", List.of("authors"));
        service.saveAce("authors", "/content", true, List.of("jcr:read"), null, null);
        tree = new PermissionTree(session);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> level, String name) {
        for (Object o : (List<Object>) level.get("children")) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (name.equals(m.get("name"))) {
                return m;
            }
        }
        throw new AssertionError("no child " + name);
    }

    @SuppressWarnings("unchecked")
    private static Object can(Map<String, Object> row, String column) {
        return ((Map<String, Object>) row.get("can")).get(column);
    }

    @SuppressWarnings("unchecked")
    private static Object set(Map<String, Object> row, String column) {
        return ((Map<String, Object>) row.get("set")).get(column);
    }

    @Test
    void showsWhatAUserCanDoThroughItsGroupsAndWhereItIsSet() throws Exception {
        Map<String, Object> level = tree.level("jdoe", "/content/site");
        Map<String, Object> en = child(level, "en");
        assertEquals(true, can(en, "read"), "read comes from authors, set on /content");
        assertNull(set(en, "read"), "nothing set on the node itself");
        assertEquals(false, can(en, "modify"));
        assertEquals("English", en.get("title"));
        assertEquals(false, en.get("hasChildren"), "jcr:content is not shown");
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) tree.level("authors", "/content").get("node");
        assertEquals("allow", set(group, "read"), "the group's own entry on /content");
    }

    @Test
    void allowsDeniesAndInheritsAColumn() throws Exception {
        // deny read for the user below its group's allow
        Map<String, Object> row = tree.set("jdoe", "/content/site/fr", Map.of("read", "deny"));
        assertEquals(false, can(row, "read"));
        assertEquals("deny", set(row, "read"));

        // back to what is inherited: the deny entry goes, read comes back from the group
        row = tree.set("jdoe", "/content/site/fr", Map.of("read", "inherit"));
        assertEquals(true, can(row, "read"));
        assertNull(set(row, "read"));
        assertNull(acl("/content/site/fr"), "no empty entries left behind");

        // allowing what is already inherited adds nothing
        tree.set("jdoe", "/content/site", Map.of("read", "allow"));
        assertNull(acl("/content/site"));

        // modify and create at once, inherited below
        row = tree.set("jdoe", "/content/site", Map.of("modify", "allow", "create", "allow"));
        assertEquals("allow", set(row, "modify"));
        assertEquals("allow", set(row, "create"));
        Map<String, Object> en = child(tree.level("jdoe", "/content/site"), "en");
        assertEquals(true, can(en, "modify"));
        assertEquals(1, acl("/content/site").getAccessControlEntries().length, "one allow entry for both columns");

        // taking one column away keeps the other
        row = tree.set("jdoe", "/content/site", Map.of("modify", "inherit"));
        assertEquals(false, can(row, "modify"));
        assertEquals(true, can(row, "create"));
    }

    @Test
    void leavesEntriesWithRestrictionsAlone() throws Exception {
        service.saveAce("jdoe", "/content/site", true, List.of("jcr:modifyProperties"), "/en*", null);
        Map<String, Object> row = tree.set("jdoe", "/content/site", Map.of("modify", "inherit"));
        assertEquals(true, row.get("restricted"));
        assertEquals(1, acl("/content/site").getAccessControlEntries().length, "the glob entry stays");
    }

    private JackrabbitAccessControlList acl(String path) throws Exception {
        for (javax.jcr.security.AccessControlPolicy p : session.getAccessControlManager().getPolicies(path)) {
            if (p instanceof JackrabbitAccessControlList) {
                AccessControlEntry[] e = ((JackrabbitAccessControlList) p).getAccessControlEntries();
                return e.length == 0 ? null : (JackrabbitAccessControlList) p;
            }
        }
        return null;
    }
}
