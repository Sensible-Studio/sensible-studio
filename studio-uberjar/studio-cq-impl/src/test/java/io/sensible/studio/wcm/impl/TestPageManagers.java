/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.PageManager;

/** Sensible Studio's page manager for tests in other packages. */
public final class TestPageManagers {

    private TestPageManagers() {
    }

    public static PageManager of(ResourceResolver resolver) {
        return new PageManagerImpl(resolver);
    }
}
