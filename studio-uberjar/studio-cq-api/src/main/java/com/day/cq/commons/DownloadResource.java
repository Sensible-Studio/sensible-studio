/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;

/**
 * A component resource that points to a binary, either inline ({@code file} child) or by
 * reference ({@code fileReference} property to a DAM asset).
 */
public class DownloadResource extends ResourceWrapper implements LabeledResource {

    public static final String PN_REFERENCE = "fileReference";
    public static final String NN_FILE = "file";

    protected final ValueMap properties;

    public DownloadResource(Resource resource) {
        super(resource);
        this.properties = resource.getValueMap();
    }

    /** @return the referenced asset path, or {@code null} */
    public String getFileReference() {
        return properties.get(PN_REFERENCE, String.class);
    }

    /** @return the inline file resource, or {@code null} */
    public Resource getFileResource() {
        return getResource().getChild(NN_FILE);
    }

    /** @return {@code true} if there is a reference or an inline file */
    public boolean hasContent() {
        return getFileReference() != null || getFileResource() != null;
    }

    @Override
    public String getTitle() {
        return properties.get("jcr:title", String.class);
    }

    @Override
    public String getDescription() {
        return properties.get("jcr:description", String.class);
    }
}
