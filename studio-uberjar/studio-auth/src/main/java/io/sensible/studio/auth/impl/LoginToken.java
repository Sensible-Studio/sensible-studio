package io.sensible.studio.auth.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What the sign-in cookie holds: who signed in, when, until when, a fingerprint of the user's password
 * at that time, and a signature over all of it made with a key that every publish instance of a farm
 * has. Nothing is kept on the instance, so the instance that reads the cookie need not be the one that
 * wrote it; a password changed since (on any instance: users travel between them) ends the cookie.
 *
 * <p>{@code 2.<user, base64url>.<issued, ms>.<expires, ms>.<password fingerprint>.<HMAC-SHA256 of all before, base64url>}
 */
final class LoginToken {

    /** Longer than any token this class writes for a user id of {@link #MAX_USER} bytes. */
    static final int MAX_LENGTH = 1024;
    static final int MAX_USER = 256;
    private static final String VERSION = "2";
    /** How far ahead of this instance's clock another instance's may be. */
    private static final long CLOCK_SKEW_MS = 60_000;

    final String user;
    final long issued;
    final long expires;
    /** {@link #fingerprint} of the user's password hash when the cookie was made. */
    final String password;

    private LoginToken(String user, long issued, long expires, String password) {
        this.user = user;
        this.issued = issued;
        this.expires = expires;
        this.password = password;
    }

    /**
     * A short digest of the password hash the repository keeps for a user (never of the password): the
     * same as long as the password is, another after any change. null (no password) has one of its own.
     */
    static String fingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(("studio-login\n" + (passwordHash == null ? "" : passwordHash)).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(digest, 12));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is part of every Java", e);
        }
    }

    static String issue(byte[] key, String user, String password, long issued, long expires) {
        byte[] name = user.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > MAX_USER) {
            throw new IllegalArgumentException("not a user id to sign in with");
        }
        if (password == null || !password.matches("[A-Za-z0-9_-]{16}")) {
            throw new IllegalArgumentException("not a password fingerprint");
        }
        String payload = VERSION + '.' + Base64.getUrlEncoder().withoutPadding().encodeToString(name) + '.' + issued + '.' + expires + '.' + password;
        return payload + '.' + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(key, payload));
    }

    /** The token's content if this key signed it and its time has not passed; otherwise null. */
    static LoginToken verify(byte[] key, String token, long now) {
        if (key == null || token == null || token.isEmpty() || token.length() > MAX_LENGTH) {
            return null;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            return null;
        }
        byte[] given;
        byte[] name;
        try {
            given = Base64.getUrlDecoder().decode(parts[5]);
            name = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // the signature first: nothing of the rest is looked at unless it holds
        String payload = token.substring(0, token.lastIndexOf('.'));
        if (!MessageDigest.isEqual(sign(key, payload), given)) {
            return null;
        }
        long issued = number(parts[2]);
        long expires = number(parts[3]);
        if (name.length == 0 || name.length > MAX_USER || issued < 0 || expires <= now || issued > now + CLOCK_SKEW_MS) {
            return null;
        }
        return new LoginToken(new String(name, StandardCharsets.UTF_8), issued, expires, parts[4]);
    }

    private static long number(String text) {
        if (text.isEmpty() || text.length() > 15) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return -1;
            }
        }
        return Long.parseLong(text);
    }

    private static byte[] sign(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is part of every Java", e);
        }
    }
}
