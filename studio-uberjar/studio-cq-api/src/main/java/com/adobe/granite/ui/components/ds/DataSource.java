/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.ds;

import java.util.Iterator;

import org.apache.sling.api.resource.Resource;

/**
 * Items for a dialog field (select options, ...), set as a request attribute by a data source
 * servlet.
 */
public interface DataSource extends Iterable<Resource> {

    @Override
    Iterator<Resource> iterator();
}
