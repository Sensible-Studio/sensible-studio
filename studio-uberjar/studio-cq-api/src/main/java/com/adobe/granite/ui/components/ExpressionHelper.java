/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Convenience wrapper around {@link ExpressionResolver} bound to a request.
 */
public class ExpressionHelper {

    private final ExpressionResolver resolver;
    private final SlingHttpServletRequest request;

    public ExpressionHelper(ExpressionResolver resolver, SlingHttpServletRequest request) {
        this.resolver = resolver;
        this.request = request;
    }

    public <T> T get(String expression, Class<T> type) {
        if (expression == null) {
            return null;
        }
        if (resolver == null) {
            return type.isInstance(expression) ? type.cast(expression) : null;
        }
        return resolver.resolve(expression, request.getLocale(), type, request);
    }

    public String getString(String expression) {
        return get(expression, String.class);
    }

    public boolean getBoolean(String expression) {
        Boolean b = get(expression, Boolean.class);
        return b != null && b;
    }
}
