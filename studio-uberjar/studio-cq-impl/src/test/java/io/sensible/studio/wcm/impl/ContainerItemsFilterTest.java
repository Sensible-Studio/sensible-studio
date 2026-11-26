/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class ContainerItemsFilterTest {

    private static final String CAROUSEL = "/content/site/en/jcr:content/root/carousel";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private List<String> children() {
        List<String> out = new ArrayList<>();
        for (Resource r : context.resourceResolver().getResource(CAROUSEL).getChildren()) {
            out.add(r.getName());
        }
        return out;
    }

    @Test
    void deletesAndReordersItems() throws Exception {
        context.create().resource(CAROUSEL, "sling:resourceType", "wknd/components/carousel");
        for (String name : new String[] {"item_1", "item_2", "teaser", "item_3"}) {
            context.create().resource(CAROUSEL + "/" + name, "sling:resourceType", "wknd/components/teaser");
        }
        Resource carousel = context.resourceResolver().getResource(CAROUSEL);

        ContainerItemsFilter.apply(carousel, ContainerItemsFilter.names("teaser"),
                ContainerItemsFilter.names("item_3, item_1,teaser,item_2"));

        assertEquals(List.of("item_3", "item_1", "item_2"), children());
    }

    @Test
    void namesAreChildNamesOnly() {
        assertEquals(List.of("a", "b"), ContainerItemsFilter.names("a, ,../x,/content/y,.,b"));
        assertEquals(List.of(), ContainerItemsFilter.names(null));
    }
}
