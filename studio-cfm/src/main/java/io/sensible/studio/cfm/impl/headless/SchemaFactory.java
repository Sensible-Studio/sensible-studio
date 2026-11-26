package io.sensible.studio.cfm.impl.headless;

import graphql.execution.DataFetcherResult;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.DataFetcherFactories;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.FieldCoordinates;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLCodeRegistry;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.GraphQLUnionType;
import graphql.schema.PropertyDataFetcher;
import graphql.schema.TypeResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static graphql.Scalars.GraphQLBoolean;
import static graphql.Scalars.GraphQLFloat;
import static graphql.Scalars.GraphQLID;
import static graphql.Scalars.GraphQLInt;
import static graphql.Scalars.GraphQLString;

/**
 * Builds the GraphQL schema of an endpoint from its content fragment models, in AEM's shape: per model an object
 * type ({@code AdventureModel}), a filter input ({@code AdventureModelFilter}) and the queries
 * {@code adventureByPath}, {@code adventureList} and {@code adventurePaginated}; shared types for multi-line text
 * ({@code MultilineString}), content references ({@code ImageRef}, {@code DocumentRef}, ...), filters and
 * {@code _assetTransform}.
 */
final class SchemaFactory {

    static final String REFERENCE = "Reference";
    static final String ALL_MODELS = "AllFragmentModels";
    static final String ALL_REFERENCES = "AllReferences";

    private static final Set<String> SYSTEM_FIELDS = new HashSet<>(java.util.Arrays.asList(
            "_path", "_id", "_variation", "_variations", "_model", "_metadata", "_tags", "_locale"));

    private final Collection<GqlModel> models;
    private final Map<String, GqlModel> byPath = new LinkedHashMap<>();
    private final GraphQLCodeRegistry.Builder code = GraphQLCodeRegistry.newCodeRegistry();
    private final Set<GraphQLType> types = new LinkedHashSet<>();

    private GraphQLScalarType calendar;
    private GraphQLScalarType date;
    private GraphQLScalarType time;
    private GraphQLScalarType json;

    private SchemaFactory(Collection<GqlModel> models) {
        this.models = models;
        for (GqlModel m : models) byPath.put(m.path, m);
    }

    static GraphQLSchema build(Collection<GqlModel> models) {
        return new SchemaFactory(models).schema();
    }

    private GraphQLSchema schema() {
        code.defaultDataFetcher(DataFetcherFactories.useDataFetcher(SchemaFactory::generic));
        scalars();
        enums();
        inputs();
        outputs();

        List<GraphQLObjectType> modelTypes = new ArrayList<>();
        for (GqlModel m : models) modelTypes.add(modelType(m));
        types.addAll(modelTypes);

        TypeResolver resolver = env -> {
            Object o = env.getObject();
            String name = o instanceof FragmentView ? ((FragmentView) o).model.typeName
                    : o instanceof AssetRef ? ((AssetRef) o).typeName : null;
            return name == null ? null : env.getSchema().getObjectType(name);
        };
        GraphQLUnionType.Builder all = GraphQLUnionType.newUnionType().name(ALL_REFERENCES)
                .description("Everything an item can reference: fragments of any model, assets and pages.");
        for (String ref : new String[]{AssetRef.IMAGE, AssetRef.DOCUMENT, AssetRef.MULTIMEDIA, AssetRef.ARCHIVE, AssetRef.PAGE}) {
            all.possibleType(GraphQLTypeReference.typeRef(ref));
        }
        for (GraphQLObjectType t : modelTypes) all.possibleType(t);
        types.add(all.build());
        code.typeResolver(ALL_REFERENCES, resolver);
        code.typeResolver(REFERENCE, resolver);
        if (!modelTypes.isEmpty()) {
            GraphQLUnionType.Builder u = GraphQLUnionType.newUnionType().name(ALL_MODELS)
                    .description("A fragment of any model of this endpoint.");
            for (GraphQLObjectType t : modelTypes) u.possibleType(t);
            types.add(u.build());
            code.typeResolver(ALL_MODELS, resolver);
        }

        GraphQLObjectType.Builder query = GraphQLObjectType.newObject().name("Query");
        for (GqlModel m : models) {
            types.add(filterType(m));
            queries(m, query);
        }
        if (models.isEmpty()) {
            query.field(field("_endpoint", GraphQLString).description("This endpoint has no content fragment models yet."));
            onQuery("_endpoint", env -> "empty");
        }
        return GraphQLSchema.newSchema().query(query.build()).additionalTypes(types).codeRegistry(code.build()).build();
    }

