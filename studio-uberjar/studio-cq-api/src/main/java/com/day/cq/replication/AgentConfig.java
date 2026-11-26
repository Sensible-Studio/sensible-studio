/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

import org.apache.sling.api.resource.ValueMap;

/** The stored configuration of an agent ({@code /etc/replication/agents.author/<id>/jcr:content}). */
public interface AgentConfig {

    String AGENT_ENABLED = "enabled";
    String TRANSPORT_URI = "transportUri";
    String TRANSPORT_USER = "transportUser";
    String TRANSPORT_PASSWORD = "transportPassword";
    String AGENT_LOG_LEVEL = "logLevel";
    String RETRY_DELAY = "retryDelay";
    String SERIALIZATION_TYPE = "serializationType";
    String AGENT_DESCRIPTION = "jcr:description";
    String AGENT_NAME = "jcr:title";

    String getId();

    String getName();

    String getDescription();

    String getConfigPath();

    boolean isEnabled();

    String getTransportURI();

    String getTransportUser();

    String getTransportPassword();

    /** Milliseconds between retries of a failed delivery. */
    long getRetryDelay();

    String getSerializationType();

    /** All stored properties. */
    ValueMap getProperties();
}
