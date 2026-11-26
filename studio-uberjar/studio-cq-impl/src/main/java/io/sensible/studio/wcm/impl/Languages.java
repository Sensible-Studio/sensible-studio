/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.NameConstants;

/**
 * The language versions of a site, for the hreflang links of its sitemaps: the site is the topmost
 * page above (WKND: {@code /content/wknd}), its language roots the pages one or two levels below it
 * named after a language ({@code /content/wknd/us/en}, {@code /content/wknd/ch/fr},
 * {@code /content/wknd/language-masters/de}). A page named like a language that holds other such
 * pages is a country, not a language ({@code /content/wknd/ca}, Canada, is also Catalan's code).
 * <p>
 * The version of {@code <root>/a/b} in another language is {@code <other root>/a/b}, when it exists,
 * can be read and is in a sitemap itself: that keeps out the language masters, which are no one's
 * site unless someone gives them a sitemap.
 */
final class Languages {

    static final Languages NONE = new Languages(Collections.emptyList());

    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));

    /** A language root and what it is called in an hreflang. */
    static final class Root {
        final String path;
        final String hreflang;

        Root(String path, String hreflang) {
            this.path = path;
            this.hreflang = hreflang;
        }
    }

    /** One version of a page. */
    static final class Version {
        final String path;
        final String hreflang;

        Version(String path, String hreflang) {
            this.path = path;
            this.hreflang = hreflang;
        }
    }

    final List<Root> roots;

    private Languages(List<Root> roots) {
        this.roots = roots;
    }

    boolean isEmpty() {
        return roots.size() < 2;
    }

    /** The language roots of the site a page belongs to. */
    static Languages of(Resource page) {
        Resource site = siteOf(page);
        if (site == null) {
            return NONE;
        }
        List<Root> found = new ArrayList<>();
        collect(site, 0, found);
        return found.size() < 2 ? NONE : new Languages(found);
    }

    /** The topmost page above a page (or the page itself). */
    static Resource siteOf(Resource page) {
        Resource site = null;
        for (Resource p = page; p != null && Pages.isPage(p); p = p.getParent()) {
            site = p;
        }
        return site;
    }

    private static void collect(Resource page, int depth, List<Root> out) {
        if (depth >= 2) {
            return;
        }
        for (Resource child : page.getChildren()) {
            if (!Pages.isPage(child)) {
                continue;
            }
            if (Pages.parseLanguage(child.getName()) != null && (depth == 1 || !holdsLanguages(child))) {
                out.add(new Root(child.getPath(), hreflang(child)));
            } else {
                collect(child, depth + 1, out);
            }
        }
    }

    private static boolean holdsLanguages(Resource page) {
        for (Resource child : page.getChildren()) {
            if (Pages.isPage(child) && Pages.parseLanguage(child.getName()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a language root is called: its language, with the country when its content or the page
     * above says one ({@code jcr:language} {@code en_CA} on Canada, or a parent named after a country).
     */
    static String hreflang(Resource root) {
        Locale named = Pages.parseLanguage(root.getName());
        String language = named.getLanguage();
        String country = named.getCountry();
        if (country.isEmpty()) {
            country = countryOf(root, language);
        }
        Resource parent = root.getParent();
        if (country.isEmpty() && parent != null) {
            country = countryOf(parent, language);
            if (country.isEmpty() && COUNTRIES.contains(parent.getName().toUpperCase(Locale.ROOT)) && parent.getName().length() == 2) {
                country = parent.getName().toUpperCase(Locale.ROOT);
            }
        }
        return country.isEmpty() ? language : language + "-" + country;
    }

    /** The country of a page's jcr:language, when that is of the same language. */
    private static String countryOf(Resource page, String language) {
        Resource content = page.getChild(NameConstants.NN_CONTENT);
        Locale l = content == null ? null : Pages.parseLanguage(content.getValueMap().get(Pages.PN_LANGUAGE, String.class));
        return l != null && l.getLanguage().equals(language) ? l.getCountry() : "";
    }

    /** The root a path is in, or null. */
    Root rootOf(String path) {
        for (Root r : roots) {
            if (path.equals(r.path) || path.startsWith(r.path + "/")) {
                return r;
            }
        }
        return null;
    }

    /**
     * The versions of a page that are in a sitemap, itself included; none when it has no other: a
     * lone version needs no links. One per hreflang (the first root wins).
     */
    List<Version> versions(ResourceResolver resolver, String path) {
        Root own = isEmpty() ? null : rootOf(path);
        if (own == null) {
            return Collections.emptyList();
        }
        String rest = path.substring(own.path.length());
        Map<String, Version> out = new LinkedHashMap<>();
        for (Root r : roots) {
            if (out.containsKey(r.hreflang)) {
                continue;
            }
            Resource version = resolver.getResource(r.path + rest);
            if (version != null && Pages.isPage(version) && SitemapServlet.inSitemap(version)) {
                out.put(r.hreflang, new Version(version.getPath(), r.hreflang));
            }
        }
        return out.size() < 2 ? Collections.emptyList() : new ArrayList<>(out.values());
    }
}
