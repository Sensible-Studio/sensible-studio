/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation.forms;

import java.util.Iterator;

/**
 * Lists the available form actions.
 */
public interface FormsManager {

    /** @return the registered form actions */
    Iterator<ComponentDescription> getActions();

    /** A selectable form action or constraint. */
    interface ComponentDescription {

        /** @return resource type of the action */
        String getResourceType();

        /** @return display title */
        String getTitle();
    }
}
