package io.sensible.studio.auth.impl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * An OAuth 2.0 authorization server, as AEM's OAuth clients and tokens: applications an
 * administrator registers get tokens that act as a user, without the user's password.
 *
 * <ul>
 * <li><b>Authorization code with PKCE</b> (RFC 6749 §4.1, RFC 7636): a user signed in on this
 * instance allows a client; the client exchanges the code, with the verifier, for tokens that act as
 * that user. PKCE (S256) is required, also for clients with a secret.</li>
 * <li><b>Refresh tokens</b>, for the scope {@code offline_access}: each use gives a new one and ends
 * the old one; a refresh token used twice ends the whole grant (it was stolen or copied).</li>
 * <li><b>Client credentials</b>: a client an administrator lets act as one user (never {@code admin})
 * gets tokens for that user with its id and secret alone.</li>
 * </ul>
 * Scopes: {@code read} (GET, HEAD, OPTIONS), {@code write} (any method), {@code offline_access}
 * (refresh tokens). A token can never do more than its user may: the repository's permissions apply.
 * Tokens and secrets are random (256 bits) and kept only as their SHA-256.
 */
final class OAuth {

    static final String READ = "read";
    static final String WRITE = "write";
    static final String OFFLINE = "offline_access";
    static final List<String> SCOPES = List.of(READ, WRITE, OFFLINE);

    static final long CODE_MS = 10 * 60_000L;
    static final long ACCESS_MS = 60 * 60_000L;
    static final long REFRESH_MS = 30L * 24 * 60 * 60_000L;
    private static final long LAST_USED_GRANULARITY_MS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A problem the token endpoint answers with RFC 6749's error codes. */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final String error;
        final int status;

        Failure(String error, String description) {
            this(error, description, "invalid_client".equals(error) ? 401 : 400);
        }

