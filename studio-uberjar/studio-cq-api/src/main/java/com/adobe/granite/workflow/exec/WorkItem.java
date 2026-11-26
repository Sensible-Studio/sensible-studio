/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec;

import java.util.Date;

import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.adobe.granite.workflow.model.WorkflowNode;

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
}
