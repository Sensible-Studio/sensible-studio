package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.junit.jupiter.api.Test;

class ReplicationTopicTest {

    @Test
    void everyAgentHasItsOwnTopic() {
        assertEquals("studio/replication/publish", ReplicationJob.topicOf("publish"));
        assertEquals("studio/replication/live_cache-2", ReplicationJob.topicOf("live_cache-2"));
        assertEquals(Arrays.asList("studio/replication/publish", "studio/replication"), ReplicationJob.topicsOf("publish"));
    }

    @Test
    void anIdThatCannotBeATopicSegmentKeepsTheSharedQueue() {
        for (String bad : new String[] {null, "", "a/b", "a.b", "*", "x y"}) {
            assertEquals(ReplicationJob.TOPIC, ReplicationJob.topicOf(bad), String.valueOf(bad));
            assertEquals(Collections.singletonList(ReplicationJob.TOPIC), ReplicationJob.topicsOf(bad));
        }
    }

    @Test
    void replicationTopics() {
        assertTrue(ReplicationJob.isReplicationTopic("studio/replication"));
        assertTrue(ReplicationJob.isReplicationTopic("studio/replication/publish"));
        assertFalse(ReplicationJob.isReplicationTopic("studio/replicationx"));
        assertFalse(ReplicationJob.isReplicationTopic("studio/workflow"));
        assertFalse(ReplicationJob.isReplicationTopic(null));
    }

    @Test
    void findsAnAgentsRequestsOnItsTopicThenOnTheOldOne() {
        List<Job> jobs = new ArrayList<>();
        jobs.add(job("studio/replication", "publish", "/content/a"));
        jobs.add(job("studio/replication/publish", "publish", "/content/b"));
        jobs.add(job("studio/replication/publish2", "publish2", "/content/c"));
        jobs.add(job("studio/workflow", "publish", "/content/d"));
        JobManager jm = manager(jobs);

        assertEquals(Arrays.asList("/content/b", "/content/a"), paths(ReplicationJob.find(jm, "publish", null, -1)));
        assertEquals(Collections.singletonList("/content/b"), paths(ReplicationJob.find(jm, "publish", null, 1)));
        assertEquals(Arrays.asList("/content/a", "/content/b", "/content/c"), paths(ReplicationJob.find(jm, null, null, -1)));
        assertEquals(Collections.singletonList("/content/c"),
                paths(ReplicationJob.find(jm, null, Collections.singletonMap(ReplicationJob.PROP_PATH, "/content/c"), 1)));
        assertTrue(ReplicationJob.find(jm, null, Collections.singletonMap(ReplicationJob.PROP_PATH, "/content/d"), 1).isEmpty());
    }

    private static List<String> paths(List<Job> jobs) {
        List<String> out = new ArrayList<>();
        for (Job j : jobs) {
            out.add(j.getProperty(ReplicationJob.PROP_PATH, String.class));
        }
        return out;
    }

    private static Job job(String topic, String agent, String path) {
        Map<String, Object> props = new HashMap<>();
        props.put(ReplicationJob.PROP_AGENT, agent);
        props.put(ReplicationJob.PROP_PATH, path);
        return (Job) Proxy.newProxyInstance(Job.class.getClassLoader(), new Class<?>[] {Job.class}, (p, m, args) -> {
            switch (m.getName()) {
                case "getTopic":
                    return topic;
                case "getProperty":
                    return props.get(args[0]);
                default:
                    throw new UnsupportedOperationException(m.getName());
            }
        });
    }

    /** findJobs as Sling does it: an exact topic (null: all), a template every property of which must match. */
    @SuppressWarnings("unchecked")
    private static JobManager manager(List<Job> jobs) {
        return (JobManager) Proxy.newProxyInstance(JobManager.class.getClassLoader(), new Class<?>[] {JobManager.class}, (p, m, args) -> {
            if (!"findJobs".equals(m.getName())) {
                throw new UnsupportedOperationException(m.getName());
            }
            String topic = (String) args[1];
            long limit = ((Number) args[2]).longValue();
            Map<String, Object>[] templates = (Map<String, Object>[]) args[3];
            Collection<Job> out = new ArrayList<>();
            for (Job j : jobs) {
                if (topic != null && !topic.equals(j.getTopic())) {
                    continue;
                }
                boolean matches = templates == null || templates.length == 0;
                for (Map<String, Object> t : templates == null ? new Map[0] : templates) {
                    boolean all = true;
                    for (Map.Entry<String, Object> e : t.entrySet()) {
                        all &= e.getValue().equals(j.getProperty(e.getKey(), Object.class));
                    }
                    matches |= all;
                }
                if (matches && (limit < 1 || out.size() < limit)) {
                    out.add(j);
                }
            }
            return out;
        });
    }
}
