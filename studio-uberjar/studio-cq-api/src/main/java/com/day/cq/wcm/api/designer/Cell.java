/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.designer;

import java.util.Iterator;

/**
 * Identifies a position in the component hierarchy for style lookup, e.g.
 * {@code page/root/container/title}.
 */
public interface Cell {

    /** @return the name of this cell (usually the component name) */
    String getName();

    /** @return the parent cell, or {@code null} */
    Cell getParent();

    /** @return the cell path, segments joined with {@code /} */
    String getPath();

    /** @return candidate style paths, most specific first */
    Iterator<String> paths();
}
