/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.clientlibs;

import java.util.Map;
import java.util.Set;

/**
 * A client library folder ({@code cq:ClientLibraryFolder}).
 */
public interface ClientLibrary {

    /** @return path of the folder, e.g. {@code /apps/wknd/clientlibs/clientlib-site} */
    String getPath();

    /** @return the categories it belongs to */
    String[] getCategories();

    /** @return categories embedded into this library's output */
    String[] getEmbeddedCategories();

    /** @return categories that must be included before this library */
    String[] getDependentCategories();

    /** @return {@code true} if it may be served through {@code /etc.clientlibs} */
    boolean allowProxy();

    /** @return the output types this library has content for */
    Set<LibraryType> getTypes();

    /** @return libraries this one depends on, by path */
    Map<String, ? extends ClientLibrary> getDependencies(boolean transitive);

    /** @return libraries embedded into this one, by path */
    Map<String, ? extends ClientLibrary> getEmbedded(LibraryType type);
}
