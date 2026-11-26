package io.sensible.studio.translation.impl;

import org.apache.sling.api.resource.ModifiableValueMap;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Which page properties are text to translate. Like AEM's translation rules, by property name for every component,
 * plus properties of given resource types; everything else (paths, settings, styles) is copied as it is.
 *
 * <p>As AEM's Translation Configuration, rules belong to a <em>context</em>, a content path: the most specific
 * context above a page applies. The OSGi configuration is the built-in context {@code /content}; contexts made in
 * the Translation Configuration console live in {@code /conf/global/settings/translation/rules/<name>} (property
 * {@code context}) and override it, {@code /content} included.
 */
@Component(service = PageTranslationRules.class)
@Designate(ocd = PageTranslationRules.Config.class)
public class PageTranslationRules {

    static final String[] DEFAULT_PROPERTIES = {
            "jcr:title", "jcr:description", "pageTitle", "navTitle", "subtitle", "title", "text", "alt", "description",
            "actionText", "linkText", "cq:panelTitle", "helpMessage", "placeholder", "label", "caption", "pretitle"};

    @ObjectClassDefinition(name = "Sensible Studio Page Translation Rules",
            description = "Which properties of pages and components are translated into language copies.")
    public @interface Config {
        @AttributeDefinition(name = "Translated properties", description = "Translated in every component (and the page) when they hold text.")
        String[] properties() default {
                "jcr:title", "jcr:description", "pageTitle", "navTitle", "subtitle", "title", "text", "alt", "description",
                "actionText", "linkText", "cq:panelTitle", "helpMessage", "placeholder", "label", "caption", "pretitle"};

        @AttributeDefinition(name = "Properties of resource types",
                description = "resource/type = prop1, prop2 (added to the properties above for that component), e.g. myapp/components/quote = author, source")
        String[] resourceTypeProperties() default {};

        @AttributeDefinition(name = "Not translated resource types", description = "Components whose texts stay as they are, e.g. code or embeds.")
        String[] excludedResourceTypes() default {"core/wcm/components/embed/v1/embed", "core/wcm/components/embed/v2/embed"};

        @AttributeDefinition(name = "Not translated properties", description = "Names never translated even if listed above.")
        String[] excludedProperties() default {};

        @AttributeDefinition(name = "Translated property endings",
                description = "Properties whose names end like this are translated too when they hold text (e.g. accessibilityLabel, signInLabel).")
        String[] propertySuffixes() default {"Label", "Text", "Title", "Message"};

        @AttributeDefinition(name = "Translate assets",
                description = "When a page is translated with its references, the DAM assets it shows get language copies with their metadata translated "
                        + "(assets without such texts stay shared).")
        boolean translateAssets() default true;

        @AttributeDefinition(name = "Asset metadata translated", description = "Properties of jcr:content/metadata translated in asset copies.")
        String[] assetProperties() default {"dc:title", "dc:description", "photoshop:Headline"};
    }

    static final String[] DEFAULT_SUFFIXES = {"Label", "Text", "Title", "Message"};
    static final String[] DEFAULT_ASSET_PROPERTIES = {"dc:title", "dc:description", "photoshop:Headline"};
    static final String[] DEFAULT_EXCLUDED_TYPES = {"core/wcm/components/embed/v1/embed", "core/wcm/components/embed/v2/embed"};

    /** Where the contexts made in the console are kept. */
    static final String STORE = "/conf/global/settings/translation/rules";
    /** The context of the OSGi configuration. */
    static final String BASE_CONTEXT = "/content";
    static final String PN_CONTEXT = "context";

