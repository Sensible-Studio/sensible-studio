/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

/** A replication agent: a configuration and the queue of what it still has to deliver. */
public interface Agent {

    String getId();

    AgentConfig getConfiguration();

    boolean isEnabled();

    /** @return {@code true} if the configuration is complete enough to deliver anything */
    boolean isValid();

    /** Number of requests waiting to be delivered. */
    int getQueueSize();

    /** @return {@code true} if the last delivery failed and the queue waits for a retry */
    boolean isBlocked();
}
