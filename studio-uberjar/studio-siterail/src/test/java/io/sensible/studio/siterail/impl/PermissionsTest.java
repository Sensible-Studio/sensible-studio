/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.PageManager;

/**
 * Authors without rights on {@code /var}: comments, deletes and restores go through the service resolver, with the
 * author's own permissions checked first. Real Oak users and ACLs.
 */
@ExtendWith(SlingContextExtension.class)
class PermissionsTest {

    private static final String SITE = "/content/site";
    private static final String EN = SITE + "/en";
    private static final String NEWS = EN + "/news";
    private static final String STORY = NEWS + "/story";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver author;
    private ResourceResolver reader;
    private ResourceResolver service;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(SITE, "jcr:primaryType", "sling:Folder");
        for (String[] p : new String[][] {{EN, "English"}, {NEWS, "News"}, {STORY, "Story"}}) {
            context.create().resource(p[0], "jcr:primaryType", "cq:Page");
            context.create().resource(p[0] + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", p[1]);
        }
        context.create().resource("/var/studio", "jcr:primaryType", "sling:Folder");
        ResourceResolver admin = context.resourceResolver();
        admin.commit();
        context.registerAdapter(ResourceResolver.class, PageManager.class,
                (java.util.function.Function<ResourceResolver, PageManager>) OakPages::pageManager);

        Session s = admin.adaptTo(Session.class);
        UserManager users = ((JackrabbitSession) s).getUserManager();
        users.createUser("author", "author-test");
        users.createUser("reader", "reader-test");
        users.createUser("keeper", "keeper-test"); // stands in for studio-siterail-service
        AccessControlUtils.addAccessControlEntry(s, SITE, users.getAuthorizable("author").getPrincipal(),
                new String[] {"jcr:read", "rep:write", "jcr:versionManagement", "jcr:nodeTypeManagement"}, true);
        AccessControlUtils.addAccessControlEntry(s, SITE, users.getAuthorizable("reader").getPrincipal(), new String[] {"jcr:read"}, true);
        AccessControlUtils.addAccessControlEntry(s, "/content", users.getAuthorizable("keeper").getPrincipal(),
                new String[] {"jcr:read", "rep:write", "jcr:versionManagement", "jcr:nodeTypeManagement"}, true);
        AccessControlUtils.addAccessControlEntry(s, "/var/studio", users.getAuthorizable("keeper").getPrincipal(),
                new String[] {"jcr:read", "rep:write"}, true);
        // as the repoinit of studio-siterail-service: reading the version storage, to find pages deleted elsewhere
        AccessControlUtils.addAccessControlEntry(s, "/jcr:system/jcr:versionStorage", users.getAuthorizable("keeper").getPrincipal(),
                new String[] {"jcr:read", "jcr:versionManagement"}, true);
        s.save();

        author = as(s, "author");
        reader = as(s, "reader");
        service = as(s, "keeper");
    }

    @AfterEach
    void tearDown() {
        for (ResourceResolver r : new ResourceResolver[] {author, reader, service}) {
            if (r != null) {
                r.close();
            }
        }
    }

    private ResourceResolver as(Session admin, String user) throws Exception {
        Session session = admin.impersonate(new SimpleCredentials(user, new char[0]));
        return context.getService(ResourceResolverFactory.class).getResourceResolver(Map.of("user.jcr.session", session));
    }

    @Test
    void anAuthorDeletesAndRestoresWithoutRightsOnVar() throws Exception {
        assertNull(author.getResource("/var/studio"), "the author cannot see /var/studio");
        assertThrows(Exception.class, () -> new VersionRestore(author).delete(List.of(NEWS)), "alone, the author cannot write the record");
        author.revert();
        assertNotNull(author.getResource(NEWS));

        VersionRestore rail = new VersionRestore(author, service);
        assertEquals(2, rail.delete(List.of(NEWS)));
        assertNull(context.resourceResolver().getResource(NEWS));
        assertNotNull(context.resourceResolver().getResource(VersionRestore.DELETED + STORY));

        List<Map<String, Object>> inside = rail.versionsInside(EN);
        assertEquals(List.of(NEWS, STORY), inside.stream().map(p -> p.get("path")).collect(java.util.stream.Collectors.toList()));
        assertEquals("author", inside.get(0).get("deletedBy"));
        assertTrue(new VersionRestore(reader, service).versionsInside(EN).size() == 2, "a reader sees what was there");

        Map<String, String> choices = new java.util.LinkedHashMap<>();
        for (Map<String, Object> p : inside) {
            choices.put(String.valueOf(p.get("path")), String.valueOf(((Map<?, ?>) ((List<?>) p.get("versions")).get(0)).get("id")));
        }
        List<Map<String, Object>> results = rail.restoreVersions(choices);
        assertEquals("recreated", results.get(0).get("status"), String.valueOf(results));
        assertEquals("recreated", results.get(1).get("status"), String.valueOf(results));
        context.resourceResolver().refresh();
        assertEquals("Story", context.resourceResolver().getResource(STORY + "/jcr:content").getValueMap().get("jcr:title", String.class));
    }

    @Test
    void aReaderCannotDeleteOrRestore() throws Exception {
        assertThrows(SecurityException.class, () -> new VersionRestore(reader, service).delete(List.of(NEWS)));
        assertNotNull(context.resourceResolver().getResource(NEWS), "nothing was deleted");
        assertNull(context.resourceResolver().getResource(VersionRestore.DELETED + NEWS), "and nothing recorded");

        new VersionRestore(author, service).delete(List.of(NEWS));
        String id = String.valueOf(((Map<?, ?>) ((List<?>) new VersionRestore(reader, service).versionsInside(EN).get(0).get("versions")).get(0)).get("id"));
        Map<String, Object> result = new VersionRestore(reader, service).restoreVersions(Map.of(NEWS, id)).get(0);
        assertEquals("failed", result.get("status"));
        assertTrue(String.valueOf(result.get("error")).contains("Not allowed"), String.valueOf(result));
        assertNull(context.resourceResolver().getResource(NEWS));
    }

    @Test
    void theServiceFindsPagesDeletedOutsideTheConsole() throws Exception {
        OakPages.pageManager(context.resourceResolver()).createRevision(OakPages.page(STORY), null, "kept");
        Session admin = context.resourceResolver().adaptTo(Session.class);
        admin.getNode(STORY).remove();
        admin.save();
        assertEquals(1, new VersionRestore(service, service).recordDeleted("/content"), "the service walks the version storage");
        assertEquals(List.of(STORY), new VersionRestore(author, service).versionsInside(EN).stream()
                .filter(p -> Boolean.TRUE.equals(p.get("deleted"))).map(p -> p.get("path")).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void commentsWithoutRightsOnVar() throws Exception {
        String id = String.valueOf(new SiteRail(author, service).addComment(EN, "Looks good").get("id"));
        assertNotNull(context.resourceResolver().getResource(id));
        List<Map<String, Object>> events = new SiteRail(reader, service).timeline(EN);
        Map<String, Object> comment = events.stream().filter(e -> "comment".equals(e.get("kind"))).findFirst().get();
        assertEquals("Looks good", comment.get("text"));
        assertEquals(false, comment.get("mine"));
        assertThrows(SecurityException.class, () -> new SiteRail(reader, service).deleteComment(id));
        new SiteRail(author, service).deleteComment(id);
        assertNull(service.getResource(id));
        assertThrows(IllegalArgumentException.class, () -> new SiteRail(reader, service).addComment("/content/other", "x"));
    }
}
