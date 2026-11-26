/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.adobe.cq.dam.cfm;

/**
 * Data type of a content fragment field.
 */
public interface DataType {

    String getTypeString();

    String getValueType();

    boolean isMultiValue();
}
