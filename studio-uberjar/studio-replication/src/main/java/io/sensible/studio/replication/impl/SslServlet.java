/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code /bin/studio/ssl.json}, the SSL Configuration wizard's endpoint. Administrators only: it
 * changes how the instance's web server listens.
 *
 * <ul>
 * <li>GET {@code op=status}: HTTPS as configured, the certificate, whether it answers, the last change;</li>
 * <li>POST {@code op=check}: checks a key and certificate (files {@code key} and {@code certificate}, or
 * the texts {@code keyText} and {@code certificateText}, or {@code selfSigned=true}), {@code hostname},
 * {@code port}; nothing changes;</li>
 * <li>POST {@code op=apply}: the same, and HTTPS is configured with it;</li>
 * <li>POST {@code op=disable} and {@code op=enable}: HTTPS off, and on again with what was configured.</li>
 * </ul>
 * Changes happen a moment after the answer, while the web server restarts; {@code status} then says how it went.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/ssl",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST" })
public class SslServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient SslConfig ssl;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!isAdmin(request.getResourceResolver())) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Only administrators may see and change the instance's HTTPS");
            return;
        }
        String op = request.getParameter("op");
        if (op != null && !"status".equals(op)) {
            error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
            return;
        }
        Map<String, Object> out = ssl.status();
        out.put("canEdit", true);
        send(response, out);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!isAdmin(request.getResourceResolver())) {
            error(response, HttpServletResponse.SC_FORBIDDEN, "Only administrators may change the instance's HTTPS");
            return;
        }
        String op = request.getParameter("op");
        try {
            if ("check".equals(op) || "apply".equals(op)) {
                SslConfig.Prepared p = ssl.prepare(read(request));
                if ("check".equals(op)) {
                    send(response, p.describe());
                } else {
                    Map<String, Object> out = ssl.apply(p);
                    out.put("canEdit", true);
                    send(response, out);
                }
            } else if ("disable".equals(op)) {
                send(response, ssl.disable());
            } else if ("enable".equals(op)) {
                send(response, ssl.enable());
            } else {
                error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
            }
        } catch (IllegalArgumentException e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (GeneralSecurityException e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
    }

    static SslConfig.Request read(SlingHttpServletRequest request) {
        SslConfig.Request r = new SslConfig.Request();
        r.selfSigned = "true".equals(request.getParameter("selfSigned"));
        r.key = bytes(request, "key", "keyText");
        r.certificate = bytes(request, "certificate", "certificateText");
        r.hostname = request.getParameter("hostname");
        String port = request.getParameter("port");
        try {
            r.port = port == null ? 0 : Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            r.port = 0;
        }
        return r;
    }

    private static byte[] bytes(SlingHttpServletRequest request, String file, String text) {
        RequestParameter f = request.getRequestParameter(file);
        if (f != null && !f.isFormField() && f.getSize() > 0) {
            return f.get();
        }
        String t = request.getParameter(text);
        return t == null || t.trim().isEmpty() ? null : t.getBytes(StandardCharsets.US_ASCII);
    }

    /** admin, or who has every privilege at the root. */
    static boolean isAdmin(ResourceResolver resolver) {
        String user = resolver.getUserID();
        if ("admin".equals(user)) {
            return true;
        }
        Session s = resolver.adaptTo(Session.class);
        if (s == null || user == null || "anonymous".equals(user)) {
            return false;
        }
        try {
            AccessControlManager acm = s.getAccessControlManager();
            return acm.hasPrivileges("/", new Privilege[] { acm.privilegeFromName(Privilege.JCR_ALL) });
        } catch (RepositoryException | RuntimeException e) {
            return false;
        }
    }

    private static void send(SlingHttpServletResponse response, Object body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }

    private static void error(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        send(response, Collections.singletonMap("error", message == null ? "" : message));
    }
}
