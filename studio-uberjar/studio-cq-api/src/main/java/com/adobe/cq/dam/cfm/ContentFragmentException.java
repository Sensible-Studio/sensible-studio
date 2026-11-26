/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

/**
 * Content fragment failure.
 */
public class ContentFragmentException extends Exception {

    private static final long serialVersionUID = 1L;

    public ContentFragmentException(String message) {
        super(message);
    }

    public ContentFragmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
