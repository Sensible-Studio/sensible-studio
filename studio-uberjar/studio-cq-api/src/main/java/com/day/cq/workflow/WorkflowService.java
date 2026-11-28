/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow;

import javax.jcr.Session;

/** The older way to a workflow session: an OSGi service asked with a JCR session. */
public interface WorkflowService {

    /** A workflow session acting as the user of {@code session}. It lives as long as that session. */
    WorkflowSession getWorkflowSession(Session session);
}
