/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.model;

import com.adobe.granite.workflow.WorkflowException;

/** A model that cannot run as it is: what is wrong is the message. */
public class ValidationException extends WorkflowException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
