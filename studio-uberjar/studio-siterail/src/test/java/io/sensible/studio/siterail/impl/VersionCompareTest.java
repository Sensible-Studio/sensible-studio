/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.PageManager;

/** Compare to Current on a real Oak repository. */
@ExtendWith(SlingContextExtension.class)
class VersionCompareTest {

    private static final String SITE = "/content/site";
    private static final String PAGE = SITE + "/en/story";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private String first;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource(SITE, "jcr:primaryType", "sling:Folder", "cq:conf", "/conf/site");
        context.create().resource(SITE + "/en", "jcr:primaryType", "cq:Page");
        context.create().resource(SITE + "/en/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "English");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Story",
                "sling:resourceType", "site/components/page", "cq:template", "/conf/site/settings/wcm/templates/page");
        context.create().resource(PAGE + "/jcr:content/root", "jcr:primaryType", "nt:unstructured",
                "sling:resourceType", "site/components/container");
        context.create().resource(PAGE + "/jcr:content/root/text", "jcr:primaryType", "nt:unstructured",
                "sling:resourceType", "site/components/text", "text", "<p>first</p>", "keep", "same");
        context.create().resource(PAGE + "/jcr:content/root/teaser", "jcr:primaryType", "nt:unstructured",
                "sling:resourceType", "site/components/teaser", "jcr:title", "Old teaser");
        context.create().resource(PAGE + "/jcr:content/root/teaser/link", "jcr:primaryType", "nt:unstructured", "url", "/x");
        resolver = context.resourceResolver();
        resolver.commit();
        first = OakPages.pageManager(resolver).createRevision(OakPages.page(PAGE), "launch", null).getId();
    }

    private VersionCompare as(String user) {
        ResourceResolver r = new ResourceResolverWrapper(resolver) {
            @Override
            public String getUserID() {
                return user;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T adaptTo(Class<T> type) {
                return type == PageManager.class ? (T) OakPages.pageManager(this) : super.adaptTo(type);
            }
        };
        return new VersionCompare(r);
    }

    private void edit() throws Exception {
        resolver.getResource(PAGE + "/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:title", "Story, edited");
        ModifiableValueMap text = resolver.getResource(PAGE + "/jcr:content/root/text").adaptTo(ModifiableValueMap.class);
        text.put("text", "<p>second</p>");
        text.put("textIsRich", true);
        resolver.delete(resolver.getResource(PAGE + "/jcr:content/root/teaser"));
        resolver.create(resolver.getResource(PAGE + "/jcr:content/root"), "image",
                Map.of("jcr:primaryType", "nt:unstructured", "sling:resourceType", "site/components/image"));
        resolver.commit();
    }

    @Test
    @SuppressWarnings("unchecked")
    void diffListsComponentAndPropertyChanges() throws Exception {
        edit();
        Map<String, Object> diff = as("admin").diff(PAGE, first, "current");
        List<Map<String, Object>> changes = (List<Map<String, Object>>) diff.get("changes");
        List<String> summary = changes.stream()
                .map(c -> c.get("kind") + " " + c.get("node") + (c.get("property") == null ? "" : " " + c.get("property")))
                .collect(Collectors.toList());
        assertEquals(List.of("changed  jcr:title", "added root/image", "removed root/teaser", "changed root/text text",
                "propertyAdded root/text textIsRich"), summary, "the teaser's link goes with the teaser");
        Map<String, Object> title = changes.get(0);
        assertEquals("Story", title.get("before"));
        assertEquals("Story, edited", title.get("after"));
        assertEquals("site/components/teaser", changes.get(2).get("resourceType"));
        assertEquals("Old teaser", ((Map<String, String>) changes.get(2).get("properties")).get("jcr:title"));

        // versions against each other, and nothing against itself
        String second = OakPages.pageManager(resolver).createRevision(OakPages.page(PAGE), null, null).getId();
        assertEquals(5, ((List<?>) as("admin").diff(PAGE, first, second).get("changes")).size());
        assertTrue(((List<?>) as("admin").diff(PAGE, second, "current").get("changes")).isEmpty(), "version and saved state match");
        assertTrue(((List<?>) as("admin").diff(PAGE, null, null).get("changes")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> as("admin").diff(PAGE, "nope", null));
        assertNull(diff.get("missing"));
    }
}
