/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.export.json;

import java.util.Map;

import org.apache.sling.api.resource.Resource;

/**
 * Decides which properties and child resources take part in the JSON model export.
 */
public interface SlingModelFilter {

    /**
     * @param properties all properties of a resource
     * @return the properties to export
     */
    Map<String, Object> filterProperties(Map<String, Object> properties);

    /**
     * @param childResources all children of a resource
     * @return the children to export
     */
    Iterable<Resource> filterChildResources(Iterable<Resource> childResources);
}
