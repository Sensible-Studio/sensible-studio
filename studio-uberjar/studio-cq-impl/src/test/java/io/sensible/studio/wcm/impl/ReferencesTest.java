/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;

@ExtendWith(SlingContextExtension.class)
class ReferencesTest {

    private static final String BALI = EN + "/adventures/bali-surf-camp";

    private final SlingContext context = WcmTestSupport.newContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        // a teaser on the home page and a rich text link elsewhere point at Bali
        context.create().resource(EN + "/jcr:content/root/container/teaser", "sling:resourceType", "wknd/components/teaser",
                "linkURL", BALI, "actions", new String[] {"/content/other", BALI + ".html#book"});
        context.create().resource(EN + "/adventures/jcr:content/root/text", "sling:resourceType", "wknd/components/text",
                "text", "<p>See <a href=\"" + BALI + ".html\">Bali</a> or " + BALI + "-not-this</p>");
        // self reference inside the moved page
        context.create().resource(BALI + "/jcr:content/root/link", "linkURL", BALI + "/gear");
    }

    @Test
    void matchesWholePathsOnly() {
        assertTrue(References.mentions(BALI, BALI));
        assertTrue(References.mentions("x " + BALI + ".html?x", BALI));
        assertTrue(References.mentions(BALI + "/child", BALI));
        assertFalse(References.mentions(BALI + "-2", BALI));
        assertFalse(References.mentions("/x" + BALI, BALI));
        assertEquals("<a href=\"/n.html\">/n</a> /n-x", References.rewrite("<a href=\"/o.html\">/o</a> /n-x", "/o", "/n"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsReferencesFromOtherPages() {
        Map<String, Object> r = new SitesServlet().references(context.resourceResolver(), BALI);
        List<Map<String, Object>> refs = (List<Map<String, Object>>) r.get("references");
        assertEquals(List.of("actions", "linkURL", "text"), refs.stream().map(m -> (String) m.get("property")).sorted().collect(Collectors.toList()));
        assertTrue(refs.stream().anyMatch(m -> EN.equals(m.get("page"))));
        assertTrue(refs.stream().allMatch(m -> !((String) m.get("path")).startsWith(BALI)), "own subtree excluded");
        assertNull(new SitesServlet().references(context.resourceResolver(), "/content/nope"));
    }

    @Test
    void moveAdjustsChosenPagesAndItsOwnSubtree() throws Exception {
        PageManagerImpl pm = new PageManagerImpl(context.resourceResolver());
        Page moved = pm.move(pm.getPage(BALI), EN + "/bali", null, false, false, new String[] {EN});
        assertEquals(EN + "/bali", moved.getPath());

        ValueMap teaser = context.resourceResolver().getResource(EN + "/jcr:content/root/container/teaser").getValueMap();
        assertEquals(EN + "/bali", teaser.get("linkURL", String.class));
        assertArrayEquals(new String[] {"/content/other", EN + "/bali.html#book"}, teaser.get("actions", String[].class));
        // not chosen: left as it was
        assertTrue(context.resourceResolver().getResource(EN + "/adventures/jcr:content/root/text").getValueMap()
                .get("text", String.class).contains(BALI + ".html"));
        ValueMap self = context.resourceResolver().getResource(EN + "/bali/jcr:content/root/link").getValueMap();
        assertEquals(EN + "/bali/gear", self.get("linkURL", String.class));
        assertNotNull(pm.getPage(EN + "/bali"));
    }
}
