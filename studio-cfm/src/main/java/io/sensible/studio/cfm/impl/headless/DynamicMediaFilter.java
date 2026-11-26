package io.sensible.studio.cfm.impl.headless;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.osgi.service.component.annotations.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

/**
 * Routes {@code /adobe/dynamicmedia/deliver/dm-aid--<id>/<seo name>.<format>} (the {@code _dynamicUrl} GraphQL
 * returns) to {@link DynamicMediaServlet}. Sling resolves such a URL to a non-existing resource, since the path has
 * no extension right after {@code deliver}, so a path servlet alone never sees it.
 */
@Component(
        service = Filter.class,
        property = {
                "sling.filter.scope=REQUEST",
                "sling.filter.pattern=/adobe/dynamicmedia/deliver/.*",
                "service.ranking:Integer=1000"
        })
public class DynamicMediaFilter implements Filter {

    static final String PREFIX = AssetRef.DELIVER;

    private final DynamicMediaServlet servlet = new DynamicMediaServlet();

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        if (req instanceof SlingHttpServletRequest && res instanceof SlingHttpServletResponse) {
            SlingHttpServletRequest request = (SlingHttpServletRequest) req;
            String method = request.getMethod();
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (path.startsWith(PREFIX) && ("GET".equals(method) || "HEAD".equals(method))) {
                servlet.deliver(request, (SlingHttpServletResponse) res, decode(path.substring(PREFIX.length())));
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    @Override
    public void destroy() {
    }
}
