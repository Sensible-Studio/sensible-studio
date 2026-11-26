/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.security.impl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON API of the Sensible Studio security consoles ({@code /bin/studio/security.json?op=...}):
 * <pre>
 * GET  users[&amp;q=&amp;system=true]   groups[&amp;q=]   authorizable&amp;id=
 *      aces&amp;principal=   effective&amp;principal=&amp;path=   privileges
 *      tree&amp;principal=&amp;path=      (a node and its children: what the principal can do there, what is set there)
 * POST createUser (id, password, profile.*)   createGroup (id, profile.*)
 *      update (id, profile.*, disabled, reason, password)   delete (id)
 *      memberOf (id, group*)   members (id, member*)   impersonators (id, principal*)
 *      saveAce (principal, path, allow, privilege*, glob, index?)   removeAce (principal, path, index)
 *      setPermissions (principal, path, read|modify|create|delete|readAcl|editAcl|replicate = allow|deny|inherit)
 * </pre>
 * Everything runs with the requesting user's session, so the repository's own permissions apply.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/security",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class SecurityServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(SecurityServlet.class);

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    private void handle(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean post) throws IOException {
        Session session = request.getResourceResolver().adaptTo(Session.class);
        String op = request.getParameter("op");
        try {
            if (session == null) {
                throw new RepositoryException("No JCR session");
            }
            SecurityService s = new SecurityService(session);
            Object result = post ? write(s, op, request) : read(s, op, request);
            if (result == null) {
                send(response, HttpServletResponse.SC_NOT_FOUND, error("Unknown op " + op));
            } else {
                send(response, HttpServletResponse.SC_OK, result);
            }
        } catch (IllegalArgumentException e) {
            refresh(session);
            send(response, HttpServletResponse.SC_BAD_REQUEST, error(e.getMessage()));
        } catch (AccessDeniedException e) {
            refresh(session);
            send(response, HttpServletResponse.SC_FORBIDDEN, error("Not allowed: " + e.getMessage()));
        } catch (RepositoryException e) {
            refresh(session);
            LOG.warn("Security op {} failed", op, e);
            send(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, error(e.getMessage()));
        }
    }

    private static Object read(SecurityService s, String op, SlingHttpServletRequest r) throws RepositoryException {
        switch (String.valueOf(op)) {
            case "users":
                return s.list(false, r.getParameter("q"), "true".equals(r.getParameter("system")));
            case "groups":
                return s.list(true, r.getParameter("q"), true);
            case "authorizable":
                return s.detail(r.getParameter("id"));
            case "aces":
                return s.aces(r.getParameter("principal"));
            case "effective":
                return s.effective(r.getParameter("principal"), r.getParameter("path"));
            case "privileges":
                return s.privileges();
            case "tree":
                return new PermissionTree(r.getResourceResolver().adaptTo(Session.class)).level(r.getParameter("principal"), r.getParameter("path"));
            default:
                return null;
        }
    }

    private static Object write(SecurityService s, String op, SlingHttpServletRequest r) throws RepositoryException {
        String id = r.getParameter("id");
        switch (String.valueOf(op)) {
            case "createUser":
                return s.createUser(id, r.getParameter("password"), profile(r));
            case "createGroup":
                return s.createGroup(id, profile(r));
            case "update": {
                String disabled = r.getParameter("disabled");
                return s.update(id, profile(r), disabled == null ? null : Boolean.valueOf(disabled), r.getParameter("reason"),
                        r.getParameter("password"));
            }
            case "delete":
                s.delete(id);
                return Collections.singletonMap("deleted", id);
            case "memberOf":
                return s.setMemberOf(id, values(r, "group"));
            case "members":
                return s.setMembers(id, values(r, "member"));
            case "impersonators":
                return s.setImpersonators(id, values(r, "principal"));
            case "saveAce": {
                String index = r.getParameter("index");
                return s.saveAce(r.getParameter("principal"), path(r), "true".equals(r.getParameter("allow")),
                        values(r, "privilege"), r.getParameter("glob"), index == null || index.isEmpty() ? null : Integer.valueOf(index));
            }
            case "removeAce":
                return s.removeAce(r.getParameter("principal"), path(r), Integer.parseInt(r.getParameter("index")));
            case "setPermissions": {
                Map<String, String> values = new LinkedHashMap<>();
                for (PermissionTree.Column c : PermissionTree.COLUMNS) {
                    String v = r.getParameter(c.id);
                    if (v != null && !v.isEmpty()) {
                        values.put(c.id, v);
                    }
                }
                return new PermissionTree(r.getResourceResolver().adaptTo(Session.class)).set(r.getParameter("principal"), r.getParameter("path"), values);
            }
            default:
                return null;
        }
    }

    /** {@code path} empty means the repository-level ACL. */
    private static String path(SlingHttpServletRequest r) {
        String p = r.getParameter("path");
        return p == null || p.isEmpty() ? null : p;
    }

    private static Map<String, String> profile(SlingHttpServletRequest r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String p : SecurityService.PROFILE) {
            String v = r.getParameter("profile." + p);
            if (v != null) {
                m.put(p, v);
            }
        }
        return m;
    }

    private static List<String> values(SlingHttpServletRequest r, String name) {
        String[] v = r.getParameterValues(name);
        return v == null ? Collections.emptyList() : Arrays.asList(v);
    }

    private static Map<String, String> error(String message) {
        return Collections.singletonMap("error", message == null ? "" : message);
    }

    private static void refresh(Session session) {
        try {
            if (session != null && session.hasPendingChanges()) {
                session.refresh(false);
            }
        } catch (RepositoryException e) {
            LOG.debug("Could not revert", e);
        }
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }
}
