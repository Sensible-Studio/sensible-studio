/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.adobe.cq.wcm.style.ComponentStyleInfo;
import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentContext;

/**
 * The wrapper element around an included component: {@code <div class="title ...">}. Its class
 * list is the component name, the component's {@code cq:cssClass}, the Style System classes the
 * author applied and any classes added to the component context. Components opt out with
 * {@code cq:noDecoration}; {@code cq:htmlTag/cq:tagName} changes the element.
 */
final class Decoration {

    final String tagName;
    final String cssClass;
    final String attributes;

    private Decoration(String tagName, String cssClass, String attributes) {
        this.tagName = tagName;
        this.cssClass = cssClass;
        this.attributes = attributes;
    }

    /** @return a copy using another element ({@code data-sly-resource @ decorationTagName}) */
    Decoration withTag(String tag) {
        return new Decoration(tag, cssClass, attributes);
    }

    /** @return a copy with more classes ({@code data-sly-resource @ cssClassName}) */
    Decoration withClasses(String extra) {
        Set<String> classes = new LinkedHashSet<>(Arrays.asList(cssClass.split("\\s+")));
        add(classes, extra);
        return new Decoration(tagName, String.join(" ", classes).trim(), attributes);
    }

    /** @return a copy carrying the editor's {@code data-studio-*} attributes */
    Decoration withEditMeta(EditMeta meta) {
        return new Decoration(tagName, cssClass, meta.attributes());
    }

    /**
     * The decoration of a component rendered by a container (grid): the component chain only,
     * like AEM's {@code <div class="teaser cmp-teaser--featured">}.
     *
     * @return the decoration, or {@code null} if the component is not decorated
     */
    static Decoration of(Resource resource, Component component, ComponentContext context, String... extraClasses) {
        return build(resource, component, context, null, extraClasses);
    }

    /**
     * The decoration of an included component ({@code data-sly-resource}, {@code cq:include}): AEM
     * names the cell after the included resource, so the class list starts with it
     * ({@code <div class="root container responsivegrid">}).
     */
    static Decoration ofInclude(Resource resource, Component component, ComponentContext context) {
        return build(resource, component, context, resource.getName());
    }

    /**
     * The component's name and those of its super components, each once, in order: AEM's
     * {@code <div class="text parbase section">}. Also the page's {@code page basicpage}.
     */
    static java.util.List<String> componentChain(Component component) {
        java.util.List<String> names = new java.util.ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (Component c = component; c != null && seenPaths.add(c.getPath()); c = c.getSuperComponent()) {
            String name = c.getName();
            if (name != null && !name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
            if (names.size() > 20) {
                break;
            }
        }
        return names;
    }

    private static Decoration build(Resource resource, Component component, ComponentContext context, String cellName,
            String... extraClasses) {
        if (component == null) {
            return null;
        }
        ValueMap cp = component.getProperties();
        if (cp.get("cq:noDecoration", false)) {
            return null;
        }
        String tag = "div";
        Set<String> classes = new LinkedHashSet<>();
        if (cellName != null && !cellName.trim().isEmpty()) {
            classes.add(cellName.trim());
        }
        classes.addAll(componentChain(component));
        String tagName = null;
        String tagClass = null;
        boolean hasTag = false;
        if (component instanceof ComponentDefinitions.Snapshot) {
            ComponentDefinitions.Snapshot snap = (ComponentDefinitions.Snapshot) component;
            hasTag = snap.hasHtmlTag;
            tagName = snap.htmlTagName;
            tagClass = snap.htmlTagClass;
        } else {
            Resource def = component.adaptTo(Resource.class);
            Resource htmlTag = def == null ? null : def.getChild("cq:htmlTag");
            if (htmlTag != null) {
                hasTag = true;
                tagName = htmlTag.getValueMap().get("cq:tagName", String.class);
                tagClass = htmlTag.getValueMap().get("class", String.class);
            }
        }
        if (hasTag) {
            if (tagName != null) {
                if (tagName.trim().isEmpty()) {
                    return null;
                }
                tag = tagName.trim();
            }
            add(classes, tagClass);
        }
        add(classes, cp.get("cq:cssClass", String.class));
        ComponentStyleInfo style = resource.adaptTo(ComponentStyleInfo.class);
        if (style != null) {
            add(classes, style.getAppliedCssClasses());
        }
        if (context != null) {
            classes.addAll(context.getCssClassNames());
        }
        for (String extra : extraClasses) {
            add(classes, extra);
        }
        return new Decoration(tag, String.join(" ", classes), "");
    }

    private static void add(Set<String> classes, String value) {
        if (value != null && !value.trim().isEmpty()) {
            classes.addAll(Arrays.asList(value.trim().split("\\s+")));
        }
    }

    String open() {
        return "<" + tagName + " class=\"" + escape(cssClass) + "\"" + attributes + ">";
    }

    String close() {
        return "</" + tagName + ">";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
