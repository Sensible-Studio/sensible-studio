/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.launches.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;

import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.Replicator;

import org.slf4j.LoggerFactory;

/**
 * {@code /bin/studio/launches.json}:
 * <ul>
 * <li>GET {@code op=list}: the launches, newest first, each with its page count and modified count</li>
 * <li>GET {@code op=info&path=<any path>}: the launch the path belongs to (null when none), its pages and
 *     the source of that page, plus the launches made from the page: what the editor's status bar and
 *     page menu need</li>
 * <li>POST {@code op=create&source=<page>&title=&description=&liveDate=&deep=true|false&liveCopy=true|false}
 *     (and {@code op=addSource&path=<launch>&source=<page>&deep=&liveCopy=} for one more source page):
 *     with {@code liveCopy} the pages are not copied; the response says where the caller creates the MSM
 *     live copy ({@code liveCopyParent}, {@code liveCopyName})</li>
 * <li>POST {@code op=update&path=<launch>&title=&description=&liveDate=}</li>
 * <li>POST {@code op=promote&path=<launch>&scope=all|page|pageAndSubpages&page=<launch page>&modifiedOnly=&delete=&publish=}
 *     ({@code publish=true} also publishes the promoted pages)</li>
 * <li>POST {@code op=delete&path=<launch>}</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/launches",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class LaunchesServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(LaunchesServlet.class);

    /** Publishes promoted pages when asked to; absent on an instance without replication. */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile Replicator replicator;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        Session session = request.getResourceResolver().adaptTo(Session.class);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("list".equals(op)) {
                List<Object> list = new ArrayList<>();
                for (Launches.Launch l : Launches.list(session)) {
                    Map<String, Object> m = l.toJson();
                    List<Launches.LaunchPage> pages = Launches.pages(session, l);
                    m.put("pages", pages.size());
                    m.put("modified", pages.stream().filter(p -> p.modified).count());
                    list.add(m);
                }
                out.put("launches", list);
            } else if ("info".equals(op)) {
                String path = request.getParameter("path");
                Launches.Launch l = Launches.of(session, path);
                out.put("launch", l == null ? null : l.toJson());
                if (l != null) {
                    out.put("sourcePath", Launches.sourceOf(l, path));
                    out.put("pages", pagesJson(session, l));
                }
                List<Object> from = new ArrayList<>();
                if (l == null && path != null) {
                    for (Launches.Launch x : Launches.list(session)) {
                        if (x.promoted == null && x.covers(path)
                                && session.nodeExists(Launches.inLaunch(x, path))) {
                            Map<String, Object> m = x.toJson();
                            m.put("pageInLaunch", Launches.inLaunch(x, path));
                            from.add(m);
                        }
                    }
                }
                out.put("launchesOfPage", from);
            } else {
                error(response, 400, "Unknown op " + op);
                return;
            }
            write(response, 200, out);
        } catch (RepositoryException | RuntimeException e) {
            LOG.warn("launches {} failed", op, e);
            error(response, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        Session session = request.getResourceResolver().adaptTo(Session.class);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (op == null ? "" : op) {
                case "create": {
                    boolean liveCopy = "true".equals(request.getParameter("liveCopy"));
                    Launches.Launch l = Launches.create(session, request.getParameter("source"), request.getParameter("title"),
                            parseDate(request.getParameter("liveDate")), !"false".equals(request.getParameter("deep")), liveCopy,
                            request.getParameter("description"));
                    if ("true".equals(request.getParameter("productionReady"))) {
                        Launches.setProductionReady(session, l, true);
                    }
                    session.save();
                    l = new Launches.Launch(session.getNode(l.path));
                    out.put("launch", l.toJson());
                    if (liveCopy) {
                        String in = l.sourceInLaunch();
                        out.put("liveCopyParent", in.substring(0, in.lastIndexOf('/')));
                        out.put("liveCopyName", in.substring(in.lastIndexOf('/') + 1));
                    }
                    break;
                }
                case "addSource": {
                    Launches.Launch l = launch(session, request.getParameter("path"));
                    boolean liveCopy = "true".equals(request.getParameter("liveCopy"));
                    String in = Launches.addSource(session, l, request.getParameter("source"), !"false".equals(request.getParameter("deep")), liveCopy);
                    session.save();
                    out.put("launch", new Launches.Launch(session.getNode(l.path)).toJson());
                    out.put("pageInLaunch", in);
                    if (liveCopy) {
                        out.put("liveCopyParent", in.substring(0, in.lastIndexOf('/')));
                        out.put("liveCopyName", in.substring(in.lastIndexOf('/') + 1));
                    }
                    break;
                }
                case "update": {
                    Launches.Launch l = launch(session, request.getParameter("path"));
                    Node jc = session.getNode(l.path).getNode("jcr:content");
                    String title = request.getParameter("title");
                    if (title != null && !title.isEmpty()) {
                        jc.setProperty("jcr:title", title);
                    }
                    String description = request.getParameter("description");
                    if (description != null) {
                        jc.setProperty("jcr:description", description.isEmpty() ? null : description);
                    }
                    if (request.getParameter("liveDate") != null) {
                        jc.setProperty(Launches.PROP_LIVE_DATE, parseDate(request.getParameter("liveDate")));
                    }
                    if (request.getParameter("productionReady") != null) {
                        Launches.setProductionReady(session, l, "true".equals(request.getParameter("productionReady")));
                    }
                    session.save();
                    out.put("launch", new Launches.Launch(session.getNode(l.path)).toJson());
                    break;
                }
                case "promote": {
                    Launches.Launch l = launch(session, request.getParameter("path"));
                    String scope = request.getParameter("scope") == null ? "all" : request.getParameter("scope");
                    Launches.Promotion p = Launches.promote(session, l, scope, request.getParameter("page"), "true".equals(request.getParameter("modifiedOnly")));
                    boolean delete = "true".equals(request.getParameter("delete"));
                    if (delete) {
                        session.getNode(l.path).remove();
                    }
                    session.save();
                    out.put("result", p.toJson());
                    out.put("deleted", delete);
                    if ("true".equals(request.getParameter("publish"))) {
                        // the promotion stands whatever happens to the publishing; the answer says which
                        try {
                            out.put("published", LaunchAutoPromotion.publish(replicator, session, p));
                        } catch (ReplicationException e) {
                            out.put("publishError", e.getMessage());
                        }
                    }
                    break;
                }
                case "delete": {
                    Launches.Launch l = launch(session, request.getParameter("path"));
                    session.getNode(l.path).remove();
                    session.save();
                    out.put("deleted", l.path);
                    break;
                }
                default:
                    error(response, 400, "Unknown op " + op);
                    return;
            }
            write(response, 200, out);
        } catch (IllegalArgumentException e) {
            refresh(session);
            error(response, 400, e.getMessage());
        } catch (RepositoryException | RuntimeException e) {
            refresh(session);
            LOG.warn("launches {} failed", op, e);
            error(response, 500, e.getMessage());
        }
    }

    private static Launches.Launch launch(Session session, String path) throws RepositoryException {
        Launches.Launch l = Launches.of(session, path);
        if (l == null) {
            throw new IllegalArgumentException("No launch at " + path);
        }
        return l;
    }

    private static List<Object> pagesJson(Session session, Launches.Launch l) throws RepositoryException {
        List<Object> out = new ArrayList<>();
        for (Launches.LaunchPage p : Launches.pages(session, l)) {
            out.add(p.toJson());
        }
        return out;
    }

    /** ISO 8601 (with or without seconds and zone) or epoch milliseconds; null when empty. */
    static Calendar parseDate(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        if (s.matches("\\d{10,}")) {
            c.setTimeInMillis(Long.parseLong(s));
            return c;
        }
        String v = s.length() == 16 ? s + ":00" : s;
        if (!v.matches(".*(Z|[+-]\\d{2}:\\d{2})$")) {
            c.setTime(java.util.Date.from(java.time.LocalDateTime.parse(v).atZone(java.time.ZoneId.systemDefault()).toInstant()));
        } else {
            c.setTime(java.util.Date.from(java.time.OffsetDateTime.parse(v).toInstant()));
        }
        return c;
    }

    private static void refresh(Session session) {
        try {
            session.refresh(false);
        } catch (RepositoryException ignored) {
            // the response already says what failed
        }
    }

    private static void write(SlingHttpServletResponse response, int status, Map<String, Object> body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.write(body));
    }

    private static void error(SlingHttpServletResponse response, int status, String message) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message == null ? "failed" : message);
        write(response, status, m);
    }
}
