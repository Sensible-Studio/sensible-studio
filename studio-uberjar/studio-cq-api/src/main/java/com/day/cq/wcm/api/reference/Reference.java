/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.reference;

import org.apache.sling.api.resource.Resource;

/**
 * Something a page references (an asset, a configuration, ...), shown when publishing.
 */
public class Reference {

    private final String type;
    private final String name;
    private final Resource resource;
    private final long lastModified;

    public Reference(String type, String name, Resource resource, long lastModified) {
        this.type = type;
        this.name = name;
        this.resource = resource;
        this.lastModified = lastModified;
    }

    public String getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public Resource getResource() {
        return resource;
    }

    public long getLastModified() {
        return lastModified;
    }
}
