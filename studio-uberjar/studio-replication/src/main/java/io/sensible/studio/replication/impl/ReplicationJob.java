/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentConfig;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;

/**
 * One queued request of one agent: packs the content (with the replication service user, as it is
 * now, not as it was when requested) and delivers it. A failed delivery is retried by the queue
 * (configuration {@code Sensible Studio replication {0}}: one ordered queue per agent); the agent's last error is kept at
 * {@code /var/studio/replication/<agent>} for the console.
 */
@Component(service = JobConsumer.class, property = {
        JobConsumer.PROPERTY_TOPICS + "=" + ReplicationJob.TOPIC,
        JobConsumer.PROPERTY_TOPICS + "=" + ReplicationJob.TOPIC + "/*"})
public class ReplicationJob implements JobConsumer {

    /**
     * The topic of the requests queued before every agent had its own queue; still consumed, so
     * they drain. New requests go to {@link #topicOf(String)}.
     */
    public static final String TOPIC = "studio/replication";

    /**
     * The topic of one agent's requests: each topic gets its own ordered queue (the queue
     * configuration names it with {@code {0}}), so a target that is down holds back only its own
     * agent's requests. An id that cannot be a topic segment keeps the old shared queue.
     */
    public static String topicOf(String agentId) {
        return safeAgentId(agentId) ? TOPIC + "/" + agentId : TOPIC;
    }

    /** The topics an agent's requests can be queued under: its own and the old shared one. */
    public static java.util.List<String> topicsOf(String agentId) {
        String own = topicOf(agentId);
        return TOPIC.equals(own) ? java.util.Collections.singletonList(TOPIC) : java.util.Arrays.asList(own, TOPIC);
    }

    /** An agent id can be one segment of a job topic: letters, digits, {@code _} and {@code -}. */
    public static boolean safeAgentId(String agentId) {
        return agentId != null && agentId.matches("[A-Za-z0-9_-]+");
    }

    /** Whether a job topic is a replication topic (the old shared one or one agent's). */
    public static boolean isReplicationTopic(String topic) {
        return TOPIC.equals(topic) || (topic != null && topic.startsWith(TOPIC + "/"));
    }

