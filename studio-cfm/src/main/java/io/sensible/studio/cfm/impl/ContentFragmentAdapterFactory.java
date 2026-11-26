package io.sensible.studio.cfm.impl;

import com.adobe.cq.dam.cfm.ContentFragment;
import org.apache.sling.api.adapter.AdapterFactory;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

/**
 * {@code resource.adaptTo(ContentFragment.class)} for content fragment assets, which is how Core Components
 * (Content Fragment, Content Fragment List) and project code open a fragment. Accepts the asset itself or its
 * {@code jcr:content}; anything else adapts to null.
 */
@Component(
        service = AdapterFactory.class,
        property = {
                AdapterFactory.ADAPTABLE_CLASSES + "=org.apache.sling.api.resource.Resource",
                AdapterFactory.ADAPTER_CLASSES + "=com.adobe.cq.dam.cfm.ContentFragment"
        }
)
public class ContentFragmentAdapterFactory implements AdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <A> A getAdapter(Object adaptable, Class<A> type) {
        if (type != ContentFragment.class || !(adaptable instanceof Resource)) return null;
        Resource resource = (Resource) adaptable;
        if ("jcr:content".equals(resource.getName()) && resource.getParent() != null) {
            resource = resource.getParent();
        }
        return ContentFragmentImpl.isFragment(resource) ? (A) ContentFragmentImpl.of(resource) : null;
    }
}
