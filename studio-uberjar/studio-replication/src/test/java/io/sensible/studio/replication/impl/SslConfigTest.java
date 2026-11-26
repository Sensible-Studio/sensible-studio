/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

class SslConfigTest {

    @TempDir
    Path dir;

    private ServerSocket http;
    private final AtomicReference<ServerSocket> https = new AtomicReference<>();

    @AfterEach
    void close() throws Exception {
        if (http != null) {
            http.close();
        }
        ServerSocket s = https.get();
        if (s != null) {
            s.close();
        }
    }

    private static String pem(String kind, byte[] der) {
        return "-----BEGIN " + kind + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der) + "\n-----END " + kind + "-----\n";
    }

    @Test
    void makesACertificateForTheHost() throws Exception {
        KeyPair kp = SslConfig.rsa();
        X509Certificate c = SslConfig.selfSigned(kp, "studio.example.test", 30);
        c.verify(kp.getPublic());
        c.checkValidity();
        List<?> names = (List<?>) TrustStore.describe(c).get("names");
        assertTrue(names.contains("studio.example.test"));
        assertTrue(names.contains("localhost"));
        assertTrue(names.contains("127.0.0.1"));
        assertEquals(-1, c.getBasicConstraints());
        assertTrue(c.getKeyUsage()[0] && c.getKeyUsage()[2]);
        assertTrue(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        assertTrue(SslConfig.forHost(c, "studio.example.test"));
        assertTrue(SslConfig.forHost(c, "127.0.0.1"));
        assertFalse(SslConfig.forHost(c, "other.example.test"));
        assertTrue(SslConfig.matches(kp.getPrivate(), c));
        assertFalse(SslConfig.matches(SslConfig.rsa().getPrivate(), c));
    }

    @Test
    void readsKeysAsPemAndDer() throws Exception {
        KeyPair kp = SslConfig.rsa();
        byte[] pkcs8 = kp.getPrivate().getEncoded();
        assertArrayEquals(pkcs8, SslConfig.privateKey(pkcs8).getEncoded());
        assertArrayEquals(pkcs8, SslConfig.privateKey(pem("PRIVATE KEY", pkcs8).getBytes(StandardCharsets.US_ASCII)).getEncoded());
        // PKCS #1: the octet string inside PKCS #8
        SslConfig.Der outer = SslConfig.Der.read(pkcs8, 0);
        SslConfig.Der version = SslConfig.Der.read(pkcs8, outer.start);
        SslConfig.Der alg = SslConfig.Der.read(pkcs8, version.end);
        SslConfig.Der octets = SslConfig.Der.read(pkcs8, alg.end);
        byte[] pkcs1 = java.util.Arrays.copyOfRange(pkcs8, octets.start, octets.end);
        String pemKey = pem("RSA PRIVATE KEY", pkcs1);
        assertEquals("RSA", SslConfig.privateKey(pemKey.getBytes(StandardCharsets.US_ASCII)).getAlgorithm());
        assertEquals("RSA", SslConfig.privateKey(pkcs1).getAlgorithm());
        // EC, PKCS #8
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        KeyPair ec = g.generateKeyPair();
        assertEquals("EC", SslConfig.privateKey(pem("PRIVATE KEY", ec.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII)).getAlgorithm());
        // what is refused, in words
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SslConfig.privateKey(pem("ENCRYPTED PRIVATE KEY", pkcs8).getBytes(StandardCharsets.US_ASCII)));
        assertTrue(e.getMessage().contains("password"));
        X509Certificate c = SslConfig.selfSigned(kp, "localhost", 1);
        e = assertThrows(IllegalArgumentException.class, () -> SslConfig.privateKey(TrustStore.pem(c).getBytes(StandardCharsets.US_ASCII)));
        assertTrue(e.getMessage().contains("no private key"));
        assertThrows(IllegalArgumentException.class, () -> SslConfig.privateKey("nonsense".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void checksTheKeyCertificatesAndPort() throws Exception {
        http = new ServerSocket(0);
        SslConfig ssl = new SslConfig();
        ssl.setUp(admin(new AtomicReference<>(), null), dir, http.getLocalPort());
        KeyPair kp = SslConfig.rsa();
        X509Certificate mine = SslConfig.selfSigned(kp, "other.example.test", 30);
        X509Certificate stranger = SslConfig.selfSigned(SslConfig.rsa(), "stranger.example.test", 30);
        int port = freePort();

        SslConfig.Request r = new SslConfig.Request();
        r.key = pem("PRIVATE KEY", kp.getPrivate().getEncoded()).getBytes(StandardCharsets.US_ASCII);
        r.certificate = (TrustStore.pem(stranger) + TrustStore.pem(mine)).getBytes(StandardCharsets.US_ASCII);
        r.hostname = "Studio.Example.Test";
        r.port = port;
        SslConfig.Prepared p = ssl.prepare(r);
        assertEquals(1, p.chain.length);
        assertEquals(mine, p.chain[0]);
        assertEquals("studio.example.test", p.hostname);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("not for studio.example.test")));
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("signed by itself")));

        r.certificate = TrustStore.pem(stranger).getBytes(StandardCharsets.US_ASCII);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ssl.prepare(r)).getMessage().contains("None of the certificates"));

        SslConfig.Request s = new SslConfig.Request();
        s.selfSigned = true;
        s.port = http.getLocalPort();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ssl.prepare(s)).getMessage().contains("HTTP port"));
        try (ServerSocket taken = new ServerSocket(0)) {
            s.port = taken.getLocalPort();
            assertTrue(assertThrows(IllegalArgumentException.class, () -> ssl.prepare(s)).getMessage().contains("in use"));
        }
        s.port = 0;
        assertThrows(IllegalArgumentException.class, () -> ssl.prepare(s));
        s.port = port;
        s.hostname = "bad host/";
        assertThrows(IllegalArgumentException.class, () -> ssl.prepare(s));
    }

    @Test
    void appliesAndTheServerAnswersHttps() throws Exception {
        http = new ServerSocket(0);
        AtomicReference<Dictionary<String, Object>> stored = new AtomicReference<>();
        SslConfig ssl = new SslConfig();
        ssl.setUp(admin(stored, this::serve), dir, http.getLocalPort());
        ssl.delayMs = 0;
        ssl.waitMs = 5000;
        acceptForever(http);

        SslConfig.Request r = new SslConfig.Request();
        r.selfSigned = true;
        r.hostname = "localhost";
        r.port = freePort();
        SslConfig.Prepared p = ssl.prepare(r);
        assertNull(((Map<?, ?>) ((List<?>) p.describe().get("certificate")).get(0)).get("sha256"), "made again when applied");
        Map<String, Object> out = ssl.apply(p);
        assertEquals("applying", ((Map<?, ?>) out.get("last")).get("state"));
        Map<?, ?> last = waitFor(ssl);
        assertEquals("done", last.get("state"), String.valueOf(last.get("message")));

        Dictionary<String, Object> conf = stored.get();
        assertEquals(Boolean.TRUE, conf.get(SslConfig.ENABLE));
        assertEquals(r.port, conf.get(SslConfig.PORT));
        assertEquals("PKCS12", conf.get(SslConfig.KEYSTORE_TYPE));
        Path ks = Paths.get(String.valueOf(conf.get(SslConfig.KEYSTORE)));
        assertTrue(Files.exists(ks));
        assertTrue(ks.startsWith(dir));
        Map<String, Object> status = ssl.status();
        assertEquals(true, status.get("enabled"));
        assertEquals(true, status.get("listening"));
        assertEquals(TrustStore.fingerprint(p.chain[0]), status.get("serves"));
        assertEquals("https://localhost:" + r.port + "/", status.get("url"));
        assertEquals(1, ((List<?>) status.get("certificate")).size());
        assertFalse(Json.write(status).contains(String.valueOf(conf.get(SslConfig.KEYSTORE_PASSWORD))), "never the password");

        // turned off: the key store stays configured
        ssl.disable();
        assertEquals("done", waitFor(ssl).get("state"));
        assertEquals(Boolean.FALSE, stored.get().get(SslConfig.ENABLE));
        assertNotNull(stored.get().get(SslConfig.KEYSTORE));
        assertEquals(false, ssl.status().get("listening"));
        assertThrows(IllegalArgumentException.class, ssl::disable);
        // and on again
        ssl.enable();
        assertEquals("done", waitFor(ssl).get("state"));
        assertEquals(true, ssl.status().get("listening"));
    }

    @Test
    void putsTheConfigurationBackWhenHttpsDoesNotAnswer() throws Exception {
        http = new ServerSocket(0);
        AtomicReference<Dictionary<String, Object>> stored = new AtomicReference<>();
        SslConfig ssl = new SslConfig();
        // a web server that never starts HTTPS
        ssl.setUp(admin(stored, null), dir, http.getLocalPort());
        ssl.delayMs = 0;
        ssl.waitMs = 1500;
        acceptForever(http);
        SslConfig.Request r = new SslConfig.Request();
        r.selfSigned = true;
        r.port = freePort();
        ssl.apply(ssl.prepare(r));
        Map<?, ?> last = waitFor(ssl);
        assertEquals("rolledBack", last.get("state"));
        assertTrue(String.valueOf(last.get("message")).contains("HTTPS does not answer on port " + r.port));
        assertNull(stored.get(), "there was no configuration before: none now");
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.filter(f -> f.getFileName().toString().endsWith(".p12")).count());
        }
    }

    // ---- a web server and its configuration, as Felix's would be ----

    interface Server {
        void configured(Dictionary<String, Object> props) throws Exception;
    }

    /** Starts HTTPS with the configuration's key store, or stops it, as Jetty does on a change. */
    private void serve(Dictionary<String, Object> props) throws Exception {
        ServerSocket old = https.getAndSet(null);
        if (old != null) {
            old.close();
        }
        if (props == null || !Boolean.TRUE.equals(props.get(SslConfig.ENABLE))) {
            return;
        }
        char[] pw = String.valueOf(props.get(SslConfig.KEYSTORE_PASSWORD)).toCharArray();
        KeyStore ks = KeyStore.getInstance(String.valueOf(props.get(SslConfig.KEYSTORE_TYPE)));
        try (InputStream in = Files.newInputStream(Paths.get(String.valueOf(props.get(SslConfig.KEYSTORE))))) {
            ks.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, String.valueOf(props.get(SslConfig.KEY_PASSWORD)).toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocket s = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket((Integer) props.get(SslConfig.PORT));
        https.set(s);
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try (SSLSocket c = (SSLSocket) s.accept()) {
                    c.setSoTimeout(2000);
                    c.startHandshake();
                } catch (Exception e) {
                    // the next
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void acceptForever(ServerSocket s) {
        Thread t = new Thread(() -> {
            while (!s.isClosed()) {
                try (Socket c = s.accept()) {
                    // answered
                } catch (Exception e) {
                    // closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Map<?, ?> waitFor(SslConfig ssl) throws InterruptedException {
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until) {
            Map<?, ?> last = (Map<?, ?>) ssl.status().get("last");
            if (last != null && !"applying".equals(last.get("state"))) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("still applying");
    }

    @SuppressWarnings("unchecked")
    private static ConfigurationAdmin admin(AtomicReference<Dictionary<String, Object>> stored, Server server) {
        Configuration conf = (Configuration) Proxy.newProxyInstance(SslConfigTest.class.getClassLoader(), new Class<?>[] { Configuration.class }, (o, m, args) -> {
            switch (m.getName()) {
                case "getPid":
                    return SslConfig.PID;
                case "getProperties":
                    return stored.get() == null ? null : SslConfig.copy(stored.get());
                case "update":
                    if (args != null && args.length == 1) {
                        stored.set(SslConfig.copy((Dictionary<String, Object>) args[0]));
                        if (server != null) {
                            server.configured(stored.get());
                        }
                    }
                    return null;
                case "delete":
                    stored.set(null);
                    if (server != null) {
                        server.configured(null);
                    }
                    return null;
                case "hashCode":
                    return 1;
                case "equals":
                    return o == args[0];
                default:
                    return null;
            }
        });
        return (ConfigurationAdmin) Proxy.newProxyInstance(SslConfigTest.class.getClassLoader(), new Class<?>[] { ConfigurationAdmin.class }, (o, m, args) -> {
            switch (m.getName()) {
                case "getConfiguration":
                    return conf;
                case "listConfigurations":
                    return stored.get() == null ? null : new Configuration[] { conf };
                case "hashCode":
                    return 1;
                case "equals":
                    return o == args[0];
                default:
                    return null;
            }
        });
    }
}