    // ------------------------------------------------------------------------------------------- shared types

    private void scalars() {
        calendar = stringScalar("Calendar", "Date and time with offset, ISO 8601 (2022-06-01T10:43:38.015-07:00).");
        date = stringScalar("Date", "A date, ISO 8601 (2022-06-01).");
        time = stringScalar("Time", "A time of day, ISO 8601 (10:43:38).");
        json = GraphQLScalarType.newScalar().name("Json").description("Any JSON value.").coercing(new JsonCoercing()).build();
        types.add(calendar);
        types.add(date);
        types.add(time);
        types.add(json);
    }

    private static GraphQLScalarType stringScalar(String name, String description) {
        return GraphQLScalarType.newScalar().name(name).description(description).coercing(new StringCoercing(name)).build();
    }

    private void enums() {
        types.add(enumType("LogOp", "AND", "OR"));
        types.add(enumType("StringOperator", "EQUALS", "EQUALS_NOT", "CONTAINS", "CONTAINS_NOT", "STARTS_WITH", "LIKE"));
        String[] numeric = {"EQUALS", "EQUALS_NOT", "GREATER", "GREATER_EQUAL", "LOWER", "LOWER_EQUAL"};
        types.add(enumType("IntOperator", numeric));
        types.add(enumType("FloatOperator", numeric));
        types.add(enumType("BooleanOperator", "EQUALS", "EQUALS_NOT"));
        String[] temporal = {"EQUALS", "EQUALS_NOT", "BEFORE", "AT_OR_BEFORE", "AFTER", "AT_OR_AFTER"};
        types.add(enumType("CalendarOperator", temporal));
        types.add(enumType("DateOperator", temporal));
        types.add(enumType("TimeOperator", temporal));
        types.add(enumType("ArrayMode", "AT_LEAST_ONCE", "ALL", "ALL_OR_EMPTY", "NONE"));
        types.add(enumType("AssetTransformFormat", "GIF", "PNG", "PNG8", "JPG", "PJPG", "BJPG", "WEBP", "WEBPLL", "WEBPLY"));
        types.add(enumType("AssetTransformRotation", "R90", "R180", "R270"));
        types.add(enumType("AssetTransformFlip", "HORIZONTAL", "VERTICAL", "HORIZONTAL_AND_VERTICAL"));
    }

    private static GraphQLEnumType enumType(String name, String... values) {
        GraphQLEnumType.Builder b = GraphQLEnumType.newEnum().name(name);
        for (String v : values) b.value(v);
        return b.build();
    }

    private void inputs() {
        filters("String", GraphQLString, "StringOperator", true);
        filters("Int", GraphQLInt, "IntOperator", false);
        filters("Float", GraphQLFloat, "FloatOperator", false);
        filters("Boolean", GraphQLBoolean, "BooleanOperator", false);
        filters("Calendar", calendar, "CalendarOperator", false);
        filters("Date", date, "DateOperator", false);
        filters("Time", time, "TimeOperator", false);

        types.add(GraphQLInputObjectType.newInputObject().name("AssetTransformCrop")
                .field(inputField("xOrigin", GraphQLInt)).field(inputField("yOrigin", GraphQLInt))
                .field(inputField("width", GraphQLInt)).field(inputField("height", GraphQLInt)).build());
        types.add(GraphQLInputObjectType.newInputObject().name("AssetTransformSize")
                .field(inputField("width", GraphQLInt)).field(inputField("height", GraphQLInt)).build());
        types.add(GraphQLInputObjectType.newInputObject().name("AssetTransform")
                .description("Delivery options for images, applied to _dynamicUrl.")
                .field(inputField("format", ref("AssetTransformFormat")))
                .field(inputField("seoName", GraphQLString))
                .field(inputField("crop", ref("AssetTransformCrop")))
                .field(inputField("size", ref("AssetTransformSize")))
                .field(inputField("rotation", ref("AssetTransformRotation")))
                .field(inputField("flip", ref("AssetTransformFlip")))
                .field(inputField("quality", GraphQLInt))
                .field(inputField("width", GraphQLInt))
                .field(inputField("preferWebp", GraphQLBoolean))
                .build());
    }

