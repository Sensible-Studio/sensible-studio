/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.cfm.impl;

import com.adobe.granite.translation.api.TranslationException;
import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Translates the content fragments a page refers to when the page is translated with its references (Sites →
 * Language Copies): the same language copies, updates and records as the Content Fragments console, including the
 * fragments those fragments refer to.
 */
@Component(service = ReferenceTranslator.class)
public class FragmentReferenceTranslator implements ReferenceTranslator {

    @Reference
    private volatile Translator translator;

    @Override
    public boolean accepts(ResourceResolver resolver, String path) {
        return path != null && path.startsWith("/content/dam/") && ContentFragmentImpl.isFragment(resolver.getResource(path))
                && FragmentTranslation.languageRoot(path) != null;
    }

    @Override
    public List<Map<String, Object>> translate(ResourceResolver resolver, String path, String target, String provider, boolean force)
            throws TranslationException {
        try {
            return new FragmentTranslation(resolver, translator).translate(Collections.singletonList(path), Collections.singletonList(target),
                    provider, true, force);
        } catch (FragmentStore.ValidationException e) {
            StringBuilder sb = new StringBuilder();
            for (FragmentStore.FieldError fe : e.errors) sb.append(sb.length() == 0 ? "" : " ").append(fe.message);
            throw new IllegalArgumentException(sb.toString());
        } catch (PersistenceException e) {
            throw new IllegalArgumentException("Could not save the copy of " + path + ": " + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> review(ResourceResolver resolver, String path) {
        if (path == null || !ContentFragmentImpl.isFragment(resolver.getResource(path))) return null;
        return new FragmentTranslation(resolver, translator).review(path);
    }

    @Override
    public void saveReview(ResourceResolver resolver, String path, Map<String, String> translations) throws Exception {
        new FragmentTranslation(resolver, translator).saveReview(path, translations);
    }

    @Override
    public boolean approve(ResourceResolver resolver, String path, String by) throws Exception {
        if (path == null || !ContentFragmentImpl.isFragment(resolver.getResource(path)) || resolver.getResource(path + "/jcr:content/translation") == null) {
            return false;
        }
        new FragmentTranslation(resolver, translator).approve(path, by);
        return true;
    }
}
