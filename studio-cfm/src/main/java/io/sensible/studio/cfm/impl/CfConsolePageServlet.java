/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.cfm.impl;

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
 * The Content Fragments screens: the console at {@code /cf.html/<dam folder>}, the models at
 * {@code /cfm/models.html/<conf or model>}, the GraphQL endpoints at {@code /cfm/graphql.html} and the query editor at
 * {@code /cfm/graphiql.html}. Answers with the Sensible Studio author application's
 * {@code index.html} (the {@code studio-editor} package), which routes on the URL; fragments open in
 * {@code /editor.html/<fragment path>}, which the editor page servlet already serves. Anonymous visitors
 * are sent to the login form first.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/cf",
                "sling.servlet.paths=/cfm/models",
                "sling.servlet.paths=/cfm/graphiql",
                "sling.servlet.paths=/cfm/graphql",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
public class CfConsolePageServlet extends SlingSafeMethodsServlet {

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
                    "The Sensible Studio author application is not installed (" + APP + "/index.html).");
            return;
        }
        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        try (InputStream body = in; OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }
}
