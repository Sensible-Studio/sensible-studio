/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.Queue;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

/**
 * {@code /bin/studio/jobs.json}, the Jobs console (AEM's Tools › General › Jobs), for signed-in users:
 * <ul>
 * <li>GET {@code op=list&state=waiting|running|failed|done|all&limit=}: the studio jobs, newest first, and the counts</li>
 * <li>GET {@code op=queues}: the studio queues, how they run and what they are doing</li>
 * <li>POST (admin) {@code op=retry&id=}, {@code op=remove&id=}, {@code op=suspend|resume&queue=},
 *     {@code op=configure&pid=&maxParallel=&retries=&retryDelay=}. POSTs need {@code X-Studio-CSRF: 1}.</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/jobs",
                "sling.servlet.extensions=json",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class JobsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile transient JobManager jobManager;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile transient ConfigurationAdmin configAdmin;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (anonymous(request)) {
            HealthServlet.send(response, 401, HealthServlet.error("sign in"));
            return;
        }
        JobManager jm = jobManager;
        if (jm == null) {
            HealthServlet.send(response, 503, HealthServlet.error("The job manager is not running"));
            return;
        }
        String op = request.getParameter("op");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("list".equals(op)) {
                out.put("jobs", StudioJobs.list(jm, StudioJobs.State.of(request.getParameter("state")), limit(request)));
                out.put("counts", StudioJobs.counts(jm));
                out.put("admin", isAdmin(request.getResourceResolver()));
            } else if ("queues".equals(op)) {
                out.put("queues", StudioJobs.queues(jm, configAdmin));
                out.put("admin", isAdmin(request.getResourceResolver()));
            } else {
                HealthServlet.send(response, 400, HealthServlet.error("unknown op"));
                return;
            }
            HealthServlet.send(response, 200, out);
        } catch (IllegalArgumentException e) {
            HealthServlet.send(response, 400, HealthServlet.error(e.getMessage()));
        } catch (Exception e) {
            HealthServlet.send(response, 500, HealthServlet.error(e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (anonymous(request)) {
            HealthServlet.send(response, 401, HealthServlet.error("sign in"));
            return;
        }
        if (!"1".equals(request.getHeader(HealthServlet.CSRF_HEADER))) {
            HealthServlet.send(response, 403, HealthServlet.error("missing " + HealthServlet.CSRF_HEADER + " header"));
            return;
        }
        if (!isAdmin(request.getResourceResolver())) {
            HealthServlet.send(response, 403, HealthServlet.error("Only administrators change jobs and queues"));
            return;
        }
        JobManager jm = jobManager;
        String op = request.getParameter("op");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            if ("retry".equals(op) || "remove".equals(op)) {
                String id = request.getParameter("id");
                Job job = jm == null || id == null ? null : jm.getJobById(id);
                if (job == null || job.getTopic() == null || !job.getTopic().startsWith(StudioJobs.PREFIX)) {
                    HealthServlet.send(response, 404, HealthServlet.error("No such job"));
                    return;
                }
                if ("retry".equals(op)) {
                    out.put("job", jm.retryJobById(id) == null ? null : id);
                    if (out.get("job") == null) {
                        HealthServlet.send(response, 409, HealthServlet.error("Only a failed job is tried again"));
                        return;
                    }
                } else {
                    // a waiting or running job stops; a finished one leaves the history
                    out.put("removed", jm.removeJobById(id));
                }
            } else if ("suspend".equals(op) || "resume".equals(op)) {
                String name = request.getParameter("queue");
                Queue q = jm == null || name == null ? null : jm.getQueue(name);
                if (q == null || !name.startsWith("Sensible Studio")) {
                    HealthServlet.send(response, 404, HealthServlet.error("No such queue"));
                    return;
                }
                if ("suspend".equals(op)) {
                    q.suspend();
                } else {
                    q.resume();
                }
                out.put("queue", name);
                out.put("suspended", q.isSuspended());
            } else if ("configure".equals(op)) {
                out = StudioJobs.configure(configAdmin, request.getParameter("pid"), integer(request, "maxParallel"),
                        integer(request, "retries"), longer(request, "retryDelay"));
            } else {
                HealthServlet.send(response, 400, HealthServlet.error("unknown op"));
                return;
            }
            HealthServlet.send(response, 200, out);
        } catch (IllegalArgumentException e) {
            HealthServlet.send(response, 400, HealthServlet.error(e.getMessage()));
        } catch (Exception e) {
            HealthServlet.send(response, 500, HealthServlet.error(e.getMessage()));
        }
    }

    private static Integer integer(SlingHttpServletRequest r, String name) {
        String v = r.getParameter(name);
        try {
            return v == null || v.isEmpty() ? null : Integer.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is a number");
        }
    }

    private static Long longer(SlingHttpServletRequest r, String name) {
        String v = r.getParameter(name);
        try {
            return v == null || v.isEmpty() ? null : Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is a number");
        }
    }

    private static int limit(SlingHttpServletRequest r) {
        try {
            return Math.max(1, Math.min(500, Integer.parseInt(String.valueOf(r.getParameter("limit")))));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static boolean anonymous(SlingHttpServletRequest request) {
        String user = request.getResourceResolver().getUserID();
        return user == null || "anonymous".equals(user);
    }

    /** admin, or whoever has every right on the repository's root (administrators). */
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
            return acm.hasPrivileges("/", new Privilege[] {acm.privilegeFromName(Privilege.JCR_ALL)});
        } catch (RepositoryException | RuntimeException e) {
            return false;
        }
    }
}
