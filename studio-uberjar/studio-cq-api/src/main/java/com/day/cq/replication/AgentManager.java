/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

import java.util.Map;

/** The replication agents of this instance, by id. */
public interface AgentManager {

    Map<String, Agent> getAgents();

    /** @return the agent with this id, or {@code null} */
    Agent getAgent(String id);
}
