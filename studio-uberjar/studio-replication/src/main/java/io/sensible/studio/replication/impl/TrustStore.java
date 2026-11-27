/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.replication.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The instance's trust store, as AEM's Tools › Security › Trust Store: certificates an
 * administrator added, trusted for the instance's own HTTPS connections (replication, cache
 * flushes and status, reverse replication) besides the ones Java trusts already. For publish
 * instances or caches behind HTTPS with a certificate of the organisation's own authority.
 *
 * <p>The certificates are nodes below {@code /etc/truststore}, one per alias, the certificate
 * as PEM in {@code certificate}. Certificates are not secrets: there is no password; who may
 * change them is who may write there (administrators). Host names are checked as always.
 */
@Component(service = { TrustStore.class, ResourceChangeListener.class }, immediate = true,
        property = { ResourceChangeListener.PATHS + "=" + TrustStore.PATH })
public class TrustStore implements ResourceChangeListener {

    static final String PATH = "/etc/truststore";
    static final String CERTIFICATE = "certificate";
    private static final Logger LOG = LoggerFactory.getLogger(TrustStore.class);

    /** What the connections use; null: Java's own trust only. */
    private static volatile SSLSocketFactory factory;
    private static volatile X509TrustManager trust;

    @Reference
    private AgentManagerImpl agents;

    @Activate
    void activate() {
        reload();
    }

    @Deactivate
    void deactivate() {
        factory = null;
        trust = null;
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        reload();
    }

    /** Reads the certificates again and makes the context the connections use from them. */
    void reload() {
        List<X509Certificate> added = new ArrayList<>();
        try (ResourceResolver rr = agents.serviceResolver()) {
            Resource root = rr.getResource(PATH);
            if (root != null) {
                for (Resource r : root.getChildren()) {
                    String pem = r.getValueMap().get(CERTIFICATE, String.class);
                    if (pem == null) {
                        continue;
                    }
                    try {
                        added.addAll(parse(pem.getBytes(StandardCharsets.UTF_8)));
                    } catch (CertificateException e) {
                        LOG.warn("Trust store: {} is not a certificate: {}", r.getPath(), e.getMessage());
                    }
                }
            }
        } catch (LoginException e) {
            LOG.warn("Trust store not read: {}", e.getMessage());
        }
        try {
            use(added);
            LOG.info("Trust store: {} certificate(s) trusted besides Java's own", added.size());
        } catch (GeneralSecurityException e) {
            LOG.error("Trust store not applied", e);
        }
    }

