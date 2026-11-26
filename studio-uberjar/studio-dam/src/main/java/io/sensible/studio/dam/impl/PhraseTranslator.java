/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts a search phrase into English, the language the picture model reads, with whatever
 * translation provider the instance has (Translations › Providers).
 *
 * <p>The translation module is another bundle that may not be installed, and Media must build
 * and start without it, so its service ({@code io.sensible.studio.translation.api.Translator})
 * is found by name and called through reflection: no import, no build dependency. The provider
 * is the one that applies to the folder searched (its {@code cq:conf}, then
 * {@code /conf/global}, then the instance's default); when none of those is set up, the first
 * translation cloud configuration the asking user may read anywhere under {@code /conf}.</p>
 */
final class PhraseTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(PhraseTranslator.class);
    private static final String API = "io.sensible.studio.translation.api.";
    private static final int REMEMBERED = 500;

    /** Phrases already translated (the same search is asked again at every sort or reload). */
    private static final Map<String, String> CACHE = Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > REMEMBERED;
        }
    });

    private PhraseTranslator() {
    }

    /** No provider answers: the module is missing, or nothing is set up. */
    static final class Unavailable extends Exception {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /** A provider was asked and failed. */
    static final class Failed extends Exception {
        private static final long serialVersionUID = 1L;

        Failed(String message) {
            super(message);
        }
    }

    /** {@code text} in English. */
    static String toEnglish(BundleContext context, ResourceResolver resolver, String folder, String text) throws Unavailable, Failed {
        String known = CACHE.get(text);
        if (known != null) {
            return known;
        }
        ServiceReference<?> ref = context == null ? null : context.getServiceReference(API + "Translator");
        Object translator = ref == null ? null : context.getService(ref);
        if (translator == null) {
            throw new Unavailable("Translations are not installed on this instance");
        }
        try {
            String english;
            try {
                english = ask(translator, resolver, folder, null, text);
            } catch (Failed first) {
                // nothing applies to this folder: any configuration this person may use
                String config = anyConfiguration(resolver);
                if (config == null) {
                    throw new Unavailable("No translation provider is set up (Translations › Providers): " + first.getMessage());
                }
                english = ask(translator, resolver, folder, config, text);
            }
            english = english.replaceAll("\\s+", " ").trim();
            // a model may answer in quotes or with a full stop; the phrase is what is searched
            english = english.replaceAll("^[\"'“”‘’«»]+|[\"'“”‘’«»]+$", "").trim();
            if (english.isEmpty()) {
                throw new Failed("the provider answered nothing");
            }
            if (english.length() > DamSimilar.MAX_TEXT) {
                english = english.substring(0, DamSimilar.MAX_TEXT);
            }
            CACHE.put(text, english);
            return english;
        } finally {
            context.ungetService(ref);
        }
    }

    private static String ask(Object translator, ResourceResolver resolver, String folder, String provider, String text) throws Failed {
        try {
            ClassLoader cl = translator.getClass().getClassLoader();
            Class<?> segmentClass = cl.loadClass(API + "Segment");
            Class<?> requestClass = cl.loadClass(API + "TranslationRequest");
            Object segment = segmentClass.getConstructor(String.class, String.class, boolean.class, String.class)
                    .newInstance("q", text, false, "Search phrase");
            List<Object> segments = new ArrayList<>();
            segments.add(segment);
            // no source language: the provider reads the phrase in whatever language it is written
            Object request = requestClass.getConstructor(String.class, String.class, List.class).newInstance(null, "en", segments);
            requestClass.getMethod("title", String.class).invoke(request, "Picture search");
            requestClass.getMethod("useMemory", boolean.class).invoke(request, false);
            requestClass.getMethod("instructions", String.class).invoke(request,
                    "This is a short description of what a picture shows, typed into an image search. Answer with the same "
                            + "description in plain English, nothing else. If it is already English, repeat it unchanged.");
            if (provider != null) {
                requestClass.getMethod("provider", String.class).invoke(request, provider);
            }
            Method translate = translator.getClass().getMethod("translate", ResourceResolver.class, String.class, requestClass);
            Object response = translate.invoke(translator, resolver, folder, request);
            List<?> results = (List<?>) response.getClass().getMethod("getResults").invoke(response);
            Object result = results.isEmpty() ? null : results.get(0);
            if (result == null) {
                throw new Failed("the provider answered nothing");
            }
            if (!Boolean.TRUE.equals(result.getClass().getMethod("isTranslated").invoke(result))) {
                throw new Failed(String.valueOf(result.getClass().getMethod("getError").invoke(result)));
            }
            return String.valueOf(result.getClass().getMethod("getText").invoke(result));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new Failed(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("The translation module does not look as expected: {}", e.toString());
            throw new Failed("the translation module could not be called");
        }
    }

    /** The first translation cloud configuration below {@code /conf} that the user may read, or null. */
    static String anyConfiguration(ResourceResolver resolver) {
        Resource conf = resolver.getResource("/conf");
        if (conf == null) {
            return null;
        }
        for (Resource site : conf.getChildren()) {
            Resource folder = site.getChild("settings/cloudconfigs/translation");
            if (folder == null) {
                continue;
            }
            for (Resource c : folder.getChildren()) {
                if (!c.getName().startsWith("jcr:") && !c.getName().startsWith("rep:")) {
                    return c.getPath();
                }
            }
        }
        return null;
    }
}
