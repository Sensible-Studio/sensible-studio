package io.sensible.studio.auth.impl;

import java.io.IOException;
import java.util.Hashtable;

import javax.security.auth.spi.LoginModule;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.jaas.LoginModuleFactory;
import org.apache.sling.auth.core.spi.AuthenticationHandler;
import org.apache.sling.auth.core.spi.AuthenticationInfo;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;

/**
 * {@code Authorization: Bearer <access token>}: the request is made as the user the token acts for
 * ({@link BearerLoginModule}), with the token's scopes noted on the request for
 * {@link OAuthScopeFilter}. A token that is unknown, over, or whose client is gone gets 401 with
 * {@code WWW-Authenticate: Bearer error="invalid_token"}, never the login page. Requests without the
 * header are left to the other ways of signing in.
 */
@Component(service = AuthenticationHandler.class, immediate = true, property = {
        AuthenticationHandler.PATH_PROPERTY + "=/",
        AuthenticationHandler.TYPE_PROPERTY + "=" + BearerHandler.TYPE,
        "service.ranking:Integer=2000",
        "service.description=Sensible Studio: OAuth access tokens" })
public final class BearerHandler implements AuthenticationHandler {

    static final String TYPE = "Bearer";
    /** The scopes of the token a request came with (a {@code Set<String>}). */
    static final String SCOPES = "io.sensible.studio.oauth.scopes";
    static final String CLIENT = "io.sensible.studio.oauth.client";
    private static final String JCR_CREDENTIALS = "user.jcr.credentials";

    @Reference
    private OAuthService service;
    private ServiceRegistration<?> loginModule;

    @Activate
    void activate(BundleContext context) {
        Hashtable<String, Object> properties = new Hashtable<>();
        properties.put("service.description", "Sensible Studio: the holder of a valid OAuth access token is its user");
        properties.put(LoginModuleFactory.JAAS_RANKING, 1100);
        properties.put(LoginModuleFactory.JAAS_CONTROL_FLAG, "optional");
        properties.put(LoginModuleFactory.JAAS_REALM_NAME, "jackrabbit.oak");
        LoginModuleFactory factory = new LoginModuleFactory() {
            @Override
            public LoginModule createLoginModule() {
                return new BearerLoginModule(service);
            }
        };
        loginModule = context.registerService(LoginModuleFactory.class.getName(), factory, properties);
    }

    @Deactivate
    void deactivate() {
        if (loginModule != null) {
            loginModule.unregister();
            loginModule = null;
        }
    }

    /** For the tests. */
    void service(OAuthService s) {
        service = s;
    }

    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    @Override
    public AuthenticationInfo extractCredentials(HttpServletRequest request, HttpServletResponse response) {
        String token = bearer(request);
        if (token == null) {
            return null;
        }
        OAuth.Access access = service.oauth().access(token);
        if (access == null) {
            refuse(response, "invalid_token", "The access token is unknown or over");
            return AuthenticationInfo.DOING_AUTH;
        }
        request.setAttribute(SCOPES, access.scopes);
        request.setAttribute(CLIENT, access.client);
        AuthenticationInfo info = new AuthenticationInfo(TYPE, access.user);
        info.put(JCR_CREDENTIALS, new BearerCredentials(access.user, token));
        return info;
    }

    static void refuse(HttpServletResponse response, String error, String description) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Bearer error=\"" + error + "\", error_description=\"" + description + "\"");
            response.setHeader("Cache-Control", "no-store");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"" + error + "\",\"error_description\":\"" + description + "\"}");
            response.flushBuffer();
        } catch (IOException e) {
            // the client went away
        }
    }

    @Override
    public boolean requestCredentials(HttpServletRequest request, HttpServletResponse response) {
        // tokens are asked for at /oauth/token, never by this handler
        return false;
    }

    @Override
    public void dropCredentials(HttpServletRequest request, HttpServletResponse response) {
        // nothing kept in the browser
    }

    @Override
    public String toString() {
        return "Sensible Studio OAuth access tokens";
    }
}
