package io.sensible.studio.cfm.impl;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import javax.servlet.Servlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON API of the Sensible Studio content fragment editor. Reads and writes with the requesting user's resolver.
 *
 * <pre>
 * GET  /bin/studio/cf.json?op=models
 * GET  /bin/studio/cf.json?op=model&amp;path=/conf/wknd-shared/settings/dam/cfm/models/adventure
 * GET  /bin/studio/cf.json?op=folder&amp;path=/content/dam/wknd-shared/en
 * GET  /bin/studio/cf.json?op=search&amp;path=/content/dam&amp;q=surf&amp;model=...&amp;offset=0&amp;limit=50
 * GET  /bin/studio/cf.json?op=fragment&amp;path=/content/dam/.../bali-surf-camp
 * POST /bin/studio/cf.json  {"op":"save", "path", "variation", "values":{...}, "contentTypes":{...}, "title", "description", "tags"}
 *                        {"op":"create", "parent", "name", "title", "model", "description"}
 *                        {"op":"delete", "path"}
 *                        {"op":"createVariation", "path", "name", "title", "description"}
 *                        {"op":"deleteVariation", "path", "name"}
 *                        {"op":"createModel", "conf", "name", "title", "description"}
 *                        {"op":"saveModel", "path", "title", "description", "fields":[...]}   (fields as op=model returns them)
 *                        {"op":"setModelStatus", "path", "enabled"}
 *                        {"op":"deleteModel", "path", "force"}   (refused with 422 while fragments use it)
 *                        {"op":"createVersion", "path", "label", "comment"}
 *                        {"op":"restoreVersion", "path", "version"}   (saves the current state as a version first)
 *                        {"op":"translate", "paths":[...], "languages":["fr"], "provider", "includeReferences", "force"}
 *                        {"op":"approveTranslation", "path"}
 * GET  /bin/studio/cf.json?op=languages&amp;path=... | op=translationProviders   (translation errors answer 502 with a code)
 * GET  /bin/studio/cf.json?op=versions&amp;path=... | op=version&amp;path=...&amp;version=1.2
 * GET  /bin/studio/cf.json?op=confs | op=modelUsage&amp;path=...
 * </pre>
 * Validation failures answer 422 with {@code {"errors":[{"field","message"}]}}; other client errors 400.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/studio/cf",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST"
        })
public class ContentFragmentServlet extends SlingAllMethodsServlet {

