/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import com.day.cq.workflow.WorkflowSession;

/**
 * The oldest shape of a process step: no arguments. Register it as an OSGi service of this type with a
 * {@code process.label}; a model's process step names it by that label or by its class name.
 */
public interface JavaProcess {

    void execute(WorkItem item, WorkflowSession session) throws Exception;
}
