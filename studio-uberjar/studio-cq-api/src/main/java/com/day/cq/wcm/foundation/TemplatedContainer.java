/*
 * Licensed under the Apache License, Version 2.0. Clean-room Sensible Studio implementation.
 */
package com.day.cq.wcm.foundation;

import java.util.ArrayList;
import java.util.List;

import javax.script.Bindings;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.sightly.pojo.Use;

import com.day.cq.wcm.api.TemplatedResource;

/**
 * HTL use object for page bodies: the top-level resources to render, i.e. the page content merged
 * with its editable template's structure ({@link TemplatedResource}). Structure-only components
 * (a header experience fragment, say) come from the template; editable parts from the page.
 */
public class TemplatedContainer implements Use {

    private List<Resource> structureResources = new ArrayList<>();

    @Override
    public void init(Bindings bindings) {
        Resource resource = (Resource) bindings.get(SlingBindings.RESOURCE);
        if (resource == null) {
            return;
        }
        Resource merged = resource.adaptTo(TemplatedResource.class);
        Resource source = merged != null ? merged : resource;
        List<Resource> out = new ArrayList<>();
        for (Resource child : source.getChildren()) {
            String n = child.getName();
            if (!n.startsWith("cq:") && !n.startsWith("jcr:") && !n.startsWith("rep:") && child.getResourceType() != null) {
                out.add(child);
            }
        }
        structureResources = out;
    }

    /** @return the resources to render, in template order */
    public List<Resource> getStructureResources() {
        return structureResources;
    }

    /** @return {@code true} if there is something to render */
    public boolean hasStructureSupport() {
        return !structureResources.isEmpty();
    }
}
