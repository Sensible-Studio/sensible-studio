package io.sensible.studio.cfm.impl.headless;

import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.language.Document;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.OperationDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.VariableDefinition;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import io.sensible.studio.cfm.impl.Json;
import io.sensible.studio.cfm.impl.ModelJson;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Sensible Studio's headless delivery: GraphQL over content fragments, compatible with AEM's endpoints and persisted
 * queries. One schema per endpoint ({@code /conf/<conf>} models plus {@code /conf/global}, or every model for the
 * global endpoint), rebuilt when a model changes. Queries run with the caller's resolver.
 */
@Component(service = HeadlessService.class)
@Designate(ocd = HeadlessService.Config.class)
public class HeadlessService {

    static final String PERSISTED = "settings/graphql/persistentQueries";
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");

    @ObjectClassDefinition(name = "Sensible Studio Headless GraphQL", description = "GraphQL delivery of content fragments.")
    public @interface Config {
        @AttributeDefinition(name = "Publish URL", description = "Base URL for _publishUrl, e.g. https://publish.example.com. Empty: the URL of the request.")
        String publishUrl() default "";

        @AttributeDefinition(name = "CORS origins", description = "Origins allowed to call the GraphQL endpoints from a browser, e.g. http://localhost:3000. '*' allows any.")
        String[] corsAllowedOrigins() default {};

        @AttributeDefinition(name = "Max query depth")
        int maxQueryDepth() default 15;

        @AttributeDefinition(name = "Persisted query Cache-Control", description = "Default Cache-Control of persisted query responses.")
        String persistedQueryCacheControl() default "max-age=60";
    }

    private volatile String publishUrl = "";
    private volatile Set<String> corsOrigins = Collections.emptySet();
    private volatile int maxDepth = 15;
    private volatile String cacheControl = "max-age=60";
    private final Map<String, GraphQL> engines = new ConcurrentHashMap<>();

    @Activate
    @Modified
    void activate(Config config) {
        publishUrl = config.publishUrl() == null ? "" : config.publishUrl().trim();
        corsOrigins = new HashSet<>(Arrays.asList(config.corsAllowedOrigins() == null ? new String[0] : config.corsAllowedOrigins()));
        maxDepth = Math.max(3, config.maxQueryDepth());
        cacheControl = config.persistedQueryCacheControl();
        engines.clear();
    }

    // ------------------------------------------------------------------------------------------- execution

    /**
     * Runs a query against the endpoint of {@code conf} (null: the global endpoint) and returns the response in
     * the GraphQL wire format ({@code data}, {@code errors}).
     */
    public Map<String, Object> execute(ResourceResolver resolver, String conf, String query, Map<String, Object> variables,
                                       String operationName, String baseUrl) {
        Map<String, GqlModel> models = models(resolver, conf);
        GraphQL graphql = engine(models);
        HeadlessContext ctx = new HeadlessContext(resolver, models, baseUrl, publishUrl);
        Map<Object, Object> context = new HashMap<>();
        context.put(HeadlessContext.KEY, ctx);
        ExecutionInput input = ExecutionInput.newExecutionInput()
                .query(query)
                .variables(variables == null ? Collections.emptyMap() : variables)
                .operationName(operationName == null || operationName.isEmpty() ? null : operationName)
                .graphQLContext(context)
                .build();
        ExecutionResult result = graphql.execute(input);
        return result.toSpecification();
    }

    /** Validation errors of a query against the endpoint's schema (empty if it is valid). */
    public List<String> validate(ResourceResolver resolver, String conf, String query) {
        List<String> out = new ArrayList<>();
        Document doc;
        try {
            doc = Parser.parse(query);
        } catch (InvalidSyntaxException e) {
            out.add(e.getMessage());
            return out;
        }
        GraphQLSchema schema = engine(models(resolver, conf)).getGraphQLSchema();
        for (ValidationError e : new Validator().validateDocument(schema, doc, Locale.ENGLISH)) out.add(e.getMessage());
        return out;
    }

    /** The schema of an endpoint, e.g. for tools. */
    public GraphQLSchema schema(ResourceResolver resolver, String conf) {
        return engine(models(resolver, conf)).getGraphQLSchema();
    }

