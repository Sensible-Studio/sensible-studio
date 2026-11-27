/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.props;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import io.sensible.studio.wcm.impl.EditorJson;

/**
 * The Permissions tab of the page properties ({@link PagePermissions}), with the signed-in user's session:
 * <pre>
 * GET  /bin/studio/pagepermissions.json?path=   {acl: [...], cug: {supported, principals}}
 * GET  ?op=effective&amp;path=                 {effective: [...]}
 * POST op=save&amp;path=&amp;principal=&amp;read=&amp;modify=&amp;delete=&amp;replicate=&amp;create=
 * POST op=remove&amp;path=&amp;principal=
 * POST op=cug&amp;path=&amp;principal=…            (none: no closed user group)
 * </pre>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/pagepermissions",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class PagePermissionsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String path = page(request);
        Session session = request.getResourceResolver().adaptTo(Session.class);
        if (path == null || session == null) {
            send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "No page"));
            return;
        }
        try {
            PagePermissions perms = new PagePermissions(session);
            Map<String, Object> out = new LinkedHashMap<>();
            if ("effective".equals(request.getParameter("op"))) {
                out.put("effective", perms.effective(path));
            } else {
                out.put("own", perms.own(path));
                try {
                    out.put("acl", perms.acl(path));
                    out.put("cug", perms.cug(path));
                } catch (javax.jcr.AccessDeniedException e) {
                    // who may not read the access control list (an author) still learns what they may do themselves
                    out.put("acl", Collections.emptyList());
                    Map<String, Object> cug = new LinkedHashMap<>();
                    cug.put("supported", Boolean.FALSE);
                    cug.put("principals", Collections.emptyList());
                    out.put("cug", cug);
                    out.put("restricted", Boolean.TRUE);
                }
            }
            send(response, HttpServletResponse.SC_OK, out);
        } catch (RepositoryException e) {
            send(response, HttpServletResponse.SC_FORBIDDEN, Collections.singletonMap("error", e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String path = page(request);
        Session session = request.getResourceResolver().adaptTo(Session.class);
        String op = String.valueOf(request.getParameter("op"));
        if (path == null || session == null) {
            send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "No page"));
            return;
        }
        try {
            PagePermissions perms = new PagePermissions(session);
            switch (op) {
                case "save": {
                    Set<String> flags = new LinkedHashSet<>();
                    for (String f : PagePermissions.FLAGS) {
                        if ("true".equals(request.getParameter(f))) {
                            flags.add(f);
                        }
                    }
                    perms.save(path, request.getParameter("principal"), flags);
                    break;
                }
                case "remove":
                    perms.remove(path, request.getParameter("principal"));
                    break;
                case "cug": {
                    String[] names = request.getParameterValues("principal");
                    List<String> list = names == null ? Collections.emptyList() : Arrays.asList(names);
                    perms.setCug(path, list);
                    break;
                }
                default:
                    send(response, HttpServletResponse.SC_NOT_FOUND, Collections.singletonMap("error", "Unknown op " + op));
                    return;
            }
            session.save();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("acl", perms.acl(path));
            out.put("cug", perms.cug(path));
            send(response, HttpServletResponse.SC_OK, out);
        } catch (RepositoryException e) {
            try {
                session.refresh(false);
            } catch (RepositoryException ignored) {
                // nothing more to undo
            }
            send(response, HttpServletResponse.SC_CONFLICT, Collections.singletonMap("error", e.getMessage()));
        }
    }

    /** the page path, if it is a page under /content the user can read */
    private static String page(SlingHttpServletRequest request) {
        String path = request.getParameter("path");
        if (path == null || !path.startsWith("/content/") || path.contains("..") || path.contains("//")
                || path.contains("/jcr:content")) {
            return null;
        }
        return request.getResourceResolver().getResource(path) == null ? null : path;
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(EditorJson.write(body));
    }
}
