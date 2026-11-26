package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One chat completion against a model API: Anthropic's Messages API or an OpenAI-compatible chat completions API
 * (OpenAI, Azure OpenAI, Ollama and other local servers). Retries rate limits and overloads briefly.
 */
final class LlmClient {

    /** Anthropic's Messages API; an OpenAI-compatible chat API; OpenRouter (OpenAI format, many providers' models). */
    enum Kind { ANTHROPIC, OPENAI, OPENROUTER }

    static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    static final String OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    /** How Sensible Studio names itself to OpenRouter (app attribution). */
    static final String APP_TITLE = "Sensible Studio Translation";
    static final String ANTHROPIC_VERSION = "2023-06-01";

    /** The answer text and why the model stopped ({@code end_turn}/{@code stop}, {@code max_tokens}/{@code length} ...). */
    static final class Answer {
        final String text;
        final boolean truncated;

        Answer(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }

    private final Kind kind;
    private final String url;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http;

    private final Map<String, String> headers;

    LlmClient(Kind kind, String url, String model, String apiKey, int maxTokens, Duration timeout) {
        this(kind, url, model, apiKey, maxTokens, timeout, new LinkedHashMap<>());
    }

    /** {@code headers}: extra headers, resolved (they replace Sensible Studio's own, e.g. a different authorization). */
    LlmClient(Kind kind, String url, String model, String apiKey, int maxTokens, Duration timeout, Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : headers;
        this.kind = kind;
        this.url = url == null || url.isEmpty() ? (kind == Kind.ANTHROPIC ? ANTHROPIC_URL : kind == Kind.OPENROUTER ? OPENROUTER_URL : OPENAI_URL) : url;
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    String model() {
        return model;
    }

    Answer complete(String system, String user) throws TranslationException {
        return complete(system, user, null);
    }

    /**
     * {@code temperature}: how freely the model writes, 0..1; null for the default (translations: 0.2 on OpenAI-style
     * APIs, the API's own for Claude).
     */
    Answer complete(String system, String user, Double temperature) throws TranslationException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null && !model.isEmpty()) body.put("model", model); // OpenRouter: none = the account's default model
        body.put("max_tokens", (long) maxTokens);
        List<Object> messages = new ArrayList<>();
        if (kind == Kind.ANTHROPIC) {
            body.put("system", system);
            if (temperature != null) body.put("temperature", temperature);
        } else {
            messages.add(message("system", system));
            body.put("temperature", temperature != null ? temperature : 0.2);
        }
        messages.add(message("user", user));
        body.put("messages", messages);

        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (kind == Kind.ANTHROPIC) {
            req.header("x-api-key", apiKey == null ? "" : apiKey).header("anthropic-version", ANTHROPIC_VERSION);
        } else if (apiKey != null && !apiKey.isEmpty()) {
            req.header("authorization", "Bearer " + apiKey);
        }
        if (kind == Kind.OPENROUTER) req.header("X-Title", APP_TITLE).header("X-OpenRouter-Title", APP_TITLE);
        for (Map.Entry<String, String> h : headers.entrySet()) req.setHeader(h.getKey(), h.getValue());

        HttpResponse<String> res = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt == 2) throw new TranslationException("Cannot reach " + url + ": " + e.getMessage(), e,
                        TranslationException.ErrorCode.SERVICE_NOT_AVAILABLE);
                pause(attempt, null);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TranslationException("Interrupted", e, TranslationException.ErrorCode.GENERAL);
            }
            int status = res.statusCode();
            if (status == 429 || status == 529 || status >= 500) {
                if (attempt == 2) break;
                pause(attempt, res.headers().firstValue("retry-after").orElse(null));
                continue;
            }
            break;
        }
        int status = res.statusCode();
        if (status == 401 || status == 403) {
            throw new TranslationException("The model API refused the credentials (HTTP " + status + "): " + errorOf(res.body()),
                    TranslationException.ErrorCode.MISSING_CREDENTIALS);
        }
        if (status == 402) {
            throw new TranslationException("The model API wants payment (HTTP 402, e.g. no credits left): " + errorOf(res.body()),
                    TranslationException.ErrorCode.SERVICE_NOT_AVAILABLE);
        }
        if (status == 429 || status == 529 || status >= 500) {
            throw new TranslationException("The model API is busy (HTTP " + status + "): " + errorOf(res.body()),
                    TranslationException.ErrorCode.SERVICE_NOT_AVAILABLE);
        }
        if (status != 200) {
            throw new TranslationException("The model API answered HTTP " + status + ": " + errorOf(res.body()),
                    TranslationException.ErrorCode.REQUEST_FAILED);
        }
        return parse(res.body());
    }

    @SuppressWarnings("unchecked")
    Answer parse(String json) throws TranslationException {
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException e) {
            throw new TranslationException("Not JSON: " + abbreviate(json), e, TranslationException.ErrorCode.INVALID_RESPONSE);
        }
        if (!(parsed instanceof Map)) throw new TranslationException("Unexpected answer", TranslationException.ErrorCode.INVALID_RESPONSE);
        Map<String, Object> m = (Map<String, Object>) parsed;
        StringBuilder text = new StringBuilder();
        String stop;
        if (kind == Kind.ANTHROPIC) {
            Object content = m.get("content");
            if (content instanceof List) {
                for (Object block : (List<Object>) content) {
                    if (block instanceof Map && "text".equals(((Map<String, Object>) block).get("type"))) {
                        text.append(((Map<String, Object>) block).get("text"));
                    }
                }
            }
            stop = String.valueOf(m.get("stop_reason"));
            if ("refusal".equals(stop)) throw new TranslationException("The model refused to translate this content", TranslationException.ErrorCode.REQUEST_FAILED);
        } else {
            Object choices = m.get("choices");
            Map<String, Object> first = choices instanceof List && !((List<Object>) choices).isEmpty()
                    ? (Map<String, Object>) ((List<Object>) choices).get(0) : null;
            Object msg = first == null ? null : first.get("message");
            if (msg instanceof Map && ((Map<String, Object>) msg).get("content") != null) {
                text.append(((Map<String, Object>) msg).get("content"));
            }
            stop = first == null ? null : String.valueOf(first.get("finish_reason"));
            if ("content_filter".equals(stop)) throw new TranslationException("The model's content filter stopped the translation", TranslationException.ErrorCode.REQUEST_FAILED);
            if ("error".equals(stop) || m.get("error") != null && text.length() == 0) {
                throw new TranslationException("The model failed: " + errorOf(json), TranslationException.ErrorCode.REQUEST_FAILED);
            }
        }
        return new Answer(text.toString(), "max_tokens".equals(stop) || "length".equals(stop) || "model_context_window_exceeded".equals(stop));
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String errorOf(String body) {
        try {
            Object parsed = Json.parse(body);
            if (parsed instanceof Map) {
                Object error = ((Map<String, Object>) parsed).get("error");
                if (error instanceof Map && ((Map<String, Object>) error).get("message") != null) {
                    return String.valueOf(((Map<String, Object>) error).get("message"));
                }
                if (error != null) return String.valueOf(error);
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return abbreviate(body);
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static void pause(int attempt, String retryAfter) {
        long millis = (long) (1000 * Math.pow(3, attempt));
        try {
            if (retryAfter != null) millis = Math.min(10_000, Math.max(millis, (long) (Double.parseDouble(retryAfter) * 1000)));
        } catch (NumberFormatException ignored) {
            // keep the backoff
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
