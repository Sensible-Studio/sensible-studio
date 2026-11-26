/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

/** What a replication request asks an agent to do. */
public enum ReplicationActionType {
    /** Publish: the content is sent to the agent's target. */
    ACTIVATE("Activate"),
    /** Unpublish: the content is removed from the agent's target. */
    DEACTIVATE("Deactivate"),
    /** Removed on the author; the target removes it too. */
    DELETE("Delete"),
    /** Connectivity check of the agent; nothing is sent. */
    TEST("Test");

    private final String name;

    ReplicationActionType(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /** @return the type for its name ({@code Activate}) or constant ({@code ACTIVATE}); {@code null} if unknown */
    public static ReplicationActionType fromName(String name) {
        if (name == null) {
            return null;
        }
        for (ReplicationActionType t : values()) {
            if (t.name.equalsIgnoreCase(name) || t.name().equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }
}
