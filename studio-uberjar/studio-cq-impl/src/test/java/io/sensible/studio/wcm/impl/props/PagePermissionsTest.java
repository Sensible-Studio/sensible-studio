/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.props;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.JackrabbitWorkspace;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The Permissions tab on a real Oak repository. */
@ExtendWith(SlingContextExtension.class)
class PagePermissionsTest {

    private static final String PAGE = "/content/site/en";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;
    private PagePermissions perms;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "English");
        session = context.resourceResolver().adaptTo(Session.class);
        session.save();
        try {
            session.getWorkspace().getNamespaceRegistry().registerNamespace("crx", "http://www.day.com/crx/1.0");
        } catch (javax.jcr.NamespaceException alreadyThere) {
            // registered by an earlier test of the same repository
        }
        ((JackrabbitWorkspace) session.getWorkspace()).getPrivilegeManager().registerPrivilege("crx:replicate", false, new String[0]);
        ((JackrabbitSession) session).getUserManager().createGroup("authors");
        ((JackrabbitSession) session).getUserManager().createUser("anna", "secret-pass").setProperty("profile/givenName",
                session.getValueFactory().createValue("Anna"));
        ((org.apache.jackrabbit.api.security.user.Group) ((JackrabbitSession) session).getUserManager().getAuthorizable("authors"))
                .addMember(((JackrabbitSession) session).getUserManager().getAuthorizable("anna"));
        session.save();
        perms = new PagePermissions(session);
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String principal) {
        return rows.stream().filter(r -> principal.equals(r.get("principal"))).findFirst().orElseThrow();
    }

    @Test
    void savesReadsAndRemovesThePermissionsOfAGroup() throws Exception {
        perms.save(PAGE, "authors", Set.of("read", "modify", "create"));
        session.save();
        Map<String, Object> authors = row(perms.acl(PAGE), "authors");
        assertEquals(true, authors.get("group"));
        assertEquals(true, authors.get("read"));
        assertEquals(true, authors.get("modify"));
        assertEquals(true, authors.get("create"));
        assertEquals(false, authors.get("delete"));
        assertEquals(false, authors.get("replicate"));
        assertFalse(authors.containsKey("other"));

        // saving again replaces, it does not add up
        perms.save(PAGE, "authors", Set.of("read", "delete", "replicate"));
        session.save();
        authors = row(perms.acl(PAGE), "authors");
        assertEquals(List.of(true, false, true, true, false),
                List.of(authors.get("read"), authors.get("modify"), authors.get("delete"), authors.get("replicate"), authors.get("create")));

        perms.remove(PAGE, "authors");
        session.save();
        assertTrue(perms.acl(PAGE).isEmpty());
    }

    @Test
    void effectivePermissionsFollowGroupMembership() throws Exception {
        perms.save(PAGE, "authors", Set.of("read", "modify"));
        session.save();
        List<Map<String, Object>> effective = perms.effective(PAGE);
        Map<String, Object> authors = row(effective, "authors");
        assertEquals(true, authors.get("read"));
        assertEquals(true, authors.get("modify"));
        assertEquals(false, authors.get("delete"));
    }

    @Test
    void otherEntriesAreKeptAndFlagged() throws Exception {
        javax.jcr.security.AccessControlManager acm = session.getAccessControlManager();
        org.apache.jackrabbit.api.security.JackrabbitAccessControlList list =
                org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils.getAccessControlList(session, PAGE);
        list.addEntry(((JackrabbitSession) session).getPrincipalManager().getPrincipal("authors"),
                new javax.jcr.security.Privilege[] {acm.privilegeFromName("jcr:versionManagement")}, true);
        acm.setPolicy(PAGE, list);
        session.save();
        assertEquals(true, row(perms.acl(PAGE), "authors").get("other"));
        perms.save(PAGE, "authors", Set.of("read"));
        session.save();
        Map<String, Object> authors = row(perms.acl(PAGE), "authors");
        assertEquals(true, authors.get("read"));
        assertEquals(true, authors.get("other"), "the custom entry is still there");
    }

    @Test
    void unknownPrincipalsAreRefused() {
        assertThrows(RepositoryException.class, () -> perms.save(PAGE, "nobody-here", Set.of("read")));
    }

    @Test
    void closedUserGroupsNeedTheCugModule() throws Exception {
        assertEquals(false, perms.cug(PAGE).get("supported"));
        assertThrows(RepositoryException.class, () -> perms.setCug(PAGE, List.of("authors")));
    }
}
