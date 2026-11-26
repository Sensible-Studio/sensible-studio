/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.Queue;
import org.apache.sling.event.jobs.QueueConfiguration;
import org.apache.sling.event.jobs.Statistics;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/**
 * AEM's Jobs (Tools &gt; General &gt; Jobs, the asynchronous jobs) and their configuration, for Sensible Studio's own
 * work in the background: media processing, auto tags, smart crops, publishing, rollouts, flows, translations. Each
 * runs as a Sling job of a {@code studio/…} topic; the list shows them waiting, running, failed or done, and an admin
 * retries or removes one. The configuration is the queues': how many run at once, how often a failed one is tried
 * again and after how long, each queue an OSGi configuration ({@code QueueConfiguration~studio-…}) changed in place.
 */
final class StudioJobs {

    static final String PREFIX = "studio/";
    static final String QUEUE_FACTORY = "org.apache.sling.event.jobs.QueueConfiguration";
    /** Ours: the queue configurations Sensible Studio ships ({@code QueueConfiguration~studio-dam} …). */
    static final String OURS = "~studio-";

    private StudioJobs() {
    }

    /** waiting, running, failed (given up or failed and waiting to be retried), done, or all of them. */
    enum State {
        WAITING, RUNNING, FAILED, DONE, ALL;

