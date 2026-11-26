package io.sensible.studio.auth.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;

/**
 * The key the sign-in cookies are signed with: a file of 64 or more hexadecimal digits, written on the
 * first start if it is not there. Every publish instance of a farm needs the same file (copy it from the
 * first instance to the others); who has it can sign in as any visitor, so it is readable by its owner
 * only and belongs in no backup that others read. A changed file is noticed without a restart.
 */
final class LoginKey {

    private static final int BYTES = 32;
    private static final long CHECK_EVERY_MS = 5_000;

    private final Path file;
    private byte[] key;
    private FileTime modified;
    private long checked;

    LoginKey(Path file) {
        this.file = file;
    }

    Path file() {
        return file;
    }

    /** The key, read again if the file changed since. */
    synchronized byte[] get(long now) throws IOException {
        if (key != null && now - checked < CHECK_EVERY_MS) {
            return key;
        }
        checked = now;
        try {
            if (!Files.exists(file)) {
                create();
            }
            FileTime time = Files.getLastModifiedTime(file);
            if (key == null || !time.equals(modified)) {
                key = parse(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII));
                modified = time;
            }
        } catch (IOException e) {
            if (key == null) {
                throw e;
            }
            // a copy in progress, a moment without the file: the key in use stays
        }
        return key;
    }

    static byte[] parse(String text) throws IOException {
        String hex = text.trim();
        if (hex.length() < 2 * BYTES || hex.length() % 2 != 0 || hex.length() > 1024) {
            throw new IOException("the sign-in key is " + 2 * BYTES + " or more hexadecimal digits");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IOException("the sign-in key is " + 2 * BYTES + " or more hexadecimal digits");
            }
            bytes[i] = (byte) (high << 4 | low);
        }
        return bytes;
    }

    private void create() throws IOException {
        byte[] bytes = new byte[BYTES];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(2 * BYTES + 1);
        for (byte b : bytes) {
            hex.append(Character.forDigit(b >> 4 & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        hex.append('\n');
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException e) {
            return; // another start wrote it in the meantime: that one is the key
        }
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // a file system without owners: the folder's protection is what there is
        }
        Files.write(file, hex.toString().getBytes(StandardCharsets.US_ASCII), StandardOpenOption.TRUNCATE_EXISTING);
    }
}
