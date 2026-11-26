/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * OSGi service that hands out {@link PageManager} instances.
 */
public interface PageManagerFactory {

    /**
     * @param resolver resolver whose permissions the page manager uses
     * @return a page manager bound to the resolver
     */
    PageManager getPageManager(ResourceResolver resolver);
}
