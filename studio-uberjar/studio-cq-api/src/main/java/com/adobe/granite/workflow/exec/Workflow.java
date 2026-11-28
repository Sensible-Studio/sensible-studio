/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec;

import java.util.Date;
import java.util.List;

import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.adobe.granite.workflow.model.WorkflowModel;

/** A running (or finished) workflow instance. */
public interface Workflow extends com.adobe.granite.workflow.HasMetaData {

    /** @return the instance path, e.g. {@code /var/workflow/instances/server0/2026-10-17/my_model_1} */
    String getId();

    /** @return the active work items */
    List<WorkItem> getWorkItems();

    /** The waiting steps of this workflow that the filter lets through. */
    List<WorkItem> getWorkItems(com.adobe.granite.workflow.exec.filter.WorkItemFilter filter);

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
