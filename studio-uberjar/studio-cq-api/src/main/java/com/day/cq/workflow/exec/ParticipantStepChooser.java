/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.metadata.MetaDataMap;

/** Chooses the participant of a dynamic participant step (OSGi service with {@code chooser.label}). */
public interface ParticipantStepChooser {

    /** @return the user or group ID the work item is assigned to */
    String getParticipant(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException;
}
