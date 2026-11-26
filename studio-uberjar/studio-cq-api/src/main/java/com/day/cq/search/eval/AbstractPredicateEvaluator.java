/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search.eval;

import java.util.Comparator;

import javax.jcr.query.Row;

import com.day.cq.search.Predicate;

/**
 * Neutral defaults for {@link PredicateEvaluator}: no constraint, includes everything, no order.
 */
public abstract class AbstractPredicateEvaluator implements PredicateEvaluator {

    @Override
    public String getXPathExpression(Predicate predicate, EvaluationContext context) {
        return null;
    }

    @Override
    public boolean includes(Predicate predicate, Row row, EvaluationContext context) {
        return true;
    }

    @Override
    public boolean canFilter(Predicate predicate, EvaluationContext context) {
        return false;
    }

    @Override
    public boolean canXpath(Predicate predicate, EvaluationContext context) {
        return true;
    }

    @Override
    public Comparator<Row> getOrderByComparator(Predicate predicate, EvaluationContext context) {
        return null;
    }

    @Override
    public String[] getOrderByProperties(Predicate predicate, EvaluationContext context) {
        return null;
    }
}
