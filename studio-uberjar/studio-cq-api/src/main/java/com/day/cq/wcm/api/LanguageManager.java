/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

import java.util.Locale;

import org.apache.sling.api.resource.Resource;

/**
 * Finds language roots (for example {@code /content/wknd/us/en}) and content languages.
 * Available as an OSGi service.
 */
public interface LanguageManager {

    /**
     * @param resource any resource in a site
     * @return the nearest page at or above it whose name is a language code, or {@code null}
     */
    Page getLanguageRoot(Resource resource);

    /**
     * @param resource      any resource in a site
     * @param respectContent {@code true} to also honour {@code jcr:language} on page content
     * @return the language root page, or {@code null}
     */
    Page getLanguageRoot(Resource resource, boolean respectContent);

    /**
     * @param resource      any resource in a site
     * @param respectContent {@code true} to prefer {@code jcr:language} over the path
     * @return the content language, never {@code null}
     */
    Locale getLanguage(Resource resource, boolean respectContent);
}
