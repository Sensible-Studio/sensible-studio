/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTPS for this instance, as AEM's Tools › Security › SSL Configuration: a private key and its
 * certificate (or one made here, for trying it out) and a port, and the instance's own web server
 * (the Felix HTTP service, Jetty) answers HTTPS there besides HTTP. No Apache or other proxy needed.
 *
 * <p>The key and its certificates are written to a PKCS #12 key store in the instance's folder
 * ({@code <sling.home>/studio/ssl}), readable by its owner only, with a password made here; the web
 * server's configuration ({@code org.apache.felix.http}) gets its path, its password and the port.
 * The web server restarts with it; then the instance checks that HTTPS answers with this
 * certificate and that HTTP still does. If not, it puts the configuration back as it was.
 */
@Component(service = SslConfig.class, immediate = true)
public class SslConfig {

    static final String PID = "org.apache.felix.http";
    static final String ENABLE = "org.apache.felix.https.enable";
    static final String PORT = "org.osgi.service.http.port.secure";
    static final String HTTP_PORT = "org.osgi.service.http.port";
    static final String KEYSTORE = "org.apache.felix.https.keystore";
    static final String KEYSTORE_TYPE = "org.apache.felix.https.keystore.type";
    static final String KEYSTORE_PASSWORD = "org.apache.felix.https.keystore.password";
    static final String KEY_PASSWORD = "org.apache.felix.https.keystore.key.password";
    /** Ours: the host name HTTPS is for, for the address the console shows. */
    static final String HOSTNAME = "studio.ssl.hostname";
    static final String ALIAS = "studio";
    static final int DEFAULT_PORT = 8443;
    /** How long the web server gets to answer HTTPS after a change before it is put back. */
    static final long WAIT_MS = 20_000;

    private static final Logger LOG = LoggerFactory.getLogger(SslConfig.class);

    @Reference
    private ConfigurationAdmin configAdmin;

    private volatile Path home;
    private volatile int httpPort = 8080;
    /** What the last change did: state applying, done, rolledBack or failed; message; at. */
    private volatile Map<String, Object> last;
    private volatile Thread worker;

    /** Waits after answering the request that asked for a change, so that it is answered before the server restarts. */
    long delayMs = 1000;
    long waitMs = WAIT_MS;

    @Activate
    void activate() {
        // the launcher sets both as system properties too
        String sh = System.getProperty("sling.home", System.getProperty("user.dir"));
        home = Paths.get(sh).toAbsolutePath().resolve("studio").resolve("ssl");
        String p = System.getProperty(HTTP_PORT);
        try {
            httpPort = p == null ? 8080 : Integer.parseInt(p.trim());
        } catch (NumberFormatException e) {
            httpPort = 8080;
        }
    }

    @Deactivate
    void deactivate() {
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    /** For tests. */
    void setUp(ConfigurationAdmin ca, Path folder, int http) {
        configAdmin = ca;
        home = folder;
        httpPort = http;
    }

    // ---- what is configured ----

    /** The web server's HTTPS as configured now, what it serves, the last change. Never passwords. */
    Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        Dictionary<String, Object> p = current();
        int http = intOf(p == null ? null : p.get(HTTP_PORT), httpPort);
        boolean enabled = p != null && boolOf(p.get(ENABLE));
        int port = intOf(p == null ? null : p.get(PORT), 0);
        out.put("httpPort", http);
        out.put("enabled", enabled);
        out.put("port", port > 0 ? port : null);
        String host = p == null || p.get(HOSTNAME) == null ? "localhost" : String.valueOf(p.get(HOSTNAME));
        out.put("hostname", host);
        out.put("url", enabled && port > 0 ? url(host, port) : null);
        List<Map<String, Object>> chain = new ArrayList<>();
        String ks = p == null || p.get(KEYSTORE) == null ? null : String.valueOf(p.get(KEYSTORE));
        if (ks != null && p.get(KEYSTORE_PASSWORD) != null) {
            try {
                for (X509Certificate c : chainOf(Paths.get(ks), String.valueOf(p.get(KEYSTORE_PASSWORD)).toCharArray())) {
                    chain.add(TrustStore.describe(c));
                }
            } catch (IOException | GeneralSecurityException e) {
                out.put("keystoreError", "The key store " + ks + " could not be read: " + e.getMessage());
            }
        }
        out.put("certificate", chain);
        if (enabled && port > 0) {
            String served = served(port);
            out.put("listening", served != null);
            out.put("serves", served);
        } else {
            out.put("listening", false);
        }
        out.put("suggestedPort", enabled && port > 0 ? port : suggestPort());
        out.put("last", last);
        return out;
    }

