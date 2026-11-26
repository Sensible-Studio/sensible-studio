package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Creates and edits content fragment models in AEM's storage format, the same one {@link ModelJson} reads: a
 * {@code cq:Template} under {@code /conf/<conf>/settings/dam/cfm/models} whose
 * {@code jcr:content/model/cq:dialog/content/items} holds one Granite field per element ({@code metaType},
 * {@code valueType}, {@code sling:resourceType}, {@code optionsmultifield}, validation in {@code granite:data}).
 *
 * <p>Fields arrive in {@link ModelJson}'s JSON shape. An existing item (matched by {@code id}, its node name) keeps
 * the properties the editor does not manage; the managed ones are rewritten. Items are ordered as sent; items not
 * sent are removed. Fragments are not migrated: renaming or removing an element leaves its stored values in place.</p>
 */
final class ModelStore {

    static final String MODELS = "settings/dam/cfm/models";
    static final String ITEMS = FragmentModel.MODEL_ITEMS;
    private static final Pattern ELEMENT_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NODE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_\\-]*$");
    private static final Set<String> RESERVED = new HashSet<>(java.util.Arrays.asList(
            "jcr", "path", "id", "variation", "variations", "metadata", "model", "_path", "_id", "_model", "_variation",
            "_metadata", "_tags", "_variations", "_references"));

    /** Managed item properties, removed before a field is written so that a changed kind leaves nothing stale. */
    private static final String[] MANAGED = {
            "sling:resourceType", "metaType", "valueType", "name", "fieldLabel", "cfm-element", "fieldDescription",
            "emptyText", "required", "maxlength", "value", "checked", "step", "typeHint", "default-mime-type", "rootPath",
            "type", "displayedFormat", "valueFormat", "emptyOption", "fragmentmodelreference", "nameSuffix", "filter",
            "showThumbnail", "validation", "listOrder", "options", "allowNew"};

    private final ResourceResolver resolver;

    ModelStore(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------------------------------- confs

    /** Configurations a model can be created in: every {@code /conf/<name>} (one level of nesting too). */
    List<Map<String, Object>> confs() {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource conf = resolver.getResource("/conf");
        if (conf == null) return out;
        for (Resource c : conf.getChildren()) {
            if (c.getName().contains(":")) continue;
            out.add(conf(c));
            for (Resource nested : c.getChildren()) {
                if (nested.getName().contains(":") || "settings".equals(nested.getName())) continue;
                if (nested.getChild("settings") != null) out.add(conf(nested));
            }
        }
        return out;
    }

    private static Map<String, Object> conf(Resource c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", c.getPath());
        m.put("title", c.getValueMap().get("jcr:title", c.getName()));
        Resource models = c.getChild(MODELS);
        int count = 0;
        if (models != null) for (Resource ignored : models.getChildren()) count++;
        m.put("models", count);
        return m;
    }

    // ------------------------------------------------------------------------------------------- create / delete

    /** A new, empty model; answers its path. */
    String create(String confPath, String name, String title, String description) throws PersistenceException,
            FragmentStore.ValidationException {
        List<FragmentStore.FieldError> errors = new ArrayList<>();
        if (title == null || title.trim().isEmpty()) errors.add(new FragmentStore.FieldError("title", "A title is required."));
        String n = name == null || name.trim().isEmpty() ? FragmentStore.slug(title) : name.trim();
        if (!NODE_NAME.matcher(n).matches()) {
            errors.add(new FragmentStore.FieldError("name", "Use lower case letters, digits, '-' and '_'."));
        }
        Resource conf = confPath == null ? null : resolver.getResource(confPath);
        if (conf == null || !confPath.startsWith("/conf/")) throw new IllegalArgumentException("No configuration at " + confPath);
        if (errors.isEmpty() && conf.getChild(MODELS + "/" + n) != null) {
            errors.add(new FragmentStore.FieldError("name", "A model named " + n + " exists already."));
        }
        if (!errors.isEmpty()) throw new FragmentStore.ValidationException(errors);

        Resource models = folders(conf, "settings", "dam", "cfm", "models");
        Map<String, Object> template = new HashMap<>();
        template.put("jcr:primaryType", "cq:Template");
        template.put("allowedPaths", new String[]{"/content/entities(/.*)?"});
        template.put("ranking", 100L);
        Resource model = resolver.create(models, n, template);

        Map<String, Object> content = new HashMap<>();
        content.put("jcr:primaryType", "cq:PageContent");
        content.put("jcr:title", title.trim());
        if (description != null && !description.trim().isEmpty()) content.put("jcr:description", description.trim());
        content.put("cq:templateType", "/libs/settings/dam/cfm/model-types/fragment");
        content.put("sling:resourceType", "dam/cfm/models/console/components/data/entity/default");
        content.put("sling:resourceSuperType", "dam/cfm/models/console/components/data/entity");
        content.put("cq:scaffolding", model.getPath() + "/jcr:content/model");
        content.put("cq:lastModified", Calendar.getInstance());
        content.put("cq:lastModifiedBy", userId());
        Resource jcrContent = resolver.create(model, "jcr:content", content);

        Map<String, Object> scaffolding = new HashMap<>();
        scaffolding.put("jcr:primaryType", "cq:PageContent");
        scaffolding.put("cq:targetPath", "/content/entities");
        scaffolding.put("sling:resourceType", "wcm/scaffolding/components/scaffolding");
        scaffolding.put("dataTypesConfig", "/mnt/overlay/settings/dam/cfm/models/formbuilderconfig/datatypes");
        scaffolding.put("maxGeneratedOrder", "20");
        Resource scaffold = resolver.create(jcrContent, "model", scaffolding);
        Resource dialog = resolver.create(scaffold, "cq:dialog",
                props("jcr:primaryType", "nt:unstructured", "sling:resourceType", "cq/gui/components/authoring/dialog"));
        Resource dialogContent = resolver.create(dialog, "content",
                props("jcr:primaryType", "nt:unstructured", "sling:resourceType", "granite/ui/components/coral/foundation/fixedcolumns"));
        resolver.create(dialogContent, "items", props("jcr:primaryType", "nt:unstructured"));
        resolver.commit();
        return model.getPath();
    }

    /** Paths of the fragments that use a model (up to {@code max}). */
    List<String> usage(String modelPath, int max) {
        List<String> out = new ArrayList<>();
        Resource dam = resolver.getResource("/content/dam");
        if (dam != null) collectUsage(dam, modelPath, max, out, new int[]{0});
        return out;
    }

    private void collectUsage(Resource r, String modelPath, int max, List<String> out, int[] seen) {
        if (out.size() >= max || ++seen[0] > 50_000) return;
        if (ContentFragmentImpl.isFragment(r)) {
            Resource data = r.getChild("jcr:content/data");
            if (data != null && modelPath.equals(data.getValueMap().get("cq:model", String.class))) out.add(r.getPath());
            return;
        }
        for (Resource child : r.getChildren()) {
            String n = child.getName();
            if (n.startsWith("jcr:") || "renditions".equals(n) || "rep:policy".equals(n)) continue;
            collectUsage(child, modelPath, max, out, seen);
        }
    }

    /** Deletes a model; refused while fragments use it, unless {@code force}. */
    void delete(String modelPath, boolean force) throws PersistenceException, FragmentStore.ValidationException {
        Resource model = model(modelPath);
        if (!force) {
            List<String> used = usage(modelPath, 5);
            if (!used.isEmpty()) {
                List<FragmentStore.FieldError> errors = new ArrayList<>();
                errors.add(new FragmentStore.FieldError("model", "Content fragments use this model, e.g. " + used.get(0)
                        + (used.size() > 1 ? " and " + (used.size() - 1) + "+ more" : "") + "."));
                throw new FragmentStore.ValidationException(errors);
            }
        }
        resolver.delete(model);
        resolver.commit();
    }

    /** Enables or disables a model (disabled models are not offered for new fragments). */
    void setEnabled(String modelPath, boolean enabled) throws PersistenceException {
        Resource content = model(modelPath).getChild("jcr:content");
        ModifiableValueMap p = content.adaptTo(ModifiableValueMap.class);
        if (p == null) throw new PersistenceException("Cannot modify " + content.getPath());
        p.put("cq:status", enabled ? "enabled" : "disabled");
        touch(p);
        resolver.commit();
    }

    // ------------------------------------------------------------------------------------------- save

    /** Writes title, description and the fields of a model, in the order given. */
    @SuppressWarnings("unchecked")
    void save(String modelPath, String title, String description, List<Object> fields) throws PersistenceException,
            FragmentStore.ValidationException {
        Resource model = model(modelPath);
        Resource items = model.getChild(ITEMS);
        if (items == null) throw new IllegalArgumentException("Not a content fragment model: " + modelPath);
        List<Map<String, Object>> list = new ArrayList<>();
        if (fields != null) for (Object o : fields) if (o instanceof Map) list.add((Map<String, Object>) o);
        validate(title, list);

        ModifiableValueMap content = model.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
        if (content == null) throw new PersistenceException("Cannot modify " + model.getPath());
        if (title != null) content.put("jcr:title", title.trim());
        if (description != null) {
            if (description.trim().isEmpty()) content.remove("jcr:description");
            else content.put("jcr:description", description.trim());
        }
        touch(content);

        // remove the items no longer sent
        Set<String> keep = new HashSet<>();
        for (Map<String, Object> f : list) if (str(f, "id") != null) keep.add(str(f, "id"));
        List<Resource> gone = new ArrayList<>();
        for (Resource item : items.getChildren()) if (!keep.contains(item.getName())) gone.add(item);
        for (Resource item : gone) resolver.delete(item);

        long stamp = System.currentTimeMillis();
        List<String> order = new ArrayList<>();
        for (Map<String, Object> f : list) {
            String id = str(f, "id");
            Resource item = id == null ? null : items.getChild(id);
            if (item == null) {
                String nodeName = Long.toString(stamp++);
                while (items.getChild(nodeName) != null) nodeName = Long.toString(stamp++);
                item = resolver.create(items, nodeName, props("jcr:primaryType", "nt:unstructured"));
            }
            write(item, f);
            order.add(item.getName());
        }
        order(items, order);
        ModifiableValueMap dialog = items.getParent().adaptTo(ModifiableValueMap.class);
        if (dialog != null) {
            dialog.put("jcr:lastModified", Calendar.getInstance());
            dialog.put("jcr:lastModifiedBy", userId());
        }
        resolver.commit();
    }

    private void validate(String title, List<Map<String, Object>> fields) throws FragmentStore.ValidationException {
        List<FragmentStore.FieldError> errors = new ArrayList<>();
        if (title != null && title.trim().isEmpty()) errors.add(new FragmentStore.FieldError("title", "A title is required."));
        Set<String> names = new HashSet<>();
        int i = 0;
        for (Map<String, Object> f : fields) {
            String key = "fields." + i++;
            String kind = str(f, "kind");
            if (kind == null || ModelJson.metaTypeOf(kind) == null) {
                errors.add(new FragmentStore.FieldError(key + ".kind", "Unknown data type " + kind + "."));
                continue;
            }
            String label = str(f, "label");
            if (label == null || label.trim().isEmpty()) errors.add(new FragmentStore.FieldError(key + ".label", "A label is required."));
            if ("tab".equals(kind)) continue;
            String name = str(f, "name");
            if (name == null || !ELEMENT_NAME.matcher(name).matches()) {
                errors.add(new FragmentStore.FieldError(key + ".name",
                        "Use letters, digits and '_', starting with a letter or '_'."));
            } else if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
                errors.add(new FragmentStore.FieldError(key + ".name", "\"" + name + "\" is reserved."));
            } else if (!names.add(name)) {
                errors.add(new FragmentStore.FieldError(key + ".name", "Another element is named " + name + "."));
            }
            if ("enumeration".equals(kind) && options(f).isEmpty()) {
                errors.add(new FragmentStore.FieldError(key + ".options", "Add at least one option."));
            }
            Map<String, Object> v = map(f, "validation");
            String regex = v == null ? null : str(v, "regex");
            if (regex != null && !regex.isEmpty()) {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    errors.add(new FragmentStore.FieldError(key + ".validation.regex", "Not a valid regular expression: " + e.getDescription() + "."));
                }
            }
            Double min = v == null ? null : num(v.get("min"));
            Double max = v == null ? null : num(v.get("max"));
            if (min != null && max != null && min > max) {
                errors.add(new FragmentStore.FieldError(key + ".validation.max", "The maximum is below the minimum."));
            }
        }
        if (!errors.isEmpty()) throw new FragmentStore.ValidationException(errors);
    }

    /** One field as a Granite item, in the shape AEM's model editor writes. */
    private void write(Resource item, Map<String, Object> f) throws PersistenceException {
        ModifiableValueMap p = item.adaptTo(ModifiableValueMap.class);
        if (p == null) throw new PersistenceException("Cannot modify " + item.getPath());
        String kind = str(f, "kind");
        // Same data type: change only what differs, so properties the editor does not show (translatable, unique,
        // fragmentmodelreference on rich text ...) and stored types (arrays, booleans) survive. A new type starts clean.
        if (!ModelJson.metaTypeOf(kind).equals(p.get("metaType", String.class))) for (String k : MANAGED) p.remove(k);
        boolean multiple = Boolean.TRUE.equals(f.get("multiple"));
        String label = str(f, "label").trim();
        set(p, "metaType", ModelJson.metaTypeOf(kind));
        if (!p.containsKey("renderReadOnly")) p.put("renderReadOnly", "false");
        if (!p.containsKey("showEmptyInReadOnly")) p.put("showEmptyInReadOnly", "true");
        set(p, "fieldDescription", str(f, "description"));
        set(p, "emptyText", str(f, "emptyText"));
        boolean required = Boolean.TRUE.equals(f.get("required")) && !"tab".equals(kind) && !"boolean".equals(kind);
        if (required != ModelJson.isOn(p.get("required"))) set(p, "required", required ? "on" : null);
        if (!"tab".equals(kind)) set(p, "name", str(f, "name"));
        // translation projects translate the elements marked translatable (AEM stores "true")
        if (f.containsKey("translatable") && !"tab".equals(kind)) {
            boolean t = Boolean.TRUE.equals(f.get("translatable"));
            if (t != ModelJson.isOn(p.get("translatable"))) set(p, "translatable", t ? "true" : null);
        }
        set(p, "fieldLabel", label);
        Object dflt = f.get("defaultValue");

        Resource options = item.getChild("optionsmultifield");
        if (options != null && !"enumeration".equals(kind)) resolver.delete(options);
        Resource datasource = item.getChild("datasource");
        if (datasource != null && !"enumeration".equals(kind)) resolver.delete(datasource);
        Resource field = item.getChild("field");
        if (field != null && !"fragment-reference".equals(kind)) resolver.delete(field);

        switch (kind) {
            case "text":
                set(p, "sling:resourceType", "granite/ui/components/coral/foundation/form/textfield");
                valueType(p, multiple ? "string[]" : "string");
                set(p, "maxlength", String.valueOf(longOr(f.get("maxLength"), 255L)));
                set(p, "value", dflt instanceof String ? (String) dflt : null);
                break;
            case "longtext":
                p.remove("fieldLabel");
                set(p, "sling:resourceType", "dam/cfm/admin/components/authoring/contenteditor/multieditor");
                if (!p.containsKey("checked")) p.put("checked", "false");
                set(p, "cfm-element", label);
                valueType(p, multiple ? "string[]" : "string", multiple ? "string/multiline[]" : "string/multiline");
                set(p, "default-mime-type", mime(str(f, "mimeType")));
                break;
            case "number": {
                boolean decimal = "double".equals(str(f, "valueType")) || "any".equals(str(f, "step"));
                set(p, "sling:resourceType", "granite/ui/components/coral/foundation/form/numberfield");
                set(p, "valueType", (decimal ? "double" : "long") + (multiple ? "[]" : ""));
                set(p, "typeHint", decimal ? "double" : "long");
                set(p, "step", decimal ? "any" : "1");
                Double d = num(dflt);
                Double old = num(p.get("value"));
                if (d == null) p.remove("value");
                else if (!d.equals(old)) p.put("value", decimal ? String.valueOf(d) : String.valueOf(d.longValue()));
                break;
            }
            case "boolean":
                set(p, "sling:resourceType", "granite/ui/components/coral/foundation/form/checkbox");
                set(p, "valueType", "boolean");
                set(p, "checked", Boolean.TRUE.equals(dflt) || "true".equals(String.valueOf(dflt)) ? "true" : "false");
                break;
            case "date": {
                String type = str(f, "dateType");
                if (!"date".equals(type) && !"time".equals(type)) type = "datetime";
                set(p, "sling:resourceType", "dam/cfm/models/editor/components/datatypes/datepicker");
                set(p, "type", type);
                set(p, "valueType", "calendar/" + type);
                set(p, "displayedFormat", "date".equals(type) ? "YYYY-MM-DD" : "time".equals(type) ? "HH:mm" : "YYYY-MM-DD HH:mm");
                break;
            }
            case "enumeration":
                set(p, "sling:resourceType", multiple
                        ? "dam/cfm/admin/components/authoring/contenteditor/multicheckbox"
                        : "granite/ui/components/coral/foundation/form/select");
                set(p, "valueType", multiple ? "string[]" : "string");
                if (!p.containsKey("emptyOption")) p.put("emptyOption", Boolean.TRUE);
                writeOptions(item, options(f));
                if (item.getChild("datasource") == null) {
                    resolver.create(item, "datasource", props("jcr:primaryType", "nt:unstructured",
                            "sling:resourceType", "dam/cfm/admin/components/datasources/optionrendererenumeration",
                            "variant", "default"));
                }
                set(p, "value", dflt instanceof String ? (String) dflt : null);
                break;
            case "tags":
                set(p, "sling:resourceType", "cq/gui/components/coral/common/form/tagfield");
                set(p, "valueType", "string[]");
                set(p, "rootPath", str(f, "rootPath"));
                break;
            case "content-reference":
                set(p, "sling:resourceType", "dam/cfm/models/editor/components/contentreference");
                valueType(p, "string/reference" + (multiple ? "[]" : ""), multiple ? "string[]" : "string");
                set(p, "rootPath", root(str(f, "rootPath")));
                set(p, "nameSuffix", "contentReference");
                set(p, "filter", "hierarchy");
                set(p, "showThumbnail", "true");
                break;
            case "fragment-reference": {
                set(p, "sling:resourceType", "dam/cfm/models/editor/components/fragmentreference");
                set(p, "valueType", "string/content-fragment" + (multiple ? "[]" : ""));
                String root = root(str(f, "rootPath"));
                set(p, "rootPath", root);
                set(p, "nameSuffix", "contentReference");
                set(p, "filter", "hierarchy");
                if (!p.containsKey("allowNew")) p.put("allowNew", Boolean.TRUE);
                List<String> allowed = strings(f.get("allowedModels"));
                if (allowed.isEmpty()) p.remove("fragmentmodelreference");
                else if (!allowed.equals(strings(p.get("fragmentmodelreference", String[].class) == null ? null
                        : java.util.Arrays.asList(p.get("fragmentmodelreference", String[].class))))) {
                    p.put("fragmentmodelreference", allowed.toArray(new String[0]));
                }
                Resource fieldNode = item.getChild("field");
                if (fieldNode == null) resolver.create(item, "field", props("jcr:primaryType", "nt:unstructured", "rootPath", root));
                else {
                    ModifiableValueMap fp = fieldNode.adaptTo(ModifiableValueMap.class);
                    if (fp != null) set(fp, "rootPath", root);
                }
                break;
            }
            case "json":
                set(p, "sling:resourceType", "dam/cfm/models/editor/components/datatypes/json");
                set(p, "valueType", "string");
                break;
            case "tab":
                set(p, "sling:resourceType", "dam/cfm/models/editor/components/tabplaceholder");
                set(p, "valueType", "string");
                break;
            default:
                throw new IllegalArgumentException("Unknown data type " + kind);
        }

        // validation in granite:data, as AEM
        Map<String, Object> v = map(f, "validation");
        String regex = v == null ? null : str(v, "regex");
        String message = v == null ? null : str(v, "message");
        Double min = v == null ? null : num(v.get("min"));
        Double max = v == null ? null : num(v.get("max"));
        Resource data = item.getChild("granite:data");
        if (data == null) data = resolver.create(item, "granite:data", props("jcr:primaryType", "nt:unstructured"));
        ModifiableValueMap d = data.adaptTo(ModifiableValueMap.class);
        if (d == null) throw new PersistenceException("Cannot modify " + data.getPath());
        String validation = null;
        String validationRegex = null;
        String numberMin = null;
        String numberMax = null;
        if (regex != null && !regex.isEmpty() && ("text".equals(kind) || "longtext".equals(kind))) {
            validation = "cfm.validation.custom";
            validationRegex = regex;
        } else if ("number".equals(kind) && (min != null || max != null)) {
            validation = min != null ? "cfm.validation.number.min" : "cfm.validation.number.max";
            numberMin = min == null ? null : trim(min);
            numberMax = max == null ? null : trim(max);
        } else if ("content-reference".equals(kind)) {
            validation = "cfm.validation.contenttype.image".equals(v == null ? null : str(v, "type"))
                    ? "cfm.validation.contenttype.image" : "cfm.validation.contenttype.any";
        }
        set(p, "validation", validation);
        set(d, "validationRegex", validationRegex);
        if (numberMin == null || num(d.get("number-min")) == null || !num(numberMin).equals(num(d.get("number-min")))) set(d, "number-min", numberMin);
        if (numberMax == null || num(d.get("number-max")) == null || !num(numberMax).equals(num(d.get("number-max")))) set(d, "number-max", numberMax);
        set(d, "customErrorMsg", validation == null ? null : message);
    }

    private void writeOptions(Resource item, List<String[]> options) throws PersistenceException {
        Resource existing = item.getChild("optionsmultifield");
        if (existing != null) resolver.delete(existing);
        Resource container = resolver.create(item, "optionsmultifield", props("jcr:primaryType", "nt:unstructured"));
        int i = 0;
        for (String[] o : options) {
            resolver.create(container, "item" + i++, props("jcr:primaryType", "nt:unstructured",
                    "fieldValue", o[0], "fieldLabel", o[1]));
        }
    }

    private void order(Resource items, List<String> order) throws PersistenceException {
        List<String> current = new ArrayList<>();
        for (Resource item : resolver.getResource(items.getPath()).getChildren()) current.add(item.getName());
        if (current.equals(order)) return;
        try {
            // walk from the end: each item goes before the one placed after it
            for (int i = order.size() - 2; i >= 0; i--) {
                resolver.orderBefore(items, order.get(i), order.get(i + 1));
            }
        } catch (UnsupportedOperationException e) {
            // A resource provider without ordering: copy each item, in order, to a new node at the end. Item node
            // names are ids nothing refers to (fragments and GraphQL go by the element name).
            String parent = items.getPath();
            long stamp = System.currentTimeMillis();
            for (String name : order) {
                Resource item = resolver.getResource(parent + "/" + name);
                if (item == null) continue;
                Node copy = Node.of(item);
                resolver.delete(item);
                String fresh = Long.toString(stamp++);
                while (resolver.getResource(parent + "/" + fresh) != null) fresh = Long.toString(stamp++);
                copy.create(resolver, resolver.getResource(parent), fresh);
            }
        }
    }

    /** A detached copy of a resource tree (properties and children, in order). */
    private static final class Node {
        final Map<String, Object> props = new HashMap<>();
        final List<Map.Entry<String, Node>> children = new ArrayList<>();

        static Node of(Resource r) {
            Node n = new Node();
            for (Map.Entry<String, Object> e : r.getValueMap().entrySet()) {
                if (!"jcr:uuid".equals(e.getKey())) n.props.put(e.getKey(), e.getValue());
            }
            for (Resource c : r.getChildren()) n.children.add(new java.util.AbstractMap.SimpleEntry<>(c.getName(), of(c)));
            return n;
        }

        void create(ResourceResolver resolver, Resource parent, String name) throws PersistenceException {
            Resource created = resolver.create(parent, name, props);
            for (Map.Entry<String, Node> c : children) c.getValue().create(resolver, created, c.getKey());
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private Resource model(String path) {
        Resource model = path == null ? null : resolver.getResource(path);
        if (model == null || !path.startsWith("/conf/") || !path.contains("/" + MODELS + "/") || model.getChild("jcr:content") == null) {
            throw new IllegalArgumentException("No content fragment model at " + path);
        }
        return model;
    }

    private Resource folders(Resource parent, String... names) throws PersistenceException {
        Resource r = parent;
        for (String n : names) {
            Resource child = r.getChild(n);
            r = child != null ? child : resolver.create(r, n, props("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }

    private void touch(ModifiableValueMap content) {
        content.put("cq:lastModified", Calendar.getInstance());
        content.put("cq:lastModifiedBy", userId());
    }

    private String userId() {
        String id = resolver.getUserID();
        return id == null ? "anonymous" : id;
    }

    private static String root(String path) {
        return path == null || path.trim().isEmpty() || !path.startsWith("/content") ? "/content/dam" : path.trim();
    }

    private static String mime(String m) {
        return "text/x-markdown".equals(m) || "text/plain".equals(m) ? m : "text/html";
    }

    private static String trim(Double d) {
        return d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    /** Sets {@code valueType} unless the stored one is equivalent (WKND stores e.g. {@code string} for references). */
    private static void valueType(ModifiableValueMap p, String desired, String... equivalent) {
        String current = p.get("valueType", String.class);
        if (desired.equals(current) || Arrays.asList(equivalent).contains(current)) return;
        p.put("valueType", desired);
    }

    /** Sets a text property when it differs from the stored one (kept as stored otherwise); removes it when empty. */
    private static void set(ModifiableValueMap p, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            p.remove(key);
            return;
        }
        if (!value.equals(p.get(key, String.class))) p.put(key, value);
    }

    @SuppressWarnings("unchecked")
    private static List<String[]> options(Map<String, Object> f) {
        List<String[]> out = new ArrayList<>();
        Object o = f.get("options");
        if (!(o instanceof Collection)) return out;
        Set<String> seen = new HashSet<>();
        for (Object e : (Collection<Object>) o) {
            String value;
            String label;
            if (e instanceof Map) {
                value = str((Map<String, Object>) e, "value");
                label = str((Map<String, Object>) e, "label");
            } else {
                value = e == null ? null : e.toString();
                label = null;
            }
            if (value == null || value.trim().isEmpty() || !seen.add(value.trim())) continue;
            out.add(new String[]{value.trim(), label == null || label.trim().isEmpty() ? value.trim() : label.trim()});
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection) {
            for (Object e : (Collection<Object>) o) if (e != null && !e.toString().isEmpty()) out.add(e.toString());
        } else if (o instanceof String && !((String) o).isEmpty()) {
            out.add((String) o);
        }
        return out;
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    private static Double num(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String && !((String) v).trim().isEmpty()) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static long longOr(Object v, long dflt) {
        Double d = num(v);
        return d == null || d < 1 ? dflt : d.longValue();
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
