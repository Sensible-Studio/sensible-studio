/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.sensible.studio.wcm.impl;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * A page that names another as its redirect ({@code cq:redirectTarget}, Page Properties › Redirect) sends its
 * visitors there. Nothing did: the root page of a site, which is only a signpost to its first country and
 * language, was served as it is, an empty frame, and it is where {@code /} of the live site leads when there is
 * one site. On a publish instance always; on the author when the page is asked for as published
 * ({@code wcmmode=disabled}), so the editor can still open the page that redirects.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.methods=GET",
                "sling.filter.methods=HEAD",
                "sling.filter.pattern=/content/.*",
                "service.ranking:Integer=1500"
        })
public class PageRedirectFilter implements Filter {

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    private volatile org.apache.sling.settings.SlingSettingsService settings;

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to set up
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            org.apache.sling.settings.SlingSettingsService s = settings;
            boolean asPublished = (s != null && s.getRunModes().contains("publish")) || "disabled".equals(request.getParameter("wcmmode"));
            if (asPublished && "html".equals(request.getRequestPathInfo().getExtension())
                    && request.getRequestPathInfo().getSelectorString() == null) {
                Resource page = request.getResource();
                Resource content = page.getChild("jcr:content");
                String target = target(page.getPath(), content == null ? null : content.getValueMap().get("cq:redirectTarget", String.class));
                if (target != null) {
                    // the address as the site's own links write it (/content/…), not the resolver's short form of it
                    String address = target.startsWith("/") ? request.getContextPath() + target : target;
                    if (target.startsWith("/") && "disabled".equals(request.getParameter("wcmmode"))) {
                        address += "?wcmmode=disabled";
                    }
                    ((HttpServletResponse) res).sendRedirect(address);
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * Where the page at {@code path} sends its visitors: a page of this repository as its {@code .html}, another
     * address as it is; null when it names none, itself, or something that is not an address.
     */
    static String target(String path, String redirect) {
        if (redirect == null) {
            return null;
        }
        String t = redirect.trim();
        if (t.isEmpty() || t.equals(path) || t.equals(path + ".html")) {
            return null;
        }
        if (t.startsWith("/content/")) {
            return t.substring(t.lastIndexOf('/') + 1).indexOf('.') < 0 ? t + ".html" : t;
        }
        return t.startsWith("https://") || t.startsWith("http://") ? t : null;
    }

    @Override
    public void destroy() {
        // nothing to release
    }
}