        Failure(String error, String description, int status) {
            super(description);
            this.error = error;
            this.status = status;
        }
    }

    /** What a valid access token stands for. */
    static final class Access {
        final String id;
        final String client;
        final String user;
        final Set<String> scopes;

        Access(String id, String client, String user, Set<String> scopes) {
            this.id = id;
            this.client = client;
            this.user = user;
            this.scopes = scopes;
        }

        boolean mayWrite() {
            return scopes.contains(WRITE);
        }
    }

    private final OAuthStorage storage;
    private final LongSupplier clock;
    /** May this user be signed in by a token (exists, enabled, not a system user)? */
    private final Predicate<String> userMaySignIn;

    OAuth(OAuthStorage storage, LongSupplier clock, Predicate<String> userMaySignIn) {
        this.storage = storage;
        this.clock = clock;
        this.userMaySignIn = userMaySignIn;
    }

    // ---------- clients

    /** A new client; the map has its record and, once, {@code secret}. */
    Map<String, Object> createClient(String name, Collection<String> redirectUris, Collection<String> scopes, String runAs, String by)
            throws Failure {
        String id = "studio-" + random(12);
        Map<String, Object> client = clientRecord(name, redirectUris, scopes, runAs);
        String secret = random(32);
        client.put("secret", sha256(secret));
        client.put("created", clock.getAsLong());
        client.put("createdBy", by == null ? "" : by);
        storage.put(OAuthStorage.CLIENTS, id, client);
        Map<String, Object> out = describeClient(id, client);
        out.put("secret", secret);
        return out;
    }

    Map<String, Object> updateClient(String id, String name, Collection<String> redirectUris, Collection<String> scopes, String runAs)
            throws Failure {
        Map<String, Object> client = storage.get(OAuthStorage.CLIENTS, id);
        if (client == null) {
            throw new Failure("invalid_client", "No such client", 404);
        }
        Map<String, Object> updated = clientRecord(name, redirectUris, scopes, runAs);
        for (String keep : List.of("secret", "created", "createdBy")) {
            updated.put(keep, client.get(keep));
        }
        storage.put(OAuthStorage.CLIENTS, id, updated);
        return describeClient(id, updated);
    }

    /** A new secret; the old one stops working at once. Tokens already given stay. */
    String newSecret(String id) throws Failure {
        Map<String, Object> client = storage.get(OAuthStorage.CLIENTS, id);
        if (client == null) {
            throw new Failure("invalid_client", "No such client", 404);
        }
        String secret = random(32);
        client.put("secret", sha256(secret));
        storage.put(OAuthStorage.CLIENTS, id, client);
        return secret;
    }

    /** Removes the client and every code and token it was given. */
    void deleteClient(String id) {
        storage.delete(OAuthStorage.CLIENTS, id);
        for (String kind : List.of(OAuthStorage.CODES, OAuthStorage.TOKENS)) {
            for (Map<String, Object> r : storage.list(kind)) {
                if (id.equals(r.get("client"))) {
                    storage.delete(kind, (String) r.get("id"));
                }
            }
        }
    }

    List<Map<String, Object>> clients() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : storage.list(OAuthStorage.CLIENTS)) {
            out.add(describeClient((String) r.get("id"), r));
        }
        out.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        return out;
    }

    Map<String, Object> client(String id) {
        Map<String, Object> r = storage.get(OAuthStorage.CLIENTS, id);
        return r == null ? null : describeClient(id, r);
    }

    private Map<String, Object> clientRecord(String name, Collection<String> redirectUris, Collection<String> scopes, String runAs)
            throws Failure {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new Failure("invalid_request", "A client needs a name");
        }
        List<String> uris = new ArrayList<>();
        for (String u : redirectUris == null ? Collections.<String>emptyList() : redirectUris) {
            String uri = u.trim();
            if (uri.isEmpty()) {
                continue;
            }
            if (!validRedirect(uri)) {
                throw new Failure("invalid_request", "Not a redirect address a client may have: " + uri
                        + " (https, or http on localhost; no fragment)");
            }
            uris.add(uri);
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String s : scopes == null ? Collections.<String>emptyList() : scopes) {
            if (!SCOPES.contains(s)) {
                throw new Failure("invalid_scope", "No such scope: " + s);
            }
            allowed.add(s);
        }
        if (!allowed.contains(READ) && !allowed.contains(WRITE)) {
            allowed.add(READ);
        }
        String as = runAs == null ? "" : runAs.trim();
        if ("admin".equals(as)) {
            throw new Failure("invalid_request", "A client may not act as admin: make a user for it");
        }
        if (!as.isEmpty() && !userMaySignIn.test(as)) {
            throw new Failure("invalid_request", as + " is no user who may sign in here");
        }
        if (uris.isEmpty() && as.isEmpty()) {
            throw new Failure("invalid_request", "A client needs a redirect address (users allow it) or a user to act as (client credentials)");
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", n);
        r.put("redirectUris", uris.toArray(new String[0]));
        r.put("scopes", allowed.toArray(new String[0]));
        r.put("runAs", as);
        return r;
    }

    /** https anywhere, http only to this machine (a developer's tool), never a fragment. */
    static boolean validRedirect(String uri) {
        try {
            URI u = new URI(uri);
            if (u.getFragment() != null || u.getHost() == null) {
                return false;
            }
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(scheme) || ("http".equals(scheme) && ("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)));
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> describeClient(String id, Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", r.get("name"));
        m.put("redirectUris", strings(r.get("redirectUris")));
        m.put("scopes", strings(r.get("scopes")));
        m.put("runAs", r.get("runAs") == null ? "" : r.get("runAs"));
        m.put("created", r.get("created"));
        m.put("createdBy", r.get("createdBy"));
        return m;
    }

    /** The client a request names, if its secret is right. */
    Map<String, Object> authenticate(String id, String secret) throws Failure {
        Map<String, Object> client = id == null ? null : storage.get(OAuthStorage.CLIENTS, id);
        if (client == null || secret == null || !equal(sha256(secret), (String) client.get("secret"))) {
            throw new Failure("invalid_client", "Unknown client or wrong secret");
        }
        return client;
    }

    // ---------- authorization codes

    /**
     * What the authorize endpoint checks before it asks the user: the client, the redirect address
     * (exactly as registered), the scopes and PKCE.
     *
     * @return the scopes asked for, as the client may have them
     */
    Set<String> checkAuthorization(String clientId, String redirectUri, String scope, String challenge, String method) throws Failure {
        Map<String, Object> client = clientId == null ? null : storage.get(OAuthStorage.CLIENTS, clientId);
        if (client == null) {
            throw new Failure("invalid_client", "No such client");
        }
        if (redirectUri == null || !strings(client.get("redirectUris")).contains(redirectUri)) {
            throw new Failure("invalid_request", "That redirect address is not the client's");
        }
        if (challenge == null || challenge.length() < 43 || challenge.length() > 128 || !"S256".equals(method)) {
            throw new Failure("invalid_request", "PKCE is required: code_challenge with code_challenge_method=S256");
        }
        return scopes(client, scope);
    }

    /** The scopes asked for (space separated), each one the client may have; read when none. */
    static Set<String> scopes(Map<String, Object> client, String asked) throws Failure {
        List<String> allowed = strings(client.get("scopes"));
        Set<String> out = new LinkedHashSet<>();
        if (asked == null || asked.trim().isEmpty()) {
            out.add(allowed.contains(READ) ? READ : WRITE);
            return out;
        }
        for (String s : asked.trim().split("\\s+")) {
            if (!allowed.contains(s)) {
                throw new Failure("invalid_scope", "The client may not ask for " + s);
            }
            out.add(s);
        }
        if (!out.contains(READ) && !out.contains(WRITE)) {
            out.add(allowed.contains(READ) ? READ : WRITE);
        }
        return out;
    }

    /** The user allowed it: a code for the client, good once, for ten minutes. */
    String issueCode(String clientId, String user, String redirectUri, Set<String> scopes, String challenge) {
        String code = random(32);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("client", clientId);
        r.put("user", user);
        r.put("redirectUri", redirectUri);
        r.put("scopes", scopes.toArray(new String[0]));
        r.put("challenge", challenge);
        r.put("expires", clock.getAsLong() + CODE_MS);
        storage.put(OAuthStorage.CODES, sha256(code), r);
        return code;
    }

    /** The token response for a code, with the verifier of its challenge. */
    Map<String, Object> exchangeCode(Map<String, Object> client, String clientId, String code, String redirectUri, String verifier)
            throws Failure {
        String key = code == null ? null : sha256(code);
        Map<String, Object> r = key == null ? null : storage.get(OAuthStorage.CODES, key);
        if (r == null) {
            throw new Failure("invalid_grant", "Unknown or used code");
        }
        // good once, whatever happens next
        storage.delete(OAuthStorage.CODES, key);
        if (((Number) r.get("expires")).longValue() < clock.getAsLong()) {
            throw new Failure("invalid_grant", "The code is over");
        }
        if (!clientId.equals(r.get("client")) || !String.valueOf(r.get("redirectUri")).equals(redirectUri)) {
            throw new Failure("invalid_grant", "The code was given to another client or for another redirect address");
        }
        if (verifier == null || !equal(s256(verifier), (String) r.get("challenge"))) {
            throw new Failure("invalid_grant", "The code verifier does not match the challenge");
        }
        String user = (String) r.get("user");
        if (!userMaySignIn.test(user)) {
            throw new Failure("invalid_grant", "The user may not sign in any more");
        }
        return issueTokens(clientId, user, new LinkedHashSet<>(strings(r.get("scopes"))), null);
    }

    // ---------- tokens

    /** Tokens for a client that acts as its own user. */
    Map<String, Object> clientCredentials(Map<String, Object> client, String clientId, String scope) throws Failure {
        String user = (String) client.get("runAs");
        if (user == null || user.isEmpty()) {
            throw new Failure("unauthorized_client", "The client may not act as a user of its own");
        }
        if (!userMaySignIn.test(user)) {
            throw new Failure("invalid_grant", user + " may not sign in");
        }
        Set<String> scopes = scopes(client, scope);
        // nobody to come back for: no refresh token
        scopes.remove(OFFLINE);
        return issueTokens(clientId, user, scopes, null);
    }

    /** A new access token and a new refresh token for a refresh token, which ends. */
    Map<String, Object> refresh(String clientId, String refreshToken, String scope) throws Failure {
        String key = refreshToken == null ? null : sha256(refreshToken);
        Map<String, Object> r = key == null ? null : storage.get(OAuthStorage.TOKENS, key);
        if (r == null || !"refresh".equals(r.get("kind")) || !clientId.equals(r.get("client"))) {
            throw new Failure("invalid_grant", "Unknown refresh token");
        }
        String grant = (String) r.get("grant");
        if (Boolean.TRUE.equals(r.get("used"))) {
            // used a second time: whoever has it is not who should; the whole grant ends
            endGrant(grant);
            throw new Failure("invalid_grant", "The refresh token was used before: every token of the grant is revoked");
        }
        if (((Number) r.get("expires")).longValue() < clock.getAsLong()) {
            storage.delete(OAuthStorage.TOKENS, key);
            throw new Failure("invalid_grant", "The refresh token is over");
        }
        String user = (String) r.get("user");
        if (!userMaySignIn.test(user)) {
            endGrant(grant);
            throw new Failure("invalid_grant", "The user may not sign in any more");
        }
        Set<String> had = new LinkedHashSet<>(strings(r.get("scopes")));
        Set<String> scopes = had;
        if (scope != null && !scope.trim().isEmpty()) {
            scopes = new LinkedHashSet<>(Arrays.asList(scope.trim().split("\\s+")));
            if (!had.containsAll(scopes)) {
                throw new Failure("invalid_scope", "More than was allowed");
            }
        }
        r.put("used", true);
        storage.put(OAuthStorage.TOKENS, key, r);
        return issueTokens(clientId, user, scopes, grant);
    }

    private Map<String, Object> issueTokens(String clientId, String user, Set<String> scopes, String grant) {
        long now = clock.getAsLong();
        String g = grant == null ? random(12) : grant;
        String access = "sst_" + random(32);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("kind", "access");
        a.put("client", clientId);
        a.put("user", user);
        a.put("scopes", scopes.toArray(new String[0]));
        a.put("grant", g);
        a.put("created", now);
        a.put("expires", now + ACCESS_MS);
        storage.put(OAuthStorage.TOKENS, sha256(access), a);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("access_token", access);
        out.put("token_type", "Bearer");
        out.put("expires_in", ACCESS_MS / 1000);
        out.put("scope", String.join(" ", scopes));
        if (scopes.contains(OFFLINE)) {
            String refresh = "ssr_" + random(32);
            Map<String, Object> r = new LinkedHashMap<>(a);
            r.put("kind", "refresh");
            r.put("expires", now + REFRESH_MS);
            storage.put(OAuthStorage.TOKENS, sha256(refresh), r);
            out.put("refresh_token", refresh);
        }
        return out;
    }

    /** Every token of a grant (the ones a refresh token gave since the user allowed it). */
    private void endGrant(String grant) {
        if (grant == null) {
            return;
        }
        for (Map<String, Object> t : storage.list(OAuthStorage.TOKENS)) {
            if (grant.equals(t.get("grant"))) {
                storage.delete(OAuthStorage.TOKENS, (String) t.get("id"));
            }
        }
    }

    /** What an access token stands for, if it is one and still good; its last use is noted. */
    Access access(String token) {
        if (token == null || !token.startsWith("sst_")) {
            return null;
        }
        String key = sha256(token);
        Map<String, Object> r = storage.get(OAuthStorage.TOKENS, key);
        long now = clock.getAsLong();
        if (r == null || !"access".equals(r.get("kind"))) {
            return null;
        }
        if (((Number) r.get("expires")).longValue() < now) {
            storage.delete(OAuthStorage.TOKENS, key);
            return null;
        }
        // a client that is gone leaves no token that works
        if (storage.get(OAuthStorage.CLIENTS, (String) r.get("client")) == null) {
            return null;
        }
        Object last = r.get("lastUsed");
        if (!(last instanceof Number) || now - ((Number) last).longValue() > LAST_USED_GRANULARITY_MS) {
            r.put("lastUsed", now);
            storage.put(OAuthStorage.TOKENS, key, r);
        }
        return new Access(key, (String) r.get("client"), (String) r.get("user"), new LinkedHashSet<>(strings(r.get("scopes"))));
    }

    /** RFC 7009: a client ends one of its tokens (and, for a refresh token, its grant). */
    void revoke(String clientId, String token) {
        if (token == null) {
            return;
        }
        String key = sha256(token);
        Map<String, Object> r = storage.get(OAuthStorage.TOKENS, key);
        if (r != null && clientId.equals(r.get("client"))) {
            if ("refresh".equals(r.get("kind"))) {
                endGrant((String) r.get("grant"));
            }
            storage.delete(OAuthStorage.TOKENS, key);
        }
    }

    /** The console: tokens (without their values), all of them or one user's. */
    List<Map<String, Object>> tokens(String onlyUser) {
        long now = clock.getAsLong();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : storage.list(OAuthStorage.TOKENS)) {
            if (((Number) t.get("expires")).longValue() < now || Boolean.TRUE.equals(t.get("used"))) {
                continue;
            }
            if (onlyUser != null && !onlyUser.equals(t.get("user"))) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id"));
            m.put("kind", t.get("kind"));
            m.put("client", t.get("client"));
            Map<String, Object> c = storage.get(OAuthStorage.CLIENTS, (String) t.get("client"));
            m.put("clientName", c == null ? t.get("client") : c.get("name"));
            m.put("user", t.get("user"));
            m.put("scopes", strings(t.get("scopes")));
            m.put("created", t.get("created"));
            m.put("expires", t.get("expires"));
            m.put("lastUsed", t.get("lastUsed"));
            out.add(m);
        }
        out.sort((a, b) -> Long.compare(((Number) b.get("created")).longValue(), ((Number) a.get("created")).longValue()));
        return out;
    }

    /** The console: a token by its id, and every token of its grant. */
    boolean revokeById(String id, String onlyUser) {
        Map<String, Object> r = id == null ? null : storage.get(OAuthStorage.TOKENS, id);
        if (r == null || (onlyUser != null && !onlyUser.equals(r.get("user")))) {
            return false;
        }
        endGrant((String) r.get("grant"));
        storage.delete(OAuthStorage.TOKENS, id);
        return true;
    }

    /** Codes and tokens that are over. */
    int sweep() {
        long now = clock.getAsLong();
        int removed = 0;
        for (String kind : List.of(OAuthStorage.CODES, OAuthStorage.TOKENS)) {
            for (Map<String, Object> r : storage.list(kind)) {
                Object expires = r.get("expires");
                if (expires instanceof Number && ((Number) expires).longValue() < now) {
                    storage.delete(kind, (String) r.get("id"));
                    removed++;
                }
            }
        }
        return removed;
    }

    // ---------- helpers

    static List<String> strings(Object value) {
        if (value instanceof String[]) {
            return Arrays.asList((String[]) value);
        }
        if (value instanceof Collection) {
            List<String> out = new ArrayList<>();
            for (Object o : (Collection<?>) value) {
                out.add(String.valueOf(o));
            }
            return out;
        }
        return value == null ? Collections.emptyList() : Collections.singletonList(String.valueOf(value));
    }

    static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String value) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCE S256: BASE64URL(SHA256(verifier)). */
    static String s256(String verifier) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean equal(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
