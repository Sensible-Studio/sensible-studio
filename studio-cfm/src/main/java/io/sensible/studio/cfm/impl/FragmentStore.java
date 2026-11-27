package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads and writes content fragments in the AEM storage format (see {@link ContentFragmentImpl}) for the
 * fragment editor. Values are written with the JCR type the model declares, text elements keep their
 * {@code <name>@ContentType}, and every written element gets {@code <name>@LastModified}, like AEM.
 */
final class FragmentStore {

    static final String MASTER = ContentFragmentImpl.MASTER;
    private static final int MAX_SCAN = 20000;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_\\-.]*$");

    private final ResourceResolver resolver;

    FragmentStore(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    /** A validation problem of one element. */
    static final class FieldError {
        final String field;
        final String message;

        FieldError(String field, String message) {
            this.field = field;
            this.message = message;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("message", message);
            return m;
        }
    }

    static final class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;
        final List<FieldError> errors;

        ValidationException(List<FieldError> errors) {
            super(errors.size() + " invalid field(s)");
            this.errors = errors;
        }
    }

    // ------------------------------------------------------------------------------------------- read

    /** Subfolders and fragments directly in a DAM folder. */
    Map<String, Object> folder(String path) {
        Resource folder = resolver.getResource(path);
        if (folder == null) return null;
        List<Map<String, Object>> folders = new ArrayList<>();
        List<Map<String, Object>> fragments = new ArrayList<>();
        for (Resource child : folder.getChildren()) {
            if (child.getName().contains(":") || child.getName().startsWith("rep:")) continue;
            if (ContentFragmentImpl.isFragment(child)) {
                fragments.add(summary(child));
            } else if (isFolder(child)) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", child.getName());
                f.put("path", child.getPath());
                f.put("title", FragmentModel.valueMap(child.getChild("jcr:content")).get("jcr:title", child.getName()));
                f.put("hasChildren", hasFolderContent(child));
                folders.add(f);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", folder.getPath());
        m.put("title", FragmentModel.valueMap(folder.getChild("jcr:content")).get("jcr:title", folder.getName()));
        m.put("folders", folders);
        m.put("fragments", fragments);
        m.put("canCreate", mayAddBelow(resolver, folder.getPath()));
        return m;
    }

    /** Whether a folder has anything the console would show: a subfolder or a fragment directly in it. */
    private static boolean hasFolderContent(Resource folder) {
        for (Resource c : folder.getChildren()) {
            if (c.getName().contains(":") || c.getName().startsWith("rep:")) continue;
            if (ContentFragmentImpl.isFragment(c) || isFolder(c)) return true;
        }
        return false;
    }

    /** Fragments anywhere below {@code root} matching a text query and/or a model. */
    Map<String, Object> search(String root, String query, String model, int offset, int limit) {
        Resource start = resolver.getResource(root);
        List<Map<String, Object>> hits = new ArrayList<>();
        int[] scanned = {0};
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (start != null) collect(start, q, model, hits, scanned);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", hits.size());
        m.put("truncated", scanned[0] >= MAX_SCAN);
        m.put("fragments", hits.subList(Math.min(offset, hits.size()), Math.min(offset + limit, hits.size())));
        return m;
    }

    private void collect(Resource node, String q, String model, List<Map<String, Object>> hits, int[] scanned) {
        for (Resource child : node.getChildren()) {
            if (++scanned[0] >= MAX_SCAN) return;
            if (child.getName().contains(":")) continue;
            if (ContentFragmentImpl.isFragment(child)) {
                Map<String, Object> s = summary(child);
                boolean modelOk = model == null || model.isEmpty() || model.equals(s.get("model"));
                boolean textOk = q.isEmpty() || String.valueOf(s.get("title")).toLowerCase(Locale.ROOT).contains(q)
                        || child.getName().toLowerCase(Locale.ROOT).contains(q);
                if (modelOk && textOk) hits.add(s);
            } else if (isFolder(child)) {
                collect(child, q, model, hits, scanned);
            }
        }
    }

    private static boolean isFolder(Resource r) {
        String type = r.getValueMap().get("jcr:primaryType", "");
        return type.endsWith("Folder") || type.equals("nt:folder");
    }

    Map<String, Object> summary(Resource asset) {
        ValueMap content = FragmentModel.valueMap(asset.getChild("jcr:content"));
        ValueMap data = FragmentModel.valueMap(asset.getChild("jcr:content/data"));
        String modelPath = data.get("cq:model", String.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", asset.getPath());
        m.put("name", asset.getName());
        m.put("title", content.get("jcr:title", asset.getName()));
        m.put("description", content.get("jcr:description", ""));
        m.put("model", modelPath);
        Resource model = modelPath == null ? null : resolver.getResource(modelPath + "/jcr:content");
        m.put("modelTitle", model == null ? null : model.getValueMap().get("jcr:title", String.class));
        Calendar modified = content.get("lastFragmentSave", Calendar.class);
        if (modified == null) modified = content.get("jcr:lastModified", Calendar.class);
        m.put("modified", iso(modified));
        return m;
    }

    /** The full fragment: values of every variation, keyed by element name. */
    Map<String, Object> fragment(String path) {
        Resource asset = resolver.getResource(path);
        if (!ContentFragmentImpl.isFragment(asset)) return null;
        ContentFragmentImpl cf = ContentFragmentImpl.of(asset);
        Map<String, Object> m = summary(asset);
        Resource data = asset.getChild("jcr:content/data");

        List<Map<String, Object>> variations = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> contentTypes = new LinkedHashMap<>();
        Map<String, Object> master = new LinkedHashMap<>();
        master.put("name", MASTER);
        master.put("title", "Master");
        variations.add(master);
        if (data != null) {
            for (Resource v : data.getChildren()) {
                if (!MASTER.equals(v.getName())) {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", v.getName());
                    d.put("title", v.getValueMap().get("jcr:title", v.getName()));
                    d.put("description", v.getValueMap().get("jcr:description", ""));
                    variations.add(d);
                }
                Map<String, Object> vals = new LinkedHashMap<>();
                Map<String, Object> types = new LinkedHashMap<>();
                ValueMap p = v.getValueMap();
                for (FragmentModel.ElementDef def : cf.getModel().getElements()) {
                    Object raw = p.get(def.name);
                    vals.put(def.name, toJson(raw));
                    if (def.isText()) {
                        types.put(def.name, p.get(def.name + ContentFragmentImpl.CONTENT_TYPE_SUFFIX,
                                def.defaultContentType != null ? def.defaultContentType : "text/plain"));
                    }
                }
                values.put(v.getName(), vals);
                contentTypes.put(v.getName(), types);
            }
        }
        m.put("variations", variations);
        m.put("values", values);
        m.put("contentTypes", contentTypes);
        String[] tags = FragmentModel.valueMap(asset.getChild("jcr:content/metadata")).get("cq:tags", String[].class);
        m.put("tags", tags == null ? new String[0] : tags);
        m.put("modifiedBy", FragmentModel.valueMap(asset.getChild("jcr:content")).get("jcr:lastModifiedBy", String.class));
        return m;
    }

    // ------------------------------------------------------------------------------------------- write

    /**
     * Saves one variation. {@code values} holds only the elements to change (absent = untouched,
     * null/empty = removed). {@code title}, {@code description}, {@code tags} are optional.
     */
    void save(String path, String variation, Map<String, Object> values, Map<String, Object> contentTypes,
              String title, String description, List<String> tags) throws ValidationException, PersistenceException {
        Resource asset = resolver.getResource(path);
        if (!ContentFragmentImpl.isFragment(asset)) throw new IllegalArgumentException("Not a content fragment: " + path);
        ContentFragmentImpl cf = ContentFragmentImpl.of(asset);
        String v = variation == null || variation.isEmpty() ? MASTER : variation;
        Resource target = asset.getChild("jcr:content/data/" + v);
        if (target == null) {
            if (!MASTER.equals(v)) throw new IllegalArgumentException("No variation " + v);
            target = ensure(asset.getChild("jcr:content/data"), MASTER);
        }

        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            FragmentModel.ElementDef def = cf.getModel().getElement(e.getKey());
            if (def == null) {
                errors.add(new FieldError(e.getKey(), "Not an element of the model"));
                continue;
            }
            try {
                converted.put(def.name, convert(def, e.getValue()));
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldError(def.name, ex.getMessage()));
            }
        }
        if (MASTER.equals(v)) errors.addAll(validate(cf, target, converted));
        if (!errors.isEmpty()) throw new ValidationException(errors);

        Calendar now = Calendar.getInstance();
        ModifiableValueMap props = target.adaptTo(ModifiableValueMap.class);
        for (Map.Entry<String, Object> e : converted.entrySet()) {
            if (e.getValue() == null) props.remove(e.getKey());
            else props.put(e.getKey(), e.getValue());
            props.put(e.getKey() + "@LastModified", now);
        }
        if (contentTypes != null) {
            for (Map.Entry<String, Object> e : contentTypes.entrySet()) {
                FragmentModel.ElementDef def = cf.getModel().getElement(e.getKey());
                if (def != null && def.isText() && e.getValue() != null) {
                    props.put(def.name + ContentFragmentImpl.CONTENT_TYPE_SUFFIX, e.getValue().toString());
                }
            }
        }

        ModifiableValueMap content = asset.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        if (title != null) content.put("jcr:title", title);
        if (description != null) content.put("jcr:description", description);
        content.put("lastFragmentSave", now);
        content.put("jcr:lastModified", now);
        content.put("jcr:lastModifiedBy", userId());
        if (tags != null) {
            Resource metadata = ensure(asset.getChild("jcr:content"), "metadata");
            ModifiableValueMap meta = metadata.adaptTo(ModifiableValueMap.class);
            if (tags.isEmpty()) meta.remove("cq:tags");
            else meta.put("cq:tags", tags.toArray(new String[0]));
        }
        resolver.commit();
    }

    /** Creates a fragment of {@code modelPath} in {@code parentPath}; returns its path. */
    String create(String parentPath, String name, String title, String modelPath, String description)
            throws PersistenceException {
        Resource parent = resolver.getResource(parentPath);
        if (parent == null) throw new IllegalArgumentException("No folder " + parentPath);
        FragmentModel model = FragmentModel.load(resolver, modelPath);
        if (model == null) throw new IllegalArgumentException("No content fragment model " + modelPath);
        String n = name == null || name.trim().isEmpty() ? slug(title) : name.trim();
        if (!NAME.matcher(n).matches()) throw new IllegalArgumentException("Invalid name: " + n);
        if (parent.getChild(n) != null) throw new IllegalArgumentException("Exists already: " + parentPath + "/" + n);

        Calendar now = Calendar.getInstance();
        Resource asset = resolver.create(parent, n, props("jcr:primaryType", "dam:Asset"));
        Map<String, Object> contentProps = props("jcr:primaryType", "dam:AssetContent");
        contentProps.put("jcr:title", title == null || title.isEmpty() ? n : title);
        if (description != null && !description.isEmpty()) contentProps.put("jcr:description", description);
        contentProps.put("contentFragment", Boolean.TRUE);
        contentProps.put("lastFragmentSave", now);
        contentProps.put("jcr:lastModified", now);
        contentProps.put("jcr:lastModifiedBy", userId());
        Resource content = resolver.create(asset, "jcr:content", contentProps);
        Map<String, Object> dataProps = props("jcr:primaryType", "nt:unstructured");
        dataProps.put("cq:model", modelPath);
        Resource data = resolver.create(content, "data", dataProps);

        Map<String, Object> masterProps = props("jcr:primaryType", "nt:unstructured");
        for (FragmentModel.ElementDef def : model.getElements()) {
            Object dflt = defaultValue(modelPath, def.name);
            if (dflt != null) {
                try {
                    Object v = convert(def, dflt);
                    if (v != null) masterProps.put(def.name, v);
                } catch (IllegalArgumentException ignored) {
                    // a default that does not fit its own type is skipped
                }
            }
            if (def.isText() && def.defaultContentType != null) {
                masterProps.put(def.name + ContentFragmentImpl.CONTENT_TYPE_SUFFIX, def.defaultContentType);
            }
        }
        resolver.create(data, MASTER, masterProps);
        resolver.create(content, "metadata", props("jcr:primaryType", "nt:unstructured"));
        resolver.create(content, "related", props("jcr:primaryType", "nt:unstructured"));
        resolver.commit();
        return asset.getPath();
    }

    void delete(String path) throws PersistenceException {
        Resource asset = resolver.getResource(path);
        if (!ContentFragmentImpl.isFragment(asset)) throw new IllegalArgumentException("Not a content fragment: " + path);
        resolver.delete(asset);
        resolver.commit();
    }

    /** A new variation starts as a copy of master. */
    String createVariation(String path, String name, String title, String description) throws PersistenceException {
        Resource asset = resolver.getResource(path);
        if (!ContentFragmentImpl.isFragment(asset)) throw new IllegalArgumentException("Not a content fragment: " + path);
        String n = name == null || name.trim().isEmpty() ? slug(title) : name.trim();
        if (!NAME.matcher(n).matches() || MASTER.equals(n)) throw new IllegalArgumentException("Invalid variation name: " + n);
        Resource data = asset.getChild("jcr:content/data");
        if (data.getChild(n) != null) throw new IllegalArgumentException("Variation exists already: " + n);
        Map<String, Object> props = new HashMap<>();
        Resource master = data.getChild(MASTER);
        if (master != null) {
            for (Map.Entry<String, Object> e : master.getValueMap().entrySet()) {
                if (e.getKey().startsWith("jcr:") && !"jcr:primaryType".equals(e.getKey())) continue;
                props.put(e.getKey(), e.getValue());
            }
        }
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("jcr:title", title == null || title.isEmpty() ? n : title);
        if (description != null && !description.isEmpty()) props.put("jcr:description", description);
        resolver.create(data, n, props);
        resolver.commit();
        return n;
    }

    void deleteVariation(String path, String name) throws PersistenceException {
        if (MASTER.equals(name)) throw new IllegalArgumentException("The master variation cannot be deleted");
        Resource variation = resolver.getResource(path + "/jcr:content/data/" + name);
        if (variation == null) throw new IllegalArgumentException("No variation " + name);
        resolver.delete(variation);
        resolver.commit();
    }

    // ------------------------------------------------------------------------------------------- values

    /** Converts a JSON value to what the repository stores for the element. Null/blank means "remove". */
    static Object convert(FragmentModel.ElementDef def, Object value) {
        DataTypeImpl type = new DataTypeImpl(def.valueType);
        boolean multiple = type.isMultiValue() || "enumeration".equals(def.metaType) && value instanceof Collection;
        List<Object> items = new ArrayList<>();
        if (value instanceof Collection) items.addAll((Collection<?>) value);
        else if (value instanceof Object[]) items.addAll(Arrays.asList((Object[]) value));
        else if (value != null) items.add(value);
        items.removeIf(o -> o == null || o instanceof String && ((String) o).trim().isEmpty());
        if (items.isEmpty()) return null;

        Class<?> target;
        switch (type.getTypeString()) {
            case "long": target = Long.class; break;
            case "double": target = Double.class; break;
            case "boolean": target = Boolean.class; break;
            case "calendar": target = Calendar.class; break;
            default: target = String.class;
        }
        if (target == Boolean.class) {
            for (Object o : items) {
                String s = o.toString();
                if (!(o instanceof Boolean) && !"true".equalsIgnoreCase(s) && !"false".equalsIgnoreCase(s)) {
                    throw new IllegalArgumentException("Not a boolean: " + s);
                }
            }
        }
        Object single = FragmentDataImpl.convert(multiple ? items.toArray() : items.get(0),
                multiple ? java.lang.reflect.Array.newInstance(target, 0).getClass() : target);
        if (single == null || single.getClass().isArray() && java.lang.reflect.Array.getLength(single) != items.size()) {
            throw new IllegalArgumentException("Not a valid " + type.getTypeString() + " value");
        }
        return single;
    }

    private List<FieldError> validate(ContentFragmentImpl cf, Resource master, Map<String, Object> changes) {
        List<FieldError> errors = new ArrayList<>();
        Resource items = cf.getModel().getResource() == null ? null : cf.getModel().getResource().getChild(FragmentModel.MODEL_ITEMS);
        if (items == null) return errors;
        ValueMap stored = master.getValueMap();
        for (Resource item : items.getChildren()) {
            ValueMap p = item.getValueMap();
            String name = p.get("name", String.class);
            if (name == null) continue;
            Object value = changes.containsKey(name) ? changes.get(name) : stored.get(name);
            boolean empty = value == null || value instanceof String && ((String) value).trim().isEmpty()
                    || value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0;
            if (empty) {
                if (ModelJson.isOn(p.get("required")) && changes.containsKey(name)) {
                    errors.add(new FieldError(name, "This field is required."));
                }
                continue;
            }
            Resource data = item.getChild("granite:data");
            ValueMap d = FragmentModel.valueMap(data);
            String regex = d.get("validationRegex", String.class);
            if (regex != null && value instanceof String) {
                try {
                    if (!Pattern.compile(regex).matcher((String) value).matches()) {
                        errors.add(new FieldError(name, d.get("customErrorMsg", "The value does not match the required format.")));
                    }
                } catch (PatternSyntaxException ignored) {
                    // a broken pattern in the model must not block authors
                }
            }
            Double min = d.get("number-min", Double.class);
            if (min != null && value instanceof Number && ((Number) value).doubleValue() < min) {
                errors.add(new FieldError(name, d.get("customErrorMsg", "Must be at least " + min)));
            }
            Double max = d.get("number-max", Double.class);
            if (max != null && value instanceof Number && ((Number) value).doubleValue() > max) {
                errors.add(new FieldError(name, d.get("customErrorMsg", "Must be at most " + max)));
            }
            Long maxLength = p.get("maxlength", Long.class);
            if (maxLength != null && value instanceof String && ((String) value).length() > maxLength) {
                errors.add(new FieldError(name, "At most " + maxLength + " characters."));
            }
        }
        return errors;
    }

    private Object defaultValue(String modelPath, String element) {
        Resource items = resolver.getResource(modelPath + "/" + FragmentModel.MODEL_ITEMS);
        if (items == null) return null;
        for (Resource item : items.getChildren()) {
            ValueMap p = item.getValueMap();
            if (element.equals(p.get("name", String.class))) {
                Object v = p.get("value");
                if (v == null && "boolean".equals(p.get("metaType", String.class))) v = p.get("checked");
                return v;
            }
        }
        return null;
    }

    static Object toJson(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Calendar) return iso((Calendar) raw);
        if (raw.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int len = java.lang.reflect.Array.getLength(raw);
            for (int i = 0; i < len; i++) out.add(toJson(java.lang.reflect.Array.get(raw, i)));
            return out;
        }
        if (raw instanceof String || raw instanceof Number || raw instanceof Boolean) return raw;
        return raw.toString();
    }

    /** {@link #toJson} as a list: none, one or many values. */
    @SuppressWarnings("unchecked")
    static List<Object> toJsonList(Object raw) {
        Object j = toJson(raw);
        if (j == null) return new ArrayList<>();
        if (j instanceof List) return (List<Object>) j;
        List<Object> one = new ArrayList<>();
        one.add(j);
        return one;
    }

    static String iso(Calendar c) {
        return c == null ? null : c.toInstant().atOffset(ZoneOffset.UTC).toString();
    }

    static String slug(String title) {
        String s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "fragment" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    private Resource ensure(Resource parent, String name) throws PersistenceException {
        Resource child = parent.getChild(name);
        return child != null ? child : resolver.create(parent, name, props("jcr:primaryType", "nt:unstructured"));
    }

    private String userId() {
        String id = resolver.getUserID();
        return id == null ? "anonymous" : id;
    }

    private static Map<String, Object> props(String key, Object value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    /**
     * Whether the signed-in user may add something below {@code path}: the consoles offered Create to a reader and
     * let the refusal say so. Without a JCR session (tests on mocks), or when it cannot be told: yes.
     */
    static boolean mayAddBelow(org.apache.sling.api.resource.ResourceResolver resolver, String path) {
        javax.jcr.Session session = resolver == null ? null : resolver.adaptTo(javax.jcr.Session.class);
        if (session == null) {
            return true;
        }
        try {
            return session.hasPermission(path + "/studio-probe", javax.jcr.Session.ACTION_ADD_NODE);
        } catch (javax.jcr.RepositoryException | RuntimeException e) {
            return true;
        }
    }
}
