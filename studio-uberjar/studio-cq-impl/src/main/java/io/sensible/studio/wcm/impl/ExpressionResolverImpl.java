/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Locale;

import org.apache.sling.api.SlingHttpServletRequest;
import org.osgi.service.component.annotations.Component;

import com.adobe.granite.ui.components.ExpressionResolver;

/**
 * Minimal expression support for dialog definitions: literal values pass through, and
 * {@code ${requestPathInfo.suffix}} / {@code ${requestPathInfo.resourcePath}} are resolved.
 * Other expressions resolve to {@code null}.
 */
@Component(service = ExpressionResolver.class)
public class ExpressionResolverImpl implements ExpressionResolver {

    @Override
    public <T> T resolve(String expression, Locale locale, Class<T> type, SlingHttpServletRequest request) {
        if (expression == null) {
            return null;
        }
        String value = expression;
        if (expression.contains("${")) {
            value = expression
                    .replace("${requestPathInfo.suffix}", nz(request.getRequestPathInfo().getSuffix()))
                    .replace("${requestPathInfo.resourcePath}", nz(request.getRequestPathInfo().getResourcePath()));
            if (value.contains("${")) {
                return null;
            }
        }
        if (type == String.class || type == Object.class) {
            return type.cast(value);
        }
        if (type == Boolean.class) {
            return type.cast(Boolean.valueOf(value));
        }
        if (type == Integer.class) {
            try {
                return type.cast(Integer.valueOf(value.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
