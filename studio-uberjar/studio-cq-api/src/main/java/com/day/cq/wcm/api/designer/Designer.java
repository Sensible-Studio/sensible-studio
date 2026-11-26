/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.designer;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.Page;

/**
 * Resolves designs and styles. Obtain with {@code resourceResolver.adaptTo(Designer.class)}.
 */
public interface Designer {

    /**
     * @param page a page
     * @return the design of the page, or {@code null} if none applies
     */
    Design getDesign(Page page);

    /**
     * @param id design path
     * @return the design, or {@code null}
     */
    Design getDesign(String id);

    /**
     * @param id design path
     * @return {@code true} if a design exists at that path
     */
    boolean hasDesign(String id);

    /** @return the default design; never {@code null} */
    Design getDefaultDesign();

    /**
     * @param resource component resource inside a page
     * @return the applicable style (from the content policy, else the design); never {@code null}
     */
    Style getStyle(Resource resource);

    /**
     * @param resource component resource
     * @param cellPath explicit cell path
     * @return the style; never {@code null}
     */
    Style getStyle(Resource resource, String cellPath);
}
