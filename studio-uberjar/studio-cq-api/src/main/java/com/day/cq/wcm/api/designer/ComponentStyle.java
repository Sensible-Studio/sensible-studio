/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api.designer;

import java.util.Map;

/**
 * Style-system definition of a component: the style groups and styles authors can choose from.
 */
public interface ComponentStyle {

    /** @return the component path this definition is for */
    String getPath();

    /** @return style id to CSS classes */
    Map<String, String> getStyles();
}
