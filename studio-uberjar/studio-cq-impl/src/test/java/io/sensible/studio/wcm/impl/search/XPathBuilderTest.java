/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.day.cq.search.PredicateGroup;

class XPathBuilderTest {

    @Test
    void wkndImageListQuery() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("path", "/content/wknd/us/en");
        p.put("type", "nt:unstructured");
        p.put("property", "sling:resourceType");
        p.put("property.value", "wknd/components/image");
        p.put("p.limit", "-1");
        p.put("p.guessTotal", "true");
        p.put("orderby", "@jcr:path");
        p.put("orderby.sort", "asc");
        XPathBuilder x = XPathBuilder.build(PredicateGroup.create(p));
        assertEquals("/jcr:root/content/wknd/us/en//element(*, nt:unstructured)"
                + "[@sling:resourceType = 'wknd/components/image'] order by @jcr:path ascending", x.statement);
        assertEquals(-1, x.limit);
    }

    @Test
    void numberedPropertiesMultipleValuesAndEscaping() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("path", "/content/2024 site");
        p.put("1_property", "jcr:content/cq:tags");
        p.put("1_property.1_value", "wknd:activity");
        p.put("1_property.2_value", "o'neil");
        p.put("2_property", "hideInNav");
        p.put("2_property.operation", "exists");
        p.put("2_property.value", "false");
        XPathBuilder x = XPathBuilder.build(PredicateGroup.create(p));
        assertEquals("/jcr:root/content/_x0032_024_x0020_site//element(*, nt:base)"
                + "[(jcr:content/@cq:tags = 'wknd:activity' or jcr:content/@cq:tags = 'o''neil') and not(@hideInNav)]", x.statement);
        assertEquals(10, x.limit, "QueryBuilder default limit");
    }
}
