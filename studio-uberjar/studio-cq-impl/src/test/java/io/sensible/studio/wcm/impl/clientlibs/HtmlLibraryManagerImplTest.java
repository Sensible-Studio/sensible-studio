/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.adobe.granite.ui.clientlibs.HtmlLibrary;
import com.adobe.granite.ui.clientlibs.LibraryType;

@ExtendWith(SlingContextExtension.class)
class HtmlLibraryManagerImplTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private HtmlLibraryManagerImpl manager;

    private void folder(String path, String[] categories, String[] embed, String[] deps, boolean proxy) {
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", "cq:ClientLibraryFolder");
        p.put("categories", categories);
        p.put("embed", embed);
        p.put("dependencies", deps);
        p.put("allowProxy", proxy);
        context.create().resource(path, p);
    }

    private void file(String path, String text) {
        context.load().binaryFile(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), path, "text/plain");
    }

    @BeforeEach
    void setUp() {
        String text = "/apps/core/wcm/components/text/v2/text/clientlibs/site";
        folder(text, new String[] {"core.wcm.components.text.v2"}, new String[0], new String[0], false);
        file(text + "/css.txt", "#base=css\ntext.css\n");
        file(text + "/css/text.css", ".cmp-text{background:url(../images/bg.png)} .x{background:url(data:x)}");

        String base = "/apps/wknd/clientlibs/clientlib-base";
        folder(base, new String[] {"wknd.base"}, new String[] {"core.wcm.components.text.v2"},
                new String[] {"wknd.dependencies"}, true);
        file(base + "/css.txt", "base.css");
        file(base + "/base.css", "body{margin:0}");
        file(base + "/js.txt", "# comment\nbase.js");
        file(base + "/base.js", "console.log('wknd')");
        file(base + "/resources/fonts/a.woff2", "FONT");

        String deps = "/apps/wknd/clientlibs/clientlib-dependencies";
        folder(deps, new String[] {"wknd.dependencies"}, new String[0], new String[0], true);
        file(deps + "/js.txt", "deps.js");
        file(deps + "/deps.js", "var deps=1");

        manager = new HtmlLibraryManagerImpl(context.resourceResolver());
    }

    @Test
    void embedsComeFirstAndRelativeUrlsAreRewritten() throws Exception {
        HtmlLibrary css = manager.getLibrary(LibraryType.CSS, "/etc.clientlibs/wknd/clientlibs/clientlib-base");
        String out = new String(css.getInputStream(false).readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(out.indexOf(".cmp-text") < out.indexOf("body{margin:0}"), out);
        assertTrue(out.contains("url(/apps/core/wcm/components/text/v2/text/clientlibs/site/images/bg.png)"), out);
        assertTrue(out.contains("url(data:x)"), "data URLs untouched");
        assertEquals("/etc.clientlibs/wknd/clientlibs/clientlib-base.css", css.getPath());
    }

    @Test
    void writesTagsWithDependenciesFirst() throws Exception {
        StringWriter js = new StringWriter();
        manager.writeJsInclude(context.request(), js, "wknd.base");
        String s = js.toString();
        assertTrue(s.indexOf("clientlib-dependencies.js") < s.indexOf("clientlib-base.js"), s);
        assertTrue(s.contains("<script src=\"/etc.clientlibs/wknd/clientlibs/clientlib-base.js"), s);

        StringWriter css = new StringWriter();
        manager.writeCssInclude(context.request(), css, "wknd.base");
        assertFalse(css.toString().contains("clientlib-dependencies"), "deps has no CSS: " + css);
        assertTrue(css.toString().contains("rel=\"stylesheet\" href=\"/etc.clientlibs/wknd/clientlibs/clientlib-base.css"));
    }

    @Test
    void proxyRequiresAllowProxy() throws Exception {
        assertNull(manager.getLibrary(LibraryType.CSS, "/etc.clientlibs/core/wcm/components/text/v2/text/clientlibs/site"));
        assertNotNull(manager.getLibrary(LibraryType.CSS, "/apps/core/wcm/components/text/v2/text/clientlibs/site"));
        assertEquals("/apps/wknd/clientlibs/clientlib-base",
                manager.index().owning("/apps/wknd/clientlibs/clientlib-base/resources/fonts/a.woff2").getPath());
    }

    @Test
    void cacheIsDroppedOnChange() throws Exception {
        assertEquals(3, manager.getLibraries().size());
        folder("/apps/wknd/clientlibs/new", new String[] {"wknd.new"}, new String[0], new String[0], true);
        assertEquals(3, manager.getLibraries().size(), "cached");
        manager.onChange(java.util.Collections.emptyList());
        assertEquals(4, manager.getLibraries().size());
    }

    @Test
    void pathHelpers() {
        assertEquals("/a/c/d.png?x#y", HtmlLibraryManagerImpl.normalize("/a/b/../c/./d.png?x#y"));
        assertEquals("a{b:url('/p/q/i.png')}", HtmlLibraryManagerImpl.rewriteUrls("a{b:url('../q/i.png')}", "/p/r"));
        assertEquals("font/woff2", ClientLibServingFilter.guessMime("x.WOFF2"));
    }
}
