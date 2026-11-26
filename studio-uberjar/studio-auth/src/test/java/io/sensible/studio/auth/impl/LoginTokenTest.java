package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class LoginTokenTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OTHER = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);
    private static final long NOW = 1_800_000_000_000L;
    private static final String FP = LoginToken.fingerprint("{SHA-256}5d8d36bb2f0b6c0e-1000-abcdef");

    @Test
    void whatOneInstanceWritesAnotherWithTheSameKeyReads() {
        String token = LoginToken.issue(KEY, "ana.pop@example.com", FP, NOW, NOW + 60_000);
        LoginToken read = LoginToken.verify(KEY.clone(), token, NOW + 59_999);
        assertNotNull(read);
        assertEquals("ana.pop@example.com", read.user);
        assertEquals(NOW, read.issued);
        assertEquals(NOW + 60_000, read.expires);
        assertTrue(token.length() < LoginToken.MAX_LENGTH);
        // names that are not ASCII, and with the separator in them
        assertEquals("zoë.1.2", LoginToken.verify(KEY, LoginToken.issue(KEY, "zoë.1.2", FP, NOW, NOW + 1), NOW).user);
    }

    @Test
    void notAfterItsTimeAndNotWithAnotherKey() {
        String token = LoginToken.issue(KEY, "ana", FP, NOW, NOW + 60_000);
        assertNull(LoginToken.verify(KEY, token, NOW + 60_000));
        assertNull(LoginToken.verify(OTHER, token, NOW));
        assertNull(LoginToken.verify(null, token, NOW));
        // issued in a future no clock difference explains
        assertNull(LoginToken.verify(KEY, LoginToken.issue(KEY, "ana", FP, NOW + 3_600_000, NOW + 7_200_000), NOW));
        assertNotNull(LoginToken.verify(KEY, LoginToken.issue(KEY, "ana", FP, NOW + 5_000, NOW + 7_200_000), NOW));
    }

    @Test
    void nothingChangedAndNothingMadeUpPasses() {
        String token = LoginToken.issue(KEY, "ana", FP, NOW, NOW + 60_000);
        String[] parts = token.split("\\.");
        String admin = Base64.getUrlEncoder().withoutPadding().encodeToString("admin".getBytes(StandardCharsets.UTF_8));
        // another user, a later end, under the old signature
        assertNull(LoginToken.verify(KEY, String.join(".", parts[0], admin, parts[2], parts[3], parts[4], parts[5]), NOW));
        assertNull(LoginToken.verify(KEY, String.join(".", parts[0], parts[1], parts[2], String.valueOf(NOW + 600_000_000L), parts[4], parts[5]), NOW));
        // no signature, an empty one, one of another length, a changed one
        assertNull(LoginToken.verify(KEY, String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]), NOW));
        assertNull(LoginToken.verify(KEY, String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4], ""), NOW));
        assertNull(LoginToken.verify(KEY, token.substring(0, token.length() - 4), NOW));
        char last = token.charAt(token.length() - 1);
        assertNull(LoginToken.verify(KEY, token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A'), NOW));
        // another version, more parts, rubbish
        assertNull(LoginToken.verify(KEY, "3" + token.substring(1), NOW));
        // another password fingerprint under the old signature
        assertNull(LoginToken.verify(KEY, String.join(".", parts[0], parts[1], parts[2], parts[3], LoginToken.fingerprint("other"), parts[5]), NOW));
        assertNull(LoginToken.verify(KEY, token + ".x", NOW));
        for (String rubbish : new String[] {"", ".", "....", "1.!!.1.2.!!", "a", "1.YW5h.-5.9999999999999.AAAA", null}) {
            assertNull(LoginToken.verify(KEY, rubbish, NOW), String.valueOf(rubbish));
        }
        assertNull(LoginToken.verify(KEY, "1." + "A".repeat(LoginToken.MAX_LENGTH), NOW));
    }

    @Test
    void aTokenCarriesThePasswordItWasMadeWith() {
        LoginToken read = LoginToken.verify(KEY, LoginToken.issue(KEY, "ana", FP, NOW, NOW + 60_000), NOW);
        assertEquals(FP, read.password);
        // the same hash, the same fingerprint; any change of it, another; no password has one of its own
        assertEquals(FP, LoginToken.fingerprint("{SHA-256}5d8d36bb2f0b6c0e-1000-abcdef"));
        assertNotEquals(FP, LoginToken.fingerprint("{SHA-256}5d8d36bb2f0b6c0e-1000-abcdeg"));
        assertNotEquals(LoginToken.fingerprint(null), FP);
        assertEquals(LoginToken.fingerprint(null), LoginToken.fingerprint(""));
        assertEquals(16, FP.length());
        assertFalse(FP.contains("{") || FP.contains("."), "nothing of the hash itself");
        // a cookie of the first version (no password in it) is not taken any more
        String payload = "1.YW5h." + NOW + "." + (NOW + 60_000);
        assertNull(LoginToken.verify(KEY, payload + ".AAAA", NOW));
        assertThrows(IllegalArgumentException.class, () -> LoginToken.issue(KEY, "ana", "not.a.fingerprint", NOW, NOW + 1));
        assertThrows(IllegalArgumentException.class, () -> LoginToken.issue(KEY, "ana", null, NOW, NOW + 1));
    }

    @Test
    void aTokenNeedsAUser() {
        assertThrows(IllegalArgumentException.class, () -> LoginToken.issue(KEY, "", FP, NOW, NOW + 1));
        assertThrows(IllegalArgumentException.class, () -> LoginToken.issue(KEY, "x".repeat(LoginToken.MAX_USER + 1), FP, NOW, NOW + 1));
        String longest = LoginToken.issue(KEY, "x".repeat(LoginToken.MAX_USER), FP, NOW, NOW + 1);
        assertTrue(longest.length() <= LoginToken.MAX_LENGTH);
        assertNotNull(LoginToken.verify(KEY, longest, NOW));
    }
}
