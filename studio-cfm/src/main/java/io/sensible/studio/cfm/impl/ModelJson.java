package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content fragment models as JSON for the Sensible Studio fragment editor.
 *
 * <p>A model is stored as a Granite form ({@code jcr:content/model/cq:dialog/content/items}). The editor does not
 * render Granite: it gets one plain field description per element, with the kind of input it needs.</p>
 */
public final class ModelJson {

    private ModelJson() {
    }

    /** Model kinds (from {@code metaType}) mapped to the editor's field kinds. */
    public static String kindOf(String metaType) {
        if (metaType == null) return "text";
        switch (metaType) {
            case "text-single": return "text";
            case "text-multi": return "longtext";
            case "number": return "number";
            case "boolean": return "boolean";
            case "date": return "date";
            case "enumeration": return "enumeration";
            case "tags": return "tags";
            case "reference":
            case "content-reference": return "content-reference";
            case "fragment-reference": return "fragment-reference";
            case "json": return "json";
            case "tab-placeholder": return "tab";
            default: return "unknown";
        }
    }

    /** The {@code metaType} AEM stores for an editor field kind; null for kinds that cannot be written. */
    public static String metaTypeOf(String kind) {
        if (kind == null) return null;
        switch (kind) {
            case "text": return "text-single";
            case "longtext": return "text-multi";
            case "number": return "number";
            case "boolean": return "boolean";
            case "date": return "date";
            case "enumeration": return "enumeration";
            case "tags": return "tags";
            case "content-reference": return "reference";
            case "fragment-reference": return "fragment-reference";
            case "json": return "json";
            case "tab": return "tab-placeholder";
            default: return null;
        }
    }

    /** Every model below {@code /conf/<site>/settings/dam/cfm/models} (also nested confs, up to depth 4). */
    public static List<Map<String, Object>> listModels(ResourceResolver resolver) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource conf = resolver.getResource("/conf");
        if (conf != null) collectModels(conf, 0, out);
        return out;
    }

    private static void collectModels(Resource confNode, int depth, List<Map<String, Object>> out) {
        Resource models = confNode.getChild("settings/dam/cfm/models");
        if (models != null) {
            for (Resource model : models.getChildren()) {
                if (model.getChild(FragmentModel.MODEL_ITEMS) == null) continue;
                out.add(summary(model, confNode.getPath()));
            }
        }
        if (depth >= 4) return;
        for (Resource child : confNode.getChildren()) {
            if ("settings".equals(child.getName()) || child.getName().contains(":")) continue;
            collectModels(child, depth + 1, out);
        }
    }

    static Map<String, Object> summary(Resource model, String conf) {
        ValueMap content = FragmentModel.valueMap(model.getChild("jcr:content"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", model.getPath());
        m.put("title", content.get("jcr:title", model.getName()));
        m.put("description", content.get("jcr:description", ""));
        m.put("conf", conf);
        m.put("enabled", !"disabled".equals(content.get("cq:status", "enabled")));
        return m;
    }

    /** The full model: summary plus fields, or null when there is no model at {@code path}. */
    public static Map<String, Object> model(ResourceResolver resolver, String path) {
        Resource model = path == null ? null : resolver.getResource(path);
        if (model == null) return null;
        Resource items = model.getChild(FragmentModel.MODEL_ITEMS);
        if (items == null) return null;
        String conf = path.contains("/settings/") ? path.substring(0, path.indexOf("/settings/")) : null;
        Map<String, Object> m = summary(model, conf);
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Resource item : items.getChildren()) {
            Map<String, Object> f = field(item);
            if (f != null) fields.add(f);
        }
        m.put("fields", fields);
        return m;
    }

    private static Map<String, Object> field(Resource item) {
        ValueMap p = item.getValueMap();
        String metaType = p.get("metaType", String.class);
        String kind = kindOf(metaType);
        String name = p.get("name", String.class);
        if (name != null && name.startsWith("./")) name = name.substring(2);
        if (name == null && !"tab".equals(kind)) return null;

        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", item.getName());
        f.put("name", name != null ? name : item.getName());
        f.put("kind", kind);
        f.put("metaType", metaType);
        String valueType = p.get("valueType", "string");
        f.put("valueType", valueType);
        String label = p.get("fieldLabel", String.class);
        if (label == null) label = p.get("cfm-element", String.class);
        f.put("label", label != null ? label : name);
        putIf(f, "description", p.get("fieldDescription", String.class));
        putIf(f, "emptyText", p.get("emptyText", String.class));
        f.put("required", isOn(p.get("required")));
        if (!"tab".equals(kind)) f.put("translatable", isOn(p.get("translatable")));
        boolean multiple = valueType.endsWith("[]") || "dam/cfm/admin/components/authoring/contenteditor/multicheckbox"
                .equals(p.get("sling:resourceType", String.class));
        f.put("multiple", multiple);
        putIf(f, "defaultValue", p.get("value"));
        if ("boolean".equals(kind)) putIf(f, "defaultValue", p.get("checked"));
        putIf(f, "maxLength", p.get("maxlength", Long.class));
        putIf(f, "step", p.get("step", String.class));
        putIf(f, "mimeType", p.get("default-mime-type", String.class));
        putIf(f, "rootPath", p.get("rootPath", String.class));
        putIf(f, "dateType", p.get("type", String.class));
        putIf(f, "displayAs", p.get("sling:resourceType", String.class));

        String[] allowed = p.get("fragmentmodelreference", String[].class);
        if (allowed != null && allowed.length > 0 && "fragment-reference".equals(kind)) f.put("allowedModels", allowed);

        Resource options = item.getChild("optionsmultifield");
        if (options != null) {
            List<Map<String, Object>> opts = new ArrayList<>();
            for (Resource o : options.getChildren()) {
                ValueMap op = o.getValueMap();
                String value = op.get("fieldValue", String.class);
                if (value == null) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", value);
                entry.put("label", op.get("fieldLabel", value));
                opts.add(entry);
            }
            f.put("options", opts);
        } else if (p.get("options") != null) {
            // older models: options as one comma separated string
            List<Map<String, Object>> opts = new ArrayList<>();
            for (String o : p.get("options", String.class).split(",")) {
                if (o.trim().isEmpty()) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", o.trim());
                entry.put("label", o.trim());
                opts.add(entry);
            }
            f.put("options", opts);
        }

        Resource data = item.getChild("granite:data");
        Map<String, Object> validation = new LinkedHashMap<>();
        if (data != null) {
            ValueMap d = data.getValueMap();
            putIf(validation, "regex", d.get("validationRegex", String.class));
            putIf(validation, "message", d.get("customErrorMsg", String.class));
            putIf(validation, "min", d.get("number-min", Double.class));
            putIf(validation, "max", d.get("number-max", Double.class));
        }
        if (p.get("validation") != null) validation.put("type", p.get("validation", String.class));
        if (!validation.isEmpty()) f.put("validation", validation);
        return f;
    }

    public static boolean isOn(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && ("on".equalsIgnoreCase(v.toString()) || "true".equalsIgnoreCase(v.toString()));
    }

    private static void putIf(Map<String, Object> m, String key, Object value) {
        if (value == null) return;
        if (value instanceof String && ((String) value).isEmpty()) return;
        m.put(key, value);
    }
}
