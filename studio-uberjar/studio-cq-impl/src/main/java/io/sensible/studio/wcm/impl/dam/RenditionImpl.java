/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.dam;

import java.io.InputStream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;

/**
 * A rendition is the {@code nt:file} resource itself, so it can be passed wherever a resource is
 * expected (e.g. streamed by the default GET servlet).
 */
public final class RenditionImpl extends ResourceWrapper implements Rendition {

    private final Asset asset;

    public RenditionImpl(Resource resource, Asset asset) {
        super(resource);
        this.asset = asset;
    }

    private ValueMap content() {
        Resource c = getResource().getChild("jcr:content");
        return c == null ? ValueMap.EMPTY : c.getValueMap();
    }

    @Override
    public String getMimeType() {
        return content().get("jcr:mimeType", String.class);
    }

    @Override
    public ValueMap getProperties() {
        return content();
    }

    @Override
    public long getSize() {
        long len = getResource().getResourceMetadata().getContentLength();
        if (len > 0) {
            return len;
        }
        Resource c = getResource().getChild("jcr:content");
        return c == null ? -1 : c.getResourceMetadata().getContentLength();
    }

    @Override
    public InputStream getStream() {
        InputStream in = getResource().adaptTo(InputStream.class);
        if (in == null) {
            Resource c = getResource().getChild("jcr:content");
            in = c == null ? null : c.adaptTo(InputStream.class);
        }
        return in;
    }

    @Override
    public Asset getAsset() {
        return asset;
    }
}
