/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.components;

import java.util.Locale;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Evaluates {@code ${...}} expressions in dialog definitions.
 */
public interface ExpressionResolver {

    /**
     * @return the expression's value converted to {@code type}, or {@code null}
     */
    <T> T resolve(String expression, Locale locale, Class<T> type, SlingHttpServletRequest request);
}
