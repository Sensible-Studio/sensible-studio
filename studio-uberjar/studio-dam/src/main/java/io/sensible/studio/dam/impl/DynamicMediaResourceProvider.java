/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.Iterator;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.SyntheticResource;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Makes every path below {@code /is/image} a resource of type {@value #TYPE}, so a URL without
 * an extension ({@code /is/image/wknd/en/surf?wid=800}) reaches {@link DynamicMediaServlet}:
 * Sling only looks for a servlet above a missing path when the URL has a dot in it.
 */
@Component(service = ResourceProvider.class, property = {
        ResourceProvider.PROPERTY_ROOT + "=" + DynamicMedia.PREFIX,
        ResourceProvider.PROPERTY_NAME + "=studio-dynamic-media"})
public class DynamicMediaResourceProvider extends ResourceProvider<Object> {

    static final String TYPE = "studio/dam/dm/image";

    @Override
    public Resource getResource(ResolveContext<Object> ctx, String path, ResourceContext resourceContext, Resource parent) {
        if (path.equals(DynamicMedia.PREFIX) || path.startsWith(DynamicMedia.PREFIX + "/")) {
            return new SyntheticResource(ctx.getResourceResolver(), path, TYPE);
        }
        return null;
    }

    @Override
    public Iterator<Resource> listChildren(ResolveContext<Object> ctx, Resource parent) {
        return null;
    }
}
