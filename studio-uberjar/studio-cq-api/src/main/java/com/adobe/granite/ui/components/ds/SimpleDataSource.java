/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.ds;

import java.util.Iterator;

import org.apache.sling.api.resource.Resource;

/**
 * A data source over a single-use iterator.
 */
public class SimpleDataSource implements DataSource {

    private final Iterator<Resource> items;

    public SimpleDataSource(Iterator<Resource> items) {
        this.items = items;
    }

    @Override
    public Iterator<Resource> iterator() {
        return items;
    }
}
