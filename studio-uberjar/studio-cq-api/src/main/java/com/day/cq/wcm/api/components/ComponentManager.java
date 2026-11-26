/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.components;

import java.util.Collection;

import org.apache.sling.api.resource.Resource;

/**
 * Looks up component definitions. Obtain with {@code resolver.adaptTo(ComponentManager.class)}.
 */
public interface ComponentManager {

    /**
     * @param path absolute path or resource type of the component
     * @return the component, or {@code null}
     */
    Component getComponent(String path);

    /**
     * @param resource a content resource
     * @return the component its resource type points to, or {@code null}
     */
    Component getComponentOfResource(Resource resource);

    /** @return all components below {@code /apps} and {@code /libs} */
    Collection<Component> getComponents();
}
