package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class VanityUrlsServletTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    @Test
    void everyVanityPathWithItsPage() {
        context.create().resource("/content/site/en/summer", "jcr:primaryType", "nt:unstructured");
        context.create().resource("/content/site/en/summer/jcr:content", "jcr:primaryType", "nt:unstructured",
                "sling:vanityPath", new String[] {"/summer", "deals/"});
        context.create().resource("/content/site/en/old", "jcr:primaryType", "nt:unstructured");
        context.create().resource("/content/site/en/old/jcr:content", "jcr:primaryType", "nt:unstructured",
                "sling:vanityPath", "/old-offer", "sling:redirect", true, "sling:redirectStatus", 301);
        context.create().resource("/content/other/x/jcr:content", "jcr:primaryType", "nt:unstructured",
                "sling:vanityPath", "/summer");
        List<Map<String, Object>> all = VanityUrlsServlet.vanityUrls(context.resourceResolver());
        assertEquals(3, all.size(), all.toString());
        assertEquals("/deals", all.get(0).get("path"));
        assertEquals("/content/site/en/summer", all.get(0).get("target"));
        assertEquals("/old-offer", all.get(1).get("path"));
        assertEquals(Boolean.TRUE, all.get(1).get("redirect"));
        assertEquals(301, all.get(1).get("status"));
        assertEquals("/summer", all.get(2).get("path"));
        assertEquals(Boolean.FALSE, all.get(2).get("redirect"));
        assertEquals(302, all.get(2).get("status"));
    }

    @Test
    void whatIsNoPath() {
        assertEquals("/a/b", VanityUrlsServlet.normalise(" a/b/ "));
        for (String bad : new String[] {null, "", "/", "http://elsewhere/x", "/a/../bin", "/a;x", "/a?b", "/a#b"}) {
            assertNull(VanityUrlsServlet.normalise(bad), String.valueOf(bad));
        }
    }
}
