/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.designer;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.components.ComponentContext;

/**
 * A static-template design ({@code /apps/settings/wcm/designs/...} or {@code /etc/designs/...}).
 * Editable templates use content policies instead; Sensible Studio supports designs only as a fallback.
 */
public interface Design {

    /** @return design id (its path) */
    String getId();

    /** @return design path */
    String getPath();

    /** @return the design content resource, or {@code null} */
    Resource getContentResource();

    /**
     * @param context component context of the instance
     * @return the style for that component instance; never {@code null}
     */
    Style getStyle(ComponentContext context);

    /**
     * @param cell style cell
     * @return the style for that cell; never {@code null}
     */
    Style getStyle(Cell cell);

    /**
     * @param path cell path such as {@code page/par/title}
     * @return the style; never {@code null}
     */
    Style getStyle(String path);

    /**
     * @param resource component resource
     * @return the style for that resource; never {@code null}
     */
    Style getStyle(Resource resource);
}
