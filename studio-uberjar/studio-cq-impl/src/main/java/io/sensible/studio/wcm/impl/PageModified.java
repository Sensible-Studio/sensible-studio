/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.sensible.studio.wcm.impl;

import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.SlingPostProcessor;
import org.osgi.service.component.annotations.Component;

/**
 * A page is modified when its content is: the editor writes components through the Sling POST servlet, which
 * knows nothing of pages, so a page edited after it was published went on reading "Published", and the editor's
 * "Modified … by …" kept the day the page was made. As AEM does, every such write dates the page
 * ({@code cq:lastModified}, {@code cq:lastModifiedBy} on its content), in the same save.
 */
@Component(service = SlingPostProcessor.class)
public class PageModified implements SlingPostProcessor {

    private static final String CONTENT = "/jcr:content";

    @Override
    public void process(SlingHttpServletRequest request, List<Modification> changes) {
        ResourceResolver resolver = request.getResourceResolver();
        Set<String> pages = new LinkedHashSet<>();
        for (Modification m : changes) {
            add(pages, m.getSource());
            add(pages, m.getDestination());
        }
        for (String content : pages) {
            Resource r = resolver.getResource(content);
            if (r == null || !"cq:PageContent".equals(r.getValueMap().get("jcr:primaryType", String.class))) {
                continue;
            }
            ModifiableValueMap props = r.adaptTo(ModifiableValueMap.class);
            if (props != null) {
                props.put("cq:lastModified", Calendar.getInstance());
                props.put("cq:lastModifiedBy", resolver.getUserID());
            }
        }
    }

    /** The content node of the page a changed path belongs to; nothing for what only records publishing or a lock. */
    static void add(Set<String> pages, String path) {
        String content = contentOf(path);
        if (content != null) {
            pages.add(content);
        }
    }

    static String contentOf(String path) {
        if (path == null || !path.startsWith("/content/") || path.startsWith("/content/dam/")) {
            return null;
        }
        int at = path.indexOf(CONTENT);
        if (at < 0) {
            return null;
        }
        String rest = path.substring(at + CONTENT.length());
        if (!rest.isEmpty() && !rest.startsWith("/")) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.startsWith("cq:lastReplicat") || name.startsWith("cq:lastModified") || name.equals(Pages.PN_LOCK_OWNER)) {
            return null;
        }
        return path.substring(0, at + CONTENT.length());
    }
}
