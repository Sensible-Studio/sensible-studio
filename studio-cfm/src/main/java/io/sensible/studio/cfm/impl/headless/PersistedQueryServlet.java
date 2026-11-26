package io.sensible.studio.cfm.impl.headless;

import io.sensible.studio.cfm.impl.Json;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AEM's persisted query API:
 * <ul>
 *   <li>{@code GET /graphql/execute.json/<conf>/<name>;var1=value1;var2=value2} runs a stored query (the
 *   semicolons may be URL encoded as {@code %3B} and {@code %3D}, as the AEM headless client does);</li>
 *   <li>{@code PUT /graphql/persist.json/<conf>/<name>} stores a query (body: the query, or JSON with
 *   {@code query} and {@code cache-control}); {@code POST} replaces one;</li>
 *   <li>{@code GET /graphql/list.json[/<conf>]} lists them.</li>
 * </ul>
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/graphql/execute",
                "sling.servlet.paths=/graphql/persist",
                "sling.servlet.paths=/graphql/list",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST",
                "sling.servlet.methods=PUT",
                "sling.servlet.methods=OPTIONS"
        })
public class PersistedQueryServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String EXECUTE = "/graphql/execute";

    @Reference
    private transient HeadlessService headless;

    public PersistedQueryServlet() {
    }

    PersistedQueryServlet(HeadlessService headless) {
        this.headless = headless;
    }

    @Override
    protected void doOptions(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        headless.cors(request, response);
        response.setHeader("Allow", "GET, OPTIONS");
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String servlet = request.getRequestPathInfo().getResourcePath();
        if (servlet.endsWith("/list")) {
            String suffix = request.getRequestPathInfo().getSuffix();
            String conf = suffix == null || suffix.length() < 2 ? null : suffix.substring(1);
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_OK, headless.listPersisted(request.getResourceResolver(), conf));
            return;
        }
        if (!servlet.endsWith("/execute")) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        headless.cors(request, response);
        Call call = Call.parse(request.getRequestURI(), request.getContextPath());
        if (call == null) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    GraphQLEndpointServlet.error("Expected /graphql/execute.json/<configuration>/<query name>"));
            return;
        }
        String query = headless.persistedQuery(request.getResourceResolver(), call.conf, call.name);
        if (query == null) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_NOT_FOUND,
                    GraphQLEndpointServlet.error("No persisted query " + call.conf + "/" + call.name));
            return;
        }
        Map<String, String> raw = new LinkedHashMap<>(call.variables);
        // plain query string parameters are accepted too
        for (Map.Entry<String, String[]> p : request.getParameterMap().entrySet()) {
            if (p.getValue().length > 0) raw.putIfAbsent(p.getKey(), p.getValue()[0]);
        }
        Map<String, Object> variables = HeadlessService.coerceVariables(query, null, raw);
        Map<String, Object> result = headless.execute(request.getResourceResolver(), call.conf, query, variables, null,
                HeadlessService.baseUrl(request));
        response.setHeader("Cache-Control", headless.cacheControl(request.getResourceResolver(), call.conf, call.name));
        GraphQLEndpointServlet.write(response, HttpServletResponse.SC_OK, result);
    }

    @Override
    protected void doPut(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        persist(request, response, false);
    }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        persist(request, response, true);
    }

    @SuppressWarnings("unchecked")
    private void persist(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean replace) throws IOException {
        if (!request.getRequestPathInfo().getResourcePath().endsWith("/persist")) {
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        String suffix = request.getRequestPathInfo().getSuffix();
        String[] parts = suffix == null ? new String[0] : suffix.substring(1).split("/");
        if (parts.length < 2) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    GraphQLEndpointServlet.error("Expected /graphql/persist.json/<configuration>/<query name>"));
            return;
        }
        String name = parts[parts.length - 1];
        String conf = String.join("/", java.util.Arrays.copyOf(parts, parts.length - 1));
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String query = body;
        String cache = null;
        if (body.trim().startsWith("{")) {
            try {
                Map<String, Object> m = (Map<String, Object>) Json.parse(body);
                query = (String) m.get("query");
                Object cc = m.get("cache-control");
                if (cc instanceof Map) {
                    Object maxAge = ((Map<String, Object>) cc).get("max-age");
                    cache = maxAge == null ? null : "max-age=" + ((Number) maxAge).longValue();
                } else if (cc != null) {
                    cache = cc.toString();
                }
            } catch (RuntimeException e) {
                query = body; // a query that starts with "{" (shorthand syntax)
            }
        }
        if (query == null || query.trim().isEmpty()) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_BAD_REQUEST, GraphQLEndpointServlet.error("No query"));
            return;
        }
        List<String> problems = headless.validate(request.getResourceResolver(), conf, query);
        if (!problems.isEmpty()) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    GraphQLEndpointServlet.error(String.join("; ", problems)));
            return;
        }
        try {
            Map<String, Object> result = headless.persist(request.getResourceResolver(), conf, name, query, cache, replace);
            GraphQLEndpointServlet.write(response, "create".equals(result.get("action")) ? HttpServletResponse.SC_CREATED
                    : HttpServletResponse.SC_OK, result);
        } catch (IllegalStateException e) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_CONFLICT, GraphQLEndpointServlet.error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_BAD_REQUEST, GraphQLEndpointServlet.error(e.getMessage()));
        } catch (PersistenceException e) {
            GraphQLEndpointServlet.write(response, HttpServletResponse.SC_FORBIDDEN,
                    GraphQLEndpointServlet.error("Cannot store the query: " + e.getMessage()));
        }
    }

    /** A parsed {@code /graphql/execute.json/<conf>/<name>;k=v;...} request. */
    static final class Call {
        final String conf;
        final String name;
        final Map<String, String> variables = new LinkedHashMap<>();

        private Call(String conf, String name) {
            this.conf = conf;
            this.name = name;
        }

        static Call parse(String requestUri, String contextPath) {
            String uri = requestUri;
            if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) uri = uri.substring(contextPath.length());
            int at = uri.indexOf(EXECUTE);
            if (at < 0) return null;
            String rest = uri.substring(at + EXECUTE.length());
            int slash = rest.indexOf('/');
            if (slash < 0) return null;
            rest = rest.substring(slash + 1);
            int q = rest.indexOf('?');
            if (q >= 0) rest = rest.substring(0, q);
            rest = rest.replace("%3B", ";").replace("%3b", ";").replace("%3D", "=").replace("%3d", "=");
            String[] segments = rest.split(";");
            String path = decode(segments[0]);
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int last = path.lastIndexOf('/');
            if (last <= 0 || last == path.length() - 1) return null;
            Call call = new Call(path.substring(0, last), path.substring(last + 1));
            if (call.conf.contains("..")) return null;
            for (int i = 1; i < segments.length; i++) {
                String s = segments[i];
                if (s.isEmpty()) continue;
                int eq = s.indexOf('=');
                if (eq <= 0) call.variables.put(decode(s), "");
                else call.variables.put(decode(s.substring(0, eq)), decode(s.substring(eq + 1)));
            }
            return call;
        }

        private static String decode(String s) {
            try {
                return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
            } catch (java.io.UnsupportedEncodingException | IllegalArgumentException e) {
                return s;
            }
        }
    }
}
