/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

/**
 * A typed field value.
 */
public interface FragmentData {

    DataType getDataType();

    Object getValue();

    <T> T getValue(Class<T> type);

    String getContentType();
}
