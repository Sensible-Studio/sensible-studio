package io.sensible.studio.translation.impl;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Translation projects: items, jobs per language and their lifecycle. */
@ExtendWith(SlingContextExtension.class)
class TranslationProjectsTest {

    private static final String EN = "/content/site/language-masters/en";
    private static final String FR = "/content/site/language-masters/fr";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private PageTranslationTest.Marking translator;
    private TranslationProjects projects;

    @BeforeEach
    void setUp() {
        context.load().json("/page-site.json", "/content/site");
        context.create().resource("/var/studio", "jcr:primaryType", "sling:Folder");
        translator = new PageTranslationTest.Marking();
        projects = new TranslationProjects(context.resourceResolver(), null, translator, new PageTranslationRules(), Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    private static Map<String, Object> job(Map<String, Object> project, String language) {
        return listOf(project, "jobs").stream().filter(j -> language.equals(j.get("language"))).findFirst().orElseThrow(AssertionError::new);
    }

    private Map<String, Object> runJob(String name, String language) throws Exception {
        Map<String, Object> p = projects.get(name);
        for (Map<String, Object> item : listOf(p, "items")) projects.translateItem(name, language, String.valueOf(item.get("id")), false);
        return projects.get(name);
    }

    @Test
    void aProjectTranslatesItsPagesPerLanguageAndGoesThroughReview() throws Exception {
        Map<String, Object> p = projects.create("Spring site", "The spring update", Collections.singletonList(EN + "/about"), true, true,
                Arrays.asList("fr", "de"), "studio-pseudo", null);
        assertEquals("spring-site", p.get("name"));
        assertEquals("en", p.get("sourceLanguage"));
        assertEquals(Arrays.asList(EN + "/about", EN + "/about/team"), listOf(p, "items").stream().map(i -> i.get("path")).collect(Collectors.toList()));
        assertEquals("DRAFT", job(p, "fr").get("status"));
        assertEquals("DRAFT", job(p, "de").get("status"));

        // the first item: in progress; then all: ready for review, with the copies and their review state
        Map<String, Object> first = projects.translateItem("spring-site", "fr", "item_0", false);
        assertEquals("TRANSLATED", first.get("status"));
        assertEquals(FR + "/about", first.get("copyPath"));
        assertEquals("IN_PROGRESS", job(projects.get("spring-site"), "fr").get("status"));
        p = runJob("spring-site", "fr");
        Map<String, Object> fr = job(p, "fr");
        assertEquals("READY_FOR_REVIEW", fr.get("status"));
        assertEquals(2, fr.get("done"));
        assertEquals(Arrays.asList(FR + "/about", FR + "/about/team"), listOf(fr, "copies").stream().map(c -> c.get("path")).collect(Collectors.toList()));
        assertEquals(Collections.singletonMap("review", 2), fr.get("review"));
        assertEquals("[fr] About us", context.resourceResolver().getResource(FR + "/about/jcr:content").getValueMap().get("jcr:title"));
        assertEquals("DRAFT", job(p, "de").get("status"));

        // approving the job approves its copies (into the memory); then it is completed and closed
        p = projects.setStatus("spring-site", "fr", "APPROVED");
        assertEquals("APPROVED", job(p, "fr").get("status"));
        assertEquals(Collections.singletonMap("approved", 2), job(p, "fr").get("review"));
        assertFalse(translator.remembered.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> projects.setStatus("spring-site", "de", "COMPLETE"));
        p = projects.setStatus("spring-site", "fr", "COMPLETE");
        assertEquals("COMPLETE", job(p, "fr").get("status"));
        assertThrows(IllegalArgumentException.class, () -> projects.translateItem("spring-site", "fr", "item_0", false));

        // an update runs the job again: only changed texts go to the connector
        projects.setStatus("spring-site", "de", "CANCELLED");
        assertThrows(IllegalArgumentException.class, () -> projects.setStatus("spring-site", "de", "APPROVED"));
        p = projects.setStatus("spring-site", "de", "DRAFT");
        int before = translator.requests.size();
        p = runJob("spring-site", "de");
        assertEquals("READY_FOR_REVIEW", job(p, "de").get("status"));
        assertTrue(translator.requests.size() > before);
    }

    @Test
    void itemsLanguagesAndMistakes() throws Exception {
        projects.create("Site", null, Collections.singletonList(EN + "/about"), false, false, Collections.singletonList("fr"), null, null);
        Map<String, Object> p = projects.create("Site", null, Collections.singletonList(EN + "/about"), false, false, Collections.singletonList("fr"), null, null);
        assertEquals("site-1", p.get("name"));
        assertEquals(2, projects.list().size());

        // more items; a job already translated goes back to work
        runJob("site", "fr");
        assertEquals("READY_FOR_REVIEW", job(projects.get("site"), "fr").get("status"));
        p = projects.addItems("site", Collections.singletonList(EN + "/contact"), false);
        assertEquals(2, ((Number) p.get("itemCount")).intValue());
        assertEquals("IN_PROGRESS", job(p, "fr").get("status"));
        assertThrows(IllegalArgumentException.class, () -> projects.addItems("site", Collections.singletonList(EN + "/contact"), false));
        // the French contact page was made by people: it is kept, the item is translated (nothing sent)
        Map<String, Object> contact = projects.translateItem("site", "fr", "item_1", false);
        assertEquals("TRANSLATED", contact.get("status"));
        assertEquals("READY_FOR_REVIEW", job(projects.get("site"), "fr").get("status"));
        p = projects.removeItem("site", "item_1");
        assertEquals(1, ((Number) p.get("itemCount")).intValue());

        p = projects.addLanguage("site", "de");
        assertEquals(Arrays.asList("fr", "de"), p.get("languages"));
        assertThrows(IllegalArgumentException.class, () -> projects.addLanguage("site", "fr"));
        assertThrows(IllegalArgumentException.class, () -> projects.addLanguage("site", "en"));

        // one source language per project; real languages; something to translate
        assertThrows(IllegalArgumentException.class, () -> projects.addItems("site", Collections.singletonList(FR + "/contact"), false));
        assertThrows(IllegalArgumentException.class, () -> projects.create("X", null, Collections.singletonList(EN), false, false, Collections.singletonList("xx"), null, null));
        assertThrows(IllegalArgumentException.class, () -> projects.create("X", null, Collections.emptyList(), false, false, Collections.singletonList("fr"), null, null));
        assertThrows(IllegalArgumentException.class, () -> projects.create(" ", null, Collections.singletonList(EN), false, false, Collections.singletonList("fr"), null, null));

        // a missing source page fails its item, the job goes on
        projects.create("Gone", null, Collections.singletonList(EN + "/about/team"), false, false, Collections.singletonList("fr"), null, null);
        Resource team = context.resourceResolver().getResource(EN + "/about/team");
        context.resourceResolver().delete(team);
        context.resourceResolver().commit();
        Map<String, Object> failed = projects.translateItem("gone", "fr", "item_0", false);
        assertEquals("FAILED", failed.get("status"));
        assertNotNull(failed.get("error"));
        assertEquals("READY_FOR_REVIEW", job(projects.get("gone"), "fr").get("status"));
        assertEquals(1, job(projects.get("gone"), "fr").get("failedItems"));

        projects.delete("site-1");
        assertEquals(2, projects.list().size());
        ValueMap stored = context.resourceResolver().getResource(TranslationProjects.ROOT + "/site").getValueMap();
        assertEquals("en", stored.get("sourceLanguage"));
    }

    @Test
    void aJobRunsOnTheServerOnBehalfOfItsStarter() throws Exception {
        projects.create("Server", null, Collections.singletonList(EN + "/about"), true, false, Arrays.asList("fr", "de"), null, null);
        TranslationProjects worker = new TranslationProjects(context.resourceResolver(), null, translator, new PageTranslationRules(), Collections.emptyList()).as("anna");
        Map<String, Object> p = projects.queueBackground("server", "fr");
        assertEquals("QUEUED", job(p, "fr").get("background"));
        assertThrows(IllegalArgumentException.class, () -> projects.queueBackground("server", "fr"));
        worker.runBackground("server", "fr");
        p = projects.get("server");
        assertEquals("READY_FOR_REVIEW", job(p, "fr").get("status"));
        assertEquals(null, job(p, "fr").get("background"));
        assertEquals("anna", job(p, "fr").get("startedBy"));
        assertEquals("anna", context.resourceResolver().getResource(PageTranslation.RECORDS + FR + "/about").getValueMap().get("translatedBy"));
        // a finished job does not run again; a queued one can be stopped before it starts
        assertThrows(IllegalArgumentException.class, () -> projects.queueBackground("server", "fr"));
        projects.queueBackground("server", "de");
        p = projects.stopBackground("server", "de");
        assertEquals(null, job(p, "de").get("background"));
        worker.runBackground("server", "de");
        assertEquals("DRAFT", job(projects.get("server"), "de").get("status"));
    }

    @Test
    void xliffGoesOutToTranslatorsAndComesBackChecked() throws Exception {
        projects.create("Agency", null, Collections.singletonList(EN + "/about"), true, false, Collections.singletonList("fr"), null, null);
        Map<String, Object> job = job(runJob("agency", "fr"), "fr");
        assertTrue(((Number) job.get("textsTranslated")).longValue() > 0);
        assertTrue(((Number) job.get("characters")).longValue() > 0);

        String xliff = projects.exportXliff("agency", "fr");
        assertTrue(xliff.contains("<file original=\"" + FR + "/about\" source-language=\"en\" target-language=\"fr\""), xliff);
        assertTrue(xliff.contains("<trans-unit id=\"@jcr:title\""), xliff);
        assertTrue(xliff.contains("<source>About us</source>"), xliff);
        assertTrue(xliff.contains("<target state=\"translated\">[fr] About us</target>"), xliff);

        // the agency's version: a better title, an HTML text with its markup broken, a copy not in the job
        String back = xliff.replace("<target state=\"translated\">[fr] About us</target>", "<target state=\"final\">À propos</target>");
        java.util.regex.Matcher html = java.util.regex.Pattern.compile("<target state=\"translated\">(&lt;p&gt;[^<]*)</target>").matcher(back);
        boolean hasHtml = html.find();
        if (hasHtml) back = back.substring(0, html.start(1)) + "plain text, the paragraph gone" + back.substring(html.end(1));
        back = back.replace("</xliff>", "<file original=\"/content/site/language-masters/fr/contact\" target-language=\"fr\"><body><trans-unit id=\"@jcr:title\"><source>x</source><target>y</target></trans-unit></body></file></xliff>");
        Map<String, Object> result = projects.importXliff("agency", "fr", new java.io.ByteArrayInputStream(back.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, result.get("saved"), String.valueOf(result));
        assertEquals("À propos", context.resourceResolver().getResource(FR + "/about/jcr:content").getValueMap().get("jcr:title"));
        List<Map<String, Object>> errors = listOf(result, "errors");
        assertTrue(errors.stream().anyMatch(e -> String.valueOf(e.get("message")).contains("Not a copy of this job")), String.valueOf(errors));
        if (hasHtml) assertTrue(errors.stream().anyMatch(e -> e.get("id") != null), String.valueOf(errors));
        assertEquals(1L, ((Number) job(projects.get("agency"), "fr").get("textsImported")).longValue());

        // line breaks survive the trip (CR LF is not turned into LF)
        java.util.Map<String, Object> crlf = new java.util.LinkedHashMap<>();
        crlf.put("path", "/x");
        crlf.put("title", "X");
        crlf.put("segments", Collections.singletonList(new java.util.HashMap<String, Object>() {{
            put("id", "a");
            put("label", "A");
            put("source", "one\r\ntwo");
            put("translation", "un\r\ndeux");
        }}));
        String written = Xliff.write("P", "en", "fr", Collections.singletonList(crlf));
        assertEquals("un\r\ndeux", Xliff.read(new java.io.ByteArrayInputStream(written.getBytes(java.nio.charset.StandardCharsets.UTF_8))).targets.get("/x").get("a"));

        // a file for another language, or with a DTD, is refused
        String de = xliff.replace("target-language=\"fr\"", "target-language=\"de\"");
        assertThrows(IllegalArgumentException.class, () -> projects.importXliff("agency", "fr", new java.io.ByteArrayInputStream(de.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><xliff version=\"1.2\"><file original=\"" + FR + "/about\"><body><trans-unit id=\"@jcr:title\"><target>&e;</target></trans-unit></body></file></xliff>";
        assertThrows(IllegalArgumentException.class, () -> projects.importXliff("agency", "fr", new java.io.ByteArrayInputStream(xxe.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void dueDatesAndLateProjects() throws Exception {
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DATE, -1);
        Map<String, Object> p = projects.create("Late", null, Collections.singletonList(EN + "/about"), false, false, Collections.singletonList("fr"), null, yesterday);
        assertEquals(true, p.get("overdue"));
        java.util.Calendar nextWeek = java.util.Calendar.getInstance();
        nextWeek.add(java.util.Calendar.DATE, 7);
        p = projects.setDueDate("late", nextWeek);
        assertEquals(false, p.get("overdue"));
        assertEquals(nextWeek.getTimeInMillis(), ((Number) p.get("dueDate")).longValue());
        p = projects.setDueDate("late", yesterday);
        assertEquals(true, p.get("overdue"));
        // a project whose jobs are all closed is not late
        projects.setStatus("late", "fr", "CANCELLED");
        assertEquals(false, projects.get("late").get("overdue"));
        p = projects.setDueDate("late", null);
        assertNull(p.get("dueDate"));
    }

    @Test
    void xliff2GoesOutAndComesBackToo() throws Exception {
        projects.create("Agency2", null, Collections.singletonList(EN + "/about"), false, false, Collections.singletonList("fr"), null, null);
        runJob("agency2", "fr");
        String x = projects.exportXliff("agency2", "fr", "2.0");
        assertTrue(x.contains("xmlns=\"urn:oasis:names:tc:xliff:document:2.0\" version=\"2.0\" srcLang=\"en\" trgLang=\"fr\""), x);
        assertTrue(x.contains("<file id=\"f1\" original=\"" + FR + "/about\">"), x);
        assertTrue(x.contains("name=\"@jcr:title\""), x);
        assertTrue(x.contains("<target xml:space=\"preserve\">[fr] About us</target>"), x);
        assertThrows(IllegalArgumentException.class, () -> projects.exportXliff("agency2", "fr", "3.0"));
        // a tool split the title into two segments: joined again
        String back = x.replace("<segment state=\"translated\">\n        <source xml:space=\"preserve\">About us</source>\n        <target xml:space=\"preserve\">[fr] About us</target>\n      </segment>",
                "<segment state=\"final\"><source>About </source><target>À </target></segment><segment state=\"final\"><source>us</source><target>propos</target></segment>");
        assertTrue(back.contains("propos"), "the replacement applied");
        Map<String, Object> r = projects.importXliff("agency2", "fr", new java.io.ByteArrayInputStream(back.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, r.get("saved"), String.valueOf(r));
        assertEquals("À propos", context.resourceResolver().getResource(FR + "/about/jcr:content").getValueMap().get("jcr:title"));
        // the round trip of an unchanged 2.0 file saves nothing
        Map<String, Object> again = projects.importXliff("agency2", "fr",
                new java.io.ByteArrayInputStream(projects.exportXliff("agency2", "fr", "2.0").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(0, again.get("saved"), String.valueOf(again));
    }
}
