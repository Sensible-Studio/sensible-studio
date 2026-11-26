/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.designer;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * The design configuration that applies to a component instance. With editable templates this is
 * backed by the component's content policy; Core Components read it as {@code currentStyle}.
 */
public interface Style extends ValueMap {

    /** @return the design this style belongs to, or {@code null} */
    Design getDesign();

    /** @return path of the resource holding the style properties, or {@code null} */
    String getPath();

    /** @return the cell (position in the design) this style was resolved for, or {@code null} */
    Cell getCell();

    /**
     * @param relPath path relative to the style resource
     * @return that resource, or {@code null}
     */
    Resource getDefiningResource(String relPath);

    /**
     * @param name property name
     * @return path of the resource that defines the property, or {@code null}
     */
    String getDefiningPath(String name);

    /**
     * @param relPath path relative to this style
     * @return a style for the sub-path; never {@code null}
     */
    Style getSubStyle(String relPath);
}
