/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search.eval;

import java.util.Comparator;

import javax.jcr.query.Row;

import com.day.cq.search.Predicate;

/**
 * Translates one predicate type into a query constraint and/or filters and orders rows.
 */
public interface PredicateEvaluator {

    /** @return an XPath constraint, or {@code null} */
    String getXPathExpression(Predicate predicate, EvaluationContext context);

    /** @return {@code true} if the row matches (post-filtering) */
    boolean includes(Predicate predicate, Row row, EvaluationContext context);

    /** @return {@code true} if {@link #includes} must be called */
    boolean canFilter(Predicate predicate, EvaluationContext context);

    /** @return {@code true} if the XPath expression fully expresses the predicate */
    boolean canXpath(Predicate predicate, EvaluationContext context);

    /** @return a comparator for ordering, or {@code null} */
    Comparator<Row> getOrderByComparator(Predicate predicate, EvaluationContext context);

    /** @return order-by properties, or {@code null} */
    String[] getOrderByProperties(Predicate predicate, EvaluationContext context);
}
