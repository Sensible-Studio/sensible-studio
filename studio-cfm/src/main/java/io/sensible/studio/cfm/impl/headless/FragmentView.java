package io.sensible.studio.cfm.impl.headless;

import graphql.schema.DataFetchingEnvironment;
import io.sensible.studio.cfm.impl.ContentFragmentImpl;
import io.sensible.studio.cfm.impl.FragmentModel;
import io.sensible.studio.cfm.impl.Json;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import java.lang.reflect.Array;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One content fragment in one variation, as a GraphQL object of its model's type. Values the variation does not
 * hold come from master.
 */
final class FragmentView implements Gettable {

    static final String MASTER = ContentFragmentImpl.MASTER;
    private static final Pattern LOCALE = Pattern.compile("^[a-z]{2}([_-][A-Za-z]{2,4})?$");
    private static final DateTimeFormatter CALENDAR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    final Resource asset;
    final GqlModel model;
    final String variation;
    private final ValueMap values;
    private final ValueMap master;

    FragmentView(Resource asset, GqlModel model, String variation) {
        this.asset = asset;
        this.model = model;
        Resource data = asset.getChild("jcr:content/data");
        Resource m = data == null ? null : data.getChild(MASTER);
        Resource v = variation == null || MASTER.equals(variation) || data == null ? null : data.getChild(variation);
        this.master = FragmentModel.valueMap(m);
        this.values = v == null ? master : v.getValueMap();
        this.variation = v == null ? MASTER : variation;
    }

    String path() {
        return asset.getPath();
    }

    @Override
    public Object get(String field, DataFetchingEnvironment env) {
        HeadlessContext ctx = env.getGraphQlContext().get(HeadlessContext.KEY);
        switch (field) {
            case "_path": return path();
            case "_id": return asset.getValueMap().get("jcr:uuid", String.class);
            case "_variation": return variation;
            case "_variations": return variations();
            case "_model": {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("_path", model.path);
                m.put("title", model.title);
                return m;
            }
            case "_metadata": return metadata();
            case "_tags": return tags();
            case "_locale": return locale();
            default: {
                GqlModel.Field f = model.field(field);
                return f == null ? null : value(f, ctx);
            }
        }
    }

    /** The stored value of an element (variation first, then master). */
    Object raw(String element) {
        Object v = values.get(element);
        return v != null || values == master ? v : master.get(element);
    }

    String contentType(String element) {
        String key = element + ContentFragmentImpl.CONTENT_TYPE_SUFFIX;
        String t = values.get(key, String.class);
        return t != null ? t : master.get(key, RichText.PLAIN);
    }

    /** The GraphQL value of an element. */
    Object value(GqlModel.Field f, HeadlessContext ctx) {
        List<Object> items = items(raw(f.name));
        List<Object> out = new ArrayList<>();
        for (Object item : items) {
            Object v = convert(f, item, ctx);
            if (v != null) out.add(v);
        }
        if (f.multiple || "tags".equals(f.kind)) return out;
        return out.isEmpty() ? null : out.get(0);
    }

