/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import org.apache.sling.api.resource.Resource;

/**
 * Understands one form structure (e.g. Core Components forms). Implementations are OSGi services;
 * {@link FormStructureHelperFactory} picks the one that {@link #canManage(Resource) can manage} a
 * resource.
 */
public interface FormStructureHelper {

    /** @return {@code true} if this helper understands the form the resource belongs to */
    boolean canManage(Resource resource);

    /** @return the form container resource for a form element, or {@code null} */
    Resource getFormResource(Resource resource);

    /** @return the field resources of a form */
    Iterable<Resource> getFormElements(Resource resource);

    /** @return the form resource after normalising its structure */
    Resource updateFormStructure(Resource formResource);
}
