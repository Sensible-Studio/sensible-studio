/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.reference;

import java.util.List;

import org.apache.sling.api.resource.Resource;

/**
 * OSGi service that finds references of one kind for a resource.
 */
public interface ReferenceProvider {

    /** @return references of the resource, never {@code null} */
    List<Reference> findReferences(Resource resource);
}
