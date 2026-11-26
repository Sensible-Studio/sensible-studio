/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import com.day.cq.commons.Externalizer;

/**
 * Domain-based URL externalization. Domains are configured as {@code name url} pairs, e.g.
 * {@code publish https://www.example.com}.
 */
@Component(service = Externalizer.class)
@Designate(ocd = ExternalizerImpl.Config.class)
public class ExternalizerImpl implements Externalizer {

    @ObjectClassDefinition(name = "Sensible Studio Externalizer")
    public @interface Config {
        @AttributeDefinition(name = "Domains", description = "Entries of the form '<name> <scheme>://<host>[:port]'")
        String[] domains() default {"local http://localhost:4502", "author http://localhost:4502",
                "publish http://localhost:4503"};
    }

    private volatile Map<String, String> domains = new HashMap<>();

    @Activate
    @Modified
    void activate(Config config) {
        Map<String, String> map = new HashMap<>();
        for (String entry : config.domains()) {
            String[] parts = entry.trim().split("\\s+", 2);
            if (parts.length == 2) {
                map.put(parts[0], stripSlash(parts[1]));
            }
        }
        domains = map;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String publishLink(ResourceResolver resolver, String path) {
        return externalLink(resolver, PUBLISH, path);
    }

    @Override
    public String publishLink(ResourceResolver resolver, String scheme, String path) {
        return externalLink(resolver, PUBLISH, scheme, path);
    }

    @Override
    public String authorLink(ResourceResolver resolver, String path) {
        return externalLink(resolver, AUTHOR, path);
    }

    @Override
    public String authorLink(ResourceResolver resolver, String scheme, String path) {
        return externalLink(resolver, AUTHOR, scheme, path);
    }

    @Override
    public String externalLink(ResourceResolver resolver, String domain, String path) {
        return externalLink(resolver, domain, null, path);
    }

    @Override
    public String externalLink(ResourceResolver resolver, String domain, String scheme, String path) {
        String base = domains.get(domain);
        if (base == null) {
            throw new IllegalArgumentException("Unknown externalizer domain: " + domain);
        }
        if (scheme != null) {
            int idx = base.indexOf("://");
            base = scheme + (idx < 0 ? "://" + base : base.substring(idx));
        }
        String mapped = resolver == null || path == null ? path : resolver.map(path);
        return base + (mapped == null ? "" : mapped);
    }

    @Override
    public String absoluteLink(SlingHttpServletRequest request, String scheme, String path) {
        String s = scheme != null ? scheme : request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(s) && port == 80) || ("https".equals(s) && port == 443);
        String mapped = request.getResourceResolver().map(request, path);
        return s + "://" + request.getServerName() + (defaultPort || port <= 0 ? "" : ":" + port) + mapped;
    }

    @Override
    public String relativeLink(SlingHttpServletRequest request, String path) {
        return request.getResourceResolver().map(request, path);
    }
}
