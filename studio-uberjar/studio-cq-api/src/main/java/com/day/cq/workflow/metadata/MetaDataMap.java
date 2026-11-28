/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.workflow.metadata;

import java.util.Map;

/** Workflow metadata: a map with typed reads. */
public interface MetaDataMap extends Map<String, Object> {

    /** The value converted to {@code type}, or null. */
    <T> T get(String name, Class<T> type);

    /** The value converted to the type of {@code defaultValue}, or {@code defaultValue}. */
    <T> T get(String name, T defaultValue);
}
