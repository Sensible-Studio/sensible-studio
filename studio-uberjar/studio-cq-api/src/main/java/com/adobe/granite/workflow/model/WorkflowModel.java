/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.model;

import java.util.List;

import com.adobe.granite.workflow.metadata.MetaDataMap;

/**
 * A runtime workflow model: nodes (steps, splits, joins, start and end) connected by transitions.
 */
public interface WorkflowModel {

    /** @return the model path, e.g. {@code /var/workflow/models/request_for_activation} */
    String getId();

    String getTitle();

    String getDescription();

    String getVersion();

    List<WorkflowNode> getNodes();

    WorkflowNode getNode(String id);

    List<WorkflowTransition> getTransitions();

    WorkflowNode getRootNode();

    WorkflowNode getEndNode();

    MetaDataMap getMetaDataMap();
}
