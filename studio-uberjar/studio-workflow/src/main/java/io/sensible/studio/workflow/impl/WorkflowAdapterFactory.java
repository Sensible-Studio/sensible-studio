/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.adobe.granite.workflow.WorkflowSession;

/** {@code resolver.adaptTo(WorkflowSession.class)}: a workflow session acting as the resolver's user. */
@Component(service = AdapterFactory.class, property = {
        AdapterFactory.ADAPTABLE_CLASSES + "=org.apache.sling.api.resource.ResourceResolver",
        AdapterFactory.ADAPTER_CLASSES + "=com.adobe.granite.workflow.WorkflowSession"})
public class WorkflowAdapterFactory implements AdapterFactory {

    @Reference
    private WorkflowService service;

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType getAdapter(Object adaptable, Class<AdapterType> type) {
        if (adaptable instanceof ResourceResolver && type == WorkflowSession.class) {
            return (AdapterType) new Objects.SessionImpl((ResourceResolver) adaptable, service);
        }
        return null;
    }
}
