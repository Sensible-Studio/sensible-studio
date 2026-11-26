package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationServiceFactory;
import io.sensible.studio.translation.api.CloudConfig;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import javax.servlet.Servlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;

/**
 * Translation cloud configurations for the author UI ("Cloud Services → Translation").
 * <pre>
 * GET  /bin/studio/translation.json?op=configs                  all configurations, the confs and the connectors
 * GET  /bin/studio/translation.json?op=config&amp;path=...           one configuration
 * GET  /bin/studio/translation.json?op=providers&amp;path=&lt;content&gt;  what can translate that content (configurations first)
 * POST /bin/studio/translation  {"op":"save", "conf", "name", "path"?, "title", "connector", "settings":{...}}   422 {errors}
 *      settings.apiKey: a reference ($[secret:NAME], $[env:NAME]) or the stored $[enc:v1:...];
 *      settings.apiKeyValue: a key typed in the console, stored encrypted (never answered)
 *                            {"op":"delete", "path"}
 *                            {"op":"test", "path"} or {"op":"test", "config":{"title","connector","settings"}}
 * </pre>
 * API keys are never accepted or answered: a configuration holds a reference ({@code $[secret:NAME]}) and the
 * answer says only whether it resolves on this instance.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/translation",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class TranslationConfigServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    transient volatile Translator translator;

    @Reference
    transient volatile Secrets secrets;

    private final transient List<TranslationServiceFactory> factories = new CopyOnWriteArrayList<>();

    @Reference(service = TranslationServiceFactory.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindFactory(TranslationServiceFactory factory) {
        factories.add(factory);
    }

    void unbindFactory(TranslationServiceFactory factory) {
        factories.remove(factory);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        if ("configs".equals(op)) {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Object> configs = new ArrayList<>();
            List<String> confs = CloudConfigs.confs(resolver);
            for (String conf : confs) for (CloudConfig c : CloudConfigs.list(resolver, conf)) configs.add(json(c));
            out.put("confs", confs);
            out.put("connectors", connectors());
            out.put("configs", configs);
            send(response, 200, out);
        } else if ("config".equals(op)) {
            CloudConfig c = path == null ? null : CloudConfigs.read(resolver.getResource(path));
            if (c == null) send(response, 404, error("No translation cloud configuration at " + path));
            else send(response, 200, json(c));
        } else if ("providers".equals(op)) {
            send(response, 200, translator.providers(resolver, path));
        } else {
            send(response, 400, error("Unknown op " + op));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(readBody(request));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("A JSON object is expected");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            body = m;
        } catch (IllegalArgumentException e) {
            send(response, 400, error("Bad request: " + e.getMessage()));
            return;
        }
        String op = str(body, "op");
        try {
            if ("save".equals(op)) {
                Form form = Form.of(body, connectorNames(), secrets);
                String path = str(body, "path");
                String conf = path != null ? new CloudConfig(path, null, null, new LinkedHashMap<>()).getConf() : str(body, "conf");
                String name = path != null ? path.substring(path.lastIndexOf('/') + 1) : form.name;
                if (conf == null || !conf.startsWith("/conf/") || resolver.getResource(conf) == null) form.error("conf", "Choose a configuration folder under /conf.");
                else if (path == null && name != null && resolver.getResource(conf + "/" + CloudConfig.BUCKET + "/" + name) != null) {
                    form.error("name", "A configuration named " + name + " exists already in " + conf + ".");
                } else if (path != null && CloudConfigs.read(resolver.getResource(path)) == null) {
                    send(response, 404, error("No translation cloud configuration at " + path));
                    return;
                }
                if (!form.errors.isEmpty()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("errors", form.errors);
                    send(response, 422, m);
                    return;
                }
                CloudConfig saved = CloudConfigs.save(resolver, conf, name, form.title, form.connector, form.settings);
                send(response, 200, json(saved));
            } else if ("delete".equals(op)) {
                String path = str(body, "path");
                if (path == null || CloudConfigs.read(resolver.getResource(path)) == null) {
                    send(response, 404, error("No translation cloud configuration at " + path));
                    return;
                }
                CloudConfigs.delete(resolver, path);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("deleted", path);
                send(response, 200, m);
            } else if ("test".equals(op)) {
                CloudConfig config;
                if (str(body, "path") != null) {
                    config = CloudConfigs.read(resolver.getResource(str(body, "path")));
                    if (config == null) {
                        send(response, 404, error("No translation cloud configuration at " + str(body, "path")));
                        return;
                    }
                } else {
                    Object c = body.get("config");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cm = c instanceof Map ? (Map<String, Object>) c : new LinkedHashMap<>();
                    Form form = Form.of(cm, connectorNames(), secrets);
                    form.errors.removeIf(e -> "name".equals(e.get("field")) || "title".equals(e.get("field")));
                    if (!form.errors.isEmpty()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("errors", form.errors);
                        send(response, 422, m);
                        return;
                    }
                    config = new CloudConfig(null, form.title == null ? "Test" : form.title, form.connector, form.settings);
                }
                send(response, 200, translator.test(config));
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (PersistenceException e) {
            resolver.revert();
            send(response, 500, error("Could not save: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------- the form

    /** A configuration as the UI sends it, checked. Plain API keys are refused (and never echoed). */
    static final class Form {
        String title;
        String name;
        String connector;
        final Map<String, Object> settings = new LinkedHashMap<>();
        final List<Map<String, Object>> errors = new ArrayList<>();

        void error(String field, String message) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("field", field);
            e.put("message", message);
            errors.add(e);
        }

        static Form of(Map<String, Object> body, List<String> connectors) {
            return of(body, connectors, null);
        }

        /** With {@code secrets}, keys typed in the console are encrypted (without it they are refused). */
        static Form of(Map<String, Object> body, List<String> connectors, Secrets secrets) {
            Form f = new Form();
            f.title = trim(str(body, "title"));
            if (f.title == null) f.error("title", "A title is required.");
            String name = trim(str(body, "name"));
            f.name = name != null ? name : slug(f.title);
            if (f.name == null || !f.name.matches("[a-z0-9][a-z0-9_-]*")) f.error("name", "Use lower-case letters, digits, '-' and '_'.");
            f.connector = trim(str(body, "connector"));
            if (f.connector == null) f.connector = AiTranslationServiceFactory.NAME;
            if (!connectors.contains(f.connector)) f.error("connector", "No translation connector " + f.connector + " on this instance.");

            Object s = body.get("settings");
            @SuppressWarnings("unchecked")
            Map<String, Object> in = s instanceof Map ? (Map<String, Object>) s : new LinkedHashMap<>();
            String api = trim(str(in, "api"));
            if (api != null) {
                api = api.toLowerCase(Locale.ROOT);
                if (!api.equals("anthropic") && !api.equals("openai") && !api.equals("openrouter")) {
                    f.error("settings.api", "Anthropic, OpenAI-compatible or OpenRouter.");
                }
                f.settings.put("api", api);
            }
            String endpoint = trim(str(in, "endpoint"));
            if (endpoint != null) {
                if (!endpoint.matches("https?://[^\\s/]+(/\\S*)?")) f.error("settings.endpoint", "An http(s) URL, e.g. http://localhost:11434/v1/chat/completions");
                f.settings.put("endpoint", endpoint);
            }
            String model = trim(str(in, "model"));
            if (model != null) f.settings.put("model", model);
            String key = trim(str(in, "apiKey"));
            // a key typed in the console: stored encrypted with this instance's master key, never as it is
            String typed = str(in, "apiKeyValue");
            if (typed != null && !typed.trim().isEmpty()) {
                String t = typed.trim();
                if (t.length() > 4096 || t.chars().anyMatch(Character::isWhitespace)) {
                    f.error("settings.apiKeyValue", "Not an API key: one word, without spaces.");
                } else if (Secrets.isReference(t)) {
                    f.error("settings.apiKeyValue", "That is a reference: enter it as the key's reference instead.");
                } else if (secrets == null) {
                    f.error("settings.apiKeyValue", "Keys cannot be encrypted here: use a reference.");
                } else {
                    f.settings.put("apiKey", secrets.encrypt(t));
                }
            } else if (key != null) {
                if (Secrets.isReference(key)) f.settings.put("apiKey", key);
                else f.error("settings.apiKey", "Keys are not stored in the repository as they are. Enter the key itself (it is stored encrypted), "
                        + "or the reference of a \"Sensible Studio Secret\" or environment variable, e.g. $[secret:ANTHROPIC_API_KEY].");
            }
            for (String n : new String[]{"maxTokens", "maxChars", "timeoutSeconds"}) {
                Object v = in.get(n);
                if (v == null || String.valueOf(v).trim().isEmpty()) continue;
                try {
                    long l = v instanceof Number ? ((Number) v).longValue() : Long.parseLong(String.valueOf(v).trim());
                    if (l <= 0) throw new NumberFormatException();
                    f.settings.put(n, l);
                } catch (NumberFormatException e) {
                    f.error("settings." + n, "A positive whole number.");
                }
            }
            String instructions = str(in, "instructions");
            if (instructions != null && !instructions.trim().isEmpty()) f.settings.put("instructions", instructions.trim());
            Object terms = in.get("doNotTranslate");
            List<String> list = new ArrayList<>();
            if (terms instanceof List) {
                for (Object t : (List<?>) terms) if (t != null && !String.valueOf(t).trim().isEmpty()) list.add(String.valueOf(t).trim());
            }
            if (!list.isEmpty()) f.settings.put("doNotTranslate", list.toArray(new String[0]));
            String preset = trim(str(in, "preset"));
            if (preset != null) f.settings.put("preset", preset);
            List<String> headers = new ArrayList<>();
            Object hs = in.get("headers");
            if (hs instanceof List) {
                for (Object h : (List<?>) hs) {
                    String line = null;
                    if (h instanceof Map) {
                        Object n = ((Map<?, ?>) h).get("name");
                        Object v = ((Map<?, ?>) h).get("value");
                        if ((n == null || String.valueOf(n).trim().isEmpty()) && (v == null || String.valueOf(v).trim().isEmpty())) continue;
                        line = (n == null ? "" : String.valueOf(n).trim()) + ": " + (v == null ? "" : String.valueOf(v).trim());
                    } else if (h != null && !String.valueOf(h).trim().isEmpty()) {
                        line = String.valueOf(h).trim();
                    }
                    if (line == null) continue;
                    String problem = Headers.problem(line, false);
                    if (problem != null) {
                        f.error("settings.headers." + headers.size(), problem);
                        String[] nv = Headers.split(line);
                        // a refused credential is never kept or echoed
                        line = nv == null ? "" : nv[0] + ": ";
                    }
                    headers.add(line);
                }
            }
            if (!headers.isEmpty()) f.settings.put("headers", headers.toArray(new String[0]));
            return f;
        }

        static String slug(String title) {
            if (title == null) return null;
            String s = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
            return s.isEmpty() ? null : s;
        }
    }

    // ------------------------------------------------------------------------------------------- json

    Map<String, Object> json(CloudConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", c.getPath());
        m.put("conf", c.getConf());
        m.put("name", c.getPath().substring(c.getPath().lastIndexOf('/') + 1));
        m.put("title", c.getTitle());
        m.put("connector", c.getConnector());
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String k : CloudConfigs.SETTINGS.keySet()) {
            Object v = c.getProperties().get(k);
            if (v == null) continue;
            if ("apiKey".equals(k) && !Secrets.isReference(String.valueOf(v))) continue; // never answer a literal key
            settings.put(k, v instanceof Object[] ? java.util.Arrays.asList((Object[]) v) : v);
        }
        m.put("settings", settings);
        m.put("key", keyStatus(c));
        Object modified = c.getProperties().get("lastModified");
        if (modified instanceof Calendar) m.put("lastModified", ((Calendar) modified).getTimeInMillis());
        if (c.getProperties().get("lastModifiedBy") != null) m.put("lastModifiedBy", String.valueOf(c.getProperties().get("lastModifiedBy")));
        return m;
    }

    /** Whether the API key resolves here: {@code status} found, missing, plain, notNeeded; {@code name} of the secret or variable. */
    Map<String, Object> keyStatus(CloudConfig c) {
        Secrets s = secrets;
        Map<String, Object> m = new LinkedHashMap<>();
        LlmClient.Kind kind = AiTranslationServiceFactory.kindOf(c.get("api"));
        boolean local = kind == LlmClient.Kind.OPENAI && AiTranslationServiceFactory.isLocal(c.get("endpoint")); // local servers need no key
        String key = c.get("apiKey");
        String envName = AiTranslationServiceFactory.defaultKeyVariable(kind);
        if (!AiTranslationServiceFactory.NAME.equals(c.getConnector())) {
            m.put("status", "notNeeded");
            return m;
        }
        String[] headers = c.getArray("headers");
        List<String> missingInHeaders = new ArrayList<>();
        Headers.resolve(headers, s, missingInHeaders);
        if (!missingInHeaders.isEmpty()) {
            m.put("status", "missing");
            m.put("name", referenceName(missingInHeaders.get(0)));
            m.put("header", true);
            return m;
        }
        if ((key == null || key.trim().isEmpty()) && Headers.hasAuth(headers)) {
            List<String> refs = Headers.references(headers);
            m.put("status", "found");
            m.put("header", true);
            if (!refs.isEmpty()) m.put("name", referenceName(refs.get(0)));
            return m;
        }
        switch (s.status(key)) {
            case PLAIN:
                m.put("status", "plain");
                break;
            case FOUND:
                m.put("status", "found");
                if (Secrets.isEncrypted(key)) m.put("encrypted", true);
                else m.put("name", referenceName(key));
                break;
            case MISSING:
                m.put("status", local ? "notNeeded" : "missing");
                if (Secrets.isEncrypted(key)) {
                    // encrypted with another master key: another instance, or the key file was lost
                    m.put("encrypted", true);
                    m.put("unreadable", true);
                } else {
                    m.put("name", referenceName(key));
                }
                break;
            default:
                m.put("default", true);
                m.put("name", envName);
                m.put("status", s.env(envName) != null ? "found" : local ? "notNeeded" : "missing");
        }
        return m;
    }


    private static String referenceName(String reference) {
        Matcher m = Secrets.REFERENCE.matcher(reference.trim());
        return m.matches() ? m.group(2) : null;
    }

    private List<Object> connectors() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> p : translator.providers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.get("name"));
            m.put("label", AiTranslationServiceFactory.NAME.equals(p.get("name")) ? "AI (Claude, OpenRouter, OpenAI-compatible or local model)" : p.get("label"));
            out.add(m);
        }
        return out;
    }

    private List<String> connectorNames() {
        List<String> out = new ArrayList<>();
        for (TranslationServiceFactory f : factories) out.add(f.getServiceFactoryName());
        return out;
    }

    // ------------------------------------------------------------------------------------------- helpers

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String readBody(SlingHttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 1_000_000) throw new IOException("Request too large");
            }
        }
        return sb.toString();
    }
}
