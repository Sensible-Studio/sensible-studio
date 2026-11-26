/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import com.adobe.cq.export.json.SlingModelFilter;

/**
 * Keeps repository bookkeeping out of {@code .model.json}: properties in the {@code jcr:}
 * namespace (except title and description) and child nodes in the {@code jcr:}, {@code cq:} and
 * {@code rep:} namespaces are dropped.
 */
@Component(service = SlingModelFilter.class)
public class SlingModelFilterImpl implements SlingModelFilter {

    @Override
    public Map<String, Object> filterProperties(Map<String, Object> properties) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith("jcr:") || "jcr:title".equals(k) || "jcr:description".equals(k)) {
                out.put(k, e.getValue());
            }
        }
        return out;
    }

    @Override
    public Iterable<Resource> filterChildResources(Iterable<Resource> childResources) {
        List<Resource> out = new ArrayList<>();
        for (Resource r : childResources) {
            String n = r.getName();
            if (!n.startsWith("jcr:") && !n.startsWith("cq:") && !n.startsWith("rep:")) {
                out.add(r);
            }
        }
        return out;
    }
}
