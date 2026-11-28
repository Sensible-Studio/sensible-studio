/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import java.util.List;

import com.day.cq.workflow.model.WorkflowTransition;

/** A way to complete a work item: the transitions the workflow follows. */
public interface Route {

    String getId();

    String getName();

    boolean hasDefault();

    List<WorkflowTransition> getDestinations();
}
