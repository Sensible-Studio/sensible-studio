/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.WCMException;

/** Page versions on a real Oak repository. */
@ExtendWith(SlingContextExtension.class)
class PageRevisionsTest {

    private static final String PAGE = "/content/site/en";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private ResourceResolver resolver;
    private PageManager pm;

    @BeforeEach
    void setUp() throws Exception {
        context.create().resource("/content/site", "jcr:primaryType", "sling:Folder");
        context.create().resource(PAGE, "jcr:primaryType", "cq:Page");
        context.create().resource(PAGE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "One");
        context.create().resource(PAGE + "/jcr:content/root", "jcr:primaryType", "nt:unstructured", "text", "a");
        resolver = context.resourceResolver();
        resolver.commit();
        pm = new PageManagerImpl(resolver);
    }

    private void title(String t) throws Exception {
        resolver.getResource(PAGE + "/jcr:content").adaptTo(ModifiableValueMap.class).put("jcr:title", t);
        resolver.commit();
    }

    @Test
    void createListAndRestore() throws Exception {
        Page page = pm.getPage(PAGE);
        assertTrue(pm.getRevisions(PAGE, null).isEmpty());
        Revision first = pm.createRevision(page, "launch", "first");
        title("Two");
        pm.createRevision(page);
        List<Revision> all = new ArrayList<>(pm.getRevisions(PAGE, null));
        assertEquals(2, all.size());
        assertEquals("Two", all.get(0).getTitle(), "newest first");
        assertEquals("launch", all.get(1).getLabel());
        assertEquals("first", all.get(1).getComment());
        assertEquals("One", all.get(1).getTitle());

        title("Three");
        Page restored = pm.restore(PAGE, first.getId());
        assertEquals("One", restored.getTitle());
        assertEquals(3, pm.getRevisions(PAGE, null).size(), "the state before the restore was saved");
        title("Editable after restore");
        assertThrows(WCMException.class, () -> pm.createRevision(pm.getPage(PAGE), "launch", null), "labels are unique");
    }

    @Test
    void aUsedLabelLeavesNoVersionBehind() throws Exception {
        pm.createRevision(pm.getPage(PAGE), "launch", null);
        title("Two");
        assertThrows(WCMException.class, () -> pm.createRevision(pm.getPage(PAGE), "launch", null));
        assertEquals(1, pm.getRevisions(PAGE, null).size());
    }

    @Test
    void restoreKeepsTheLiveCopyConfigurationTheLockAndTheReplicationStatus() throws Exception {
        Session s = resolver.adaptTo(Session.class);
        Revision plain = pm.createRevision(pm.getPage(PAGE));
        Node content = s.getNode(PAGE + "/jcr:content");
        content.addMixin("cq:LiveSync");
        Node cfg = content.addNode("cq:LiveSyncConfig", "cq:LiveCopy");
        cfg.setProperty("cq:master", "/content/site/master");
        cfg.setProperty("cq:isDeep", true);
        content.setProperty("cq:lastReplicationAction", "Activate");
        content.setProperty("jcr:title", "Live");
        s.save();
        Revision live = pm.createRevision(pm.getPage(PAGE));

        pm.restore(PAGE, plain.getId());
        content = s.getNode(PAGE + "/jcr:content");
        assertEquals("One", content.getProperty("jcr:title").getString());
        assertTrue(content.isNodeType("cq:LiveSync"), "an old version does not detach the page");
        assertEquals("/content/site/master", content.getProperty("cq:LiveSyncConfig/cq:master").getString());
        assertEquals("Activate", content.getProperty("cq:lastReplicationAction").getString());

        content.getNode("cq:LiveSyncConfig").remove();
        content.removeMixin("cq:LiveSync");
        content.getProperty("cq:lastReplicationAction").remove();
        s.save();
        pm.restore(PAGE, live.getId());
        content = s.getNode(PAGE + "/jcr:content");
        assertEquals("Live", content.getProperty("jcr:title").getString());
        assertFalse(content.hasNode("cq:LiveSyncConfig"), "an old version does not attach the page again");
        assertFalse(content.isNodeType("cq:LiveSync"));
        assertFalse(content.hasProperty("cq:lastReplicationAction"));
    }

    @Test
    void restoreIsRefusedOnAPageLockedBySomeoneElse() throws Exception {
        Revision first = pm.createRevision(pm.getPage(PAGE));
        resolver.getResource(PAGE + "/jcr:content").adaptTo(ModifiableValueMap.class).put("studio:lockOwner", "someone");
        resolver.commit();
        assertThrows(WCMException.class, () -> pm.restore(PAGE, first.getId()));
        assertEquals(1, pm.getRevisions(PAGE, null).size());
    }
}
