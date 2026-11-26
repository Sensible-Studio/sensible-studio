/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.translation.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The Translation Configuration console's server side (AEM's Tools › General › Translation Configuration), at
 * {@code /bin/studio/translation/rules.json}:
 * <ul>
 *   <li>{@code GET}: every context with its rules (see {@link PageTranslationRules});</li>
 *   <li>{@code GET op=preview&path=<page>}: the context that applies to a page and the texts its rules take from it;</li>
 *   <li>{@code POST} JSON {@code {"op":"save","rules":{...}}} stores a context's rules (with {@code "previous"}: the path it
 *       had, when it moves); {@code {"op":"delete","path":...}} removes one (for /content: back to the built-in rules).</li>
 * </ul>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/translation/rules",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class TranslationRulesServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    /** Texts a preview lists at most. */
    static final int MAX_PREVIEW = 500;

    @Reference
    transient volatile PageTranslationRules rules;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        if ("preview".equals(request.getParameter("op"))) {
            String path = request.getParameter("path");
            Resource page = path == null ? null : resolver.getResource(path);
            Resource content = page == null ? null : page.getChild("jcr:content");
            if (content == null) {
                send(response, 404, error("No page at " + path));
                return;
            }
            send(response, 200, preview(resolver, rules, content));
            return;
        }
        List<Object> contexts = new ArrayList<>();
        for (PageTranslationRules.Rules r : rules.contexts(resolver)) contexts.add(r.toJson());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contexts", contexts);
        out.put("store", PageTranslationRules.STORE);
        send(response, 200, out);
    }

    static Map<String, Object> preview(ResourceResolver resolver, PageTranslationRules rules, Resource content) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("context", rules.at(resolver, content.getPath()).path);
        List<Object> texts = new ArrayList<>();
        List<PageTranslation.Seg> segs = new PageTranslation(resolver, null, rules).segments(content);
        for (PageTranslation.Seg s : segs) {
            if (texts.size() >= MAX_PREVIEW) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node", s.node);
            m.put("property", s.prop);
            m.put("label", s.label);
            m.put("text", s.text.length() > 300 ? s.text.substring(0, 300) + "…" : s.text);
            m.put("html", s.html);
            texts.add(m);
        }
        out.put("texts", texts);
        out.put("total", segs.size());
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver resolver = request.getResourceResolver();
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(readBody(request));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("A JSON object is expected");
            body = (Map<String, Object>) parsed;
        } catch (IllegalArgumentException e) {
            send(response, 400, error("Bad request: " + e.getMessage()));
            return;
        }
        String op = body.get("op") == null ? null : String.valueOf(body.get("op"));
        try {
            if ("save".equals(op)) {
                if (!(body.get("rules") instanceof Map)) throw new IllegalArgumentException("rules are missing");
                PageTranslationRules.Rules r = PageTranslationRules.Rules.fromJson((Map<String, Object>) body.get("rules"));
                String previous = body.get("previous") == null ? null : String.valueOf(body.get("previous"));
                boolean taken = rules.contexts(resolver).stream().anyMatch(c -> !c.builtIn && c.path.equals(r.path));
                if (taken && (previous == null || !previous.equals(r.path))) {
                    throw new IllegalArgumentException("There is a context for " + r.path + " already");
                }
                PageTranslationRules.Rules saved = rules.save(resolver, r);
                if (previous != null && !previous.equals(r.path)) rules.delete(resolver, previous);
                resolver.commit();
                send(response, 200, saved.toJson());
            } else if ("delete".equals(op)) {
                String path = body.get("path") == null ? null : String.valueOf(body.get("path"));
                if (path == null || !rules.delete(resolver, path)) {
                    send(response, 404, error("No context " + path + " to remove"));
                    return;
                }
                resolver.commit();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("deleted", path);
                send(response, 200, out);
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (IllegalArgumentException e) {
            resolver.revert();
            send(response, 400, error(e.getMessage()));
        } catch (PersistenceException e) {
            resolver.revert();
            send(response, 403, error("Could not save the rules: " + e.getMessage()));
        }
    }

    private static String readBody(SlingHttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            char[] buf = new char[4096];
            for (int n; (n = reader.read(buf)) > 0; ) {
                sb.append(buf, 0, n);
                if (sb.length() > 1_000_000) throw new IOException("Request too large");
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

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}
