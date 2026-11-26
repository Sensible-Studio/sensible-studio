/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.search;

import javax.jcr.Session;

/**
 * OSGi service that turns predicates into repository queries.
 */
public interface QueryBuilder {

    Query createQuery(Session session);

    Query createQuery(PredicateGroup predicates, Session session);
}
