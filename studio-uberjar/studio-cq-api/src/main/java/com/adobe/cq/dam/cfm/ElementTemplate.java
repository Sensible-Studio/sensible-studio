/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

import org.apache.sling.api.adapter.Adaptable;

/**
 * Definition of one field in a fragment model.
 */
public interface ElementTemplate extends Adaptable {

    String getName();

    String getTitle();
}
