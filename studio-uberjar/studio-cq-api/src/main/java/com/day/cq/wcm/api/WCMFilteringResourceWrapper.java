/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;

import com.adobe.granite.ui.components.ExpressionResolver;

/**
 * Wraps a dialog/configuration resource relative to the content it edits. Sensible Studio evaluates no
 * expressions here; the wrapper only remembers the base resource.
 */
public class WCMFilteringResourceWrapper extends ResourceWrapper {

    private final Resource baseResource;

    public WCMFilteringResourceWrapper(Resource resource, Resource baseResource, ExpressionResolver resolver,
            SlingHttpServletRequest request) {
        super(resource);
        this.baseResource = baseResource;
    }

    /** @return the content resource the wrapped resource is about */
    public Resource getBaseResource() {
        return baseResource;
    }
}
