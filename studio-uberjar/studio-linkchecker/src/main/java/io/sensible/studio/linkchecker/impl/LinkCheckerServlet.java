/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.linkchecker.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code /bin/studio/linkchecker.json}, for signed-in authors:
 * <ul>
 * <li>GET {@code op=list&status=all|valid|invalid|pending&kind=all|external|internal&q=&offset=&limit=}: the
 *     links (invalid first, then pending, then valid), the counts per status and the run's progress</li>
 * <li>GET {@code op=info&url=}: one link with every place it is written</li>
 * <li>GET {@code op=progress}</li>
 * <li>POST {@code op=scan}: scan content and check what is due; {@code op=check&url=…} (repeatable): check
 *     these now; {@code op=checkAll}: check every link now; {@code op=delete&url=…}: forget these links
 *     (a scan finds them again while content uses them). POSTs need {@code X-Studio-CSRF: 1}.</li>
 * </ul>
 * The links are read and written with the link checker's service user: they are a report on all content,
 * the same for every author who may open the console.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/linkchecker",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class LinkCheckerServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String CSRF_HEADER = "X-Studio-CSRF";

    @Reference
    private transient LinkChecker checker;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (anonymous(request)) {
            send(response, 401, error("sign in"));
            return;
        }
        String op = request.getParameter("op");
        try (ResourceResolver rr = checker.serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            if ("list".equals(op)) {
                send(response, 200, list(session, request));
            } else if ("info".equals(op)) {
                Node n = Links.find(session, request.getParameter("url"));
                if (n == null) {
                    send(response, 404, error("no such link"));
                    return;
                }
                Map<String, Object> out = Links.toJson(n, 0);
                List<Object> refs = new ArrayList<>();
                if (n.hasProperty("referrers")) {
                    for (Value v : n.getProperty("referrers").getValues()) {
                        refs.add(Links.referrerJson(request.getResourceResolver().adaptTo(Session.class), v.getString()));
                    }
                }
                out.put("referrers", refs);
                send(response, 200, out);
            } else if ("progress".equals(op)) {
                send(response, 200, checker.progress());
            } else {
                send(response, 400, error("unknown op"));
            }
        } catch (Exception e) {
            send(response, 500, error(e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (anonymous(request)) {
            send(response, 401, error("sign in"));
            return;
        }
        if (!"1".equals(request.getHeader(CSRF_HEADER))) {
            send(response, 403, error("missing " + CSRF_HEADER + " header"));
            return;
        }
        String op = request.getParameter("op");
        String by = request.getResourceResolver().getUserID();
        String[] urls = request.getParameterValues("url");
        boolean started;
        if ("scan".equals(op)) {
            started = checker.start(true, null, false, by);
        } else if ("checkAll".equals(op)) {
            started = checker.start(false, null, true, by);
        } else if ("check".equals(op)) {
            if (urls == null || urls.length == 0) {
                send(response, 400, error("url is required"));
                return;
            }
            started = checker.start(false, Arrays.asList(urls), true, by);
        } else if ("delete".equals(op)) {
            if (urls == null || urls.length == 0) {
                send(response, 400, error("url is required"));
                return;
            }
            try (ResourceResolver rr = checker.serviceResolver()) {
                Session session = rr.adaptTo(Session.class);
                int removed = 0;
                for (String u : urls) {
                    Node n = Links.find(session, u);
                    if (n != null) {
                        n.remove();
                        removed++;
                    }
                }
                session.save();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("removed", removed);
                send(response, 200, out);
            } catch (Exception e) {
                send(response, 500, error(e.getMessage()));
            }
            return;
        } else {
            send(response, 400, error("unknown op"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", started);
        out.put("progress", checker.progress());
        send(response, started ? 202 : 409, out);
    }

    private Map<String, Object> list(Session session, SlingHttpServletRequest request) throws RepositoryException {
        String status = param(request, "status", "all");
        String kind = param(request, "kind", "all");
        String q = param(request, "q", "").toLowerCase(Locale.ROOT);
        int offset = Math.max(0, intParam(request, "offset", 0));
        int limit = Math.min(1000, Math.max(1, intParam(request, "limit", 200)));

        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : new String[] { "all", Links.INVALID, Links.PENDING, Links.VALID }) {
            counts.put(s, 0L);
        }
        List<Node> shown = new ArrayList<>();
        for (Node n : Links.all(session)) {
            String url = n.getProperty("url").getString();
            String k = n.hasProperty("kind") ? n.getProperty("kind").getString() : Links.EXTERNAL;
            if (!"all".equals(kind) && !kind.equals(k)) {
                continue;
            }
            if (!q.isEmpty() && !url.toLowerCase(Locale.ROOT).contains(q) && !referrersContain(n, q)) {
                continue;
            }
            String s = n.hasProperty("status") ? n.getProperty("status").getString() : Links.PENDING;
            counts.merge("all", 1L, Long::sum);
            counts.merge(s, 1L, Long::sum);
            if ("all".equals(status) || status.equals(s)) {
                shown.add(n);
            }
        }
        shown.sort(Comparator.comparingInt(LinkCheckerServlet::rank).thenComparing(LinkCheckerServlet::urlOf));
        List<Object> links = new ArrayList<>();
        for (int i = offset; i < shown.size() && i < offset + limit; i++) {
            links.add(Links.toJson(shown.get(i), 3));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("links", links);
        out.put("total", shown.size());
        out.put("counts", counts);
        out.put("progress", checker.progress());
        out.put("checkPeriodHours", checker.checkPeriodMillis() / 3_600_000L);
        return out;
    }

    private static boolean referrersContain(Node n, String q) throws RepositoryException {
        if (!n.hasProperty("referrers")) {
            return false;
        }
        for (Value v : n.getProperty("referrers").getValues()) {
            if (v.getString().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        return false;
    }

    private static int rank(Node n) {
        try {
            String s = n.hasProperty("status") ? n.getProperty("status").getString() : Links.PENDING;
            return Links.INVALID.equals(s) ? 0 : Links.PENDING.equals(s) ? 1 : 2;
        } catch (RepositoryException e) {
            return 3;
        }
    }

    private static String urlOf(Node n) {
        try {
            return n.getProperty("url").getString();
        } catch (RepositoryException e) {
            return "";
        }
    }

    private static boolean anonymous(SlingHttpServletRequest request) {
        String user = request.getResourceResolver().getUserID();
        return user == null || "anonymous".equals(user);
    }

    private static String param(SlingHttpServletRequest r, String name, String def) {
        String v = r.getParameter(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static int intParam(SlingHttpServletRequest r, String name, int def) {
        try {
            return Integer.parseInt(r.getParameter(name));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.getWriter().write(Json.write(body));
    }
}
