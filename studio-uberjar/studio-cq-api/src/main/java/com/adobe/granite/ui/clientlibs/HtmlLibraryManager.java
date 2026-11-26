/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.clientlibs;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Map;

import org.apache.sling.api.SlingHttpServletRequest;

/**
 * OSGi service that indexes client libraries and writes the {@code <link>} and {@code <script>}
 * tags for categories.
 */
public interface HtmlLibraryManager {

    /** Writes {@code <link>} and {@code <script>} tags for the categories (with dependencies). */
    void writeIncludes(SlingHttpServletRequest request, Writer out, String... categories) throws IOException;

    /** Writes {@code <link rel="stylesheet">} tags for the categories (with dependencies). */
    void writeCssInclude(SlingHttpServletRequest request, Writer out, String... categories) throws IOException;

    /** Writes {@code <script>} tags for the categories (with dependencies). */
    void writeJsInclude(SlingHttpServletRequest request, Writer out, String... categories) throws IOException;

    /** @return all libraries by path */
    Map<String, ClientLibrary> getLibraries();

    /**
     * @param categories   categories to look up
     * @param type         output type, {@code null} for any
     * @param ignoreThemed ignored by Sensible Studio
     * @param transitive   {@code true} to include dependencies
     * @return matching libraries, dependencies first
     */
    Collection<ClientLibrary> getLibraries(String[] categories, LibraryType type, boolean ignoreThemed,
            boolean transitive);

    /**
     * @param type output type
     * @param path library folder path, or its {@code /etc.clientlibs} proxy path
     * @return the built library, or {@code null}
     */
    HtmlLibrary getLibrary(LibraryType type, String path);

    /** @return {@code true} if minified output is requested by default */
    boolean isMinifyEnabled();

    /** @return {@code true} if debug output (one tag per file) is enabled */
    boolean isDebugEnabled();
}
