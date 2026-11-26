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
 * The Sensible Studio author application: the Start screen at {@code /studio/start.html}, the Sites console at
 * {@code /sites.html/<path>}, the page properties at {@code /mnt/overlay/wcm/core/content/sites/properties.html?item=}, the security consoles at {@code /security/*.html}, the Templates console
 * ({@code /libs/wcm/core/content/sites/templates.html/conf}, its create wizard and template properties), the Components console
 * ({@code /libs/wcm/core/content/sites/components.html} and a component's details at
 * {@code /mnt/overlay/wcm/core/content/sites/components/details.html/<component>}) and the page editor at
 * {@code /editor.html/<page path>.html}, as in AEM. Answers these URLs with the
 * editor application's {@code index.html} (installed by the {@code studio-editor} content package under
 * {@link #APP}); the application routes on the URL. Anonymous visitors are sent to
 * the login form first.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/editor",
                "sling.servlet.paths=/studio/start",
                "sling.servlet.paths=/sites",
                "sling.servlet.paths=/mnt/overlay/wcm/core/content/sites/properties",
                "sling.servlet.paths=/libs/wcm/core/content/sites/templates",
                "sling.servlet.paths=/libs/wcm/core/content/sites/createtemplatewizard",
                "sling.servlet.paths=/mnt/overlay/wcm/core/content/sites/templates/properties",
                "sling.servlet.paths=/libs/wcm/core/content/sites/templates/properties",
                "sling.servlet.paths=/libs/wcm/core/content/sites/components",
                "sling.servlet.paths=/mnt/overlay/wcm/core/content/sites/components/details",
                "sling.servlet.paths=/libs/wcm/msm/gui/content/blueprintconfig",
                "sling.servlet.paths=/security/users",
                "sling.servlet.paths=/security/groups",
                "sling.servlet.paths=/security/permissions",
                "sling.servlet.paths=/security/user",
                "sling.servlet.paths=/security/group",
                "sling.servlet.paths=/security/truststore",
                "sling.servlet.paths=/security/oauth",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class EditorPageServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    static final String APP = "/libs/studio/editor/app";
    static final String LOGIN = LoginPageServlet.URL;

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