        static State of(String s) {
            if (s == null || s.isEmpty()) {
                return ALL;
            }
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("A state is waiting, running, failed, done or all");
            }
        }
    }

    /** What a topic does, in words. */
    static String kind(String topic) {
        if (topic == null) {
            return "Job";
        }
        if (topic.equals("studio/dam/process")) {
            return "Media processing";
        }
        if (topic.equals("studio/dam/smarttag")) {
            return "Auto tags";
        }
        if (topic.equals("studio/dam/smartcrop")) {
            return "Smart crop";
        }
        if (topic.equals("studio/replication")) {
            return "Publishing";
        }
        if (topic.startsWith("studio/replication/")) {
            return "Publishing · " + topic.substring("studio/replication/".length());
        }
        if (topic.startsWith("studio/msm")) {
            return "Rollout";
        }
        if (topic.startsWith("studio/workflow")) {
            return "Flow";
        }
        if (topic.startsWith("studio/translation")) {
            return "Translation";
        }
        return topic;
    }

    /** The studio jobs in a state, newest first, at most {@code limit}. */
    static List<Map<String, Object>> list(JobManager jm, State state, int limit) {
        List<Job> found = new ArrayList<>();
        switch (state) {
            case WAITING:
                found.addAll(find(jm, JobManager.QueryType.QUEUED, limit));
                break;
            case RUNNING:
                found.addAll(find(jm, JobManager.QueryType.ACTIVE, limit));
                break;
            case FAILED:
                found.addAll(find(jm, JobManager.QueryType.GIVEN_UP, limit));
                found.addAll(find(jm, JobManager.QueryType.ERROR, limit));
                found.addAll(find(jm, JobManager.QueryType.DROPPED, limit));
                // waiting again after a failed try
                for (Job j : find(jm, JobManager.QueryType.QUEUED, limit)) {
                    if (j.getRetryCount() > 0) {
                        found.add(j);
                    }
                }
                break;
            case DONE:
                found.addAll(find(jm, JobManager.QueryType.SUCCEEDED, limit));
                found.addAll(find(jm, JobManager.QueryType.CANCELLED, limit));
                found.addAll(find(jm, JobManager.QueryType.STOPPED, limit));
                break;
            default:
                found.addAll(find(jm, JobManager.QueryType.ALL, limit));
                found.addAll(find(jm, JobManager.QueryType.HISTORY, limit));
        }
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Job j : found) {
            if (j.getTopic() != null && j.getTopic().startsWith(PREFIX) && !byId.containsKey(j.getId())) {
                byId.put(j.getId(), describe(j));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> String.valueOf(b.get("created")).compareTo(String.valueOf(a.get("created"))));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Job> find(JobManager jm, JobManager.QueryType type, int limit) {
        return jm.findJobs(type, null, Math.max(limit * 4, 200), (Map<String, Object>[]) null);
    }

    static Map<String, Object> describe(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("topic", j.getTopic());
        m.put("kind", kind(j.getTopic()));
        Object path = first(j, "path", "payload", "instance");
        m.put("item", path == null ? null : String.valueOf(path));
        Object action = j.getProperty("action");
        m.put("action", action == null ? null : String.valueOf(action));
        Object user = j.getProperty("user");
        m.put("user", user == null ? null : String.valueOf(user));
        Job.JobState s = j.getJobState();
        m.put("state", state(s, j.getRetryCount()));
        m.put("queue", j.getQueueName());
        m.put("created", iso(j.getCreated()));
        m.put("started", iso(j.getProcessingStarted()));
        m.put("finished", iso(j.getFinishedDate()));
        m.put("tries", j.getRetryCount());
        m.put("maxTries", j.getNumberOfRetries());
        m.put("message", j.getResultMessage());
        return m;
    }

    /** Sling's states as the console says them. */
    static String state(Job.JobState s, int retries) {
        if (s == null) {
            return "waiting";
        }
        switch (s) {
            case QUEUED:
                return retries > 0 ? "retrying" : "waiting";
            case ACTIVE:
                return "running";
            case SUCCEEDED:
                return "done";
            case STOPPED:
            case DROPPED:
                return "stopped";
            case GIVEN_UP:
            case ERROR:
                return "failed";
            default:
                return s.name().toLowerCase(Locale.ROOT);
        }
    }

    private static Object first(Job j, String... names) {
        for (String n : names) {
            Object v = j.getProperty(n);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    static String iso(Calendar c) {
        return c == null ? null : c.toInstant().toString();
    }

    /** How many studio jobs in each state (as the lists count them, at most 1000 each). */
    static Map<String, Object> counts(JobManager jm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("waiting", list(jm, State.WAITING, 1000).size());
        m.put("running", list(jm, State.RUNNING, 1000).size());
        m.put("failed", list(jm, State.FAILED, 1000).size());
        return m;
    }

    /**
     * The studio queues: what each runs, how (parallel, retries, delay) and what it is doing; each with the OSGi
     * configuration it comes from, which an admin changes.
     */
    static List<Map<String, Object>> queues(JobManager jm, ConfigurationAdmin ca) throws IOException, InvalidSyntaxException {
        List<Map<String, Object>> out = new ArrayList<>();
        Configuration[] configs = ca == null ? null : ca.listConfigurations("(service.factoryPid=" + QUEUE_FACTORY + ")");
        List<Queue> running = new ArrayList<>();
        if (jm != null) {
            for (Queue q : jm.getQueues()) {
                running.add(q);
            }
        }
        for (Configuration c : configs == null ? new Configuration[0] : configs) {
            if (!c.getPid().contains(OURS)) {
                continue;
            }
            Dictionary<String, Object> p = c.getProperties();
            if (p == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            String name = String.valueOf(p.get("queue.name"));
            m.put("pid", c.getPid());
            m.put("name", name);
            m.put("topics", strings(p.get("queue.topics")));
            m.put("type", String.valueOf(p.get("queue.type")));
            m.put("maxParallel", number(p.get("queue.maxparallel"), 1));
            m.put("retries", number(p.get("queue.retries"), 10));
            m.put("retryDelay", number(p.get("queue.retrydelay"), 2000));
            m.put("priority", p.get("queue.priority") == null ? "NORM" : String.valueOf(p.get("queue.priority")));
            // the queues running under it: one, or one per topic when the name has {0} (publishing: one per agent)
            List<Map<String, Object>> live = new ArrayList<>();
            long waiting = 0;
            long active = 0;
            long failed = 0;
            for (Queue q : running) {
                if (!matches(name, q.getName())) {
                    continue;
                }
                Statistics s = q.getStatistics();
                Map<String, Object> qm = new LinkedHashMap<>();
                qm.put("name", q.getName());
                qm.put("waiting", s.getNumberOfQueuedJobs());
                qm.put("running", s.getNumberOfActiveJobs());
                qm.put("failed", s.getNumberOfFailedJobs());
                qm.put("done", s.getNumberOfFinishedJobs());
                qm.put("suspended", q.isSuspended());
                qm.put("state", q.getStateInfo());
                live.add(qm);
                waiting += s.getNumberOfQueuedJobs();
                active += s.getNumberOfActiveJobs();
                failed += s.getNumberOfFailedJobs();
            }
            m.put("queues", live);
            m.put("waiting", waiting);
            m.put("running", active);
            m.put("failed", failed);
            out.add(m);
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    /** A configured name with {0} stands for every queue named after a topic. */
    static boolean matches(String configured, String actual) {
        if (configured == null || actual == null) {
            return false;
        }
        int at = configured.indexOf("{0}");
        if (at < 0) {
            return configured.equals(actual);
        }
        String before = configured.substring(0, at);
        String after = configured.substring(at + 3);
        return actual.length() >= before.length() + after.length() && actual.startsWith(before) && actual.endsWith(after);
    }

    /**
     * Changes how a studio queue runs: at once (Sling reconfigures the queue), kept in its OSGi configuration.
     */
    static Map<String, Object> configure(ConfigurationAdmin ca, String pid, Integer maxParallel, Integer retries, Long retryDelay)
            throws IOException, InvalidSyntaxException {
        if (ca == null) {
            throw new IllegalStateException("No configuration admin");
        }
        if (pid == null || !pid.startsWith(QUEUE_FACTORY) || !pid.contains(OURS)) {
            throw new IllegalArgumentException("Only Sensible Studio's own queues are configured here");
        }
        Configuration[] found = ca.listConfigurations("(service.pid=" + pid + ")");
        if (found == null || found.length == 0) {
            throw new IllegalArgumentException("No queue " + pid);
        }
        Configuration c = found[0];
        Dictionary<String, Object> p = c.getProperties();
        Dictionary<String, Object> next = new Hashtable<>();
        for (java.util.Enumeration<String> e = p.keys(); e.hasMoreElements();) {
            String k = e.nextElement();
            next.put(k, p.get(k));
        }
        if (maxParallel != null) {
            if (maxParallel < 1 || maxParallel > 32) {
                throw new IllegalArgumentException("At once: 1 to 32");
            }
            if (QueueConfiguration.Type.ORDERED.name().equals(String.valueOf(p.get("queue.type"))) && maxParallel != 1) {
                throw new IllegalArgumentException("An ordered queue runs one job at a time");
            }
            next.put("queue.maxparallel", maxParallel);
        }
        if (retries != null) {
            if (retries < -1 || retries > 10_000) {
                throw new IllegalArgumentException("Retries: -1 (always) to 10000");
            }
            next.put("queue.retries", retries);
        }
        if (retryDelay != null) {
            if (retryDelay < 0 || retryDelay > 86_400_000L) {
                throw new IllegalArgumentException("The delay between tries: 0 to 24 hours");
            }
            next.put("queue.retrydelay", retryDelay);
        }
        c.update(next);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pid", pid);
        out.put("maxParallel", number(next.get("queue.maxparallel"), 1));
        out.put("retries", number(next.get("queue.retries"), 10));
        out.put("retryDelay", number(next.get("queue.retrydelay"), 2000));
        return out;
    }

    private static long number(Object v, long fallback) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return v == null ? fallback : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof String[]) {
            for (String s : (String[]) v) {
                out.add(s);
            }
        } else if (v instanceof Collection) {
            for (Object o : (Collection<?>) v) {
                out.add(String.valueOf(o));
            }
        } else if (v != null) {
            out.add(String.valueOf(v));
        }
        return out;
    }
}
