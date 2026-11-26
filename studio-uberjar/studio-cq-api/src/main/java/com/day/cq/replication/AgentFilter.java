/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

/** Chooses the agents a replication request goes to. */
public interface AgentFilter {

    /** Every enabled agent. */
    AgentFilter DEFAULT = agent -> agent != null && agent.isEnabled();

    boolean isIncluded(Agent agent);
}
