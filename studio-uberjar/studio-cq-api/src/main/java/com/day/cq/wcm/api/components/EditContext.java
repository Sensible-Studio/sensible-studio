/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.components;

/**
 * Authoring information for a component instance. Sensible Studio's authoring UI does not decorate output on
 * the server, so this is only present for API compatibility.
 */
public interface EditContext {

    /** @return the component context this edit context belongs to */
    ComponentContext getComponentContext();

    /** @return the component being edited */
    Component getComponent();
}
