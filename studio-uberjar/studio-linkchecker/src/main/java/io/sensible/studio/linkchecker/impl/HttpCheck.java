/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.linkchecker.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Checks an external link the way a browser would reach it: {@code HEAD} first, {@code GET} when the site
 * does not answer HEAD properly, following up to five redirects. Valid is any answer below 400.
 */
public final class HttpCheck {

    static final String USER_AGENT = "Mozilla/5.0 (compatible; SensibleStudio-LinkChecker/1.0)";
    private static final int MAX_REDIRECTS = 5;

    private HttpCheck() {
    }

    public static Links.Check check(String url, int timeoutMillis) {
        try {
            Answer a = request(url, "HEAD", timeoutMillis);
            // many sites refuse or mishandle HEAD: ask again with GET before calling the link broken
            if (a.code >= 400 || a.code < 0) {
                Answer g = request(url, "GET", timeoutMillis);
                if (g.code > 0) {
                    a = g;
                }
            }
            boolean valid = a.code > 0 && a.code < 400;
            return new Links.Check(valid, a.code, valid ? null : reason(a.code), a.finalUrl == null || a.finalUrl.equals(url) ? null : a.finalUrl);
        } catch (UnknownHostException e) {
            return new Links.Check(false, 0, "Unknown host " + e.getMessage(), null);
        } catch (SocketTimeoutException e) {
            return new Links.Check(false, 0, "No answer within " + timeoutMillis / 1000 + " s", null);
        } catch (SSLException e) {
            return new Links.Check(false, 0, "TLS: " + e.getMessage(), null);
        } catch (IllegalArgumentException e) {
            return new Links.Check(false, 0, "Not a valid URL", null);
        } catch (IOException e) {
            return new Links.Check(false, 0, e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), null);
        }
    }

    private static final class Answer {
        final int code;
        final String finalUrl;

        Answer(int code, String finalUrl) {
            this.code = code;
            this.finalUrl = finalUrl;
        }
    }

    private static Answer request(String url, String method, int timeoutMillis) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = URI.create(current.replace(" ", "%20")).toURL();
            String protocol = u.getProtocol().toLowerCase(Locale.ROOT);
            if (!"http".equals(protocol) && !"https".equals(protocol)) {
                throw new IllegalArgumentException(current);
            }
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setRequestMethod(method);
                c.setConnectTimeout(timeoutMillis);
                c.setReadTimeout(timeoutMillis);
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    current = u.toURI().resolve(c.getHeaderField("Location").replace(" ", "%20")).toString();
                    continue;
                }
                if ("GET".equals(method)) {
                    drain(c);
                }
                return new Answer(code, current);
            } catch (java.net.URISyntaxException e) {
                throw new IllegalArgumentException(e);
            } finally {
                c.disconnect();
            }
        }
        return new Answer(310, current);
    }

    /** Reads a little of the body so the connection can be reused, without downloading big pages. */
    private static void drain(HttpURLConnection c) {
        try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in != null) {
                byte[] buf = new byte[8192];
                in.read(buf);
            }
        } catch (IOException e) {
            // the code is what matters
        }
    }

    static String reason(int code) {
        switch (code) {
            case 0:
                return "No answer";
            case 310:
                return "Too many redirects";
            case 401:
                return "401 Unauthorized";
            case 403:
                return "403 Forbidden";
            case 404:
                return "404 Not Found";
            case 410:
                return "410 Gone";
            case 429:
                return "429 Too Many Requests";
            case 500:
                return "500 Server Error";
            case 502:
                return "502 Bad Gateway";
            case 503:
                return "503 Unavailable";
            default:
                return "HTTP " + code;
        }
    }
}