    /** {@code <T>Filter}/{@code <T>FilterExpression} and the array variants {@code <T>ArrayFilter}. */
    private void filters(String name, GraphQLInputType valueType, String operator, boolean ignoreCase) {
        for (boolean array : new boolean[]{false, true}) {
            String base = name + (array ? "Array" : "") + "Filter";
            GraphQLInputObjectType.Builder expr = GraphQLInputObjectType.newInputObject().name(base + "Expression")
                    .field(inputField("value", valueType))
                    .field(GraphQLInputObjectField.newInputObjectField().name("_operator").type(ref(operator))
                            .defaultValueProgrammatic("EQUALS"));
            if (ignoreCase) {
                expr.field(GraphQLInputObjectField.newInputObjectField().name("_ignoreCase").type(GraphQLBoolean)
                        .defaultValueProgrammatic(false));
            }
            if (array) {
                expr.field(GraphQLInputObjectField.newInputObjectField().name("_apply").type(ref("ArrayMode"))
                        .defaultValueProgrammatic("AT_LEAST_ONCE"));
            }
            types.add(expr.build());
            types.add(GraphQLInputObjectType.newInputObject().name(base)
                    .field(inputField("_logOp", ref("LogOp")))
                    .field(inputField("_expressions", GraphQLList.list(GraphQLNonNull.nonNull(ref(base + "Expression")))))
                    .build());
        }
    }

    private void outputs() {
        types.add(GraphQLObjectType.newObject().name("MultilineString")
                .description("Multi-line text in every format.")
                .field(field("html", GraphQLString))
                .field(field("markdown", GraphQLString))
                .field(field("plaintext", GraphQLString))
                .field(field("json", json))
                .build());
        GraphQLObjectType image = refType(AssetRef.IMAGE, "An image in the DAM.", true);
        types.add(image);
        types.add(refType(AssetRef.DOCUMENT, "A document in the DAM.", false));
        types.add(refType(AssetRef.MULTIMEDIA, "A video or audio file in the DAM.", false));
        types.add(refType(AssetRef.ARCHIVE, "An archive in the DAM.", false));
        types.add(GraphQLObjectType.newObject().name(AssetRef.PAGE).description("A page.")
                .field(field("_path", GraphQLNonNull.nonNull(GraphQLID)))
                .field(field("_authorUrl", GraphQLString))
                .field(field("_publishUrl", GraphQLString)).build());
        types.add(GraphQLUnionType.newUnionType().name(REFERENCE).description("A content reference.")
                .possibleType(image).possibleType(GraphQLTypeReference.typeRef(AssetRef.DOCUMENT))
                .possibleType(GraphQLTypeReference.typeRef(AssetRef.MULTIMEDIA))
                .possibleType(GraphQLTypeReference.typeRef(AssetRef.ARCHIVE))
                .possibleType(GraphQLTypeReference.typeRef(AssetRef.PAGE)).build());

        types.add(GraphQLObjectType.newObject().name("ModelInfo")
                .field(field("_path", GraphQLNonNull.nonNull(GraphQLID))).field(field("title", GraphQLString)).build());
        GraphQLObjectType.Builder meta = GraphQLObjectType.newObject().name("FragmentMetadata");
        Object[][] kinds = {{"string", GraphQLString}, {"stringArray", GraphQLList.list(GraphQLString)}, {"int", GraphQLInt},
                {"float", GraphQLFloat}, {"boolean", GraphQLBoolean}, {"calendar", calendar}};
        for (Object[] k : kinds) {
            String typeName = Character.toUpperCase(((String) k[0]).charAt(0)) + ((String) k[0]).substring(1) + "Metadata";
            types.add(GraphQLObjectType.newObject().name(typeName)
                    .field(field("name", GraphQLNonNull.nonNull(GraphQLString)))
                    .field(field("value", (GraphQLOutputType) k[1])).build());
            meta.field(field(k[0] + "Metadata", GraphQLList.list(GraphQLNonNull.nonNull(ref(typeName)))));
        }
        types.add(meta.build());
        types.add(GraphQLObjectType.newObject().name("PageInfo")
                .field(field("startCursor", GraphQLString)).field(field("endCursor", GraphQLString))
                .field(field("hasNextPage", GraphQLNonNull.nonNull(GraphQLBoolean)))
                .field(field("hasPreviousPage", GraphQLNonNull.nonNull(GraphQLBoolean))).build());
    }

