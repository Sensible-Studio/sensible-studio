package io.sensible.studio.auth.impl;

import java.util.Collections;
import java.util.function.Function;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.security.auth.spi.LoginModule;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.jaas.LoginModuleFactory;
import org.apache.sling.auth.core.AuthUtil;
import org.apache.sling.auth.core.spi.AuthenticationFeedbackHandler;
import org.apache.sling.auth.core.spi.AuthenticationHandler;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.apache.sling.auth.core.spi.DefaultAuthenticationFeedbackHandler;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A visitor's sign-in that every publish instance of a farm accepts. The login form posts the name and
 * the password to {@code .../j_security_check} as before; when the repository accepts them, the visitor
 * gets a cookie that says who they are and until when, signed with the farm's key ({@link LoginKey}).
 * Any instance with the same key reads it ({@link LoginToken}) and signs the user in
 * ({@link CookieLoginModule}), so a request may go to whichever instance is free. Nothing is stored on an
 * instance: signing out removes the cookie from the browser, and a cookie somebody copied before stays
 * good until its time is over, which is why that time is short and renewed while the visitor is active,
 * or until the password changes: the cookie carries a fingerprint of it ({@link LoginToken}).
 *
 * <p>Active only where it is configured, which is on the publish instances. It stands before Sling's form
 * sign-in, whose cookie was good on the one instance that wrote it.
 */
@Component(
        service = AuthenticationHandler.class,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {
                AuthenticationHandler.PATH_PROPERTY + "=/",
                AuthenticationHandler.TYPE_PROPERTY + "=" + HttpServletRequest.FORM_AUTH,
                "service.ranking:Integer=1000",
                "service.description=Sensible Studio: sign-in valid on every publish instance"
        })
@Designate(ocd = LoginHandler.Config.class)
public final class LoginHandler extends DefaultAuthenticationFeedbackHandler implements AuthenticationHandler {

    @ObjectClassDefinition(
            name = "Sensible Studio: sign-in valid on every publish instance",
            description = "Visitors sign in with a signed cookie that every publish instance holding the same key accepts.")
    public @interface Config {

        @AttributeDefinition(name = "Cookie", description = "The name of the sign-in cookie. The cache in front must know it as a login cookie.")
        String cookie_name() default "studio-login";

        @AttributeDefinition(name = "Minutes", description = "How long a sign-in lasts without a request. It is renewed while the visitor is active.")
        int timeout_minutes() default 30;

        @AttributeDefinition(name = "Key file", description = "The file with the key the cookies are signed with, the same on every publish instance of the farm. "
                + "A relative path is below the instance's home folder. Written on the first start if it is not there.")
        String key_file() default "login.key";

        @AttributeDefinition(name = "Login page", description = "Where a visitor who has to sign in is sent.")
        String login_form() default "/libs/granite/core/content/login.html";

        @AttributeDefinition(name = "Wrong passwords per user", description = "From one client for one user name within the minutes below; "
                + "then that client may not try that name until they are over.")
        int throttle_attempts() default 5;

        @AttributeDefinition(name = "Wrong passwords per client", description = "From one client for any user names within the minutes below; "
                + "then that client may not sign in until they are over.")
        int throttle_client_attempts() default 50;

        @AttributeDefinition(name = "Minutes counted", description = "How far back wrong passwords count.")
        int throttle_minutes() default 15;

        @AttributeDefinition(name = "Proxies in front", description = "Addresses whose X-Forwarded-For is believed (the cache, the web server): "
                + "the client is the nearest address before them.")
        String[] trusted_proxies() default {"127.0.0.1", "::1", "0:0:0:0:0:0:0:1"};
    }

