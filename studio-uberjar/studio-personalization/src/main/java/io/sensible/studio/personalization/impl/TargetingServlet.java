/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /bin/studio/targeting.json}: what the editor's Targeting mode, the ContextHub bar and the
 * Audiences console need.
 * <ul>
 * <li>GET {@code op=segments&path=<page or conf>}: the audiences the page can target</li>
 * <li>GET {@code op=personas&path=<page>}: the personas (ContextHub's simulated visitors)</li>
 * <li>POST {@code op=setSplit&path=&mode=targeting|abtest&shares={"default":50,"b":50}}: an A/B test's split</li>
 * <li>GET {@code op=contexthub&path=<page or conf>}: ContextHub's configuration (stores, the bar's fields)</li>
 * <li>POST {@code op=saveContexthub} conf, config (JSON); {@code op=resetContexthub} conf</li>
 * <li>GET {@code op=target&path=<component>}: whether it is targeted, its experiences, goal and stats</li>
 * <li>GET {@code op=stats&path=<component>}: impressions and conversions per experience</li>
 * <li>GET {@code op=activities&path=<page or site>}: every targeted component below, with goal and stats</li>
 * <li>GET {@code op=redirects&path=<page>}: the page's audience redirects</li>
 * <li>POST {@code op=setGoal} path, goalType (none|click|pageview|custom), goalValue, activity</li>
 * <li>POST {@code op=setRedirects} path, redirects (JSON [{segment, target}])</li>
 * <li>POST {@code op=resetStats} path</li>
 * <li>POST {@code op=saveSegment} conf, name, title, description, boost, expression (JSON tree)</li>
 * <li>POST {@code op=removeSegment} path</li>
 * <li>POST {@code op=target|untarget} path (untarget: keep)</li>
 * <li>POST {@code op=addExperience|updateExperience|removeExperience} path, name, title, segment, from</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/targeting",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class TargetingServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(TargetingServlet.class);
    static final String PERSONAS = "/settings/contexthub/personas";
    static final String[] STORES = {"profile", "geolocation", "surferinfo"};

    /** What the live instances counted: the reports of an author add it to the author's own counters. */
    @org.osgi.service.component.annotations.Reference
    private transient LiveStats live;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        ResourceResolver rr = request.getResourceResolver();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("segments".equals(op)) {
                String conf = Segments.confOf(rr, path);
                out.put("conf", conf);
                List<Object> list = new ArrayList<>();
                for (Segments.Segment s : path != null && path.startsWith("/conf/") ? Segments.list(rr, conf) : Segments.forPage(rr, path)) {
                    list.add(s.toJson());
                }
                out.put("segments", list);
                out.put("operators", Segments.OPERATORS);
            } else if ("personas".equals(op)) {
                out.put("personas", personas(rr, Segments.confOf(rr, path)));
            } else if ("contexthub".equals(op)) {
                // ContextHub's configuration for a page or a configuration: stores and the bar's fields
                out = ContextHubConfig.effective(rr, path);
                out.put("properties", ContextHubConfig.properties(out));
                out.put("canEdit", canEdit(rr, String.valueOf(out.get("conf"))));
            } else if ("target".equals(op)) {
                Session session = rr.adaptTo(Session.class);
                if (path == null || !session.nodeExists(path)) {
                    error(response, 404, "No component at " + path);
                    return;
                }
                target(out, session.getNode(path));
                out.put("stats", live.withLive(Stats.read(session, path), path));
                out.put("statsSources", live.sources());
            } else if ("stats".equals(op)) {
                out.put("stats", live.withLive(Stats.read(rr.adaptTo(Session.class), path), path));
                out.put("statsSources", live.sources());
            } else if ("allStats".equals(op)) {
                // this instance's own counters, as they are: what an author asks a publish instance for
                out.put("stats", Stats.all(rr.adaptTo(Session.class)));
            } else if ("activities".equals(op)) {
                Session session = rr.adaptTo(Session.class);
                List<Map<String, Object>> targets = Targeting.findTargets(session, path, 500);
                for (Map<String, Object> t : targets) {
                    t.put("stats", live.withLive(Stats.read(session, String.valueOf(t.get("path"))), String.valueOf(t.get("path"))));
                }
                out.put("activities", targets);
                out.put("statsSources", live.sources());
            } else if ("redirects".equals(op)) {
                Session session = rr.adaptTo(Session.class);
                if (path == null || !session.nodeExists(path + "/jcr:content")) {
                    error(response, 404, "No page at " + path);
                    return;
                }
                out.put("redirects", Targeting.redirects(session.getNode(path + "/jcr:content")));
            } else {
                error(response, 400, "Unknown op " + op);
                return;
            }
            write(response, 200, out);
        } catch (RepositoryException | RuntimeException e) {
            LOG.warn("targeting {} failed", op, e);
            error(response, 500, e.getMessage());
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        ResourceResolver rr = request.getResourceResolver();
        Session session = rr.adaptTo(Session.class);
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (op == null ? "" : op) {
                case "saveSegment": {
                    String conf = request.getParameter("conf");
                    if (conf == null || conf.isEmpty()) {
                        conf = Segments.confOf(rr, path);
                    }
                    Object expr = Json.parse(request.getParameter("expression") == null ? "null" : request.getParameter("expression"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> expression = expr instanceof Map ? (Map<String, Object>) expr : null;
                    String boost = request.getParameter("boost");
                    Segments.Segment s = Segments.save(session, conf, request.getParameter("name"), request.getParameter("title"),
                            request.getParameter("description"), boost == null || boost.isEmpty() ? null : Long.valueOf(boost), expression);
                    session.save();
                    out.put("segment", s.toJson());
                    segmentsOf(out, rr, conf);
                    break;
                }
                case "removeSegment": {
                    String conf = Segments.confOf(rr, path);
                    Segments.remove(session, path);
                    session.save();
                    segmentsOf(out, rr, conf);
                    break;
                }
                case "target": {
                    Node t = Targeting.target(session, path);
                    session.save();
                    target(out, t);
                    break;
                }
                case "untarget": {
                    Node n = Targeting.untarget(session, path, request.getParameter("keep"));
                    session.save();
                    target(out, n);
                    break;
                }
                case "addExperience": {
                    Targeting.addExperience(session, path, request.getParameter("name"), request.getParameter("title"),
                            request.getParameter("segment"), request.getParameter("from"));
                    session.save();
                    target(out, session.getNode(path));
                    break;
                }
                case "updateExperience": {
                    Targeting.updateExperience(session, path, request.getParameter("name"), request.getParameter("title"), request.getParameter("segment"));
                    session.save();
                    target(out, session.getNode(path));
                    break;
                }
                case "setGoal": {
                    Targeting.setGoal(session, path, request.getParameter("goalType"), request.getParameter("goalValue"), request.getParameter("activity"));
                    session.save();
                    target(out, session.getNode(path));
                    break;
                }
                case "setSplit": {
                    // by audience, or an A/B test: the visitors split among the experiences by percent
                    Object parsed = Json.parse(request.getParameter("shares") == null ? "{}" : request.getParameter("shares"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> shares = parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
                    Targeting.setSplit(session, path, request.getParameter("mode"), shares);
                    session.save();
                    target(out, session.getNode(path));
                    break;
                }
                case "setRedirects": {
                    Object parsed = Json.parse(request.getParameter("redirects") == null ? "[]" : request.getParameter("redirects"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> list = parsed instanceof List ? (List<Map<String, Object>>) parsed : new ArrayList<>();
                    Targeting.setRedirects(session, path, list);
                    session.save();
                    out.put("redirects", Targeting.redirects(session.getNode(path + "/jcr:content")));
                    break;
                }
                case "resetStats": {
                    Stats.reset(session, path);
                    session.save();
                    // and where the counting is done: on the live instances
                    List<String> failed = live.reset(path);
                    out.put("stats", new LinkedHashMap<>());
                    out.put("notReset", failed);
                    break;
                }
                case "removeExperience": {
                    Targeting.removeExperience(session, path, request.getParameter("name"));
                    session.save();
                    target(out, session.getNode(path));
                    break;
                }
                case "saveContexthub": {
                    String conf = request.getParameter("conf");
                    String config = request.getParameter("config");
                    ContextHubConfig.save(session, conf, Json.parse(config == null ? "null" : config));
                    session.save();
                    out = ContextHubConfig.effective(rr, conf);
                    out.put("properties", ContextHubConfig.properties(out));
                    out.put("canEdit", true);
                    break;
                }
                case "resetContexthub": {
                    String conf = request.getParameter("conf");
                    ContextHubConfig.reset(session, conf);
                    session.save();
                    out = ContextHubConfig.effective(rr, conf);
                    out.put("properties", ContextHubConfig.properties(out));
                    out.put("canEdit", true);
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
            LOG.warn("targeting {} failed", op, e);
            error(response, 500, e.getMessage());
        }
    }

    /** Whether the user may write the configuration's ContextHub settings. */
    static boolean canEdit(ResourceResolver rr, String conf) {
        Session s = rr.adaptTo(Session.class);
        if (s == null || conf == null) {
            return false;
        }
        try {
            String at = s.nodeExists(conf + "/settings") ? conf + "/settings" : conf;
            return s.nodeExists(at) && s.hasPermission(at, Session.ACTION_ADD_NODE + "," + Session.ACTION_SET_PROPERTY);
        } catch (RepositoryException e) {
            return false;
        }
    }

    private static void refresh(Session session) {
        try {
            session.refresh(false);
        } catch (RepositoryException ignored) {
            // the response already says what failed
        }
    }

    private static void segmentsOf(Map<String, Object> out, ResourceResolver rr, String conf) {
        List<Object> list = new ArrayList<>();
        for (Segments.Segment s : Segments.list(rr, conf)) {
            list.add(s.toJson());
        }
        out.put("conf", conf);
        out.put("segments", list);
    }

    private static void target(Map<String, Object> out, Node n) throws RepositoryException {
        out.put("path", n.getPath());
        boolean targeted = Targeting.isTarget(n);
        out.put("targeted", targeted);
        out.put("experiences", targeted ? Targeting.experiences(n) : new ArrayList<>());
        out.put("goal", targeted ? Targeting.goal(n) : null);
        out.put("mode", targeted ? Targeting.mode(n) : Targeting.MODE_TARGETING);
    }

    /** The personas: {@code /libs/settings/contexthub/personas} and the configuration's own. */
    static List<Object> personas(ResourceResolver rr, String conf) {
        List<Object> out = new ArrayList<>();
        for (String root : new String[] {conf + PERSONAS, Segments.GLOBAL + PERSONAS, "/libs" + PERSONAS}) {
            Resource r = rr.getResource(root);
            if (r == null) {
                continue;
            }
            for (Resource p : r.getChildren()) {
                if (p.getName().startsWith("jcr:") || p.getName().startsWith("rep:")) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                ValueMap vm = p.getValueMap();
                m.put("id", p.getName());
                m.put("path", p.getPath());
                m.put("title", vm.get("jcr:title", p.getName()));
                m.put("description", vm.get("jcr:description", String.class));
                Map<String, Object> stores = new LinkedHashMap<>();
                // the stores built in, and any store of the configuration's ContextHub a persona fills
                for (Resource s : p.getChildren()) {
                    String store = s.getName();
                    if (store.contains(":")) {
                        continue;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> e : s.getValueMap().entrySet()) {
                        if (!e.getKey().contains(":")) {
                            values.put(e.getKey(), e.getValue() instanceof Object[] ? java.util.Arrays.asList((Object[]) e.getValue()) : e.getValue());
                        }
                    }
                    stores.put(store, values);
                }
                m.put("stores", stores);
                if (out.stream().noneMatch(o -> p.getName().equals(((Map<?, ?>) o).get("id")))) {
                    out.add(m);
                }
            }
        }
        return out;
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
        write(response, status == 0 ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : status, m);
    }
}
