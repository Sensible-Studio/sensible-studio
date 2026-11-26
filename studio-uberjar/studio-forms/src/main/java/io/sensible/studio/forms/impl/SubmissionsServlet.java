/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The Submissions view's API, {@code /bin/studio/forms/submissions.json}, for signed-in authors:
 * <ul>
 *   <li>{@code ?form=<page>}: the form's fields (the columns) and its submissions, newest first
 *       ({@code offset}, {@code limit} up to 500, {@code q} filters on any value);</li>
 *   <li>{@code ?form=<page>&op=count}: how many, and how many unseen;</li>
 *   <li>{@code ?under=<folder or page>&op=counts}: the same per form, for every form below (the console's badges);</li>
 *   <li>{@code ?op=file&path=<file node>}: an uploaded file, as a download;</li>
 *   <li>{@code ?op=get&path=<submission>}: one submission in full (marks it seen);</li>
 *   <li>{@code ?form=<page>&op=export&format=csv|json}: everything, as a download;</li>
 *   <li>{@code POST op=delete&path=…} (repeatable): removes submissions. Needs {@code X-Studio-CSRF: 1}.</li>
 * </ul>
 * Reads and writes go through the forms service user; the caller only needs to be signed in and allowed
 * to read the form page.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/forms/submissions",
                "sling.servlet.extensions=json",
                "sling.servlet.extensions=csv",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class SubmissionsServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String CSRF_HEADER = "X-Studio-CSRF";
    static final int MAX_LIMIT = 500;

    @Reference
    private transient SubmissionStore store;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!signedIn(request, response)) {
            return;
        }
        String op = param(request, "op", "list");
        String form = param(request, "form", null);
        try (ResourceResolver resolver = store.serviceResolver()) {
            if ("file".equals(op)) {
                Resource file = resolver.getResource(param(request, "path", ""));
                Resource content = file == null ? null : file.getChild("jcr:content");
                Resource submission = file == null ? null : file.getParent() == null ? null : file.getParent().getParent() == null ? null : file.getParent().getParent().getParent();
                if (content == null || submission == null || !submission.isResourceType(SubmissionStore.RESOURCE_TYPE)
                        || request.getResourceResolver().getResource(submission.getValueMap().get("form", "")) == null) {
                    SubmitServlet.send(response, 404, SubmitServlet.error("No file there"));
                    return;
                }
                try (java.io.InputStream in = content.adaptTo(java.io.InputStream.class)) {
                    if (in == null) {
                        SubmitServlet.send(response, 404, SubmitServlet.error("No file there"));
                        return;
                    }
                    response.setContentType(content.getValueMap().get("jcr:mimeType", "application/octet-stream"));
                    response.setHeader("Content-Disposition", ("true".equals(param(request, "download", "false")) ? "attachment" : "inline")
                            + "; filename=\"" + file.getName().replace("\"", "") + "\"");
                    response.setHeader("X-Content-Type-Options", "nosniff");
                    byte[] buf = new byte[65536];
                    java.io.OutputStream out = response.getOutputStream();
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                    }
                }
                return;
            }
            if ("counts".equals(op)) {
                String under = param(request, "under", SubmissionStore.FORMS_ROOT);
                if (!under.startsWith(SubmissionStore.FORMS_ROOT) || request.getResourceResolver().getResource(under) == null) {
                    SubmitServlet.send(response, 404, SubmitServlet.error("Nothing at " + under));
                    return;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, int[]> e : store.countsUnder(resolver, under).entrySet()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("total", e.getValue()[0]);
                    c.put("unseen", e.getValue()[1]);
                    out.put(e.getKey(), c);
                }
                json(response, out);
                return;
            }
            if ("get".equals(op)) {
                Resource r = resolver.getResource(param(request, "path", ""));
                if (r == null || !r.isResourceType(SubmissionStore.RESOURCE_TYPE)) {
                    SubmitServlet.send(response, 404, SubmitServlet.error("No submission there"));
                    return;
                }
                store.markSeen(r);
                json(response, SubmissionStore.toJson(r, true));
                return;
            }
            if (form == null || request.getResourceResolver().getResource(form) == null) {
                SubmitServlet.send(response, 404, SubmitServlet.error("No form at " + form));
                return;
            }
            FormModel model = FormModel.of(resolver.getResource(form));
            List<Resource> all = store.list(resolver, form);
            switch (op) {
                case "count": {
                    int unseen = 0;
                    for (Resource r : all) {
                        if (!r.getValueMap().get("seen", false)) {
                            unseen++;
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("total", all.size());
                    out.put("unseen", unseen);
                    json(response, out);
                    return;
                }
                case "export": {
                    boolean csv = "csv".equals(param(request, "format", "csv"));
                    String name = form.substring(form.lastIndexOf('/') + 1) + "-submissions." + (csv ? "csv" : "json");
                    response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
                    if (csv) {
                        response.setContentType("text/csv");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write(csv(model, all));
                    } else {
                        List<Object> items = new ArrayList<>();
                        for (Resource r : all) {
                            items.add(SubmissionStore.toJson(r, true));
                        }
                        json(response, items);
                    }
                    return;
                }
                default: {
                    String q = param(request, "q", "").trim().toLowerCase();
                    int offset = Math.max(0, intParam(request, "offset", 0));
                    int limit = Math.max(1, Math.min(MAX_LIMIT, intParam(request, "limit", 100)));
                    List<Object> items = new ArrayList<>();
                    int matched = 0;
                    for (Resource r : all) {
                        Map<String, Object> j = SubmissionStore.toJson(r, false);
                        if (!q.isEmpty() && !matches(j, q)) {
                            continue;
                        }
                        if (matched++ < offset) {
                            continue;
                        }
                        if (items.size() < limit) {
                            items.add(j);
                        }
                    }
                    List<Object> fields = new ArrayList<>();
                    if (model != null) {
                        for (FormModel.Field f : model.fields) {
                            fields.add(f.toJson());
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("form", form);
                    out.put("title", model == null ? form : model.title);
                    out.put("fields", fields);
                    out.put("total", matched);
                    out.put("items", items);
                    json(response, out);
                }
            }
        } catch (Exception e) {
            SubmitServlet.send(response, 500, SubmitServlet.error(e.getMessage()));
        }
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!signedIn(request, response)) {
            return;
        }
        if (!"1".equals(request.getHeader(CSRF_HEADER))) {
            SubmitServlet.send(response, 403, SubmitServlet.error("missing " + CSRF_HEADER + " header"));
            return;
        }
        String op = param(request, "op", "");
        if (!"delete".equals(op)) {
            SubmitServlet.send(response, 400, SubmitServlet.error("Unknown op " + op));
            return;
        }
        String[] paths = request.getParameterValues("path");
        try (ResourceResolver resolver = store.serviceResolver()) {
            int n = 0;
            for (String p : paths == null ? new String[0] : paths) {
                Resource r = resolver.getResource(p);
                if (r != null && r.isResourceType(SubmissionStore.RESOURCE_TYPE)) {
                    String form = r.getValueMap().get("form", "");
                    // only submissions of forms the author may read
                    if (request.getResourceResolver().getResource(form) != null) {
                        resolver.delete(r);
                        n++;
                    }
                }
            }
            resolver.commit();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("deleted", n);
            json(response, out);
        } catch (Exception e) {
            SubmitServlet.send(response, 500, SubmitServlet.error(e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean signedIn(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String user = request.getResourceResolver().getUserID();
        if (user == null || "anonymous".equals(user)) {
            SubmitServlet.send(response, 401, SubmitServlet.error("Sign in to see submissions"));
            return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static boolean matches(Map<String, Object> j, String q) {
        Map<String, Object> values = (Map<String, Object>) j.get("values");
        for (Object v : values.values()) {
            if (v != null && String.valueOf(v).toLowerCase().contains(q)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    static String csv(FormModel model, List<Resource> all) {
        StringBuilder sb = new StringBuilder();
        List<String> columns = new ArrayList<>();
        columns.add("submittedAt");
        if (model != null) {
            for (FormModel.Field f : model.fields) {
                columns.add(f.name);
            }
        }
        // values the form no longer asks for still count
        for (Resource r : all) {
            Map<String, Object> values = (Map<String, Object>) SubmissionStore.toJson(r, false).get("values");
            for (String k : values.keySet()) {
                if (!columns.contains(k)) {
                    columns.add(k);
                }
            }
        }
        sb.append(String.join(",", escapeAll(columns))).append("\r\n");
        for (Resource r : all) {
            Map<String, Object> j = SubmissionStore.toJson(r, false);
            Map<String, Object> values = (Map<String, Object>) j.get("values");
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(j.get("submittedAt")));
            for (int i = 1; i < columns.size(); i++) {
                Object v = values.get(columns.get(i));
                row.add(v == null ? "" : String.valueOf(v));
            }
            sb.append(String.join(",", escapeAll(row))).append("\r\n");
        }
        return sb.toString();
    }

    private static List<String> escapeAll(List<String> cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            String s = c == null ? "" : c;
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
                s = "\"" + s.replace("\"", "\"\"") + "\"";
            }
            out.add(s);
        }
        return out;
    }

    static String param(SlingHttpServletRequest request, String name, String dflt) {
        String v = request.getParameter(name);
        return v == null || v.isEmpty() ? dflt : v;
    }

    static int intParam(SlingHttpServletRequest request, String name, int dflt) {
        try {
            return Integer.parseInt(param(request, name, String.valueOf(dflt)));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static void json(SlingHttpServletResponse response, Object body) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.getWriter().write(Json.write(body));
    }
}
