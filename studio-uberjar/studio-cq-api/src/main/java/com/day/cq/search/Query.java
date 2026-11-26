/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import com.day.cq.search.eval.PredicateEvaluator;
import com.day.cq.search.result.SearchResult;

/**
 * A query created by {@link QueryBuilder}.
 */
public interface Query {

    SearchResult getResult();

    PredicateGroup getPredicates();

    void setStart(long start);

    void setHitsPerPage(long hitsPerPage);

    void registerPredicateEvaluator(String type, PredicateEvaluator evaluator);
}
