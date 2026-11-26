/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.servlet.Servlet;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * {@code GET /libs/granite/security/currentuser.json}: who is making the request, in the shape AEM
 * sites read it (the WKND header's "Welcome, Administrator" uses {@code authorizableId} and
 * {@code name}). {@code ?props=profile/*} adds the user's profile.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/libs/granite/security/currentuser",
                "sling.servlet.methods=GET"
        })
public class CurrentUserServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String userId = request.getResourceResolver().getUserID();
        if (userId == null) {
            userId = "anonymous";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "user");
        out.put("authorizableId", userId);
        String name = userId;
        String home = null;
        Map<String, Object> profile = new LinkedHashMap<>();
        Session session = request.getResourceResolver().adaptTo(Session.class);
        if (session instanceof JackrabbitSession) {
            try {
                Authorizable a = ((JackrabbitSession) session).getUserManager().getAuthorizable(userId);
                if (a != null) {
                    home = a.getPath();
                    String given = first(a, "profile/givenName");
                    String family = first(a, "profile/familyName");
                    if (!given.isEmpty()) {
                        profile.put("givenName", given);
                    }
                    if (!family.isEmpty()) {
                        profile.put("familyName", family);
                    }
                    String full = (given + " " + family).trim();
                    if (!full.isEmpty()) {
                        name = full;
                    }
                }
            } catch (RepositoryException e) {
                // the id is still a valid answer
            }
        }
        if ("admin".equals(userId) && name.equals(userId)) {
            name = "Administrator";
        }
        out.put("name", name);
        out.put("home", home);
        out.put("isImpersonated", false);
        String props = request.getParameter("props");
        if (props != null && props.startsWith("profile")) {
            out.put("profile", profile);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(out));
    }

    private static String first(Authorizable a, String property) throws RepositoryException {
        Value[] v = a.getProperty(property);
        return v == null || v.length == 0 ? "" : v[0].getString().trim();
    }
}
