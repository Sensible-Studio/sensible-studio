/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * Projects: a piece of work with a team, a due date, the content it is about and its tasks — what AEM's
 * Projects console is for. A project is a node at {@code /content/projects/<name>}
 * ({@code sling:resourceType=studio/projects/project}): {@code title}, {@code description}, {@code due}
 * ({@code yyyy-MM-dd}), {@code active}, {@code owner}, {@code members} ({@code user|role}: owner, editor,
 * observer), {@code links} (content paths) and {@code tasks/<id>} ({@code title}, {@code description},
 * {@code assignee}, {@code due}, {@code priority} low | medium | high, {@code status} open | done). Read and
 * written with the session of the person asking, so the repository's permissions on
 * {@code /content/projects} decide who may.
 * <p>
 * {@code /bin/studio/projects.json}: GET {@code op=list} (every project with its counts), {@code op=get&path=}
 * (one, with tasks and links), {@code op=mytasks} (the open tasks assigned to the person asking, soonest
 * first); POST {@code op=create|save|delete|member|removeMember|link|unlink|task|removeTask} with
 * {@code X-Studio-CSRF: 1}; a POST answers the project as it is now (422 with {@code error} for what is
 * missing or malformed).
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/projects",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class ProjectsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String ROOT = "/content/projects";
    static final String RESOURCE_TYPE = "studio/projects/project";
    static final List<String> ROLES = Arrays.asList("owner", "editor", "observer");
    static final List<String> PRIORITIES = Arrays.asList("low", "medium", "high");

    /** Something the person asking got wrong: answered as 422. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Invalid(String message) {
            super(message);
        }
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver rr = request.getResourceResolver();
        String user = rr.getUserID();
        if (user == null || "anonymous".equals(user)) {
            send(response, 401, error("sign in"));
            return;
        }
        String op = request.getParameter("op");
        if ("get".equals(op)) {
            Resource project = project(rr, request.getParameter("path"));
            if (project == null) {
                send(response, 404, error("No such project"));
                return;
            }
            send(response, 200, detail(project, user, LocalDate.now()));
        } else if ("mytasks".equals(op)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tasks", myTasks(rr, user));
            send(response, 200, out);
        } else {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("projects", list(rr, user, LocalDate.now()));
            out.put("canCreate", rr.getResource(ROOT) != null && SitesServlet.mayAddBelow(rr, ROOT));
            send(response, 200, out);
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver rr = request.getResourceResolver();
        String user = rr.getUserID();
        if (user == null || "anonymous".equals(user)) {
            send(response, 401, error("sign in"));
            return;
        }
        if (!"1".equals(request.getHeader("X-Studio-CSRF"))) {
            send(response, 403, error("missing X-Studio-CSRF header"));
            return;
        }
        String op = request.getParameter("op");
        Map<String, String> p = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> p.put(k, v.length == 0 ? null : v[0]));
        try {
            Resource project;
            if ("create".equals(op)) {
                project = create(rr, user, p);
            } else {
                project = project(rr, p.get("path"));
                if (project == null) {
                    send(response, 404, error("No such project"));
                    return;
                }
                if ("delete".equals(op)) {
                    rr.delete(project);
                    rr.commit();
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("deleted", true);
                    send(response, 200, out);
                    return;
                }
                apply(rr, project, op, p, user);
            }
            rr.commit();
            send(response, 200, detail(rr.getResource(project.getPath()), user, LocalDate.now()));
        } catch (Invalid e) {
            rr.revert();
            send(response, 422, error(e.getMessage()));
        } catch (PersistenceException e) {
            rr.revert();
            send(response, 403, error("Not saved: " + e.getMessage()));
        }
    }

    static Resource project(ResourceResolver rr, String path) {
        if (path == null || !path.startsWith(ROOT + "/") || path.indexOf('/', ROOT.length() + 1) > 0) {
            return null;
        }
        Resource r = rr.getResource(path);
        return r != null && RESOURCE_TYPE.equals(r.getValueMap().get("sling:resourceType", String.class)) ? r : null;
    }

    static Resource create(ResourceResolver rr, String user, Map<String, String> p) throws PersistenceException {
        String title = required(p.get("title"), "Give the project a title");
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            throw new Invalid("Projects cannot be created here: " + ROOT + " is missing or not readable");
        }
        String base = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) {
            base = "project";
        }
        String name = base;
        for (int i = 2; root.getChild(name) != null; i++) {
            name = base + "-" + i;
        }
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("sling:resourceType", RESOURCE_TYPE);
        props.put("title", title);
        props.put("owner", user);
        props.put("active", true);
        props.put("created", Instant.now().toString());
        props.put("members", new String[] {user + "|owner"});
        Resource project = rr.create(root, name, props);
        info(project.adaptTo(ModifiableValueMap.class), p);
        return project;
    }

    /** One change to a project (no commit). */
    static void apply(ResourceResolver rr, Resource project, String op, Map<String, String> p, String user) throws PersistenceException {
        ModifiableValueMap m = project.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            throw new PersistenceException("no write access to " + project.getPath());
        }
        if ("save".equals(op)) {
            m.put("title", required(p.get("title"), "Give the project a title"));
            info(m, p);
            if (p.get("active") != null) {
                m.put("active", "true".equals(p.get("active")));
            }
        } else if ("member".equals(op)) {
            String who = required(p.get("user"), "Who? Give a user or group id");
            String role = p.get("role") == null ? "editor" : p.get("role");
            if (!ROLES.contains(role)) {
                throw new Invalid("A role is owner, editor or observer");
            }
            List<String> members = without(m.get("members", new String[0]), who);
            members.add(who + "|" + role);
            m.put("members", members.toArray(new String[0]));
        } else if ("removeMember".equals(op)) {
            List<String> members = without(m.get("members", new String[0]), required(p.get("user"), "Who?"));
            if (members.stream().noneMatch(x -> x.endsWith("|owner"))) {
                throw new Invalid("A project keeps at least one owner");
            }
            m.put("members", members.toArray(new String[0]));
        } else if ("link".equals(op)) {
            String target = required(p.get("target"), "Which content? Give its path");
            if (!target.startsWith("/content/") || rr.getResource(target) == null) {
                throw new Invalid("No content at " + target);
            }
            List<String> links = new ArrayList<>(Arrays.asList(m.get("links", new String[0])));
            if (!links.contains(target)) {
                links.add(target);
            }
            m.put("links", links.toArray(new String[0]));
        } else if ("unlink".equals(op)) {
            List<String> links = new ArrayList<>(Arrays.asList(m.get("links", new String[0])));
            links.remove(p.get("target"));
            m.put("links", links.toArray(new String[0]));
        } else if ("task".equals(op)) {
            task(rr, project, p, user);
        } else if ("removeTask".equals(op)) {
            Resource task = project.getChild("tasks/" + p.get("id"));
            if (task != null) {
                rr.delete(task);
            }
        } else {
            throw new Invalid("Unknown op " + op);
        }
    }

    private static void info(ModifiableValueMap m, Map<String, String> p) {
        if (p.containsKey("description")) {
            put(m, "description", p.get("description"));
        }
        if (p.containsKey("due")) {
            put(m, "due", date(p.get("due")));
        }
    }

    private static void task(ResourceResolver rr, Resource project, Map<String, String> p, String user) throws PersistenceException {
        Resource tasks = project.getChild("tasks");
        if (tasks == null) {
            Map<String, Object> props = new HashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            tasks = rr.create(project, "tasks", props);
        }
        String id = p.get("id");
        Resource task = id == null || id.isEmpty() ? null : tasks.getChild(id);
        if (task == null) {
            String title = required(p.get("title"), "Give the task a title");
            id = Long.toString(System.currentTimeMillis(), 36);
            for (int i = 0; tasks.getChild(id) != null; i++) {
                id = Long.toString(System.currentTimeMillis(), 36) + i;
            }
            Map<String, Object> props = new HashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            props.put("title", title);
            props.put("status", "open");
            props.put("priority", "medium");
            props.put("created", Instant.now().toString());
            props.put("createdBy", user);
            task = rr.create(tasks, id, props);
        }
        ModifiableValueMap m = task.adaptTo(ModifiableValueMap.class);
        if (p.get("title") != null) {
            m.put("title", required(p.get("title"), "Give the task a title"));
        }
        if (p.containsKey("description")) {
            put(m, "description", p.get("description"));
        }
        if (p.containsKey("assignee")) {
            put(m, "assignee", p.get("assignee"));
        }
        if (p.containsKey("due")) {
            put(m, "due", date(p.get("due")));
        }
        if (p.get("priority") != null) {
            if (!PRIORITIES.contains(p.get("priority"))) {
                throw new Invalid("A priority is low, medium or high");
            }
            m.put("priority", p.get("priority"));
        }
        if (p.get("status") != null) {
            boolean done = "done".equals(p.get("status"));
            boolean was = "done".equals(m.get("status", "open"));
            m.put("status", done ? "done" : "open");
            if (done && !was) {
                m.put("doneAt", Instant.now().toString());
                m.put("doneBy", user);
            } else if (!done) {
                m.remove("doneAt");
                m.remove("doneBy");
            }
        }
    }

    static List<Map<String, Object>> list(ResourceResolver rr, String user, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return out;
        }
        for (Resource project : root.getChildren()) {
            if (RESOURCE_TYPE.equals(project.getValueMap().get("sling:resourceType", String.class))) {
                out.add(summary(project, user, today));
            }
        }
        // active first, then the soonest due, then by title
        out.sort((a, b) -> {
            int d = Boolean.compare(Boolean.TRUE.equals(b.get("active")), Boolean.TRUE.equals(a.get("active")));
            if (d != 0) {
                return d;
            }
            String da = a.get("due") == null ? "9999" : String.valueOf(a.get("due"));
            String db = b.get("due") == null ? "9999" : String.valueOf(b.get("due"));
            d = da.compareTo(db);
            return d != 0 ? d : String.valueOf(a.get("title")).compareToIgnoreCase(String.valueOf(b.get("title")));
        });
        return out;
    }

    static Map<String, Object> summary(Resource project, String user, LocalDate today) {
        ValueMap v = project.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", project.getPath());
        m.put("name", project.getName());
        m.put("title", v.get("title", project.getName()));
        m.put("description", v.get("description", String.class));
        m.put("due", v.get("due", String.class));
        m.put("active", v.get("active", true));
        m.put("owner", v.get("owner", String.class));
        m.put("created", v.get("created", String.class));
        List<Map<String, Object>> members = new ArrayList<>();
        String mine = null;
        for (String s : v.get("members", new String[0])) {
            int bar = s.lastIndexOf('|');
            Map<String, Object> one = new LinkedHashMap<>();
            String who = bar < 0 ? s : s.substring(0, bar);
            String role = bar < 0 ? "editor" : s.substring(bar + 1);
            one.put("user", who);
            one.put("role", role);
            members.add(one);
            if (who.equals(user)) {
                mine = role;
            }
        }
        m.put("members", members);
        m.put("myRole", mine);
        int open = 0;
        int done = 0;
        int overdue = 0;
        Resource tasks = project.getChild("tasks");
        if (tasks != null) {
            for (Resource t : tasks.getChildren()) {
                ValueMap tv = t.getValueMap();
                if ("done".equals(tv.get("status", "open"))) {
                    done++;
                } else {
                    open++;
                    String due = tv.get("due", String.class);
                    if (due != null && due.compareTo(today.toString()) < 0) {
                        overdue++;
                    }
                }
            }
        }
        m.put("open", open);
        m.put("done", done);
        m.put("overdue", overdue);
        m.put("links", v.get("links", new String[0]).length);
        return m;
    }

    static Map<String, Object> detail(Resource project, String user, LocalDate today) {
        Map<String, Object> m = summary(project, user, today);
        ResourceResolver rr = project.getResourceResolver();
        List<Map<String, Object>> links = new ArrayList<>();
        for (String path : project.getValueMap().get("links", new String[0])) {
            Map<String, Object> one = new LinkedHashMap<>();
            Resource target = rr.getResource(path);
            Resource content = target == null ? null : target.getChild("jcr:content");
            one.put("path", path);
            one.put("exists", target != null);
            one.put("title", content == null ? path.substring(path.lastIndexOf('/') + 1) : content.getValueMap().get("jcr:title", path.substring(path.lastIndexOf('/') + 1)));
            one.put("kind", path.startsWith("/content/dam") ? "media" : "page");
            links.add(one);
        }
        m.put("links", links);
        List<Map<String, Object>> tasks = new ArrayList<>();
        Resource holder = project.getChild("tasks");
        if (holder != null) {
            for (Resource t : holder.getChildren()) {
                tasks.add(taskOf(t, today));
            }
        }
        // open first (soonest due, then high priority), the done ones after, latest first
        tasks.sort((a, b) -> {
            boolean da = "done".equals(a.get("status"));
            boolean db = "done".equals(b.get("status"));
            if (da != db) {
                return da ? 1 : -1;
            }
            if (da) {
                return String.valueOf(b.get("doneAt")).compareTo(String.valueOf(a.get("doneAt")));
            }
            int d = (a.get("due") == null ? "9999" : String.valueOf(a.get("due"))).compareTo(b.get("due") == null ? "9999" : String.valueOf(b.get("due")));
            return d != 0 ? d : Integer.compare(PRIORITIES.indexOf(b.get("priority")), PRIORITIES.indexOf(a.get("priority")));
        });
        m.put("tasks", tasks);
        return m;
    }

    private static Map<String, Object> taskOf(Resource t, LocalDate today) {
        ValueMap v = t.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getName());
        m.put("title", v.get("title", t.getName()));
        m.put("description", v.get("description", String.class));
        m.put("assignee", v.get("assignee", String.class));
        m.put("due", v.get("due", String.class));
        m.put("priority", v.get("priority", "medium"));
        m.put("status", v.get("status", "open"));
        m.put("created", v.get("created", String.class));
        m.put("createdBy", v.get("createdBy", String.class));
        m.put("doneAt", v.get("doneAt", String.class));
        m.put("doneBy", v.get("doneBy", String.class));
        String due = v.get("due", String.class);
        m.put("overdue", !"done".equals(v.get("status", "open")) && due != null && due.compareTo(today.toString()) < 0);
        return m;
    }

    static List<Map<String, Object>> myTasks(ResourceResolver rr, String user) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = rr.getResource(ROOT);
        if (root == null) {
            return out;
        }
        LocalDate today = LocalDate.now();
        for (Resource project : root.getChildren()) {
            Resource tasks = project.getChild("tasks");
            if (tasks == null || !project.getValueMap().get("active", true)) {
                continue;
            }
            for (Resource t : tasks.getChildren()) {
                ValueMap v = t.getValueMap();
                if (user.equals(v.get("assignee", String.class)) && !"done".equals(v.get("status", "open"))) {
                    Map<String, Object> m = taskOf(t, today);
                    m.put("project", project.getPath());
                    m.put("projectTitle", project.getValueMap().get("title", project.getName()));
                    out.add(m);
                }
            }
        }
        out.sort((a, b) -> (a.get("due") == null ? "9999" : String.valueOf(a.get("due"))).compareTo(b.get("due") == null ? "9999" : String.valueOf(b.get("due"))));
        return out;
    }

    private static List<String> without(String[] members, String who) {
        List<String> out = new ArrayList<>();
        for (String s : members) {
            if (!s.startsWith(who + "|")) {
                out.add(s);
            }
        }
        return out;
    }

    private static String required(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new Invalid(message);
        }
        return value.trim();
    }

    /** {@code yyyy-MM-dd}, or nothing. */
    static String date(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).toString();
        } catch (java.time.format.DateTimeParseException e) {
            throw new Invalid("A date is yyyy-mm-dd: " + value);
        }
    }

    private static void put(ModifiableValueMap m, String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            m.remove(name);
        } else {
            m.put(name, value.trim());
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null ? "failed" : message);
        return m;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
