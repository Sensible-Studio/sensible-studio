/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.script.Bindings;
import javax.script.SimpleBindings;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.commons.Externalizer;
import com.day.cq.wcm.api.LanguageManager;
import com.day.cq.wcm.api.WCMMode;
import com.day.cq.wcm.api.components.Component;
import com.day.cq.wcm.api.components.ComponentContext;
import com.day.cq.wcm.api.components.ComponentManager;
import com.day.cq.wcm.api.designer.Designer;
import com.day.cq.wcm.api.designer.Style;
import com.day.cq.wcm.commons.WCMUtils;
import com.day.text.Text;

@ExtendWith(SlingContextExtension.class)
class RenderingApiTest {

    private final SlingContext context = WcmTestSupport.newContext();

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
    }

    private Resource res(String rel) {
        return context.resourceResolver().getResource(EN + "/jcr:content" + rel);
    }

    @Test
    void styleComesFromPolicy() {
        Designer designer = context.resourceResolver().adaptTo(Designer.class);
        Style style = designer.getStyle(res("/root/container/title"));
        assertEquals("h2", style.get("type", String.class));
        assertArrayEquals(new String[] {"h1", "h2", "h3"}, style.get("allowedTypes", String[].class));
        assertEquals("page/root/container/title", style.getCell().getPath());
        assertEquals("fallback", designer.getStyle(context.resourceResolver().getResource("/conf/wknd"))
                .get("missing", "fallback"), "no policy gives an empty style, never null");
    }

    @Test
    void componentsResolveThroughSearchPath() {
        ComponentManager cm = context.resourceResolver().adaptTo(ComponentManager.class);
        Component title = cm.getComponentOfResource(res("/root/container/title"));
        assertEquals("/apps/wknd/components/title", title.getPath());
        assertEquals("wknd/components/title", title.getResourceType());
        assertEquals("Title (v3)", title.getSuperComponent().getTitle());
        assertTrue(title.isEditable());
        assertTrue(cm.getComponent("wknd/components/container").isContainer());
        assertNull(cm.getComponent("wknd/components/nope"));
        assertEquals(2 + 1, cm.getComponents().size());
    }

    @Test
    void filterPushesAndRestoresComponentContext() throws Exception {
        WcmComponentFilter filter = new WcmComponentFilter();
        AtomicReference<ComponentContext> outer = new AtomicReference<>();
        AtomicReference<ComponentContext> inner = new AtomicReference<>();

        context.currentResource(res(""));
        filter.doFilter(context.request(), context.response(), (rq, rs) -> {
            outer.set(WCMUtils.getComponentContext(rq));
            context.currentResource(res("/root/container/title"));
            filter.doFilter(rq, rs, (rq2, rs2) -> inner.set(WCMUtils.getComponentContext(rq2)));
            assertSame(outer.get(), WCMUtils.getComponentContext(rq), "restored after the include");
        });
        assertTrue(outer.get().isRoot());
        assertEquals(EN, outer.get().getPage().getPath());
        assertSame(outer.get(), inner.get().getParent());
        assertEquals("/apps/wknd/components/title", inner.get().getComponent().getPath());
        assertNull(WCMUtils.getComponentContext(context.request()));
        assertEquals(WCMMode.DISABLED, WCMMode.fromRequest(context.request()));
    }

    @Test
    void wcmmodeFromParameter() {
        assertEquals(WCMMode.EDIT, WcmComponentFilter.modeFromParameter("edit"));
        assertEquals(WCMMode.DISABLED, WcmComponentFilter.modeFromParameter("bogus"));
    }

    @Test
    void bindingsIncludeStyleComponentAndMode() {
        Resource title = res("/root/container/title");
        context.currentResource(title);
        Bindings b = new SimpleBindings();
        b.put(SlingBindings.RESOURCE, title);
        b.put(SlingBindings.REQUEST, context.request());
        new WcmBindingsValuesProvider().addBindings(b);
        assertEquals("h2", ((Style) b.get("currentStyle")).get("type", String.class));
        assertEquals("/apps/wknd/components/title", ((Component) b.get("component")).getPath());
        assertTrue(((WcmModeBinding) b.get("wcmmode")).isDisabled());
    }

    @Test
    void languageManager() {
        LanguageManager lm = context.registerInjectActivateService(new LanguageManagerImpl());
        assertEquals(EN, lm.getLanguageRoot(res("/root/container/title")).getPath());
        assertEquals(Locale.ENGLISH, lm.getLanguage(res(""), true));
        assertNull(lm.getLanguageRoot(context.resourceResolver().getResource("/content/wknd/us")));
    }

    @Test
    void externalizer() {
        Externalizer ext = context.registerInjectActivateService(new ExternalizerImpl(),
                "domains", new String[] {"publish https://wknd.example/"});
        assertEquals("https://wknd.example/content/wknd/us/en.html",
                ext.publishLink(context.resourceResolver(), "/content/wknd/us/en.html"));
        assertEquals("http://wknd.example/x", ext.publishLink(context.resourceResolver(), "http", "/x"));
    }

    @Test
    void modelFilterDropsBookkeeping() {
        List<Resource> kids = new java.util.ArrayList<>();
        new SlingModelFilterImpl().filterChildResources(res("/root").getChildren()).forEach(kids::add);
        assertEquals(1, kids.size());
        assertFalse(new SlingModelFilterImpl().filterProperties(res("").getValueMap()).containsKey("jcr:primaryType"));
    }

    @Test
    void textHelpers() {
        assertArrayEquals(new String[] {"a", "b"}, Text.explode("/a//b", '/'));
        assertArrayEquals(new String[] {"", "a", "", "b"}, Text.explode("/a//b", '/', true));
        assertEquals("c", Text.getName("/a/b/c"));
        assertEquals("/a", Text.getRelativeParent("/a/b/c", 2));
        assertEquals("&lt;b&gt; &amp; &quot;x&quot;", Text.escapeXml("<b> & \"x\""));
        assertNotNull(Text.implode(new String[] {"a", "b"}, ","));
    }
}
