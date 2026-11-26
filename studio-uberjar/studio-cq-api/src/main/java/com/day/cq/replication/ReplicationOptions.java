/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.replication;

/** Options of a replication request. */
public class ReplicationOptions {

    private boolean synchronous;
    private boolean suppressStatusUpdate;
    private boolean suppressVersions;
    private AgentFilter filter = AgentFilter.DEFAULT;
    private boolean deep;

    public boolean isSynchronous() {
        return synchronous;
    }

    /** Deliver before returning instead of queueing. */
    public void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    public boolean isSuppressStatusUpdate() {
        return suppressStatusUpdate;
    }

    /** Leave the {@code cq:lastReplicated*} properties as they are. */
    public void setSuppressStatusUpdate(boolean suppressStatusUpdate) {
        this.suppressStatusUpdate = suppressStatusUpdate;
    }

    public boolean isSuppressVersions() {
        return suppressVersions;
    }

    /** Do not save a version of the content before it is activated. */
    public void setSuppressVersions(boolean suppressVersions) {
        this.suppressVersions = suppressVersions;
    }

    public AgentFilter getFilter() {
        return filter;
    }

    public void setFilter(AgentFilter filter) {
        this.filter = filter == null ? AgentFilter.DEFAULT : filter;
    }

    public boolean isDeep() {
        return deep;
    }

    /** Pages: also the child pages (tree activation). */
    public void setDeep(boolean deep) {
        this.deep = deep;
    }
}
