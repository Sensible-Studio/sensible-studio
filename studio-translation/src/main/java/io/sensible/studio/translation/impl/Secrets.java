package io.sensible.studio.translation.impl;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves secret references, as AEM as a Cloud Service writes them: {@code $[secret:NAME]} is a "Sensible Studio Secret"
 * configuration, or else the environment variable {@code NAME}; {@code $[env:NAME]} is the environment variable.
 * And keys entered in the console, kept encrypted (like AEM's CryptoSupport): {@code $[enc:v1:...]} is AES-256-GCM
 * with this instance's master key, a random key in {@code <sling.home>/studio/translation-master.key} (never in the
 * repository; back it up with the instance), so a
 * configuration copied to another instance, or exported, does not carry a usable key.
 * Values are only handed to connectors, never written anywhere.
 */
@Component(service = Secrets.class)
public class Secrets {

    static final Pattern REFERENCE = Pattern.compile("^\\$\\[(secret|env):([A-Za-z_][A-Za-z0-9_.]*)\\]$");
    /** A reference inside a longer value, e.g. {@code Bearer $[secret:TOKEN]}. */
    static final Pattern EMBEDDED = Pattern.compile("\\$\\[(secret|env):([A-Za-z_][A-Za-z0-9_.]*)\\]|\\$\\[enc:v1:[A-Za-z0-9_-]+\\]");
    /** A value encrypted with the instance's master key. */
    static final Pattern ENCRYPTED = Pattern.compile("^\\$\\[enc:v1:([A-Za-z0-9_-]+)\\]$");
    private static final byte[] AAD = "studio-translation-secret".getBytes(StandardCharsets.UTF_8);

    /** What a setting holds. */
    enum Status {
        /** Nothing set. */
        EMPTY,
        /** A reference that resolves. */
        FOUND,
        /** A reference without a value on this instance. */
        MISSING,
        /** A literal value: not allowed in repository content. */
        PLAIN
    }

    private final List<SecretEntry> entries = new CopyOnWriteArrayList<>();
    private Function<String, String> env = System::getenv;
    private volatile File keyFile;
    private volatile byte[] masterKey;

    @Activate
    void activate() {
        String home = System.getProperty("sling.home");
        this.keyFile = home == null ? null : new File(new File(home, "studio"), "translation-master.key");
    }

    /** For tests: the master key. */
    Secrets masterKey(byte[] key) {
        this.masterKey = key.clone();
        return this;
    }

    @Reference(service = SecretEntry.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindEntry(SecretEntry entry) {
        entries.add(entry);
    }

    void unbindEntry(SecretEntry entry) {
        entries.remove(entry);
    }

    /** For tests: the environment. */
    Secrets environment(Map<String, String> environment) {
        this.env = environment::get;
        return this;
    }

    static boolean isReference(String value) {
        return value != null && (REFERENCE.matcher(value.trim()).matches() || isEncrypted(value));
    }

    static boolean isEncrypted(String value) {
        return value != null && ENCRYPTED.matcher(value.trim()).matches();
    }

    /** A key entered in the console, as it is stored: {@code $[enc:v1:...]}. */
    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(AAD);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "$[enc:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(out) + "]";
        } catch (Exception e) {
            throw new IllegalStateException("The key could not be encrypted: " + e.getMessage(), e);
        }
    }

    /** The plain value, or null when it was encrypted with another master key (another instance) or is damaged. */
    String decrypt(String value) {
        Matcher m = value == null ? null : ENCRYPTED.matcher(value.trim());
        if (m == null || !m.matches()) return null;
        try {
            byte[] in = Base64.getUrlDecoder().decode(m.group(1));
            if (in.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, in, 0, 12));
            c.updateAAD(AAD);
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** The master key: read from its file, made there the first time (readable by the owner only). */
    private synchronized byte[] key() throws IOException {
        if (masterKey != null) return masterKey;
        File f = keyFile;
        if (f == null) {
            // no data area (tests): a key for this run
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            masterKey = k;
            return k;
        }
        if (f.isFile()) {
            byte[] k = Base64.getDecoder().decode(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim());
            if (k.length != 32) throw new IOException("The master key " + f + " is damaged");
            masterKey = k;
            return k;
        }
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        Files.write(f.toPath(), Base64.getEncoder().encodeToString(k).getBytes(StandardCharsets.US_ASCII));
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system
        }
        masterKey = k;
        return k;
    }

    /** The value of a reference; null when it does not resolve or is not a reference. */
    String resolve(String reference) {
        if (reference == null) return null;
        if (isEncrypted(reference)) return decrypt(reference);
        Matcher m = REFERENCE.matcher(reference.trim());
        if (!m.matches()) return null;
        String name = m.group(2);
        if ("secret".equals(m.group(1))) {
            for (SecretEntry e : entries) {
                if (name.equals(e.name()) && e.value() != null && !e.value().isEmpty()) return e.value();
            }
        }
        String v = env.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /** Replaces every reference in a text; the ones without a value are added to {@code missing} (and left as they are). */
    String expand(String text, List<String> missing) {
        if (text == null) return null;
        Matcher m = EMBEDDED.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = resolve(m.group());
            if (v == null) {
                missing.add(m.group());
                v = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean hasReference(String text) {
        return text != null && EMBEDDED.matcher(text).find();
    }

    Status status(String value) {
        if (value == null || value.trim().isEmpty()) return Status.EMPTY;
        if (!isReference(value)) return Status.PLAIN;
        return resolve(value) == null ? Status.MISSING : Status.FOUND;
    }

    /** The value of an environment variable (for connectors' defaults). */
    String env(String name) {
        String v = env.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
