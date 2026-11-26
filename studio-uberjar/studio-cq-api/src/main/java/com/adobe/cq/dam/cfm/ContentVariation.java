/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

/**
 * The value of a field in one variation.
 */
public interface ContentVariation {

    String getName();

    String getTitle();

    String getContentType();

    FragmentData getValue();
}
