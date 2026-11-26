/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.i18n;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javax.servlet.http.HttpServletRequest;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * Translates UI strings through the Sling i18n resource bundles ({@code sling:MessageEntry} /
 * JSON dictionaries). Unknown keys are returned unchanged, so untranslated sites still render.
 */
public class I18n {

    private final ResourceBundle bundle;

    public I18n(ResourceBundle bundle) {
        this.bundle = bundle;
    }

    public I18n(HttpServletRequest request) {
        this(bundleFor(request, null));
    }

    private static ResourceBundle bundleFor(HttpServletRequest request, Locale locale) {
        if (request instanceof SlingHttpServletRequest) {
            return ((SlingHttpServletRequest) request).getResourceBundle(locale);
        }
        return null;
    }

    /** @return the translation of {@code text}, or {@code text} itself */
    public String get(String text) {
        return get(text, null);
    }

    /**
     * @param comment translator hint (ignored at runtime)
     * @return the translation of {@code text}, or {@code text} itself
     */
    public String get(String text, String comment) {
        return lookup(text);
    }

    /**
     * @param args values for {@code {0}}, {@code {1}}, ...
     * @return the formatted translation
     */
    public String get(String text, String comment, Object... args) {
        return format(lookup(text), args);
    }

    /** Same as {@link #get(String)} for keys that are not literal (computed at runtime). */
    public String getVar(String text) {
        return lookup(text);
    }

    public String getVar(String text, String comment) {
        return lookup(text);
    }

    public String getVar(String text, String comment, Object... args) {
        return format(lookup(text), args);
    }

    /** @return the underlying bundle, or {@code null} */
    public ResourceBundle getResourceBundle() {
        return bundle;
    }

    /** Static convenience: {@code new I18n(request).get(text)}. */
    public static String get(HttpServletRequest request, String text) {
        return new I18n(request).get(text);
    }

    private String lookup(String text) {
        if (text == null || bundle == null) {
            return text;
        }
        try {
            return bundle.getString(text);
        } catch (MissingResourceException e) {
            return text;
        }
    }

    private static String format(String pattern, Object... args) {
        if (pattern == null || args == null) {
            return pattern;
        }
        String out = pattern;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }
}
