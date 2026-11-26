package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginHandlerTest {

    @TempDir
    Path dir;
    private LoginHandler one;
    private LoginHandler two;
    private LoginHandler stranger;

    @BeforeEach
    void twoInstancesOfAFarmAndOneThatIsNot() throws IOException {
        Path key = dir.resolve("one/login.key");
        one = handler(key);
        new LoginKey(key).get(0); // the first start writes it
        Path copy = dir.resolve("two/login.key");
        Files.createDirectories(copy.getParent());
        Files.copy(key, copy);
        two = handler(copy);
        stranger = handler(dir.resolve("stranger/login.key"));
    }

    /** The password hashes the repository keeps, as the handler reads them at a sign-in. */
    private final Map<String, String> hashes = new HashMap<>(Map.of("ana", "{SHA-256}aa-1000-first", "bob", "{SHA-256}bb-1000-first"));

    private LoginHandler handler(Path key) {
        LoginHandler handler = new LoginHandler();
        handler.configure("studio-login", 30, "/libs/granite/core/content/login.html", new LoginKey(key));
        handler.passwords(hashes::get);
        return handler;
    }

    /** Signs in on an instance as the login form does, and returns the cookie the visitor gets. */
    private String signIn(LoginHandler instance, String user) {
        HttpServletRequest post = request("POST", "/libs/granite/core/content/login.html/j_security_check", null,
                Map.of("j_username", user, "j_password", "secret", "j_validate", "true"));
        Answer answer = new Answer();
        AuthenticationInfo info = instance.extractCredentials(post, answer.response());
        assertEquals(user, info.getUser());
        assertEquals("secret", new String(info.getPassword()));
        assertTrue(answer.cookies.isEmpty(), "no cookie before the repository said yes");
        // the repository said yes
        assertFalse(instance.authenticationSucceeded(post, answer.response(), info));
        assertEquals(1, answer.cookies.size());
        String header = answer.cookies.get(0);
        assertTrue(header.startsWith("studio-login=2."), header);
        assertTrue(header.endsWith("; Path=/; HttpOnly; SameSite=Lax"), header);
        return header.substring("studio-login=".length(), header.indexOf(';'));
    }

    @Test
    void aSignInOnOneInstanceIsGoodOnTheOther() {
        String cookie = signIn(one, "ana");
        for (LoginHandler instance : new LoginHandler[] {one, two}) {
            Answer answer = new Answer();
            AuthenticationInfo info = instance.extractCredentials(request("GET", "/content/site/members.html", cookie, Map.of()), answer.response());
            assertNotNull(info);
            assertEquals("ana", info.getUser());
            assertNull(info.getPassword());
            LoginTokenCredentials credentials = (LoginTokenCredentials) info.get("user.jcr.credentials");
            // what the repository asks the login module
            assertEquals("ana", instance.verified(credentials).user);
            assertEquals(LoginToken.fingerprint("{SHA-256}aa-1000-first"), instance.verified(credentials).password);
            assertNull(instance.verified(new LoginTokenCredentials("admin", credentials.token())));
            assertTrue(answer.cookies.isEmpty(), "a fresh cookie is not written again");
            // signed in by the cookie: the request goes on, no new cookie
            assertFalse(instance.authenticationSucceeded(request("GET", "/content/site/members.html", cookie, Map.of()), answer.response(), info));
            assertTrue(answer.cookies.isEmpty());
        }
    }

    @Test
    void notOnAnInstanceWithAnotherKey() {
        String cookie = signIn(one, "ana");
        Answer answer = new Answer();
        assertNull(stranger.extractCredentials(request("GET", "/content/site/members.html", cookie, Map.of()), answer.response()));
        assertEquals(List.of("studio-login=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"), answer.cookies);
        assertNull(stranger.verified(new LoginTokenCredentials("ana", cookie)));
    }

    @Test
    void noCookieNoUserAndRubbishIsRemoved() {
        Answer answer = new Answer();
        assertNull(two.extractCredentials(request("GET", "/content/site/en.html", null, Map.of()), answer.response()));
        assertTrue(answer.cookies.isEmpty());
        assertNull(two.extractCredentials(request("GET", "/content/site/en.html", "1.YWRtaW4.1.99999999999999.AAAA", Map.of()), answer.response()));
        assertEquals(1, answer.cookies.size());
        // a name and a password sent anywhere but to the login address are not looked at
        assertNull(two.extractCredentials(request("POST", "/content/site/en.html", null, Map.of("j_username", "ana", "j_password", "secret")), new Answer().response()));
        assertNull(two.extractCredentials(request("GET", "/x/j_security_check", null, Map.of("j_username", "ana", "j_password", "secret")), new Answer().response()));
    }

    @Test
    void signingOutAndARefusalRemoveTheCookie() {
        String cookie = signIn(one, "ana");
        Answer answer = new Answer();
        two.dropCredentials(request("GET", "/system/sling/logout", cookie, Map.of()), answer.response());
        assertEquals(List.of("studio-login=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"), answer.cookies);
        // the user does not exist on this instance: the repository refuses, the cookie goes
        Answer refused = new Answer();
        two.authenticationFailed(request("GET", "/content/site/members.html", cookie, Map.of()), refused.response(), new AuthenticationInfo("FORM", "ana"));
        assertEquals(1, refused.cookies.size());
        // nothing to remove, nothing sent
        Answer nothing = new Answer();
        two.dropCredentials(request("GET", "/system/sling/logout", null, Map.of()), nothing.response());
        assertTrue(nothing.cookies.isEmpty());
    }

    @Test
    void renewedWhenHalfItsTimeIsOver() throws IOException {
        byte[] key = new LoginKey(dir.resolve("two/login.key")).get(0);
        long now = System.currentTimeMillis();
        String fp = LoginToken.fingerprint("{SHA-256}aa-1000-first");
        String old = LoginToken.issue(key, "ana", fp, now - 20 * 60_000, now + 10 * 60_000);
        Answer answer = new Answer();
        HttpServletRequest visit = request("GET", "/content/site/members.html", old, Map.of());
        AuthenticationInfo info = two.extractCredentials(visit, answer.response());
        assertEquals("ana", info.getUser());
        assertTrue(answer.cookies.isEmpty(), "not before the repository knows the user");
        assertFalse(two.authenticationSucceeded(visit, answer.response(), info));
        assertEquals(1, answer.cookies.size());
        String renewed = answer.cookies.get(0).substring("studio-login=".length(), answer.cookies.get(0).indexOf(';'));
        assertTrue(LoginToken.verify(key, renewed, now).expires > now + 29 * 60_000);
        assertEquals(fp, LoginToken.verify(key, renewed, now).password, "renewed with the password it was made with");
        // over: not signed in
        String over = LoginToken.issue(key, "ana", fp, now - 40 * 60_000, now - 1);
        assertNull(two.extractCredentials(request("GET", "/content/site/members.html", over, Map.of()), new Answer().response()));
    }

    @Test
    void aCookieSaysWhichPasswordItWasMadeWith() {
        String before = signIn(one, "ana");
        hashes.put("ana", "{SHA-256}aa-1000-second");
        String after = signIn(two, "ana");
        LoginToken first = one.verified(new LoginTokenCredentials("ana", before));
        LoginToken second = one.verified(new LoginTokenCredentials("ana", after));
        // the login module compares this with the hash the user has now: the first cookie ends there
        assertNotEquals(first.password, second.password);
        assertEquals(LoginToken.fingerprint("{SHA-256}aa-1000-second"), second.password);
        // a password that cannot be read: no cookie, rather than one that no password stands behind
        hashes.remove("bob");
        LoginHandler broken = handler(dir.resolve("two/login.key"));
        broken.passwords(user -> { throw new IllegalStateException("no access"); });
        HttpServletRequest post = request("POST", "/login.html/j_security_check", null, Map.of("j_username", "bob", "j_password", "secret", "j_validate", "true"));
        Answer answer = new Answer();
        broken.authenticationSucceeded(post, answer.response(), broken.extractCredentials(post, answer.response()));
        assertTrue(answer.cookies.isEmpty());
    }

    @Test
    void secureBehindHttps() {
        HttpServletRequest post = request("POST", "/login.html/j_security_check", null,
                Map.of("j_username", "ana", "j_password", "secret", "j_validate", "true", "header:X-Forwarded-Proto", "https"));
        Answer answer = new Answer();
        one.authenticationSucceeded(post, answer.response(), one.extractCredentials(post, answer.response()));
        assertTrue(answer.cookies.get(0).endsWith("; HttpOnly; SameSite=Lax; Secure"), answer.cookies.get(0));
    }

    @Test
    void tooManyWrongPasswordsAreRefusedWithoutAskingTheRepository() {
        one.throttle(3, 50, 15, new String[] {"127.0.0.1"});
        Map<String, String> guess = Map.of("j_username", "ana", "j_password", "guess", "j_validate", "true");
        for (int i = 0; i < 3; i++) {
            HttpServletRequest post = request("POST", "/login.html/j_security_check", null, guess);
            AuthenticationInfo info = one.extractCredentials(post, new Answer().response());
            assertEquals("ana", info.getUser(), "attempt " + (i + 1) + " goes to the repository");
            one.authenticationFailed(post, new Answer().response(), info); // the repository said no
        }
        HttpServletRequest fourth = request("POST", "/login.html/j_security_check", null, guess);
        assertSame(AuthenticationInfo.FAIL_AUTH, one.extractCredentials(fourth, new Answer().response()));
        // the right password from there is refused too, until the minutes are over
        Map<String, String> right = Map.of("j_username", "ana", "j_password", "secret", "j_validate", "true");
        assertSame(AuthenticationInfo.FAIL_AUTH, one.extractCredentials(request("POST", "/login.html/j_security_check", null, right), new Answer().response()));
        // but ana herself, from elsewhere, is not locked out by whoever guesses
        Map<String, String> elsewhere = Map.of("j_username", "ana", "j_password", "secret", "j_validate", "true", "remote", "198.51.100.7");
        assertEquals("ana", one.extractCredentials(request("POST", "/login.html/j_security_check", null, elsewhere), new Answer().response()).getUser());
        // behind the cache the client is the address before it
        Map<String, String> proxied = Map.of("j_username", "ana", "j_password", "secret", "j_validate", "true", "remote", "127.0.0.1",
                "header:X-Forwarded-For", "203.0.113.9, 127.0.0.1");
        assertSame(AuthenticationInfo.FAIL_AUTH, one.extractCredentials(request("POST", "/login.html/j_security_check", null, proxied), new Answer().response()));
    }

    // ---------- a request and a response, as far as the handler looks at them

    private static HttpServletRequest request(String method, String uri, String cookie, Map<String, String> parameters) {
        Map<String, Object> attributes = new HashMap<>();
        return (HttpServletRequest) Proxy.newProxyInstance(LoginHandlerTest.class.getClassLoader(), new Class<?>[] {HttpServletRequest.class}, (proxy, call, args) -> {
            switch (call.getName()) {
                case "getMethod": return method;
                case "getRequestURI": return uri;
                case "getContextPath": return "";
                case "getParameter": return parameters.get((String) args[0]);
                case "getHeader": return parameters.get("header:" + args[0]);
                case "getCookies": return cookie == null ? null : new Cookie[] {new Cookie("other", "x"), new Cookie("studio-login", cookie)};
                case "isSecure": return false;
                case "getRemoteAddr": return parameters.getOrDefault("remote", "203.0.113.9");
                case "getAttribute": return attributes.get((String) args[0]);
                case "setAttribute": attributes.put((String) args[0], args[1]); return null;
                default: throw new UnsupportedOperationException(call.getName());
            }
        });
    }

    private static final class Answer {
        final List<String> cookies = new ArrayList<>();

        HttpServletResponse response() {
            return (HttpServletResponse) Proxy.newProxyInstance(LoginHandlerTest.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class}, (proxy, call, args) -> {
                switch (call.getName()) {
                    case "addHeader":
                        assertEquals("Set-Cookie", args[0]);
                        cookies.add((String) args[1]);
                        return null;
                    case "isCommitted": return false;
                    default: throw new UnsupportedOperationException(call.getName());
                }
            });
        }
    }
}
