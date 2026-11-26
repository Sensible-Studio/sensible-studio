/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Asset Reports ({@link DamReports}):
 * <pre>
 * GET  /bin/studio/dam/reports.json                         the user's reports
 * GET  /bin/studio/dam/reports.json?op=catalog              report types and columns
 * GET  /bin/studio/dam/reports.json?id=ID&amp;offset=&amp;limit=   one report, a page of its rows
 * GET  /bin/studio/dam/reports.csv?id=ID                    the CSV (download)
 * POST /bin/studio/dam/reports.json  op=create  type, title, description, path, from, to,
 *                                 column (repeated), property (repeated), excludeFragments,
 *                                 includeRenditions, excludeSubfolders           (X-Studio-CSRF)
 * POST /bin/studio/dam/reports.json  op=delete  id (repeated)
 * </pre>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam/reports",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class DamReportServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient ResourceResolverFactory factory;

    public DamReportServlet() {
    }

    DamReportServlet(ResourceResolverFactory factory) {
        this.factory = factory;
    }

    private ResourceResolver service() throws LoginException {
        return factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, DamReports.SUBSERVICE));
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getResourceResolver().getUserID();
        if (user == null || "anonymous".equals(user)) {
            json(response, 401, error("login required"));
            return;
        }
        if ("catalog".equals(param(request, "op"))) {
            json(response, 200, DamReports.catalog());
            return;
        }
        try (ResourceResolver service = service()) {
            String id = param(request, "id");
            if (id == null) {
                json(response, 200, Collections.singletonMap("items", DamReports.list(service, user, DamAccess.isAdmin(request.getResourceResolver()))));
                return;
            }
            Resource report = DamReports.get(service, user, DamAccess.isAdmin(request.getResourceResolver()), id);
            if (report == null) {
                json(response, 404, error("no report " + id));
                return;
            }
            if ("csv".equals(request.getRequestPathInfo().getExtension())) {
                byte[] body = DamReports.csvOf(report).getBytes(StandardCharsets.UTF_8);
                response.setContentType("text/csv");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Content-Disposition", "attachment; filename=\"" + DamReports.fileName(report) + "\"");
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                return;
            }
            json(response, 200, DamReports.page(report, intParam(request, "offset", 0), intParam(request, "limit", 100)));
        } catch (LoginException e) {
            json(response, 503, error("reports are not set up (service user studio-dam-reports)"));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader(DamApiServlet.CSRF_HEADER))) {
            json(response, 403, error("missing " + DamApiServlet.CSRF_HEADER + " header"));
            return;
        }
        ResourceResolver user = request.getResourceResolver();
        String owner = user.getUserID();
        if (owner == null || "anonymous".equals(owner)) {
            json(response, 401, error("login required"));
            return;
        }
        String op = param(request, "op");
        try (ResourceResolver service = service()) {
            if ("create".equals(op)) {
                json(response, 200, DamReports.create(user, service, request(request)));
            } else if ("delete".equals(op)) {
                json(response, 200, Collections.singletonMap("deleted", DamReports.delete(service, owner, DamAccess.isAdmin(user), values(request, "id"))));
            } else {
                json(response, 400, error("unknown op"));
            }
        } catch (DamApiServlet.ApiException e) {
            json(response, e.status, error(e.getMessage()));
        } catch (LoginException e) {
            json(response, 503, error("reports are not set up (service user studio-dam-reports)"));
        } catch (PersistenceException e) {
            json(response, 500, error("cannot save the report: " + e.getMessage()));
        }
    }

    static DamReports.Request request(SlingHttpServletRequest request) throws DamApiServlet.ApiException {
        DamReports.Request q = new DamReports.Request();
        q.type = DamReports.Type.of(param(request, "type"));
        q.title = param(request, "title");
        q.description = param(request, "description");
        String path = param(request, "path");
        if (path != null && !path.trim().isEmpty()) {
            q.path = path.trim().replaceAll("/+$", "");
        }
        q.from = DamReports.date(param(request, "from"), false);
        q.to = DamReports.date(param(request, "to"), true);
        if (request.getRequestParameter("column") != null || request.getRequestParameter("columnsSet") != null) {
            q.columns = values(request, "column");
        }
        q.properties = values(request, "property");
        q.excludeFragments = "true".equals(param(request, "excludeFragments"));
        q.includeRenditions = "true".equals(param(request, "includeRenditions"));
        q.excludeSubfolders = "true".equals(param(request, "excludeSubfolders"));
        return q;
    }

    private static List<String> values(SlingHttpServletRequest request, String name) {
        List<String> out = new ArrayList<>();
        RequestParameter[] ps = request.getRequestParameters(name);
        if (ps != null) {
            for (RequestParameter p : ps) {
                String v = p.getString().trim();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static int intParam(SlingHttpServletRequest request, String name, int fallback) {
        try {
            return Integer.parseInt(String.valueOf(param(request, name)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void json(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonOut.write(body));
    }
}
