package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.ResourceResolver;

import java.util.List;
import java.util.Map;

/** Test access to package-private helpers of {@link ContentFragmentServlet}. */
public final class ContentFragmentServletAccess {

    private ContentFragmentServletAccess() {
    }

    public static List<Map<String, Object>> endpoints(ResourceResolver resolver) {
        return ContentFragmentServlet.endpoints(resolver);
    }
}
