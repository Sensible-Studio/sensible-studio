/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.model;

/** Says which models of a list are wanted. */
public interface WorkflowModelFilter {

    boolean doInclude(WorkflowModel model);
}
