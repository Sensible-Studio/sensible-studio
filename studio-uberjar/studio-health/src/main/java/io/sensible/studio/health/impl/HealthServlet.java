/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * {@code /bin/studio/health.json}, for signed-in users:
 * <ul>
 * <li>GET {@code op=status}: the checks (disk, memory, bundles, jobs, log, maintenance, repository), the
 *     maintenance settings, the run in progress, the last run and when the next one is due</li>
 * <li>GET {@code op=runs}: the maintenance history, newest first</li>
 * <li>GET {@code op=progress}</li>
 * <li>POST {@code op=run&kind=all|compact|versions}: start a run now (202, or 409 when one is going);
 *     {@code op=settings&enabled=&hour=&compact=&purgeVersions=&keepVersions=&keepDays=}. POSTs need
 *     {@code X-Studio-CSRF: 1}.</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/health",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class HealthServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String CSRF_HEADER = "X-Studio-CSRF";

    @Reference
    private transient Maintenance maintenance;

    @Reference
    private transient SlingSettingsService slingSettings;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile transient JobManager jobManager;

    private transient BundleContext context;

    @Activate
    void activate(BundleContext ctx) {
        this.context = ctx;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (anonymous(request)) {
            send(response, 401, error("sign in"));
            return;
        }
        String op = request.getParameter("op");
        try {
            if ("status".equals(op)) {
                send(response, 200, status());
            } else if ("runs".equals(op)) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("runs", maintenance.runs(60));
                send(response, 200, out);
            } else if ("progress".equals(op)) {
                send(response, 200, maintenance.progress());
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
        // Maintenance compacts the repository and removes versions, and its settings decide when: an
        // administrator's to start or change. (Until 27.11.2026 any signed-in user could.)
        if (!JobsServlet.isAdmin(request.getResourceResolver())) {
            send(response, 403, error("Only administrators run maintenance or change its settings"));
            return;
        }
        try {
            if ("run".equals(op)) {
                String kind = request.getParameter("kind");
                if (kind == null || kind.isEmpty()) {
                    kind = "all";
                }
                if (!"all".equals(kind) && !"compact".equals(kind) && !"versions".equals(kind)) {
                    send(response, 400, error("kind is all, compact or versions"));
                    return;
                }
                boolean started = maintenance.start(kind, request.getResourceResolver().getUserID());
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("started", started);
                out.put("progress", maintenance.progress());
                send(response, started ? 202 : 409, out);
            } else if ("settings".equals(op)) {
                Map<String, String> values = new LinkedHashMap<>();
                for (String k : new String[] { "enabled", "hour", "compact", "purgeVersions", "keepVersions", "keepDays" }) {
                    String v = request.getParameter(k);
                    if (v != null && !v.isEmpty()) {
                        values.put(k, v);
                    }
                }
                send(response, 200, maintenance.saveSettings(values).toJson());
            } else {
                send(response, 400, error("unknown op"));
            }
        } catch (IllegalArgumentException e) {
            send(response, 400, error(e.getMessage()));
        } catch (Exception e) {
            send(response, 500, error(e.getMessage()));
        }
    }

    private Map<String, Object> status() throws Exception {
        Maintenance.Settings settings = maintenance.settings();
        Map<String, Object> last = maintenance.lastRun();
        String next = maintenance.nextRun(settings);
        File repository = maintenance.repositoryDir();
        long size = maintenance.repositorySize();
        List<Object> checks = new ArrayList<>();
        checks.add(Checks.maintenance(last, settings, next));
        checks.add(Checks.repository(size, maintenance.sizeSamples()));
        checks.add(Checks.disk(repository));
        checks.add(Checks.memory());
        checks.add(Checks.bundles(context));
        checks.add(Checks.jobs(jobManager));
        checks.add(Checks.log(new File(slingSettings.getSlingHomePath(), "logs/error.log")));
        String overall = Checks.OK;
        for (Object c : checks) {
            String s = String.valueOf(((Map<?, ?>) c).get("status"));
            if (Checks.ERROR.equals(s)) {
                overall = Checks.ERROR;
            } else if (Checks.WARN.equals(s) && !Checks.ERROR.equals(overall)) {
                overall = Checks.WARN;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", overall);
        out.put("checks", checks);
        out.put("settings", settings.toJson());
        out.put("progress", maintenance.progress());
        out.put("lastRun", last);
        out.put("nextRun", next);
        out.put("compactionAvailable", maintenance.gc().available());
        out.put("now", Checks.isoNow());
        return out;
    }

    private static boolean anonymous(SlingHttpServletRequest request) {
        String user = request.getResourceResolver().getUserID();
        return user == null || "anonymous".equals(user);
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