    private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);
    private static final String LOGIN_SUFFIX = "/j_security_check";
    /** Where Sling's resource resolver looks for credentials that are not a name and a password. */
    private static final String JCR_CREDENTIALS = "user.jcr.credentials";

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile ResourceResolverFactory resolverFactory;
    private volatile Function<String, String> passwords = this::readPasswordHash;

    private String cookieName;
    private long timeoutMs;
    private String loginForm;
    private LoginKey key;
    private ServiceRegistration<?> loginModule;
    private volatile SignInThrottle throttle = new SignInThrottle(5, 50, 15 * 60_000L);
    private volatile List<String> trustedProxies = Arrays.asList("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    @Activate
    void activate(Config config, BundleContext context) {
        String home = context.getProperty("sling.home");
        Path file = Paths.get(config.key_file());
        if (!file.isAbsolute()) {
            file = Paths.get(home == null ? "." : home).resolve(file);
        }
        configure(config.cookie_name(), config.timeout_minutes(), config.login_form(), new LoginKey(file.toAbsolutePath().normalize()));
        throttle(config.throttle_attempts(), config.throttle_client_attempts(), config.throttle_minutes(), config.trusted_proxies());
        try {
            key.get(System.currentTimeMillis());
            LOG.info("Sign-in cookie {} for {} minutes, signed with the key in {}", cookieName, timeoutMs / 60_000, key.file());
        } catch (IOException e) {
            LOG.error("No sign-in until the key file {} can be read: {}", key.file(), e.toString());
        }
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("service.description", "Sensible Studio: the holder of a valid sign-in cookie is its user");
        properties.put(LoginModuleFactory.JAAS_RANKING, 1000);
        properties.put(LoginModuleFactory.JAAS_CONTROL_FLAG, "optional");
        properties.put(LoginModuleFactory.JAAS_REALM_NAME, "jackrabbit.oak");
        LoginModuleFactory factory = new LoginModuleFactory() {
            @Override
            public LoginModule createLoginModule() {
                return new CookieLoginModule(LoginHandler.this);
            }
        };
        loginModule = context.registerService(LoginModuleFactory.class.getName(), factory, properties);
    }

    /** For the tests too. */
    void configure(String cookie, int minutes, String form, LoginKey loginKey) {
        cookieName = cookie == null || cookie.trim().isEmpty() ? "studio-login" : cookie.trim();
        timeoutMs = Math.max(1, minutes) * 60_000L;
        loginForm = form == null || form.trim().isEmpty() ? "/libs/granite/core/content/login.html" : form.trim();
        key = loginKey;
    }

    /** For the tests too. */
    void throttle(int perUser, int perClient, int minutes, String[] proxies) {
        throttle = new SignInThrottle(perUser, perClient, Math.max(1, minutes) * 60_000L);
        trustedProxies = proxies == null ? Collections.emptyList() : Arrays.asList(proxies);
    }

    private String clientOf(HttpServletRequest request) {
        return SignInThrottle.client(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxies);
    }

    @Deactivate
    void deactivate() {
        if (loginModule != null) {
            loginModule.unregister();
            loginModule = null;
        }
    }

    // ---------- AuthenticationHandler

    @Override
    public AuthenticationInfo extractCredentials(HttpServletRequest request, HttpServletResponse response) {
        // the login form: a name and a password, for the repository to judge
        if (isLogin(request)) {
            String user = request.getParameter("j_username");
            String password = request.getParameter("j_password");
            if (user != null && password != null) {
                if (!throttle.allowed(user, clientOf(request), System.currentTimeMillis())) {
                    // too many wrong passwords: refused as a wrong one, without asking the repository
                    LOG.info("Sign-in as {} from {} refused: too many wrong passwords", user, clientOf(request));
                    request.setAttribute(FAILURE_REASON, "Too many attempts. Try again later.");
                    return AuthenticationInfo.FAIL_AUTH;
                }
                AuthenticationInfo info = new AuthenticationInfo(HttpServletRequest.FORM_AUTH, user, password.toCharArray());
                info.put("$$auth.info.login$$", new Object());
                // where to go afterwards, if the form did not say
                if (!AuthUtil.isValidateRequest(request)) {
                    AuthUtil.setLoginResourceAttribute(request, request.getContextPath());
                }
                return info;
            }
        }
        // the cookie of an earlier sign-in, here or on another instance
        String token = cookie(request);
        if (token == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        LoginToken valid = LoginToken.verify(currentKey(now), token, now);
        if (valid == null) {
            // over, forged, or signed with another key: a visitor who is not signed in
            clearCookie(request, response);
            return null;
        }
        AuthenticationInfo info = new AuthenticationInfo(HttpServletRequest.FORM_AUTH, valid.user);
        info.put(JCR_CREDENTIALS, new LoginTokenCredentials(valid.user, token));
        return info;
    }

    @Override
    public boolean requestCredentials(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // another way of signing in was asked for
        String asked = request.getParameter(REQUEST_LOGIN_PARAMETER);
        if (asked != null && !HttpServletRequest.FORM_AUTH.equals(asked)) {
            return false;
        }
        // A sign-in attempt that did not come from the login page is not answered with the login page (a script
        // gets its 401 or 403), except one: a visitor who typed a wrong password into a site's own sign-in form.
        // Their browser would be left on a bare line of text at the address of the check; they get the login page,
        // which says what went wrong and takes them back to the page they came from.
        String back = null;
        if (!AuthUtil.checkReferer(request, loginForm)) {
            back = isLogin(request) && !AuthUtil.isValidateRequest(request) ? cameFrom(request) : null;
            if (back == null) {
                return false;
            }
        }
        String resource = back != null ? back : AuthUtil.setLoginResourceAttribute(request, request.getRequestURI());
        Map<String, String> parameters = new HashMap<>();
        parameters.put("resource", resource);
        Object reason = request.getAttribute(FAILURE_REASON);
        if (reason != null) {
            parameters.put(FAILURE_REASON, reason.toString());
            Object code = request.getAttribute(FAILURE_REASON_CODE);
            parameters.put(FAILURE_REASON_CODE, code != null ? code.toString() : reason instanceof Enum ? ((Enum<?>) reason).name() : reason.toString());
        }
        try {
            AuthUtil.sendRedirect(request, response, loginForm, parameters);
        } catch (IOException e) {
            LOG.error("Could not send the visitor to the login page", e);
        }
        return true;
    }

    /**
     * The page of this site a browser's sign-in form was on: the path (and query) of a Referer on this host, for a
     * request that asks for a page. Null for anything else.
     */
    static String cameFrom(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String referer = request.getHeader("Referer");
        if (accept == null || !accept.contains("text/html") || referer == null) {
            return null;
        }
        try {
            java.net.URI u = java.net.URI.create(referer);
            String path = u.getRawPath();
            if (u.getHost() == null || !u.getHost().equalsIgnoreCase(request.getServerName()) || path == null
                    || !path.startsWith("/") || path.startsWith("//") || path.contains("j_security_check")) {
                return null;
            }
            int port = u.getPort() > 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
            if (port != request.getServerPort()) {
                return null;
            }
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void dropCredentials(HttpServletRequest request, HttpServletResponse response) {
        if (cookie(request) != null) {
            clearCookie(request, response);
        }
    }

    // ---------- AuthenticationFeedbackHandler

    @Override
    public void authenticationFailed(HttpServletRequest request, HttpServletResponse response, AuthenticationInfo authInfo) {
        // a cookie the repository did not take (the user is gone or disabled here), or a wrong password
        if (isLogin(request) && authInfo != null && authInfo.getPassword() != null) {
            throttle.failed(authInfo.getUser(), clientOf(request), System.currentTimeMillis());
        }
        if (cookie(request) != null) {
            clearCookie(request, response);
        }
    }

    @Override
    public boolean authenticationSucceeded(HttpServletRequest request, HttpServletResponse response, AuthenticationInfo authInfo) {
        if (!isLogin(request) || authInfo.getPassword() == null) {
            // signed in by the cookie, and the repository knows the user: the request goes on, with a
            // new cookie once half the time of the old one is over
            Object credentials = authInfo.get(JCR_CREDENTIALS);
            if (credentials instanceof LoginTokenCredentials) {
                long now = System.currentTimeMillis();
                LoginToken valid = LoginToken.verify(currentKey(now), ((LoginTokenCredentials) credentials).token(), now);
                if (valid != null && valid.expires - now < timeoutMs / 2) {
                    setCookie(request, response, valid.user, valid.password, now);
                }
            }
            return false;
        }
        // the repository took the name and the password: from here on the cookie stands for them, as long as
        // the password is the same
        throttle.succeeded(authInfo.getUser(), clientOf(request));
        String password = passwordOf(authInfo.getUser());
        if (password != null) {
            setCookie(request, response, authInfo.getUser(), password, System.currentTimeMillis());
        }
        if (AuthUtil.isValidateRequest(request)) {
            return false; // the form only asked whether they are right; Sling answers that
        }
        if (DefaultAuthenticationFeedbackHandler.handleRedirect(request, response)) {
            return true;
        }
        String target = AuthUtil.getLoginResource(request, null);
        if (target == null || response.isCommitted()) {
            return false;
        }
        // only to a place on this site
        if (!AuthUtil.isRedirectValid(request, target)) {
            target = request.getContextPath() + "/";
        }
        try {
            response.resetBuffer();
            response.sendRedirect(target);
            return true;
        } catch (IOException e) {
            LOG.error("Could not send the visitor on to " + target, e);
            return false;
        }
    }

    // ---------- for the login module

    /** What a cookie's content says, checked once more; null if it stands for nobody. */
    LoginToken verified(LoginTokenCredentials credentials) {
        long now = System.currentTimeMillis();
        LoginToken valid = LoginToken.verify(currentKey(now), credentials.token(), now);
        return valid != null && valid.user.equals(credentials.user()) ? valid : null;
    }

    /** The fingerprint of a user's password hash as the repository keeps it now; null if it cannot be read. */
    private String passwordOf(String user) {
        Function<String, String> lookup = passwords;
        String hash;
        try {
            hash = lookup.apply(user);
        } catch (RuntimeException e) {
            LOG.warn("Could not read the password of {}: {}", user, e.toString());
            return null;
        }
        return LoginToken.fingerprint(hash);
    }

    /** The password hash, read with this bundle's service user (read access to the users, nothing else). */
    private String readPasswordHash(String user) {
        ResourceResolverFactory factory = resolverFactory;
        if (factory == null) {
            throw new IllegalStateException("no resource resolver factory");
        }
        try (ResourceResolver resolver = factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, "login"))) {
            Session session = resolver.adaptTo(Session.class);
            if (!(session instanceof JackrabbitSession)) {
                throw new IllegalStateException("no user management");
            }
            Authorizable found = ((JackrabbitSession) session).getUserManager().getAuthorizable(user);
            if (found == null || found.isGroup()) {
                throw new IllegalStateException(user + " is no user here");
            }
            String property = found.getPath() + "/rep:password";
            return session.propertyExists(property) ? session.getProperty(property).getString() : null;
        } catch (LoginException | RepositoryException e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    /** For the tests: where password hashes come from. */
    void passwords(Function<String, String> lookup) {
        passwords = lookup;
    }

    // ---------- the cookie

    private static boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().endsWith(LOGIN_SUFFIX);
    }

    private byte[] currentKey(long now) {
        try {
            return key.get(now);
        } catch (IOException e) {
            LOG.error("The sign-in key file {} cannot be read: {}", key.file(), e.toString());
            return null;
        }
    }

    private String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isEmpty()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private void setCookie(HttpServletRequest request, HttpServletResponse response, String user, String password, long now) {
        byte[] current = currentKey(now);
        if (current == null || user == null || response.isCommitted()) {
            return;
        }
        String token;
        try {
            token = LoginToken.issue(current, user, password, now, now + timeoutMs);
        } catch (IllegalArgumentException e) {
            LOG.warn("No sign-in cookie for a user id of that length");
            return;
        }
        response.addHeader("Set-Cookie", header(request, token, -1));
    }

    private void clearCookie(HttpServletRequest request, HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.addHeader("Set-Cookie", header(request, "", 0));
        }
    }

    /** A cookie for this browser session only, kept from scripts and from requests other sites start. */
    private String header(HttpServletRequest request, String value, int maxAge) {
        String path = request.getContextPath();
        StringBuilder header = new StringBuilder(cookieName).append('=').append(value)
                .append("; Path=").append(path == null || path.isEmpty() ? "/" : path);
        if (maxAge >= 0) {
            header.append("; Max-Age=").append(maxAge);
        }
        header.append("; HttpOnly; SameSite=Lax");
        if (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))) {
            header.append("; Secure");
        }
        return header.toString();
    }

    @Override
    public String toString() {
        return "Sensible Studio sign-in valid on every publish instance";
    }
}