    private GraphQL engine(Map<String, GqlModel> models) {
        StringBuilder key = new StringBuilder();
        for (GqlModel m : models.values()) key.append(m.typeName).append('=').append(m.fingerprint).append(';');
        String k = key.toString();
        GraphQL cached = engines.get(k);
        if (cached != null) return cached;
        if (engines.size() > 32) engines.clear();
        GraphQL g = GraphQL.newGraphQL(SchemaFactory.build(models.values()))
                .instrumentation(new MaxQueryDepthInstrumentation(maxDepth))
                .build();
        engines.put(k, g);
        return g;
    }

    /** The enabled models of an endpoint, by path, with unique type names. */
    Map<String, GqlModel> models(ResourceResolver resolver, String conf) {
        List<String> paths = new ArrayList<>();
        if (conf == null) {
            for (Map<String, Object> m : ModelJson.listModels(resolver)) paths.add((String) m.get("path"));
        } else {
            // the configuration's own models, then the shared ones of /conf/global
            for (String root : new String[]{"/conf/" + conf, "/conf/global"}) {
                Resource models = resolver.getResource(root + "/settings/dam/cfm/models");
                if (models == null) continue;
                for (Resource m : models.getChildren()) if (!paths.contains(m.getPath())) paths.add(m.getPath());
            }
        }
        Map<String, GqlModel> out = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (String p : paths) {
            GqlModel m = GqlModel.load(resolver, p);
            if (m == null) continue;
            if (!names.add(m.typeName)) {
                String confName = m.conf == null ? "" : m.conf.substring(m.conf.lastIndexOf('/') + 1);
                String base = m.typeBase() + GqlModel.pascal(confName);
                int i = 2;
                while (names.contains(base + "Model")) base = m.typeBase() + GqlModel.pascal(confName) + i++;
                m = m.renamed(base);
                names.add(m.typeName);
            }
            out.put(p, m);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------- persisted queries

    /** The text of a persisted query ({@code /conf/<conf>/settings/graphql/persistentQueries/<name>}), or null. */
    public String persistedQuery(ResourceResolver resolver, String conf, String name) {
        Resource content = resolver.getResource(persistedPath(conf, name) + "/jcr:content");
        if (content == null) return null;
        ValueMap vm = content.getValueMap();
        Object data = vm.get("jcr:data");
        if (data instanceof String) return (String) data;
        InputStream in = vm.get("jcr:data", InputStream.class);
        if (in == null) in = content.adaptTo(InputStream.class);
        if (in == null) return null;
        try (InputStream body = in) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Cache-Control for a persisted query: its own {@code cache-control} property or the configured default. */
    public String cacheControl(ResourceResolver resolver, String conf, String name) {
        Resource content = resolver.getResource(persistedPath(conf, name) + "/jcr:content");
        String own = content == null ? null : content.getValueMap().get("cache-control", String.class);
        return own != null && !own.isEmpty() ? own : cacheControl;
    }

    /** Creates or replaces a persisted query; returns AEM's answer ({@code action}, {@code path}, ...). */
    public Map<String, Object> persist(ResourceResolver resolver, String conf, String name, String query,
                                       String queryCacheControl, boolean replace) throws PersistenceException {
        if (conf == null || conf.isEmpty() || name == null || !NAME.matcher(name).matches() || conf.contains("..")) {
            throw new IllegalArgumentException("Expected /<configuration>/<query name>");
        }
        Resource confRoot = resolver.getResource("/conf/" + conf);
        if (confRoot == null) throw new IllegalArgumentException("No configuration /conf/" + conf);
        Resource parent = ensure(resolver, ensure(resolver, ensure(resolver, confRoot, "settings", "sling:Folder"), "graphql", "sling:Folder"),
                "persistentQueries", "sling:Folder");
        Resource existing = parent.getChild(name);
        if (existing != null && !replace) throw new IllegalStateException("Exists already: " + existing.getPath());
        if (existing != null) resolver.delete(existing);
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("sling:resourceType", "graphql/persistent/query");
        Resource node = resolver.create(parent, name, props);
        Map<String, Object> content = new HashMap<>();
        content.put("jcr:primaryType", "nt:unstructured");
        content.put("sling:resourceType", "graphql/persistent/query/content");
        content.put("jcr:mimeType", "application/graphql");
        content.put("jcr:data", query);
        content.put("jcr:lastModified", Calendar.getInstance());
        if (queryCacheControl != null && !queryCacheControl.isEmpty()) content.put("cache-control", queryCacheControl);
        resolver.create(node, "jcr:content", content);
        resolver.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", existing == null ? "create" : "update");
        out.put("configurationName", conf);
        out.put("name", name);
        out.put("shortPath", "/" + conf + "/" + name);
        out.put("path", node.getPath());
        return out;
    }

    /** All persisted queries (of one configuration, or of all). */
    public List<Map<String, Object>> listPersisted(ResourceResolver resolver, String onlyConf) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource confs = resolver.getResource("/conf");
        if (confs == null) return out;
        for (Resource conf : confs.getChildren()) {
            if (onlyConf != null && !onlyConf.equals(conf.getName())) continue;
            Resource queries = conf.getChild(PERSISTED);
            if (queries == null) continue;
            for (Resource q : queries.getChildren()) {
                if (q.getChild("jcr:content") == null || "jcr:content".equals(q.getName())) continue;
                Map<String, Object> path = new LinkedHashMap<>();
                path.put("shortForm", "/" + conf.getName() + "/" + q.getName());
                path.put("fullForm", q.getPath());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", path);
                entry.put("query", persistedQuery(resolver, conf.getName(), q.getName()));
                Calendar modified = q.getChild("jcr:content").getValueMap().get("jcr:lastModified", Calendar.class);
                if (modified != null) entry.put("lastModified", modified.toInstant().toString());
                out.add(entry);
            }
        }
        return out;
    }

    static String persistedPath(String conf, String name) {
        return "/conf/" + conf + "/" + PERSISTED + "/" + name;
    }

    /**
     * Variables of a persisted query call ({@code ;name=value}) typed by the query's variable definitions:
     * Int, Float, Boolean become numbers/booleans, lists and input objects are read as JSON, the rest stays text.
     */
    public static Map<String, Object> coerceVariables(String query, String operationName, Map<String, String> raw) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        Document doc;
        try {
            doc = Parser.parse(query);
        } catch (InvalidSyntaxException e) {
            return out;
        }
        for (OperationDefinition op : doc.getDefinitionsOfType(OperationDefinition.class)) {
            if (operationName != null && !operationName.isEmpty() && !operationName.equals(op.getName())) continue;
            for (VariableDefinition vd : op.getVariableDefinitions()) {
                String value = raw.get(vd.getName());
                if (value != null) out.put(vd.getName(), coerce(value, vd.getType()));
            }
            break;
        }
        return out;
    }

    @SuppressWarnings("rawtypes")
    private static Object coerce(String value, Type type) {
        if (type instanceof NonNullType) return coerce(value, ((NonNullType) type).getType());
        String v = value.trim();
        if (type instanceof ListType) return v.startsWith("[") ? parseJson(value) : Collections.singletonList(coerce(value, ((ListType) type).getType()));
        String name = ((TypeName) type).getName();
        try {
            switch (name) {
                case "Int": return Integer.valueOf(v);
                case "Float": return Double.valueOf(v);
                case "Boolean": return "true".equalsIgnoreCase(v) ? Boolean.TRUE : "false".equalsIgnoreCase(v) ? Boolean.FALSE : value;
                case "String":
                case "ID": return value;
                default: return v.startsWith("{") || v.startsWith("[") ? parseJson(value) : value;
            }
        } catch (NumberFormatException e) {
            return value; // reported by GraphQL validation
        }
    }

    private static Object parseJson(String s) {
        try {
            return Json.parse(s);
        } catch (RuntimeException e) {
            return s;
        }
    }

    // ------------------------------------------------------------------------------------------- http helpers

    /** Adds CORS headers when the request's origin is allowed; true if it is (or the request has no origin). */
    public boolean cors(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader("Origin");
        if (origin == null) return true;
        boolean allowed = corsOrigins.contains("*") || corsOrigins.contains(origin);
        if (allowed) {
            response.setHeader("Access-Control-Allow-Origin", corsOrigins.contains("*") ? "*" : origin);
            response.addHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
            response.setHeader("Access-Control-Max-Age", "1800");
        }
        return allowed;
    }

    /** {@code scheme://host[:port]<context path>} of a request. */
    public static String baseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = "http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443;
        return scheme + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port) + request.getContextPath();
    }

    static List<Map<String, Object>> errors(String... messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String m : messages) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("message", m);
            out.add(e);
        }
        return out;
    }

    static List<Map<String, Object>> errors(List<? extends GraphQLError> errors) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (GraphQLError e : errors) out.add(e.toSpecification());
        return out;
    }

    private static Resource ensure(ResourceResolver resolver, Resource parent, String name, String type) throws PersistenceException {
        Resource child = parent.getChild(name);
        if (child != null) return child;
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", type);
        return resolver.create(parent, name, props);
    }
}
