/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import com.day.cq.search.result.SearchResult;

/**
 * Full-text search helper (adapt a resource to it).
 */
public interface SimpleSearch {

    void setQuery(String query);

    void setSearchIn(String path);

    void setHitsPerPage(long hits);

    void addPredicate(Predicate predicate);

    SearchResult getResult();
}
