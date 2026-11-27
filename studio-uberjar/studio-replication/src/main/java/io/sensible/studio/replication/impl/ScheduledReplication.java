/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;

/**
 * Publish Later / Unpublish Later: the requests wait under {@code /var/studio/replication/scheduled/<id>}
 * ({@code at}, {@code action}, {@code path}, {@code deep}, {@code user}, {@code requested}) and this
 * job, every 15 seconds, replicates the ones whose time has come, on behalf of who scheduled them
 * (version, status, one queued request per agent, as if asked then).
 */
@Component(service = {Runnable.class, ScheduledReplication.class},
        property = {"scheduler.period:Long=15", "scheduler.concurrent:Boolean=false", "scheduler.runOn=SINGLE"})
public class ScheduledReplication implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledReplication.class);
    public static final String ROOT = ReplicationJob.LOG_ROOT + "/scheduled";

    @Reference
    private AgentManagerImpl agents;

    @Reference
    private ReplicatorImpl replicator;

    /** One scheduled request. */
    public static final class Entry {
        public final String id;
        public final String action;
        public final String path;
        public final boolean deep;
        public final String user;
        public final Calendar requested;
        public final Calendar at;

        Entry(String id, String action, String path, boolean deep, String user, Calendar requested, Calendar at) {
            this.id = id;
            this.action = action;
            this.path = path;
            this.deep = deep;
            this.user = user;
            this.requested = requested;
            this.at = at;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("agent", ReplicationJob.ALL_AGENTS);
            m.put("action", action);
            m.put("path", path);
            m.put("deep", deep);
            m.put("user", user);
            m.put("requested", ReplicationServlet.iso(requested));
            m.put("retries", 0);
            m.put("state", "SCHEDULED");
            m.put("scheduled", ReplicationServlet.iso(at));
            return m;
        }
    }

    /** Adds a request on behalf of {@code user} (the caller checked the permission; the caller saves). */
    public static Entry add(Session session, ReplicationActionType type, String path, boolean deep, Calendar at, String user) throws RepositoryException {
        Node root = mkdirs(session, ROOT);
        String id = UUID.randomUUID().toString();
        Node n = root.addNode(id, "nt:unstructured");
        n.setProperty("action", type.getName());
        n.setProperty("path", path);
        n.setProperty("deep", deep);
        n.setProperty("user", user == null ? session.getUserID() : user);
        n.setProperty("requested", Calendar.getInstance());
        n.setProperty("at", at);
        return read(n);
    }

    public static boolean remove(Session session, String id) throws RepositoryException {
        if (id == null || id.contains("/") || !session.nodeExists(ROOT + "/" + id)) {
            return false;
        }
        session.getNode(ROOT + "/" + id).remove();
        return true;
    }

    /** The waiting requests, soonest first; {@code path} null for all of them. */
    public static List<Entry> list(Session session, String path) throws RepositoryException {
        List<Entry> out = new ArrayList<>();
        if (!session.nodeExists(ROOT)) {
            return out;
        }
        for (NodeIterator it = session.getNode(ROOT).getNodes(); it.hasNext();) {
            Entry e = read(it.nextNode());
            if (e != null && (path == null || path.equals(e.path))) {
                out.add(e);
            }
        }
        out.sort((a, b) -> a.at.compareTo(b.at));
        return out;
    }

    static Entry read(Node n) throws RepositoryException {
        if (!n.hasProperty("at") || !n.hasProperty("path")) {
            return null;
        }
        return new Entry(n.getName(), Segments(n, "action", "Activate"), n.getProperty("path").getString(),
                n.hasProperty("deep") && n.getProperty("deep").getBoolean(), Segments(n, "user", null),
                n.hasProperty("requested") ? n.getProperty("requested").getDate() : null, n.getProperty("at").getDate());
    }

    private static String Segments(Node n, String name, String dflt) throws RepositoryException {
        return n.hasProperty(name) ? n.getProperty(name).getString() : dflt;
    }

    static Node mkdirs(Session session, String path) throws RepositoryException {
        // not from the root: a session that is not an administrator's may not see it ("Root node is not accessible")
        if (session.nodeExists(path)) {
            return session.getNode(path);
        }
        int cut = path.lastIndexOf('/');
        if (cut <= 0) {
            throw new javax.jcr.PathNotFoundException(path);
        }
        return mkdirs(session, path.substring(0, cut)).addNode(path.substring(cut + 1), "sling:Folder");
    }

    @Override
    public void run() {
        try (ResourceResolver rr = agents.serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            Calendar now = Calendar.getInstance();
            for (Entry e : list(session, null)) {
                if (e.at.after(now)) {
                    continue;
                }
                try {
                    ReplicationOptions o = new ReplicationOptions();
                    o.setDeep(e.deep);
                    replicator.replicateAs(session, ReplicationActionType.fromName(e.action), new String[] {e.path}, o, e.user == null ? "scheduler" : e.user);
                    LOG.info("Scheduled {} of {} fired (asked by {})", e.action, e.path, e.user);
                } catch (ReplicationException ex) {
                    LOG.warn("Scheduled {} of {} failed: {}", e.action, e.path, ex.getMessage());
                }
                session.getNode(ROOT + "/" + e.id).remove();
                session.save();
            }
        } catch (LoginException ex) {
            LOG.error("No replication service user: {}", ex.getMessage());
        } catch (RepositoryException ex) {
            LOG.warn("Scheduled replication: {}", ex.getMessage());
        }
    }
}
