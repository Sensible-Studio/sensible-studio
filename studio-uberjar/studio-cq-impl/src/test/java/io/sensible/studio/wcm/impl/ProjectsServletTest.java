/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class ProjectsServletTest {

    private final SlingContext context = new SlingContext();
    private ResourceResolver rr;
    private final LocalDate today = LocalDate.of(2026, 11, 11);

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        rr = context.resourceResolver();
        context.create().resource(ProjectsServlet.ROOT);
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProjectHasAnOwnerATeamContentAndTasks() throws Exception {
        Resource project = ProjectsServlet.create(rr, "ana", p("title", "Spring Campaign!", "due", "2026-12-01", "description", "Relaunch"));
        assertEquals("/content/projects/spring-campaign", project.getPath());
        assertEquals("/content/projects/spring-campaign-2", ProjectsServlet.create(rr, "ana", p("title", "Spring campaign")).getPath());
        assertThrows(ProjectsServlet.Invalid.class, () -> ProjectsServlet.create(rr, "ana", p("title", " ")));
        assertThrows(ProjectsServlet.Invalid.class, () -> ProjectsServlet.create(rr, "ana", p("title", "x", "due", "next week")));

        ProjectsServlet.apply(rr, project, "member", p("user", "bob", "role", "editor"), "ana");
        ProjectsServlet.apply(rr, project, "member", p("user", "bob", "role", "observer"), "ana");
        assertThrows(ProjectsServlet.Invalid.class, () -> ProjectsServlet.apply(rr, project, "member", p("user", "eve", "role", "boss"), "ana"));
        assertThrows(ProjectsServlet.Invalid.class, () -> ProjectsServlet.apply(rr, project, "removeMember", p("user", "ana"), "ana"));
        ProjectsServlet.apply(rr, project, "link", p("target", WcmTestSupport.EN), "ana");
        ProjectsServlet.apply(rr, project, "link", p("target", WcmTestSupport.EN), "ana");
        assertThrows(ProjectsServlet.Invalid.class, () -> ProjectsServlet.apply(rr, project, "link", p("target", "/content/nope"), "ana"));

        ProjectsServlet.apply(rr, project, "task", p("title", "Write the copy", "assignee", "bob", "due", "2026-11-01", "priority", "high"), "ana");
        ProjectsServlet.apply(rr, project, "task", p("title", "Review"), "ana");
        rr.commit();

        Map<String, Object> d = ProjectsServlet.detail(rr.getResource(project.getPath()), "bob", today);
        assertEquals("observer", d.get("myRole"));
        assertEquals(2, ((List<?>) d.get("members")).size());
        assertEquals(2, d.get("open"));
        assertEquals(1, d.get("overdue"));
        List<Map<String, Object>> links = (List<Map<String, Object>>) d.get("links");
        assertEquals(1, links.size());
        assertEquals(Boolean.TRUE, links.get(0).get("exists"));
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) d.get("tasks");
        assertEquals("Write the copy", tasks.get(0).get("title"));
        assertEquals(Boolean.TRUE, tasks.get(0).get("overdue"));

        // bob's tasks, then done
        List<Map<String, Object>> mine = ProjectsServlet.myTasks(rr, "bob");
        assertEquals(1, mine.size());
        assertEquals("Spring Campaign!", mine.get(0).get("projectTitle"));
        String id = (String) tasks.get(0).get("id");
        ProjectsServlet.apply(rr, rr.getResource(project.getPath()), "task", p("id", id, "status", "done"), "bob");
        rr.commit();
        assertTrue(ProjectsServlet.myTasks(rr, "bob").isEmpty());
        d = ProjectsServlet.detail(rr.getResource(project.getPath()), "bob", today);
        tasks = (List<Map<String, Object>>) d.get("tasks");
        assertEquals("done", tasks.get(1).get("status"));
        assertEquals("bob", tasks.get(1).get("doneBy"));
        assertNotNull(tasks.get(1).get("doneAt"));
        assertEquals(1, d.get("done"));

        ProjectsServlet.apply(rr, rr.getResource(project.getPath()), "removeTask", p("id", id), "ana");
        ProjectsServlet.apply(rr, rr.getResource(project.getPath()), "save", p("title", "Spring", "active", "false", "due", ""), "ana");
        rr.commit();
        Map<String, Object> s = ProjectsServlet.summary(rr.getResource(project.getPath()), "ana", today);
        assertEquals("Spring", s.get("title"));
        assertEquals(Boolean.FALSE, s.get("active"));
        assertNull(s.get("due"));
        assertEquals("owner", s.get("myRole"));
        // inactive ones come last
        assertEquals("/content/projects/spring-campaign-2", ProjectsServlet.list(rr, "ana", today).get(0).get("path"));
    }

    @Test
    void onlyAProjectIsAProject() {
        assertNull(ProjectsServlet.project(rr, "/content/wknd"));
        assertNull(ProjectsServlet.project(rr, "/content/projects/x/tasks"));
        assertNull(ProjectsServlet.project(rr, null));
        assertEquals("2026-02-28", ProjectsServlet.date(" 2026-02-28 "));
        assertNull(ProjectsServlet.date(""));
    }
}
