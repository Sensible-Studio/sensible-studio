/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.metadata.MetaDataMap;

/**
 * A process step. Register implementations as OSGi services with a {@code process.label} property; a model's process
 * step refers to them by that label (or by class name). {@code args} holds the step's {@code PROCESS_ARGS}.
 */
public interface WorkflowProcess {

    void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException;
}
