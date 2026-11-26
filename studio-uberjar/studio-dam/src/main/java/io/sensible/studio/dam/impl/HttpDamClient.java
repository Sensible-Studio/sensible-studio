/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link DamClient} over HTTP with the JDK client (no extra bundles needed).
 */
public final class HttpDamClient implements DamClient {

    private static final Pattern BLOB_ID = Pattern.compile("[0-9a-f]{64}");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final URI base;
    private final String token;
    private final HttpClient http;
    private final Duration processTimeout;

    public HttpDamClient(String baseUrl, String token, Duration processTimeout) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(b);
        this.token = token;
        this.processTimeout = processTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token);
    }

    @Override
    public String putBlob(InputStream in) throws IOException {
        HttpRequest req = request("/blobs")
                .timeout(processTimeout)
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new NonClosingInputStream(in)))
                .build();
        String body = check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        Object id = Json.parseObject(body).get("blobId");
        return validId(id == null ? null : id.toString());
    }

    @Override
    public ProcessResult process(String blobId, String mimeType) throws IOException {
        return process(blobId, mimeType, java.util.Collections.emptyList());
    }

    @Override
    public ProcessResult process(String blobId, String mimeType, List<Map<String, Object>> extra) throws IOException {
        return process(blobId, mimeType, extra, null);
    }

    @Override
    public ProcessResult process(String blobId, String mimeType, List<Map<String, Object>> extra, Map<String, Object> watermark)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobId", validId(blobId));
        if (mimeType != null) {
            body.put("mimeType", mimeType);
        }
        if (extra != null && !extra.isEmpty()) {
            body.put("extra", extra);
        }
        if (watermark != null && extra != null && !extra.isEmpty()) {
            Map<String, Object> w = new LinkedHashMap<>(watermark);
            w.put("blobId", validId(String.valueOf(watermark.get("blobId"))));
            body.put("watermark", w);
        }
        String json = JsonOut.write(body);
        HttpRequest req = request("/process")
                .timeout(processTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return ProcessResult.fromJson(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
    }

    @Override
    public EditResult edit(String blobId, List<Map<String, Object>> operations) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobId", validId(blobId));
        body.put("operations", operations);
        HttpRequest req = request("/edit")
                .timeout(processTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(body), StandardCharsets.UTF_8))
                .build();
        Map<String, Object> res = Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
        Object id = res.get("blobId");
        return new EditResult(validId(id == null ? null : id.toString()), String.valueOf(res.get("mimeType")),
                number(res.get("width")), number(res.get("height")), number(res.get("size")));
    }

    @Override
    public Map<String, Object> smartTagsStatus() throws IOException {
        HttpRequest req = request("/smart-tags/status").timeout(Duration.ofSeconds(10)).GET().build();
        return Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
    }

    @Override
    public void installSmartTagsFile(String name, InputStream in) throws IOException {
        installModelFile("/smart-tags/model/", name, in);
    }

    @Override
    public Map<String, Object> searchModelStatus() throws IOException {
        HttpRequest req = request("/search-model/status").timeout(Duration.ofSeconds(10)).GET().build();
        return Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
    }

    @Override
    public void installSearchModelFile(String name, InputStream in) throws IOException {
        installModelFile("/search-model/model/", name, in);
    }

    private void installModelFile(String prefix, String name, InputStream in) throws IOException {
        if (name == null || !name.matches("^[a-z]+(\\.[a-z]+)+$")) {
            throw new DamProcessingException(0, "invalid model file name");
        }
        HttpRequest req = request(prefix + name)
                .timeout(Duration.ofMinutes(30))
                .PUT(HttpRequest.BodyPublishers.ofInputStream(() -> new NonClosingInputStream(in)))
                .build();
        check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> predictTags(String blobId, List<Map<String, String>> labels, int max, double minConfidence,
            double minScore) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobId", validId(blobId));
        body.put("labels", labels);
        body.put("max", (long) max);
        body.put("minConfidence", minConfidence);
        body.put("minScore", minScore);
        HttpRequest req = request("/smart-tags/predict")
                // the first call loads the model and embeds every label
                .timeout(processTimeout.plus(Duration.ofMinutes(5)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(body), StandardCharsets.UTF_8))
                .build();
        Object tags = Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))).get("tags");
        return tags instanceof List ? (List<Map<String, Object>>) tags : java.util.Collections.emptyList();
    }

    @Override
    public Map<String, Object> smartCrop(String blobId, List<Map<String, Object>> ratios) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobId", validId(blobId));
        body.put("ratios", ratios);
        HttpRequest req = request("/smart-crop")
                .timeout(processTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(body), StandardCharsets.UTF_8))
                .build();
        return Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
    }

    @Override
    public TextEmbedding embedText(String text) throws IOException {
        return embedText("/smart-tags/embed-text", text);
    }

    @Override
    public TextEmbedding searchEmbedText(String text) throws IOException {
        return embedText("/search-model/embed-text", text);
    }

    private TextEmbedding embedText(String path, String text) throws IOException {
        HttpRequest req = request(path)
                .timeout(processTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(java.util.Collections.singletonMap("text", text)), StandardCharsets.UTF_8))
                .build();
        Map<String, Object> res = Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
        Object scale = res.get("scale");
        Object bias = res.get("bias");
        if (!(scale instanceof Number) || !(bias instanceof Number)) {
            throw new DamProcessingException(0, "studio-dam sent no scale");
        }
        return new TextEmbedding(floats(res.get("embedding")), ((Number) scale).doubleValue(), ((Number) bias).doubleValue());
    }

    private static float[] floats(Object e) throws DamProcessingException {
        if (!(e instanceof List)) {
            throw new DamProcessingException(0, "studio-dam sent no embedding");
        }
        List<?> list = (List<?>) e;
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = list.get(i);
            if (!(v instanceof Number)) {
                throw new DamProcessingException(0, "studio-dam sent a bad embedding");
            }
            out[i] = ((Number) v).floatValue();
        }
        return out;
    }

    @Override
    public float[] embed(String blobId) throws IOException {
        return embed("/smart-tags/embed", blobId);
    }

    @Override
    public float[] searchEmbed(String blobId) throws IOException {
        return embed("/search-model/embed", blobId);
    }

    private float[] embed(String path, String blobId) throws IOException {
        HttpRequest req = request(path)
                .timeout(processTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(java.util.Collections.singletonMap("blobId", validId(blobId))), StandardCharsets.UTF_8))
                .build();
        return floats(Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))).get("embedding"));
    }

    @Override
    public byte[] videoPoster(String blobId) throws IOException {
        HttpRequest req = request("/video/poster")
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(java.util.Collections.singletonMap("blobId", validId(blobId))), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> res = send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw failure(res.statusCode(), new String(res.body(), StandardCharsets.UTF_8));
        }
        return res.body();
    }

    @Override
    public String videoPreview(String blobId) throws IOException {
        return video(java.util.Collections.singletonMap("blobId", validId(blobId)));
    }

    @Override
    public String videoEncode(String blobId, String format, int height) throws IOException {
        if (!"mp4".equals(format) && !"webm".equals(format)) {
            throw new IllegalArgumentException("format is mp4 or webm");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blobId", validId(blobId));
        body.put("format", format);
        body.put("height", height);
        return video(body);
    }

    private String video(Map<String, Object> body) throws IOException {
        HttpRequest req = request("/video/preview")
                .timeout(Duration.ofMinutes(16)) // studio-dam gives ffmpeg fifteen
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(body), StandardCharsets.UTF_8))
                .build();
        Object id = Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))).get("blobId");
        return validId(String.valueOf(id));
    }

    @Override
    public Map<String, Object> gcBlobs(java.util.Collection<String> keep, long graceSeconds, boolean dryRun) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keep", new java.util.ArrayList<>(keep));
        body.put("graceSeconds", graceSeconds);
        body.put("dryRun", dryRun);
        HttpRequest req = request("/blobs/gc")
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOut.write(body), StandardCharsets.UTF_8))
                .build();
        return Json.parseObject(check(send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))));
    }

    private static long number(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    @Override
    public InputStream getBlob(String blobId) throws IOException {
        HttpRequest req = request("/blobs/" + validId(blobId)).timeout(processTimeout).GET().build();
        HttpResponse<InputStream> res = send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            String msg;
            try (InputStream err = res.body()) {
                msg = new String(err.readNBytes(2048), StandardCharsets.UTF_8);
            }
            throw failure(res.statusCode(), msg);
        }
        return res.body();
    }

    /**
     * Healthy = answers and proves it knows the token (HMAC over a random challenge). Another
     * program squatting on the port cannot pass, and the token itself is never sent.
     */
    @Override
    public boolean isHealthy() {
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        StringBuilder challenge = new StringBuilder();
        for (byte b : nonce) {
            challenge.append(String.format("%02x", b));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health?challenge=" + challenge))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return false;
            }
            Map<String, Object> m = Json.parseObject(res.body());
            Object proof = m.get("proof");
            String expected = hmacHex(token.getBytes(StandardCharsets.UTF_8), "health:" + challenge);
            return "ok".equals(m.get("status")) && proof instanceof String
                    && java.security.MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                            ((String) proof).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    /** ffmpeg does not come and go: asked at most once a minute. */
    private volatile Boolean videoTools;
    private volatile long videoToolsAsked;

    @Override
    public Boolean videoTools() {
        long now = System.currentTimeMillis();
        if (videoTools != null && now - videoToolsAsked < 60_000) {
            return videoTools;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health")).timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            Object f = res.statusCode() == 200 ? Json.parseObject(res.body()).get("ffmpeg") : null;
            videoTools = f instanceof Boolean ? (Boolean) f : null;
            videoToolsAsked = now;
            return videoTools;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality) throws IOException {
        return deliver(blobId, width, format, quality, null);
    }

    /** Transforms studio-dam accepts, checked here too so nothing else ends up in the URL. */
    private static final String STEP = "c\\d{1,9},\\d{1,9},\\d{1,9},\\d{1,9}|r90|r180|r270|h|v|fcrop|fstretch|u|s\\d{1,3},\\d{1,4},\\d{1,3}|b\\d{1,4}|[lka]n?\\d{1,3}|g[0-9a-f]{6}"
            + "|m|p|o[0-9a-f]{6},\\d{1,3}|d[btlr],[0-9a-f]{6},\\d{1,3},\\d{1,3}|w\\d{1,3}|e\\d{1,4}|x\\d{1,4},[0-9a-f]{6}";
    private static final Pattern TRANSFORM = Pattern.compile("^(" + STEP + ")(-(" + STEP + ")){0,19}$");

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality, String transform) throws IOException {
        return deliver(blobId, width, MAX_DELIVERY_HEIGHT, format, quality, transform);
    }

    /** Whether {@code transform} is one studio-dam accepts (checked before anything reaches the URL). */
    static boolean validTransform(String transform) {
        return transform == null || transform.isEmpty() || TRANSFORM.matcher(transform).matches();
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, int height, String format, int quality, String transform) throws IOException {
        String t = transform == null ? "" : transform;
        if (!validTransform(t)) {
            throw new DamProcessingException(0, "bad transform " + t);
        }
        String ext = "jpg".equals(format) ? "jpeg" : format;
        if (!"jpeg".equals(ext) && !"png".equals(ext) && !"webp".equals(ext)) {
            throw new DamProcessingException(0, "unsupported format " + format);
        }
        int q = "png".equals(ext) ? 0 : quality;
        // studio-dam signs the full request (see dam-service/src/delivery.rs): <blob>/<W>x<H>.<ext>?q=<q>
        if (width < 1 || height < 1 || width > MAX_DELIVERY_HEIGHT || height > MAX_DELIVERY_HEIGHT) {
            throw new DamProcessingException(0, "bad size " + width + "x" + height);
        }
        String canonical = validId(blobId) + "/" + width + "x" + height + "." + ext + "?q=" + q
                + (t.isEmpty() ? "" : "&t=" + t);
        String query = ("png".equals(ext) ? "" : "q=" + q + "&") + (t.isEmpty() ? "" : "t=" + t + "&")
                + "sig=" + hmacHex(token.getBytes(StandardCharsets.UTF_8), canonical);
        // Short timeout: a visitor's request holds a servlet thread while it waits.
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/deliver/" + blobId + "/" + width + "x"
                + height + "." + ext + "?" + query)).timeout(DELIVERY_TIMEOUT).GET().build();
        HttpResponse<InputStream> res = send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            String msg;
            try (InputStream err = res.body()) {
                msg = new String(err.readNBytes(2048), StandardCharsets.UTF_8);
            }
            throw failure(res.statusCode(), msg);
        }
        return new DeliveredImage(res.headers().firstValue("content-type").orElse("application/octet-stream"),
                res.headers().firstValue("x-studio-cache").orElse(null),
                res.headers().firstValueAsLong("content-length").orElse(-1), res.body());
    }

    @Override
    public String signUrl(String canonical) {
        byte[] key = hmac(token.getBytes(StandardCharsets.UTF_8), "studio-delivery-url-v1");
        return hmacHex(key, canonical).substring(0, 32);
    }

    static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(15);

    /** Height of the bounding box sent to studio-dam: delivery is width-driven. */
    static final int MAX_DELIVERY_HEIGHT = 4096;

    static String hmacHex(byte[] key, String data) {
        StringBuilder s = new StringBuilder();
        for (byte b : hmac(key, data)) {
            s.append(String.format("%02x", b));
        }
        return s.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling studio-dam", e);
        }
    }

    private static String check(HttpResponse<String> res) throws IOException {
        int s = res.statusCode();
        if (s >= 200 && s < 300) {
            return res.body();
        }
        throw failure(s, res.body());
    }

    /** 4xx are permanent (except 408/429), everything else is worth a retry. */
    private static IOException failure(int status, String body) {
        String msg = body == null ? "" : body.length() > 500 ? body.substring(0, 500) : body;
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            return new DamProcessingException(status, msg);
        }
        return new IOException("studio-dam returned " + status + ": " + msg);
    }

    private static String validId(String id) throws DamProcessingException {
        if (id == null || !BLOB_ID.matcher(id).matches()) {
            throw new DamProcessingException(0, "invalid blob id");
        }
        return id;
    }

    /** The HTTP client closes the body stream when done; the caller owns the original stream. */
    private static final class NonClosingInputStream extends java.io.FilterInputStream {
        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // owned by the caller
        }
    }
}
