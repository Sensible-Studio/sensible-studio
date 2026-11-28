/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.model;

import com.day.cq.workflow.metadata.MetaDataMap;

/** A directed connection between two nodes; {@code rule} may decide whether an OR split takes it. */
public interface WorkflowTransition extends com.day.cq.workflow.HasMetaData {

    WorkflowNode getFrom();

    WorkflowNode getTo();

    String getRule();

    void setFrom(WorkflowNode from);

    void setTo(WorkflowNode to);

    void setRule(String rule);

    MetaDataMap getMetaDataMap();
}
