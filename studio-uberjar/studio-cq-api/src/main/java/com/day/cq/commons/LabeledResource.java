/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.commons;

/**
 * A resource with a title and a description.
 */
public interface LabeledResource {

    String getPath();

    String getName();

    String getTitle();

    String getDescription();
}
