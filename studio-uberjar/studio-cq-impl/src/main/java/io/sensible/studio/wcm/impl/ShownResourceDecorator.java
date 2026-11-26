/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceDecorator;
import org.osgi.service.component.annotations.Component;

import io.sensible.studio.wcm.shown.Shown;

/**
 * Sees every resource the resource resolver hands out, and tells a page's recording ({@link Shown}) when
 * one is in another page's content: what a component reads of other pages without the Page API. It
 * decorates nothing; outside a recording it costs a thread-local look-up.
 */
@Component(service = ResourceDecorator.class)
public class ShownResourceDecorator implements ResourceDecorator {

    @Override
    public Resource decorate(Resource resource) {
        if (resource != null && Shown.recording()) {
            Shown.content(resource.getPath());
        }
        return null; // nothing decorated
    }

    @Override
    @Deprecated
    public Resource decorate(Resource resource, HttpServletRequest request) {
        return decorate(resource);
    }
}