    private Object convert(GqlModel.Field f, Object item, HeadlessContext ctx) {
        switch (f.kind) {
            case "number":
                if (f.isDouble()) return number(item);
                Double d = number(item);
                return d == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(d)));
            case "boolean":
                return item instanceof Boolean ? item : Boolean.valueOf(item.toString());
            case "date":
                return date(item, f.dateType);
            case "longtext":
                return new RichText(contentType(f.name), item.toString());
            case "content-reference":
                return ctx.reference(item.toString());
            case "fragment-reference":
                return ctx.fragment(item.toString(), variation, f.allowedModels.size() == 1 ? f.allowedModels.get(0) : null);
            case "json":
                try {
                    return item instanceof String ? Json.parse((String) item) : item;
                } catch (RuntimeException e) {
                    return item.toString();
                }
            default:
                return item.toString();
        }
    }

    /** A comparable/filterable value of an element: numbers, strings, instants; rich text as plain text. */
    List<Object> comparable(GqlModel.Field f, HeadlessContext ctx) {
        List<Object> out = new ArrayList<>();
        for (Object item : items(raw(f.name))) {
            switch (f.kind) {
                case "number": out.add(number(item)); break;
                case "boolean": out.add(item instanceof Boolean ? item : Boolean.valueOf(item.toString())); break;
                case "date": out.add(item instanceof Calendar ? ((Calendar) item).toInstant() : item.toString()); break;
                case "longtext": out.add(new RichText(contentType(f.name), item.toString()).plaintext()); break;
                default: out.add(item.toString());
            }
        }
        return out;
    }

    List<String> variations() {
        List<String> out = new ArrayList<>();
        Resource data = asset.getChild("jcr:content/data");
        if (data == null) return out;
        for (Resource v : data.getChildren()) {
            if (!MASTER.equals(v.getName()) && !v.getName().contains(":")) out.add(v.getName());
        }
        return out;
    }

    List<String> tags() {
        String[] tags = FragmentModel.valueMap(asset.getChild("jcr:content/metadata")).get("cq:tags", String[].class);
        List<String> out = new ArrayList<>();
        if (tags != null) Collections.addAll(out, tags);
        return out;
    }

    /** The language folder of the fragment ({@code /content/dam/site/en/...} → {@code en}), or "". */
    String locale() {
        String[] parts = path().split("/");
        for (int i = 4; i < parts.length - 1; i++) {
            if (LOCALE.matcher(parts[i]).matches()) return parts[i];
        }
        return "";
    }

    /** {@code _metadata}: the asset metadata grouped by type, as AEM's {@code FragmentMetadata}. */
    private Map<String, Object> metadata() {
        ValueMap meta = FragmentModel.valueMap(asset.getChild("jcr:content/metadata"));
        List<Object> strings = new ArrayList<>();
        List<Object> stringArrays = new ArrayList<>();
        List<Object> ints = new ArrayList<>();
        List<Object> floats = new ArrayList<>();
        List<Object> booleans = new ArrayList<>();
        List<Object> calendars = new ArrayList<>();
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.startsWith("jcr:") || v == null) continue;
            if (v instanceof String[]) stringArrays.add(entry(k, new ArrayList<>(java.util.Arrays.asList((String[]) v))));
            else if (v instanceof String) strings.add(entry(k, v));
            else if (v instanceof Long || v instanceof Integer) ints.add(entry(k, ((Number) v).intValue()));
            else if (v instanceof Number) floats.add(entry(k, ((Number) v).doubleValue()));
            else if (v instanceof Boolean) booleans.add(entry(k, v));
            else if (v instanceof Calendar) calendars.add(entry(k, date(v, "datetime")));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stringMetadata", strings);
        m.put("stringArrayMetadata", stringArrays);
        m.put("intMetadata", ints);
        m.put("floatMetadata", floats);
        m.put("booleanMetadata", booleans);
        m.put("calendarMetadata", calendars);
        return m;
    }

    private static Map<String, Object> entry(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    static List<Object> items(Object raw) {
        List<Object> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(raw); i++) {
                Object o = Array.get(raw, i);
                if (o != null && !(o instanceof String && ((String) o).isEmpty())) out.add(o);
            }
        } else if (!(raw instanceof String && ((String) raw).isEmpty())) {
            out.add(raw);
        }
        return out;
    }

    static Double number(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return o == null ? null : Double.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String date(Object o, String dateType) {
        if (!(o instanceof Calendar)) return o.toString();
        Calendar c = (Calendar) o;
        ZoneId zone = c.getTimeZone().toZoneId();
        OffsetDateTime t = OffsetDateTime.ofInstant(c.toInstant(), zone);
        if ("date".equals(dateType)) return t.toLocalDate().toString();
        if ("time".equals(dateType)) return TIME.format(t);
        return CALENDAR.format(t);
    }
}
