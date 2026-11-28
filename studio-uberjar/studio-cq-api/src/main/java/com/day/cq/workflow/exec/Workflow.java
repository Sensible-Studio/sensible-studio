/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import java.util.Date;
import java.util.List;

import com.day.cq.workflow.metadata.MetaDataMap;
import com.day.cq.workflow.model.WorkflowModel;

/** A running (or finished) workflow instance. */
public interface Workflow {

    /** @return the instance path, e.g. {@code /var/workflow/instances/server0/2026-10-17/my_model_1} */
    String getId();

    /** @return the active work items */
    List<WorkItem> getWorkItems();

    WorkflowModel getWorkflowModel();

    boolean isActive();

    /** @return {@code RUNNING}, {@code COMPLETED}, {@code ABORTED} or {@code SUSPENDED} */
    String getState();

    String getInitiator();

    Date getTimeStarted();

    Date getTimeEnded();

    WorkflowData getWorkflowData();

    MetaDataMap getMetaDataMap();
}
