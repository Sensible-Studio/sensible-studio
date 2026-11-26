/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * Read access to a dialog field's configuration resource.
 */
public class Config {

    /** Child node that names a data source. */
    public static final String DATASOURCE = "datasource";

    private final Resource resource;
    private final ValueMap props;

    public Config(Resource resource) {
        this.resource = resource;
        this.props = resource == null ? ValueMap.EMPTY : resource.getValueMap();
    }

    public <T> T get(String name, Class<T> type) {
        return props.get(name, type);
    }

    public <T> T get(String name, T defaultValue) {
        return props.get(name, defaultValue);
    }

    /** @return the child resource, or {@code null} */
    public Resource getChild(String name) {
        return resource == null ? null : resource.getChild(name);
    }

    public ValueMap getProperties() {
        return props;
    }
}
