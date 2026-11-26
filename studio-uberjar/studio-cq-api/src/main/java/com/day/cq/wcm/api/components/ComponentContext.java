/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.components;

import java.util.Set;

import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.designer.Cell;

/**
 * Per-include context of a component being rendered: which resource, which component, where in
 * the page. Available to scripts as {@code componentContext}.
 */
public interface ComponentContext {

    /** Request attribute under which the current context is stored. */
    String CONTEXT_ATTR_NAME = "com.day.cq.wcm.componentcontext";

    /**
     * Request attribute that, when {@code true}, makes the next include skip Sensible Studio's component
     * handling (no decoration, no new context).
     */
    String BYPASS_COMPONENT_HANDLING_ON_INCLUDE_ATTRIBUTE = "com.day.cq.wcm.bypass";

    /** @return the context of the including component, or {@code null} at the page level */
    ComponentContext getParent();

    /** @return the outermost context */
    ComponentContext getRoot();

    /** @return {@code true} for the page-level context */
    boolean isRoot();

    /** @return the resource being rendered */
    Resource getResource();

    /** @return the component definition of the resource, or {@code null} */
    Component getComponent();

    /** @return the page containing the resource, or {@code null} */
    Page getPage();

    /** @return the style cell for this position */
    Cell getCell();

    /** @return the edit context in author mode, {@code null} otherwise */
    EditContext getEditContext();

    /** @return CSS classes to add to the component's wrapper element (mutable) */
    Set<String> getCssClassNames();

    /**
     * @param name attribute name
     * @return attribute value, or {@code null}
     */
    Object getAttribute(String name);

    /**
     * @param name  attribute name
     * @param value value, {@code null} removes
     * @return previous value
     */
    Object setAttribute(String name, Object value);
}
