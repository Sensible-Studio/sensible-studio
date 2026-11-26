/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

/**
 * Generic predicate used when iterating content (pages, resources).
 *
 * @param <T> type of the element being tested
 */
@FunctionalInterface
public interface Filter<T> {

    /**
     * @param element element to test
     * @return {@code true} if the element passes the filter
     */
    boolean includes(T element);
}
