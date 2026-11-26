package io.sensible.studio.auth.impl;

import java.util.List;
import java.util.Map;

/**
 * Where the OAuth server keeps its records: clients, authorization codes and tokens, each a flat
 * map of strings, string arrays, longs and booleans under a kind and an id. In the repository below
 * {@code /var/studio/oauth/<kind>/<id>} ({@link OAuthService}); in memory for the tests.
 */
interface OAuthStorage {

    String CLIENTS = "clients";
    String CODES = "codes";
    String TOKENS = "tokens";

    /** @return the record, or null */
    Map<String, Object> get(String kind, String id);

    /** Creates or replaces the record. */
    void put(String kind, String id, Map<String, Object> record);

    void delete(String kind, String id);

    /** Every record of a kind, each with its id under {@code "id"}. */
    List<Map<String, Object>> list(String kind);
}
