/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec.filter;

import com.adobe.granite.workflow.exec.WorkItem;

/** Says which work items of a list are wanted. */
public interface WorkItemFilter {

    boolean doInclude(WorkItem workItem);
}
