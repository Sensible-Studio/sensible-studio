package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.DataType;

/**
 * Data type of an element, from the model's {@code valueType}. The type string is the JCR-level type
 * ({@code string}, {@code long}, {@code double}, {@code boolean}, {@code calendar}); the full value type keeps
 * the semantic part ({@code string/multiline}, {@code string/reference}, {@code calendar/datetime}).
 */
final class DataTypeImpl implements DataType {

    private final String valueType;

    DataTypeImpl(String valueType) {
        this.valueType = valueType == null || valueType.isEmpty() ? "string" : valueType;
    }

    @Override
    public String getTypeString() {
        String base = valueType;
        if (base.endsWith("[]")) base = base.substring(0, base.length() - 2);
        int slash = base.indexOf('/');
        return slash > 0 ? base.substring(0, slash) : base;
    }

    @Override
    public String getValueType() {
        return valueType;
    }

    @Override
    public boolean isMultiValue() {
        return valueType.endsWith("[]");
    }

    @Override
    public String toString() {
        return valueType;
    }
}
