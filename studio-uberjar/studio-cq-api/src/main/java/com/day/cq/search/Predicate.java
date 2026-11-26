/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One query predicate: a type (e.g. {@code path}, {@code property}) and its parameters.
 */
public class Predicate {

    private final String name;
    private final String type;
    private final Map<String, String> params = new LinkedHashMap<>();

    public Predicate(String type) {
        this(null, type);
    }

    public Predicate(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    /** @return the parameter value, or {@code null} */
    public String get(String parameter) {
        return params.get(parameter);
    }

    /** Sets a parameter and returns this predicate. */
    public Predicate set(String parameter, String value) {
        if (value == null) {
            params.remove(parameter);
        } else {
            params.put(parameter, value);
        }
        return this;
    }

    /** @return a copy of the parameters */
    public Map<String, String> getParameters() {
        return new LinkedHashMap<>(params);
    }
}
