/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.ds;

import java.util.Collections;
import java.util.Iterator;

import org.apache.sling.api.resource.Resource;

/**
 * A data source without items.
 */
public final class EmptyDataSource implements DataSource {

    private static final EmptyDataSource INSTANCE = new EmptyDataSource();

    private EmptyDataSource() {
    }

    public static DataSource instance() {
        return INSTANCE;
    }

    @Override
    public Iterator<Resource> iterator() {
        return Collections.emptyIterator();
    }
}
