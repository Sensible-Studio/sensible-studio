/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.security;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Validates that a form POST only writes allowed parameters.
 */
public interface SaferSlingPostValidator {

    /**
     * @param request      the POST
     * @param allowedParams parameter names that may be written
     * @return {@code true} if the request is safe to forward to the Sling POST servlet
     */
    boolean reject(SlingHttpServletRequest request, String[] allowedParams);
}
