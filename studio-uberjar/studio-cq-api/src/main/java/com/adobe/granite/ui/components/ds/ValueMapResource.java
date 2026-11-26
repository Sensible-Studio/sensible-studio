/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.ds;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

import org.apache.sling.api.resource.AbstractResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * A synthetic resource backed by a value map, used as a data source item.
 */
public class ValueMapResource extends AbstractResource {

    private final ResourceResolver resolver;
    private final ResourceMetadata metadata;
    private final String path;
    private final String resourceType;
    private final ValueMap valueMap;
    private final Collection<Resource> children;

    public ValueMapResource(ResourceResolver resolver, ResourceMetadata metadata, String resourceType, ValueMap vm) {
        this(resolver, metadata == null ? "" : metadata.getResolutionPath(), resourceType, vm, Collections.emptyList());
    }

    public ValueMapResource(ResourceResolver resolver, String path, String resourceType, ValueMap vm) {
        this(resolver, path, resourceType, vm, Collections.emptyList());
    }

    public ValueMapResource(ResourceResolver resolver, String path, String resourceType, ValueMap vm,
            Collection<Resource> children) {
        this.resolver = resolver;
        this.path = path == null ? "" : path;
        this.resourceType = resourceType;
        this.valueMap = vm == null ? ValueMap.EMPTY : vm;
        this.children = children == null ? Collections.emptyList() : children;
        this.metadata = new ResourceMetadata();
        this.metadata.setResolutionPath(this.path);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getResourceType() {
        return resourceType;
    }

    @Override
    public String getResourceSuperType() {
        return null;
    }

    @Override
    public ResourceMetadata getResourceMetadata() {
        return metadata;
    }

    @Override
    public ResourceResolver getResourceResolver() {
        return resolver;
    }

    @Override
    public ValueMap getValueMap() {
        return valueMap;
    }

    @Override
    public Iterator<Resource> listChildren() {
        return children.iterator();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if (type == ValueMap.class) {
            return (AdapterType) valueMap;
        }
        return super.adaptTo(type);
    }
}
