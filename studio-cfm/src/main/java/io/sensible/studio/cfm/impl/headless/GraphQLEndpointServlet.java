package io.sensible.studio.cfm.impl.headless;

import io.sensible.studio.cfm.impl.Json;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphQL endpoints, as AEM: {@code POST /content/cq:graphql/<conf>/endpoint.json} (an endpoint node of type
 * {@code graphql/sites/components/endpoint}, as the WKND package ships) and the global endpoint at
 * {@code /content/graphql/global/endpoint.json} or {@code /content/cq:graphql/global/endpoint.json}.
 * Body: {@code {"query", "variables", "operationName"}} (or {@code application/graphql}); GET takes the same as
 * parameters.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=graphql/sites/components/endpoint",
                "sling.servlet.extensions=json",
                "sling.servlet.paths=/content/graphql/global/endpoint",
                "sling.servlet.paths=/content/cq:graphql/global/endpoint",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=POST",
                "sling.servlet.methods=OPTIONS"
        })
public class GraphQLEndpointServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient HeadlessService headless;

    public GraphQLEndpointServlet() {
    }

    GraphQLEndpointServlet(HeadlessService headless) {
        this.headless = headless;
    }

    @Override
    protected void doOptions(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        headless.cors(request, response);
        response.setHeader("Allow", "GET, POST, OPTIONS");
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Object variables = request.getParameter("variables") == null ? null : parse(request.getParameter("variables"));
        run(request, response, request.getParameter("query"), variables, request.getParameter("operationName"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String type = request.getContentType() == null ? "" : request.getContentType();
        if (type.startsWith("application/graphql")) {
            run(request, response, body, null, request.getParameter("operationName"));
            return;
        }
        Object parsed = parse(body);
        if (!(parsed instanceof Map)) {
            write(response, HttpServletResponse.SC_BAD_REQUEST, error("Expected a JSON body with a \"query\""));
            return;
        }
        Map<String, Object> m = (Map<String, Object>) parsed;
        Object variables = m.get("variables") instanceof String ? parse((String) m.get("variables")) : m.get("variables");
        run(request, response, (String) m.get("query"), variables, (String) m.get("operationName"));
    }

    @SuppressWarnings("unchecked")
    private void run(SlingHttpServletRequest request, SlingHttpServletResponse response, String query, Object variables,
                     String operationName) throws IOException {
        headless.cors(request, response);
        if (query == null || query.trim().isEmpty()) {
            write(response, HttpServletResponse.SC_BAD_REQUEST, error("No query"));
            return;
        }
        if (variables != null && !(variables instanceof Map)) {
            write(response, HttpServletResponse.SC_BAD_REQUEST, error("\"variables\" must be a JSON object"));
            return;
        }
        Map<String, Object> result = headless.execute(request.getResourceResolver(), conf(request.getResource()), query,
                (Map<String, Object>) variables, operationName, HeadlessService.baseUrl(request));
        write(response, HttpServletResponse.SC_OK, result);
    }

    /** {@code /content/cq:graphql/wknd-shared/endpoint} → {@code wknd-shared}; the global endpoint → null. */
    static String conf(Resource endpoint) {
        String path = endpoint.getPath();
        String[] parts = path.split("/");
        if (parts.length < 2) return null;
        String conf = parts[parts.length - 2];
        return "global".equals(conf) ? null : conf;
    }

    private static Object parse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errors", HeadlessService.errors(message));
        return m;
    }

    static void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.write(body));
    }
}
