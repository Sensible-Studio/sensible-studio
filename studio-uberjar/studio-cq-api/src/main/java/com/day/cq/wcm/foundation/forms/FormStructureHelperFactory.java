/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import org.apache.sling.api.resource.Resource;

/**
 * Returns the {@link FormStructureHelper} responsible for a resource.
 */
public interface FormStructureHelperFactory {

    /** @return the responsible helper, or {@code null} */
    FormStructureHelper getFormStructureHelper(Resource resource);
}
