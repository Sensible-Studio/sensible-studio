/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * The root of a publish instance. Sling sends {@code /} to its own {@code /starter.html}; a visitor who types the
 * bare host should land on the site. The configured home when there is one; else the only site there is; else,
 * with several sites, a short page that lists them. With no site at all, Sling's answer stands. Not on an author.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/",
                "sling.filter.methods=GET",
                "sling.filter.methods=HEAD",
                "service.ranking:Integer=1900"
        })
@Designate(ocd = RootHomeFilter.Config.class)
public class RootHomeFilter implements Filter {

    @ObjectClassDefinition(name = "Sensible Studio Live home",
            description = "Where the root address (/) of a publish instance leads.")
    public @interface Config {
        @AttributeDefinition(name = "Home", description = "A page path (/content/site/en) or an address. Empty: the "
                + "only site, or a list of the sites when there are several.")
        String home() default "";
    }

    static final String CONTENT = "/content";

    /** The product's own areas under /content: they hold things for sites and are not one. */
    static final java.util.Set<String> AREAS = new java.util.HashSet<>(java.util.Arrays.asList(
            "forms", "experience-fragments", "dam", "launches", "usergenerated", "projects", "cq:tags", "cq:graphql"));

    private volatile String home = "";

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC,
            policyOption = ReferencePolicyOption.GREEDY)
    private volatile org.apache.sling.settings.SlingSettingsService settings;

    @Activate
    @Modified
    void activate(Config config) {
        home = config.home() == null ? "" : config.home().trim();
    }

    private boolean live() {
        org.apache.sling.settings.SlingSettingsService s = settings;
        return s != null && s.getRunModes().contains("publish");
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest && live()) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            String uri = request.getRequestURI().substring(request.getContextPath().length());
            if ("/".equals(uri)) {
                HttpServletResponse response = (HttpServletResponse) res;
                Map<String, String> sites = sites(request.getResourceResolver());
                String target = target(home, sites);
                if (target != null) {
                    response.sendRedirect(request.getContextPath() + target);
                    return;
                }
                if (sites.size() > 1) {
                    response.setContentType("text/html;charset=UTF-8");
                    response.setHeader("Cache-Control", "no-cache");
                    response.getWriter().write(list(request.getContextPath(), sites));
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** Where {@code /} leads: the configured home, the only site, or null (several sites, or none). */
    static String target(String home, Map<String, String> sites) {
        if (home != null && !home.isEmpty()) {
            return address(home);
        }
        return sites.size() == 1 ? address(sites.keySet().iterator().next()) : null;
    }

    private static String address(String path) {
        return path.startsWith("/content/") && path.indexOf('.') < 0 ? path + ".html" : path;
    }

    /** The sites this session can see: the pages right under /content that are not hidden, path → title. */
    static Map<String, String> sites(ResourceResolver resolver) {
        Map<String, String> sites = new LinkedHashMap<>();
        Resource content = resolver.getResource(CONTENT);
        if (content == null) {
            return sites;
        }
        for (Resource child : content.getChildren()) {
            Resource jcrContent = child.getChild("jcr:content");
            if (jcrContent == null || AREAS.contains(child.getName())) {
                continue;
            }
            ValueMap vm = jcrContent.getValueMap();
            // a page that only holds things (forms, fragments) has no pages of its own to show and says so
            if (vm.get("hideInNav", false) || !hasPages(child)) {
                continue;
            }
            sites.put(child.getPath(), vm.get("jcr:title", child.getName()));
        }
        return sites;
    }

    private static boolean hasPages(Resource site) {
        for (Resource child : site.getChildren()) {
            if (child.getChild("jcr:content") != null) {
                return true;
            }
        }
        return false;
    }

    static String list(String contextPath, Map<String, String> sites) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Sites</title>"
                + "<style>body{font:16px/1.5 system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;"
                + "background:#f6f6f4;color:#1c1c1a}main{width:min(28rem,calc(100% - 2rem))}h1{font-size:1.25rem;margin:0 0 1rem}"
                + "ul{list-style:none;margin:0;padding:0}a{display:block;padding:.75rem 1rem;margin-bottom:.5rem;background:#fff;"
                + "border:1px solid #deded9;border-radius:8px;color:inherit;text-decoration:none}a:hover,a:focus{border-color:#1c1c1a}"
                + "</style></head><body><main><h1>Sites</h1><ul>");
        for (Map.Entry<String, String> e : sites.entrySet()) {
            sb.append("<li><a href=\"").append(esc(contextPath + address(e.getKey()))).append("\">").append(esc(e.getValue()))
                    .append("</a></li>");
        }
        return sb.append("</ul></main></body></html>").toString();
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
