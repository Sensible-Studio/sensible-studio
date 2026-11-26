/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.security.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Runs against a real (in-memory) Oak repository, signed in as admin. */
@ExtendWith(SlingContextExtension.class)
class SecurityServiceTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private SecurityService service;
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        session.getRootNode().addNode("content", "nt:unstructured").addNode("site", "nt:unstructured").addNode("page", "nt:unstructured");
        session.save();
        service = new SecurityService(session);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsUsersAndGroupsWithProfilesAndMemberships() throws Exception {
        service.createUser("jdoe", "secret", Map.of("givenName", "Jane", "familyName", "Doe", "email", "jane@example.com"));
        service.createGroup("authors", Map.of("givenName", "Authors", "aboutMe", "Write content"));
        service.setMemberOf("jdoe", List.of("authors"));

        Map<String, Object> jane = service.detail("jdoe");
        assertEquals("Jane Doe", jane.get("name"));
        assertEquals("jane@example.com", ((Map<String, Object>) jane.get("profile")).get("email"));
        assertEquals(List.of("authors"), ids((List<Map<String, Object>>) jane.get("memberOf")));

        Map<String, Object> authors = service.detail("authors");
        assertEquals("Authors", authors.get("name"));
        assertEquals(List.of("jdoe"), ids((List<Map<String, Object>>) authors.get("members")));

        assertEquals(List.of("jdoe"), ids(service.list(false, "jane", false)));
        assertTrue(service.list(true, "", true).stream().anyMatch(g -> "authors".equals(g.get("id"))));

        service.setMembers("authors", Collections.emptyList());
        assertTrue(((List<?>) service.detail("jdoe").get("memberOf")).isEmpty());

        service.update("jdoe", Map.of("email", ""), true, "left", "new-secret");
        Map<String, Object> disabled = service.detail("jdoe");
        assertEquals(Boolean.TRUE, disabled.get("disabled"));
        assertEquals("left", disabled.get("disabledReason"));
        assertEquals(null, ((Map<String, Object>) disabled.get("profile")).get("email"));

        service.delete("jdoe");
        assertTrue(service.list(false, "jdoe", true).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.delete("admin"));
        assertThrows(IllegalArgumentException.class, () -> service.createGroup("authors", Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void managesAccessControlEntries() throws Exception {
        service.createGroup("editors", Map.of());
        service.saveAce("editors", "/content/site", true, List.of("jcr:read", "rep:write"), null, null);
        service.saveAce("editors", "/content/site", false, List.of("jcr:removeNode"), "/page", null);

        List<Map<String, Object>> aces = service.aces("editors");
        assertEquals(2, aces.size());
        assertEquals("/content/site", aces.get(0).get("path"));
        assertEquals(Boolean.TRUE, aces.get(0).get("allow"));
        assertEquals(Boolean.FALSE, aces.get(1).get("allow"));
        assertEquals("/page", ((Map<String, Object>) aces.get(1).get("restrictions")).get("rep:glob"));

        // replace the first entry in place
        service.saveAce("editors", "/content/site", true, List.of("jcr:read"), null, (Integer) aces.get(0).get("index"));
        aces = service.aces("editors");
        assertEquals(List.of("jcr:read"), aces.get(0).get("privileges"));
        assertEquals(Boolean.FALSE, aces.get(1).get("allow"), "order kept");

        Map<String, Object> eff = service.effective("editors", "/content/site/page");
        Map<String, Object> summary = (Map<String, Object>) eff.get("summary");
        assertEquals(Boolean.TRUE, summary.get("read"));
        assertEquals(Boolean.FALSE, summary.get("modify"));

        service.removeAce("editors", "/content/site", (Integer) aces.get(1).get("index"));
        service.removeAce("editors", "/content/site", 0);
        assertTrue(service.aces("editors").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.saveAce("editors", "/nope", true, List.of("jcr:read"), null, null));
        assertFalse(service.privileges().isEmpty());
    }

    private static List<Object> ids(List<Map<String, Object>> list) {
        return list.stream().map(m -> m.get("id")).collect(Collectors.toList());
    }
}
