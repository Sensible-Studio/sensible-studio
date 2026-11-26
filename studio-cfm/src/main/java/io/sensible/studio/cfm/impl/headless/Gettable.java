package io.sensible.studio.cfm.impl.headless;

import graphql.schema.DataFetchingEnvironment;

/** A value the generic data fetcher reads fields from (fragments, rich text, asset references, result pages). */
interface Gettable {

    Object get(String field, DataFetchingEnvironment env);
}
