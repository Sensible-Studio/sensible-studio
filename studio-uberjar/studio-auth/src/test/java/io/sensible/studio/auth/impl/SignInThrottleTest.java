package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class SignInThrottleTest {

    private static final long MIN = 60_000L;

    @Test
    void aPairIsRefusedAfterTooManyWrongPasswordsUntilTheyAreOld() {
        SignInThrottle t = new SignInThrottle(3, 100, 15 * MIN);
        for (int i = 0; i < 3; i++) {
            assertTrue(t.allowed("ana", "1.2.3.4", i));
            t.failed("Ana", "1.2.3.4", i);
        }
        assertFalse(t.allowed("ana", "1.2.3.4", 10), "names compared without case");
        assertTrue(t.allowed("ana", "5.6.7.8", 10), "another client");
        assertTrue(t.allowed("bob", "1.2.3.4", 10), "another name");
        assertTrue(t.allowed("ana", "1.2.3.4", 15 * MIN + 1), "the window has passed");
    }

    @Test
    void aRightPasswordStartsThePairAgain() {
        SignInThrottle t = new SignInThrottle(3, 100, 15 * MIN);
        t.failed("ana", "c", 0);
        t.failed("ana", "c", 1);
        t.succeeded("ana", "c");
        t.failed("ana", "c", 2);
        t.failed("ana", "c", 3);
        assertTrue(t.allowed("ana", "c", 4));
    }

    @Test
    void aClientGuessingManyNamesIsRefused() {
        SignInThrottle t = new SignInThrottle(5, 10, 15 * MIN);
        for (int i = 0; i < 10; i++) {
            t.failed("user" + i, "c", i);
        }
        assertFalse(t.allowed("someone-new", "c", 20));
        assertTrue(t.allowed("someone-new", "d", 20));
    }

    @Test
    void whatIsRememberedIsBounded() {
        SignInThrottle t = new SignInThrottle(5, 1_000_000, 15 * MIN);
        for (int i = 0; i < SignInThrottle.MAX_KEYS + 500; i++) {
            t.failed("user" + i, "c" + i, 0);
        }
        assertTrue(t.size() <= SignInThrottle.MAX_KEYS);
    }

    @Test
    void theClientBehindTrustedProxies() {
        List<String> proxies = Arrays.asList("127.0.0.1", "10.0.0.5");
        assertEquals("203.0.113.9", SignInThrottle.client("127.0.0.1", "203.0.113.9, 10.0.0.5", proxies));
        assertEquals("203.0.113.9", SignInThrottle.client("127.0.0.1", "198.51.100.1, 203.0.113.9", proxies), "what the client wrote itself is not believed");
        assertEquals("198.51.100.2", SignInThrottle.client("198.51.100.2", "1.1.1.1", proxies), "not from a proxy: its own address");
        assertEquals("127.0.0.1", SignInThrottle.client("127.0.0.1", null, proxies));
        assertEquals("203.0.113.9", SignInThrottle.client("[0:0:0:0:0:0:0:1]", "203.0.113.9", Arrays.asList("::1")), "Jetty writes IPv6 in brackets");
    }
}
