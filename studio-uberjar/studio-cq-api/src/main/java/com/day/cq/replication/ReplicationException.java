/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

/** A replication request that could not be accepted or carried out. */
public class ReplicationException extends Exception {

    private static final long serialVersionUID = 1L;

    public ReplicationException(String message) {
        super(message);
    }

    public ReplicationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReplicationException(Throwable cause) {
        super(cause);
    }
}
