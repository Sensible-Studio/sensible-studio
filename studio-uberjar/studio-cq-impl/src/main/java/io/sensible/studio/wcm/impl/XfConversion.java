/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.WCMException;

/**
 * "Convert to experience fragment variation", like AEM's: the selected components move into a new
 * experience fragment (a {@code cq/experience-fragments/components/experiencefragment} page with a
 * {@code master} variation made from an experience fragment template), and an Experience Fragment
 * component that references the variation takes their place in the page.
 */
final class XfConversion {

    static final String XF_TYPE = "cq/experience-fragments/components/experiencefragment";
    static final String XF_TEMPLATE = "/libs/cq/experience-fragments/components/experiencefragment/template";

    private XfConversion() {
    }

    /** Experience fragment templates: those whose initial content is a variation ({@code cq:xfVariantType}), allowed below {@code parent}. */
    static List<Map<String, Object>> templates(ResourceResolver resolver, String parent) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource conf = resolver.getResource("/conf");
        if (conf == null) {
            return out;
        }
        for (Resource site : conf.getChildren()) {
            Resource templates = site.getChild("settings/wcm/templates");
            if (templates == null) {
                continue;
            }
            for (Resource t : templates.getChildren()) {
                Resource initial = t.getChild("initial/jcr:content");
                Resource content = t.getChild("jcr:content");
                if (initial == null || content == null || initial.getValueMap().get("cq:xfVariantType", String.class) == null) {
                    continue;
                }
                if (!"enabled".equals(content.getValueMap().get("status", "enabled"))) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", t.getPath());
                m.put("title", content.getValueMap().get("jcr:title", t.getName()));
                m.put("variantType", initial.getValueMap().get("cq:xfVariantType", String.class));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * @param paths the components (siblings, in order); the fragment takes the place of the first
     * @param parent the folder or fragment page the new fragment goes under
     * @param resourceType the project's Experience Fragment component (an allowed component of the container)
     * @return the fragment page, its master variation and the new component's path
     */
    static Map<String, Object> convert(ResourceResolver resolver, String[] paths, String parent, String title, String template,
            String resourceType) throws WCMException, PersistenceException {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("No component to convert");
        }
        if (parent == null || title == null || title.trim().isEmpty() || template == null || resourceType == null) {
            throw new IllegalArgumentException("parent, title, template and resourceType are required");
        }
        List<Resource> components = new ArrayList<>();
        for (String p : paths) {
            Resource r = resolver.getResource(p);
            if (r == null) {
                throw new IllegalArgumentException("No component at " + p);
            }
            components.add(r);
        }
        Resource container = components.get(0).getParent();
        for (Resource c : components) {
            if (!c.getParent().getPath().equals(container.getPath())) {
                throw new IllegalArgumentException("The components must sit in the same container");
            }
        }
        if (resolver.getResource(parent) == null) {
            throw new IllegalArgumentException("Parent " + parent + " does not exist");
        }
        PageManagerImpl pm = new PageManagerImpl(resolver);

        // the fragment: a page of the experience fragment type, below which the variations are pages
        String name = Pages.toValidName(title.trim());
        Resource parentRes = resolver.getResource(parent);
        Resource xf = resolver.create(parentRes, uniqueName(parentRes, name), props("jcr:primaryType", NameConstants.NT_PAGE));
        Map<String, Object> cp = props("jcr:primaryType", "cq:PageContent", "sling:resourceType", XF_TYPE, "cq:template", XF_TEMPLATE,
                "jcr:title", title.trim());
        Resource xfContent = resolver.create(xf, NameConstants.NN_CONTENT, cp);
        stamp(xfContent);

        // the master variation from the template (its initial content gives the root container)
        Page master = pm.create(xf.getPath(), "master", template, title.trim(), false);
        Resource masterContent = master.getContentResource();
        ModifiableValueMap mm = masterContent.adaptTo(ModifiableValueMap.class);
        mm.put("cq:xfMasterVariation", Boolean.TRUE);
        if (mm.get("cq:xfVariantType") == null) {
            mm.put("cq:xfVariantType", "web");
        }
        Resource target = masterContent.getChild("root/container");
        if (target == null) {
            target = masterContent.getChild("root");
        }
        if (target == null) {
            target = resolver.create(masterContent, "root", props("jcr:primaryType", "nt:unstructured", "sling:resourceType",
                    "wcm/foundation/components/responsivegrid"));
        }

        // move the components in, then put the fragment component where the first one was
        String first = components.get(0).getName();
        String next = null;
        boolean seen = false;
        for (Resource sibling : container.getChildren()) {
            if (seen && !Arrays.asList(paths).contains(sibling.getPath())) {
                next = sibling.getName();
                break;
            }
            if (sibling.getName().equals(first)) {
                seen = true;
            }
        }
        for (Resource c : components) {
            resolver.move(c.getPath(), target.getPath());
        }
        Resource component = resolver.create(container, uniqueName(container, "experiencefragment"),
                props("jcr:primaryType", "nt:unstructured", "sling:resourceType", resourceType, "fragmentVariationPath", master.getPath()));
        if (next != null) {
            javax.jcr.Node node = container.adaptTo(javax.jcr.Node.class);
            if (node != null) {
                try {
                    node.orderBefore(component.getName(), next);
                } catch (javax.jcr.RepositoryException e) {
                    throw new WCMException("Could not place the fragment component: " + e.getMessage(), e);
                }
            }
        }
        resolver.commit();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fragment", xf.getPath());
        out.put("variation", master.getPath());
        out.put("component", component.getPath());
        return out;
    }

    private static String uniqueName(Resource parent, String name) {
        if (parent.getChild(name) == null) {
            return name;
        }
        for (int i = 1; ; i++) {
            if (parent.getChild(name + "_" + i) == null) {
                return name + "_" + i;
            }
        }
    }

    private static void stamp(Resource r) {
        ModifiableValueMap m = r.adaptTo(ModifiableValueMap.class);
        if (m != null) {
            m.put("cq:lastModified", java.util.Calendar.getInstance());
            m.put("cq:lastModifiedBy", r.getResourceResolver().getUserID());
        }
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
