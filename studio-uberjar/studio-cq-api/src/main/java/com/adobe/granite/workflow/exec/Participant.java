/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec;

/** A user or a group a step can be given to. */
public interface Participant {

    /** The ID of the user or group. */
    String getID();

    String getName();

    boolean isGroup();
}
