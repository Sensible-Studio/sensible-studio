package io.sensible.studio.auth.impl;

import javax.jcr.Credentials;

/** The sign-in cookie's content on its way to the repository, which asks {@link CookieLoginModule} about it. */
final class LoginTokenCredentials implements Credentials {

    private static final long serialVersionUID = 1L;

    private final String user;
    private final String token;

    LoginTokenCredentials(String user, String token) {
        this.user = user;
        this.token = token;
    }

    String user() {
        return user;
    }

    String token() {
        return token;
    }
}