    private Dictionary<String, Object> current() {
        try {
            Configuration[] found = configAdmin.listConfigurations("(service.pid=" + PID + ")");
            return found == null || found.length == 0 ? null : found[0].getProperties();
        } catch (Exception e) {
            LOG.warn("SSL: the web server's configuration could not be read: {}", e.getMessage());
            return null;
        }
    }

    /** The first free port from 8443 on (the one AEM's documentation uses). */
    int suggestPort() {
        for (int p = DEFAULT_PORT; p < DEFAULT_PORT + 50; p++) {
            if (p != httpPort && free(p)) {
                return p;
            }
        }
        return DEFAULT_PORT;
    }

    static boolean free(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String url(String host, int port) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "https://" + h + (port == 443 ? "" : ":" + port) + "/";
    }

    // ---- a request ----

    /** What the wizard sends: a key and certificates, or none for one made here; the host name and the port. */
    static final class Request {
        byte[] key;
        byte[] certificate;
        boolean selfSigned;
        String hostname = "localhost";
        int port;
    }

    /** What is checked before anything changes: the key, its certificates in order, the port. */
    static final class Prepared {
        PrivateKey key;
        X509Certificate[] chain;
        final List<String> warnings = new ArrayList<>();
        String hostname;
        int port;
        /** Made here: a check makes one, and applying makes another, so its fingerprint is not shown before. */
        boolean made;

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Map<String, Object>> c = new ArrayList<>();
            for (X509Certificate x : chain) {
                Map<String, Object> d = TrustStore.describe(x);
                if (made) {
                    d.remove("sha256");
                }
                c.add(d);
            }
            m.put("certificate", c);
            m.put("warnings", warnings);
            m.put("hostname", hostname);
            m.put("port", port);
            m.put("url", url(hostname, port));
            return m;
        }
    }

    /** Checks a request; nothing is written. IllegalArgumentException says what is wrong, in words. */
    Prepared prepare(Request r) throws GeneralSecurityException {
        Prepared p = new Prepared();
        p.hostname = r.hostname == null || r.hostname.trim().isEmpty() ? "localhost" : r.hostname.trim().toLowerCase(Locale.ROOT);
        if (!p.hostname.matches("[a-z0-9.\\-:\\[\\]]+")) {
            throw new IllegalArgumentException("The host name may have letters, digits, dots and hyphens only");
        }
        p.hostname = p.hostname.replace("[", "").replace("]", "");
        if (r.port < 1 || r.port > 65535) {
            throw new IllegalArgumentException("The HTTPS port must be a number from 1 to 65535");
        }
        if (r.port == httpPort) {
            throw new IllegalArgumentException("Port " + r.port + " is the instance's HTTP port; HTTPS needs another one");
        }
        Dictionary<String, Object> now = current();
        boolean ours = now != null && boolOf(now.get(ENABLE)) && intOf(now.get(PORT), 0) == r.port;
        if (!ours && !free(r.port)) {
            throw new IllegalArgumentException("Port " + r.port + " is in use by another program");
        }
        p.port = r.port;
        if (r.selfSigned) {
            KeyPair kp = rsa();
            p.key = kp.getPrivate();
            p.chain = new X509Certificate[] { selfSigned(kp, p.hostname, 365) };
            p.made = true;
            p.warnings.add("The certificate is made here and signed by itself: browsers warn about it until it is trusted. For trying HTTPS out; use one from an authority for real visitors.");
        } else {
            if (r.key == null || r.key.length == 0) {
                throw new IllegalArgumentException("The private key is required");
            }
            if (r.certificate == null || r.certificate.length == 0) {
                throw new IllegalArgumentException("The certificate is required");
            }
            p.key = privateKey(r.key);
            List<X509Certificate> certs;
            try {
                certs = TrustStore.parse(r.certificate);
            } catch (CertificateException e) {
                throw new IllegalArgumentException("The certificate could not be read: " + e.getMessage());
            }
            p.chain = order(p.key, certs);
            X509Certificate leaf = p.chain[0];
            long now2 = System.currentTimeMillis();
            if (leaf.getNotAfter().getTime() < now2) {
                p.warnings.add("The certificate expired on " + leaf.getNotAfter() + ": browsers refuse it.");
            } else if (leaf.getNotBefore().getTime() > now2) {
                p.warnings.add("The certificate is valid from " + leaf.getNotBefore() + " only.");
            }
            if (!forHost(leaf, p.hostname)) {
                p.warnings.add("The certificate is not for " + p.hostname + ": browsers warn when the instance is opened by that name.");
            }
            if (leaf.getSubjectX500Principal().equals(leaf.getIssuerX500Principal())) {
                p.warnings.add("The certificate is signed by itself: browsers warn about it until it is trusted.");
            } else if (p.chain.length == 1) {
                p.warnings.add("Only the server's own certificate was given: add the authority's intermediate certificates to the same file, or some clients cannot check it.");
            }
        }
        return p;
    }

    /** The certificates with the one for this key first, then who signed it, and so on; others are left out. */
    static X509Certificate[] order(PrivateKey key, List<X509Certificate> certs) throws GeneralSecurityException {
        X509Certificate leaf = null;
        for (X509Certificate c : certs) {
            if (matches(key, c)) {
                leaf = c;
                break;
            }
        }
        if (leaf == null) {
            throw new IllegalArgumentException("None of the certificates is for this private key");
        }
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(leaf);
        X509Certificate at = leaf;
        while (!at.getIssuerX500Principal().equals(at.getSubjectX500Principal()) && chain.size() < 10) {
            X509Certificate next = null;
            for (X509Certificate c : certs) {
                if (!chain.contains(c) && c.getSubjectX500Principal().equals(at.getIssuerX500Principal())) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                break;
            }
            chain.add(next);
            at = next;
        }
        return chain.toArray(new X509Certificate[0]);
    }

    /** Whether the certificate's key is this key's other half: signs something and checks it. */
    static boolean matches(PrivateKey key, X509Certificate c) {
        try {
            String alg = "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "RSA".equals(key.getAlgorithm()) ? "SHA256withRSA" : null;
            if (alg == null || !key.getAlgorithm().equals(c.getPublicKey().getAlgorithm())) {
                return false;
            }
            byte[] data = new byte[32];
            new SecureRandom().nextBytes(data);
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(data);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(c.getPublicKey());
            v.update(data);
            return v.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** Whether the certificate names the host (its alternative names, else its common name; * for one label). */
    static boolean forHost(X509Certificate c, String host) {
        List<String> names = new ArrayList<>();
        Object n = TrustStore.describe(c).get("names");
        if (n instanceof List) {
            for (Object o : (List<?>) n) {
                names.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            }
        }
        if (names.isEmpty()) {
            Matcher m = Pattern.compile("(?:^|,)\\s*CN=([^,]+)").matcher(c.getSubjectX500Principal().getName());
            if (m.find()) {
                names.add(m.group(1).trim().toLowerCase(Locale.ROOT));
            }
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.equals(h)) {
                return true;
            }
            if (name.startsWith("*.") && h.indexOf('.') > 0 && h.substring(h.indexOf('.')).equals(name.substring(1))) {
                return true;
            }
            try {
                if (looksLikeIp(name) && looksLikeIp(h) && InetAddress.getByName(name).equals(InetAddress.getByName(h))) {
                    return true;
                }
            } catch (IOException e) {
                // not the same
            }
        }
        return false;
    }

    private static boolean looksLikeIp(String s) {
        return s.matches("[0-9.]+") || s.contains(":");
    }

    // ---- keys ----

    /**
     * A private key: PEM ({@code PRIVATE KEY}, {@code RSA PRIVATE KEY}, {@code EC PRIVATE KEY}) or DER
     * (PKCS #8, as AEM asks for, or PKCS #1). Not encrypted: the instance has to read it without anyone typing.
     */
    static PrivateKey privateKey(byte[] data) {
        String text = new String(data, StandardCharsets.US_ASCII);
        byte[] der;
        String kind;
        if (text.contains("-----BEGIN")) {
            Matcher m = Pattern.compile("-----BEGIN ([A-Z ]+)-----([\\s\\S]*?)-----END \\1-----").matcher(text);
            der = null;
            kind = null;
            while (m.find()) {
                if (m.group(1).endsWith("PRIVATE KEY")) {
                    kind = m.group(1);
                    der = Base64.getMimeDecoder().decode(m.group(2).replaceAll("(?m)^[A-Za-z-]+:.*$", "").trim());
                    break;
                }
            }
            if (der == null) {
                throw new IllegalArgumentException("There is no private key in it (a certificate was given?)");
            }
            if (kind.contains("ENCRYPTED") || text.contains("Proc-Type: 4,ENCRYPTED")) {
                throw new IllegalArgumentException("The private key is protected by a password: give it without one (openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.der -outform DER)");
            }
        } else {
            der = data;
            kind = null;
        }
        List<byte[]> tries = new ArrayList<>();
        if (kind == null || "PRIVATE KEY".equals(kind)) {
            tries.add(der);
        }
        if (kind == null || "RSA PRIVATE KEY".equals(kind)) {
            tries.add(pkcs8(RSA_OID, null, der));
        }
        if (kind == null || "EC PRIVATE KEY".equals(kind)) {
            byte[] curve = sec1Curve(der);
            if (curve != null) {
                tries.add(pkcs8(EC_OID, curve, der));
            }
        }
        for (byte[] t : tries) {
            for (String alg : new String[] { "RSA", "EC" }) {
                try {
                    return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(t));
                } catch (GeneralSecurityException | RuntimeException e) {
                    // the next
                }
            }
        }
        throw new IllegalArgumentException("The private key could not be read: an RSA or EC key, PEM or DER, without a password");
    }

    private static final String RSA_OID = "1.2.840.113549.1.1.1";
    private static final String EC_OID = "1.2.840.10045.2.1";

    /** PKCS #1 or SEC 1 wrapped as PKCS #8. */
    private static byte[] pkcs8(String alg, byte[] params, byte[] key) {
        byte[] algId = params == null ? seq(oid(alg), new byte[] { 0x05, 0x00 }) : seq(oid(alg), params);
        return seq(integer(BigInteger.ZERO), algId, tlv(0x04, key));
    }

    /** The curve of a SEC 1 EC key (its [0] parameters), as an OID's encoding. */
    static byte[] sec1Curve(byte[] der) {
        try {
            Der outer = Der.read(der, 0);
            if (outer.tag != 0x30) {
                return null;
            }
            int at = outer.start;
            while (at < outer.end) {
                Der d = Der.read(der, at);
                if (d.tag == 0xA0) {
                    Der o = Der.read(der, d.start);
                    return o.tag == 0x06 ? Arrays.copyOfRange(der, d.start, o.end) : null;
                }
                at = d.end;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** One DER element: its tag, where its value starts and where it ends. */
    static final class Der {
        int tag;
        int start;
        int end;

        static Der read(byte[] b, int at) {
            Der d = new Der();
            d.tag = b[at] & 0xff;
            int len = b[at + 1] & 0xff;
            int i = at + 2;
            if (len > 0x80) {
                int n = len & 0x7f;
                len = 0;
                for (int k = 0; k < n; k++) {
                    len = (len << 8) | (b[i++] & 0xff);
                }
            }
            d.start = i;
            d.end = i + len;
            if (d.end > b.length) {
                throw new IllegalArgumentException("truncated");
            }
            return d;
        }
    }

    static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048, new SecureRandom());
        return g.generateKeyPair();
    }

    /**
     * A certificate for the host, signed by its own key (SHA-256 with RSA), for the days given; for the
     * host, localhost and 127.0.0.1. Made with DER by hand: Java has no public API for it.
     */
    static X509Certificate selfSigned(KeyPair kp, String host, int days) throws GeneralSecurityException {
        byte[] alg = seq(oid("1.2.840.113549.1.1.11"), new byte[] { 0x05, 0x00 });
        byte[] name = seq(
                set(seq(oid("2.5.4.10"), utf8("Sensible Studio"))),
                set(seq(oid("2.5.4.3"), utf8(host))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(new Date(now - 60_000L)), time(new Date(now + days * 86_400_000L)));
        List<byte[]> alt = new ArrayList<>();
        for (String n : new java.util.LinkedHashSet<>(Arrays.asList(host, "localhost", "127.0.0.1"))) {
            if (looksLikeIp(n)) {
                try {
                    alt.add(tlv(0x87, InetAddress.getByName(n).getAddress()));
                } catch (IOException e) {
                    // left out
                }
            } else {
                alt.add(tlv(0x82, n.getBytes(StandardCharsets.US_ASCII)));
            }
        }
        byte[] extensions = seq(
                seq(oid("2.5.29.19"), new byte[] { 0x01, 0x01, (byte) 0xff }, tlv(0x04, seq())),
                seq(oid("2.5.29.15"), new byte[] { 0x01, 0x01, (byte) 0xff }, tlv(0x04, new byte[] { 0x03, 0x02, 0x05, (byte) 0xa0 })),
                seq(oid("2.5.29.37"), tlv(0x04, seq(oid("1.3.6.1.5.5.7.3.1")))),
                seq(oid("2.5.29.17"), tlv(0x04, seq(alt.toArray(new byte[0][])))));
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        byte[] tbs = seq(
                tlv(0xA0, integer(BigInteger.valueOf(2))),
                integer(new BigInteger(1, serial)),
                alg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded(),
                tlv(0xA3, extensions));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] cert = seq(tbs, alg, tlv(0x03, bits));
        return TrustStore.parse(cert).get(0);
    }

    static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            body.write(p, 0, p.length);
        }
        int len = body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else if (len < 0x10000) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xff);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] time(Date d) {
        SimpleDateFormat f = new SimpleDateFormat(d.getTime() < 2524608000000L ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(d.getTime() < 2524608000000L ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v > 0);
            for (int k = n - 1; k >= 0; k--) {
                out.write(tmp[k] | (k > 0 ? 0x80 : 0));
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    // ---- the key store ----

    /** Writes a new key store file with the key and its certificates; readable by its owner only. */
    static Path write(Path folder, PrivateKey key, X509Certificate[] chain, char[] password) throws IOException, GeneralSecurityException {
        Files.createDirectories(folder);
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, key, password, chain);
        Path file = folder.resolve("keystore-" + System.currentTimeMillis() + ".p12");
        Path tmp = folder.resolve(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            ks.store(out, password);
        }
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    static List<X509Certificate> chainOf(Path file, char[] password) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            ks.load(in, password);
        }
        List<X509Certificate> out = new ArrayList<>();
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements();) {
            String a = e.nextElement();
            if (ks.isKeyEntry(a)) {
                Key k = ks.getKey(a, password);
                Certificate[] c = ks.getCertificateChain(a);
                if (k != null && c != null) {
                    for (Certificate x : c) {
                        out.add((X509Certificate) x);
                    }
                    break;
                }
            }
        }
        return out;
    }

    static String password() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ---- changing it ----

    /** Writes the key store and changes the web server's configuration in a moment; the status says how it went. */
    synchronized Map<String, Object> apply(Prepared p) throws IOException, GeneralSecurityException {
        busy();
        String pw = password();
        Path file = write(home, p.key, p.chain, pw.toCharArray());
        Dictionary<String, Object> before = current();
        Dictionary<String, Object> next = copy(before);
        next.put(ENABLE, Boolean.TRUE);
        next.put(PORT, p.port);
        next.put(KEYSTORE, file.toString());
        next.put(KEYSTORE_TYPE, "PKCS12");
        next.put(KEYSTORE_PASSWORD, pw);
        next.put(KEY_PASSWORD, pw);
        next.put(HOSTNAME, p.hostname);
        String expect = TrustStore.fingerprint(p.chain[0]);
        start("HTTPS on port " + p.port, before, next, p.port, expect, file);
        return status();
    }

    /** Turns HTTPS off; the key store and the port stay configured, to turn it on again. */
    synchronized Map<String, Object> disable() {
        busy();
        Dictionary<String, Object> before = current();
        if (before == null || !boolOf(before.get(ENABLE))) {
            throw new IllegalArgumentException("HTTPS is off already");
        }
        Dictionary<String, Object> next = copy(before);
        next.put(ENABLE, Boolean.FALSE);
        start("HTTPS off", before, next, 0, null, null);
        return status();
    }

    /** Turns HTTPS on again with the key store configured before. */
    synchronized Map<String, Object> enable() throws GeneralSecurityException {
        busy();
        Dictionary<String, Object> before = current();
        if (before == null || before.get(KEYSTORE) == null || before.get(KEYSTORE_PASSWORD) == null || intOf(before.get(PORT), 0) <= 0) {
            throw new IllegalArgumentException("There is no key store configured yet: configure HTTPS first");
        }
        int port = intOf(before.get(PORT), 0);
        if (!free(port)) {
            throw new IllegalArgumentException("Port " + port + " is in use by another program");
        }
        List<X509Certificate> chain;
        try {
            chain = chainOf(Paths.get(String.valueOf(before.get(KEYSTORE))), String.valueOf(before.get(KEYSTORE_PASSWORD)).toCharArray());
        } catch (IOException e) {
            throw new IllegalArgumentException("The key store could not be read: " + e.getMessage());
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("The key store has no key");
        }
        Dictionary<String, Object> next = copy(before);
        next.put(ENABLE, Boolean.TRUE);
        start("HTTPS on port " + port, before, next, port, TrustStore.fingerprint(chain.get(0)), null);
        return status();
    }

    private void busy() {
        Map<String, Object> l = last;
        Thread t = worker;
        if (t != null && t.isAlive() && l != null && "applying".equals(l.get("state"))) {
            throw new IllegalStateException("A change is being made; wait for it");
        }
    }

    private void start(String what, Dictionary<String, Object> before, Dictionary<String, Object> next, int port, String expect, Path file) {
        last = state("applying", what + ": the web server restarts", what);
        Thread t = new Thread(() -> run(what, before, next, port, expect, file), "studio-ssl");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    /** Changes the configuration, waits for the server, and puts it back if it does not answer as it should. */
    void run(String what, Dictionary<String, Object> before, Dictionary<String, Object> next, int port, String expect, Path file) {
        try {
            Thread.sleep(delayMs);
            update(next);
            long until = System.currentTimeMillis() + waitMs;
            String problem;
            do {
                Thread.sleep(500);
                problem = problem(port, expect);
            } while (problem != null && System.currentTimeMillis() < until);
            if (problem == null) {
                LOG.info("SSL: {} done", what);
                last = state("done", what + ": done", what);
                cleanUp(file);
                return;
            }
            LOG.warn("SSL: {} did not work ({}); the web server's configuration is put back", what, problem);
            restore(before);
            if (file != null) {
                Files.deleteIfExists(file);
            }
            last = state("rolledBack", problem + ". The configuration was put back as it was; the web server's log says more.", what);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.error("SSL: " + what + " failed", e);
            try {
                restore(before);
            } catch (IOException | RuntimeException e2) {
                LOG.error("SSL: the configuration could not be put back", e2);
            }
            last = state("failed", e.getMessage() == null ? e.toString() : e.getMessage(), what);
        }
    }

    /** What is wrong now, or null: HTTP must answer, and HTTPS with the certificate expected (or not at all, when off). */
    String problem(int port, String expect) {
        if (!answers(httpPort)) {
            return "HTTP does not answer on port " + httpPort;
        }
        if (port <= 0) {
            return null;
        }
        String served = served(port);
        if (served == null) {
            return "HTTPS does not answer on port " + port;
        }
        if (expect != null && !expect.equals(served)) {
            return "HTTPS on port " + port + " shows another certificate";
        }
        return null;
    }

    private void update(Dictionary<String, Object> next) throws IOException {
        Configuration c = configAdmin.getConfiguration(PID, null);
        c.update(next);
    }

    private void restore(Dictionary<String, Object> before) throws IOException {
        Configuration c = configAdmin.getConfiguration(PID, null);
        if (before == null) {
            c.delete();
        } else {
            c.update(copy(before));
        }
    }

    /** Old key stores go once a new one works. */
    private void cleanUp(Path keep) {
        if (keep == null || home == null) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.list(home)) {
            s.filter(f -> f.getFileName().toString().startsWith("keystore-") && !f.equals(keep)).forEach(f -> {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    LOG.debug("SSL: {} not removed", f);
                }
            });
        } catch (IOException e) {
            LOG.debug("SSL: old key stores not removed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> state(String state, String message, String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state);
        m.put("message", message);
        m.put("what", what);
        m.put("at", System.currentTimeMillis());
        return Collections.unmodifiableMap(m);
    }

    static Dictionary<String, Object> copy(Dictionary<String, Object> d) {
        Dictionary<String, Object> out = new Hashtable<>();
        if (d != null) {
            for (Enumeration<String> e = d.keys(); e.hasMoreElements();) {
                String k = e.nextElement();
                if (!"service.pid".equals(k) && !"service.bundleLocation".equals(k)) {
                    out.put(k, d.get(k));
                }
            }
        }
        return out;
    }

    static boolean answers(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The SHA-256 of the certificate HTTPS shows on the port, or null when nothing answers HTTPS there. */
    static String served(int port) {
        X509Certificate[][] seen = new X509Certificate[1][];
        try {
            X509TrustManager all = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // not asked
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // only looked at: this is the instance itself
                    seen[0] = chain;
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { all }, null);
            try (Socket raw = new Socket()) {
                raw.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                raw.setSoTimeout(3000);
                try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, "localhost", port, false)) {
                    s.startHandshake();
                }
            }
            return seen[0] == null || seen[0].length == 0 ? null : TrustStore.fingerprint(seen[0][0]);
        } catch (IOException | GeneralSecurityException e) {
            return null;
        }
    }

    static int intOf(Object o, int dflt) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return o == null ? dflt : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static boolean boolOf(Object o) {
        return o instanceof Boolean ? (Boolean) o : o != null && "true".equalsIgnoreCase(String.valueOf(o).trim());
    }
}
