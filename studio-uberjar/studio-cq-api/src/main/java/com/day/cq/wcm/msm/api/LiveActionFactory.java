/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.WCMException;

/**
 * OSGi service creating {@link LiveAction}s. Register it with the {@value #LIVE_ACTION_NAME} property; a rollout
 * configuration uses the action by adding a child node with that name.
 */
public interface LiveActionFactory<T extends LiveAction> {

    /** Service property: name of the action the factory creates. */
    String LIVE_ACTION_NAME = "liveActionName";

    /** @param config the action node of the rollout configuration (its properties configure the action), may be {@code null} */
    T createAction(Resource config) throws WCMException;

    /** @return the name of the action it creates */
    String createsAction();
}
