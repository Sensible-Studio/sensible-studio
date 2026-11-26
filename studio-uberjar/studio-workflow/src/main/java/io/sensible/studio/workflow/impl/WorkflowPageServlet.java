/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

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
 * The workflow consoles ({@code /workflow/models.html}, {@code /workflow/instances.html}, {@code /workflow/inbox.html},
 * {@code /workflow/launchers.html}): answers with the author application's {@code index.html} (the {@code studio-editor}
 * content package), which routes on the URL. Anonymous visitors are sent to the login form first. The model editor
 * is the editor URL, {@code /editor.html/conf/global/settings/workflow/models/<model>.html}.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/workflow/models",
                "sling.servlet.paths=/workflow/instances",
                "sling.servlet.paths=/workflow/inbox",
                "sling.servlet.paths=/workflow/launchers",
                "sling.servlet.paths=/workflow/autoassign",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class WorkflowPageServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final String APP = "/libs/studio/editor/app";
    static final String LOGIN = "/system/sling/form/login";

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
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "The Sensible Studio editor is not installed (" + APP + "/index.html). Install the studio-editor content package.");
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
