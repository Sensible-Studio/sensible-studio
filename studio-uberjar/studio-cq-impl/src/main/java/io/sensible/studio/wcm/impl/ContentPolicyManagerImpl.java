/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.adapter.SlingAdaptable;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.policies.ContentPolicy;
import com.day.cq.wcm.api.policies.ContentPolicyManager;
import com.day.cq.wcm.api.policies.ContentPolicyMapping;

/**
 * Resolves policies of editable templates.
 *
 * <p>For a component at {@code <page>/jcr:content/<rel>} whose page uses template {@code T}:
 * <ol>
 *   <li>an exact mapping at {@code T/policies/jcr:content/<rel>} wins (structure components and
 *       the page itself);</li>
 *   <li>otherwise, for each ancestor {@code <anc>} of {@code <rel>}, nearest first, a mapping at
 *       {@code T/policies/jcr:content/<anc>/<resourceType>} applies (components placed in a
 *       layout container, including containers nested by authors).</li>
 * </ol>
 * The mapping's {@code cq:policy} is resolved against {@code <conf>/settings/wcm/policies}.
 */
final class ContentPolicyManagerImpl implements ContentPolicyManager {

    private static final String POLICIES_ROOT = "/settings/wcm/policies/";

    private final ResourceResolver resolver;
    private final PageManagerImpl pageManager;

    ContentPolicyManagerImpl(ResourceResolver resolver, PageManagerImpl pageManager) {
        this.resolver = resolver;
        this.pageManager = pageManager;
    }

    @Override
    public ContentPolicy getPolicy(Resource contentResource) {
        ContentPolicyMapping mapping = getPolicyMapping(contentResource);
        return mapping == null ? null : mapping.getPolicy();
    }

    @Override
    public ContentPolicy getPolicy(Resource contentResource, SlingHttpServletRequest request) {
        return getPolicy(contentResource);
    }

    @Override
    public ContentPolicyMapping getPolicyMapping(Resource contentResource) {
        if (contentResource == null) {
            return null;
        }
        Page page = pageManager.getContainingPage(contentResource);
        if (page == null) {
            return null;
        }
        Template template = page.getTemplate();
        if (!(template instanceof TemplateImpl) || template.getPoliciesPath() == null) {
            return null; // static templates use designs, not policies
        }
        // the page shows what its template's policy mapping says (for the cache in front of a publish)
        io.sensible.studio.wcm.shown.Shown.conf(template.getPath());
        String contentRoot = page.getPath() + "/" + NameConstants.NN_CONTENT;
        String path = contentResource.getPath();
        String rel;
        if (path.equals(contentRoot)) {
            rel = "";
        } else if (path.startsWith(contentRoot + "/")) {
            rel = path.substring(contentRoot.length() + 1);
        } else {
            return null; // the page node itself or something outside jcr:content
        }

        String mappingRoot = template.getPoliciesPath() + "/" + NameConstants.NN_CONTENT;
        Resource exact = resolver.getResource(rel.isEmpty() ? mappingRoot : mappingRoot + "/" + rel);
        if (hasPolicy(exact)) {
            return new MappingImpl(exact, (TemplateImpl) template);
        }

        String type = relativeType(contentResource.getResourceType());
        if (type == null || rel.isEmpty()) {
            return null;
        }
        for (String anc = Pages.parentPathOrEmpty(rel); anc != null; anc = anc.isEmpty() ? null : Pages.parentPathOrEmpty(anc)) {
            String candidate = (anc.isEmpty() ? mappingRoot : mappingRoot + "/" + anc) + "/" + type;
            Resource mapping = resolver.getResource(candidate);
            if (hasPolicy(mapping)) {
                return new MappingImpl(mapping, (TemplateImpl) template);
            }
        }
        return null;
    }

    private static boolean hasPolicy(Resource r) {
        return r != null && r.getValueMap().get(Pages.PN_POLICY, String.class) != null;
    }

    /** {@code /apps/wknd/components/text} and {@code wknd/components/text} both map to the latter. */
    private static String relativeType(String resourceType) {
        if (resourceType == null || resourceType.isEmpty()) {
            return null;
        }
        if (resourceType.startsWith("/apps/")) {
            return resourceType.substring("/apps/".length());
        }
        if (resourceType.startsWith("/libs/")) {
            return resourceType.substring("/libs/".length());
        }
        return resourceType.startsWith("/") ? null : resourceType;
    }

    // ------------------------------------------------------------------ value objects

    private final class MappingImpl extends SlingAdaptable implements ContentPolicyMapping {
        private final Resource resource;
        private final TemplateImpl template;

        MappingImpl(Resource resource, TemplateImpl template) {
            this.resource = resource;
            this.template = template;
        }

        @Override
        public String getPath() {
            return resource.getPath();
        }

        @Override
        public ContentPolicy getPolicy() {
            String ref = resource.getValueMap().get(Pages.PN_POLICY, String.class);
            String path;
            if (ref.startsWith("/")) {
                path = ref;
            } else {
                String conf = template.getConfRoot();
                if (conf == null) {
                    return null;
                }
                path = conf + POLICIES_ROOT + ref;
            }
            Resource policy = resolver.getResource(path);
            if (policy != null) {
                // the component looks as this policy says
                io.sensible.studio.wcm.shown.Shown.conf(policy.getPath());
            }
            return policy == null ? null : new PolicyImpl(policy);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            return type == Resource.class ? (AdapterType) resource : super.adaptTo(type);
        }
    }

    static final class PolicyImpl extends SlingAdaptable implements ContentPolicy {
        private final Resource resource;

        PolicyImpl(Resource resource) {
            this.resource = resource;
        }

        @Override
        public String getPath() {
            return resource.getPath();
        }

        @Override
        public String getName() {
            return resource.getName();
        }

        @Override
        public String getTitle() {
            return resource.getValueMap().get(NameConstants.PN_TITLE, String.class);
        }

        @Override
        public String getDescription() {
            return resource.getValueMap().get(NameConstants.PN_DESCRIPTION, String.class);
        }

        @Override
        public ValueMap getProperties() {
            return resource.getValueMap();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
            if (type == Resource.class) {
                return (AdapterType) resource;
            }
            if (type == ValueMap.class) {
                return (AdapterType) resource.getValueMap();
            }
            return super.adaptTo(type);
        }

        @Override
        public String toString() {
            return "ContentPolicy{" + getPath() + "}";
        }
    }
}
