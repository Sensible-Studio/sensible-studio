/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.wcm.style;

/**
 * Style System information of a component instance. Adapt the component resource to it.
 */
public interface ComponentStyleInfo {

    /** @return the CSS classes of the styles the author applied, space separated, or {@code null} */
    String getAppliedCssClasses();
}