    @org.osgi.service.component.annotations.Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL,
            policyOption = org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY)
    private transient volatile com.day.cq.replication.Replicator replicator;

    private static final long serialVersionUID = 1L;

    /** Sensible Studio Translation (studio-translation bundle); fragments translate only when it is there. */
    @org.osgi.service.component.annotations.Reference(
            cardinality = org.osgi.service.component.annotations.ReferenceCardinality.OPTIONAL,
            policy = org.osgi.service.component.annotations.ReferencePolicy.DYNAMIC,
            policyOption = org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY)
    private volatile transient io.sensible.studio.translation.api.Translator translator;

    void setTranslator(io.sensible.studio.translation.api.Translator translator) {
        this.translator = translator;
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = request.getParameter("op");
        String path = request.getParameter("path");
        FragmentStore store = new FragmentStore(request.getResourceResolver());
        Object result;
        if ("models".equals(op)) {
            result = ModelJson.listModels(request.getResourceResolver());
        } else if ("model".equals(op)) {
            result = ModelJson.model(request.getResourceResolver(), path);
        } else if ("folder".equals(op)) {
            result = store.folder(path == null ? "/content/dam" : path);
        } else if ("search".equals(op)) {
            result = store.search(path == null ? "/content/dam" : path, request.getParameter("q"),
                    request.getParameter("model"), intParam(request, "offset", 0), Math.min(intParam(request, "limit", 50), 500));
        } else if ("fragment".equals(op)) {
            result = path == null ? null : store.fragment(path);
        } else if ("references".equals(op)) {
            if (path == null || request.getResourceResolver().getResource(path) == null) {
                result = null;
            } else {
                Map<String, Object> refs = ReferenceSearch.usedIn(request.getResourceResolver(), path);
                refs.put("references", ReferenceSearch.referencesOf(request.getResourceResolver(), path));
                result = refs;
            }
        } else if ("versions".equals(op)) {
            try {
                result = new FragmentVersions(request.getResourceResolver()).list(path);
            } catch (javax.jcr.RepositoryException | IllegalArgumentException | UnsupportedOperationException e) {
                send(response, e instanceof UnsupportedOperationException ? 501 : 404, error(e.getMessage()));
                return;
            }
        } else if ("version".equals(op)) {
            try {
                result = new FragmentVersions(request.getResourceResolver()).content(path, request.getParameter("version"));
            } catch (javax.jcr.RepositoryException | IllegalArgumentException | UnsupportedOperationException e) {
                send(response, e instanceof UnsupportedOperationException ? 501 : 404, error(e.getMessage()));
                return;
            }
        } else if ("languages".equals(op)) {
            result = path == null ? null : new FragmentTranslation(request.getResourceResolver(), translator).languages(path);
        } else if ("translationProviders".equals(op)) {
            io.sensible.studio.translation.api.Translator t = translator;
            result = t == null ? new ArrayList<>() : path == null ? t.providers() : t.providers(request.getResourceResolver(), path);
        } else if ("confs".equals(op)) {
            result = new ModelStore(request.getResourceResolver()).confs();
        } else if ("modelUsage".equals(op)) {
            if (path == null || request.getResourceResolver().getResource(path) == null) {
                result = null;
            } else {
                List<String> used = new ModelStore(request.getResourceResolver()).usage(path, 200);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", path);
                m.put("fragments", used);
                m.put("truncated", used.size() >= 200);
                result = m;
            }
        } else if ("endpoints".equals(op)) {
            result = endpoints(request.getResourceResolver());
        } else {
            send(response, 400, error("Unknown op " + op));
            return;
        }
        if (result == null) send(response, 404, error("Not found: " + path));
        else send(response, 200, result);
    }

    /** GraphQL endpoints: the global one and one per {@code /content/cq:graphql/<conf>/endpoint}. */
    static java.util.List<Map<String, Object>> endpoints(org.apache.sling.api.resource.ResourceResolver resolver) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("conf", null);
        global.put("title", "Global");
        global.put("url", "/content/graphql/global/endpoint.json");
        out.add(global);
        org.apache.sling.api.resource.Resource root = resolver.getResource("/content/cq:graphql");
        if (root == null) return out;
        for (org.apache.sling.api.resource.Resource conf : root.getChildren()) {
            org.apache.sling.api.resource.Resource endpoint = conf.getChild("endpoint");
            if (endpoint == null || "global".equals(conf.getName())) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("conf", conf.getName());
            e.put("title", endpoint.getValueMap().get("jcr:title", conf.getName()));
            e.put("url", endpoint.getPath() + ".json");
            out.add(e);
        }
        return out;
    }

    /** A published fragment is taken off the live site before it is deleted; false when it was not published. */
    private boolean unpublish(SlingHttpServletRequest request, String path) {
        org.apache.sling.api.resource.Resource asset = path == null ? null : request.getResourceResolver().getResource(path);
        if (!ContentFragmentImpl.isFragment(asset) || !published(asset)) return false;
        com.day.cq.replication.Replicator r = replicator;
        javax.jcr.Session session = request.getResourceResolver().adaptTo(javax.jcr.Session.class);
        if (r == null || session == null) {
            throw new IllegalArgumentException(path + " is published and publishing is not available here to take it down; nothing was deleted");
        }
        try {
            r.replicate(session, com.day.cq.replication.ReplicationActionType.DELETE, path);
        } catch (com.day.cq.replication.ReplicationException e) {
            throw new IllegalArgumentException(path + " is published and could not be unpublished (" + e.getMessage() + "); nothing was deleted");
        }
        return true;
    }

    static boolean published(org.apache.sling.api.resource.Resource asset) {
        org.apache.sling.api.resource.Resource content = asset.getChild("jcr:content");
        return content != null && ("Activate".equalsIgnoreCase(content.getValueMap().get("cq:lastReplicationAction", String.class))
                || "Activate".equalsIgnoreCase(content.getValueMap().get("cq:lastReplicationAction_preview", String.class)));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(readBody(request));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
            body = (Map<String, Object>) parsed;
        } catch (RuntimeException e) {
            send(response, 400, error("Invalid JSON: " + e.getMessage()));
            return;
        }
        FragmentStore store = new FragmentStore(request.getResourceResolver());
        String op = str(body, "op");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            switch (op == null ? "" : op) {
                case "save":
                    store.save(str(body, "path"), str(body, "variation"), map(body, "values"), map(body, "contentTypes"),
                            str(body, "title"), str(body, "description"), list(body, "tags"));
                    out.put("fragment", store.fragment(str(body, "path")));
                    break;
                case "create":
                    out.put("path", store.create(str(body, "parent"), str(body, "name"), str(body, "title"),
                            str(body, "model"), str(body, "description")));
                    break;
                case "delete":
                    out.put("unpublished", unpublish(request, str(body, "path")));
                    store.delete(str(body, "path"));
                    out.put("deleted", str(body, "path"));
                    break;
                case "createVariation":
                    out.put("variation", store.createVariation(str(body, "path"), str(body, "name"), str(body, "title"),
                            str(body, "description")));
                    out.put("fragment", store.fragment(str(body, "path")));
                    break;
                case "createModel":
                    out.put("path", new ModelStore(request.getResourceResolver()).create(str(body, "conf"), str(body, "name"),
                            str(body, "title"), str(body, "description")));
                    break;
                case "saveModel":
                    new ModelStore(request.getResourceResolver()).save(str(body, "path"), str(body, "title"),
                            str(body, "description"), objects(body, "fields"));
                    out.put("model", ModelJson.model(request.getResourceResolver(), str(body, "path")));
                    break;
                case "setModelStatus":
                    new ModelStore(request.getResourceResolver()).setEnabled(str(body, "path"), !Boolean.FALSE.equals(body.get("enabled")));
                    out.put("model", ModelJson.model(request.getResourceResolver(), str(body, "path")));
                    break;
                case "createVersion":
                    out.put("version", new FragmentVersions(request.getResourceResolver()).create(str(body, "path"),
                            str(body, "label"), str(body, "comment")));
                    break;
                case "restoreVersion":
                    out.put("saved", new FragmentVersions(request.getResourceResolver()).restore(str(body, "path"), str(body, "version")));
                    out.put("fragment", store.fragment(str(body, "path")));
                    break;
                case "translate": {
                    List<String> paths = list(body, "paths");
                    if (paths == null && str(body, "path") != null) {
                        paths = new ArrayList<>();
                        paths.add(str(body, "path"));
                    }
                    out.put("results", new FragmentTranslation(request.getResourceResolver(), translator).translate(
                            paths == null ? new ArrayList<>() : paths, list(body, "languages"), str(body, "provider"),
                            Boolean.TRUE.equals(body.get("includeReferences")), Boolean.TRUE.equals(body.get("force"))));
                    break;
                }
                case "approveTranslation":
                    new FragmentTranslation(request.getResourceResolver(), translator).approve(str(body, "path"));
                    out.put("languages", new FragmentTranslation(request.getResourceResolver(), translator).languages(str(body, "path")));
                    break;
                case "deleteModel":
                    new ModelStore(request.getResourceResolver()).delete(str(body, "path"), Boolean.TRUE.equals(body.get("force")));
                    out.put("deleted", str(body, "path"));
                    break;
                case "deleteVariation":
                    store.deleteVariation(str(body, "path"), str(body, "name"));
                    out.put("fragment", store.fragment(str(body, "path")));
                    break;
                default:
                    send(response, 400, error("Unknown op " + op));
                    return;
            }
            send(response, 200, out);
        } catch (FragmentStore.ValidationException e) {
            request.getResourceResolver().revert();
            List<Object> errors = new ArrayList<>();
            for (FragmentStore.FieldError fe : e.errors) errors.add(fe.toJson());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("errors", errors);
            send(response, 422, m);
        } catch (IllegalArgumentException e) {
            request.getResourceResolver().revert();
            send(response, 400, error(e.getMessage()));
        } catch (PersistenceException e) {
            request.getResourceResolver().revert();
            if (denied(e) || String.valueOf(e.getMessage()).startsWith("Unable to create node")) {
                send(response, 403, error("You are not allowed to do that here"));
                return;
            }
            send(response, 500, error("Could not save: " + e.getMessage()));
        } catch (UnsupportedOperationException e) {
            send(response, 501, error(e.getMessage()));
        } catch (com.adobe.granite.translation.api.TranslationException e) {
            request.getResourceResolver().revert();
            Map<String, Object> m = error(e.getMessage());
            m.put("code", e.getErrorCode() == null ? "GENERAL" : e.getErrorCode().name());
            send(response, 502, m);
        } catch (javax.jcr.RepositoryException e) {
            request.getResourceResolver().revert();
            send(response, 500, error("Repository error: " + e.getMessage()));
        }
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

    private static String readBody(SlingHttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 5_000_000) throw new IOException("Request too large");
            }
        }
        return sb.toString();
    }

    private static int intParam(SlingHttpServletRequest r, String name, int dflt) {
        try {
            String v = r.getParameter(name);
            return v == null ? dflt : Math.max(0, Integer.parseInt(v));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static List<String> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof Collection)) return null;
        List<String> out = new ArrayList<>();
        for (Object o : (Collection<?>) v) if (o != null) out.add(o.toString());
        return out;
    }

    private static List<Object> objects(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Collection ? new ArrayList<>((Collection<?>) v) : null;
    }
    /** The repository refused for lack of rights (also when wrapped): a 403 with words, not a 500 with Oak's. */
    static boolean denied(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (t instanceof javax.jcr.AccessDeniedException || m.contains("OakAccess0000") || m.startsWith("Access denied")) {
                return true;
            }
        }
        return false;
    }
}
