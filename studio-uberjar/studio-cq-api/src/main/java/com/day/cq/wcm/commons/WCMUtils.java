/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.commons;

import javax.servlet.ServletRequest;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;

import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.components.ComponentManager;
import com.day.cq.wcm.api.designer.Designer;
import com.day.cq.wcm.api.designer.Style;
import com.day.cq.wcm.commons.policy.ContentPolicyStyle;

/**
 * Static helpers for code running inside a component request.
 */
public final class WCMUtils {

    private WCMUtils() {
    }

    /**
     * @param request current request
     * @return the component context of the current include, or {@code null}
     */
    public static ComponentContext getComponentContext(ServletRequest request) {
        Object ctx = request == null ? null : request.getAttribute(ComponentContext.CONTEXT_ATTR_NAME);
        return ctx instanceof ComponentContext ? (ComponentContext) ctx : null;
    }

    /**
     * @param request current request
     * @return the style of the current resource; never {@code null}
     */
    public static Style getStyle(SlingHttpServletRequest request) {
        Resource resource = request.getResource();
        Designer designer = request.getResourceResolver().adaptTo(Designer.class);
        Style style = designer == null ? null : designer.getStyle(resource);
        return style != null ? style : new ContentPolicyStyle(null, null);
    }

    /**
     * @param resource a content resource
     * @return its component definition, or {@code null}
     */
    public static Component getComponent(Resource resource) {
        if (resource == null) {
            return null;
        }
        ComponentManager cm = resource.getResourceResolver().adaptTo(ComponentManager.class);
        return cm == null ? null : cm.getComponentOfResource(resource);
    }
}
