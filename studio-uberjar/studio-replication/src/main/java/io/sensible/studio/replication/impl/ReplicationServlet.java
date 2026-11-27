/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.ReplicationStatus;

/**
 * {@code /bin/studio/replication.json}, what the editor, the Sites console and the Replication console use:
 * <ul>
 *   <li>{@code GET op=agents}: the agents, with queue size, blocked state and last delivery;</li>
 *   <li>{@code GET op=queue&agent=}: the queued requests of an agent;</li>
 *   <li>{@code GET op=history&agent=&limit=}: the agent's last deliveries (action, path, at, error), newest first;</li>
 *   <li>{@code GET op=status&path=…}: the replication status of paths;</li>
 *   <li>{@code GET op=references&path=}: content the page uses that is not published yet (assets,
 *       fragments, templates' policies), for the Publish dialog;</li>
 *   <li>{@code POST op=activate|deactivate&path=…[&deep=true][&agent=…][&at=<ISO date or epoch ms>]}: {@code at} schedules
 *       the request for later (AEM's Publish Later / Unpublish Later); {@code POST op=unschedule&id=} drops one;</li>
 *   <li>{@code POST op=test&agent=}: sends a TEST to the agent and reports the answer;</li>
 *   <li>{@code POST op=agent&id=&title=&enabled=&transportUri=&transportUser=&transportPassword=}: creates or
 *       changes an agent; {@code POST op=removeAgent&id=};</li>
 *   <li>{@code GET op=cache[&local=true]}: the caches in front of the published site ({@link LiveCache});
 *       {@code POST op=clearCache&cache=<flush target>[&via=<agent of the publish that reported it>]} empties one;</li>
 *   <li>{@code POST op=clear&agent=}: drops the agent's queue; {@code op=retry&agent=}: re-queues the blocked job now.</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/replication",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class ReplicationServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private ReplicatorImpl replicator;

    @Reference
    private AgentManagerImpl agents;

    @Reference
    private ReverseReplication reverse;

    @Reference
    private JobManager jobManager;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        ResourceResolver rr = request.getResourceResolver();
        Session session = rr.adaptTo(Session.class);
        Object out;
        try {
            if ("agents".equals(op)) {
                List<Map<String, Object>> list = agentList();
                if (!mayClearCache(session)) {
                    // an author needs the targets' names for the Publish dialog, not where they deliver or as whom
                    for (Map<String, Object> a : list) {
                        a.keySet().removeAll(java.util.Arrays.asList("transportUri", "transportUser", "lastError", "lastPath"));
                    }
                }
                out = Collections.singletonMap("agents", list);
            } else if ("queue".equals(op)) {
                out = Collections.singletonMap("jobs", queue(session, request.getParameter("agent")));
            } else if ("status".equals(op)) {
                List<Map<String, Object>> list = new ArrayList<>();
                String[] paths = request.getParameterValues("path");
                for (String p : paths == null ? new String[0] : paths) {
                    list.add(status(session, p));
                }
                out = Collections.singletonMap("status", list);
            } else if ("history".equals(op)) {
                String limit = request.getParameter("limit");
                try (ResourceResolver service = agents.serviceResolver()) {
                    out = Collections.singletonMap("history", ReplicationJob.history(service, request.getParameter("agent"),
                            limit == null ? 100 : Integer.parseInt(limit)));
                } catch (org.apache.sling.api.resource.LoginException e) {
                    out = Collections.singletonMap("history", new ArrayList<>());
                }
            } else if ("cache".equals(op)) {
                // the caches in front of the published site: this instance's, and (unless local) those of the publish instances it delivers to
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caches", LiveCache.list(agents.getAgents().values(), !"true".equals(request.getParameter("local"))));
                m.put("canClear", mayClearCache(session));
                out = m;
            } else if ("explainUrl".equals(op)) {
                // the URL tester: what one cache would do with a visitor's request (cache=<flush target>[&via=<publish agent>])
                String url = request.getParameter("url");
                if (url == null || url.isEmpty()) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "url is required");
                    return;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                try {
                    m.put("explain", LiveCache.explain(agents.getAgents().values(), request.getParameter("cache"), request.getParameter("via"),
                            url, request.getParameter("method"), request.getParameter("host")));
                    m.put("ok", true);
                } catch (ReplicationException e) {
                    m.put("ok", false);
                    m.put("message", e.getMessage());
                }
                out = m;
            } else if ("references".equals(op)) {
                String tier;
                try {
                    tier = Tiers.of(request.getParameter("tier"));
                } catch (IllegalArgumentException e) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                    return;
                }
                out = Collections.singletonMap("references", References.unpublished(rr, session, replicator, request.getParameter("path"), tier));
            } else {
                error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
                return;
            }
        } catch (RepositoryException e) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }
        send(response, out);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        ResourceResolver rr = request.getResourceResolver();
        Session session = rr.adaptTo(Session.class);
        try {
            // The targets and their queues belong to whoever runs publishing. These operations work through
            // services (the job manager, the transport), so the repository does not judge them: we do.
            // (Until 27.11.2026 any signed-in user could empty a queue.)
            if (MANAGING.contains(op) && !mayClearCache(session)) {
                error(response, HttpServletResponse.SC_FORBIDDEN, "Only those who run publishing manage its targets and queues");
                return;
            }
            if ("activate".equals(op) || "deactivate".equals(op)) {
                String[] paths = request.getParameterValues("path");
                if (paths == null || paths.length == 0) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "path is required");
                    return;
                }
                ReplicationOptions o = new ReplicationOptions();
                o.setDeep("true".equals(request.getParameter("deep")));
                String agent = request.getParameter("agent");
                // tier=preview: AEM as a Cloud Service's Publish to Preview, to the preview tier's agents only
                String tier;
                try {
                    tier = Tiers.of(request.getParameter("tier"));
                } catch (IllegalArgumentException e) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                    return;
                }
                if (agent != null && !agent.isEmpty()) {
                    o.setFilter(a -> a.isEnabled() && a.getId().equals(agent));
                } else if (tier != null) {
                    if (!replicator.hasPreview()) {
                        error(response, HttpServletResponse.SC_BAD_REQUEST, "No preview tier: add an agent with the tier Preview in Publishing");
                        return;
                    }
                    o.setFilter(Tiers.filter(tier));
                }
                ReplicationActionType type = "activate".equals(op) ? ReplicationActionType.ACTIVATE : ReplicationActionType.DEACTIVATE;
                Calendar at = parseAt(request.getParameter("at"));
                if (at != null && tier != null) {
                    error(response, HttpServletResponse.SC_BAD_REQUEST, "The preview tier gets content now, not later");
                    return;
                }
                if (at != null) {
                    schedule(session, type, paths, o.isDeep(), at);
                } else {
                    replicator.replicate(session, type, paths, o);
                }
                List<Map<String, Object>> list = new ArrayList<>();
                for (String p : paths) {
                    list.add(status(session, p));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", list);
                out.put("agents", agentList());
                send(response, out);
            } else if ("test".equals(op)) {
                Agent a = agents.getAgent(request.getParameter("agent"));
                if (a == null) {
                    error(response, HttpServletResponse.SC_NOT_FOUND, "No such agent");
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                try {
                    if (ReverseReplication.REVERSE.equals(a.getConfiguration().getSerializationType())) {
                        // a reverse agent reads an outbox: the test is the listing
                        int n = ReverseReplication.list(a.getConfiguration()).size();
                        out.put("ok", true);
                        out.put("message", "OK, " + n + " waiting in the outbox");
                        out.put("waiting", n);
                    } else {
                        String answer = HttpTransport.deliver(a.getConfiguration(), ReplicationActionType.TEST.getName(), "/", null);
                        out.put("ok", true);
                        out.put("message", answer);
                    }
                } catch (ReplicationException e) {
                    out.put("ok", false);
                    out.put("message", e.getMessage());
                }
                send(response, out);
            } else if ("clearCache".equals(op)) {
                if (!mayClearCache(session)) {
                    error(response, HttpServletResponse.SC_FORBIDDEN, "Not allowed to empty the cache");
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                try {
                    out.put("message", LiveCache.clear(agents.getAgents().values(), request.getParameter("cache"), request.getParameter("via")));
                    out.put("ok", true);
                } catch (ReplicationException e) {
                    out.put("ok", false);
                    out.put("message", e.getMessage());
                }
                out.put("caches", LiveCache.list(agents.getAgents().values(), !"true".equals(request.getParameter("local"))));
                send(response, out);
            } else if ("copyUsers".equals(op)) {
                // the first copy of the users to a new publish instance: from=<reverse agent>&to=<content agent>
                if (!mayClearCache(session)) {
                    error(response, HttpServletResponse.SC_FORBIDDEN, "Not allowed to copy users between instances");
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>(reverse.copyAuthorizables(request.getParameter("from"), request.getParameter("to")));
                out.put("ok", ((Number) out.get("failed")).intValue() == 0);
                send(response, out);
            } else if ("poll".equals(op)) {
                // a reverse agent: fetch its outbox now
                Map<String, Object> out = new LinkedHashMap<>();
                try {
                    out.putAll(reverse.fetchNow(request.getParameter("agent")));
                    out.put("ok", true);
                } catch (ReplicationException e) {
                    out.put("ok", false);
                    out.put("message", e.getMessage());
                }
                out.put("agents", agentList());
                send(response, out);
            } else if ("agent".equals(op)) {
                saveAgent(request);
                send(response, Collections.singletonMap("agents", agentList()));
            } else if ("removeAgent".equals(op)) {
                Resource r = rr.getResource(agents.agentsRoot() + "/" + request.getParameter("id"));
                if (r != null) {
                    rr.delete(r);
                    rr.commit();
                }
                send(response, Collections.singletonMap("agents", agentList()));
            } else if ("clear".equals(op)) {
                for (Job j : jobs(request.getParameter("agent"))) {
                    jobManager.removeJobById(j.getId());
                }
                send(response, Collections.singletonMap("agents", agentList()));
            } else if ("removeJob".equals(op)) {
                // one request out of the queue: a request that can never succeed (a path the target refuses) otherwise
                // holds back everything queued behind it, and clearing the queue loses the rest
                String id = request.getParameter("id");
                boolean found = false;
                for (Job j : jobs(null)) {
                    if (j.getId().equals(id)) {
                        found = jobManager.removeJobById(j.getId());
                        break;
                    }
                }
                if (!found) {
                    error(response, HttpServletResponse.SC_NOT_FOUND, "No such request in the queue");
                    return;
                }
                send(response, Collections.singletonMap("jobs", queue(session, request.getParameter("agent"))));
            } else if ("unschedule".equals(op)) {
                try (ResourceResolver srr = agents.serviceResolver()) {
                    Session service = srr.adaptTo(Session.class);
                    // a waiting request is cancelled by someone who may change what it would publish
                    String id = request.getParameter("id");
                    for (ScheduledReplication.Entry e : ScheduledReplication.list(service, null)) {
                        if (e.id.equals(id) && !mayClearCache(session)
                                && !(session.nodeExists(e.path) && session.hasPermission(e.path, Session.ACTION_SET_PROPERTY))) {
                            error(response, HttpServletResponse.SC_FORBIDDEN, "Not allowed to cancel the request for " + e.path);
                            return;
                        }
                    }
                    if (ScheduledReplication.remove(service, request.getParameter("id"))) {
                        service.save();
                    }
                } catch (org.apache.sling.api.resource.LoginException e) {
                    error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No replication service user");
                    return;
                }
                send(response, Collections.singletonMap("jobs", queue(session, request.getParameter("agent"))));
            } else if ("retry".equals(op)) {
                // Sling retries on its own schedule; a retry now re-adds the queued requests in order
                List<Job> old = jobs(request.getParameter("agent"));
                for (Job j : old) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    for (String n : j.getPropertyNames()) {
                        if (!n.startsWith("slingevent:") && !n.startsWith("event.job.") && !n.startsWith(":")) {
                            props.put(n, j.getProperty(n));
                        }
                    }
                    jobManager.removeJobById(j.getId());
                    jobManager.addJob(ReplicationJob.topicOf((String) props.get(ReplicationJob.PROP_AGENT)), props);
                }
                send(response, Collections.singletonMap("agents", agentList()));
            } else {
                error(response, HttpServletResponse.SC_NOT_FOUND, "Unknown op");
            }
        } catch (ReplicationException e) {
            error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (RepositoryException | PersistenceException e) {
            error(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private void saveAgent(SlingHttpServletRequest request) throws PersistenceException, ReplicationException {
        String id = request.getParameter("id");
        if (id == null || !id.matches("[a-zA-Z0-9_-]+")) {
            throw new ReplicationException("The agent id must be letters, digits, - or _");
        }
        ResourceResolver rr = request.getResourceResolver();
        Resource page = ResourceUtil.getOrCreateResource(rr, agents.agentsRoot() + "/" + id, "cq:Page", "sling:Folder", false);
        Resource content = page.getChild("jcr:content");
        if (content == null) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("jcr:primaryType", "cq:PageContent");
            props.put("sling:resourceType", "cq/replication/components/agent");
            content = rr.create(page, "jcr:content", props);
        }
        ModifiableValueMap m = content.adaptTo(ModifiableValueMap.class);
        put(m, AgentConfig.AGENT_NAME, request.getParameter("title"));
        put(m, AgentConfig.AGENT_DESCRIPTION, request.getParameter("description"));
        put(m, AgentConfig.TRANSPORT_URI, request.getParameter("transportUri"));
        put(m, AgentConfig.TRANSPORT_USER, request.getParameter("transportUser"));
        String tier = request.getParameter("tier");
        if (tier != null) {
            // publish (nothing) or preview: see Tiers
            put(m, Tiers.PN_TIER, Tiers.PREVIEW.equals(tier) ? Tiers.PREVIEW : "");
        }
        put(m, Tiers.PN_PUBLIC_URL, request.getParameter("publicUrl"));
        String kind = request.getParameter("serializationType");
        if (kind != null && !kind.isEmpty()) {
            m.put(AgentConfig.SERIALIZATION_TYPE, HttpTransport.FLUSH.equals(kind) ? HttpTransport.FLUSH : HttpTransport.WEBHOOK.equals(kind) ? HttpTransport.WEBHOOK : ReverseReplication.REVERSE.equals(kind) ? ReverseReplication.REVERSE : "studio");
        }
        String pw = request.getParameter("transportPassword");
        if (pw != null && !pw.isEmpty()) {
            m.put(AgentConfig.TRANSPORT_PASSWORD, pw);
        }
        if (request.getParameter("enabled") != null) {
            m.put(AgentConfig.AGENT_ENABLED, "true".equals(request.getParameter("enabled")));
        }
        if (request.getParameter("retryDelay") != null) {
            try {
                m.put(AgentConfig.RETRY_DELAY, Long.parseLong(request.getParameter("retryDelay")));
            } catch (NumberFormatException ignored) {
                // keep the old value
            }
        }
        m.put("cq:lastModified", Calendar.getInstance());
        m.put("cq:lastModifiedBy", rr.getUserID());
        rr.commit();
    }

    private static void put(ModifiableValueMap m, String name, String value) {
        if (value != null) {
            if (value.isEmpty()) {
                m.remove(name);
            } else {
                m.put(name, value);
            }
        }
    }

    // ------------------------------------------------------------------ views

    /**
     * Emptying a cache is for whoever runs publishing: on an author its administrators, on a
     * publish also the transport user an author's request arrives as. Both may write the
     * publishing log; authors may not.
     */
    /** What only those who run publishing may do. */
    private static final java.util.Set<String> MANAGING = new java.util.HashSet<>(
            java.util.Arrays.asList("test", "poll", "agent", "removeAgent", "clear", "retry", "removeJob"));

    private static boolean mayClearCache(Session session) {
        try {
            return session != null && session.hasPermission(ReplicationJob.LOG_ROOT, Session.ACTION_SET_PROPERTY);
        } catch (RepositoryException e) {
            return false;
        }
    }

    private List<Map<String, Object>> agentList() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResourceResolver rr = agents.serviceResolver()) {
            for (Agent a : agents.agents(rr).values()) {
                AgentConfig c = a.getConfiguration();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.getId());
                m.put("title", c.getName());
                m.put("description", c.getDescription());
                m.put("enabled", a.isEnabled());
                m.put("valid", a.isValid());
                m.put("transportUri", c.getTransportURI());
                m.put("transportUser", c.getTransportUser());
                m.put("retryDelay", c.getRetryDelay());
                m.put("serializationType", c.getSerializationType());
                m.put("tier", Tiers.isPreview(a) ? Tiers.PREVIEW : Tiers.PUBLISH);
                m.put("publicUrl", Tiers.publicUrl(a));
                m.put("queue", a.getQueueSize());
                m.put("blocked", a.isBlocked());
                Resource log = rr.getResource(ReplicationJob.LOG_ROOT + "/" + a.getId());
                if (log != null) {
                    ValueMap v = log.getValueMap();
                    m.put("lastAction", v.get("lastAction", String.class));
                    m.put("lastPath", v.get("lastPath", String.class));
                    m.put("lastAt", iso(v.get("lastAt", Calendar.class)));
                    m.put("lastSuccessAt", iso(v.get("lastSuccessAt", Calendar.class)));
                    m.put("lastError", v.get("lastError", String.class));
                    if (ReverseReplication.REVERSE.equals(c.getSerializationType())) {
                        m.put("outboxWaiting", v.get("outboxWaiting", Integer.class));
                    }
                }
                out.add(m);
            }
        } catch (org.apache.sling.api.resource.LoginException e) {
            // no service user: no agents
        }
        return out;
    }

    private List<Job> jobs(String agent) {
        if (agent == null || agent.isEmpty()) {
            return ReplicationJob.find(jobManager, null, null, -1);
        }
        return ReplicationJob.find(jobManager, agent, null, -1);
    }

    /** {@code at}: an ISO 8601 date-time or epoch milliseconds; null when absent. */
    static Calendar parseAt(String at) {
        if (at == null || at.isEmpty()) {
            return null;
        }
        Calendar c = Calendar.getInstance();
        if (at.matches("\\d{10,}")) {
            c.setTimeInMillis(Long.parseLong(at));
            return c;
        }
        c.setTime(java.util.Date.from(java.time.OffsetDateTime.parse(at.length() == 16 ? at + ":00Z" : at).toInstant()));
        return c;
    }

    /** One waiting request per path (see {@link ScheduledReplication}); it replicates to every agent when its time comes. */
    private void schedule(Session session, ReplicationActionType type, String[] paths, boolean deep, Calendar at) throws ReplicationException, RepositoryException {
        if (!at.after(Calendar.getInstance())) {
            throw new ReplicationException("The time has already passed");
        }
        for (String path : paths) {
            replicator.checkPermission(session, type, path);
        }
        // kept under /var by the replication service user: authors need no rights there
        try (ResourceResolver rr = agents.serviceResolver()) {
            Session service = rr.adaptTo(Session.class);
            for (String path : paths) {
                ScheduledReplication.add(service, type, path, deep, at, session.getUserID());
            }
            service.save();
        } catch (org.apache.sling.api.resource.LoginException e) {
            throw new ReplicationException("No replication service user", e);
        }
    }

    /** The waiting Publish Later / Unpublish Later requests ({@code path} null: all). */
    private List<Map<String, Object>> scheduled(Session session, String path) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResourceResolver rr = agents.serviceResolver()) {
            for (ScheduledReplication.Entry e : ScheduledReplication.list(rr.adaptTo(Session.class), path)) {
                out.add(e.toJson());
            }
        } catch (org.apache.sling.api.resource.LoginException e) {
            // no service user: nothing scheduled to show
        }
        return out;
    }

    private List<Map<String, Object>> queue(Session session, String agent) throws RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>(scheduled(session, null));
        for (Job j : jobs(agent)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId());
            m.put("agent", j.getProperty(ReplicationJob.PROP_AGENT, String.class));
            m.put("action", j.getProperty(ReplicationJob.PROP_ACTION, String.class));
            m.put("path", j.getProperty(ReplicationJob.PROP_PATH, String.class));
            m.put("deep", j.getProperty(ReplicationJob.PROP_DEEP, Boolean.class));
            m.put("user", j.getProperty(ReplicationJob.PROP_USER, String.class));
            m.put("requested", iso(j.getProperty(ReplicationJob.PROP_REQUESTED, Calendar.class)));
            m.put("retries", j.getRetryCount());
            m.put("state", String.valueOf(j.getJobState()));
            m.put("error", j.getResultMessage());
            out.add(m);
        }
        return out;
    }

    Map<String, Object> status(Session session, String path) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        List<Map<String, Object>> later = scheduled(session, path);
        if (!later.isEmpty()) {
            m.put("scheduled", later.get(0).get("scheduled"));
            m.put("scheduledAction", later.get(0).get("action"));
            m.put("scheduledId", later.get(0).get("id"));
        }
        if (replicator.hasPreview()) {
            m.put("preview", previewStatus(session, path));
        }
        ReplicationStatus s = replicator.getReplicationStatus(session, path);
        if (s == null) {
            m.put("published", false);
            m.put("pending", false);
            return m;
        }
        m.put("lastReplicated", iso(s.getLastPublished()));
        m.put("lastReplicatedBy", s.getLastPublishedBy());
        m.put("lastReplicationAction", s.getLastReplicationAction() == null ? null : s.getLastReplicationAction().getName());
        m.put("activated", s.isActivated());
        m.put("published", s.isDelivered());
        m.put("pending", s.isPending());
        return m;
    }

    /** The preview tier's status of {@code path}, and where it is seen there. */
    private Map<String, Object> previewStatus(Session session, String path) throws RepositoryException {
        Map<String, Object> p = new LinkedHashMap<>();
        ReplicationStatus s = replicator.getReplicationStatus(session, path, Tiers.PREVIEW);
        if (s != null) {
            p.put("lastReplicated", iso(s.getLastPublished()));
            p.put("lastReplicatedBy", s.getLastPublishedBy());
            p.put("lastReplicationAction", s.getLastReplicationAction() == null ? null : s.getLastReplicationAction().getName());
            p.put("activated", s.isActivated());
            p.put("published", s.isDelivered());
            p.put("pending", s.isPending());
        } else {
            p.put("published", false);
            p.put("pending", false);
        }
        for (Agent a : agents.getAgents().values()) {
            if (a.isEnabled() && Tiers.isPreview(a) && Tiers.publicUrl(a) != null) {
                boolean page = session.nodeExists(path) && session.getNode(path).isNodeType("cq:Page");
                p.put("url", Tiers.publicUrl(a) + path + (page ? ".html" : ""));
                break;
            }
        }
        return p;
    }

    static String iso(Calendar c) {
        if (c == null) {
            return null;
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
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
