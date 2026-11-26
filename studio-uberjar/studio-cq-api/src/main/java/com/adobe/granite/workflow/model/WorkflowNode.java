/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.model;

import java.util.List;

import com.adobe.granite.workflow.metadata.MetaDataMap;

/** A node of a runtime workflow model. */
public interface WorkflowNode {

    String TYPE_START = "START";
    String TYPE_END = "END";
    String TYPE_PROCESS = "PROCESS";
    String TYPE_PARTICIPANT = "PARTICIPANT";
    String TYPE_DYNAMIC_PARTICIPANT = "DYNAMIC_PARTICIPANT";
    String TYPE_OR_SPLIT = "OR_SPLIT";
    String TYPE_OR_JOIN = "OR_JOIN";
    String TYPE_AND_SPLIT = "AND_SPLIT";
    String TYPE_AND_JOIN = "AND_JOIN";

    String getId();

    String getTitle();

    String getDescription();

    String getType();

    MetaDataMap getMetaDataMap();

    /** @return the outgoing transitions */
    List<WorkflowTransition> getTransitions();

    List<WorkflowTransition> getIncomingTransitions();
}
