package io.sensible.studio.compat.dialog.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds the content policy of a component instance on a page created from an editable template.
 *
 * <p>The mapping lives under {@code <template>/policies/jcr:content}, mirroring the page structure:</p>
 * <ul>
 *   <li>an exact node for the instance path (e.g. {@code root/container/title}) carrying {@code cq:policy}, or</li>
 *   <li>a node named after the resource type below the nearest container that has a mapping
 *       (e.g. {@code root/container/wknd/components/title}); containers are tried from the nearest outwards.</li>
 * </ul>
 * <p>{@code cq:policy} is relative to {@code /conf/<site>/settings/wcm/policies}; {@code /conf/global} is used as a
 * fallback. Dialogs read the policy as {@code cqDesign} and the Style System reads its {@code cq:styleGroups}.</p>
 */
public final class PolicyResolver {

    private static final String CONTENT = "jcr:content";
    private static final String TEMPLATES_MARKER = "/settings/wcm/templates/";

    private final NodeSource source;

    public PolicyResolver(NodeSource source) {
        this.source = source;
    }

    /** A resolved policy: where it is and its full subtree. */
    public static final class Policy {
        private final String path;
        private final DialogNode node;

        Policy(String path, DialogNode node) {
            this.path = path;
            this.node = node;
        }

        public String getPath() { return path; }
        public DialogNode getNode() { return node; }
    }

    /**
     * @param contentPath  path of the component resource, e.g. {@code /content/wknd/us/en/x/jcr:content/root/container/title},
     *                     or the page's {@code jcr:content} itself
     * @param resourceType its {@code sling:resourceType}
     * @return the policy, or null when the page has no editable template or no mapping applies
     */
    public Policy resolve(String contentPath, String resourceType) {
        int idx = contentPath.indexOf("/" + CONTENT);
        if (idx < 0) return null;
        String pageContent = contentPath.substring(0, idx + CONTENT.length() + 1);
        String relative = contentPath.length() > pageContent.length() ? contentPath.substring(pageContent.length() + 1) : "";
        DialogNode page = source.loadShallow(pageContent);
        String template = page == null ? null : page.getString("cq:template");
        if (template == null || template.isEmpty()) return null;
        return resolveInTemplate(template, relative, resourceType);
    }

    /** Resolution against a known template; {@code relative} is the path below the page's jcr:content ("" for the page). */
    public Policy resolveInTemplate(String templatePath, String relative, String resourceType) {
        String mappings = templatePath + "/policies/" + CONTENT;
        String policy = null;

        // 1. exact structural mapping
        DialogNode exact = source.loadShallow(relative.isEmpty() ? mappings : mappings + "/" + relative);
        if (exact != null) policy = exact.getString("cq:policy");

        // 2. by resource type below the nearest mapped container, then further out
        if (policy == null && resourceType != null && !relative.isEmpty()) {
            String rt = stripSearchPath(resourceType);
            List<String> segments = new ArrayList<>(Arrays.asList(relative.split("/")));
            segments.remove(segments.size() - 1); // the instance itself
            while (policy == null) {
                String container = String.join("/", segments);
                DialogNode byType = source.loadShallow(mappings + (container.isEmpty() ? "" : "/" + container) + "/" + rt);
                if (byType != null) policy = byType.getString("cq:policy");
                if (segments.isEmpty()) break;
                segments.remove(segments.size() - 1);
            }
        }
        if (policy == null || policy.isEmpty()) return null;

        for (String root : policyRoots(templatePath)) {
            String path = policy.startsWith("/") ? policy : root + policy;
            DialogNode node = source.load(path);
            if (node != null) return new Policy(path, node);
        }
        return null;
    }

    private static List<String> policyRoots(String templatePath) {
        List<String> roots = new ArrayList<>();
        int idx = templatePath.indexOf(TEMPLATES_MARKER);
        if (idx > 0) roots.add(templatePath.substring(0, idx) + "/settings/wcm/policies/");
        roots.add("/conf/global/settings/wcm/policies/");
        return roots;
    }

    private static String stripSearchPath(String rt) {
        if (rt.startsWith("/apps/")) return rt.substring(6);
        if (rt.startsWith("/libs/")) return rt.substring(6);
        return rt;
    }
}
