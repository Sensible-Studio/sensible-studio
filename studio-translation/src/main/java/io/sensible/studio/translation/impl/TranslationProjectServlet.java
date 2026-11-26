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
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translation projects (see {@link TranslationProjects}).
 * <pre>
 * GET  /bin/studio/translationprojects.json?op=list | op=get&amp;name= | op=xliff&amp;name=&amp;language=[&amp;version=2.0] (the job's texts, XLIFF 1.2 or 2.0)
 * POST /bin/studio/translationprojects?op=importXliff&amp;name=&amp;language=  body: a translated XLIFF (checked, saved as reviewed)
 * POST /bin/studio/translationprojects  JSON:
 *   {"op":"create", "title", "description", "paths":[...], "deep", "includeReferences", "languages":[...], "provider", "dueDate":millis}
 *   {"op":"addItems", "name", "paths":[...], "deep"}       {"op":"removeItem", "name", "item"}
 *   {"op":"addLanguage", "name", "language"}       {"op":"setDueDate", "name", "dueDate":millis|null}  (the owner's Inbox reminder follows)
 *   {"op":"translateItem", "name", "language", "item", "force"}   502 {code} when the connector fails (the item stays to do)
 *   {"op":"setStatus", "name", "language", "status": APPROVED | COMPLETE | CANCELLED | DRAFT (update)}
 *   {"op":"runInBackground", "name", "language"}  202: the job's items are translated on the server (studio-translation-worker,
 *                                                  on the user's behalf; the user must be able to write where the copies go)
 *   {"op":"stopBackground", "name", "language"}   stops after the current item
 *   {"op":"delete", "name"}
 * </pre>
 * Projects are kept with the service session (authors need no rights on /var); content is translated with the user's.
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/translationprojects",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class TranslationProjectServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    transient volatile Translator translator;

    @Reference
    transient volatile PageTranslationRules rules;

    @Reference
    transient volatile ResourceResolverFactory resolverFactory;

    /** Sling jobs (optional: without them projects run from the console only). */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    transient volatile org.apache.sling.event.jobs.JobManager jobManager;

    private final transient List<ReferenceTranslator> referenceTranslators = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Reference(service = ReferenceTranslator.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    void bindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.add(t);
    }

    void unbindReferenceTranslator(ReferenceTranslator t) {
        referenceTranslators.remove(t);
    }

    private ResourceResolver serviceResolver() {
        try {
            return resolverFactory.getServiceResourceResolver(java.util.Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "translation"));
        } catch (LoginException | RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        ResourceResolver service = serviceResolver();
        try {
            TranslationProjects projects = new TranslationProjects(request.getResourceResolver(), service, translator, rules, referenceTranslators);
            String op = request.getParameter("op");
            if ("list".equals(op) || op == null) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("projects", projects.list());
                send(response, 200, m);
            } else if ("get".equals(op)) {
                send(response, 200, projects.get(request.getParameter("name")));
            } else if ("xliff".equals(op)) {
                String name = request.getParameter("name");
                String language = request.getParameter("language");
                String version = request.getParameter("version");
                String xml = projects.exportXliff(name, language, version == null || version.isEmpty() ? "1.2" : version);
                response.setStatus(200);
                response.setContentType("application/x-xliff+xml");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                String file = (name + "-" + language + ("2.0".equals(version) ? "-xliff2" : "") + ".xlf").replaceAll("[^A-Za-z0-9._-]", "_");
                response.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
                response.getWriter().write(xml);
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
        if ("importXliff".equals(request.getParameter("op"))) {
            importXliff(request, response);
            return;
        }
        Map<String, Object> body;
        try {
            Object parsed = Json.parse(readBody(request));
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("A JSON object is needed");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            body = m;
        } catch (RuntimeException e) {
            send(response, 400, error("Bad request: " + e.getMessage()));
            return;
        }
        ResourceResolver service = serviceResolver();
        try {
            TranslationProjects projects = new TranslationProjects(request.getResourceResolver(), service, translator, rules, referenceTranslators);
            String op = str(body, "op");
            String name = str(body, "name");
            if ("create".equals(op)) {
                Calendar due = null;
                if (body.get("dueDate") instanceof Number) {
                    due = Calendar.getInstance();
                    due.setTimeInMillis(((Number) body.get("dueDate")).longValue());
                }
                send(response, 201, projects.create(str(body, "title"), str(body, "description"), list(body.get("paths")), Boolean.TRUE.equals(body.get("deep")),
                        !Boolean.FALSE.equals(body.get("includeReferences")), list(body.get("languages")), str(body, "provider"), due));
            } else if ("addItems".equals(op)) {
                send(response, 200, projects.addItems(name, list(body.get("paths")), Boolean.TRUE.equals(body.get("deep"))));
            } else if ("removeItem".equals(op)) {
                send(response, 200, projects.removeItem(name, str(body, "item")));
            } else if ("setDueDate".equals(op)) {
                Calendar due = null;
                if (body.get("dueDate") instanceof Number) {
                    due = Calendar.getInstance();
                    due.setTimeInMillis(((Number) body.get("dueDate")).longValue());
                }
                send(response, 200, projects.setDueDate(name, due));
            } else if ("addLanguage".equals(op)) {
                send(response, 200, projects.addLanguage(name, str(body, "language")));
            } else if ("translateItem".equals(op)) {
                send(response, 200, projects.translateItem(name, str(body, "language"), str(body, "item"), Boolean.TRUE.equals(body.get("force"))));
            } else if ("setStatus".equals(op)) {
                send(response, 200, projects.setStatus(name, str(body, "language"), str(body, "status")));
            } else if ("runInBackground".equals(op)) {
                org.apache.sling.event.jobs.JobManager jobs = jobManager;
                if (jobs == null) throw new IllegalArgumentException("Jobs cannot run on the server here (no Sling job manager)");
                String language = str(body, "language");
                Map<String, Object> project = projects.queueBackground(name, language);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(TranslationProjectJob.PROP_PROJECT, name);
                props.put(TranslationProjectJob.PROP_LANGUAGE, language);
                props.put(TranslationProjectJob.PROP_USER, request.getResourceResolver().getUserID());
                if (jobs.addJob(TranslationProjectJob.TOPIC, props) == null) {
                    projects.stopBackground(name, language);
                    throw new IllegalArgumentException("The job could not be queued");
                }
                send(response, 202, project);
            } else if ("stopBackground".equals(op)) {
                send(response, 200, projects.stopBackground(name, str(body, "language")));
            } else if ("delete".equals(op)) {
                projects.delete(name);
                send(response, 200, java.util.Collections.singletonMap("deleted", name));
            } else {
                send(response, 400, error("Unknown op " + op));
            }
        } catch (IllegalArgumentException e) {
            request.getResourceResolver().revert();
            if (service != null) service.revert();
            send(response, 422, error(e.getMessage()));
        } catch (TranslationException e) {
            request.getResourceResolver().revert();
            Map<String, Object> m = error(e.getMessage());
            m.put("code", e.getErrorCode() == null ? "GENERAL" : e.getErrorCode().name());
            send(response, 502, m);
        } catch (PersistenceException e) {
            request.getResourceResolver().revert();
            if (service != null) service.revert();
            send(response, 500, error("Could not save: " + e.getMessage()));
        } catch (Exception e) {
            request.getResourceResolver().revert();
            if (service != null) service.revert();
            send(response, 500, error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (service != null) service.close();
        }
    }

    /** POST ?op=importXliff&amp;name=&amp;language= with the XLIFF file as the body (at most 20 MB). */
    private void importXliff(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (request.getContentLength() > 20_000_000) {
            send(response, 413, error("The file is too large (20 MB at most)"));
            return;
        }
        ResourceResolver service = serviceResolver();
        try {
            TranslationProjects projects = new TranslationProjects(request.getResourceResolver(), service, translator, rules, referenceTranslators);
            send(response, 200, projects.importXliff(request.getParameter("name"), request.getParameter("language"), request.getInputStream()));
        } catch (IllegalArgumentException e) {
            request.getResourceResolver().revert();
            send(response, 422, error(e.getMessage()));
        } catch (Exception e) {
            request.getResourceResolver().revert();
            send(response, 500, error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (service != null) service.close();
        }
    }

    private static List<String> list(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) if (x != null && !String.valueOf(x).trim().isEmpty()) out.add(String.valueOf(x).trim());
        return out;
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
}
