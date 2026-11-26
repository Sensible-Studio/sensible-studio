/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

final class WcmTestSupport {

    static final String EN = "/content/wknd/us/en";
    static final String TEMPLATE = "/conf/wknd/settings/wcm/templates/page-content";

    private WcmTestSupport() {
    }

    static SlingContext newContext() {
        return new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    }

    /** Registers the Sensible Studio services the same way the OSGi runtime would and loads the fixture. */
    static void setUp(SlingContext context) {
        context.registerInjectActivateService(new WcmAdapterFactory());
        context.registerInjectActivateService(new PageManagerFactoryImpl());
        context.load().json("/wknd-content.json", "/content");
        context.load().json("/wknd-conf.json", "/conf");
        context.load().json("/wknd-apps.json", "/apps");
    }
}
