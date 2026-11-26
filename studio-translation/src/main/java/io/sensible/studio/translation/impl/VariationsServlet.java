/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.translation.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import com.adobe.granite.translation.api.TranslationException;
import com.adobe.granite.translation.api.TranslationServiceFactory;

import io.sensible.studio.translation.api.CloudConfig;

/**
 * Generate Variations' server side, at {@code /bin/studio/variations.json}:
 * <pre>
 * GET  op=setup                     prompt templates, tones, the models one may use (translation providers of the AI connector)
 * GET  op=history                   my generations, newest first (without their variations)
 * GET  op=generation&amp;id=           one of them, whole
 * POST {"op":"generate", "provider", "template", "request", "audience", "tone", "language", "page", "count", "creativity"}
 * POST {"op":"favorite", "id", "index", "on"}     {"op":"delete", "id"}
 * </pre>
 * A person's generations are kept for them alone at {@code /var/studio/translation/variations/<user>} (the translation
 * service user writes them; each request reaches only its own user's folder).
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/variations",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class VariationsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String STORE = "/var/studio/translation/variations";
    /** Generations kept per person; older ones go. */
    static final int MAX_HISTORY = 100;

    /** Writes the answer of the model of a provider; replaced in tests. */
    interface Generator {
        /** {text, engine, truncated ("true"/"false")} */
        String[] generate(CloudConfig provider, String system, String user, double temperature) throws TranslationException;
    }

    @Reference(target = "(translationFactory=" + AiTranslationServiceFactory.NAME + ")", cardinality = ReferenceCardinality.OPTIONAL,
            policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    transient volatile TranslationServiceFactory factory;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    transient volatile PageTranslationRules rules;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    transient volatile ResourceResolverFactory resolverFactory;

    transient Generator generator = (provider, system, user, temperature) -> {
        TranslationServiceFactory f = factory;
        if (!(f instanceof AiTranslationServiceFactory)) {
            throw new TranslationException("The AI connector is not running", TranslationException.ErrorCode.NO_ENGINE);
        }
        AiTranslationServiceFactory.Built b = ((AiTranslationServiceFactory) f).build(provider);
        LlmClient.Answer a = b.client.complete(system, user, temperature);
        return new String[] {a.text, b.engine, String.valueOf(a.truncated)};
    };

    // ------------------------------------------------------------------------------------ GET

    /** Who asks. */
    String user(SlingHttpServletRequest request) {
        return request.getResourceResolver().getUserID();
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String user = user(request);
        if (user == null || "anonymous".equals(user)) {
            send(response, 401, error("Sign in to generate variations"));
            return;
        }
        String op = request.getParameter("op");
        if ("setup".equals(op)) {
            send(response, 200, setup(resolver));
            return;
        }
        try (Store store = store(request)) {
            if ("history".equals(op)) {
                List<Object> items = new ArrayList<>();
                for (Resource r : history(store.resolver, user)) {
                    Map<String, Object> m = record(r, false);
                    items.add(m);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("items", items);
                send(response, 200, out);
            } else if ("generation".equals(op)) {
                Resource r = mine(store.resolver, user, request.getParameter("id"));
                if (r == null) {
                    send(response, 404, error("No such generation"));
                } else {
                    send(response, 200, record(r, true));
                }
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (LoginException e) {
            send(response, 503, error("The store of the generations cannot be reached"));
        }
    }

    static Map<String, Object> setup(ResourceResolver resolver) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> templates = new ArrayList<>();
        for (Variations.Template t : Variations.TEMPLATES) {
            templates.add(t.toJson());
        }
        out.put("templates", templates);
        out.put("tones", Variations.TONES);
        out.put("maxCount", Variations.MAX_COUNT);
        out.put("providers", providers(resolver));
        return out;
    }

    /** The translation providers of the AI connector the person can read: the models they may use. */
    static List<Object> providers(ResourceResolver resolver) {
        List<Object> out = new ArrayList<>();
        for (String conf : CloudConfigs.confs(resolver)) {
            for (CloudConfig c : CloudConfigs.list(resolver, conf)) {
                if (!AiTranslationServiceFactory.NAME.equals(c.getConnector())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", c.getPath());
                m.put("title", c.getTitle() == null ? c.getPath() : c.getTitle());
                String model = c.get("model");
                m.put("engine", AiTranslationServiceFactory.engineName(AiTranslationServiceFactory.kindOf(c.get("api")), c.get("endpoint"))
                        + (model == null || model.isEmpty() ? "" : " (" + model + ")"));
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ POST

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        String user = user(request);
        if (user == null || "anonymous".equals(user)) {
            send(response, 401, error("Sign in to generate variations"));
            return;
        }
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(readBody(request));
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("A JSON object is expected");
            }
            body = (Map<String, Object>) parsed;
        } catch (IllegalArgumentException e) {
            send(response, 400, error("Bad request: " + e.getMessage()));
            return;
        }
        String op = body.get("op") == null ? null : String.valueOf(body.get("op"));
        try (Store store = store(request)) {
            if ("generate".equals(op)) {
                generate(request, resolver, store.resolver, user, body, response);
            } else if ("favorite".equals(op)) {
                Resource r = mine(store.resolver, user, str(body.get("id")));
                if (r == null) {
                    send(response, 404, error("No such generation"));
                    return;
                }
                Object index = body.get("index");
                if (!(index instanceof Number)) {
                    send(response, 400, error("index is missing"));
                    return;
                }
                ModifiableValueMap v = r.adaptTo(ModifiableValueMap.class);
                Set<Long> favs = new LinkedHashSet<>();
                for (Long l : v.get("favorites", new Long[0])) {
                    favs.add(l);
                }
                long i = ((Number) index).longValue();
                if (Boolean.TRUE.equals(body.get("on"))) {
                    favs.add(i);
                } else {
                    favs.remove(i);
                }
                v.put("favorites", favs.toArray(new Long[0]));
                store.resolver.commit();
                send(response, 200, record(r, true));
            } else if ("delete".equals(op)) {
                Resource r = mine(store.resolver, user, str(body.get("id")));
                if (r == null) {
                    send(response, 404, error("No such generation"));
                    return;
                }
                store.resolver.delete(r);
                store.resolver.commit();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("deleted", str(body.get("id")));
                send(response, 200, out);
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (LoginException e) {
            send(response, 503, error("The store of the generations cannot be reached"));
        } catch (PersistenceException | RuntimeException e) {
            send(response, 500, error("Could not keep the generation: " + e.getMessage()));
        }
    }

    private void generate(SlingHttpServletRequest request, ResourceResolver resolver, ResourceResolver storeResolver, String user,
            Map<String, Object> body, SlingHttpServletResponse response) throws IOException, PersistenceException {
        Variations.Input in;
        Variations.Template t;
        try {
            in = Variations.Input.fromJson(body);
            t = Variations.template(in.template);
        } catch (IllegalArgumentException e) {
            send(response, 400, error(e.getMessage()));
            return;
        }
        // the model: a provider the person may read
        String providerPath = str(body.get("provider"));
        CloudConfig provider = null;
        for (Object p : providers(resolver)) {
            String path = String.valueOf(((Map<?, ?>) p).get("path"));
            if (providerPath == null || providerPath.equals(path)) {
                provider = CloudConfigs.read(resolver.getResource(path));
                break;
            }
        }
        if (provider == null) {
            send(response, 400, error(providerPath == null
                    ? "No model to write with: add a translation provider of the AI connector (Translations › Providers)"
                    : "No provider " + providerPath));
            return;
        }
        // the page, read as the person: what they may not read is not sent anywhere
        String pageText = null;
        if (in.page != null) {
            Resource page = resolver.getResource(in.page);
            Resource content = page == null ? null : page.getChild("jcr:content");
            if (content == null) {
                send(response, 400, error("No page at " + in.page));
                return;
            }
            pageText = pageText(resolver, content);
        }
        // where it is kept, before the model is asked: a store that fails must not cost a generation
        Resource folder = ensure(storeResolver, STORE + "/" + slug(user));
        String[] answer;
        try {
            answer = generator.generate(provider, Variations.system(t, in), Variations.user(in, pageText), in.creativity);
        } catch (TranslationException e) {
            send(response, 502, error(e.getMessage()));
            return;
        }
        List<Map<String, Object>> variations;
        try {
            variations = Variations.parse(t, answer[0]);
        } catch (IllegalStateException e) {
            send(response, 502, error("true".equals(answer[2]) ? "The answer was cut off: ask for fewer variations" : e.getMessage()));
            return;
        }
        String id = String.format(Locale.ROOT, "%d-%s", System.currentTimeMillis(), UUID.randomUUID().toString().substring(0, 8));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("user", user);
        props.put("created", Calendar.getInstance());
        props.put("template", t.id);
        props.put("input", Json.write(in.toJson()));
        props.put("variations", Json.write(variations));
        props.put("provider", provider.getPath());
        props.put("engine", answer[1]);
        Resource r = storeResolver.create(folder, id, props);
        trim(storeResolver, user);
        storeResolver.commit();
        send(response, 200, record(r, true));
    }

    /** The texts of a page, as translation takes them (its rules), up to {@link Variations#MAX_CONTEXT} characters. */
    String pageText(ResourceResolver resolver, Resource content) {
        StringBuilder b = new StringBuilder();
        PageTranslationRules r = rules;
        if (r != null) {
            for (PageTranslation.Seg s : new PageTranslation(resolver, null, r).segments(content)) {
                String text = s.html ? Variations.plain(s.text) : s.text.trim();
                if (!text.isEmpty()) {
                    b.append(text).append('\n');
                }
                if (b.length() > Variations.MAX_CONTEXT) {
                    break;
                }
            }
        } else {
            ValueMap v = content.getValueMap();
            b.append(v.get("jcr:title", "")).append('\n').append(v.get("jcr:description", ""));
        }
        String s = b.toString().trim();
        return s.length() > Variations.MAX_CONTEXT ? s.substring(0, Variations.MAX_CONTEXT) : s;
    }

    // ------------------------------------------------------------------------------------ store

    /** The resolver that keeps the generations: the translation service user's, or (tests) the request's. */
    private static final class Store implements AutoCloseable {
        final ResourceResolver resolver;
        final boolean own;

        Store(ResourceResolver resolver, boolean own) {
            this.resolver = resolver;
            this.own = own;
        }

        @Override
        public void close() {
            if (own) {
                resolver.close();
            }
        }
    }

    private Store store(SlingHttpServletRequest request) throws LoginException {
        ResourceResolverFactory f = resolverFactory;
        if (f == null) {
            return new Store(request.getResourceResolver(), false);
        }
        return new Store(f.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "translation")), true);
    }

    static String slug(String user) {
        String s = user.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        return s.isEmpty() ? "user" : s;
    }

    private static List<Resource> history(ResourceResolver store, String user) {
        List<Resource> out = new ArrayList<>();
        Resource folder = store.getResource(STORE + "/" + slug(user));
        if (folder == null) {
            return out;
        }
        for (Resource r : folder.getChildren()) {
            if (user.equals(r.getValueMap().get("user", String.class))) {
                out.add(r);
            }
        }
        out.sort((a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    /** One of the person's generations, or null. */
    private static Resource mine(ResourceResolver store, String user, String id) {
        if (id == null || !id.matches("[0-9]+-[0-9a-f]{8}")) {
            return null;
        }
        Resource r = store.getResource(STORE + "/" + slug(user) + "/" + id);
        return r != null && user.equals(r.getValueMap().get("user", String.class)) ? r : null;
    }

    /** Keeps the newest {@link #MAX_HISTORY}, and every one with a favorite. */
    private static void trim(ResourceResolver store, String user) throws PersistenceException {
        List<Resource> all = history(store, user);
        for (int i = MAX_HISTORY; i < all.size(); i++) {
            if (all.get(i).getValueMap().get("favorites", new Long[0]).length == 0) {
                store.delete(all.get(i));
            }
        }
    }

    private static Map<String, Object> record(Resource r, boolean whole) {
        ValueMap v = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getName());
        Calendar created = v.get("created", Calendar.class);
        if (created != null) {
            m.put("created", created.toInstant().toString());
        }
        m.put("template", v.get("template", String.class));
        m.put("engine", v.get("engine", String.class));
        m.put("provider", v.get("provider", String.class));
        Object input = parse(v.get("input", "{}"));
        m.put("input", input);
        List<Long> favs = new ArrayList<>();
        for (Long l : v.get("favorites", new Long[0])) {
            favs.add(l);
        }
        m.put("favorites", favs);
        Object variations = parse(v.get("variations", "[]"));
        m.put("count", variations instanceof List ? (long) ((List<?>) variations).size() : 0L);
        if (whole) {
            m.put("variations", variations);
        }
        return m;
    }

    private static Object parse(String json) {
        try {
            return Json.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Creates what is missing of {@code path}, from its nearest ancestor that exists (a service user may not read "/"). */
    static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            return r;
        }
        String parent = path.substring(0, path.lastIndexOf('/'));
        Resource p = parent.isEmpty() ? resolver.getResource("/") : ensure(resolver, parent);
        if (p == null) {
            throw new PersistenceException("Cannot reach " + parent);
        }
        return resolver.create(p, path.substring(path.lastIndexOf('/') + 1), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    // ------------------------------------------------------------------------------------ helpers

    private static String str(Object o) {
        return o == null || String.valueOf(o).trim().isEmpty() ? null : String.valueOf(o).trim();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static String readBody(SlingHttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            char[] buf = new char[4096];
            for (int n; (n = reader.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
                if (sb.length() > 200_000) {
                    throw new IOException("Request too large");
                }
            }
        }
        return sb.toString();
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(Json.write(body));
    }
}
