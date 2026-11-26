/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Public image URLs served by Sensible Studio from studio-dam:
 * <pre>
 * /bin/studio/dam/deliver/&lt;sig&gt;/&lt;blobId&gt;/&lt;width&gt;/&lt;quality&gt;/&lt;seo-name&gt;.&lt;jpeg|png|webp&gt;
 * </pre>
 * The signature covers blob, format and quality but not the width: Core Components put a
 * width placeholder into responsive URL templates that the browser fills in. The servlet
 * rounds the width up to a multiple of {@value #WIDTH_STEP} so the number of variants per
 * image stays bounded. Everything is in the path (no query string) so CDNs and the dispatcher
 * cache it as a plain file.
 */
final class DeliveryUrls {

    static final String PREFIX = "/bin/studio/dam/deliver";
    static final int WIDTH_STEP = 16;
    static final int MIN_WIDTH = 16;
    static final int MAX_WIDTH = 4096;
    static final int DEFAULT_WIDTH = 1280;
    static final int DEFAULT_QUALITY = 80;

    private static final Pattern SUFFIX = Pattern.compile(
            "^/([0-9a-f]{32})/([0-9a-f]{64})/(\\d{1,5})/(\\d{1,3})/([A-Za-z0-9_-][A-Za-z0-9._-]{0,149})\\.(jpeg|png|webp)$");

    /** A parsed, not yet verified, delivery request. */
    static final class Request {
        final String signature;
        final String blobId;
        final int width;
        final int quality;
        final String format;

        Request(String signature, String blobId, int width, int quality, String format) {
            this.signature = signature;
            this.blobId = blobId;
            this.width = width;
            this.quality = quality;
            this.format = format;
        }

        String canonical() {
            return DeliveryUrls.canonical(blobId, format, quality);
        }
    }

    private DeliveryUrls() {
    }

    static String canonical(String blobId, String format, int quality) {
        return blobId + "/" + format + "/" + quality;
    }

    /** {@code width} is written as given (it may be Core Components' placeholder value). */
    static String build(String signature, String blobId, String width, int quality, String seoName, String format) {
        return PREFIX + "/" + signature + "/" + blobId + "/" + width + "/" + quality + "/" + seoName(seoName) + "." + format;
    }

    /** @return the request, or {@code null} if the suffix is not a delivery URL */
    static Request parse(String suffix) {
        if (suffix == null) {
            return null;
        }
        Matcher m = SUFFIX.matcher(suffix);
        if (!m.matches()) {
            return null;
        }
        int quality = Integer.parseInt(m.group(4));
        // PNG is lossless: its URLs carry quality 0. JPEG and WebP need 1..100.
        boolean png = "png".equals(m.group(6));
        if (png ? quality != 0 : quality < 1 || quality > 100) {
            return null;
        }
        return new Request(m.group(1), m.group(2), Integer.parseInt(m.group(3)), quality, m.group(6));
    }

    static int snapWidth(int requested) {
        int w = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, requested));
        return Math.min(MAX_WIDTH, ((w + WIDTH_STEP - 1) / WIDTH_STEP) * WIDTH_STEP);
    }

    static boolean signatureMatches(String expected, String given) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), given.getBytes(StandardCharsets.US_ASCII));
    }

    static String seoName(String raw) {
        if (raw == null) {
            return "image";
        }
        String s = raw.replaceAll("\\.(?i)(jpe?g|png|webp|gif|tiff?)$", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (s.length() > 100) {
            s = s.substring(0, 100);
        }
        return s.isEmpty() ? "image" : s;
    }
}
