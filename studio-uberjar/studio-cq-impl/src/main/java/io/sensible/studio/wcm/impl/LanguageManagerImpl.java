/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Locale;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.LanguageManager;
import com.day.cq.wcm.api.Page;

/**
 * A language root is the nearest page whose name is an ISO language code ({@code en},
 * {@code de-ch}) or, with {@code respectContent}, whose content sets {@code jcr:language}.
 */
@Component(service = LanguageManager.class)
public class LanguageManagerImpl implements LanguageManager {

    @Override
    public Page getLanguageRoot(Resource resource) {
        return getLanguageRoot(resource, false);
    }

    @Override
    public Page getLanguageRoot(Resource resource, boolean respectContent) {
        if (resource == null) {
            return null;
        }
        PageManagerImpl pm = new PageManagerImpl(resource.getResourceResolver());
        for (Page p = pm.getContainingPage(resource); p != null; p = p.getParent()) {
            if (Pages.parseLanguage(p.getName()) != null) {
                return p;
            }
            if (respectContent && p.hasContent()
                    && Pages.parseLanguage(p.getProperties().get(Pages.PN_LANGUAGE, String.class)) != null) {
                return p;
            }
        }
        return null;
    }

    @Override
    public Locale getLanguage(Resource resource, boolean respectContent) {
        if (resource == null) {
            return Locale.getDefault();
        }
        PageManagerImpl pm = new PageManagerImpl(resource.getResourceResolver());
        Page page = pm.getContainingPage(resource);
        return page == null ? Locale.getDefault() : page.getLanguage(!respectContent);
    }
}
