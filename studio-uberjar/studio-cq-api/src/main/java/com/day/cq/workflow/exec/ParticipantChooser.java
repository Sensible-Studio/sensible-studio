/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;

/**
 * The oldest shape of a participant chooser: the step's Arguments as an array.
 *
 * @deprecated {@link ParticipantStepChooser}
 */
@Deprecated
public interface ParticipantChooser {

    String getParticipant(WorkItem workItem, WorkflowSession workflowSession, String[] args) throws WorkflowException;
}
