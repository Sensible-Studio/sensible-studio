/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.Privilege;
import javax.jcr.version.VersionManager;

import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.AgentFilter;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.ReplicationStatus;
import com.day.cq.replication.Replicator;

/**
 * The replication service. A request: the session's rights are checked ({@code crx:replicate}, or
 * write access), a version is saved before an activation, the status is recorded on the content, and
 * one job per enabled agent goes into the queue (or is delivered at once when synchronous).
 */
@Component(service = {Replicator.class, ReplicatorImpl.class})
public class ReplicatorImpl implements Replicator {

    private static final Logger LOG = LoggerFactory.getLogger(ReplicatorImpl.class);

    @Reference
    private AgentManagerImpl agents;

    @Reference
    private JobManager jobManager;

    public ReplicatorImpl() {
    }

    ReplicatorImpl(AgentManagerImpl agents, JobManager jobManager) {
        this.agents = agents;
        this.jobManager = jobManager;
    }

    @Override
    public void replicate(Session session, ReplicationActionType type, String path) throws ReplicationException {
        replicate(session, type, new String[] {path}, new ReplicationOptions());
    }

    @Override
    public void replicate(Session session, ReplicationActionType type, String path, ReplicationOptions options) throws ReplicationException {
        replicate(session, type, new String[] {path}, options);
    }

    @Override
    public void replicate(Session session, ReplicationActionType type, String[] paths, ReplicationOptions options) throws ReplicationException {
        replicateAs(session, type, paths, options, session.getUserID());
    }

    /** As {@link #replicate}, recording {@code user} as the one who asked (scheduled requests run under the service user). */
    void replicateAs(Session session, ReplicationActionType type, String[] paths, ReplicationOptions options, String user) throws ReplicationException {
        if (type == null || paths == null) {
            throw new ReplicationException("Nothing to replicate");
        }
        ReplicationOptions o = options == null ? new ReplicationOptions() : options;
        // the default (every enabled agent) is the publish tier: the preview tier gets only what is sent to it
        AgentFilter filter = o.getFilter() == AgentFilter.DEFAULT ? Tiers.filter(null) : o.getFilter();
        List<Agent> targets = new ArrayList<>();
        for (Agent a : agents.getAgents().values()) {
            // a reverse agent fetches; it is never delivered to
            if (filter.isIncluded(a) && a.isValid() && !ReverseReplication.REVERSE.equals(a.getConfiguration().getSerializationType())) {
                targets.add(a);
            }
        }
        // only preview agents: the request is the preview tier's, with its own status and no version
        String tier = !targets.isEmpty() && targets.stream().allMatch(Tiers::isPreview) ? Tiers.PREVIEW : null;
        if (targets.isEmpty() && type != ReplicationActionType.TEST) {
            throw new ReplicationException("No enabled replication agent");
        }
        try {
            for (String path : paths) {
                if (path == null || !path.startsWith("/")) {
                    throw new ReplicationException("Not a path: " + path);
                }
                if (path.startsWith("/content/launches/")) {
                    // as in AEM: a launch is promoted to its source, never published on its own
                    throw new ReplicationException("Launch pages are not published: promote the launch first");
                }
                checkPermission(session, type, path);
                if (type == ReplicationActionType.ACTIVATE && !session.nodeExists(path)) {
                    throw new ReplicationException("Nothing at " + path);
                }
                // a user or a group has no versions and no publishing status of its own
                boolean authorizable = AuthorizablePack.isAuthorizablePath(path);
                if (type == ReplicationActionType.ACTIVATE && !o.isSuppressVersions() && !authorizable && tier == null) {
                    version(session, path);
                }
                if (!o.isSuppressStatusUpdate() && type != ReplicationActionType.TEST && !authorizable) {
                    Status.write(session, path, type, user, Tiers.suffix(tier));
                }
            }
            if (session.hasPendingChanges()) {
                session.save();
            }
            for (String path : paths) {
                for (Agent a : targets) {
                    Map<String, Object> props = new HashMap<>();
                    props.put(ReplicationJob.PROP_AGENT, a.getId());
                    props.put(ReplicationJob.PROP_ACTION, type.getName());
                    props.put(ReplicationJob.PROP_PATH, path);
                    props.put(ReplicationJob.PROP_DEEP, o.isDeep());
                    props.put(ReplicationJob.PROP_USER, user);
                    props.put(ReplicationJob.PROP_REQUESTED, Calendar.getInstance());
                    if (o.isSynchronous()) {
                        ReplicationJob.deliver(session, a.getConfiguration(), type.getName(), path, o.isDeep());
                    } else if (jobManager.addJob(ReplicationJob.topicOf(a.getId()), props) == null) {
                        throw new ReplicationException("The replication queue refused the request for " + path);
                    }
                }
            }
        } catch (RepositoryException | IOException e) {
            throw new ReplicationException("Could not replicate: " + e.getMessage(), e);
        }
    }

