/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.siterail.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingHttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(SlingContextExtension.class)
class SiteRailTest {

    private static final String SITE = "/content/wknd";
    private static final String EN = SITE + "/language-masters/en";
    private static final String TEMPLATE = "/conf/wknd/settings/wcm/templates/page-content";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);

    @BeforeEach
    void setUp() {
        page(SITE, "WKND Site", "cq:conf", "/conf/wknd");
        page(SITE + "/language-masters", "Language Masters");
        page(EN, "WKND Adventures", "cq:template", TEMPLATE, "cq:lastModified", days(-1), "cq:lastModifiedBy", "editor",
                "jcr:created", days(-30));
        page(EN + "/surfing", "Surf Camp", "cq:template", TEMPLATE, "cq:tags", new String[] {"wknd:activity/surfing"},
                "jcr:description", "Waves in Bali", "cq:lastModified", days(-2));
        page(EN + "/magazine", "Magazine", "cq:template", "/conf/wknd/settings/wcm/templates/landing", "cq:lastModified", days(-40),
                "cq:lockOwner", "editor");
        page(SITE + "/us", "United States");
        page(SITE + "/us/en", "English", "jcr:mixinTypes", new String[] {"cq:LiveRelationship"}, "cq:lastModified", days(-3));
        context.create().resource(TEMPLATE, "jcr:primaryType", "cq:Template");
        context.create().resource(TEMPLATE + "/jcr:content", "jcr:primaryType", "cq:PageContent", "jcr:title", "Content Page",
                "status", "enabled");
    }

    private void page(String path, String title, Object... props) {
        context.create().resource(path, "jcr:primaryType", "cq:Page");
        Object[] all = new Object[props.length + 4];
        all[0] = "jcr:primaryType";
        all[1] = "cq:PageContent";
        all[2] = "jcr:title";
        all[3] = title;
        System.arraycopy(props, 0, all, 4, props.length);
        context.create().resource(path + "/jcr:content", all);
    }

    private static Calendar days(int n) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DATE, n);
        return c;
    }

    private static Calendar hoursBefore(Calendar c, int hours) {
        c.add(Calendar.HOUR_OF_DAY, -hours);
        return c;
    }

    private SiteRail rail(String user) {
        ResourceResolver as = new ResourceResolverWrapper(context.resourceResolver()) {
            @Override
            public String getUserID() {
                return user;
            }
        };
        return new SiteRail(as);
    }

    @Test
    void timelineListsTheLaunchesThatHoldThePage() throws Exception {
        String launch = SiteRail.LAUNCHES + "/2026/10/18/spring";
        context.create().resource(SiteRail.LAUNCHES, "jcr:primaryType", "sling:Folder");
        for (String f : new String[] {"/2026", "/2026/10", "/2026/10/18"}) {
            context.create().resource(SiteRail.LAUNCHES + f, "jcr:primaryType", "sling:Folder");
        }
        context.create().resource(launch, "jcr:primaryType", "cq:Page");
        context.create().resource(launch + "/jcr:content", "jcr:primaryType", "nt:unstructured", "sling:resourceType", SiteRail.LAUNCH_TYPE,
                "jcr:title", "Spring", "created", days(-3), "createdBy", "editor", "liveDate", days(7), "promoted", days(-1), "promotedBy", "admin");
        // the source page at its own path inside the launch, its ancestors as shells
        page(launch + "/content" + SITE, "WKND Site", "launchShell", true);
        page(launch + "/content" + EN, "WKND Adventures (launch)");
        // another launch that does not hold the page
        context.create().resource(SiteRail.LAUNCHES + "/2026/10/18/other", "jcr:primaryType", "cq:Page");
        context.create().resource(SiteRail.LAUNCHES + "/2026/10/18/other/jcr:content", "jcr:primaryType", "nt:unstructured",
                "sling:resourceType", SiteRail.LAUNCH_TYPE, "jcr:title", "Other", "created", days(-2));
        page(SiteRail.LAUNCHES + "/2026/10/18/other/content" + EN + "/surfing", "Surf (launch)");

        List<Map<String, Object>> launches = rail("editor").timeline(EN).stream().filter(e -> "launch".equals(e.get("kind")))
                .collect(Collectors.toList());
        assertEquals(List.of("Launch promoted: Spring", "Launch created: Spring"), launches.stream().map(e -> e.get("title")).collect(Collectors.toList()));
        assertEquals("admin", launches.get(0).get("user"));
        assertEquals("editor", launches.get(1).get("user"));
        assertEquals(launch + "/content" + EN, launches.get(1).get("pageInLaunch"));
        assertEquals(launch, launches.get(1).get("launch"));
        assertEquals(true, launches.get(1).get("promoted"));
        assertNotNull(launches.get(1).get("liveDate"));
        assertEquals(List.of("Launch created: Other"), rail("editor").timeline(EN + "/surfing").stream().filter(e -> "launch".equals(e.get("kind")))
                .map(e -> e.get("title")).collect(Collectors.toList()));
        assertTrue(rail("editor").timeline(SITE).stream().noneMatch(e -> "launch".equals(e.get("kind"))), "a shell is not the page");
    }

    @Test
    void timelineListsTheAnnotationsOnThePageComponents() throws Exception {
        String title = EN + "/jcr:content/root/container/title";
        context.create().resource(EN + "/jcr:content/root", "jcr:primaryType", "nt:unstructured");
        context.create().resource(EN + "/jcr:content/root/container", "jcr:primaryType", "nt:unstructured");
        context.create().resource(title, "jcr:primaryType", "nt:unstructured", "sling:resourceType", "wknd/components/title");
        context.create().resource(title + "/cq:annotations", "jcr:primaryType", "nt:unstructured");
        context.create().resource(title + "/cq:annotations/1_1", "jcr:primaryType", "nt:unstructured", "text", "Shorter title?",
                "color", "orange", "x", 60L, "y", 58L, "jcr:created", days(-2), "jcr:createdBy", "reviewer");
        // made by a plain Sling POST: only the last modification is known
        context.create().resource(title + "/cq:annotations/2_2", "jcr:primaryType", "nt:unstructured", "text", "Typo",
                "jcr:lastModified", hoursBefore(days(0), 1), "jcr:lastModifiedBy", "editor");
        // no time at all: left out
        context.create().resource(title + "/cq:annotations/3_3", "jcr:primaryType", "nt:unstructured", "text", "?");
        // on another page
        context.create().resource(EN + "/surfing/jcr:content/cq:annotations/1_1", "jcr:primaryType", "nt:unstructured", "text", "Not here",
                "jcr:created", days(-1));

        List<Map<String, Object>> notes = rail("editor").timeline(EN).stream().filter(e -> "annotation".equals(e.get("kind")))
                .collect(Collectors.toList());
        assertEquals(List.of("Typo", "Shorter title?"), notes.stream().map(e -> e.get("text")).collect(Collectors.toList()));
        assertEquals("editor", notes.get(0).get("user"));
        assertEquals("Annotation", notes.get(1).get("title"));
        assertEquals("reviewer", notes.get(1).get("user"));
        assertEquals("orange", notes.get(1).get("color"));
        assertEquals("root/container/title", notes.get(1).get("component"));
        assertEquals(title + "/cq:annotations/1_1", notes.get(1).get("id"));
        assertNull(notes.get(0).get("color"));
    }

    @Test
    void timelineListsActivitiesWorkflowsAndCommentsNewestFirst() throws Exception {
        String instance = SiteRail.INSTANCES + "/server0/2026-10-17/review_1";
        context.create().resource(instance, "jcr:primaryType", "cq:Workflow", "modelTitle", "Review", "initiator", "admin",
                "startTime", days(-5), "status", "COMPLETED", "endTime", days(-4));
        context.create().resource(instance + "/data", "payload", EN);
        context.create().resource(instance + "/metaData", "workflowTitle", "Check copy");
        context.create().resource(instance + "/workItems/approve", "type", "PARTICIPANT", "status", "COMPLETED", "nodeTitle",
                "Approve", "completedBy", "editor", "comment", "Looks good", "endTime", hoursBefore(days(-4), 1));
        context.create().resource(SiteRail.INSTANCES + "/server0/2026-10-17/other", "jcr:primaryType", "cq:Workflow",
                "startTime", days(-5));
        context.create().resource(SiteRail.INSTANCES + "/server0/2026-10-17/other/data", "payload", EN + "/surfing");

        SiteRail rail = rail("editor");
        rail.addComment(EN, "  Please review the hero  ");

        List<Map<String, Object>> events = rail.timeline(EN);
        List<String> titles = events.stream().map(e -> String.valueOf(e.get("title"))).collect(Collectors.toList());
        assertEquals(List.of("Comment", "Page modified", "Workflow completed: Check copy (Review)", "Step completed: Approve",
                "Workflow started: Check copy (Review)", "Page created"), titles.subList(0, 6));
        Map<String, Object> comment = events.get(0);
        assertEquals("Please review the hero", comment.get("text"));
        assertEquals(Boolean.TRUE, comment.get("mine"));
        assertEquals("editor", comment.get("user"));
        assertEquals("Looks good", events.get(3).get("text"));
        assertEquals("COMPLETED", events.get(4).get("status"));
        assertEquals(false, rail("someone").timeline(EN).get(0).get("mine"));
        assertNull(rail.lockOwner(EN));
        assertEquals("editor", rail.lockOwner(EN + "/magazine"));
    }

    @Test
    void commentsAreDeletedOnlyByTheirAuthorOrAdmin() throws Exception {
        String id = String.valueOf(rail("editor").addComment(EN, "first").get("id"));
        assertTrue(id.startsWith(SiteRail.COMMENTS + EN + "/"));
        assertThrows(SecurityException.class, () -> rail("someone").deleteComment(id));
        rail("admin").deleteComment(id);
        assertNull(context.resourceResolver().getResource(id));

        String mine = String.valueOf(rail("editor").addComment(EN, "second").get("id"));
        rail("editor").deleteComment(mine);
        assertNull(context.resourceResolver().getResource(mine));

        assertThrows(IllegalArgumentException.class, () -> rail("editor").addComment(EN, "   "));
        assertThrows(IllegalArgumentException.class, () -> rail("editor").addComment(SITE + "/missing", "x"));
        assertThrows(IllegalArgumentException.class, () -> rail("admin").deleteComment(EN));
    }

    @Test
    @SuppressWarnings("unchecked")
    void siteSummarisesLanguagesPagesAndTemplates() {
        Map<String, Object> site = rail("admin").site(EN + "/surfing");
        assertEquals(SITE, site.get("path"));
        assertEquals("WKND Site", site.get("title"));
        assertEquals("/conf/wknd", site.get("conf"));
        List<Map<String, Object>> languages = (List<Map<String, Object>>) site.get("languages");
        assertEquals(List.of(EN, SITE + "/us/en"), languages.stream().map(l -> l.get("path")).collect(Collectors.toList()));
        assertEquals(7, site.get("pages"));
        assertEquals(1, site.get("liveCopyPages"));
        assertEquals(1, site.get("lockedPages"));
        assertEquals(EN, ((Map<String, Object>) site.get("lastModified")).get("path"));
        List<Map<String, Object>> templates = (List<Map<String, Object>>) site.get("templates");
        assertEquals("Content Page", templates.get(0).get("title"));

        assertThrows(IllegalArgumentException.class, () -> rail("admin").site("/content/dam/wknd"));
        assertThrows(IllegalArgumentException.class, () -> rail("admin").site("/content/nothing/here"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchCombinesTheFilters() {
        SiteRail rail = rail("admin");
        SiteRail.Criteria c = new SiteRail.Criteria();
        c.root = SITE;
        c.text = "bali";
        assertEquals(List.of(EN + "/surfing"), paths(rail.search(c)));

        c = new SiteRail.Criteria();
        c.root = SITE;
        c.template = TEMPLATE;
        assertEquals(List.of(EN, EN + "/surfing"), paths(rail.search(c)));

        c.template = null;
        c.tags.add("wknd:activity/surfing");
        assertEquals(List.of(EN + "/surfing"), paths(rail.search(c)));

        c = new SiteRail.Criteria();
        c.root = SITE;
        c.modifiedDays = 7;
        assertEquals(List.of(EN, EN + "/surfing", SITE + "/us/en"), paths(rail.search(c)));

        c = new SiteRail.Criteria();
        c.root = SITE;
        c.liveCopy = "livecopy";
        assertEquals(List.of(SITE + "/us/en"), paths(rail.search(c)));
        c.liveCopy = "notlivecopy";
        assertFalse(paths(rail.search(c)).contains(SITE + "/us/en"));

        c = new SiteRail.Criteria();
        c.root = SITE;
        c.locked = true;
        Map<String, Object> locked = rail.search(c);
        assertEquals(List.of(EN + "/magazine"), paths(locked));
        Map<String, Object> hit = ((List<Map<String, Object>>) locked.get("items")).get(0);
        assertEquals("editor", hit.get("lockOwner"));
        assertEquals("page", hit.get("type"));

        c = new SiteRail.Criteria();
        c.root = EN;
        c.text = "camp surf";
        Map<String, Object> words = rail.search(c);
        assertEquals(1, words.get("total"));
        assertEquals(false, words.get("truncated"));
        assertEquals("Content Page", ((List<Map<String, Object>>) words.get("items")).get(0).get("templateTitle"));
    }

    @Test
    void templatesUsedBelowAPath() {
        List<Map<String, Object>> used = rail("admin").templatesUsed(SITE);
        assertEquals(List.of("Content Page", "landing"), used.stream().map(t -> t.get("title")).collect(Collectors.toList()));
    }

    @Test
    void servletReadsTheCriteria() {
        MockSlingHttpServletRequest request = context.request();
        request.setParameterMap(Map.of("path", EN, "q", " surf ", "tag", new String[] {"a:b", " ", "c:d"}, "modified", "30",
                "livecopy", "notlivecopy", "locked", "true", "template", TEMPLATE));
        SiteRail.Criteria c = SiteRailServlet.criteria(request);
        assertEquals(EN, c.root);
        assertEquals(" surf ", c.text);
        assertEquals(List.of("a:b", "c:d"), List.copyOf(c.tags));
        assertEquals(30, c.modifiedDays);
        assertEquals("notlivecopy", c.liveCopy);
        assertTrue(c.locked);

        request.setParameterMap(Map.of("modified", "soon"));
        assertThrows(IllegalArgumentException.class, () -> SiteRailServlet.criteria(request));
    }

    @Test
    void jsonLeavesOutNullsAndEscapesMarkup() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("a", "<b>\"x\"</b>");
        m.put("n", null);
        m.put("l", List.of(1, true));
        assertEquals("{\"a\":\"\\u003cb\\u003e\\\"x\\\"\\u003c/b\\u003e\",\"l\":[1,true]}", Json.write(m));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> paths(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("items")).stream().map(i -> i.get("path")).sorted(
                (a, b) -> String.valueOf(a).compareTo(String.valueOf(b))).collect(Collectors.toList());
    }
}
