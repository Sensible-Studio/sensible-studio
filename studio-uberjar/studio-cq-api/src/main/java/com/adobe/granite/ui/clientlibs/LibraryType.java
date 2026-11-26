/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.ui.clientlibs;

/**
 * Kinds of client library output.
 */
public enum LibraryType {
    JS(".js", "application/javascript"),
    CSS(".css", "text/css");

    public final String extension;
    public final String contentType;

    LibraryType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    /** @return the type for an extension such as {@code .css} or {@code css}, or {@code null} */
    public static LibraryType fromExtension(String ext) {
        for (LibraryType t : values()) {
            if (t.extension.equals(ext) || t.extension.equals("." + ext)) {
                return t;
            }
        }
        return null;
    }
}
