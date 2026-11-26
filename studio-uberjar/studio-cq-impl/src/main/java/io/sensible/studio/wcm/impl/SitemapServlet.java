/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import com.day.cq.wcm.api.NameConstants;

/**
 * The sitemaps of a site, as the sitemap protocol wants them.
 * <ul>
 * <li>{@code <page>.sitemap.xml} for a page whose properties say <i>Generate Sitemap</i>
 * ({@code sling:sitemapRoot}, the SEO section of the Advanced tab): that page and the pages below it,
 * each with an absolute {@code loc}, its {@code lastmod} and, when the page exists in other languages
 * of the site that are in a sitemap too, an {@code xhtml:link rel="alternate" hreflang} for each
 * version (itself included, as search engines want). A page below that is a sitemap root of its own
 * is left to its own sitemap, with what is below it.</li>
 * <li>{@code <page>.sitemap-index.xml} on any page (the site's top page, usually): a sitemap index
 * naming the sitemap of every root at or below it, with the newest {@code lastmod} in each.</li>
 * <li>{@code <page>.robots.txt}: the site's robots.txt (the cache answers /robots.txt with the one of
 * the site's top page): the page's own text (Advanced › SEO), else everything allowed and a
 * {@code Sitemap:} line for each sitemap at or below it.</li>
 * </ul>
 * Left out of a sitemap: a page marked {@code noindex} (its Robots Tags), a page that only redirects,
 * and a page the requesting user cannot read (on a publish instance: what a closed user group hides
 * from visitors). The address is the one the resource resolver maps the page to, on the host of the
 * request, or on {@code externalBase} when the instance sits behind another name. At most 50&nbsp;000
 * URLs, the protocol's limit for one file. Any other page answers 404.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=cq:Page",
                "sling.servlet.resourceTypes=cq/Page",
                "sling.servlet.selectors=sitemap",
                "sling.servlet.selectors=sitemap-index",
                "sling.servlet.selectors=robots",
                "sling.servlet.extensions=xml",
                "sling.servlet.extensions=txt",
                "sling.servlet.methods=GET",
                "sling.servlet.methods=HEAD"
        })
@Designate(ocd = SitemapServlet.Config.class)
public class SitemapServlet extends SlingSafeMethodsServlet {

    @ObjectClassDefinition(name = "Sensible Studio: sitemaps", description = "The sitemap.xml of pages marked Generate Sitemap, their index and the site's robots.txt")
    public @interface Config {
        @AttributeDefinition(name = "External base", description = "Scheme and host the addresses start with, e.g. https://www.example.com; empty: those of the request")
        String externalBase() default "";

        @AttributeDefinition(name = "Extension", description = "What a page's address ends with")
        String extension() default ".html";

        @AttributeDefinition(name = "Language versions", description = "Name a page's versions in the site's other languages (hreflang), when they are in a sitemap too")
        boolean alternates() default true;
    }

    private static final long serialVersionUID = 1L;
    static final String SITEMAP_ROOT = "sling:sitemapRoot";
    static final String INDEX = "sitemap-index";
    static final String ROBOTS = "robots";
    /** The site's own robots.txt, on its top page (Advanced › SEO). */
    static final String ROBOTS_TXT = "robotsTxt";
    static final int MAX_URLS = 50000;
    static final String XHTML = "http://www.w3.org/1999/xhtml";

    private volatile String externalBase = "";
    private volatile String extension = ".html";
    private volatile boolean alternates = true;

    @Activate
    @Modified
    void activate(Config config) {
        externalBase = config.externalBase() == null ? "" : config.externalBase().trim().replaceAll("/+$", "");
        extension = config.extension() == null ? ".html" : config.extension();
        alternates = config.alternates();
    }

    /** One line of a sitemap. */
    static final class Entry {
        final String path;
        final Calendar modified;

        Entry(String path, Calendar modified) {
            this.path = path;
            this.modified = modified;
        }
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Resource page = request.getResource();
        String base = externalBase.isEmpty() ? baseOf(request) : externalBase;
        List<String> selectors = Arrays.asList(request.getRequestPathInfo().getSelectors());
        boolean txt = "txt".equals(request.getRequestPathInfo().getExtension());
        if (txt != selectors.contains(ROBOTS)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (txt) {
            robots(request, response, page, base);
            return;
        }
        if (selectors.contains(INDEX)) {
            index(request, response, page, base);
            return;
        }
        if (!isSitemapRoot(page)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No sitemap here: the page's properties do not say Generate Sitemap");
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        List<Entry> entries = entries(page, MAX_URLS);
        Languages languages = alternates ? Languages.of(page) : Languages.NONE;
        response.setContentType("application/xml");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\""
                + (languages.isEmpty() ? "" : " xmlns:xhtml=\"" + XHTML + "\"") + ">\n");
        for (Entry e : entries) {
            out.print("  <url><loc>" + escape(address(request, base, e.path)) + "</loc>");
            if (e.modified != null) {
                out.print("<lastmod>" + w3c(e.modified) + "</lastmod>");
            }
            for (Languages.Version v : languages.versions(resolver, e.path)) {
                out.print("<xhtml:link rel=\"alternate\" hreflang=\"" + escape(v.hreflang) + "\" href=\""
                        + escape(address(request, base, v.path)) + "\"/>");
            }
            out.print("</url>\n");
        }
        out.print("</urlset>\n");
    }

    private void index(SlingHttpServletRequest request, SlingHttpServletResponse response, Resource page, String base) throws IOException {
        if (!Pages.isPage(page)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        List<Resource> roots = roots(page);
        response.setContentType("application/xml");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (Resource root : roots) {
            out.print("  <sitemap><loc>" + escape(sitemapAddress(request, base, root.getPath())) + "</loc>");
            Calendar newest = newest(entries(root, MAX_URLS));
            if (newest != null) {
                out.print("<lastmod>" + w3c(newest) + "</lastmod>");
            }
            out.print("</sitemap>\n");
        }
        out.print("</sitemapindex>\n");
    }

    /**
     * {@code <page>.robots.txt}, which the site's /robots.txt is (the cache's rewrite rules say so):
     * the text of the page's robots.txt property when there is one, else everything allowed and the
     * sitemaps at or below the page named.
     */
    private void robots(SlingHttpServletRequest request, SlingHttpServletResponse response, Resource page, String base) throws IOException {
        if (!Pages.isPage(page)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        List<String> sitemaps = new ArrayList<>();
        for (Resource root : roots(page)) {
            sitemaps.add(sitemapAddress(request, base, root.getPath()));
        }
        Resource content = page.getChild(NameConstants.NN_CONTENT);
        String own = content == null ? null : content.getValueMap().get(ROBOTS_TXT, String.class);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().print(robotsTxt(own, sitemaps));
    }

    /** The robots.txt: the site's own text, or the default with a Sitemap line per sitemap. */
    static String robotsTxt(String own, List<String> sitemaps) {
        if (own != null && !own.trim().isEmpty()) {
            String text = own.replace("\r\n", "\n").replace('\r', '\n');
            return text.endsWith("\n") ? text : text + "\n";
        }
        StringBuilder out = new StringBuilder("User-agent: *\nDisallow:\n");
        if (!sitemaps.isEmpty()) {
            out.append('\n');
            for (String s : sitemaps) {
                out.append("Sitemap: ").append(s).append('\n');
            }
        }
        return out.toString();
    }

    /** A page's public address: as the resolver maps it, on the base, with the extension. */
    String address(SlingHttpServletRequest request, String base, String path) {
        return absolute(request, base, path) + extension;
    }

    /** The address of the sitemap of a root. */
    String sitemapAddress(SlingHttpServletRequest request, String base, String path) {
        return absolute(request, base, path) + ".sitemap.xml";
    }

    private static String absolute(SlingHttpServletRequest request, String base, String path) {
        String mapped = request.getResourceResolver().map(request, path);
        return mapped.startsWith("http://") || mapped.startsWith("https://") ? mapped : base + mapped;
    }

    static boolean isSitemapRoot(Resource page) {
        Resource content = page == null ? null : page.getChild(NameConstants.NN_CONTENT);
        return content != null && truthy(content.getValueMap().get(SITEMAP_ROOT));
    }

    /** Whether a page would be in a sitemap: it or a page above it is a root, and it is listed. */
    static boolean inSitemap(Resource page) {
        Resource content = page == null ? null : page.getChild(NameConstants.NN_CONTENT);
        if (content == null || !listed(content.getValueMap())) {
            return false;
        }
        for (Resource p = page; p != null && Pages.isPage(p); p = p.getParent()) {
            if (isSitemapRoot(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The root and the pages below it that belong in its sitemap, in document order. A page below that
     * is a root itself has a sitemap of its own: it and its pages are not repeated here.
     */
    static List<Entry> entries(Resource root, int max) {
        List<Entry> out = new ArrayList<>();
        ArrayDeque<Resource> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty() && out.size() < max) {
            Resource page = stack.pop();
            Resource content = page.getChild(NameConstants.NN_CONTENT);
            if (content != null && listed(content.getValueMap())) {
                ValueMap v = content.getValueMap();
                Calendar modified = v.get("cq:lastModified", Calendar.class);
                out.add(new Entry(page.getPath(), modified != null ? modified : v.get("jcr:created", Calendar.class)));
            }
            // children in reverse, so the stack pops them in their order
            List<Resource> children = new ArrayList<>();
            for (Resource child : page.getChildren()) {
                if (Pages.isPage(child) && !isSitemapRoot(child)) {
                    children.add(child);
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return out;
    }

    /** The sitemap roots at or below a page, in document order. */
    static List<Resource> roots(Resource top) {
        List<Resource> out = new ArrayList<>();
        ArrayDeque<Resource> stack = new ArrayDeque<>();
        stack.push(top);
        while (!stack.isEmpty()) {
            Resource page = stack.pop();
            if (isSitemapRoot(page)) {
                out.add(page);
            }
            List<Resource> children = new ArrayList<>();
            for (Resource child : page.getChildren()) {
                if (Pages.isPage(child)) {
                    children.add(child);
                }
            }
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return out;
    }

    static Calendar newest(List<Entry> entries) {
        Calendar newest = null;
        for (Entry e : entries) {
            if (e.modified != null && (newest == null || e.modified.after(newest))) {
                newest = e.modified;
            }
        }
        return newest;
    }

    /** Not for a sitemap: marked noindex, or only a redirect to somewhere else. */
    static boolean listed(ValueMap content) {
        String[] robots = content.get("cq:robotsTags", String[].class);
        if (robots != null && Arrays.stream(robots).anyMatch(r -> "noindex".equalsIgnoreCase(r.trim()))) {
            return false;
        }
        String redirect = content.get("cq:redirectTarget", content.get("sling:redirect", String.class));
        return redirect == null || redirect.isEmpty();
    }

    private static boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    static String baseOf(SlingHttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean standard = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (standard ? "" : ":" + port);
    }

    static String w3c(Calendar c) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(c.getTime());
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
