package io.sensible.studio.auth.impl;

import javax.jcr.Credentials;

/** An OAuth access token on its way from {@link BearerHandler} to {@link BearerLoginModule}. */
final class BearerCredentials implements Credentials {

    private static final long serialVersionUID = 1L;

    private final String user;
    private final transient String token;

    BearerCredentials(String user, String token) {
        this.user = user;
        this.token = token;
    }

    String user() {
        return user;
    }

    String token() {
        return token;
    }

    @Override
    public String toString() {
        return "OAuth access token of " + user;
    }
}
