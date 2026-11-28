/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec.filter;

import com.day.cq.workflow.exec.WorkItem;

/** Says which work items of a list are wanted. */
public interface WorkItemFilter {

    boolean doInclude(WorkItem workItem);
}
