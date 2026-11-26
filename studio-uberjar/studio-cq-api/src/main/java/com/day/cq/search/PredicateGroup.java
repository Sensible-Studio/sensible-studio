/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import java.util.ArrayList;
import java.util.Map;

/**
 * A group of predicates combined with AND (default) or OR.
 */
public class PredicateGroup extends Predicate {

    private final ArrayList<Predicate> predicates = new ArrayList<>();
    private boolean allRequired = true;

    public PredicateGroup() {
        super("group");
    }

    public PredicateGroup(String name) {
        super(name, "group");
    }

    /** Builds a group from query parameters (see {@link PredicateConverter}). */
    public static PredicateGroup create(Map<?, ?> params) {
        return PredicateConverter.createPredicates(params);
    }

    public boolean add(Predicate predicate) {
        return predicates.add(predicate);
    }

    public int size() {
        return predicates.size();
    }

    public Predicate get(int index) {
        return predicates.get(index);
    }

    public boolean allRequired() {
        return allRequired;
    }

    public void setAllRequired(boolean all) {
        this.allRequired = all;
    }
}
