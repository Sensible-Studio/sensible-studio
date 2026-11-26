/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.translation.api;

/** A translation failed. */
public class TranslationException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why it failed. */
    public enum ErrorCode {
        GENERAL,
        MISSING_CREDENTIALS,
        SERVICE_NOT_AVAILABLE,
        REQUEST_FAILED,
        NOT_SUPPORTED_LANG_DIRECTION,
        NO_ENGINE,
        INVALID_RESPONSE,
        CONTENT_DAMAGED
    }

    private final ErrorCode errorCode;

    public TranslationException(String message, ErrorCode errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public TranslationException(String message, Throwable cause, ErrorCode errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
