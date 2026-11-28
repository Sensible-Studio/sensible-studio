/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow;

import java.util.List;
import java.util.Map;

import org.apache.sling.api.adapter.Adaptable;

import com.adobe.granite.workflow.exec.Route;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.model.WorkflowModel;

/**
 * Entry point to workflows, obtained by adapting a {@code ResourceResolver}. It acts with the rights of the user the
 * resolver belongs to; adapt it to {@code ResourceResolver} or {@code javax.jcr.Session} to reach the repository.
 */
public interface WorkflowSession extends Adaptable {

    /** @return the deployed (runtime) workflow models */
    WorkflowModel[] getModels() throws WorkflowException;

    /** @param id the runtime model path, e.g. {@code /var/workflow/models/request_for_activation} */
    WorkflowModel getModel(String id) throws WorkflowException;

    Workflow startWorkflow(WorkflowModel model, WorkflowData data) throws WorkflowException;

    Workflow startWorkflow(WorkflowModel model, WorkflowData data, Map<String, Object> metaData) throws WorkflowException;

    /** @param payloadType {@code JCR_PATH} or {@code URL} */
    WorkflowData newWorkflowData(String payloadType, Object payload);

    Workflow getWorkflow(String id) throws WorkflowException;

    /** @param states e.g. {@code RUNNING}, {@code COMPLETED}, {@code ABORTED}, {@code SUSPENDED}; empty for all */
    Workflow[] getWorkflows(String[] states) throws WorkflowException;

    void terminateWorkflow(Workflow workflow) throws WorkflowException;

    void suspendWorkflow(Workflow workflow) throws WorkflowException;

    void resumeWorkflow(Workflow workflow) throws WorkflowException;

    /** The active work items assigned to the session's user or one of its groups. */
    WorkItem[] getActiveWorkItems() throws WorkflowException;

    WorkItem getWorkItem(String id) throws WorkflowException;

    /** The ways a work item can be completed; with {@code expand}, an OR split after it is resolved to its branches. */
    List<Route> getRoutes(WorkItem item, boolean expand) throws WorkflowException;

    /** Completes a work item and moves the workflow on along {@code route} (null: the default route). */
    void complete(WorkItem item, Route route) throws WorkflowException;

    void updateWorkflowData(Workflow workflow, WorkflowData data) throws WorkflowException;

    /** What happened in a workflow so far, oldest first. */
    List<com.adobe.granite.workflow.exec.HistoryItem> getHistory(Workflow workflow) throws WorkflowException;

    /** Gives a waiting step to somebody else. Whoever has the step may, and an administrator. */
    void delegateWorkItem(WorkItem item, com.adobe.granite.workflow.exec.Participant participant) throws WorkflowException;

    /** The users and groups a step can be given to. */
    java.util.Iterator<com.adobe.granite.workflow.exec.Participant> getDelegates(WorkItem item) throws WorkflowException;

    /** Every waiting step the session's user may see: all of them for an administrator. */
    WorkItem[] getAllWorkItems() throws WorkflowException;

    /** Every workflow the session's user may see. */
    Workflow[] getAllWorkflows() throws WorkflowException;

    /** Runs the failed steps of a workflow again. Administrators only. */
    void restartWorkflow(Workflow workflow) throws WorkflowException;

    /**
     * A new, empty flow model (start, end) with this title, ready to be started. Give it steps in the flow
     * editor, or in code: {@code createNode}, {@code createTransition}, then {@link #deployModel}.
     */
    WorkflowModel createNewModel(String title) throws WorkflowException;

    /** The same, named: {@code id} is the model's name or its full id under {@code /var/workflow/models}. */
    WorkflowModel createNewModel(String title, String id) throws WorkflowException;

    /** Removes a model made here, design and runtime. Models that came with the product stay. */
    void deleteModel(String id) throws WorkflowException;

    /**
     * Makes the model what flows run on. A model changed in code is written as it is, and is from then on a
     * model without a design: listed and started like the others, not opened in the flow editor. An unchanged
     * one is written anew from its design, which is what Sync does in the editor.
     */
    void deployModel(WorkflowModel model) throws WorkflowException;

    /** The model, when {@code version} is its current version; older versions are not kept apart. */
    WorkflowModel getModel(String id, String version) throws WorkflowException;

    /**
     * The earlier steps of a flow a waiting step can be sent back to: the participant steps already
     * completed, the latest first. Completing the item along one of them makes that step wait again.
     */
    List<Route> getBackRoutes(WorkItem item, boolean expand) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<WorkItem> getActiveWorkItems(long start, long limit) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<WorkItem> getAllWorkItems(long start, long limit) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<Workflow> getWorkflows(String[] states, long start, long limit) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<WorkflowModel> getModels(long start, long limit) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<WorkItem> getActiveWorkItems(long start, long limit,
            com.adobe.granite.workflow.exec.filter.WorkItemFilter filter) throws WorkflowException;

    WorkflowModel[] getModels(com.adobe.granite.workflow.model.WorkflowModelFilter filter) throws WorkflowException;

    com.adobe.granite.workflow.collection.util.ResultSet<WorkflowModel> getModels(long start, long limit,
            com.adobe.granite.workflow.model.WorkflowModelFilter filter) throws WorkflowException;

    /** Whether the session's user is an administrator of workflows. */
    boolean isSuperuser();

    /** Nothing to release here: the session ends with the resolver it was adapted from. */
    void logout();
}
