/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.List;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * Looks up rollout configurations.
 */
public interface RolloutConfigManager {

    /** Path of the configuration used when a live copy names none. */
    String DEFAULT_CONFIG = "/libs/msm/wcm/rolloutconfigs/default";

    /** @return the configuration at {@code path} (an {@code /apps} overlay of a {@code /libs} path wins), or {@code null} */
    RolloutConfig getRolloutConfig(ResourceResolver resolver, String path);

    /** @return all configurations below {@code /apps/msm} and {@code /libs/msm} */
    List<RolloutConfig> getRolloutConfigs(ResourceResolver resolver);
}
