package io.sensible.studio.auth.impl;

import java.util.Collections;
import java.util.Set;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.jcr.SimpleCredentials;
import javax.security.auth.login.LoginException;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.security.authentication.AbstractLoginModule;
import org.apache.jackrabbit.oak.spi.security.authentication.PreAuthenticatedLogin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the repository that the holder of a valid sign-in cookie is the user the cookie names, without a
 * password: the cookie's signature is checked again here, the user has to exist on this instance and be
 * neither disabled nor a system user, and the password has to be the one the user had when the cookie was
 * made. The repository's own module then signs that user in.
 */
final class CookieLoginModule extends AbstractLoginModule {

    private static final Logger LOG = LoggerFactory.getLogger(CookieLoginModule.class);
    @SuppressWarnings("rawtypes")
    private static final Set<Class> SUPPORTED = Collections.<Class>singleton(LoginTokenCredentials.class);

    private final LoginHandler handler;

    CookieLoginModule(LoginHandler handler) {
        this.handler = handler;
    }

    @Override
    @SuppressWarnings("rawtypes")
    protected Set<Class> getSupportedCredentials() {
        return SUPPORTED;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean login() throws LoginException {
        Credentials credentials = getCredentials();
        if (!(credentials instanceof LoginTokenCredentials)) {
            return false;
        }
        LoginToken token = handler.verified((LoginTokenCredentials) credentials);
        if (token == null) {
            LOG.debug("not a valid sign-in cookie");
            return false;
        }
        String user = token.user;
        if (!maySignIn(user, token.password)) {
            LOG.debug("{} holds a valid sign-in cookie but is no user who may sign in here", user);
            return false;
        }
        sharedState.put(SHARED_KEY_PRE_AUTH_LOGIN, new PreAuthenticatedLogin(user));
        sharedState.put(SHARED_KEY_CREDENTIALS, new SimpleCredentials(user, new char[0]));
        sharedState.put(SHARED_KEY_LOGIN_NAME, user);
        // false: this module adds nothing of its own; the repository's module, which comes after, does the rest
        return false;
    }

    private boolean maySignIn(String id, String password) {
        UserManager users = getUserManager();
        if (users == null) {
            return false;
        }
        try {
            Authorizable found = users.getAuthorizable(id);
            if (found == null || found.isGroup()) {
                return false;
            }
            User user = (User) found;
            // not the visitor who is not signed in either: nobody signs in as that
            if (user.isDisabled() || user.isSystemUser() || "anonymous".equals(user.getID())) {
                return false;
            }
            // the password the cookie was made with: changed since, the cookie ends (the hash is not
            // offered by the user API; the login module's own view of the repository has it)
            Root root = getRoot();
            if (root == null) {
                return false;
            }
            PropertyState hash = root.getTree(user.getPath()).getProperty("rep:password");
            String now = LoginToken.fingerprint(hash == null ? null : hash.getValue(Type.STRING));
            if (!MessageDigest.isEqual(now.getBytes(StandardCharsets.US_ASCII), password.getBytes(StandardCharsets.US_ASCII))) {
                LOG.debug("the password of {} changed since the cookie was made", id);
                return false;
            }
            return true;
        } catch (RepositoryException e) {
            LOG.warn("could not look up {}: {}", id, e.toString());
            return false;
        }
    }

    @Override
    public boolean commit() {
        return false;
    }
}
