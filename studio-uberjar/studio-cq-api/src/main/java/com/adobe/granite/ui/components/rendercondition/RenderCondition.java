/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.rendercondition;

/**
 * Decides whether a dialog field is shown; set as a request attribute by a condition script.
 */
public interface RenderCondition {

    boolean check();
}
