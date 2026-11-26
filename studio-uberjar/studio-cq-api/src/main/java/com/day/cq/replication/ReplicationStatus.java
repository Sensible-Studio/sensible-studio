/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

import java.util.Calendar;

/**
 * The replication status recorded on content ({@code cq:lastReplicated}, {@code cq:lastReplicatedBy},
 * {@code cq:lastReplicationAction}), adaptable from a page's content resource or an asset.
 */
public interface ReplicationStatus {

    String NODE_PROPERTY_LAST_REPLICATED = "cq:lastReplicated";
    String NODE_PROPERTY_LAST_REPLICATED_BY = "cq:lastReplicatedBy";
    String NODE_PROPERTY_LAST_REPLICATION_ACTION = "cq:lastReplicationAction";
    String NODE_TYPE_REPLICATION_STATUS = "cq:ReplicationStatus";

    Calendar getLastPublished();

    String getLastPublishedBy();

    ReplicationActionType getLastReplicationAction();

    /** @return {@code true} if the last action was an activation */
    boolean isActivated();

    boolean isDeactivated();

    /** @return {@code true} if the last request is still in an agent's queue */
    boolean isPending();

    /** @return {@code true} if activated and delivered, with no change since */
    boolean isDelivered();
}
