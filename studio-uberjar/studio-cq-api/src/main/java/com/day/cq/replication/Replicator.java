/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

import javax.jcr.Session;

/**
 * Sends content to the replication agents: pages, assets, fragments, configurations, anything below
 * {@code /content}, {@code /conf} or {@code /etc}. A request is queued per enabled agent and the
 * content's replication status is recorded at once.
 */
public interface Replicator {

    void replicate(Session session, ReplicationActionType type, String path) throws ReplicationException;

    void replicate(Session session, ReplicationActionType type, String path, ReplicationOptions options)
            throws ReplicationException;

    void replicate(Session session, ReplicationActionType type, String[] paths, ReplicationOptions options)
            throws ReplicationException;

    /** @return the status recorded on {@code path}, or {@code null} if it has none */
    ReplicationStatus getReplicationStatus(Session session, String path);

    /** Throws if the session may not replicate {@code path} ({@code crx:replicate} privilege). */
    void checkPermission(Session session, ReplicationActionType type, String path) throws ReplicationException;
}
