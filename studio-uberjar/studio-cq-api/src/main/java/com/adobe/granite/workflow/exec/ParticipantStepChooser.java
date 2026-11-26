/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow.exec;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.metadata.MetaDataMap;

/** Chooses the participant of a dynamic participant step (OSGi service with {@code chooser.label}). */
public interface ParticipantStepChooser {

    /** @return the user or group ID the work item is assigned to */
    String getParticipant(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException;
}
