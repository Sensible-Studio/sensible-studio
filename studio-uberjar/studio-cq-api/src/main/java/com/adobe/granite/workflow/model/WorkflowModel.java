/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.model;

import java.util.List;

import com.adobe.granite.workflow.metadata.MetaDataMap;

/**
 * A runtime workflow model: nodes (steps, splits, joins, start and end) connected by transitions.
 */
public interface WorkflowModel extends com.adobe.granite.workflow.HasMetaData {

    /** @return the model path, e.g. {@code /var/workflow/models/request_for_activation} */
    String getId();

    String getTitle();

    String getDescription();

    String getVersion();

    void setTitle(String title);

    void setDescription(String description);

    /** A new process step without a title; set what it is on the node. */
    WorkflowNode createNode();

    /** A new step: {@code type} is one of the {@code WorkflowNode.TYPE_…}; what it runs or whom it is for goes into its metadata. */
    WorkflowNode createNode(String title, String type, String description);

    WorkflowTransition createTransition();

    /** Connects two steps; {@code rule} as on an OR branch, or null. */
    WorkflowTransition createTransition(WorkflowNode from, WorkflowNode to, String rule);

    void setRootNode(WorkflowNode node);

    void setEndNode(WorkflowNode node);

    /** Whether the model can run: a start, an end, every step reachable and leading on, every step saying what it does. */
    void validate() throws ValidationException;

    List<WorkflowNode> getNodes();

    WorkflowNode getNode(String id);

    List<WorkflowTransition> getTransitions();

    WorkflowNode getRootNode();

    WorkflowNode getEndNode();

    MetaDataMap getMetaDataMap();
}
