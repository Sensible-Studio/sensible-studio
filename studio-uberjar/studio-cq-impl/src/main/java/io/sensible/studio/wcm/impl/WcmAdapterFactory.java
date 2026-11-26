/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;


import com.adobe.cq.wcm.style.ComponentStyleInfo;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.dam.api.Rendition;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;

import io.sensible.studio.wcm.impl.dam.AssetImpl;
import io.sensible.studio.wcm.impl.dam.AssetManagerImpl;
import io.sensible.studio.wcm.impl.dam.RenditionImpl;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Template;
import com.day.cq.wcm.api.TemplatedResource;
import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentManager;
import com.day.cq.wcm.api.designer.Designer;
import com.day.cq.wcm.api.policies.ContentPolicyManager;

/**
 * Makes the usual AEM adaptations work:
 * <pre>
 * resource.adaptTo(Page.class)                    resolver.adaptTo(PageManager.class)
 * resource.adaptTo(Template.class)                resolver.adaptTo(ContentPolicyManager.class)
 * resource.adaptTo(Tag.class)                     resolver.adaptTo(Designer.class)
 * resource.adaptTo(Component.class)               resolver.adaptTo(ComponentManager.class)
 * resource.adaptTo(Asset.class)                   resolver.adaptTo(AssetManager.class)
 * </pre>
 */
@org.osgi.service.component.annotations.Component(
        service = AdapterFactory.class,
        property = {
                AdapterFactory.ADAPTABLE_CLASSES + "=org.apache.sling.api.resource.Resource",
                AdapterFactory.ADAPTABLE_CLASSES + "=org.apache.sling.api.resource.ResourceResolver",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.Page",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.Template",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.tagging.Tag",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.PageManager",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.policies.ContentPolicyManager",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.designer.Designer",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.components.ComponentManager",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.components.Component",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.dam.api.Asset",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.dam.api.Rendition",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.dam.api.AssetManager",
                AdapterFactory.ADAPTER_CLASSES + "=com.adobe.cq.wcm.style.ComponentStyleInfo",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.tagging.TagManager",
                AdapterFactory.ADAPTER_CLASSES + "=com.day.cq.wcm.api.TemplatedResource"
        })
public class WcmAdapterFactory implements AdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <AdapterType> AdapterType getAdapter(Object adaptable, Class<AdapterType> type) {
        if (adaptable instanceof Resource) {
            return (AdapterType) fromResource((Resource) adaptable, type);
        }
        if (adaptable instanceof ResourceResolver) {
            return (AdapterType) fromResolver((ResourceResolver) adaptable, type);
        }
        return null;
    }

    /** Adaptations of a resource; also used by wrappers that must stay the adaptable (Timewarp). */
    static Object fromResource(Resource resource, Class<?> type) {
        if (type == Page.class) {
            return Pages.isPage(resource) ? new PageImpl(resource, new PageManagerImpl(resource.getResourceResolver())) : null;
        }
        if (type == Template.class) {
            return Pages.isTemplate(resource) ? new TemplateImpl(resource) : null;
        }
        if (type == Tag.class) {
            return Pages.resolveTag(resource.getResourceResolver(), resource.getPath());
        }
        if (type == TemplatedResource.class) {
            return TemplatedResourceImpl.of(resource);
        }
        if (type == Asset.class) {
            return AssetImpl.isAsset(resource) ? new AssetImpl(resource) : null;
        }
        if (type == Rendition.class) {
            Resource assetRes = resource.getParent() == null ? null : resource.getParent().getParent();
            Resource asset = assetRes == null ? null : assetRes.getParent();
            return asset != null && AssetImpl.isAsset(asset) && "renditions".equals(resource.getParent().getName())
                    ? new RenditionImpl(resource, new AssetImpl(asset)) : null;
        }
        if (type == ComponentStyleInfo.class) {
            ContentPolicyManagerImpl cpm = new ContentPolicyManagerImpl(resource.getResourceResolver(),
                    new PageManagerImpl(resource.getResourceResolver()));
            return ComponentStyleInfoImpl.of(resource, cpm.getPolicy(resource));
        }
        if (type == Component.class) {
            return ComponentManagerImpl.isComponent(resource)
                    ? new ComponentManagerImpl(resource.getResourceResolver()).getComponent(resource.getPath()) : null;
        }
        return null;
    }

    static Object fromResolver(ResourceResolver resolver, Class<?> type) {
        if (type == PageManager.class) {
            return new PageManagerImpl(resolver);
        }
        if (type == ContentPolicyManager.class) {
            return new ContentPolicyManagerImpl(resolver, new PageManagerImpl(resolver));
        }
        if (type == TagManager.class) {
            return new TagManagerImpl(resolver);
        }
        if (type == Designer.class) {
            return new DesignerImpl(resolver);
        }
        if (type == ComponentManager.class) {
            return new ComponentManagerImpl(resolver);
        }
        if (type == AssetManager.class) {
            return new AssetManagerImpl(resolver);
        }
        return null;
    }
}
