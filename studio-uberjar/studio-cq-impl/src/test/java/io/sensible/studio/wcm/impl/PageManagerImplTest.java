/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static io.sensible.studio.wcm.impl.WcmTestSupport.EN;
import static io.sensible.studio.wcm.impl.WcmTestSupport.TEMPLATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.PageManagerFactory;
import com.day.cq.wcm.api.WCMException;

@ExtendWith(SlingContextExtension.class)
class PageManagerImplTest {

    private final SlingContext context = WcmTestSupport.newContext();
    private PageManager pm;

    @BeforeEach
    void setUp() {
        WcmTestSupport.setUp(context);
        pm = context.getService(PageManagerFactory.class).getPageManager(context.resourceResolver());
    }

    private List<String> childNames(String path) {
        List<String> names = new ArrayList<>();
        pm.getPage(path).listChildren().forEachRemaining(p -> names.add(p.getName()));
        return names;
    }

    @Test
    void createFromEditableTemplateCopiesInitialContent() throws WCMException {
        Page created = pm.create(EN, null, TEMPLATE, "Ski Touring: Alps!");
        assertEquals(EN + "/ski-touring-alps", created.getPath());
        assertEquals("Ski Touring: Alps!", created.getTitle());
        assertEquals(TEMPLATE, created.getProperties().get("cq:template", String.class));
        assertEquals("wknd/components/page", created.getContentResource().getResourceType());
        assertNotNull(created.getContentResource("root/container"), "initial content must be copied");
        assertNotNull(created.getLastModified());

        Page second = pm.create(EN, null, TEMPLATE, "Ski Touring: Alps!");
        assertEquals(EN + "/ski-touring-alps-1", second.getPath(), "derived names are made unique");
    }

    @Test
    void createRejectsBadInput() {
        assertThrows(WCMException.class, () -> pm.create("/content/missing", "x", null, "X"));
        assertThrows(WCMException.class, () -> pm.create(EN, "faqs", null, "dup"));
        assertThrows(WCMException.class, () -> pm.create(EN, "x", "/conf/nope", "X"));
        assertThrows(WCMException.class,
                () -> pm.create(EN, "x", "/conf/wknd/settings/wcm/templates/restricted", "X"),
                "allowedPaths must be enforced");
    }

    @Test
    void createWithoutTemplate() throws WCMException {
        Page created = pm.create(EN, "blank", null, null);
        assertTrue(created.hasContent());
        assertNull(created.getTitle());
        assertEquals("cq:PageContent", created.getProperties().get("jcr:primaryType", String.class));
    }

    @Test
    void deepAndShallowDelete() throws WCMException {
        pm.delete(pm.getPage(EN + "/faqs"), false);
        assertNull(context.resourceResolver().getResource(EN + "/faqs"));

        pm.delete(pm.getPage(EN + "/adventures"), true);
        Resource adventures = context.resourceResolver().getResource(EN + "/adventures");
        assertNotNull(adventures, "shallow delete keeps the page node");
        assertNull(adventures.getChild("jcr:content"));
        assertNotNull(pm.getPage(EN + "/adventures/bali-surf-camp"), "child pages survive");
    }

    @Test
    void copyDeepShallowAndConflicts() throws WCMException {
        Page deep = pm.copy(pm.getPage(EN + "/adventures"), EN + "/adventures-copy", null, false, false);
        assertEquals(List.of("bali-surf-camp", "riverside-camping"), childNames(deep.getPath()));
        assertEquals("Adventures", deep.getTitle());

        Page shallow = pm.copy(pm.getPage(EN + "/adventures"), EN + "/adventures-copy", null, true, true);
        assertEquals(EN + "/adventures-copy-1", shallow.getPath());
        assertTrue(childNames(shallow.getPath()).isEmpty());

        assertThrows(WCMException.class,
                () -> pm.copy(pm.getPage(EN + "/adventures"), EN + "/adventures-copy", null, false, false));
        assertThrows(WCMException.class,
                () -> pm.copy(pm.getPage(EN + "/adventures"), EN + "/adventures/inside", null, false, true));
    }

    @Test
    void moveToNewParent() throws WCMException {
        Page moved = pm.move(pm.getPage(EN + "/adventures"), "/content/wknd/us/trips", null, false, false, null);
        assertEquals("/content/wknd/us/trips", moved.getPath());
        assertEquals("Adventures", moved.getTitle());
        assertNotNull(pm.getPage("/content/wknd/us/trips/bali-surf-camp"));
        assertNull(context.resourceResolver().getResource(EN + "/adventures"));
    }

    /**
     * The in-memory mock resolver cannot reorder children, so this only checks that the failure
     * surfaces as a WCMException. Real ordering needs a JCR-backed resolver and is not covered by these unit tests yet.
     */
    @Test
    void orderFailureIsReportedAsWcmException() {
        WCMException e = assertThrows(WCMException.class, () -> pm.order(pm.getPage(EN + "/magazine"), "adventures"));
        assertTrue(e.getMessage().contains("/magazine"));
        assertEquals(List.of("adventures", "faqs", "magazine"), childNames(EN), "nothing changed");
    }

    @Test
    void templateLookup() {
        assertNotNull(pm.getTemplate(TEMPLATE));
        assertNull(pm.getTemplate(EN), "a page is not a template");
        assertFalse(pm.getTemplate("/conf/wknd/settings/wcm/templates/restricted").hasStructureSupport());
    }
}
