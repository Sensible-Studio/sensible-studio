package io.sensible.studio.translation.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import javax.servlet.Servlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Language copies of pages (Sites → Language Copies), with the user's session.
 * <pre>
 * GET  /bin/studio/pagetranslation.json?op=languages&amp;path=&lt;page&gt;      language, copies and their state
 * GET  /bin/studio/pagetranslation.json?op=pages&amp;path=&lt;page&gt;&amp;deep=true  the pages a translation covers, parents first
 * POST /bin/studio/pagetranslation  {"op":"translate", "path" (a page, or a DAM asset: its metadata), "languages":["fr"], "provider", "force", "batch":[source pages],
 *                                 "includeReferences", "skipReferences":[sources translated by earlier requests]}
 *                                {"op":"approve", "path":&lt;copy&gt;}  (its texts go to the translation memory as approved)
 *                                {"op":"approveAll", "paths":[copies]}  {approved, skipped [{path, reason}], failed [{path, error}]}
 *                                {"op":"saveReview", "path", "translations":{id: text}}   422 {errors:{id: message}} when markup breaks
 *                                {"op":"retranslate", "path", "ids":[...], "provider"?}
 * GET  /bin/studio/pagetranslation.json?op=review&amp;path=&lt;copy&gt;      the copy's texts next to the source's
 * GET  /bin/studio/pagetranslation.json?op=queue[&amp;language=&amp;under=&amp;status=review|outdated|failed|approved&amp;kind=]
 * </pre>
 * The UI translates a tree one page at a time (op=pages, then translate each with the whole list as {@code batch}).
 * Connector errors answer 502 with their code (e.g. MISSING_CREDENTIALS).
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/pagetranslation",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class PageTranslationServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    transient volatile Translator translator;

    @Reference
    transient volatile PageTranslationRules rules;

    @Reference
    transient volatile ResourceResolverFactory resolverFactory;

    /** The records' service session (studio-translation-service); null before repoinit created the user (then the user's own). */
    private ResourceResolver serviceResolver() {
        try {
            return resolverFactory.getServiceResourceResolver(java.util.Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "translation"));
        } catch (LoginException | RuntimeException e) {
            return null;
        }
    }

    /** Translators of referenced content that is not a page (content fragments: studio-cfm). */
    private final transient List<ReferenceTranslator> referenceTranslators = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Reference(service = ReferenceTranslator.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.add(t);
    }

    void unbindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.remove(t);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver service = serviceResolver();
        PageTranslation pt = new PageTranslation(request.getResourceResolver(), translator, rules).records(service);
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        try {
            if ("languages".equals(op)) {
                send(response, 200, pt.languages(path));
            } else if ("review".equals(op)) {
                send(response, 200, pt.review(path));
            } else if ("queue".equals(op)) {
                String language = blank(request.getParameter("language"));
                String under = blank(request.getParameter("under"));
                String status = blank(request.getParameter("status"));
                String kind = blank(request.getParameter("kind"));
                List<Map<String, Object>> items = new ArrayList<>();
                if (kind == null || !"content-fragment".equals(kind)) items.addAll(pt.queue(language, under, status, 500));
                if (kind == null || "content-fragment".equals(kind)) items.addAll(pt.fragmentQueue(language, under, status, 500));
                items.sort((a, b) -> Long.compare(((Number) b.getOrDefault("translatedAt", 0L)).longValue(), ((Number) a.getOrDefault("translatedAt", 0L)).longValue()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("items", items);
                send(response, 200, m);
            } else if ("pages".equals(op)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pages", pt.pages(path, "true".equals(request.getParameter("deep"))));
                send(response, 200, m);
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (IllegalArgumentException e) {
            send(response, 404, error(e.getMessage()));
        } finally {
            if (service != null) service.close();
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
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
        ResourceResolver service = serviceResolver();
        PageTranslation pt = new PageTranslation(request.getResourceResolver(), translator, rules, referenceTranslators)
                .skipReferences(list(body.get("skipReferences"))).records(service);
        String op = str(body, "op");
        try {
            if ("translate".equals(op)) {
                List<String> languages = list(body.get("languages"));
                if (languages.isEmpty()) throw new IllegalArgumentException("Choose a language");
                List<Object> results = new ArrayList<>();
                for (String language : languages) {
                    if (PageTranslation.isAsset(request.getResourceResolver().getResource(String.valueOf(str(body, "path"))))) {
                        results.add(pt.translateAsset(str(body, "path"), language, str(body, "provider"), Boolean.TRUE.equals(body.get("force"))));
                        continue;
                    }
                    results.addAll(pt.translate(str(body, "path"), language, str(body, "provider"), Boolean.TRUE.equals(body.get("force")),
                            list(body.get("batch")), Boolean.TRUE.equals(body.get("includeReferences"))));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("results", results);
                send(response, 200, m);
            } else if ("saveReview".equals(op)) {
                Map<String, String> translations = new LinkedHashMap<>();
                Object t = body.get("translations");
                if (t instanceof Map) for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) translations.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
                pt.saveReview(str(body, "path"), translations);
                send(response, 200, pt.review(str(body, "path")));
            } else if ("retranslate".equals(op)) {
                send(response, 200, pt.retranslate(str(body, "path"), list(body.get("ids")), str(body, "provider")));
            } else if ("approveAll".equals(op)) {
                send(response, 200, approveAll(request.getResourceResolver(), pt, list(body.get("paths"))));
            } else if ("approve".equals(op)) {
                pt.approve(str(body, "path"));
                boolean asset = PageTranslation.isAsset(request.getResourceResolver().getResource(String.valueOf(str(body, "path"))));
                send(response, 200, asset ? pt.review(str(body, "path")) : pt.languages(str(body, "path")));
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (PageTranslation.ReviewException e) {
            request.getResourceResolver().revert();
            Map<String, Object> m = error(e.getMessage());
            m.put("errors", e.errors);
            send(response, 422, m);
        } catch (IllegalArgumentException e) {
            request.getResourceResolver().revert();
            // a matter of rights is a 403, as everywhere; what is wrong with the request stays a 422
            send(response, String.valueOf(e.getMessage()).startsWith("You may not change ") ? 403 : 422, error(e.getMessage()));
        } catch (TranslationException e) {
            request.getResourceResolver().revert();
            Map<String, Object> m = error(e.getMessage());
            m.put("code", e.getErrorCode() == null ? "GENERAL" : e.getErrorCode().name());
            send(response, 502, m);
        } catch (PersistenceException e) {
            request.getResourceResolver().revert();
            if (denied(e)) {
                send(response, 403, error("You are not allowed to do that here"));
                return;
            }
            send(response, 500, error("Could not save: " + e.getMessage()));
        } finally {
            if (service != null) service.close();
        }
    }

    /**
     * Approves many copies at once (Translation Review → Approve): pages and assets through their records, content
     * fragments through their translator. Copies with untranslated texts, outdated ones (the source changed) and
     * approved ones are left as they are, with the reason. Results: approved, skipped [{path, reason}], failed [{path, error}].
     */
    Map<String, Object> approveAll(org.apache.sling.api.resource.ResourceResolver rr, PageTranslation pt, List<String> paths) {
        if (paths.isEmpty()) throw new IllegalArgumentException("Choose what to approve");
        if (paths.size() > 1000) throw new IllegalArgumentException("At most 1000 at once");
        List<String> approved = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String path : paths) {
            try {
                Map<String, Object> rec = pt.record(path);
                if (rec != null) {
                    String state = PageTranslation.state(rec);
                    String why = "approved".equals(state) ? "Approved already" : "failed".equals(state) ? "Some texts are not translated"
                            : "outdated".equals(state) ? "The source changed since it was translated" : null;
                    if (why != null) {
                        skipped.add(entry(path, "reason", why));
                        continue;
                    }
                    pt.approve(path);
                    approved.add(path);
                    continue;
                }
                org.apache.sling.api.resource.Resource t = rr.getResource(path + "/jcr:content/translation");
                if (t != null) {
                    org.apache.sling.api.resource.ValueMap tv = t.getValueMap();
                    if ("APPROVED".equals(tv.get("status", String.class))) {
                        skipped.add(entry(path, "reason", "Approved already"));
                        continue;
                    }
                    if (tv.get("failed", new String[0]).length > 0) {
                        skipped.add(entry(path, "reason", "Some texts are not translated"));
                        continue;
                    }
                }
                boolean done = false;
                for (ReferenceTranslator r : referenceTranslators) {
                    if (r.approve(rr, path, rr.getUserID())) {
                        done = true;
                        break;
                    }
                }
                if (done) approved.add(path);
                else skipped.add(entry(path, "reason", "Not a copy Sensible Studio translated"));
            } catch (Exception e) {
                rr.revert();
                failed.add(entry(path, "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approved", approved);
        out.put("skipped", skipped);
        out.put("failed", failed);
        return out;
    }

    private static Map<String, Object> entry(String path, String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put(key, value);
        return m;
    }

    private static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null && !String.valueOf(x).trim().isEmpty()) out.add(String.valueOf(x).trim());
        return out;
    }

    private static String blank(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null || String.valueOf(v).trim().isEmpty() ? null : String.valueOf(v).trim();
    }

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
    /** The repository refused for lack of rights (also when wrapped): a 403 with words, not Oak's. */
    static boolean denied(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (t instanceof javax.jcr.AccessDeniedException || m.contains("OakAccess0000") || m.contains("Access denied")) {
                return true;
            }
        }
        return false;
    }
}
