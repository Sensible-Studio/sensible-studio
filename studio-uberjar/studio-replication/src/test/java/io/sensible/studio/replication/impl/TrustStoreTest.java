/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * A server on a local port with a certificate of an authority of the test's own (made with the
 * JDK's keytool), as a publish instance behind HTTPS with the organisation's own certificate.
 */
class TrustStoreTest {

    private static Path dir;
    private static HttpsServer server;
    private static X509Certificate ca;
    private static byte[] caDer;

    private static void keytool(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("truststore");
        String pw = "changeit";
        // an authority, and a server certificate for 127.0.0.1 signed by it
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=Test Root CA", "-ext", "bc:c",
                "-validity", "3", "-keystore", "ca.p12", "-storetype", "PKCS12", "-storepass", pw);
        keytool("-exportcert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", pw, "-file", "ca.der");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=publish.test", "-validity", "3",
                "-keystore", "server.p12", "-storetype", "PKCS12", "-storepass", pw);
        keytool("-certreq", "-alias", "server", "-keystore", "server.p12", "-storepass", pw, "-file", "server.csr");
        keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", pw, "-infile", "server.csr", "-outfile", "server.der",
                "-ext", "san=ip:127.0.0.1,dns:publish.test", "-validity", "3");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", "ca.der", "-keystore", "server.p12", "-storepass", pw);
        keytool("-importcert", "-alias", "server", "-file", "server.der", "-keystore", "server.p12", "-storepass", pw);

        caDer = Files.readAllBytes(dir.resolve("ca.der"));
        ca = TrustStore.parse(caDer).get(0);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(dir.toFile(), "server.p12"))) {
            ks.load(in, pw.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/bin/receive", x -> {
            byte[] body = "OK studio-transport".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, x.getRequestMethod().equals("HEAD") ? -1 : body.length);
            if (!x.getRequestMethod().equals("HEAD")) {
                x.getResponseBody().write(body);
            }
            x.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() throws Exception {
        server.stop(0);
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @AfterEach
    void javaOnly() throws Exception {
        TrustStore.use(Collections.emptyList());
    }

    private static String url(String host) {
        return "https://" + host + ":" + server.getAddress().getPort() + "/bin/receive";
    }

    private static int get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        TrustStore.apply(c);
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    @Test
    void anAuthorityJavaDoesNotKnowIsNotTrusted() throws Exception {
        TrustStore.use(Collections.emptyList());
        assertThrows(SSLHandshakeException.class, () -> get(url("127.0.0.1")));
        Map<String, Object> check = TrustStore.check(url("127.0.0.1"));
        assertEquals(false, check.get("trusted"));
        assertEquals("no authority this instance trusts signed it", check.get("message"));
        // the certificates the server shows, so that the administrator sees which authority to add
        List<?> chain = (List<?>) check.get("chain");
        assertEquals(2, chain.size(), chain.toString());
        assertEquals("CN=publish.test", ((Map<?, ?>) chain.get(0)).get("subject"));
        assertEquals("CN=Test Root CA", ((Map<?, ?>) chain.get(1)).get("subject"));
        assertTrue(String.valueOf(((Map<?, ?>) chain.get(1)).get("pem")).startsWith("-----BEGIN CERTIFICATE-----"));
    }

    @Test
    void addedToTheTrustStoreItIs() throws Exception {
        TrustStore.use(List.of(ca));
        assertEquals(200, get(url("127.0.0.1")));
        Map<String, Object> check = TrustStore.check(url("127.0.0.1"));
        assertEquals(true, check.get("trusted"), String.valueOf(check));
        // Java's own authorities are still trusted besides it
        assertTrue(TrustStore.trustManager(List.of(ca)).getAcceptedIssuers().length > 1);
    }

    @Test
    void theHostNameIsStillChecked() throws Exception {
        TrustStore.use(List.of(ca));
        // the certificate is for 127.0.0.1 and publish.test, not for localhost
        assertThrows(java.io.IOException.class, () -> get(url("localhost")));
        assertEquals(false, TrustStore.check(url("localhost")).get("trusted"));
    }

    @Test
    void certificatesAsPemOrDer() throws Exception {
        String pem = TrustStore.pem(ca);
        assertEquals(ca, TrustStore.parse(pem.getBytes(StandardCharsets.US_ASCII)).get(0));
        assertEquals(ca, TrustStore.parse(caDer).get(0));
        assertThrows(CertificateException.class, () -> TrustStore.parse("not a certificate".getBytes(StandardCharsets.US_ASCII)));
        Map<String, Object> d = TrustStore.describe(ca);
        assertEquals(true, d.get("authority"));
        assertEquals(true, d.get("selfSigned"));
        assertFalse((Boolean) d.get("expired"));
        assertEquals(95, String.valueOf(d.get("sha256")).length());
        assertEquals("test-root-ca", TrustStoreServlet.alias(null, ca));
        assertEquals("our-ca.2026", TrustStoreServlet.alias("  Our CA.2026 ", ca));
        assertEquals("certificate", TrustStoreServlet.alias("///", ca));
    }

    @Test
    void plainHttpHasNoCertificates() {
        assertEquals(false, TrustStore.check("http://127.0.0.1:1/").get("trusted"));
    }
}
