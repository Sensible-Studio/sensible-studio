/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;

/**
 * Static helpers for form rendering and submission.
 */
public final class FormsHelper {

    private FormsHelper() {
    }

    /** @return the id of the form the request renders: its {@code id} property or a path-based id */
    public static String getFormId(SlingHttpServletRequest request) {
        Resource r = request.getResource();
        String id = r.getValueMap().get("id", String.class);
        return id != null ? id : r.getPath().replace('/', '_');
    }

    /** @return the submitted values of a field (by its {@code name} property), never {@code null} */
    public static String[] getValues(SlingHttpServletRequest request, Resource fieldResource) {
        String name = fieldResource.getValueMap().get("name", fieldResource.getName());
        String[] values = request.getParameterValues(name);
        return values == null ? new String[0] : values;
    }

    /**
     * Runs a form action script ({@code <actionType>/<script>.jsp|html}). Sensible Studio does not implement
     * form actions yet.
     *
     * @throws UnsupportedOperationException always
     */
    public static void runAction(String actionType, String script, Resource formResource,
            SlingHttpServletRequest request, SlingHttpServletResponse response) {
        throw new UnsupportedOperationException("form actions are not supported by Sensible Studio yet: " + actionType);
    }
}
