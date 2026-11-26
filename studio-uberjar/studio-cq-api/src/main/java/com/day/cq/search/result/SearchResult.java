/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search.result;

import java.util.Iterator;

import org.apache.sling.api.resource.Resource;

/**
 * The result of a {@link com.day.cq.search.Query}.
 */
public interface SearchResult {

    /** @return the matching resources of the current page of hits */
    Iterator<Resource> getResources();

    /** @return total number of matches (may be an estimate) */
    long getTotalMatches();

    /** @return the executed repository query, for debugging */
    String getQueryStatement();
}
