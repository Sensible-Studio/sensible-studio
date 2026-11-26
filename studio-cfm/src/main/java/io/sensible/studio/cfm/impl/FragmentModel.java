package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A content fragment model, read from {@code <model>/jcr:content/model/cq:dialog/content/items}.
 *
 * <p>The model is stored as a Granite form: every item is one element of the fragment, with its name in
 * {@code name}, its data type in {@code valueType} ({@code string}, {@code string/multiline}, {@code string[]},
 * {@code long}, {@code double}, {@code boolean}, {@code calendar/datetime}, {@code string/reference},
 * {@code string/content-fragment}, ...) and its kind in {@code metaType}. Element order is the item order.</p>
 */
public final class FragmentModel {

    public static final String MODEL_ITEMS = "jcr:content/model/cq:dialog/content/items";

    private final Resource resource;
    private final String title;
    private final String description;
    private final Map<String, ElementDef> elements;

    private FragmentModel(Resource resource, String title, String description, Map<String, ElementDef> elements) {
        this.resource = resource;
        this.title = title;
        this.description = description;
        this.elements = elements;
    }

    /** One element of the model. */
    static final class ElementDef {
        final String name;
        final String title;
        final String metaType;
        final String valueType;
        /** {@code default-mime-type} of text elements, e.g. text/html. */
        final String defaultContentType;
        /** The model marks the element {@code translatable}: translation projects translate it. */
        final boolean translatable;

        ElementDef(String name, String title, String metaType, String valueType, String defaultContentType) {
            this(name, title, metaType, valueType, defaultContentType, false);
        }

        ElementDef(String name, String title, String metaType, String valueType, String defaultContentType, boolean translatable) {
            this.name = name;
            this.title = title;
            this.metaType = metaType;
            this.valueType = valueType;
            this.defaultContentType = defaultContentType;
            this.translatable = translatable;
        }

        boolean isText() {
            return metaType == null || metaType.startsWith("text");
        }
    }

    /** Loads the model at {@code modelPath} (the cq:Template node), or null when it does not exist. */
    static FragmentModel load(ResourceResolver resolver, String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) return null;
        Resource model = resolver.getResource(modelPath);
        if (model == null) return null;
        ValueMap content = valueMap(model.getChild("jcr:content"));
        Map<String, ElementDef> elements = new LinkedHashMap<>();
        Resource items = model.getChild(MODEL_ITEMS);
        if (items != null) {
            for (Resource item : items.getChildren()) {
                ValueMap p = item.getValueMap();
                String name = p.get("name", String.class);
                if (name == null || name.isEmpty()) continue; // tab placeholders and other layout items
                if (name.startsWith("./")) name = name.substring(2);
                String label = p.get("fieldLabel", String.class);
                if (label == null) label = p.get("cfm-element", String.class);
                elements.put(name, new ElementDef(name, label != null ? label : name,
                        p.get("metaType", String.class), p.get("valueType", "string"),
                        p.get("default-mime-type", String.class), ModelJson.isOn(p.get("translatable"))));
            }
        }
        return new FragmentModel(model, content.get("jcr:title", model.getName()),
                content.get("jcr:description", String.class), Collections.unmodifiableMap(elements));
    }

    /**
     * Elements of a fragment whose model cannot be read: every plain property of the master variation.
     * Keeps fragments usable (rendering, export) when a model was deleted or not installed.
     */
    static FragmentModel inferred(Resource master) {
        Map<String, ElementDef> elements = new LinkedHashMap<>();
        if (master != null) {
            for (String key : master.getValueMap().keySet()) {
                if (key.contains(":") || key.contains("@")) continue;
                Object v = master.getValueMap().get(key);
                String type = v instanceof String[] ? "string[]" : v instanceof Long ? "long" : v instanceof Double ? "double"
                        : v instanceof Boolean ? "boolean" : v instanceof java.util.Calendar ? "calendar" : "string";
                elements.put(key, new ElementDef(key, key, null, type, null));
            }
        }
        return new FragmentModel(null, null, null, Collections.unmodifiableMap(elements));
    }

    Resource getResource() { return resource; }
    String getTitle() { return title; }
    String getDescription() { return description; }

    List<ElementDef> getElements() {
        return new ArrayList<>(elements.values());
    }

    ElementDef getElement(String name) {
        return elements.get(name);
    }

    public static ValueMap valueMap(Resource r) {
        return r == null ? ValueMap.EMPTY : r.getValueMap();
    }
}
