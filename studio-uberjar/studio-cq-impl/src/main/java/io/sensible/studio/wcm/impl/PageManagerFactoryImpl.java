/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.PageManagerFactory;

/**
 * OSGi service for code that injects {@code @Reference PageManagerFactory}.
 */
@Component(service = PageManagerFactory.class)
public class PageManagerFactoryImpl implements PageManagerFactory {

    @Override
    public PageManager getPageManager(ResourceResolver resolver) {
        return resolver == null ? null : new PageManagerImpl(resolver);
    }
}
