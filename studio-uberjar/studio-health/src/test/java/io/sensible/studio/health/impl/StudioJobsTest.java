/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.Queue;
import org.apache.sling.event.jobs.Statistics;
import org.junit.jupiter.api.Test;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

/** The Jobs console: the studio jobs in words, the queues and their configuration. */
class StudioJobsTest {

    private static Calendar at(int minute) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(1_795_000_000_000L + minute * 60_000L);
        return c;
    }

    private static Job job(String id, String topic, Job.JobState state, int retries, int minute, Map<String, Object> props) {
        return (Job) Proxy.newProxyInstance(Job.class.getClassLoader(), new Class<?>[] {Job.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getId":
                    return id;
                case "getTopic":
                    return topic;
                case "getJobState":
                    return state;
                case "getRetryCount":
                    return retries;
                case "getNumberOfRetries":
                    return 60;
                case "getCreated":
                    return at(minute);
                case "getProcessingStarted":
                    return state == Job.JobState.QUEUED ? null : at(minute);
                case "getFinishedDate":
                    return state == Job.JobState.SUCCEEDED || state == Job.JobState.GIVEN_UP ? at(minute + 1) : null;
                case "getQueueName":
                    return "Sensible Studio DAM processing";
                case "getResultMessage":
                    return state == Job.JobState.GIVEN_UP ? "ffmpeg not found" : null;
                case "getProperty":
                    return a.length == 1 ? props.get((String) a[0]) : null;
                default:
                    return null;
            }
        });
    }

    private static Statistics stats(long queued, long active) {
        return (Statistics) Proxy.newProxyInstance(Statistics.class.getClassLoader(), new Class<?>[] {Statistics.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getNumberOfQueuedJobs":
                    return queued;
                case "getNumberOfActiveJobs":
                    return active;
                default:
                    return m.getReturnType() == long.class ? 0L : null;
            }
        });
    }

    private static Queue queue(String name, long queued) {
        return (Queue) Proxy.newProxyInstance(Queue.class.getClassLoader(), new Class<?>[] {Queue.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getName":
                    return name;
                case "getStatistics":
                    return stats(queued, 0);
                case "isSuspended":
                    return false;
                case "getStateInfo":
                    return "ok";
                default:
                    return null;
            }
        });
    }

    private static JobManager manager(Map<JobManager.QueryType, List<Job>> jobs, List<Queue> queues) {
        return (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "findJobs":
                    return new ArrayList<>(jobs.getOrDefault((JobManager.QueryType) a[0], Collections.emptyList()));
                case "getQueues":
                    return queues;
                case "getStatistics":
                    return stats(3, 1);
                default:
                    return null;
            }
        });
    }

    private static final class Config {
        final String pid;
        Dictionary<String, Object> props;

        Config(String pid, Dictionary<String, Object> props) {
            this.pid = pid;
            this.props = props;
        }

        Configuration proxy() {
            return (Configuration) Proxy.newProxyInstance(Configuration.class.getClassLoader(), new Class<?>[] {Configuration.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "getPid":
                        return pid;
                    case "getProperties":
                        return props;
                    case "update":
                        if (a != null && a.length == 1) {
                            @SuppressWarnings("unchecked")
                            Dictionary<String, Object> d = (Dictionary<String, Object>) a[0];
                            props = d;
                        }
                        return null;
                    default:
                        return null;
                }
            });
        }
    }

    private static ConfigurationAdmin admin(List<Config> configs) {
        return (ConfigurationAdmin) Proxy.newProxyInstance(ConfigurationAdmin.class.getClassLoader(), new Class<?>[] {ConfigurationAdmin.class}, (p, m, a) -> {
            if ("listConfigurations".equals(m.getName())) {
                String filter = (String) a[0];
                List<Configuration> out = new ArrayList<>();
                for (Config c : configs) {
                    if (filter.contains("service.factoryPid") || filter.contains("service.pid=" + c.pid + ")")) {
                        out.add(c.proxy());
                    }
                }
                return out.isEmpty() ? null : out.toArray(new Configuration[0]);
            }
            return null;
        });
    }

    private static Dictionary<String, Object> queueConfig(String name, String type, int parallel) {
        Dictionary<String, Object> d = new Hashtable<>();
        d.put("queue.name", name);
        d.put("queue.type", type);
        d.put("queue.topics", new String[] {"studio/x"});
        d.put("queue.maxparallel", parallel);
        d.put("queue.retries", 60);
        d.put("queue.retrydelay", 30000L);
        return d;
    }

    @Test
    void listsTheStudioJobsInWords() {
        Map<String, Object> asset = new HashMap<>();
        asset.put("path", "/content/dam/wknd/surf.jpg");
        Map<String, Object> page = new HashMap<>();
        page.put("path", "/content/wknd/en");
        page.put("action", "Activate");
        page.put("user", "anna");
        Map<JobManager.QueryType, List<Job>> jobs = new HashMap<>();
        Job processing = job("1", "studio/dam/process", Job.JobState.QUEUED, 0, 1, asset);
        Job retrying = job("2", "studio/replication/publish", Job.JobState.QUEUED, 3, 2, page);
        Job failed = job("3", "studio/dam/smarttag", Job.JobState.GIVEN_UP, 60, 3, asset);
        Job done = job("4", "studio/dam/smartcrop", Job.JobState.SUCCEEDED, 0, 4, asset);
        Job foreign = job("5", "org/apache/sling/other", Job.JobState.QUEUED, 0, 5, asset);
        jobs.put(JobManager.QueryType.QUEUED, Arrays.asList(processing, retrying, foreign));
        jobs.put(JobManager.QueryType.ALL, Arrays.asList(processing, retrying, foreign));
        jobs.put(JobManager.QueryType.GIVEN_UP, Collections.singletonList(failed));
        jobs.put(JobManager.QueryType.HISTORY, Arrays.asList(failed, done));
        jobs.put(JobManager.QueryType.SUCCEEDED, Collections.singletonList(done));
        JobManager jm = manager(jobs, Collections.emptyList());

        List<Map<String, Object>> all = StudioJobs.list(jm, StudioJobs.State.ALL, 50);
        assertEquals(Arrays.asList("4", "3", "2", "1"), ids(all), "only the studio jobs, newest first");
        Map<String, Object> publish = all.get(2);
        assertEquals("Publishing · publish", publish.get("kind"));
        assertEquals("retrying", publish.get("state"));
        assertEquals("/content/wknd/en", publish.get("item"));
        assertEquals("anna", publish.get("user"));
        assertEquals("Activate", publish.get("action"));
        assertEquals("failed", all.get(1).get("state"));
        assertEquals("ffmpeg not found", all.get(1).get("message"));
        assertEquals("Auto tags", all.get(1).get("kind"));

        assertEquals(Arrays.asList("2", "1"), ids(StudioJobs.list(jm, StudioJobs.State.WAITING, 50)));
        // failed: given up, and waiting again after failing
        assertEquals(Arrays.asList("3", "2"), ids(StudioJobs.list(jm, StudioJobs.State.FAILED, 50)));
        assertEquals(Collections.singletonList("4"), ids(StudioJobs.list(jm, StudioJobs.State.DONE, 50)));
        assertEquals(1, StudioJobs.list(jm, StudioJobs.State.ALL, 1).size());
        assertThrows(IllegalArgumentException.class, () -> StudioJobs.State.of("sleeping"));
        assertEquals(2, StudioJobs.counts(jm).get("waiting"));
        assertEquals(2, StudioJobs.counts(jm).get("failed"));
    }

    private static List<String> ids(List<Map<String, Object>> list) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : list) {
            out.add((String) m.get("id"));
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void showsTheStudioQueuesAndChangesHowTheyRun() throws Exception {
        String dam = StudioJobs.QUEUE_FACTORY + "~studio-dam";
        String rep = StudioJobs.QUEUE_FACTORY + "~studio-replication";
        String foreign = StudioJobs.QUEUE_FACTORY + "~other";
        List<Config> configs = new ArrayList<>(Arrays.asList(
                new Config(dam, queueConfig("Sensible Studio DAM processing", "UNORDERED", 2)),
                new Config(rep, queueConfig("Sensible Studio replication {0}", "ORDERED", 1)),
                new Config(foreign, queueConfig("Someone else's", "UNORDERED", 4))));
        JobManager jm = manager(Collections.emptyMap(), Arrays.asList(queue("Sensible Studio DAM processing", 5),
                queue("Sensible Studio replication publish", 2), queue("Sensible Studio replication publish2", 1)));
        ConfigurationAdmin ca = admin(configs);

        List<Map<String, Object>> queues = StudioJobs.queues(jm, ca);
        assertEquals(2, queues.size(), "only ours");
        Map<String, Object> d = queues.get(0);
        assertEquals("Sensible Studio DAM processing", d.get("name"));
        assertEquals(2L, d.get("maxParallel"));
        assertEquals(5L, d.get("waiting"));
        Map<String, Object> r = queues.get(1);
        assertEquals(2, ((List<Object>) r.get("queues")).size(), "one queue per agent under {0}");
        assertEquals(3L, r.get("waiting"));

        Map<String, Object> changed = StudioJobs.configure(ca, dam, 4, 10, 5000L);
        assertEquals(4L, changed.get("maxParallel"));
        assertEquals(4, configs.get(0).props.get("queue.maxparallel"));
        assertEquals("Sensible Studio DAM processing", configs.get(0).props.get("queue.name"), "the rest is kept");
        assertThrows(IllegalArgumentException.class, () -> StudioJobs.configure(ca, rep, 3, null, null), "an ordered queue runs one at a time");
        assertThrows(IllegalArgumentException.class, () -> StudioJobs.configure(ca, foreign, 2, null, null));
        assertThrows(IllegalArgumentException.class, () -> StudioJobs.configure(ca, dam, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> StudioJobs.configure(ca, dam, null, null, -5L));
    }

    @Test
    void namesWithAPlaceholderStandForEachTopic() {
        assertTrue(StudioJobs.matches("Sensible Studio replication {0}", "Sensible Studio replication live_cache"));
        assertFalse(StudioJobs.matches("Sensible Studio replication {0}", "Sensible Studio DAM processing"));
        assertTrue(StudioJobs.matches("Sensible Studio DAM processing", "Sensible Studio DAM processing"));
        assertEquals("Rollout", StudioJobs.kind("studio/msm/rollout"));
        assertEquals("Flow", StudioJobs.kind("studio/workflow/event"));
        assertEquals("waiting", StudioJobs.state(null, 0));
    }
}
