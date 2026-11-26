/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.msm.api;

import javax.jcr.RangeIterator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.WCMException;

/**
 * OSGi service for querying and changing live relationships.
 */
public interface LiveRelationshipManager {

    /** @return {@code true} if the resource is a live copy target */
    boolean hasLiveRelationship(Resource resource);

    /** @return {@code true} if the resource is a blueprint source of at least one live copy */
    boolean isSource(Resource resource);

    /** @return {@code true} if the resource is inside a live copy (synchronised or local) */
    boolean isInLiveCopy(Resource resource);

    /**
     * @param advancedStatus {@code true} to compute the full status
     * @return the relationship of a live copy target, or {@code null}
     */
    LiveRelationship getLiveRelationship(Resource resource, boolean advancedStatus);

    /**
     * @param targetPathFilter only relationships whose target path starts with it, or {@code null}
     * @param trigger only live copies with a rollout configuration on that trigger, or {@code null}
     * @return the relationships of a source to its live copies ({@link LiveRelationship} items)
     */
    RangeIterator getLiveRelationships(Resource source, String targetPathFilter, RolloutManager.Trigger trigger);

    /** @return the live copy the resource belongs to, or {@code null} */
    LiveCopy getLiveCopy(Resource resource);

    /** Cancels inheritance of the target ({@code deep}: also for the nodes below it). */
    void cancelRelationship(ResourceResolver resolver, LiveRelationship relation, boolean deep, boolean autoSave)
            throws WCMException;

    /** Re-enables inheritance of the target. The next rollout synchronises it again. */
    void reenableRelationship(ResourceResolver resolver, LiveRelationship relation, boolean autoSave) throws WCMException;

    /** Cancels inheritance of single properties of the target. */
    void cancelPropertyRelationship(ResourceResolver resolver, LiveRelationship relation, String[] names, boolean autoSave)
            throws WCMException;

    /** Re-enables inheritance of single properties of the target. */
    void reenablePropertyRelationship(ResourceResolver resolver, LiveRelationship relation, String[] names,
            boolean autoSave) throws WCMException;

    /** Detaches the target (and everything below it) from its source for good. */
    void endRelationship(ResourceResolver resolver, LiveRelationship relation, boolean autoSave) throws WCMException;
}