    private static GraphQLObjectType refType(String name, String description, boolean image) {
        GraphQLObjectType.Builder b = GraphQLObjectType.newObject().name(name).description(description)
                .field(field("_path", GraphQLNonNull.nonNull(GraphQLID)))
                .field(field("_authorUrl", GraphQLString))
                .field(field("_publishUrl", GraphQLString));
        if (image) {
            b.field(field("_dynamicUrl", GraphQLString).description("Delivery URL; honours _assetTransform."))
                    .field(field("mimeType", GraphQLString))
                    .field(field("width", GraphQLInt))
                    .field(field("height", GraphQLInt));
        } else {
            b.field(field("format", GraphQLString));
        }
        return b.field(field("size", GraphQLInt)).build();
    }

    // ------------------------------------------------------------------------------------------- models

    private GraphQLObjectType modelType(GqlModel m) {
        GraphQLObjectType.Builder t = GraphQLObjectType.newObject().name(m.typeName)
                .description(m.title + " (" + m.path + ")")
                .field(field("_path", GraphQLNonNull.nonNull(GraphQLID)))
                .field(field("_id", GraphQLString))
                .field(field("_variation", GraphQLString))
                .field(field("_variations", GraphQLList.list(GraphQLString)))
                .field(field("_model", ref("ModelInfo")))
                .field(field("_metadata", ref("FragmentMetadata")))
                .field(field("_tags", GraphQLList.list(GraphQLString)))
                .field(field("_locale", GraphQLString));
        Set<String> used = new HashSet<>(SYSTEM_FIELDS);
        for (GqlModel.Field f : m.fields) {
            if (!used.add(f.gqlName)) continue;
            GraphQLOutputType type = outputType(f);
            if (type != null) t.field(field(f.gqlName, type));
        }
        return t.build();
    }

    private GraphQLOutputType outputType(GqlModel.Field f) {
        GraphQLOutputType one;
        switch (f.kind) {
            case "number": one = f.isDouble() ? GraphQLFloat : GraphQLInt; break;
            case "boolean": one = GraphQLBoolean; break;
            case "date": one = "date".equals(f.dateType) ? date : "time".equals(f.dateType) ? time : calendar; break;
            case "longtext": one = ref("MultilineString"); break;
            case "tags": return GraphQLList.list(GraphQLString);
            case "content-reference": one = ref(REFERENCE); break;
            case "fragment-reference": {
                if (models.isEmpty()) return null;
                GqlModel target = f.allowedModels.size() == 1 ? byPath.get(f.allowedModels.get(0)) : null;
                one = ref(target != null ? target.typeName : ALL_MODELS);
                break;
            }
            case "json": one = json; break;
            default: one = GraphQLString;
        }
        return f.multiple ? GraphQLList.list(one) : one;
    }

    private GraphQLInputObjectType filterType(GqlModel m) {
        GraphQLInputObjectType.Builder b = GraphQLInputObjectType.newInputObject().name(m.typeName + "Filter")
                .field(inputField("_logOp", ref("LogOp")))
                .field(inputField("_path", ref("StringFilter")))
                .field(inputField("_variation", ref("StringFilter")))
                .field(inputField("_locale", ref("StringFilter")))
                .field(inputField("_tags", ref("StringArrayFilter")));
        Set<String> used = new HashSet<>(SYSTEM_FIELDS);
        used.add("_logOp");
        for (GqlModel.Field f : m.fields) {
            if (!used.add(f.gqlName)) continue;
            String base;
            switch (f.kind) {
                case "number": base = f.isDouble() ? "Float" : "Int"; break;
                case "boolean": base = "Boolean"; break;
                case "date": base = "date".equals(f.dateType) ? "Date" : "time".equals(f.dateType) ? "Time" : "Calendar"; break;
                case "json": continue;
                case "fragment-reference": {
                    GqlModel target = f.allowedModels.size() == 1 ? byPath.get(f.allowedModels.get(0)) : null;
                    if (target != null) b.field(inputField(f.gqlName, ref(target.typeName + "Filter")));
                    continue;
                }
                default: base = "String";
            }
            boolean array = f.multiple || "tags".equals(f.kind);
            b.field(inputField(f.gqlName, ref(base + (array ? "Array" : "") + "Filter")));
        }
        return b.build();
    }

