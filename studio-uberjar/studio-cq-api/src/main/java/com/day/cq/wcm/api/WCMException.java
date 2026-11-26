/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.api;

/**
 * Thrown by {@link PageManager} operations that fail.
 */
public class WCMException extends Exception {

    private static final long serialVersionUID = 1L;

    public WCMException(String message) {
        super(message);
    }

    public WCMException(String message, Throwable cause) {
        super(message, cause);
    }

    public WCMException(Throwable cause) {
        super(cause);
    }
}
