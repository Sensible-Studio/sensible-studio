/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletRequest;

/**
 * Extra variables for expression evaluation, stored on the request.
 */
public final class ExpressionCustomizer {

    private static final String ATTR = ExpressionCustomizer.class.getName();

    private final Map<String, Object> variables = new HashMap<>();

    private ExpressionCustomizer() {
    }

    /** @return the customizer of the request, created on first use */
    public static ExpressionCustomizer from(ServletRequest request) {
        Object o = request.getAttribute(ATTR);
        if (o instanceof ExpressionCustomizer) {
            return (ExpressionCustomizer) o;
        }
        ExpressionCustomizer c = new ExpressionCustomizer();
        request.setAttribute(ATTR, c);
        return c;
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public boolean hasVariable(String name) {
        return variables.containsKey(name);
    }
}
