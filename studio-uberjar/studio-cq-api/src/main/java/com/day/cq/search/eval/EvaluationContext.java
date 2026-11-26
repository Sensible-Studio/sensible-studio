/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search.eval;

import org.apache.sling.api.resource.ResourceResolver;

/**
 * Context passed to predicate evaluators.
 */
public interface EvaluationContext {

    ResourceResolver getResourceResolver();
}
