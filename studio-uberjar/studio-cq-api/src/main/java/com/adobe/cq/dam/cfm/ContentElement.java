/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

import org.apache.sling.api.adapter.Adaptable;

/**
 * One field of a content fragment.
 */
public interface ContentElement extends Adaptable {

    String getName();

    String getTitle();

    String getContentType();

    FragmentData getValue();

    ContentVariation getVariation(String variationName);
}