    /** Nodes below {@code jcr:content} that are not content (layout, annotations, MSM). */
    static final Set<String> SKIPPED_NODES = new HashSet<>(Arrays.asList(
            "cq:responsive", "cq:annotations", "cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "cq:translation", "cq:styleIds"));

    private static final Pattern NOT_TEXT = Pattern.compile(
            "^(/|[a-z][a-z0-9+.-]*:|#|\\$\\{|\\d+([.,]\\d+)?$|true$|false$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:.\\-]*");
    private static final Pattern RESOURCE_TYPE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.\\-]*(/[A-Za-z0-9_.\\-]+)*");
    private static final Pattern SUFFIX = Pattern.compile("[A-Za-z0-9_]+");

    private volatile Rules base = new Rules(BASE_CONTEXT, true, Arrays.asList(DEFAULT_PROPERTIES), Collections.emptyMap(),
            Arrays.asList(DEFAULT_EXCLUDED_TYPES), Collections.emptyList(), Arrays.asList(DEFAULT_SUFFIXES), true,
            Arrays.asList(DEFAULT_ASSET_PROPERTIES), null, null);

    @Activate
    @Modified
    void activate(Config config) {
        Map<String, List<String>> byType = new TreeMap<>();
        for (String line : config.resourceTypeProperties()) {
            int eq = line == null ? -1 : line.indexOf('=');
            if (eq <= 0) continue;
            List<String> props = byType.computeIfAbsent(line.substring(0, eq).trim(), k -> new ArrayList<>());
            for (String p : line.substring(eq + 1).split(",")) if (!p.trim().isEmpty() && !props.contains(p.trim())) props.add(p.trim());
        }
        base = new Rules(BASE_CONTEXT, true, Arrays.asList(config.properties()), byType, Arrays.asList(config.excludedResourceTypes()),
                Arrays.asList(config.excludedProperties()), config.propertySuffixes() == null ? Collections.emptyList() : Arrays.asList(config.propertySuffixes()),
                config.translateAssets(), config.assetProperties() == null ? Collections.emptyList() : Arrays.asList(config.assetProperties()), null, null);
    }

    // ------------------------------------------------------------------ the built-in context (callers without a path)

    boolean translateAssets() {
        return base.translateAssets;
    }

    String[] assetProperties() {
        return base.assetProperties.toArray(new String[0]);
    }

    boolean isExcludedType(String resourceType) {
        return base.isExcludedType(resourceType);
    }

    boolean isTranslatable(String resourceType, String property) {
        return base.isTranslatable(resourceType, property);
    }

    Rules base() {
        return base;
    }

    // ------------------------------------------------------------------ contexts

    /** The rules of the most specific context above {@code path}. */
    Rules at(ResourceResolver resolver, String path) {
        Rules best = null;
        for (Rules r : stored(resolver)) {
            if (path != null && (path.equals(r.path) || path.startsWith(r.path + "/")) && (best == null || r.path.length() > best.path.length())) {
                best = r;
            }
        }
        return best != null ? best : base;
    }

    /** Every context: the stored ones, and the built-in {@code /content} unless a stored one replaces it. */
    List<Rules> contexts(ResourceResolver resolver) {
        List<Rules> out = new ArrayList<>(stored(resolver));
        if (out.stream().noneMatch(r -> BASE_CONTEXT.equals(r.path))) out.add(base);
        out.sort((a, b) -> a.path.compareTo(b.path));
        return out;
    }

    private static List<Rules> stored(ResourceResolver resolver) {
        List<Rules> out = new ArrayList<>();
        Resource store = resolver == null ? null : resolver.getResource(STORE);
        if (store == null) return out;
        for (Resource r : store.getChildren()) {
            Rules rules = Rules.read(r);
            if (rules != null) out.add(rules);
        }
        return out;
    }

    /** Stores a context's rules (a new one, or the replacement of one); returns what was stored. */
    Rules save(ResourceResolver resolver, Rules rules) throws PersistenceException {
        Resource store = ensure(resolver, STORE);
        Resource node = node(store, rules.path);
        if (node == null) {
            String name = rules.path.substring(1).replaceAll("[^A-Za-z0-9]+", "-");
            String unique = name;
            for (int i = 2; store.getChild(unique) != null; i++) unique = name + "-" + i;
            Map<String, Object> props = new HashMap<>();
            props.put("jcr:primaryType", "nt:unstructured");
            node = resolver.create(store, unique, props);
        }
        ModifiableValueMap m = node.adaptTo(ModifiableValueMap.class);
        if (m == null) throw new PersistenceException("Cannot change " + node.getPath());
        rules.write(m, resolver.getUserID());
        return Rules.read(node);
    }

    /** Removes a stored context; the built-in /content applies again where it applied. Returns false if there was none. */
    boolean delete(ResourceResolver resolver, String path) throws PersistenceException {
        Resource store = resolver.getResource(STORE);
        Resource node = store == null ? null : node(store, path);
        if (node == null) return false;
        resolver.delete(node);
        return true;
    }

    private static Resource node(Resource store, String path) {
        for (Resource r : store.getChildren()) {
            if (path.equals(r.getValueMap().get(PN_CONTEXT, String.class))) return r;
        }
        return null;
    }

    private static Resource ensure(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource("/");
        for (String seg : path.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }

    // ------------------------------------------------------------------ one context's rules

    /** The rules of one context. */
    static final class Rules {
        final String path;
        /** The OSGi configuration's context, not stored in /conf. */
        final boolean builtIn;
        final Set<String> properties;
        final Map<String, Set<String>> byType;
        final Set<String> excludedTypes;
        final Set<String> excludedProperties;
        final List<String> suffixes;
        final boolean translateAssets;
        final List<String> assetProperties;
        final Calendar modified;
        final String modifiedBy;

        Rules(String path, boolean builtIn, Iterable<String> properties, Map<String, ? extends Iterable<String>> byType, Iterable<String> excludedTypes,
                Iterable<String> excludedProperties, Iterable<String> suffixes, boolean translateAssets, Iterable<String> assetProperties,
                Calendar modified, String modifiedBy) {
            this.path = path;
            this.builtIn = builtIn;
            this.properties = set(properties);
            Map<String, Set<String>> t = new TreeMap<>();
            for (Map.Entry<String, ? extends Iterable<String>> e : byType.entrySet()) t.put(e.getKey(), set(e.getValue()));
            this.byType = t;
            this.excludedTypes = set(excludedTypes);
            this.excludedProperties = set(excludedProperties);
            this.suffixes = new ArrayList<>(set(suffixes));
            this.translateAssets = translateAssets;
            this.assetProperties = new ArrayList<>(set(assetProperties));
            this.modified = modified;
            this.modifiedBy = modifiedBy;
        }

        private static Set<String> set(Iterable<String> values) {
            // in the order given: asset texts are listed (and translated) title first
            Set<String> out = new java.util.LinkedHashSet<>();
            if (values != null) for (String v : values) if (v != null && !v.trim().isEmpty()) out.add(v.trim());
            return out;
        }

        boolean isExcludedType(String resourceType) {
            return resourceType != null && excludedTypes.contains(resourceType);
        }

        boolean isTranslatable(String resourceType, String property) {
            if (excludedProperties.contains(property)) return false;
            if (properties.contains(property)) return true;
            if (property.indexOf(':') < 0) {
                for (String suffix : suffixes) {
                    if (property.length() > suffix.length() && property.endsWith(suffix)) return true;
                }
            }
            Set<String> extra = resourceType == null ? null : byType.get(resourceType);
            return extra != null && extra.contains(property);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", path);
            m.put("builtIn", builtIn);
            m.put("properties", new ArrayList<>(properties));
            List<Object> types = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : byType.entrySet()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("resourceType", e.getKey());
                t.put("properties", new ArrayList<>(e.getValue()));
                types.add(t);
            }
            m.put("resourceTypes", types);
            m.put("excludedResourceTypes", new ArrayList<>(excludedTypes));
            m.put("excludedProperties", new ArrayList<>(excludedProperties));
            m.put("propertySuffixes", new ArrayList<>(suffixes));
            m.put("translateAssets", translateAssets);
            m.put("assetProperties", new ArrayList<>(assetProperties));
            if (modified != null) m.put("modified", modified.toInstant().toString());
            if (modifiedBy != null) m.put("modifiedBy", modifiedBy);
            return m;
        }

        /** The rules of a JSON object from the console, checked. */
        @SuppressWarnings("unchecked")
        static Rules fromJson(Map<String, Object> json) {
            String path = json.get("path") == null ? "" : String.valueOf(json.get("path")).trim().replaceAll("/+$", "");
            if (!(path.equals("/content") || path.startsWith("/content/")) || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("A context is a path under /content, such as /content/wknd");
            }
            Map<String, List<String>> byType = new TreeMap<>();
            Object types = json.get("resourceTypes");
            if (types instanceof List) {
                for (Object o : (List<Object>) types) {
                    if (!(o instanceof Map)) continue;
                    String type = String.valueOf(((Map<String, Object>) o).get("resourceType")).trim();
                    if (type.isEmpty() || "null".equals(type)) continue;
                    check(RESOURCE_TYPE, type, "resource type");
                    List<String> props = byType.computeIfAbsent(type, k -> new ArrayList<>());
                    for (String p : list(((Map<String, Object>) o).get("properties"), PROPERTY, "property")) if (!props.contains(p)) props.add(p);
                }
            }
            return new Rules(path, false, list(json.get("properties"), PROPERTY, "property"), byType,
                    list(json.get("excludedResourceTypes"), RESOURCE_TYPE, "resource type"),
                    list(json.get("excludedProperties"), PROPERTY, "property"),
                    list(json.get("propertySuffixes"), SUFFIX, "ending"),
                    !Boolean.FALSE.equals(json.get("translateAssets")),
                    list(json.get("assetProperties"), PROPERTY, "property"), null, null);
        }

        private static List<String> list(Object v, Pattern pattern, String what) {
            List<String> out = new ArrayList<>();
            if (v instanceof List) {
                for (Object o : (List<?>) v) {
                    String s = o == null ? "" : String.valueOf(o).trim();
                    if (s.isEmpty()) continue;
                    check(pattern, s, what);
                    if (!out.contains(s)) out.add(s);
                }
            }
            return out;
        }

        private static void check(Pattern pattern, String value, String what) {
            if (!pattern.matcher(value).matches()) throw new IllegalArgumentException("\"" + value + "\" is not a " + what + " name");
        }

        static Rules read(Resource node) {
            ValueMap vm = node.getValueMap();
            String path = vm.get(PN_CONTEXT, String.class);
            if (path == null || !path.startsWith("/")) return null;
            Map<String, List<String>> byType = new TreeMap<>();
            for (String line : vm.get("resourceTypeProperties", new String[0])) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                List<String> props = byType.computeIfAbsent(line.substring(0, eq).trim(), k -> new ArrayList<>());
                for (String p : line.substring(eq + 1).split(",")) if (!p.trim().isEmpty()) props.add(p.trim());
            }
            return new Rules(path, false, Arrays.asList(vm.get("properties", new String[0])), byType,
                    Arrays.asList(vm.get("excludedResourceTypes", new String[0])), Arrays.asList(vm.get("excludedProperties", new String[0])),
                    Arrays.asList(vm.get("propertySuffixes", new String[0])), vm.get("translateAssets", true),
                    Arrays.asList(vm.get("assetProperties", new String[0])), vm.get("jcr:lastModified", Calendar.class),
                    vm.get("jcr:lastModifiedBy", String.class));
        }

        void write(ModifiableValueMap m, String user) {
            m.put(PN_CONTEXT, path);
            m.put("properties", properties.toArray(new String[0]));
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, Set<String>> e : byType.entrySet()) lines.add(e.getKey() + " = " + String.join(", ", e.getValue()));
            m.put("resourceTypeProperties", lines.toArray(new String[0]));
            m.put("excludedResourceTypes", excludedTypes.toArray(new String[0]));
            m.put("excludedProperties", excludedProperties.toArray(new String[0]));
            m.put("propertySuffixes", suffixes.toArray(new String[0]));
            m.put("translateAssets", translateAssets);
            m.put("assetProperties", assetProperties.toArray(new String[0]));
            m.put("jcr:lastModified", Calendar.getInstance());
            if (user != null) m.put("jcr:lastModifiedBy", user);
        }
    }

    // ------------------------------------------------------------------ texts

    /** A value worth translating: some words, not a path, URL, number, flag or expression. */
    static boolean isText(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || NOT_TEXT.matcher(v).find()) return false;
        return v.chars().anyMatch(Character::isLetter);
    }

    static boolean looksLikeHtml(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).matches("(?s).*<(p|br|b|i|strong|em|a|ul|ol|li|h[1-6]|span|blockquote|sup|sub|u|div|table)\\b.*");
    }
}
