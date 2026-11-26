/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import javax.servlet.ServletRequest;

/**
 * Rendering mode of a request. Publish-style rendering is {@link #DISABLED}, which is also the
 * value when nothing was set.
 */
public enum WCMMode {
    DISABLED, EDIT, PREVIEW, DESIGN, READ_ONLY, ANALYTICS;

    /** Request attribute holding the mode. */
    public static final String REQUEST_ATTRIBUTE_NAME = WCMMode.class.getName();

    /**
     * @param request current request
     * @return the mode stored on the request, {@link #DISABLED} if none
     */
    public static WCMMode fromRequest(ServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE_NAME);
        return value instanceof WCMMode ? (WCMMode) value : DISABLED;
    }

    /**
     * Stores this mode on the request.
     *
     * @param request current request
     * @return the previous mode
     */
    public WCMMode toRequest(ServletRequest request) {
        WCMMode previous = fromRequest(request);
        request.setAttribute(REQUEST_ATTRIBUTE_NAME, this);
        return previous;
    }
}