    /** The connections trust Java's own authorities and these certificates from now on. */
    static void use(Collection<X509Certificate> added) throws GeneralSecurityException {
        X509TrustManager tm = trustManager(added);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { tm }, null);
        trust = tm;
        factory = ctx.getSocketFactory();
    }

    /** Gives an HTTPS connection the trust store; other connections are left as they are. */
    static void apply(URLConnection c) {
        SSLSocketFactory f = factory;
        if (f != null && c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(f);
        }
    }

    /** Java's own trust, then the certificates added here. */
    static X509TrustManager trustManager(Collection<X509Certificate> added) throws GeneralSecurityException {
        X509TrustManager java = javaTrust();
        if (added.isEmpty()) {
            return java;
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            ks.load(null, null);
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        int i = 0;
        for (X509Certificate c : added) {
            ks.setCertificateEntry("studio-" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        X509TrustManager own = firstX509(tmf.getTrustManagers());
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    java.checkClientTrusted(chain, authType);
                } catch (CertificateException e) {
                    own.checkClientTrusted(chain, authType);
                }
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    java.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    try {
                        own.checkServerTrusted(chain, authType);
                    } catch (CertificateException e2) {
                        throw e;
                    }
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> all = new ArrayList<>(List.of(java.getAcceptedIssuers()));
                all.addAll(List.of(own.getAcceptedIssuers()));
                return all.toArray(new X509Certificate[0]);
            }
        };
    }

    private static X509TrustManager javaTrust() throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf.getTrustManagers());
    }

    private static X509TrustManager firstX509(TrustManager[] managers) throws GeneralSecurityException {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) {
                return (X509TrustManager) m;
            }
        }
        throw new GeneralSecurityException("no X.509 trust manager");
    }

    /** The certificates of a PEM text or a DER file (one or several). */
    static List<X509Certificate> parse(byte[] data) throws CertificateException {
        List<X509Certificate> out = new ArrayList<>();
        for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(data))) {
            out.add((X509Certificate) c);
        }
        if (out.isEmpty()) {
            throw new CertificateException("no certificate in it");
        }
        return out;
    }

    /** A certificate as PEM text. */
    static String pem(X509Certificate c) throws CertificateException {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(c.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    /** What the console shows of a certificate. */
    static Map<String, Object> describe(X509Certificate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subject", c.getSubjectX500Principal().getName());
        m.put("issuer", c.getIssuerX500Principal().getName());
        m.put("notBefore", c.getNotBefore().getTime());
        m.put("notAfter", c.getNotAfter().getTime());
        m.put("expired", c.getNotAfter().getTime() < System.currentTimeMillis());
        m.put("authority", c.getBasicConstraints() >= 0);
        m.put("selfSigned", c.getSubjectX500Principal().equals(c.getIssuerX500Principal()));
        try {
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            List<String> names = new ArrayList<>();
            for (List<?> san : sans == null ? Collections.<List<?>>emptyList() : sans) {
                if (san.size() > 1 && (Integer.valueOf(2).equals(san.get(0)) || Integer.valueOf(7).equals(san.get(0)))) {
                    names.add(String.valueOf(san.get(1)));
                }
            }
            m.put("names", names);
            m.put("sha256", fingerprint(c));
        } catch (GeneralSecurityException e) {
            m.put("names", Collections.emptyList());
        }
        return m;
    }

    static String fingerprint(X509Certificate c) throws GeneralSecurityException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            sb.append(i == 0 ? "" : ":").append(String.format("%02X", d[i] & 0xff));
        }
        return sb.toString();
    }

    /**
     * Connects to an HTTPS address the way the instance's connections do and says whether it is
     * trusted, with the certificates the server shows (also when it is not trusted, so that an
     * administrator can see which authority to add). Nothing is sent but the handshake and a HEAD.
     */
    static Map<String, Object> check(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<X509Certificate[]> seen = new ArrayList<>();
        try {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) {
                out.put("trusted", false);
                out.put("message", "Only https addresses have certificates");
                return out;
            }
            X509TrustManager base = trust != null ? trust : javaTrust();
            X509TrustManager watching = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    base.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    seen.add(chain);
                    base.checkServerTrusted(chain, authType);
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return base.getAcceptedIssuers();
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { watching }, null);
            HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
            c.setSSLSocketFactory(ctx.getSocketFactory());
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("HEAD");
            c.setInstanceFollowRedirects(false);
            try {
                int status = c.getResponseCode();
                out.put("trusted", true);
                out.put("message", "the server answered HTTP " + status);
            } finally {
                ((HttpURLConnection) c).disconnect();
            }
        } catch (javax.net.ssl.SSLHandshakeException e) {
            out.put("trusted", false);
            out.put("message", seen.isEmpty() ? e.getMessage() : reason(e));
        } catch (java.net.SocketTimeoutException e) {
            // the certificate was accepted (the handshake is behind us) and then the server said nothing
            out.put("trusted", !seen.isEmpty());
            out.put("message", seen.isEmpty() ? "The server did not answer in time" : "The certificate is trusted; the server did not answer the request in time");
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            out.put("trusted", false);
            out.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        List<Map<String, Object>> chain = new ArrayList<>();
        if (!seen.isEmpty()) {
            for (X509Certificate x : seen.get(seen.size() - 1)) {
                Map<String, Object> m = describe(x);
                try {
                    m.put("pem", pem(x));
                } catch (CertificateException e) {
                    // shown without it
                }
                chain.add(m);
            }
        }
        out.put("chain", chain);
        return out;
    }

    private static String reason(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? e.getMessage() : t.getMessage();
        if (m != null && m.contains("unable to find valid certification path")) {
            return "no authority this instance trusts signed it";
        }
        if (m != null && m.contains("No subject alternative")) {
            return "the certificate is not for this host name";
        }
        return m;
    }
}
