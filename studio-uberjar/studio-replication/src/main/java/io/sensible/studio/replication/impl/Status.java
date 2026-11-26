/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.util.Calendar;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationStatus;

/** The {@code cq:lastReplicated*} properties, where AEM keeps them: on a page's or asset's {@code jcr:content}. */
final class Status implements ReplicationStatus {

    private final Calendar when;
    private final String by;
    private final ReplicationActionType action;
    private final boolean pending;
    private final Calendar modified;

    private Status(Calendar when, String by, ReplicationActionType action, boolean pending, Calendar modified) {
        this.when = when;
        this.by = by;
        this.action = action;
        this.pending = pending;
        this.modified = modified;
    }

    /** The node the status of {@code path} lives on: its {@code jcr:content} when it has one, else itself. */
    static Node statusNode(Session session, String path) throws RepositoryException {
        if (!session.nodeExists(path)) {
            return null;
        }
        Node n = session.getNode(path);
        return n.hasNode("jcr:content") ? n.getNode("jcr:content") : n;
    }

    static Status read(Session session, String path, boolean pending) throws RepositoryException {
        return read(session, path, pending, "");
    }

    /** The status of a tier: {@code suffix} {@code _preview} for the preview tier (see {@link Tiers}), empty for publish. */
    static Status read(Session session, String path, boolean pending, String suffix) throws RepositoryException {
        Node n = statusNode(session, path);
        if (n == null || !n.hasProperty(NODE_PROPERTY_LAST_REPLICATED + suffix)) {
            return null;
        }
        Calendar modified = n.hasProperty("cq:lastModified") ? n.getProperty("cq:lastModified").getDate()
                : n.hasProperty("jcr:lastModified") ? n.getProperty("jcr:lastModified").getDate() : null;
        return new Status(n.getProperty(NODE_PROPERTY_LAST_REPLICATED + suffix).getDate(),
                n.hasProperty(NODE_PROPERTY_LAST_REPLICATED_BY + suffix) ? n.getProperty(NODE_PROPERTY_LAST_REPLICATED_BY + suffix).getString() : null,
                n.hasProperty(NODE_PROPERTY_LAST_REPLICATION_ACTION + suffix)
                        ? ReplicationActionType.fromName(n.getProperty(NODE_PROPERTY_LAST_REPLICATION_ACTION + suffix).getString()) : null,
                pending, modified);
    }

    /** Records a request on the content, like AEM does when the request is accepted. */
    static void write(Session session, String path, ReplicationActionType type, String user) throws RepositoryException {
        write(session, path, type, user, "");
    }

    /** As {@link #write(Session, String, ReplicationActionType, String)}, for the tier of {@code suffix}. */
    static void write(Session session, String path, ReplicationActionType type, String user, String suffix) throws RepositoryException {
        Node n = statusNode(session, path);
        if (n == null) {
            return;
        }
        if (!n.isNodeType(NODE_TYPE_REPLICATION_STATUS) && n.canAddMixin(NODE_TYPE_REPLICATION_STATUS)) {
            n.addMixin(NODE_TYPE_REPLICATION_STATUS);
        }
        n.setProperty(NODE_PROPERTY_LAST_REPLICATED + suffix, Calendar.getInstance());
        n.setProperty(NODE_PROPERTY_LAST_REPLICATED_BY + suffix, user);
        n.setProperty(NODE_PROPERTY_LAST_REPLICATION_ACTION + suffix, type.getName());
    }

    @Override
    public Calendar getLastPublished() {
        return when;
    }

    @Override
    public String getLastPublishedBy() {
        return by;
    }

    @Override
    public ReplicationActionType getLastReplicationAction() {
        return action;
    }

    @Override
    public boolean isActivated() {
        return action == ReplicationActionType.ACTIVATE;
    }

    @Override
    public boolean isDeactivated() {
        return action == ReplicationActionType.DEACTIVATE || action == ReplicationActionType.DELETE;
    }

    @Override
    public boolean isPending() {
        return pending;
    }

    @Override
    public boolean isDelivered() {
        return isActivated() && !pending && (modified == null || !modified.after(when));
    }
}
