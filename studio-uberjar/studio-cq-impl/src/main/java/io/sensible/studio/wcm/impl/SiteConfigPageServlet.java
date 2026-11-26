/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

/**
 * The Site configurations console at {@code /studio/siteconfig.html}: answers with the author application's
 * {@code index.html} (the application routes on the URL), anonymous visitors go to the login form.
 * The content path follows as the suffix.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/studio/siteconfig",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class SiteConfigPageServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final String APP = "/libs/studio/editor/app";
    static final String LOGIN = "/libs/granite/core/content/login.html";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws ServletException, IOException {
        if ("anonymous".equals(request.getResourceResolver().getUserID())) {
            String target = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect(request.getContextPath() + LOGIN + "?resource="
                    + URLEncoder.encode(target, StandardCharsets.UTF_8.name()));
            return;
        }
        Resource index = request.getResourceResolver().getResource(APP + "/index.html");
        InputStream in = index == null ? null : index.adaptTo(InputStream.class);
        if (in == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "The Sensible Studio editor is not installed (" + APP + "/index.html).");
            return;
        }
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        try (InputStream body = in; OutputStream out = response.getOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = body.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
        }
    }
}
