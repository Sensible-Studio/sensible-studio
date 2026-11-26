/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

/**
 * Node, mixin and property names of the Multi Site Manager content model.
 */
public interface MSMNameConstants {

    /** Mixin of every node that is synchronised from a blueprint. */
    String NT_LIVE_RELATIONSHIP = "cq:LiveRelationship";
    /** Mixin of the content node of a live copy root page. */
    String NT_LIVE_SYNC = "cq:LiveSync";
    /** Mixin of a target whose inheritance is cancelled. */
    String NT_LIVE_SYNC_CANCELLED = "cq:LiveSyncCancelled";
    /** Mixin of a target with single properties cancelled. */
    String NT_PROPERTY_LIVE_SYNC_CANCELLED = "cq:PropertyLiveSyncCancelled";
    /** Primary type of the live copy configuration node. */
    String NT_LIVE_COPY = "cq:LiveCopy";
    /** Primary type of a rollout configuration. */
    String NT_ROLLOUT_CONFIG = "cq:RolloutConfig";
    /** Primary type of an action node in a rollout configuration. */
    String NT_LIVE_SYNC_ACTION = "cq:LiveSyncAction";

    /** Name of the live copy configuration node below the root's content node. */
    String NT_LIVE_SYNC_CONFIG = "cq:LiveSyncConfig";

    String PN_MASTER = "cq:master";
    String PN_IS_DEEP = "cq:isDeep";
    String PN_ROLLOUT_CONFIGS = "cq:rolloutConfigs";
    String PN_EXCLUDED_PATHS = "cq:excludedPaths";
    String PN_TRIGGER = "cq:trigger";
    String PN_LAST_ROLLEDOUT = "cq:lastRolledout";
    String PN_LAST_ROLLEDOUT_BY = "cq:lastRolledoutBy";
    String PN_IS_CANCELLED_FOR_CHILDREN = "cq:isCancelledForChildren";
    String PN_PROPERTY_INHERITANCE_CANCELLED = "cq:propertyInheritanceCancelled";
}
