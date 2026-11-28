/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.exec;

import com.day.cq.workflow.metadata.MetaDataMap;

/** What a workflow works on: the payload (usually a repository path) and the workflow's metadata. */
public interface WorkflowData {

    Object getPayload();

    /** @return {@code JCR_PATH} or {@code URL} */
    String getPayloadType();

    MetaDataMap getMetaDataMap();
}
