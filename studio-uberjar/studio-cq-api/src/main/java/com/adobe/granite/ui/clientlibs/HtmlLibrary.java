/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.clientlibs;

import java.io.IOException;
import java.io.InputStream;

/**
 * The built output of one client library for one type: its own files plus embedded libraries.
 */
public interface HtmlLibrary {

    /** @return the output type */
    LibraryType getType();

    /** @return path of the library folder */
    String getLibraryPath();

    /** @return the path the output is requested at, e.g. {@code /etc.clientlibs/wknd/clientlibs/clientlib-site.css} */
    String getPath();

    /** @return library name */
    String getName();

    /** @return last modification time of any input, in milliseconds */
    long getLastModified();

    /**
     * @param minified {@code true} for the minified variant (Sensible Studio currently serves the same bytes)
     * @return the content
     */
    InputStream getInputStream(boolean minified) throws IOException;

    /** @return same as {@code getInputStream(false)} */
    InputStream getInputStream() throws IOException;
}
