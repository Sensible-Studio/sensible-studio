package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthTest {

    @Test
    void consentPageMayPostOnToTheRedirectOrigin() {
        assertEquals("http://localhost:8765", OAuthServlet.originOf("http://localhost:8765/cb?x=1"));
        assertEquals("https://app.example.com", OAuthServlet.originOf("HTTPS://App.Example.com/cb"));
        assertNull(OAuthServlet.originOf("/relative/cb"));
        assertNull(OAuthServlet.originOf("not a uri"));
    }

    /** The records in memory. */
    static final class Memory implements OAuthStorage {
        final Map<String, Map<String, Map<String, Object>>> kinds = new HashMap<>();

        @Override
        public Map<String, Object> get(String kind, String id) {
            Map<String, Object> r = kinds.getOrDefault(kind, Map.of()).get(id);
            return r == null ? null : new LinkedHashMap<>(r);
        }

        @Override
        public void put(String kind, String id, Map<String, Object> record) {
            kinds.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(id, new LinkedHashMap<>(record));
        }

        @Override
        public void delete(String kind, String id) {
            kinds.getOrDefault(kind, new HashMap<>()).remove(id);
        }

        @Override
        public List<Map<String, Object>> list(String kind) {
            List<Map<String, Object>> out = new ArrayList<>();
            kinds.getOrDefault(kind, Map.of()).forEach((id, r) -> {
                Map<String, Object> m = new LinkedHashMap<>(r);
                m.put("id", id);
                out.add(m);
            });
            return out;
        }
    }

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Set<String> users = new java.util.HashSet<>(Set.of("ana", "svc-site"));
    private Memory memory;
    private OAuth oauth;
    private String appId;
    private String appSecret;

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String REDIRECT = "https://app.example/callback";

    @BeforeEach
    void anApplication() throws Exception {
        memory = new Memory();
        oauth = new OAuth(memory, now::get, users::contains);
        Map<String, Object> app = oauth.createClient("Site app", List.of(REDIRECT), List.of("read", "write", "offline_access"), "", "admin");
        appId = (String) app.get("id");
        appSecret = (String) app.get("secret");
    }

    private Map<String, Object> allowAndExchange(String scope) throws Exception {
        Set<String> scopes = oauth.checkAuthorization(appId, REDIRECT, scope, OAuth.s256(VERIFIER), "S256");
        String code = oauth.issueCode(appId, "ana", REDIRECT, scopes, OAuth.s256(VERIFIER));
        return oauth.exchangeCode(oauth.authenticate(appId, appSecret), appId, code, REDIRECT, VERIFIER);
    }

    @Test
    void aUserAllowsAnApplicationWhichGetsTokensThatActAsTheUser() throws Exception {
        Map<String, Object> tokens = allowAndExchange("read offline_access");
        assertEquals("Bearer", tokens.get("token_type"));
        assertEquals(3600L, tokens.get("expires_in"));
        assertEquals("read offline_access", tokens.get("scope"));
        OAuth.Access access = oauth.access((String) tokens.get("access_token"));
        assertEquals("ana", access.user);
        assertEquals(appId, access.client);
        assertFalse(access.mayWrite());
        assertNotNull(tokens.get("refresh_token"));
        // kept only as their hash
        assertFalse(memory.kinds.get("tokens").containsKey(tokens.get("access_token")));
        assertFalse(memory.kinds.get("clients").get(appId).containsValue(appSecret));
    }

    @Test
    void pkceAndTheRedirectAddressAreRequired() throws Exception {
        assertEquals("invalid_request", assertThrows(OAuth.Failure.class, () -> oauth.checkAuthorization(appId, REDIRECT, "read", null, null)).error);
        assertEquals("invalid_request", assertThrows(OAuth.Failure.class, () -> oauth.checkAuthorization(appId, REDIRECT, "read", OAuth.s256(VERIFIER), "plain")).error);
        assertEquals("invalid_request", assertThrows(OAuth.Failure.class, () -> oauth.checkAuthorization(appId, "https://evil.example/cb", "read", OAuth.s256(VERIFIER), "S256")).error);
        assertEquals("invalid_scope", assertThrows(OAuth.Failure.class, () -> oauth.checkAuthorization(appId, REDIRECT, "admin", OAuth.s256(VERIFIER), "S256")).error);
        // a code is good once, with its verifier, for its client and redirect address
        String code = oauth.issueCode(appId, "ana", REDIRECT, Set.of("read"), OAuth.s256(VERIFIER));
        Map<String, Object> client = oauth.authenticate(appId, appSecret);
        assertEquals("invalid_grant", assertThrows(OAuth.Failure.class, () -> oauth.exchangeCode(client, appId, code, REDIRECT, "wrong-verifier-wrong-verifier-wrong-verifier")).error);
        assertEquals("invalid_grant", assertThrows(OAuth.Failure.class, () -> oauth.exchangeCode(client, appId, code, REDIRECT, VERIFIER)).error, "used once already");
        String late = oauth.issueCode(appId, "ana", REDIRECT, Set.of("read"), OAuth.s256(VERIFIER));
        now.addAndGet(OAuth.CODE_MS + 1);
        assertEquals("invalid_grant", assertThrows(OAuth.Failure.class, () -> oauth.exchangeCode(client, appId, late, REDIRECT, VERIFIER)).error);
        assertEquals("invalid_client", assertThrows(OAuth.Failure.class, () -> oauth.authenticate(appId, "wrong")).error);
        assertEquals(401, assertThrows(OAuth.Failure.class, () -> oauth.authenticate(appId, "wrong")).status);
    }

    @Test
    void aRefreshTokenGivesNewOnesOnceAndASecondUseEndsTheGrant() throws Exception {
        Map<String, Object> first = allowAndExchange("write offline_access");
        Map<String, Object> second = oauth.refresh(appId, (String) first.get("refresh_token"), null);
        assertNotNull(oauth.access((String) second.get("access_token")));
        assertTrue(oauth.access((String) second.get("access_token")).mayWrite());
        // narrower is fine, wider is not
        assertEquals("invalid_scope", assertThrows(OAuth.Failure.class, () -> oauth.refresh(appId, (String) second.get("refresh_token"), "write admin")).error);
        // the first refresh token again: stolen; everything of the grant ends
        assertEquals("invalid_grant", assertThrows(OAuth.Failure.class, () -> oauth.refresh(appId, (String) first.get("refresh_token"), null)).error);
        assertNull(oauth.access((String) second.get("access_token")));
        assertNull(oauth.access((String) first.get("access_token")));
        assertEquals("invalid_grant", assertThrows(OAuth.Failure.class, () -> oauth.refresh(appId, (String) second.get("refresh_token"), null)).error);
    }

    @Test
    void tokensEnd() throws Exception {
        Map<String, Object> t = allowAndExchange("read");
        assertNull(t.get("refresh_token"), "no offline_access, no refresh token");
        String access = (String) t.get("access_token");
        assertNotNull(oauth.access(access));
        assertEquals(1, oauth.tokens("ana").size());
        assertEquals(0, oauth.tokens("bob").size());
        now.addAndGet(OAuth.ACCESS_MS + 1);
        assertNull(oauth.access(access));
        // revoked by the client, by the console, or with the client
        String a2 = (String) allowAndExchange("read").get("access_token");
        oauth.revoke(appId, a2);
        assertNull(oauth.access(a2));
        String a3 = (String) allowAndExchange("read").get("access_token");
        assertFalse(oauth.revokeById(OAuth.sha256(a3), "bob"), "not bob's");
        assertTrue(oauth.revokeById(OAuth.sha256(a3), "ana"));
        assertNull(oauth.access(a3));
        String a4 = (String) allowAndExchange("read").get("access_token");
        oauth.deleteClient(appId);
        assertNull(oauth.access(a4));
        assertTrue(oauth.tokens(null).isEmpty());
    }

    @Test
    void aClientMayActAsAUserOfItsOwn() throws Exception {
        Map<String, Object> svc = oauth.createClient("Indexer", List.of(), List.of("read", "offline_access"), "svc-site", "admin");
        String id = (String) svc.get("id");
        Map<String, Object> t = oauth.clientCredentials(oauth.authenticate(id, (String) svc.get("secret")), id, null);
        assertEquals("svc-site", oauth.access((String) t.get("access_token")).user);
        assertNull(t.get("refresh_token"), "nobody to come back for");
        assertEquals("unauthorized_client", assertThrows(OAuth.Failure.class, () -> oauth.clientCredentials(oauth.authenticate(appId, appSecret), appId, null)).error);
        // never admin, never somebody who may not sign in
        assertThrows(OAuth.Failure.class, () -> oauth.createClient("x", List.of(), List.of("read"), "admin", "admin"));
        assertThrows(OAuth.Failure.class, () -> oauth.createClient("x", List.of(), List.of("read"), "nobody", "admin"));
        // a user disabled since: no more tokens
        users.remove("svc-site");
        assertThrows(OAuth.Failure.class, () -> oauth.clientCredentials(oauth.authenticate(id, (String) svc.get("secret")), id, null));
        // a new secret: the old one stops at once
        users.add("svc-site");
        String fresh = oauth.newSecret(id);
        assertThrows(OAuth.Failure.class, () -> oauth.authenticate(id, (String) svc.get("secret")));
        assertNotNull(oauth.authenticate(id, fresh));
    }

    @Test
    void redirectAddressesAClientMayHave() {
        assertTrue(OAuth.validRedirect("https://app.example/cb?x=1"));
        assertTrue(OAuth.validRedirect("http://localhost:3000/cb"));
        assertTrue(OAuth.validRedirect("http://127.0.0.1:8080/"));
        assertFalse(OAuth.validRedirect("http://app.example/cb"));
        assertFalse(OAuth.validRedirect("https://app.example/cb#frag"));
        assertFalse(OAuth.validRedirect("javascript:alert(1)"));
        assertThrows(OAuth.Failure.class, () -> oauth.createClient("x", List.of("http://app.example/"), List.of("read"), "", "admin"));
        assertThrows(OAuth.Failure.class, () -> oauth.createClient("x", List.of(), List.of("read"), "", "admin"), "neither a redirect nor a user");
    }

    @Test
    void readTokensOnlyRead() {
        assertNull(OAuthScopeFilter.refused("GET", "/content/wknd.html", Set.of("read")));
        assertNull(OAuthScopeFilter.refused("HEAD", "/content/wknd.html", Set.of("read")));
        assertNotNull(OAuthScopeFilter.refused("POST", "/content/wknd/x", Set.of("read")));
        assertNull(OAuthScopeFilter.refused("POST", "/content/wknd/x", Set.of("read", "write")));
        assertNotNull(OAuthScopeFilter.refused("GET", "/bin/studio/oauth.json", Set.of("read", "write")), "the console is never used with a token");
    }

    @Test
    void theBearerHeaderSignsInOrIsRefused() throws Exception {
        String access = (String) allowAndExchange("read").get("access_token");
        OAuthService service = new OAuthService();
        service.use(oauth);
        BearerHandler handler = new BearerHandler();
        handler.service(service);
        Map<String, Object> attributes = new HashMap<>();
        AuthenticationInfo info = handler.extractCredentials(request("Bearer " + access, attributes), response(new int[1]));
        assertEquals("ana", info.getUser());
        assertEquals(Set.of("read"), attributes.get(BearerHandler.SCOPES));
        assertTrue(info.get("user.jcr.credentials") instanceof BearerCredentials);
        int[] status = new int[1];
        assertSame(AuthenticationInfo.DOING_AUTH, handler.extractCredentials(request("Bearer sst_nope", new HashMap<>()), response(status)));
        assertEquals(401, status[0]);
        assertNull(handler.extractCredentials(request(null, new HashMap<>()), response(new int[1])), "no header: not this handler's");
        assertNull(handler.extractCredentials(request("Basic YWRtaW46YWRtaW4=", new HashMap<>()), response(new int[1])));
    }

    private static HttpServletRequest request(String authorization, Map<String, Object> attributes) {
        return (HttpServletRequest) Proxy.newProxyInstance(OAuthTest.class.getClassLoader(), new Class<?>[] { HttpServletRequest.class }, (p, m, a) -> {
            switch (m.getName()) {
                case "getHeader":
                    return "Authorization".equals(a[0]) ? authorization : null;
                case "setAttribute":
                    attributes.put((String) a[0], a[1]);
                    return null;
                case "getAttribute":
                    return attributes.get(a[0]);
                default:
                    return null;
            }
        });
    }

    private static HttpServletResponse response(int[] status) {
        java.io.StringWriter body = new java.io.StringWriter();
        java.io.PrintWriter writer = new java.io.PrintWriter(body);
        return (HttpServletResponse) Proxy.newProxyInstance(OAuthTest.class.getClassLoader(), new Class<?>[] { HttpServletResponse.class }, (p, m, a) -> {
            switch (m.getName()) {
                case "setStatus":
                    status[0] = (Integer) a[0];
                    return null;
                case "getWriter":
                    return writer;
                case "isCommitted":
                    return false;
                default:
                    return null;
            }
        });
    }
}
