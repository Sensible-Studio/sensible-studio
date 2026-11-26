package io.sensible.studio.auth.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginKeyTest {

    @Test
    void writtenOnTheFirstStartForItsOwnerOnly(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("conf/login.key");
        byte[] key = new LoginKey(file).get(0);
        assertEquals(32, key.length);
        assertEquals(65, Files.readAllBytes(file).length);
        try {
            assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException e) {
            // not a file system with owners
        }
        // the next start, and another instance given the same file, read the same key
        assertArrayEquals(key, new LoginKey(file).get(0));
        Path copy = dir.resolve("other/login.key");
        Files.createDirectories(copy.getParent());
        Files.copy(file, copy);
        assertArrayEquals(key, new LoginKey(copy).get(0));
        // and two instances that each wrote their own do not
        assertFalse(Arrays.equals(key, new LoginKey(dir.resolve("third/login.key")).get(0)));
    }

    @Test
    void aChangedFileIsNoticedWithoutARestart(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("login.key");
        LoginKey key = new LoginKey(file);
        byte[] first = key.get(0);
        Files.write(file, ("ab".repeat(32) + "\n").getBytes(StandardCharsets.US_ASCII));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000));
        assertArrayEquals(first, key.get(1_000), "not looked at again so soon");
        byte[] second = key.get(10_000);
        assertEquals((byte) 0xab, second[0]);
        assertEquals(32, second.length);
        // a moment without the file, or with half of it, keeps the key in use
        Files.write(file, "abc".getBytes(StandardCharsets.US_ASCII));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 4_000));
        assertArrayEquals(second, key.get(20_000));
    }

    @Test
    void onlyAKeyIsAKey(@TempDir Path dir) throws IOException {
        assertEquals(32, LoginKey.parse("  " + "0F".repeat(32) + " \n").length);
        assertEquals(40, LoginKey.parse("0f".repeat(40)).length);
        for (String bad : new String[] {"", "0f".repeat(31), "0f".repeat(32) + "0", "zz".repeat(32), "0f".repeat(600)}) {
            assertThrows(IOException.class, () -> LoginKey.parse(bad), bad);
        }
        // a file that is there but is no key: no key, and it is not written over
        Path file = dir.resolve("login.key");
        Files.write(file, "secret".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> new LoginKey(file).get(0));
        assertTrue(Arrays.equals("secret".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(file)));
    }
}