    /**
     * Queued requests: of one agent (its own topic, then any left on the old shared one), or of
     * every agent when {@code agentId} is null. {@code limit} below 1 means all; {@code match} may
     * be null.
     */
    public static java.util.List<Job> find(org.apache.sling.event.jobs.JobManager jm, String agentId, Map<String, Object> match, int limit) {
        java.util.List<Job> out = new java.util.ArrayList<>();
        Map<String, Object> template = new HashMap<>();
        if (match != null) {
            template.putAll(match);
        }
        if (agentId != null) {
            template.put(PROP_AGENT, agentId);
            for (String topic : topicsOf(agentId)) {
                for (Job j : jm.findJobs(org.apache.sling.event.jobs.JobManager.QueryType.ALL, topic, limit < 1 ? -1 : limit - out.size(), template)) {
                    out.add(j);
                }
                if (limit > 0 && out.size() >= limit) {
                    return out;
                }
            }
            return out;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object>[] templates = template.isEmpty() ? new Map[0] : new Map[] {template};
        for (Job j : jm.findJobs(org.apache.sling.event.jobs.JobManager.QueryType.ALL, null, -1, templates)) {
            if (isReplicationTopic(j.getTopic())) {
                out.add(j);
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
    public static final String PROP_AGENT = "agent";
    public static final String PROP_ACTION = "action";
    public static final String PROP_PATH = "path";
    public static final String PROP_DEEP = "deep";
    public static final String PROP_USER = "user";
    public static final String PROP_REQUESTED = "requested";
    public static final String LOG_ROOT = "/var/studio/replication";
    /** The agent of a scheduled request: when it fires, the request is replicated to every agent, as if asked then. */
    public static final String ALL_AGENTS = "*";

    private static final Logger LOG = LoggerFactory.getLogger(ReplicationJob.class);

    @Reference
    private AgentManagerImpl agents;

    @Reference
    private ReplicatorImpl replicator;

    @Override
    public JobResult process(Job job) {
        String agentId = job.getProperty(PROP_AGENT, String.class);
        String action = job.getProperty(PROP_ACTION, String.class);
        String path = job.getProperty(PROP_PATH, String.class);
        boolean deep = Boolean.TRUE.equals(job.getProperty(PROP_DEEP, Boolean.class));
        if (ALL_AGENTS.equals(agentId)) {
            return fire(job, action, path, deep);
        }
        try (ResourceResolver rr = agents.serviceResolver()) {
            Agent agent = agents.agents(rr).get(agentId);
            if (agent == null || !agent.isEnabled() || ReverseReplication.REVERSE.equals(agent.getConfiguration().getSerializationType())) {
                LOG.info("Dropping replication of {} for agent {}: agent gone, disabled or reverse", path, agentId);
                return JobResult.CANCEL;
            }
            Session session = rr.adaptTo(Session.class);
            deliver(session, agent.getConfiguration(), action, path, deep);
            record(rr, agentId, action, path, null);
            return JobResult.OK;
        } catch (HttpTransport.Refused e) {
            // A refusal is final: kept in the target's history, taken out of the queue. Left there it was retried
            // for ever and everything published after it waited behind it (27.11.2026: a stylesheet under /apps,
            // which a publish instance does not take, held all publishing back for hours).
            LOG.warn("Replication of {} to {} refused, not tried again: {}", path, agentId, e.getMessage());
            try (ResourceResolver rr = agents.serviceResolver()) {
                record(rr, agentId, action, path, "Refused, not tried again: " + e.getMessage());
            } catch (LoginException | PersistenceException ignored) {
                // the log is best effort
            }
            return JobResult.CANCEL;
        } catch (ReplicationException | IOException | RepositoryException e) {
            LOG.warn("Replication of {} to {} failed (attempt {}): {}", path, agentId, job.getRetryCount() + 1, e.getMessage());
            try (ResourceResolver rr = agents.serviceResolver()) {
                record(rr, agentId, action, path, e.getMessage());
            } catch (LoginException | PersistenceException ignored) {
                // the log is best effort
            }
            return JobResult.FAILED;
        } catch (LoginException e) {
            LOG.error("No replication service user: {}", e.getMessage());
            return JobResult.FAILED;
        }
    }

    /** Packs (activation) and sends one request. */
    /** A scheduled request fires: version, status and one queued request per agent, on behalf of who scheduled it. */
    private JobResult fire(Job job, String action, String path, boolean deep) {
        try (ResourceResolver rr = agents.serviceResolver()) {
            com.day.cq.replication.ReplicationOptions o = new com.day.cq.replication.ReplicationOptions();
            o.setDeep(deep);
            String user = job.getProperty(PROP_USER, String.class);
            replicator.replicateAs(rr.adaptTo(Session.class), com.day.cq.replication.ReplicationActionType.fromName(action), new String[] {path}, o,
                    user == null ? "scheduler" : user);
            return JobResult.OK;
        } catch (ReplicationException e) {
            LOG.warn("Scheduled replication of {} failed: {}", path, e.getMessage());
            return JobResult.CANCEL;
        } catch (LoginException e) {
            LOG.error("No replication service user: {}", e.getMessage());
            return JobResult.FAILED;
        }
    }

    static void deliver(Session session, AgentConfig agent, String action, String path, boolean deep)
            throws ReplicationException, IOException, RepositoryException {
        ReplicationActionType type = ReplicationActionType.fromName(action);
        byte[] body = null;
        if (type == ReplicationActionType.ACTIVATE && !HttpTransport.sendsNoContent(agent.getSerializationType())) {
            if (!session.nodeExists(path)) {
                // removed since the request: nothing to send; the target keeps what it has
                LOG.info("Not activating {}: it no longer exists", path);
                return;
            }
            if (AuthorizablePack.isAuthorizablePath(path)) {
                // a user or a group: packed for the receiving repository's user management
                javax.jcr.Node unit = AuthorizablePack.unitOf(session, path);
                if (unit == null) {
                    throw new ReplicationException(path + " is not a user or group that is published");
                }
                path = unit.getPath();
                body = AuthorizablePack.pack(session, unit);
            } else {
                body = ContentPack.pack(session, path, action, deep);
            }
        }
        HttpTransport.deliver(agent, action, path, body);
    }

    /** The agent's last delivery (and its error, if any) for the console. */
    static void record(ResourceResolver rr, String agentId, String action, String path, String error) throws PersistenceException {
        Resource r = ResourceUtil.getOrCreateResource(rr, LOG_ROOT + "/" + agentId, "nt:unstructured", "sling:Folder", false);
        ModifiableValueMap m = r.adaptTo(ModifiableValueMap.class);
        if (m == null) {
            return;
        }
        m.put("lastAction", action);
        m.put("lastPath", path);
        m.put("lastAt", Calendar.getInstance());
        if (error == null) {
            m.remove("lastError");
            m.put("lastSuccessAt", Calendar.getInstance());
        } else {
            m.put("lastError", error);
        }
        history(rr, r, action, path, error);
        rr.commit();
    }

    /** How many deliveries an agent's history keeps. */
    static final int HISTORY_SIZE = 500;
    static final String HISTORY = "history";

    /** One line of the agent's history: what went, when, and whether it got there. */
    private static void history(ResourceResolver rr, Resource agentLog, String action, String path, String error) throws PersistenceException {
        Resource h = agentLog.getChild(HISTORY);
        if (h == null) {
            java.util.Map<String, Object> folder = new java.util.HashMap<>();
            folder.put("jcr:primaryType", "nt:unstructured");
            h = rr.create(agentLog, HISTORY, folder);
        }
        Calendar now = Calendar.getInstance();
        String name = Long.toString(now.getTimeInMillis(), 36);
        for (int i = 0; h.getChild(name) != null; i++) {
            name = Long.toString(now.getTimeInMillis(), 36) + "-" + i;
        }
        java.util.Map<String, Object> e = new java.util.HashMap<>();
        e.put("jcr:primaryType", "nt:unstructured");
        e.put("action", action);
        e.put("path", path);
        e.put("at", now);
        if (error != null) {
            e.put("error", error);
        }
        rr.create(h, name, e);
        // the oldest go: names sort by time
        java.util.List<Resource> all = new java.util.ArrayList<>();
        h.getChildren().forEach(all::add);
        if (all.size() > HISTORY_SIZE) {
            all.sort(java.util.Comparator.comparing(Resource::getName, ReplicationJob::byTime));
            for (int i = 0; i < all.size() - HISTORY_SIZE; i++) {
                rr.delete(all.get(i));
            }
        }
    }

    /** base-36 millis, with a "-n" suffix for the same millisecond: older first. */
    static int byTime(String a, String b) {
        String[] x = a.split("-", 2);
        String[] y = b.split("-", 2);
        int c = Long.compare(Long.parseLong(x[0], 36), Long.parseLong(y[0], 36));
        if (c != 0) {
            return c;
        }
        return Integer.compare(x.length > 1 ? Integer.parseInt(x[1]) : -1, y.length > 1 ? Integer.parseInt(y[1]) : -1);
    }

    /** The agent's history, newest first: action, path, at, error. */
    static java.util.List<java.util.Map<String, Object>> history(ResourceResolver rr, String agentId, int limit) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        Resource h = rr.getResource(LOG_ROOT + "/" + agentId + "/" + HISTORY);
        if (h == null) {
            return out;
        }
        java.util.List<Resource> all = new java.util.ArrayList<>();
        h.getChildren().forEach(all::add);
        all.sort(java.util.Comparator.comparing(Resource::getName, ReplicationJob::byTime).reversed());
        for (Resource e : all.subList(0, Math.min(limit, all.size()))) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", e.getName());
            m.put("action", e.getValueMap().get("action", String.class));
            m.put("path", e.getValueMap().get("path", String.class));
            Calendar at = e.getValueMap().get("at", Calendar.class);
            m.put("at", at == null ? null : at.toInstant().toString());
            m.put("error", e.getValueMap().get("error", String.class));
            out.add(m);
        }
        return out;
    }
}
