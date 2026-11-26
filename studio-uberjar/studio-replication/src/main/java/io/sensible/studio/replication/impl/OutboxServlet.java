/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The outbox of a publish instance, for an author's reverse agent ({@code /bin/outbox}; an
 * authenticated transport user, as {@code /bin/receive}):
 * <ul>
 * <li>GET {@code ?op=list}: the entries waiting (id, path, action, created), oldest first — also the
 *     reverse agent's connection test;</li>
 * <li>GET {@code ?op=fetch&id=}: the entry's content pack (an Activate), as the receiver takes it;</li>
 * <li>POST {@code ?op=ack&id=[&created=]}: the entry has been installed on the author and goes (with
 *     {@code created} as listed: unless it was replaced by a newer one since).</li>
 * <li>GET {@code ?op=authorizables}: every user and group of this publish instance that travels to the
 *     others (users first), and GET {@code ?op=authorizable&path=}: one of them packed — for the first
 *     copy of the users to a new publish instance. Publish instances only, and only for whoever may
 *     write the publishing log here (the transport user): the packs hold the password hashes.</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/outbox",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class OutboxServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient Outbox outbox;

    @Reference
    private transient AgentManagerImpl agents;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!authenticated(request, response)) {
            return;
        }
        ResourceResolver rr = request.getResourceResolver();
        String op = request.getParameter("op");
        if ("authorizables".equals(op) || "authorizable".equals(op)) {
            authorizables(request, response, "authorizable".equals(op));
            return;
        }
        if ("fetch".equals(op)) {
            String id = request.getParameter("id");
            Resource file = id == null ? null : rr.getResource(Outbox.ROOT + "/" + id + "/pack/jcr:content");
            InputStream in = file == null ? null : file.adaptTo(InputStream.class);
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "No pack " + id);
                return;
            }
            response.setContentType(HttpTransport.CONTENT_TYPE);
            try (InputStream i = in) {
                i.transferTo(response.getOutputStream());
            }
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", outbox.isActive());
        out.put("entries", Outbox.list(rr));
        json(response, 200, out);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!authenticated(request, response)) {
            return;
        }
        String op = request.getParameter("op");
        if ("ack".equals(op)) {
            String id = request.getParameter("id");
            try {
                boolean gone = id != null && Outbox.ack(request.getResourceResolver(), id, request.getParameter("created"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("acked", gone);
                json(response, gone ? 200 : 404, out);
            } catch (PersistenceException e) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            }
            return;
        }
        response.sendError(HttpServletResponse.SC_BAD_REQUEST, "op is ack");
    }

    /** The users and groups that travel, or one of them packed. */
    private void authorizables(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean one) throws IOException {
        if (!AgentManagerImpl.PUBLISH_AGENTS_ROOT.equals(agents.agentsRoot())) {
            response.sendError(HttpServletResponse.SC_CONFLICT, "Users and groups are offered by publish instances only");
            return;
        }
        javax.jcr.Session caller = request.getResourceResolver().adaptTo(javax.jcr.Session.class);
        try {
            if (caller == null || !caller.hasPermission(ReplicationJob.LOG_ROOT, javax.jcr.Session.ACTION_SET_PROPERTY)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not allowed to read the users of this instance");
                return;
            }
            try (ResourceResolver service = agents.serviceResolver()) {
                javax.jcr.Session session = service.adaptTo(javax.jcr.Session.class);
                if (one) {
                    String path = request.getParameter("path");
                    javax.jcr.Node unit = path == null ? null : AuthorizablePack.unitOf(session, path);
                    if (unit == null || !unit.getPath().equals(path)) {
                        response.sendError(HttpServletResponse.SC_NOT_FOUND, "No user or group that travels at " + path);
                        return;
                    }
                    response.setContentType(HttpTransport.CONTENT_TYPE);
                    response.getOutputStream().write(AuthorizablePack.pack(session, unit));
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("entries", AuthorizablePack.travelling(session));
                json(response, 200, out);
            }
        } catch (org.apache.sling.api.resource.LoginException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No replication service user");
        } catch (javax.jcr.RepositoryException e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private static boolean authenticated(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getResourceResolver().getUserID();
        if (user == null || "anonymous".equals(user)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "The outbox needs an authenticated transport user");
            return false;
        }
        return true;
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.write(body));
    }
}
