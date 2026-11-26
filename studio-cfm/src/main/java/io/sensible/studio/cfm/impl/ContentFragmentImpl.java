package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.ContentVariation;
import com.adobe.cq.dam.cfm.DataType;
import com.adobe.cq.dam.cfm.ElementTemplate;
import com.adobe.cq.dam.cfm.FragmentData;
import com.adobe.cq.dam.cfm.FragmentTemplate;
import com.adobe.cq.dam.cfm.VariationDef;
import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A structured content fragment in the AEM storage format:
 *
 * <pre>
 * fragment                       dam:Asset
 *   jcr:content                  contentFragment=true, jcr:title, jcr:description
 *     data                       cq:model=/conf/&lt;site&gt;/settings/dam/cfm/models/&lt;model&gt;
 *       master                   one property per element; text elements also &lt;name&gt;@ContentType
 *       &lt;variation&gt;            jcr:title, jcr:description, same properties as master
 *     metadata                   cq:tags, ...
 * </pre>
 */
public final class ContentFragmentImpl extends SlingAdaptable implements ContentFragment {

    public static final String MASTER = "master";
    public static final String CONTENT_TYPE_SUFFIX = "@ContentType";

    private final Resource asset;
    private final Resource content;
    private final Resource data;
    private final FragmentModel model;

    ContentFragmentImpl(Resource asset, FragmentModel model) {
        this.asset = asset;
        this.content = asset.getChild("jcr:content");
        this.data = content == null ? null : content.getChild("data");
        this.model = model;
    }

    /** Whether {@code resource} is a content fragment asset. */
    public static boolean isFragment(Resource resource) {
        if (resource == null) return false;
        Resource content = resource.getChild("jcr:content");
        return content != null && content.getValueMap().get("contentFragment", false);
    }

    static ContentFragmentImpl of(Resource asset) {
        Resource data = asset.getChild("jcr:content/data");
        String modelPath = data == null ? null : data.getValueMap().get("cq:model", String.class);
        FragmentModel model = FragmentModel.load(asset.getResourceResolver(), modelPath);
        if (model == null) model = FragmentModel.inferred(data == null ? null : data.getChild(MASTER));
        return new ContentFragmentImpl(asset, model);
    }

    Resource variationResource(String variation) {
        if (data == null) return null;
        return data.getChild(variation == null || variation.isEmpty() ? MASTER : variation);
    }

    FragmentModel getModel() {
        return model;
    }

    // ------------------------------------------------------------------------------------------- API

    @Override
    public String getName() {
        return asset.getName();
    }

    @Override
    public String getTitle() {
        return FragmentModel.valueMap(content).get("jcr:title", asset.getName());
    }

    @Override
    public String getDescription() {
        return FragmentModel.valueMap(content).get("jcr:description", "");
    }

    @Override
    public Iterator<ContentElement> getElements() {
        List<ContentElement> out = new ArrayList<>();
        for (FragmentModel.ElementDef def : model.getElements()) out.add(new Element(def));
        return out.iterator();
    }

    @Override
    public boolean hasElement(String elementName) {
        return model.getElement(elementName) != null;
    }

    @Override
    public ContentElement getElement(String elementName) {
        if (elementName == null || elementName.isEmpty()) {
            // AEM returns the main (first) element for a null/empty name
            List<FragmentModel.ElementDef> all = model.getElements();
            return all.isEmpty() ? null : new Element(all.get(0));
        }
        FragmentModel.ElementDef def = model.getElement(elementName);
        return def == null ? null : new Element(def);
    }

    @Override
    public FragmentTemplate getTemplate() {
        return model.getResource() == null ? null : new Template();
    }

    @Override
    public Iterator<VariationDef> listAllVariations() {
        List<VariationDef> out = new ArrayList<>();
        if (data != null) {
            for (Resource child : data.getChildren()) {
                if (MASTER.equals(child.getName())) continue;
                ValueMap p = child.getValueMap();
                out.add(new Variation(child.getName(), p.get("jcr:title", child.getName()), p.get("jcr:description", "")));
            }
        }
        return out.iterator();
    }

