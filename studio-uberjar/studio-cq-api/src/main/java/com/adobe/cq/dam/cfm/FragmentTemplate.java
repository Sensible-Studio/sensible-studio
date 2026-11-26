/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

import org.apache.sling.api.adapter.Adaptable;

/**
 * The model a content fragment was created from.
 */
public interface FragmentTemplate extends Adaptable {

    String getTitle();

    String getDescription();
}
