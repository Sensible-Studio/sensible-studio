/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

/** Saved searches of the Filter rail: per user, written by the service user, real Oak users and ACLs. */
@ExtendWith(SlingContextExtension.class)
class SavedSearchesTest {

    private static final String SURF = "{\"path\":\"/content/site\",\"text\":\"surf\",\"tags\":[]}";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver author;
    private ResourceResolver other;
    private ResourceResolver service;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/var/studio/searches", "jcr:primaryType", "sling:Folder");
        ResourceResolver admin = context.resourceResolver();
        admin.commit();
        Session s = admin.adaptTo(Session.class);
        UserManager users = ((JackrabbitSession) s).getUserManager();
        users.createUser("author@example.com", "author-test");
        users.createUser("other", "other-test");
        users.createUser("keeper", "keeper-test"); // stands in for studio-siterail-service
        // as the repoinit of studio-siterail-service; authors get nothing on /var
        AccessControlUtils.addAccessControlEntry(s, "/var/studio/searches", users.getAuthorizable("keeper").getPrincipal(),
                new String[] {"jcr:read", "rep:write"}, true);
        s.save();
        author = as(s, "author@example.com");
        other = as(s, "other");
        service = as(s, "keeper");
    }

    @AfterEach
    void tearDown() {
        for (ResourceResolver r : new ResourceResolver[] {author, other, service}) {
            if (r != null) {
                r.close();
            }
        }
    }

    private ResourceResolver as(Session admin, String user) throws Exception {
        Session session = admin.impersonate(new SimpleCredentials(user, new char[0]));
        return context.getService(ResourceResolverFactory.class).getResourceResolver(Map.of("user.jcr.session", session));
    }

    private static List<Object> names(SavedSearches s) {
        return s.list().stream().map(m -> m.get("name")).collect(Collectors.toList());
    }

    @Test
    void eachUserKeepsTheirOwnSearches() throws Exception {
        assertNull(author.getResource("/var/studio/searches"), "the author cannot see /var/studio/searches");
        assertThrows(Exception.class, () -> new SavedSearches(author, null).save("Surf", SURF), "alone, the author cannot write there");
        author.revert();

        SavedSearches mine = new SavedSearches(author, service);
        mine.save("surf pages", SURF);
        mine.save("Locked", "{\"locked\":true}");
        assertEquals(List.of("Locked", "surf pages"), names(mine), "by name");
        assertEquals(SURF, mine.list().get(1).get("criteria"));
        assertTrue(new SavedSearches(other, service).list().isEmpty(), "another user does not see them");
        assertTrue(service.getResource("/var/studio/searches/author_0040example.com") != null, "a readable folder per user");

        mine.save("surf pages", "{\"text\":\"surfing\"}");
        assertEquals(2, mine.list().size(), "the same name replaces the search");
        assertEquals("{\"text\":\"surfing\"}", mine.list().get(1).get("criteria"));

        assertThrows(IllegalArgumentException.class, () -> new SavedSearches(other, service).delete("Locked"), "nor deletes them");
        mine.delete("Locked");
        assertEquals(List.of("surf pages"), names(mine));
    }

    @Test
    void namesAndCriteriaAreChecked() throws Exception {
        SavedSearches mine = new SavedSearches(author, service);
        assertThrows(IllegalArgumentException.class, () -> mine.save(" ", SURF));
        assertThrows(IllegalArgumentException.class, () -> mine.save("x".repeat(SavedSearches.MAX_NAME + 1), SURF));
        assertThrows(IllegalArgumentException.class, () -> mine.save("Bad", "not json"));
        assertThrows(IllegalArgumentException.class, () -> mine.save("Big", "{" + "x".repeat(SavedSearches.MAX_CRITERIA) + "}"));
        mine.save("a/b [c] * : ü", SURF); // any name: the node is named by its hash
        assertEquals(List.of("a/b [c] * : ü"), names(mine));
        for (int i = 1; i < SavedSearches.MAX_SEARCHES; i++) {
            mine.save("s" + i, SURF);
        }
        IllegalArgumentException full = assertThrows(IllegalArgumentException.class, () -> mine.save("one more", SURF));
        assertTrue(full.getMessage().contains("At most"), full.getMessage());
        mine.save("s1", "{\"text\":\"still replaceable\"}");
    }

    @Test
    void anonymousCannotSave() {
        ResourceResolver anonymous = new org.apache.sling.api.wrappers.ResourceResolverWrapper(service) {
            @Override
            public String getUserID() {
                return "anonymous";
            }
        };
        assertThrows(SecurityException.class, () -> new SavedSearches(anonymous, service).save("x", SURF));
        assertThrows(SecurityException.class, () -> new SavedSearches(anonymous, service).list());
    }
}
