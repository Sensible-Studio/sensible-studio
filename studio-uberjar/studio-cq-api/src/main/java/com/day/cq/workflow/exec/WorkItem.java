/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import java.util.Date;

import com.day.cq.workflow.metadata.MetaDataMap;
import com.day.cq.workflow.model.WorkflowNode;

/** One step of a running workflow: a process being executed or a task waiting for a participant. */
public interface WorkItem {

    String getId();

    Date getTimeStarted();

    Date getTimeEnded();

    Workflow getWorkflow();

    WorkflowNode getNode();

    WorkflowData getWorkflowData();

    /** The user or group the item is assigned to (participant steps). */
    String getCurrentAssignee();

    MetaDataMap getMetaDataMap();

    /** The kind of step: {@code PROCESS}, {@code PARTICIPANT} or {@code DYNAMIC_PARTICIPANT}. */
    String getItemType();

    /** A finer kind a step may name in its {@code subType}; mostly none. */
    String getItemSubType();

    /** When the step is due, when it has a timeout. */
    Date getDueTime();
}
