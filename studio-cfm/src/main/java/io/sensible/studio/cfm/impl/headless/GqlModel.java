package io.sensible.studio.cfm.impl.headless;

import io.sensible.studio.cfm.impl.ModelJson;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A content fragment model as the GraphQL schema sees it: the object type ({@code AdventureModel}), the query
 * prefix ({@code adventure} for {@code adventureByPath}, {@code adventureList}, {@code adventurePaginated}) and
 * one field per element. Names follow AEM: derived from the model title, camel cased.
 */
final class GqlModel {

    final String path;
    final String conf;
    final String title;
    final String typeName;
    final String prefix;
    final List<Field> fields;
    final String fingerprint;
    private final Map<String, Field> byGqlName = new LinkedHashMap<>();

    /** One element of the model. */
    static final class Field {
        final String name;
        final String gqlName;
        final String kind;
        final String valueType;
        final boolean multiple;
        final String dateType;
        final List<String> allowedModels;

        Field(String name, String kind, String valueType, boolean multiple, String dateType, List<String> allowedModels) {
            this.name = name;
            this.gqlName = graphqlName(name);
            this.kind = kind;
            this.valueType = valueType == null ? "string" : valueType;
            this.multiple = multiple;
            this.dateType = dateType;
            this.allowedModels = allowedModels;
        }

        boolean isDouble() {
            return valueType.startsWith("double") || valueType.startsWith("decimal");
        }
    }

    private GqlModel(String path, String conf, String title, String typeBase, List<Field> fields, String fingerprint) {
        this.path = path;
        this.conf = conf;
        this.title = title;
        this.prefix = lowerFirst(typeBase);
        this.typeName = typeBase + "Model";
        this.fields = fields;
        this.fingerprint = fingerprint;
        for (Field f : fields) byGqlName.put(f.gqlName, f);
    }

    Field field(String gqlName) {
        return byGqlName.get(gqlName);
    }

    /** The model at {@code path}, or null when it is missing, disabled or has no elements GraphQL can expose. */
    @SuppressWarnings("unchecked")
    static GqlModel load(ResourceResolver resolver, String path) {
        Map<String, Object> m = ModelJson.model(resolver, path);
        if (m == null || Boolean.FALSE.equals(m.get("enabled"))) return null;
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) m.get("fields")) {
            String kind = (String) f.get("kind");
            if ("tab".equals(kind) || "unknown".equals(kind)) continue;
            String name = (String) f.get("name");
            if (name == null || name.isEmpty() || graphqlName(name).startsWith("__")) continue;
            Object allowed = f.get("allowedModels");
            List<String> allowedModels = allowed instanceof String[] ? Arrays.asList((String[]) allowed) : Collections.emptyList();
            fields.add(new Field(name, kind, (String) f.get("valueType"), Boolean.TRUE.equals(f.get("multiple")),
                    (String) f.get("dateType"), allowedModels));
        }
        String title = (String) m.get("title");
        Resource model = resolver.getResource(path);
        String base = typeBase(title, model == null ? path : model.getName());
        Resource content = model == null ? null : model.getChild("jcr:content");
        ValueMap cv = content == null ? ValueMap.EMPTY : content.getValueMap();
        Calendar modified = cv.get("jcr:lastModified", Calendar.class);
        String fingerprint = path + "@" + (modified == null ? "" : modified.getTimeInMillis()) + "#" + title + "#" + fields.size();
        return new GqlModel(path, (String) m.get("conf"), title, base, fields, fingerprint);
    }

    /** A copy with a different type name, used when two models of an endpoint would get the same name. */
    GqlModel renamed(String typeBase) {
        return new GqlModel(path, conf, title, typeBase, fields, fingerprint);
    }

    String typeBase() {
        return typeName.substring(0, typeName.length() - "Model".length());
    }

    /** "Adventure" → "Adventure", "my blog post" → "MyBlogPost"; falls back to the node name. */
    static String typeBase(String title, String nodeName) {
        String s = pascal(title);
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = pascal(nodeName);
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) s = "Fragment" + s;
        return s;
    }

    static String pascal(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : text.toCharArray()) {
            if (c < 128 && Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        return sb.toString();
    }

    /** A valid GraphQL name for an element name ({@code [_A-Za-z][_0-9A-Za-z]*}). */
    static String graphqlName(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) sb.append(c < 128 && (Character.isLetterOrDigit(c) || c == '_') ? c : '_');
        if (sb.length() == 0 || Character.isDigit(sb.charAt(0))) sb.insert(0, '_');
        return sb.toString();
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty()) return s;
        int i = 0;
        while (i < s.length() && Character.isUpperCase(s.charAt(i))) i++;
        // "URLLink" → "urlLink", "Adventure" → "adventure"
        int cut = i > 1 && i < s.length() ? i - 1 : i;
        return s.substring(0, Math.max(cut, 1)).toLowerCase(Locale.ROOT) + s.substring(Math.max(cut, 1));
    }
}
