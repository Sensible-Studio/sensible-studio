/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.Agent;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationOptions;
import com.day.cq.replication.Replicator;

/**
 * Where a form posts: {@code POST /bin/studio/forms/submit} with a JSON body
 * {@code {"form": "/content/forms/wknd/contact/jcr:content/root", "data": {...}}} (or the same as form
 * parameters: {@code form=…&name=…}). The form's own fields decide what is accepted; the honeypot
 * ({@code studio:website}) drops bots; a visitor may send a form at most once every two seconds.
 * The submission is stored with the forms service user, then handed to the author when a publishing
 * target named {@code author} exists on this instance (the reverse target of a Live instance).
 * {@code preview=true} validates without storing (the editor's Preview).
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/forms/submit",
                "sling.servlet.methods=POST"
        })
public class SubmitServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(SubmitServlet.class);

    static final String HONEYPOT = "studio:website";
    static final String REVERSE_AGENT = "author";
    static final int MAX_BODY = 256 * 1024;
    static final long MIN_INTERVAL_MS = 2000;

    @Reference
    private transient SubmissionStore store;

    @Reference
    private transient AfterSubmit afterSubmit;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile Replicator replicator;

    /** last submission per remote address: the simplest rate limit, enough against a runaway script */
    private final transient Map<String, Long> last = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> body;
        try {
            body = read(request);
        } catch (IllegalArgumentException e) {
            send(response, 400, error("The submission could not be read: " + e.getMessage()));
            return;
        }
        String form = string(body.get("form"));
        Object dataObj = body.get("data");
        Map<String, Object> data = dataObj instanceof Map ? (Map<String, Object>) dataObj : new LinkedHashMap<>();
        Object filesObj = body.get("files");
        Map<String, List<RequestParameter>> files = filesObj instanceof Map ? (Map<String, List<RequestParameter>>) filesObj : Collections.emptyMap();
        if (form == null || !form.startsWith(SubmissionStore.FORMS_ROOT + "/")) {
            send(response, 400, error("Not a form: " + form));
            return;
        }
        // the component path (…/jcr:content/root) or the page: the page is what we keep
        String page = form.contains("/jcr:content") ? form.substring(0, form.indexOf("/jcr:content")) : form;
        if (!isBlank(string(data.get(HONEYPOT)))) {
            // a bot filled the hidden field: say yes, keep nothing
            send(response, 200, Collections.singletonMap("ok", Boolean.TRUE));
            return;
        }
        data.remove(HONEYPOT);
        boolean preview = Boolean.TRUE.equals(body.get("preview")) || "true".equals(string(body.get("preview")));

        String remote = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        Long previous = last.put(remote, now);
        if (!preview && previous != null && now - previous < MIN_INTERVAL_MS) {
            send(response, 429, error("Please wait a moment before sending again."));
            return;
        }
        if (last.size() > 10000) {
            last.clear();
        }

        try (ResourceResolver resolver = store.serviceResolver()) {
            Resource pageRes = resolver.getResource(page);
            FormModel model = FormModel.of(pageRes);
            if (model == null) {
                send(response, 404, error("No form at " + page));
                return;
            }
            // uploaded files count as their names for required / the stored value; the files themselves are checked below
            for (Map.Entry<String, List<RequestParameter>> e : files.entrySet()) {
                List<String> names = new java.util.ArrayList<>();
                for (RequestParameter p : e.getValue()) {
                    names.add(SubmissionStore.safeName(p.getFileName()));
                }
                data.put(e.getKey(), names);
            }
            Map<String, Object> accepted = new LinkedHashMap<>();
            Map<String, String> errors = model.validate(data, accepted);
            for (FormModel.Field f : model.fields) {
                if (!"fileupload".equals(f.type) || errors.containsKey(f.name) || !accepted.containsKey(f.name)) {
                    continue;
                }
                List<RequestParameter> parts = files.getOrDefault(f.name, Collections.emptyList());
                if (parts.size() > 1 && !f.multiple) {
                    errors.put(f.name, "Choose one file.");
                    continue;
                }
                for (RequestParameter p : parts) {
                    String err = FormModel.checkFile(f, p.getFileName(), p.getContentType(), p.getSize());
                    if (err != null) {
                        errors.put(f.name, err);
                        break;
                    }
                }
            }
            if (!errors.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", Boolean.FALSE);
                out.put("errors", errors);
                send(response, 422, out);
                return;
            }
            if (preview) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", Boolean.TRUE);
                out.put("preview", Boolean.TRUE);
                send(response, 200, out);
                return;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("locale", request.getLocale() == null ? null : request.getLocale().toLanguageTag());
            meta.put("userAgent", trim(request.getHeader("User-Agent"), 300));
            meta.put("referrer", trim(request.getHeader("Referer"), 500));
            meta.put("origin", request.getServerName());
            String path = store.store(resolver, page, accepted, meta);
            Resource submission = resolver.getResource(path);
            for (Map.Entry<String, List<RequestParameter>> e : files.entrySet()) {
                FormModel.Field f = model.field(e.getKey());
                if (f == null || !"fileupload".equals(f.type) || !accepted.containsKey(f.name)) {
                    continue;
                }
                for (RequestParameter p : e.getValue()) {
                    try (java.io.InputStream in = p.getInputStream()) {
                        store.storeFile(resolver, submission, f.name, p.getFileName(), p.getContentType(), in);
                    }
                }
            }
            LOG.info("Form {} submitted: {}", page, path);
            afterSubmit.run(resolver, model, path, accepted);
            handOver(resolver, path);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", Boolean.TRUE);
            out.put("id", path.substring(path.lastIndexOf('/') + 1));
            send(response, 200, out);
        } catch (Exception e) {
            LOG.error("Submission of {} failed: {}", page, e.getMessage(), e);
            send(response, 500, error("The form could not be sent."));
        }
    }

    /** On a Live instance with a target named {@code author}: the submission travels to the author. */
    private void handOver(ResourceResolver resolver, String path) {
        Replicator r = replicator;
        if (r == null) {
            return;
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return;
        }
        ReplicationOptions options = new ReplicationOptions();
        options.setSuppressVersions(true);
        options.setSuppressStatusUpdate(true);
        options.setFilter((Agent agent) -> agent != null && agent.isEnabled() && REVERSE_AGENT.equals(agent.getId()));
        try {
            r.replicate(session, ReplicationActionType.ACTIVATE, path, options);
        } catch (ReplicationException e) {
            // no reverse target: an author instance, where the submission already is where it belongs
            LOG.debug("No hand-over of {}: {}", path, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ reading the request

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(SlingHttpServletRequest request) throws IOException {
        String type = request.getContentType();
        if (type != null && type.startsWith("application/json")) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = request.getReader()) {
                char[] buf = new char[4096];
                for (int n; (n = reader.read(buf)) > 0; ) {
                    sb.append(buf, 0, n);
                    if (sb.length() > MAX_BODY) {
                        throw new IllegalArgumentException("too large");
                    }
                }
            }
            Object parsed = Json.parse(sb.toString());
            if (!(parsed instanceof Map)) {
                throw new IllegalArgumentException("not an object");
            }
            return (Map<String, Object>) parsed;
        }
        // a plain or multipart HTML form post: form=<path>, every other parameter a field; file parts under "files"
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, List<RequestParameter>> files = new LinkedHashMap<>();
        for (Map.Entry<String, RequestParameter[]> e : request.getRequestParameterMap().entrySet()) {
            RequestParameter[] values = e.getValue();
            if ("form".equals(e.getKey()) || "preview".equals(e.getKey())) {
                body.put(e.getKey(), values.length > 0 ? values[0].getString() : null);
                continue;
            }
            List<String> l = new java.util.ArrayList<>();
            for (RequestParameter v : values) {
                if (v.isFormField()) {
                    l.add(v.getString());
                } else if (v.getSize() > 0 && v.getFileName() != null && !v.getFileName().isEmpty()) {
                    files.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(v);
                }
            }
            if (files.containsKey(e.getKey())) {
                continue;
            }
            data.put(e.getKey(), l.size() == 1 ? l.get(0) : l);
        }
        body.put("data", data);
        body.put("files", files);
        return body;
    }

    static String string(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String trim(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", Boolean.FALSE);
        m.put("error", message);
        return m;
    }

    static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.getWriter().write(Json.write(body));
    }
}
