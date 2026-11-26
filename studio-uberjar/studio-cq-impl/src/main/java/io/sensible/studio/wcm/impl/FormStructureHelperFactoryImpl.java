/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import com.day.cq.wcm.foundation.forms.FormStructureHelper;
import com.day.cq.wcm.foundation.forms.FormStructureHelperFactory;

/**
 * Returns the first registered {@link FormStructureHelper} (for example the Core Components one)
 * that can manage a resource.
 */
@Component(service = FormStructureHelperFactory.class)
public class FormStructureHelperFactoryImpl implements FormStructureHelperFactory {

    private final List<FormStructureHelper> helpers = new CopyOnWriteArrayList<>();

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindHelper(FormStructureHelper helper) {
        helpers.add(helper);
    }

    void unbindHelper(FormStructureHelper helper) {
        helpers.remove(helper);
    }

    @Override
    public FormStructureHelper getFormStructureHelper(Resource resource) {
        for (FormStructureHelper h : helpers) {
            if (h.canManage(resource)) {
                return h;
            }
        }
        return null;
    }
}
