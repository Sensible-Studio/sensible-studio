/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.List;

/**
 * The relationship between a source resource and one live copy target.
 */
public interface LiveRelationship {

    /** @return the live copy this relationship belongs to */
    LiveCopy getLiveCopy();

    /** @return path of the source resource */
    String getSourcePath();

    /** @return path of the target resource */
    String getTargetPath();

    /** @return the synchronisation status */
    LiveStatus getStatus();

    /** @return the rollout configurations of the live copy */
    List<RolloutConfig> getRolloutConfigs();

    /** @return the rollout configurations of the live copy that run on {@code trigger} */
    List<RolloutConfig> getRolloutConfigs(RolloutManager.Trigger trigger);
}
