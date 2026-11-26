/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.launches.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class LaunchesTest {

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;

    @BeforeEach
    void setUp() throws Exception {
        session = context.resourceResolver().adaptTo(Session.class);
        Node content = session.getRootNode().addNode("content", "sling:Folder");
        Node site = content.addNode("site", "cq:Page");
        site.addNode("jcr:content", "cq:PageContent").setProperty("cq:conf", "/conf/site");
        Node en = site.addNode("en", "cq:Page");
        Node jc = en.addNode("jcr:content", "cq:PageContent");
        jc.setProperty("jcr:title", "English");
        jc.addNode("root", "nt:unstructured").addNode("text", "nt:unstructured").setProperty("text", "Hello");
        Node about = en.addNode("about", "cq:Page");
        about.addNode("jcr:content", "cq:PageContent").setProperty("jcr:title", "About");
        session.save();
    }

    @Test
    void createsEditsAndPromotesALaunch() throws Exception {
        Calendar live = Calendar.getInstance();
        live.add(Calendar.DATE, 7);
        Launches.Launch l = Launches.create(session, "/content/site/en", "Spring campaign", live, true, false, null);
        session.save();
        assertTrue(l.path.startsWith("/content/launches/"));
        assertEquals("spring-campaign", l.name);
        assertEquals("/content/site/en", l.source);
        String inLaunch = l.sourceInLaunch();
        assertTrue(session.nodeExists(inLaunch + "/jcr:content/root/text"), "the page was copied");
        assertTrue(session.nodeExists(inLaunch + "/about/jcr:content"), "deep: the subpage too");
        Node shell = session.getNode(l.path + "/content/content/site");
        assertTrue(shell.isNodeType("cq:Page"));
        assertEquals("/conf/site", shell.getNode("jcr:content").getProperty("cq:conf").getString(), "ancestors are shells with their properties");
        assertTrue(Launches.isShell(shell));

        assertEquals(l.path, Launches.of(session, inLaunch + "/about/jcr:content/root").path);
        assertEquals("/content/site/en/about", Launches.sourceOf(l, inLaunch + "/about"));
        assertNull(Launches.of(session, "/content/site/en"));

        List<Launches.LaunchPage> pages = Launches.pages(session, l);
        assertEquals(2, pages.size());
        assertFalse(pages.get(0).modified);

        // edit in the launch, add a page, then promote only what changed
        Thread.sleep(5);
        session.getNode(inLaunch + "/jcr:content/root/text").setProperty("text", "Hello spring");
        session.getNode(inLaunch + "/jcr:content").setProperty("cq:lastModified", Calendar.getInstance());
        Node fresh = session.getNode(inLaunch).addNode("offers", "cq:Page");
        Node fjc = fresh.addNode("jcr:content", "cq:PageContent");
        fjc.setProperty("jcr:title", "Offers");
        fjc.setProperty("cq:lastModified", Calendar.getInstance());
        session.save();

        Launches.Promotion p = Launches.promote(session, l, "all", null, true);
        session.save();
        assertEquals(List.of("/content/site/en"), p.promoted);
        assertEquals(List.of("/content/site/en/offers"), p.created);
        assertEquals(List.of(inLaunch + "/about"), p.skipped);
        assertEquals("Hello spring", session.getNode("/content/site/en/jcr:content/root/text").getProperty("text").getString());
        assertEquals("Offers", session.getNode("/content/site/en/offers/jcr:content").getProperty("jcr:title").getString());
        assertEquals("About", session.getNode("/content/site/en/about/jcr:content").getProperty("jcr:title").getString(), "untouched page left alone");
        assertNotNull(new Launches.Launch(session.getNode(l.path)).promoted);
        int versions = 0;
        for (javax.jcr.version.VersionIterator it = session.getWorkspace().getVersionManager().getVersionHistory("/content/site/en").getAllVersions(); it.hasNext(); it.nextVersion()) {
            versions++;
        }
        assertTrue(versions >= 2, "the source kept a version from before: " + versions);
    }

    @Test
    void aLaunchOfSeveralPages() throws Exception {
        Node en = session.getNode("/content/site/en");
        en.addNode("news", "cq:Page").addNode("jcr:content", "cq:PageContent").setProperty("jcr:title", "News");
        session.save();
        Launches.Launch l = Launches.create(session, "/content/site/en/about", "Two pages", null, false, false, null);
        String in = Launches.addSource(session, l, "/content/site/en/news", false, false);
        session.save();
        l = new Launches.Launch(session.getNode(l.path));
        assertEquals(List.of("/content/site/en/about", "/content/site/en/news"), l.sources);
        assertEquals(l.path + "/content/content/site/en/news", in);
        assertTrue(Launches.isShell(session.getNode(l.path + "/content/content/site/en")), "the shared ancestor stays one shell");
        assertEquals(2, Launches.pages(session, l).size());
        assertTrue(l.covers("/content/site/en/news") && !l.covers("/content/site/en"));
        Launches.Launch again = l;
        assertThrows(IllegalArgumentException.class, () -> Launches.addSource(session, again, "/content/site/en/news", false, false));

        session.getNode(in + "/jcr:content").setProperty("jcr:title", "News!");
        session.getNode(in + "/jcr:content").setProperty("cq:lastModified", Calendar.getInstance());
        session.save();
        Launches.Promotion p = Launches.promote(session, l, "all", null, false);
        session.save();
        assertEquals(List.of("/content/site/en/about", "/content/site/en/news"), p.promoted);
        assertEquals("News!", session.getNode("/content/site/en/news/jcr:content").getProperty("jcr:title").getString());
    }

    @Test
    void aProductionReadyLaunchGoesLiveAtItsDate() throws Exception {
        Launches.Launch none = Launches.create(session, "/content/site/en/about", "No date", null, false, false, null);
        assertThrows(IllegalArgumentException.class, () -> Launches.setProductionReady(session, none, true));
        session.refresh(false);

        Calendar past = Calendar.getInstance();
        past.add(Calendar.MINUTE, -1);
        Calendar future = Calendar.getInstance();
        future.add(Calendar.DATE, 1);
        Launches.Launch soon = Launches.create(session, "/content/site/en/about", "Later", future, false, false, null);
        Launches.setProductionReady(session, soon, true);
        Launches.Launch l = Launches.create(session, "/content/site/en", "Now", past, false, false, null);
        session.save();
        assertFalse(new Launches.Launch(session.getNode(l.path)).due(Calendar.getInstance()), "not production ready: left alone");
        Launches.setProductionReady(session, l, true);
        session.getNode(l.sourceInLaunch() + "/jcr:content").setProperty("jcr:title", "English, live");
        session.save();
        assertFalse(new Launches.Launch(session.getNode(soon.path)).due(Calendar.getInstance()), "its date has not come");
        l = new Launches.Launch(session.getNode(l.path));
        assertTrue(l.due(Calendar.getInstance()));

        // without replication the promotion stands and the launch says why it was not published
        LaunchAutoPromotion.goLive(session, null, l);
        l = new Launches.Launch(session.getNode(l.path));
        assertEquals("English, live", session.getNode("/content/site/en/jcr:content").getProperty("jcr:title").getString());
        assertNotNull(l.promoted);
        assertNotNull(l.autoError);
        assertFalse(l.due(Calendar.getInstance()), "promoted: not again");

        Launches.Promotion p = new Launches.Promotion();
        p.promoted.add("/content/site/en/b");
        p.created.add("/content/site/en/a");
        List<String> sent = new java.util.ArrayList<>();
        com.day.cq.replication.Replicator fake = (com.day.cq.replication.Replicator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {com.day.cq.replication.Replicator.class}, (proxy, m, args) -> {
                    if (m.getName().equals("replicate") && args[2] instanceof String[]) {
                        sent.addAll(java.util.Arrays.asList((String[]) args[2]));
                    }
                    return null;
                });
        assertEquals(List.of("/content/site/en/a", "/content/site/en/b"), LaunchAutoPromotion.publish(fake, session, p));
        assertEquals(List.of("/content/site/en/a", "/content/site/en/b"), sent);
    }
}
