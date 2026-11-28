/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import java.io.Serializable;

import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.metadata.MetaDataMap;

/**
 * A step that starts something outside and waits for it. Register it as an OSGi service of this type with a
 * {@code process.label}; a process step names it like any process. The engine calls {@link #execute}, keeps the
 * id it answers, then asks {@link #hasFinished} every few seconds and, once that says yes, calls
 * {@link #handleResult} and moves the flow on. A timeout on the step works as on any waiting step.
 */
public interface WorkflowExternalProcess {

    /** Starts the outside work. @return what identifies it, or {@code null} when nothing was started: the flow goes on at once */
    Serializable execute(WorkItem item, WorkflowSession session, MetaDataMap arguments) throws WorkflowException;

    boolean hasFinished(Serializable externalProcessId, WorkItem item, WorkflowSession session, MetaDataMap arguments);

    void handleResult(Serializable externalProcessId, WorkItem workItem, WorkflowSession session, MetaDataMap arguments) throws WorkflowException;
}
