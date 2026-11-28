/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.granite.workflow;

import com.adobe.granite.workflow.metadata.MetaDataMap;

/** What carries metadata: a workflow, a work item, a model, its nodes and transitions. */
public interface HasMetaData {

    MetaDataMap getMetaDataMap();
}