    /** AEM saves a version of a page before it is activated (the "Versioning" of the agent). */
    private static void version(Session session, String path) throws RepositoryException {
        Node n = session.getNode(path);
        Node content = n.hasNode("jcr:content") && n.isNodeType("cq:Page") ? n.getNode("jcr:content") : null;
        if (content == null) {
            return;
        }
        try {
            if (!content.isNodeType("mix:versionable")) {
                content.addMixin("mix:versionable");
            }
            content.setProperty("cq:versionComment", "Before activation");
            content.setProperty("cq:versionCreator", session.getUserID());
            session.save();
            VersionManager vm = session.getWorkspace().getVersionManager();
            if (!vm.isCheckedOut(content.getPath())) {
                vm.checkout(content.getPath());
            }
            vm.checkpoint(content.getPath());
        } catch (RepositoryException e) {
            LOG.warn("No version before activating {}: {}", path, e.getMessage());
            session.refresh(false);
        }
    }

    @Override
    public ReplicationStatus getReplicationStatus(Session session, String path) {
        return getReplicationStatus(session, path, null);
    }

    /** The status of {@code path} on a tier (null: publish), pending while a request of that tier's agents waits. */
    public ReplicationStatus getReplicationStatus(Session session, String path, String tier) {
        try {
            boolean preview = Tiers.PREVIEW.equals(tier);
            boolean pending = false;
            for (org.apache.sling.event.jobs.Job j : ReplicationJob.find(jobManager, null,
                    Collections.singletonMap(ReplicationJob.PROP_PATH, path), -1)) {
                Agent a = agents.getAgent(j.getProperty(ReplicationJob.PROP_AGENT, String.class));
                if (Tiers.isPreview(a) == preview) {
                    pending = true;
                    break;
                }
            }
            return Status.read(session, path, pending, Tiers.suffix(tier));
        } catch (RepositoryException e) {
            return null;
        }
    }

    /** Whether there is an enabled agent of the preview tier. */
    public boolean hasPreview() {
        for (Agent a : agents.getAgents().values()) {
            if (a.isEnabled() && Tiers.isPreview(a)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void checkPermission(Session session, ReplicationActionType type, String path) throws ReplicationException {
        try {
            String existing = path;
            while (!session.nodeExists(existing) && existing.lastIndexOf('/') > 0) {
                existing = existing.substring(0, existing.lastIndexOf('/'));
            }
            AccessControlManager acm = session.getAccessControlManager();
            Privilege replicate;
            try {
                replicate = acm.privilegeFromName("crx:replicate");
            } catch (javax.jcr.security.AccessControlException e) {
                replicate = null;
            }
            boolean allowed = replicate != null && acm.hasPrivileges(existing, new Privilege[] {replicate});
            if (!allowed) {
                // without the AEM privilege, whoever may change the content may publish it
                allowed = acm.hasPrivileges(existing, new Privilege[] {acm.privilegeFromName(Privilege.JCR_WRITE)});
            }
            if (!allowed) {
                throw new ReplicationException(session.getUserID() + " may not replicate " + path);
            }
        } catch (RepositoryException e) {
            throw new ReplicationException("Cannot check replication rights on " + path + ": " + e.getMessage(), e);
        }
    }
}
