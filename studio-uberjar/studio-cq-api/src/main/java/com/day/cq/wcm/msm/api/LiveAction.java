/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.WCMException;

/**
 * A synchronisation action, run by a rollout for every pair of source and target resources (the page content node and
 * each node below it). A side that does not exist is passed as a non-existing resource
 * ({@link org.apache.sling.api.resource.ResourceUtil#isNonExistingResource}).
 */
public interface LiveAction {

    /** @return the action name used in rollout configurations */
    String getName();

    /**
     * @param autoSave commit the changes
     * @param isResetRollout the rollout re-enables cancelled inheritance
     */
    void execute(Resource source, Resource target, LiveRelationship relation, boolean autoSave, boolean isResetRollout)
            throws WCMException;
}
