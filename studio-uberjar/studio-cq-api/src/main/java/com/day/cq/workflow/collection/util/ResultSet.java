/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.collection.util;

/** One page of a longer list, with the length of the whole list. */
public interface ResultSet<T> {

    /** The items of this page. */
    T[] getItems();

    /** How many there are in all, not only on this page. */
    long getTotalSize();
}
