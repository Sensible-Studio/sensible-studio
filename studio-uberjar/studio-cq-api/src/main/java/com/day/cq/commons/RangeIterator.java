/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

import java.util.Iterator;

/**
 * An iterator that knows its size and position.
 */
public interface RangeIterator<T> extends Iterator<T> {

    /** Skips {@code skipNum} elements. */
    void skip(long skipNum);

    /** @return number of elements, {@code -1} if unknown */
    long getSize();

    /** @return current position, starting at 0 */
    long getPosition();
}
