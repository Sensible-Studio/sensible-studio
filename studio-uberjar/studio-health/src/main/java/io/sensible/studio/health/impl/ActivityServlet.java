/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The activity log for the console ({@code GET /bin/studio/activity.json}):
 * {@code ?user=&type=created|modified|deleted|published|unpublished&path=&before=<epoch ms>&limit=100} →
 * {@code {entries:[{id, at, user, type, path, kind, title, exists}], keepDays, more}}, newest first.
 * For those who may read {@code /var/studio/health} (administrators): the log names what everyone did.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/activity",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET"
        })
public class ActivityServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient Activity activity;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ResourceResolver mine = request.getResourceResolver();
        String user = mine.getUserID();
        if (user == null || "anonymous".equals(user)) {
            response.setStatus(401);
            response.getWriter().write("{\"error\":\"sign in\"}");
            return;
        }
        if (mine.getResource("/var/studio/health") == null) {
            response.setStatus(403);
            response.getWriter().write("{\"error\":\"The activity log is for administrators\"}");
            return;
        }
        int limit = 100;
        long before = 0;
        try {
            if (request.getParameter("limit") != null) {
                limit = Math.max(1, Math.min(500, Integer.parseInt(request.getParameter("limit"))));
            }
            if (request.getParameter("before") != null) {
                before = Long.parseLong(request.getParameter("before"));
            }
        } catch (NumberFormatException e) {
            // the defaults
        }
        try (ResourceResolver rr = activity.serviceResolver()) {
            List<Map<String, Object>> entries = Activity.query(rr, request.getParameter("user"), request.getParameter("type"),
                    request.getParameter("path"), before, limit + 1);
            boolean more = entries.size() > limit;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entries", more ? entries.subList(0, limit) : entries);
            out.put("more", more);
            out.put("keepDays", activity.keepDays());
            response.getWriter().write(Json.write(out));
        } catch (LoginException e) {
            response.setStatus(500);
            response.getWriter().write("{\"error\":\"no service user\"}");
        }
    }
}
