/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static io.sensible.studio.wcm.impl.WcmTestSupport.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.tagging.Tag;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageFilter;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;

@ExtendWith(SlingContextExtension.class)
class PageImplTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private PageManager pm;

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        pm = context.resourceResolver().adaptTo(PageManager.class);
        assertNotNull(pm, "resolver must adapt to PageManager");
    }

    private Page page(String path) {
        Page page = pm.getPage(path);
        assertNotNull(page, path);
        return page;
    }

    private static List<String> names(Iterator<Page> it) {
        List<String> names = new ArrayList<>();
        it.forEachRemaining(p -> names.add(p.getName()));
        return names;
    }

    @Test
    void resourceAdaptsToPageOnlyForPages() {
        Resource res = context.resourceResolver().getResource(EN);
        Page page = res.adaptTo(Page.class);
        assertNotNull(page);
        assertEquals(EN, page.getPath());
        assertSame(res, page.adaptTo(Resource.class));

        assertNull(context.resourceResolver().getResource(EN + "/jcr:content").adaptTo(Page.class));
        assertNull(context.resourceResolver().getResource(EN + "/assets-folder").adaptTo(Page.class));
        assertNull(pm.getPage("/content/nope"));
    }

    @Test
    void titlesAndProperties() {
        Page p = page(EN + "/adventures");
        assertEquals("Adventures", p.getTitle());
        assertEquals("All Adventures", p.getNavigationTitle());
        assertEquals("WKND Adventures", p.getPageTitle());
        assertEquals("Find your next trip", p.getDescription());
        assertEquals("/adventures", p.getVanityUrl());
        assertEquals("editor", p.getLastModifiedBy());
        assertEquals("adventures", p.getName());
        assertTrue(p.hasContent());
        assertEquals("Adventures", p.getProperties().get("jcr:title", String.class));
        assertEquals("Welcome", page(EN).getProperties("root/container/title").get("jcr:title", String.class));
        assertNull(page(EN).getProperties("no/such/child"));
    }

    @Test
    void hierarchy() {
        Page p = page(EN + "/adventures/bali-surf-camp");
        assertEquals(6, p.getDepth());
        assertEquals(2, page("/content/wknd").getDepth(), "/content counts as depth 1");
        assertEquals(EN + "/adventures", p.getParent().getPath());
        assertEquals(EN, p.getParent(2).getPath());
        assertEquals("/content/wknd", p.getAbsoluteParent(1).getPath());
        assertEquals(EN, p.getAbsoluteParent(3).getPath());
        assertEquals(EN + "/adventures", p.getAbsoluteParent(4).getPath());
        assertEquals(p.getPath(), p.getAbsoluteParent(5).getPath());
        assertNull(p.getAbsoluteParent(6));
        assertNull(p.getAbsoluteParent(0), "/content is a folder, not a page");
        assertNull(page("/content/wknd").getParent());

        assertTrue(page(EN).hasChild("adventures"));
        assertFalse(page(EN).hasChild("assets-folder"));
    }

    @Test
    void listChildrenWithFilterAndDeep() {
        Page en = page(EN);
        assertEquals(List.of("adventures", "faqs", "magazine"), names(en.listChildren()));
        assertEquals(List.of("adventures", "magazine"), names(en.listChildren(new PageFilter())));
        assertEquals(List.of("adventures", "bali-surf-camp", "riverside-camping", "faqs", "magazine"),
                names(en.listChildren(null, true)));
        assertEquals(List.of("bali-surf-camp"),
                names(en.listChildren(pg -> pg.getName().startsWith("bali"), true)));
    }

    @Test
    void validityWindow() {
        Page faqs = page(EN + "/faqs");
        assertTrue(faqs.isValid());
        assertEquals(0, faqs.timeUntilValid());

        ModifiableValueMap mvm = faqs.getContentResource().adaptTo(ModifiableValueMap.class);
        Calendar future = Calendar.getInstance();
        future.add(Calendar.HOUR, 1);
        mvm.put("onTime", future);
        assertFalse(faqs.isValid());
        assertTrue(faqs.timeUntilValid() > 0);

        mvm.remove("onTime");
        Calendar past = Calendar.getInstance();
        past.add(Calendar.HOUR, -1);
        mvm.put("offTime", past);
        assertFalse(faqs.isValid());
        assertTrue(faqs.timeUntilValid() < 0);
        assertFalse(new PageFilter(false, true).includes(faqs));
        assertTrue(new PageFilter(true, true).includes(faqs));
    }

    @Test
    void language() {
        assertEquals(Locale.ENGLISH, page(EN + "/adventures/bali-surf-camp").getLanguage());
        assertEquals(new Locale("de", "CH"), page(EN + "/magazine").getLanguage(false));
        assertEquals(Locale.ENGLISH, page(EN + "/magazine").getLanguage(true));
    }

    @Test
    void tagsResolveAndSkipMissing() {
        Tag[] tags = page(EN + "/adventures").getTags();
        assertEquals(1, tags.length);
        Tag surfing = tags[0];
        assertEquals("wknd:activity/surfing", surfing.getTagID());
        assertEquals("activity/surfing", surfing.getLocalTagID());
        assertEquals("Surfing", surfing.getTitle());
        assertEquals("Surfen", surfing.getTitle(Locale.GERMAN));
        assertEquals("Activity", surfing.getParent().getTitle());
        assertEquals("wknd:", surfing.getNamespace().getTagID());
        assertNull(surfing.getNamespace().getParent());
    }

    @Test
    void template() {
        Page en = page(EN);
        assertEquals(TEMPLATE, en.getTemplate().getPath());
        assertEquals("Content Page", en.getTemplate().getTitle());
        assertEquals(10L, en.getTemplate().getRanking());
        assertTrue(en.getTemplate().hasStructureSupport());
        assertNull(page(EN + "/faqs").getTemplate());
    }

    @Test
    void locking() throws WCMException {
        Page faqs = page(EN + "/faqs");
        assertFalse(faqs.isLocked());
        faqs.lock();
        assertTrue(faqs.isLocked());
        assertNotNull(faqs.getLockOwner());
        assertTrue(faqs.canUnlock());
        assertThrows(WCMException.class, faqs::lock);
        faqs.unlock();
        assertFalse(faqs.isLocked());
    }

    @Test
    void containingPage() {
        Resource text = context.resourceResolver().getResource(EN + "/jcr:content/root/container/container/text");
        assertEquals(EN, pm.getContainingPage(text).getPath());
        assertEquals(EN, pm.getContainingPage(EN + "/jcr:content/root/does/not/exist").getPath());
        assertNull(pm.getContainingPage("/conf/wknd"));
    }

    @Test
    void vanityArrayWithoutEntries() {
        assertNull(page(EN).getVanityUrl());
        assertArrayEquals(new Tag[0], page(EN).getTags());
    }
}
