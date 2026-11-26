/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.wrappers.SlingHttpServletRequestWrapper;

/**
 * Request wrapper used while a form submission is processed (GET semantics for forwards).
 */
public class FormsHandlingRequest extends SlingHttpServletRequestWrapper {

    public FormsHandlingRequest(SlingHttpServletRequest request) {
        super(request);
    }

    @Override
    public String getMethod() {
        return "GET";
    }
}
