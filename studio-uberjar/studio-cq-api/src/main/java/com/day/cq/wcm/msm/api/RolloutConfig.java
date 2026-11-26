/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.List;

/**
 * A rollout configuration ({@code cq:RolloutConfig} node, e.g. {@code /libs/msm/wcm/rolloutconfigs/default}): when it
 * runs ({@code cq:trigger}) and which synchronisation actions it executes (its child nodes, by name).
 */
public interface RolloutConfig {

    String getPath();

    String getName();

    String getTitle();

    String getDescription();

    RolloutManager.Trigger getTrigger();

    /** @return the names of its actions, in order */
    List<String> getActionNames();
}