    private void queries(GqlModel m, GraphQLObjectType.Builder query) {
        GraphQLTypeReference type = ref(m.typeName);
        GraphQLList refs = GraphQLList.list(ref(ALL_REFERENCES));
        types.add(GraphQLObjectType.newObject().name(m.typeName + "Result")
                .field(field("item", type)).field(field("_references", refs)).build());
        types.add(GraphQLObjectType.newObject().name(m.typeName + "Results")
                .field(field("items", GraphQLNonNull.nonNull(GraphQLList.list(type)))).field(field("_references", refs)).build());
        types.add(GraphQLObjectType.newObject().name(m.typeName + "Edge")
                .field(field("cursor", GraphQLNonNull.nonNull(GraphQLString))).field(field("node", GraphQLNonNull.nonNull(type))).build());
        types.add(GraphQLObjectType.newObject().name(m.typeName + "Connection")
                .field(field("edges", GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(ref(m.typeName + "Edge"))))))
                .field(field("pageInfo", GraphQLNonNull.nonNull(ref("PageInfo")))).build());

        GraphQLArgument.Builder transform = argument("_assetTransform", ref("AssetTransform"));
        query.field(field(m.prefix + "ByPath", ref(m.typeName + "Result"))
                .description("One " + m.title + " fragment by its path.")
                .argument(argument("_path", GraphQLNonNull.nonNull(GraphQLString)))
                .argument(argument("variation", GraphQLString))
                .argument(transform));
        query.field(field(m.prefix + "List", ref(m.typeName + "Results"))
                .description("All " + m.title + " fragments matching the filter.")
                .argument(argument("filter", ref(m.typeName + "Filter")))
                .argument(argument("sort", GraphQLString))
                .argument(argument("offset", GraphQLInt))
                .argument(argument("limit", GraphQLInt))
                .argument(argument("variation", GraphQLString))
                .argument(argument("includeVariations", GraphQLBoolean))
                .argument(argument("_locale", GraphQLString))
                .argument(argument("_assetTransform", ref("AssetTransform"))));
        query.field(field(m.prefix + "Paginated", ref(m.typeName + "Connection"))
                .description(m.title + " fragments, cursor-paginated.")
                .argument(argument("filter", ref(m.typeName + "Filter")))
                .argument(argument("sort", GraphQLString))
                .argument(argument("first", GraphQLInt))
                .argument(argument("after", GraphQLString))
                .argument(argument("variation", GraphQLString))
                .argument(argument("includeVariations", GraphQLBoolean))
                .argument(argument("_locale", GraphQLString))
                .argument(argument("_assetTransform", ref("AssetTransform"))));

        onQuery(m.prefix + "ByPath", env -> {
            HeadlessContext ctx = env.getGraphQlContext().get(HeadlessContext.KEY);
            FragmentView v = ctx.fragment(env.getArgument("_path"), env.getArgument("variation"), m.path);
            return withTransform(new Page(v == null ? new ArrayList<>() : java.util.Collections.singletonList(v), ctx, true), env);
        });
        onQuery(m.prefix + "List", env -> {
            HeadlessContext ctx = env.getGraphQlContext().get(HeadlessContext.KEY);
            List<FragmentView> all = select(m, env, ctx);
            Integer offset = env.getArgument("offset");
            Integer limit = env.getArgument("limit");
            int from = Math.min(all.size(), Math.max(0, offset == null ? 0 : offset));
            int to = limit == null || limit < 0 ? all.size() : Math.min(all.size(), from + limit);
            return withTransform(new Page(new ArrayList<>(all.subList(from, to)), ctx, false), env);
        });
        onQuery(m.prefix + "Paginated", env -> {
            HeadlessContext ctx = env.getGraphQlContext().get(HeadlessContext.KEY);
            List<FragmentView> all = select(m, env, ctx);
            Integer first = env.getArgument("first");
            String after = env.getArgument("after");
            int from = after == null || after.isEmpty() ? 0 : decodeCursor(after) + 1;
            from = Math.min(all.size(), Math.max(0, from));
            int to = first == null || first < 0 ? all.size() : Math.min(all.size(), from + first);
            List<Object> edges = new ArrayList<>();
            for (int i = from; i < to; i++) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("cursor", encodeCursor(i));
                e.put("node", all.get(i));
                edges.add(e);
            }
            Map<String, Object> pageInfo = new LinkedHashMap<>();
            pageInfo.put("startCursor", edges.isEmpty() ? null : encodeCursor(from));
            pageInfo.put("endCursor", edges.isEmpty() ? null : encodeCursor(to - 1));
            pageInfo.put("hasNextPage", to < all.size());
            pageInfo.put("hasPreviousPage", from > 0);
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("edges", edges);
            connection.put("pageInfo", pageInfo);
            return withTransform(connection, env);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<FragmentView> select(GqlModel m, DataFetchingEnvironment env, HeadlessContext ctx) {
        Map<String, Object> filter = env.getArgument("filter");
        String variation = env.getArgument("variation");
        boolean withVariations = Boolean.TRUE.equals(env.getArgument("includeVariations"));
        String locale = env.getArgument("_locale");
        List<FragmentView> out = new ArrayList<>();
        for (String path : ctx.fragmentsOf(m.path)) {
            FragmentView master = ctx.fragment(path, variation, m.path);
            if (master == null) continue;
            List<FragmentView> candidates = new ArrayList<>();
            candidates.add(master);
            if (withVariations && (variation == null || FragmentView.MASTER.equals(master.variation))) {
                for (String v : master.variations()) {
                    if (v.equals(master.variation)) continue;
                    FragmentView fv = ctx.fragment(path, v, m.path);
                    if (fv != null) candidates.add(fv);
                }
            }
            for (FragmentView v : candidates) {
                if (locale != null && !locale.isEmpty()
                        && !v.locale().replace('-', '_').equalsIgnoreCase(locale.replace('-', '_'))) continue;
                if (Filters.matches(v, filter, ctx)) out.add(v);
            }
        }
        out.sort(Filters.sort(env.getArgument("sort"), ctx));
        return out;
    }

    private static Object withTransform(Object data, DataFetchingEnvironment env) {
        Object transform = env.getArgument("_assetTransform");
        return DataFetcherResult.newResult().data(data).localContext(transform).build();
    }

    private static final String CURSOR_PREFIX = "studio:";

    static String encodeCursor(int index) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((CURSOR_PREFIX + index).getBytes(StandardCharsets.UTF_8));
    }

    static int decodeCursor(String cursor) {
        try {
            String s = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!s.startsWith(CURSOR_PREFIX)) throw new IllegalArgumentException();
            return Integer.parseInt(s.substring(CURSOR_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cursor: " + cursor);
        }
    }

    /** A {@code <Model>Result} (one item) or {@code <Model>Results} (items), with the items' references. */
    static final class Page implements Gettable {
        private final List<FragmentView> items;
        private final HeadlessContext ctx;
        private final boolean single;

        Page(List<FragmentView> items, HeadlessContext ctx, boolean single) {
            this.items = items;
            this.ctx = ctx;
            this.single = single;
        }

        @Override
        public Object get(String field, DataFetchingEnvironment env) {
            switch (field) {
                case "item": return single && !items.isEmpty() ? items.get(0) : null;
                case "items": return items;
                case "_references": return references();
                default: return null;
            }
        }

        /** Fragments and assets the items reference directly (elements and links in rich text), each once. */
        private List<Object> references() {
            Map<String, Object> out = new LinkedHashMap<>();
            Set<String> own = new HashSet<>();
            for (FragmentView v : items) own.add(v.path());
            for (FragmentView v : items) {
                for (GqlModel.Field f : v.model.fields) {
                    for (Object item : FragmentView.items(v.raw(f.name))) {
                        switch (f.kind) {
                            case "fragment-reference":
                            case "content-reference":
                                add(item.toString(), v.variation, own, out);
                                break;
                            case "longtext":
                                for (String p : new RichText(v.contentType(f.name), item.toString()).repositoryLinks()) {
                                    add(p, v.variation, own, out);
                                }
                                break;
                            default:
                        }
                    }
                }
            }
            return new ArrayList<>(out.values());
        }

        private void add(String path, String variation, Set<String> own, Map<String, Object> out) {
            if (own.contains(path) || out.containsKey(path)) return;
            Object ref = ctx.fragment(path, variation, null);
            if (ref == null) ref = ctx.reference(path);
            if (ref != null) out.put(path, ref);
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** Reads fields of Sensible Studio's values; everything else (introspection, maps) as usual. */
    static Object generic(DataFetchingEnvironment env) {
        Object source = env.getSource();
        String name = env.getField().getName();
        if (source instanceof Gettable) return ((Gettable) source).get(name, env);
        if (source instanceof Map) return ((Map<?, ?>) source).get(name);
        return PropertyDataFetcher.fetching(name).get(env);
    }

    private void onQuery(String field, graphql.schema.DataFetcher<Object> fetcher) {
        code.dataFetcher(FieldCoordinates.coordinates("Query", field), fetcher);
    }

    private static GraphQLFieldDefinition.Builder field(String name, GraphQLOutputType type) {
        return GraphQLFieldDefinition.newFieldDefinition().name(name).type(type);
    }

    private static GraphQLInputObjectField.Builder inputField(String name, GraphQLInputType type) {
        return GraphQLInputObjectField.newInputObjectField().name(name).type(type);
    }

    private static GraphQLArgument.Builder argument(String name, GraphQLInputType type) {
        return GraphQLArgument.newArgument().name(name).type(type);
    }

    private static GraphQLTypeReference ref(String name) {
        return GraphQLTypeReference.typeRef(name);
    }

    /** Scalars carried as strings (dates and times). */
    @SuppressWarnings("deprecation")
    private static final class StringCoercing implements Coercing<Object, String> {
        private final String name;

        StringCoercing(String name) {
            this.name = name;
        }

        @Override
        public String serialize(Object value) {
            return value == null ? null : value.toString();
        }

        @Override
        public Object parseValue(Object input) {
            return input == null ? null : input.toString();
        }

        @Override
        public Object parseLiteral(Object input) {
            if (input instanceof StringValue) return ((StringValue) input).getValue();
            throw new CoercingParseLiteralException("Expected a string for " + name);
        }
    }

    /** The {@code Json} scalar: values pass through; literals become maps, lists and plain values. */
    @SuppressWarnings("deprecation")
    private static final class JsonCoercing implements Coercing<Object, Object> {
        @Override
        public Object serialize(Object value) {
            return value;
        }

        @Override
        public Object parseValue(Object input) {
            return input;
        }

        @Override
        public Object parseLiteral(Object input) {
            return literal((Value<?>) input);
        }

        private static Object literal(Value<?> v) {
            if (v == null || v instanceof NullValue) return null;
            if (v instanceof StringValue) return ((StringValue) v).getValue();
            if (v instanceof IntValue) return ((IntValue) v).getValue();
            if (v instanceof FloatValue) return ((FloatValue) v).getValue();
            if (v instanceof BooleanValue) return ((BooleanValue) v).isValue();
            if (v instanceof EnumValue) return ((EnumValue) v).getName();
            if (v instanceof ArrayValue) {
                List<Object> out = new ArrayList<>();
                for (Value<?> item : ((ArrayValue) v).getValues()) out.add(literal(item));
                return out;
            }
            if (v instanceof ObjectValue) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (ObjectField f : ((ObjectValue) v).getObjectFields()) out.put(f.getName(), literal(f.getValue()));
                return out;
            }
            throw new CoercingParseLiteralException("Unsupported JSON literal");
        }
    }
}
