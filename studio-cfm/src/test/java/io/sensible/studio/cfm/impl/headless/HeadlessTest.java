package io.sensible.studio.cfm.impl.headless;

import io.sensible.studio.cfm.impl.Json;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GraphQL API over the real WKND shared content (30 fragments, 3 models) and the nine persisted queries the
 * WKND headless apps use, as shipped in the wknd-shared package (MIT).
 */
@ExtendWith(SlingContextExtension.class)
class HeadlessTest {

    private static final String DAM = "/content/dam/wknd-shared";
    private static final String MODELS = "/conf/wknd-shared/settings/dam/cfm/models";
    private static final String BALI = DAM + "/en/adventures/bali-surf-camp/bali-surf-camp";
    private static final String ALASKA = DAM + "/en/magazine/alaska-adventure/alaskan-adventures";
    private static final String BASE = "https://author.example.com";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private HeadlessService headless;
    private Map<String, String> persisted;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        context.load().json("/wknd-shared-dam.json", DAM);
        context.load().json("/wknd-shared-models.json", MODELS);
        context.create().resource("/content/cq:graphql/wknd-shared/endpoint",
                "jcr:primaryType", "nt:unstructured", "sling:resourceType", "graphql/sites/components/endpoint");
        try (InputStream in = getClass().getResourceAsStream("/wknd-shared-graphql.json")) {
            Map<String, Object> g = (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            persisted = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) g.get("persistedQueries")).entrySet()) {
                persisted.put(e.getKey(), (String) e.getValue());
                String path = HeadlessService.persistedPath("wknd-shared", e.getKey());
                context.create().resource(path, "jcr:primaryType", "nt:unstructured", "sling:resourceType", "graphql/persistent/query");
                context.create().resource(path + "/jcr:content", "jcr:primaryType", "nt:unstructured", "jcr:data", e.getValue(),
                        "jcr:mimeType", "text/html");
            }
        }
        createImages();
        headless = new HeadlessService();
    }

    /** The images the fragments reference (the fixture has the fragments only). */
    private void createImages() throws Exception {
        Set<String> images = new LinkedHashSet<>();
        collectReferences(context.resourceResolver().getResource(DAM), images);
        byte[] jpeg = jpeg(1600, 900);
        for (String path : images) {
            if (context.resourceResolver().getResource(path) != null) continue;
            context.create().resource(path, "jcr:primaryType", "dam:Asset");
            context.create().resource(path + "/jcr:content", "jcr:primaryType", "dam:AssetContent");
            context.create().resource(path + "/jcr:content/metadata", "jcr:primaryType", "nt:unstructured",
                    "dam:MIMEtype", "image/jpeg", "tiff:ImageWidth", 1600L, "tiff:ImageLength", 900L, "dam:size", (long) jpeg.length);
            context.create().resource(path + "/jcr:content/renditions", "jcr:primaryType", "nt:folder");
            context.load().binaryFile(new ByteArrayInputStream(jpeg), path + "/jcr:content/renditions/original", "image/jpeg");
        }
    }

    private static void collectReferences(Resource r, Set<String> out) {
        for (Object v : r.getValueMap().values()) {
            if (v instanceof String && ((String) v).startsWith("/content/dam/") && ((String) v).matches(".*\\.(jpe?g|png)$")) out.add((String) v);
        }
        for (Resource c : r.getChildren()) collectReferences(c, out);
    }

    private static byte[] jpeg(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 90, 140));
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private ResourceResolver resolver() {
        return context.resourceResolver();
    }

    private Map<String, Object> run(String query, Map<String, Object> variables) {
        return headless.execute(resolver(), "wknd-shared", query, variables, null, BASE);
    }

    /** Runs a WKND persisted query with string variables, the way the execute URL passes them. */
    private Map<String, Object> persistedCall(String name, String... kv) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) raw.put(kv[i], kv[i + 1]);
        String query = headless.persistedQuery(resolver(), "wknd-shared", name);
        assertNotNull(query, name);
        Map<String, Object> result = run(query, HeadlessService.coerceVariables(query, null, raw));
        assertNull(result.get("errors"), () -> name + ": " + Json.write(result.get("errors")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object o, Object... path) {
        Object cur = o;
        for (Object p : path) {
            if (cur == null) return null;
            cur = p instanceof Integer ? ((List<Object>) cur).get((Integer) p) : ((Map<String, Object>) cur).get(p);
        }
        return (T) cur;
    }

    // ------------------------------------------------------------------------------------------- schema

    @Test
    void schemaHasTheQueriesAndTypesOfTheWkndModels() {
        graphql.schema.GraphQLSchema schema = headless.schema(resolver(), "wknd-shared");
        for (String q : new String[]{"adventureByPath", "adventureList", "adventurePaginated", "articleByPath", "articleList",
                "articlePaginated", "authorByPath", "authorList", "authorPaginated"}) {
            assertNotNull(schema.getQueryType().getFieldDefinition(q), q);
        }
        assertNotNull(schema.getObjectType("AdventureModel").getFieldDefinition("primaryImage"));
        assertEquals("Float", ((graphql.schema.GraphQLNamedType) schema.getObjectType("AdventureModel").getFieldDefinition("price").getType()).getName());
        assertEquals("Int", ((graphql.schema.GraphQLNamedType) schema.getObjectType("AdventureModel").getFieldDefinition("groupSize").getType()).getName());
        assertEquals("AuthorModel", ((graphql.schema.GraphQLNamedType) schema.getObjectType("ArticleModel").getFieldDefinition("authorFragment").getType()).getName());
        assertNotNull(schema.getType("FloatOperator"));
        assertNotNull(schema.getType("AssetTransformFormat"));
    }

    @Test
    void everyWkndPersistedQueryIsValid() {
        assertEquals(9, persisted.size());
        for (Map.Entry<String, String> e : persisted.entrySet()) {
            assertEquals(List.of(), headless.validate(resolver(), "wknd-shared", e.getValue()), e.getKey());
        }
    }

    @Test
    void introspectionWorks() {
        Map<String, Object> r = run("{ __schema { queryType { name } } __type(name: \"AdventureModel\") { fields { name } } }", null);
        assertNull(r.get("errors"));
        assertEquals("Query", at(r, "data", "__schema", "queryType", "name"));
        List<Map<String, Object>> fields = at(r, "data", "__type", "fields");
        assertTrue(fields.stream().anyMatch(f -> "itinerary".equals(f.get("name"))));
    }

    // ------------------------------------------------------------------------------------------- persisted queries

    @Test
    void adventureByPath() {
        Map<String, Object> r = persistedCall("adventure-by-path", "adventurePath", BALI);
        Map<String, Object> item = at(r, "data", "adventureByPath", "item");
        assertEquals("Bali Surf Camp", item.get("title"));
        assertEquals("bali-surf-camp", item.get("slug"));
        assertEquals(5000.0, item.get("price"));
        assertTrue(at(item, "description", "html").toString().startsWith("<p>Surfing in Bali"));
        assertTrue(at(item, "description", "plaintext").toString().startsWith("Surfing in Bali"));
        List<Object> json = at(item, "description", "json");
        assertEquals("paragraph", at(json, 0, "nodeType"));
        assertEquals("link", at(json, 1, "content", 1, "nodeType"));
        assertEquals("Surf Camp Costa Rica", at(json, 1, "content", 1, "value"));
        String url = at(item, "primaryImage", "_dynamicUrl");
        assertTrue(url.startsWith("/adobe/dynamicmedia/deliver/dm-aid--"), url);
        assertTrue(url.contains("/adobestock-175749320.jpg?"), url);
        assertTrue(url.contains("preferwebp=true") && url.contains("width=1200") && url.contains("quality=80"), url);
        assertEquals(DAM + "/en/adventures/bali-surf-camp/adobestock-175749320.jpg", at(item, "primaryImage", "_path"));
    }

    @Test
    void adventureBySlugWithReferences() {
        Map<String, Object> r = persistedCall("adventure-by-slug", "slug", "bali-surf-camp");
        List<Object> items = at(r, "data", "adventureList", "items");
        assertEquals(1, items.size());
        List<Map<String, Object>> refs = at(r, "data", "adventureList", "_references");
        assertTrue(refs.stream().anyMatch(m -> "surf-camp-costa-rica".equals(m.get("slug")) && "AdventureModel".equals(m.get("__typename"))),
                () -> Json.write(refs));
    }

    @Test
    void adventuresAllWithPagingAndSort() {
        List<Object> all = at(persistedCall("adventures-all"), "data", "adventureList", "items");
        assertEquals(16, all.size());
        List<Object> page = at(persistedCall("adventures-all", "offset", "10", "limit", "5"), "data", "adventureList", "items");
        assertEquals(5, page.size());
        assertEquals((Object) at(all, 10, "_path"), at(page, 0, "_path"));
        List<Object> byPrice = at(persistedCall("adventures-all", "sort", "price DESC, title ASC"), "data", "adventureList", "items");
        assertEquals(5000.0, at(byPrice, 0, "price"));
        assertEquals("Cycling Tuscany", at(byPrice, 1, "title"));
        assertEquals("West Coast Cycling", at(byPrice, 2, "title"));
    }

    @Test
    void adventuresByActivityIgnoresCase() {
        List<Object> items = at(persistedCall("adventures-by-activity", "activity", "surfing"), "data", "adventureList", "items");
        assertEquals(2, items.size());
        assertEquals("Bali Surf Camp", at(items, 0, "title"));
        assertEquals("Surfing", at(items, 1, "activity"));
    }

    @Test
    void adventureByPriceWithOperatorVariable() {
        List<Object> items = at(persistedCall("adventure-by-price", "price", "1500", "priceOperator", "LOWER"), "data", "adventureList", "items");
        List<Double> prices = new ArrayList<>();
        for (Object o : items) prices.add(at(o, "price"));
        assertEquals(List.of(95.0, 152.59, 300.0, 400.0, 500.0, 900.0, 1000.0), prices);
        List<Object> atLeast = at(persistedCall("adventure-by-price", "price", "1500", "priceOperator", "GREATER_EQUAL"), "data", "adventureList", "items");
        assertEquals(9, atLeast.size());
    }

    @Test
    void articlesPaginated() {
        Map<String, Object> first = at(persistedCall("articles-all", "first", "3"), "data", "articlePaginated");
        List<Object> edges = at(first, "edges");
        assertEquals(3, edges.size());
        assertEquals(Boolean.TRUE, at(first, "pageInfo", "hasNextPage"));
        assertNotNull(at(edges, 0, "node", "authorFragment", "firstName"));
        Map<String, Object> second = at(persistedCall("articles-all", "first", "3", "after", at(first, "pageInfo", "endCursor")),
                "data", "articlePaginated");
        List<Object> next = at(second, "edges");
        assertEquals(3, next.size());
        assertNotEquals((Object) at(edges, 2, "node", "_path"), at(next, 0, "node", "_path"));
        Map<String, Object> last = at(persistedCall("articles-all", "first", "3", "after", at(second, "pageInfo", "endCursor")),
                "data", "articlePaginated");
        assertEquals(1, ((List<?>) at(last, "edges")).size());
        assertEquals(Boolean.FALSE, at(last, "pageInfo", "hasNextPage"));
    }

    @Test
    void articleByAuthorFiltersOnTheReferencedFragment() {
        List<Object> items = at(persistedCall("article-by-author", "authorFirstName", "Stacey", "authorLastName", "Roswells"),
                "data", "articleList", "items");
        assertEquals(1, items.size());
        assertEquals("Roswells", at(items, 0, "authorFragment", "lastName"));
        assertTrue(at(items, 0, "featuredImage", "_dynamicUrl").toString().contains(".png?"), "PNG requested by the query");
    }

    @Test
    void articleByPathAndSlug() {
        Map<String, Object> byPath = at(persistedCall("article-by-path", "articlePath", ALASKA), "data", "articleByPath", "item");
        assertEquals("alaskan-adventures", byPath.get("slug"));
        assertEquals("Ian", at(byPath, "authorFragment", "firstName"));
        List<Object> main = at(byPath, "main", "json");
        assertFalse(main.isEmpty());
        List<Object> bySlug = at(persistedCall("article-by-slug", "slug", "alaskan-adventures"), "data", "articleList", "items");
        assertEquals(ALASKA, at(bySlug, 0, "_path"));
    }

    // ------------------------------------------------------------------------------------------- ad hoc queries

    @Test
    void filtersOnArraysDatesAndSystemFields() {
        Map<String, Object> r = run("{ authorList(filter: {occupations: {_expressions: [{value: \"Photographer\"}]}}, sort: \"lastName\") "
                + "{ items { lastName occupations birthDay _model { title } _variation _locale } } }", null);
        assertNull(r.get("errors"), () -> Json.write(r.get("errors")));
        List<Object> items = at(r, "data", "authorList", "items");
        assertEquals(4, items.size());
        assertEquals("Author", at(items, 0, "_model", "title"));
        assertEquals("master", at(items, 0, "_variation"));
        assertEquals("en", at(items, 0, "_locale"));

        Map<String, Object> all = run("{ authorList(filter: {occupations: {_expressions: [{value: \"Photographer\", _apply: ALL}]}}) { items { lastName } } }", null);
        assertEquals(0, ((List<?>) at(all, "data", "authorList", "items")).size());

        Map<String, Object> like = run("{ adventureList(filter: {title: {_expressions: [{value: \"%Cycling%\", _operator: LIKE}]}, "
                + "_logOp: AND, price: {_expressions: [{value: 4000, _operator: GREATER}]}}) { items { title } } }", null);
        assertEquals(2, ((List<?>) at(like, "data", "adventureList", "items")).size(), () -> Json.write(like));

        Map<String, Object> or = run("{ adventureList(filter: {_logOp: OR, activity: {_expressions: [{value: \"Camping\"}]}, "
                + "slug: {_expressions: [{value: \"tahoe-skiing\"}]}}) { items { slug } } }", null);
        assertEquals(3, ((List<?>) at(or, "data", "adventureList", "items")).size());

        Map<String, Object> path = run("{ adventureList(filter: {_path: {_expressions: [{value: \"" + DAM + "/en/adventures/bali\", _operator: STARTS_WITH}]}}) { items { _path } } }", null);
        assertEquals(1, ((List<?>) at(path, "data", "adventureList", "items")).size());
    }

    @Test
    void variationsFallBackToMaster() throws Exception {
        // a variation with one changed element
        context.create().resource(BALI + "/jcr:content/data/summer", "jcr:primaryType", "nt:unstructured", "title", "Bali in Summer");
        Map<String, Object> r = run("{ adventureByPath(_path: \"" + BALI + "\", variation: \"summer\") { item { title slug _variation _variations } } }", null);
        assertEquals("Bali in Summer", at(r, "data", "adventureByPath", "item", "title"));
        assertEquals("bali-surf-camp", at(r, "data", "adventureByPath", "item", "slug"));
        assertEquals("summer", at(r, "data", "adventureByPath", "item", "_variation"));
        assertEquals(List.of("summer"), at(r, "data", "adventureByPath", "item", "_variations"));
        Map<String, Object> missing = run("{ adventureByPath(_path: \"" + BALI + "\", variation: \"winter\") { item { title _variation } } }", null);
        assertEquals("master", at(missing, "data", "adventureByPath", "item", "_variation"));
        Map<String, Object> listed = run("{ adventureList(includeVariations: true, filter: {slug: {_expressions: [{value: \"bali-surf-camp\"}]}}) { items { _variation } } }", null);
        assertEquals(2, ((List<?>) at(listed, "data", "adventureList", "items")).size());
    }

    @Test
    void referencesAndUrls() {
        Map<String, Object> r = run("{ articleByPath(_path: \"" + ALASKA + "\") { item { featuredImage { __typename ... on ImageRef { _path _authorUrl _publishUrl width height mimeType _dynamicUrl } } } _references { __typename ... on AuthorModel { lastName } ... on ImageRef { _path } } } }", null);
        assertNull(r.get("errors"), () -> Json.write(r.get("errors")));
        Map<String, Object> img = at(r, "data", "articleByPath", "item", "featuredImage");
        assertEquals("ImageRef", img.get("__typename"));
        assertEquals(BASE + img.get("_path"), img.get("_authorUrl"));
        assertEquals(1600, img.get("width"));
        assertTrue(img.get("_dynamicUrl").toString().endsWith("/alaskan-grizzly.jpg"), "no transform, no parameters");
        List<Map<String, Object>> refs = at(r, "data", "articleByPath", "_references");
        assertTrue(refs.stream().anyMatch(m -> "Provo".equals(m.get("lastName"))));
        assertTrue(refs.stream().anyMatch(m -> "ImageRef".equals(m.get("__typename"))));
    }

    @Test
    void unknownPathsAndBadCursorsAreHandled() {
        Map<String, Object> r = run("{ adventureByPath(_path: \"/content/dam/nope\") { item { title } } }", null);
        assertNull(r.get("errors"));
        assertNull(at(r, "data", "adventureByPath", "item"));
        Map<String, Object> wrongModel = run("{ authorByPath(_path: \"" + BALI + "\") { item { lastName } } }", null);
        assertNull(at(wrongModel, "data", "authorByPath", "item"));
        Map<String, Object> bad = run("{ articlePaginated(after: \"garbage\") { edges { cursor } } }", null);
        assertNotNull(bad.get("errors"));
        Map<String, Object> invalid = run("{ adventureList { items { nope } } }", null);
        assertNotNull(invalid.get("errors"));
    }

    @Test
    void depthIsLimited() {
        StringBuilder q = new StringBuilder("{ articleList { items { authorFragment { profilePicture { ... on ImageRef { _path } } } } } }");
        assertNull(run(q.toString(), null).get("errors"));
        String deep = "{ __schema { types { fields { type { ofType { ofType { ofType { ofType { ofType { ofType { ofType { ofType { ofType { ofType { ofType { ofType { name } } } } } } } } } } } } } } } }";
        assertNotNull(run(deep, null).get("errors"));
    }

    @Test
    void globalEndpointHasEveryModel() {
        Map<String, Object> r = headless.execute(resolver(), null, "{ authorList { items { firstName } } }", null, null, BASE);
        assertEquals(7, ((List<?>) at(r, "data", "authorList", "items")).size());
    }

    // ------------------------------------------------------------------------------------------- persisted query http

    @Test
    void parsesExecuteUrls() {
        PersistedQueryServlet.Call c = PersistedQueryServlet.Call.parse(
                "/graphql/execute.json/wknd-shared/adventure-by-slug;slug=bali-surf-camp;imageWidth=800", "");
        assertEquals("wknd-shared", c.conf);
        assertEquals("adventure-by-slug", c.name);
        assertEquals(Map.of("slug", "bali-surf-camp", "imageWidth", "800"), c.variables);
        PersistedQueryServlet.Call encoded = PersistedQueryServlet.Call.parse(
                "/ctx/graphql/execute.json/wknd-shared/adventure-by-path%3BadventurePath%3D%2Fcontent%2Fdam%2Fx%20y", "/ctx");
        assertEquals("adventure-by-path", encoded.name);
        assertEquals("/content/dam/x y", encoded.variables.get("adventurePath"));
        assertNull(PersistedQueryServlet.Call.parse("/graphql/execute.json/only-one", ""));
        assertNull(PersistedQueryServlet.Call.parse("/graphql/execute.json/../../etc/x", ""));

        Map<String, Object> typed = HeadlessService.coerceVariables(persisted.get("adventure-by-price"), null,
                Map.of("price", "1200", "priceOperator", "LOWER", "imageWidth", "640"));
        assertEquals(1200.0, typed.get("price"));
        assertEquals("LOWER", typed.get("priceOperator"));
        assertEquals(640, typed.get("imageWidth"));
    }

    @Test
    void persistsListsAndValidates() throws Exception {
        String q = "query($slug: String!) { adventureList(filter: {slug: {_expressions: [{value: $slug}]}}) { items { title } } }";
        Map<String, Object> created = headless.persist(resolver(), "wknd-shared", "my-query", q, "max-age=300", false);
        assertEquals("create", created.get("action"));
        assertEquals("/wknd-shared/my-query", created.get("shortPath"));
        assertEquals(q, headless.persistedQuery(resolver(), "wknd-shared", "my-query"));
        assertEquals("max-age=300", headless.cacheControl(resolver(), "wknd-shared", "my-query"));
        assertEquals("max-age=60", headless.cacheControl(resolver(), "wknd-shared", "adventures-all"));
        assertThrows(IllegalStateException.class, () -> headless.persist(resolver(), "wknd-shared", "my-query", q, null, false));
        assertEquals("update", headless.persist(resolver(), "wknd-shared", "my-query", q, null, true).get("action"));
        assertEquals(10, headless.listPersisted(resolver(), "wknd-shared").size());
        assertFalse(headless.validate(resolver(), "wknd-shared", "{ nope }").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> headless.persist(resolver(), "wknd-shared", "../x", q, null, false));
    }

    @Test
    void endpointServletAnswersPosts() throws Exception {
        GraphQLEndpointServlet servlet = new GraphQLEndpointServlet(headless);
        MockSlingHttpServletRequest request = context.request();
        request.setResource(resolver().getResource("/content/cq:graphql/wknd-shared/endpoint"));
        request.setMethod("POST");
        request.setContentType("application/json");
        request.setContent(("{\"query\":\"query($p: String!) { adventureByPath(_path: $p) { item { title } } }\","
                + "\"variables\":{\"p\":\"" + BALI + "\"}}").getBytes(StandardCharsets.UTF_8));
        MockSlingHttpServletResponse response = context.response();
        servlet.service(request, response);
        assertEquals(200, response.getStatus());
        assertEquals("{\"data\":{\"adventureByPath\":{\"item\":{\"title\":\"Bali Surf Camp\"}}}}", response.getOutputAsString());
        assertNull(GraphQLEndpointServlet.conf(new org.apache.sling.api.resource.SyntheticResource(resolver(), "/content/graphql/global/endpoint", "x")));
    }

    // ------------------------------------------------------------------------------------------- images

    @Test
    void routesTheDynamicUrlsSlingCannotResolveToAPathServlet() throws Exception {
        String path = DAM + "/en/adventures/bali-surf-camp/adobestock-175749320.jpg";
        AssetRef ref = AssetRef.of(resolver().getResource(path));
        MockSlingHttpServletRequest request = new MockSlingHttpServletRequest(resolver(), context.bundleContext());
        request.setMethod("GET");
        // as GraphQL's _dynamicUrl: no extension right after "deliver", so Sling sees a non-existing resource
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) request.getRequestPathInfo())
                .setResourcePath("/adobe/dynamicmedia/deliver/" + ref.id() + "/bali-surf");
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) request.getRequestPathInfo()).setExtension("jpg");
        Map<String, Object> params = new HashMap<>();
        params.put("width", "320");
        request.setParameterMap(params);
        MockSlingHttpServletResponse response = new MockSlingHttpServletResponse();
        boolean[] passedOn = {false};
        new DynamicMediaFilter().doFilter(request, response, (rq, rs) -> passedOn[0] = true);
        assertFalse(passedOn[0]);
        assertEquals(200, response.getStatus());
        assertEquals(320, ImageIO.read(new ByteArrayInputStream(response.getOutput())).getWidth());

        MockSlingHttpServletRequest other = new MockSlingHttpServletRequest(resolver(), context.bundleContext());
        ((org.apache.sling.servlethelpers.MockRequestPathInfo) other.getRequestPathInfo()).setResourcePath("/content/wknd/us/en");
        new DynamicMediaFilter().doFilter(other, new MockSlingHttpServletResponse(), (rq, rs) -> passedOn[0] = true);
        assertTrue(passedOn[0], "other requests go on");
    }

    @Test
    void deliversScaledImages() throws Exception {
        String path = DAM + "/en/adventures/bali-surf-camp/adobestock-175749320.jpg";
        AssetRef ref = AssetRef.of(resolver().getResource(path));
        String id = ref.id().substring(AssetRef.ID_PREFIX.length());
        assertEquals(path, DynamicMediaServlet.asset(resolver(), id).getPath());
        assertNull(DynamicMediaServlet.asset(resolver(), "studio-" + java.util.Base64.getUrlEncoder().encodeToString("/etc/passwd".getBytes())));
        assertNull(DynamicMediaServlet.asset(resolver(), "1' OR '1'='1"));

        DynamicMediaServlet servlet = new DynamicMediaServlet();
        MockSlingHttpServletRequest request = context.request();
        request.setMethod("GET");
        context.requestPathInfo().setResourcePath("/adobe/dynamicmedia/deliver");
        context.requestPathInfo().setSuffix("/" + ref.id() + "/bali.jpg");
        Map<String, Object> params = new HashMap<>();
        params.put("width", "400");
        params.put("quality", "70");
        request.setParameterMap(params);
        MockSlingHttpServletResponse response = context.response();
        servlet.service(request, response);
        assertEquals(200, response.getStatus());
        assertEquals("image/jpeg", response.getContentType());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(response.getOutput()));
        assertEquals(400, out.getWidth());
        assertEquals(225, out.getHeight());

        DynamicMediaServlet.Options never = new DynamicMediaServlet.Options();
        never.width = 4000;
        assertEquals(1600, never.apply(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)).getWidth(), "never enlarged");
        DynamicMediaServlet.Options rotate = new DynamicMediaServlet.Options();
        rotate.rotate = 90;
        assertEquals(900, rotate.apply(new BufferedImage(1600, 900, BufferedImage.TYPE_INT_RGB)).getWidth());
    }

    // ------------------------------------------------------------------------------------------- rich text

    @Test
    void richTextFormats() {
        RichText html = new RichText("text/html", "<h2>Gear</h2><p>Bring <b>boards</b> and <a href=\"/content/dam/x\" target=\"_blank\">wax</a><br>now</p><ul><li>Shorts</li><li><i>Towel</i></li></ul>");
        List<Object> json = html.json();
        assertEquals("header", at(json, 0, "nodeType"));
        assertEquals("h2", at(json, 0, "style"));
        assertEquals(List.of("bold"), at(json, 1, "content", 1, "format", "variants"));
        assertEquals("/content/dam/x", at(json, 1, "content", 3, "data", "href"));
        assertEquals("_blank", at(json, 1, "content", 3, "data", "target"));
        assertEquals("line-break", at(json, 1, "content", 4, "nodeType"));
        assertEquals("unordered-list", at(json, 2, "nodeType"));
        assertEquals("list-item", at(json, 2, "content", 1, "nodeType"));
        assertEquals("Gear\nBring boards and wax\nnow\nShorts\nTowel", html.plaintext());
        assertEquals("## Gear\n\nBring **boards** and [wax](/content/dam/x)  \nnow\n\n- Shorts\n- _Towel_", html.markdown());
        assertEquals(List.of("/content/dam/x"), html.repositoryLinks());

        RichText md = new RichText("text/x-markdown", "# Title\n\nSome *em* and **strong** [link](/x).\n\n- a\n- b\n\n1. one");
        assertEquals("<h1>Title</h1><p>Some <em>em</em> and <strong>strong</strong> <a href=\"/x\">link</a>.</p><ul><li>a</li><li>b</li></ul><ol><li>one</li></ol>", md.html());
        RichText plain = new RichText("text/plain", "line one\nline <two>\n\nsecond");
        assertEquals("<p>line one<br>line &lt;two&gt;</p><p>second</p>", plain.html());
        assertEquals("line one\nline <two>\n\nsecond", plain.plaintext());
    }

    @Test
    void namesFollowAem() {
        assertEquals("Adventure", GqlModel.typeBase("Adventure", "adventure"));
        assertEquals("MyBlogPost", GqlModel.typeBase("my blog post", "x"));
        assertEquals("Faq", GqlModel.typeBase("123", "faq"));
        assertEquals("first_name", GqlModel.graphqlName("first-name").replace('_', '_'));
        assertEquals("_1st", GqlModel.graphqlName("1st"));
    }

    // ------------------------------------------------------------------------------------------- fixture

    /** Records responses for the author UI's tests (mvn test -Dstudio.cf.dump=<dir>). */
    @Test
    void dumpFixture() throws Exception {
        String dir = System.getProperty("studio.cf.dump");
        if (dir == null) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoints", io.sensible.studio.cfm.impl.ContentFragmentServletAccess.endpoints(resolver()));
        out.put("persisted", headless.listPersisted(resolver(), null));
        Map<String, Object> schema = run(SCHEMA, null);
        assertNull(schema.get("errors"), () -> Json.write(schema.get("errors")));
        out.put("schema", schema);
        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, String[]> vars = new LinkedHashMap<>();
        vars.put("adventure-by-path", new String[]{"adventurePath", BALI});
        vars.put("adventure-by-slug", new String[]{"slug", "bali-surf-camp"});
        vars.put("adventure-by-price", new String[]{"price", "1500", "priceOperator", "LOWER"});
        vars.put("adventures-all", new String[0]);
        vars.put("adventures-by-activity", new String[]{"activity", "Surfing"});
        vars.put("article-by-author", new String[]{"authorFirstName", "Stacey", "authorLastName", "Roswells"});
        vars.put("article-by-path", new String[]{"articlePath", ALASKA});
        vars.put("article-by-slug", new String[]{"slug", "alaskan-adventures"});
        vars.put("articles-all", new String[]{"first", "3"});
        for (Map.Entry<String, String[]> e : vars.entrySet()) responses.put(e.getKey(), persistedCall(e.getKey(), e.getValue()));
        out.put("responses", responses);
        java.nio.file.Path file = java.nio.file.Paths.get(dir, "wknd-shared-graphql.json");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.write(file, Json.write(out).getBytes(StandardCharsets.UTF_8));
    }

    private static final String SCHEMA = "{ __schema { types { name kind description fields { name type { name kind ofType { name kind ofType { name kind } } } } } } }";
}
