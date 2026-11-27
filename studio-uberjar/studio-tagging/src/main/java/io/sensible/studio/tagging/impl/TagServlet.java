/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.tagging.impl;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.AccessDeniedException;
import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON API of the Sensible Studio Tags console ({@code /bin/studio/tags.json?op=...}):
 * <pre>
 * GET  children&amp;path=   tag&amp;path=   references&amp;path=
 * POST create (parent, title, name?, description?)
 *      update (path, title?, description?, title.&lt;lang&gt;=... ; empty removes a localized title)
 *      move (path, dest?, name?)   merge (path, target)   delete (path*)
 * </pre>
 * Runs with the requesting user's resolver, so the repository's permissions apply.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/tags",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class TagServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(TagServlet.class);

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    private void handle(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean post) throws IOException {
        String op = request.getParameter("op");
        TagService tags = new TagService(request.getResourceResolver());
        try {
            Object result = post ? write(tags, op, request) : read(tags, op, request);
            if (result == null) {
                send(response, HttpServletResponse.SC_NOT_FOUND, error("Unknown op " + op));
            } else {
                send(response, HttpServletResponse.SC_OK, result);
            }
        } catch (IllegalArgumentException e) {
            request.getResourceResolver().revert();
            send(response, HttpServletResponse.SC_BAD_REQUEST, error(e.getMessage()));
        } catch (AccessDeniedException | IllegalStateException e) {
            request.getResourceResolver().revert();
            send(response, HttpServletResponse.SC_FORBIDDEN, error("Not allowed: " + e.getMessage()));
        } catch (RepositoryException | PersistenceException e) {
            request.getResourceResolver().revert();
            LOG.warn("Tag op {} failed", op, e);
            String message = e.getMessage();
            // "Unable to create node at …" with the refusal as its cause: a 500 for what is a matter of rights
            boolean refused = false;
            for (Throwable t = e; t != null && !refused; t = t.getCause() == t ? null : t.getCause()) {
                String m = String.valueOf(t.getMessage());
                refused = t instanceof AccessDeniedException || m.contains("AccessDenied") || m.contains("OakAccess0000") || m.contains("Access denied");
            }
            send(response, refused ? HttpServletResponse.SC_FORBIDDEN : HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    error(refused ? "You are not allowed to do that here" : message));
        }
    }

    private static Object read(TagService tags, String op, SlingHttpServletRequest r) {
        switch (String.valueOf(op)) {
            case "children":
                return tags.children(r.getParameter("path"));
            case "tag":
                return tags.tag(r.getParameter("path"));
            case "references":
                return tags.references(r.getParameter("path"));
            default:
                return null;
        }
    }

    private static Object write(TagService tags, String op, SlingHttpServletRequest r)
            throws PersistenceException, RepositoryException {
        switch (String.valueOf(op)) {
            case "create":
                return tags.create(r.getParameter("parent"), r.getParameter("title"), r.getParameter("name"),
                        r.getParameter("description"));
            case "update":
                return tags.update(r.getParameter("path"), r.getParameter("title"), r.getParameter("description"), localized(r));
            case "move":
                return tags.move(r.getParameter("path"), r.getParameter("dest"), r.getParameter("name"));
            case "merge":
                return tags.merge(r.getParameter("path"), r.getParameter("target"));
            case "delete":
                return tags.delete(values(r, "path"));
            default:
                return null;
        }
    }

    /** {@code title.<lang>} parameters. */
    private static Map<String, String> localized(SlingHttpServletRequest r) {
        Map<String, String> out = new LinkedHashMap<>();
        Enumeration<String> names = r.getParameterNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            if (n.startsWith("title.")) {
                out.put(n.substring("title.".length()), r.getParameter(n));
            }
        }
        return out;
    }

    private static List<String> values(SlingHttpServletRequest r, String name) {
        String[] v = r.getParameterValues(name);
        return v == null ? Collections.emptyList() : Arrays.asList(v);
    }

    private static Map<String, String> error(String message) {
        return Collections.singletonMap("error", message == null ? "" : message);
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }
}
