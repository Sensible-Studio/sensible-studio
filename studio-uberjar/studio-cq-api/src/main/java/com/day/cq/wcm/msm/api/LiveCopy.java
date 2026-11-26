/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import java.util.List;
import java.util.Set;

/**
 * A live copy: a tree synchronised from a blueprint (source) tree. Its configuration is the
 * {@code cq:LiveSyncConfig} node ({@code cq:LiveCopy}) below the content node of its root page.
 */
public interface LiveCopy {

    /** @return path of the live copy root */
    String getPath();

    /** @return path of the blueprint it is synchronised from ({@code cq:master}) */
    String getBlueprintPath();

    /** @return {@code true} if child pages are synchronised too ({@code cq:isDeep}) */
    boolean isDeep();

    /** @return {@code true} if this is the top-most live copy (not nested in another one) */
    boolean isRoot();

    /** @return paths relative to the root that are not synchronised ({@code cq:excludedPaths}) */
    Set<String> getExclusions();

    /** @return {@code true} if the path (absolute, in the live copy) is excluded from synchronisation */
    boolean isExcluded(String path);

    /** @return the rollout configurations, own or inherited from the parent live copy / blueprint */
    List<RolloutConfig> getRolloutConfigs();

    /** @return {@code true} if one of its rollout configurations runs on that trigger */
    boolean isTrigger(RolloutManager.Trigger trigger);
}