    @Override
    public Iterator<Resource> getAssociatedContent() {
        return Collections.emptyIterator();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A> A adaptTo(Class<A> type) {
        if (type == Resource.class) return (A) asset;
        return super.adaptTo(type);
    }

    // ------------------------------------------------------------------------------------------- parts

    /** One element; its value comes from the master variation unless a variation is requested. */
    final class Element extends SlingAdaptable implements ContentElement {
        private final FragmentModel.ElementDef def;

        Element(FragmentModel.ElementDef def) {
            this.def = def;
        }

        @Override
        public String getName() {
            return def.name;
        }

        @Override
        public String getTitle() {
            return def.title;
        }

        @Override
        public String getContentType() {
            return contentTypeIn(variationResource(MASTER));
        }

        @Override
        public FragmentData getValue() {
            return dataIn(variationResource(MASTER));
        }

        @Override
        public ContentVariation getVariation(String variationName) {
            if (variationName == null || variationName.isEmpty() || MASTER.equals(variationName)) return null;
            Resource variation = variationResource(variationName);
            if (variation == null) return null;
            ValueMap p = variation.getValueMap();
            return new ElementVariation(this, variationName, p.get("jcr:title", variationName), variation);
        }

        String contentTypeIn(Resource variation) {
            String stored = FragmentModel.valueMap(variation).get(def.name + CONTENT_TYPE_SUFFIX, String.class);
            if (stored != null) return stored;
            if (def.defaultContentType != null) return def.defaultContentType;
            return "text/plain";
        }

        FragmentData dataIn(Resource variation) {
            Object raw = FragmentModel.valueMap(variation).get(def.name);
            DataType type = new DataTypeImpl(def.valueType);
            return new FragmentDataImpl(raw, type, contentTypeIn(variation));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> A adaptTo(Class<A> type) {
            if (type == ElementTemplate.class) return (A) new ElementTemplateImpl(def);
            return super.adaptTo(type);
        }
    }

    /** An element's value in a non-master variation. */
    final class ElementVariation implements ContentVariation {
        private final Element element;
        private final String name;
        private final String title;
        private final Resource variation;

        ElementVariation(Element element, String name, String title, Resource variation) {
            this.element = element;
            this.name = name;
            this.title = title;
            this.variation = variation;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getTitle() {
            return title;
        }

        @Override
        public String getContentType() {
            return element().contentTypeIn(variation);
        }

        @Override
        public FragmentData getValue() {
            return element().dataIn(variation);
        }

        private Element element() {
            return element;
        }
    }

    private static final class Variation implements VariationDef {
        private final String name;
        private final String title;
        private final String description;

        Variation(String name, String title, String description) {
            this.name = name;
            this.title = title;
            this.description = description;
        }

        @Override public String getName() { return name; }
        @Override public String getTitle() { return title; }
        @Override public String getDescription() { return description; }
    }

    /** The model, adaptable to the model's {@code jcr:content/model} resource like AEM's structured templates. */
    private final class Template extends SlingAdaptable implements FragmentTemplate {
        @Override
        public String getTitle() {
            return model.getTitle();
        }

        @Override
        public String getDescription() {
            return model.getDescription();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <A> A adaptTo(Class<A> type) {
            if (type == Resource.class) {
                Resource modelNode = model.getResource().getChild("jcr:content/model");
                return (A) (modelNode != null ? modelNode : model.getResource());
            }
            return super.adaptTo(type);
        }
    }

    private static final class ElementTemplateImpl extends SlingAdaptable implements ElementTemplate {
        private final FragmentModel.ElementDef def;

        ElementTemplateImpl(FragmentModel.ElementDef def) {
            this.def = def;
        }

        @Override public String getName() { return def.name; }
        @Override public String getTitle() { return def.title; }
    }
}
