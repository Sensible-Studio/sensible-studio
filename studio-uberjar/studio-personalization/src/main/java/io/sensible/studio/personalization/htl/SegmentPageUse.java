/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.htl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.sightly.pojo.Use;

import io.sensible.studio.personalization.impl.Segments;

/** A segment page's conditions, flattened for a table: logic rows and comparison rows with their depth. */
public class SegmentPageUse implements Use {

    private final List<Map<String, Object>> rules = new ArrayList<>();

    @Override
    public void init(Bindings bindings) {
        Resource resource = (Resource) bindings.get(SlingBindings.RESOURCE);
        Segments.Segment s = Segments.read(resource.getParent());
        if (s != null && s.expression != null) {
            walk(s.expression, 0);
        }
    }

    @SuppressWarnings("unchecked")
    private void walk(Map<String, Object> e, int depth) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("indent", (depth * 16 + 10) + "px");
        String type = String.valueOf(e.get("type"));
        if ("and".equals(type) || "or".equals(type)) {
            row.put("logic", "and".equals(type) ? "all of" : "any of");
            rules.add(row);
            Object items = e.get("items");
            if (items instanceof List) {
                for (Object item : (List<Object>) items) {
                    if (item instanceof Map) {
                        walk((Map<String, Object>) item, depth + 1);
                    }
                }
            }
            return;
        }
        row.put("property", e.get("property"));
        row.put("operator", e.get("operator"));
        row.put("value", e.get("otherProperty") != null ? e.get("otherProperty") : e.get("value"));
        rules.add(row);
    }

    public List<Map<String, Object>> getRules() {
        return rules;
    }
}
