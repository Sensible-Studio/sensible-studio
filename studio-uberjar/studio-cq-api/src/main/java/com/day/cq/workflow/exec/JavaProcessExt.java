/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import com.day.cq.workflow.WorkflowSession;

/** A process step that takes the step's Arguments split at commas. */
public interface JavaProcessExt extends JavaProcess {

    void execute(WorkItem item, WorkflowSession session, String[] args) throws Exception;
}
