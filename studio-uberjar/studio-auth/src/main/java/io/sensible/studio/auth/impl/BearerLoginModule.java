package io.sensible.studio.auth.impl;

import java.util.Collections;
import java.util.Set;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.jcr.SimpleCredentials;
import javax.security.auth.login.LoginException;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.spi.security.authentication.AbstractLoginModule;
import org.apache.jackrabbit.oak.spi.security.authentication.PreAuthenticatedLogin;

/**
 * Tells the repository that the holder of a valid OAuth access token is the user the token was
 * given for: the token is looked up again here, and the user has to exist, be enabled and be no
 * system user. The repository's own module then signs that user in, with the user's permissions.
 */
final class BearerLoginModule extends AbstractLoginModule {

    @SuppressWarnings("rawtypes")
    private static final Set<Class> SUPPORTED = Collections.<Class>singleton(BearerCredentials.class);

    private final OAuthService service;

    BearerLoginModule(OAuthService service) {
        this.service = service;
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
        if (!(credentials instanceof BearerCredentials)) {
            return false;
        }
        BearerCredentials bearer = (BearerCredentials) credentials;
        OAuth.Access access = service.oauth().access(bearer.token());
        if (access == null || !access.user.equals(bearer.user()) || !maySignIn(access.user)) {
            return false;
        }
        sharedState.put(SHARED_KEY_PRE_AUTH_LOGIN, new PreAuthenticatedLogin(access.user));
        sharedState.put(SHARED_KEY_CREDENTIALS, new SimpleCredentials(access.user, new char[0]));
        sharedState.put(SHARED_KEY_LOGIN_NAME, access.user);
        return false;
    }

    private boolean maySignIn(String id) {
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
            return !user.isDisabled() && !user.isSystemUser() && !"anonymous".equals(user.getID());
        } catch (RepositoryException e) {
            return false;
        }
    }

    @Override
    public boolean commit() {
        return false;
    }
}
