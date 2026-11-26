/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components.rendercondition;

/**
 * A fixed render condition.
 */
public class SimpleRenderCondition implements RenderCondition {

    private final boolean value;

    public SimpleRenderCondition(boolean value) {
        this.value = value;
    }

    @Override
    public boolean check() {
        return value;
    }
}
