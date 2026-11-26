/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** ContextHub's configuration in AEM's layout: the stores and the bar's fields, per configuration. */
@ExtendWith(SlingContextExtension.class)
class ContextHubConfigTest {

    private static final String THREE = "{\"name\":\"profile\"},{\"name\":\"geolocation\"},{\"name\":\"surferinfo\"}";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);
    private Session session;
    private ResourceResolver rr;

    @BeforeEach
    void setUp() throws Exception {
        rr = context.resourceResolver();
        session = rr.adaptTo(Session.class);
        try {
            session.getWorkspace().getNamespaceRegistry().registerNamespace("studio", "http://sensible.io/jcr/studio/1.0");
        } catch (javax.jcr.NamespaceException e) {
            // there already
        }
        Node en = session.getRootNode().addNode("content", "sling:Folder").addNode("site", "cq:Page").addNode("en", "cq:Page");
        en.addNode("jcr:content", "cq:PageContent").setProperty("cq:conf", "/conf/site");
        Node conf = session.getRootNode().addNode("conf", "sling:Folder");
        conf.addNode("site", "sling:Folder");
        conf.addNode("global", "sling:Folder");
        session.save();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    private static Map<String, Object> store(Map<String, Object> c, String name) {
        return list(c, "stores").stream().filter(s -> name.equals(s.get("name"))).findFirst().orElse(null);
    }

    @Test
    void theDefaultUntilAConfigurationHasItsOwn() throws Exception {
        Map<String, Object> d = ContextHubConfig.effective(rr, "/content/site/en");
        assertEquals("/conf/site", d.get("conf"));
        assertEquals(false, d.get("custom"));
        assertNull(d.get("from"));
        assertEquals(3, list(d, "stores").size());
        assertEquals("Age", list(d, "modules").get(1).get("title"));
        assertTrue(ContextHubConfig.properties(d).contains("profile/age"));

        // /conf/global's applies to a site without its own
        ContextHubConfig.save(session, "/conf/global", Json.parse("{\"stores\":[{\"name\":\"profile\"},{\"name\":\"geolocation\",\"enabled\":false},"
                + "{\"name\":\"surferinfo\"}],\"modules\":[{\"title\":\"City\",\"store\":\"geolocation\",\"property\":\"city\"}]}"));
        session.save();
        Node made = session.getNode("/conf/global" + ContextHubConfig.SUBPATH);
        assertEquals(ContextHubConfig.CONFIG_RT, made.getProperty("sling:resourceType").getString());
        assertEquals("contexthub.geolocation", made.getNode("geolocation").getProperty("storeType").getString());
        Map<String, Object> g = ContextHubConfig.effective(rr, "/content/site/en");
        assertEquals("/conf/global" + ContextHubConfig.SUBPATH, g.get("from"));
        assertEquals(false, g.get("custom"));
        assertEquals(false, store(g, "geolocation").get("enabled"));
        assertEquals(1, list(g, "modules").size());

        // made here: Reset removes it all
        ContextHubConfig.reset(session, "/conf/global");
        session.save();
        assertFalse(session.nodeExists("/conf/global" + ContextHubConfig.SUBPATH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsStoresOfItsOwnAndTheBarsFields() throws Exception {
        String json = "{\"stores\":["
                + "{\"name\":\"profile\",\"initial\":{\"loyalty\":\"member\"}},{\"name\":\"geolocation\"},"
                + "{\"name\":\"surferinfo\",\"enabled\":false},"
                + "{\"name\":\"campaign\",\"title\":\"Campaign\",\"type\":\"generic\",\"initial\":{\"source\":\"newsletter\",\"wave\":2}},"
                + "{\"name\":\"weather\",\"title\":\"Weather\",\"type\":\"json\",\"url\":\"https://api.example.com/weather?city=Cluj\",\"refresh\":30,\"select\":\"current\"}],"
                + "\"modules\":["
                + "{\"title\":\"Source\",\"store\":\"campaign\",\"property\":\"source\",\"type\":\"select\",\"options\":[\"newsletter\",\"ads\",\"newsletter\"]},"
                + "{\"title\":\"Temperature\",\"store\":\"weather\",\"property\":\"temperature\",\"type\":\"number\"}]}";
        ContextHubConfig.save(session, "/conf/site", Json.parse(json));
        session.save();
        Node root = session.getNode("/conf/site" + ContextHubConfig.SUBPATH);
        assertEquals("contexthub.generic-jsonp", root.getNode("weather").getProperty("storeType").getString());
        assertEquals("studio.field", root.getNode("studio-bar/field0").getProperty("moduleType").getString());

        Map<String, Object> c = ContextHubConfig.effective(rr, "/content/site/en");
        assertEquals(true, c.get("custom"));
        assertEquals(true, c.get("madeHere"));
        assertEquals(5, list(c, "stores").size());
        assertEquals("member", ((Map<String, Object>) store(c, "profile").get("initial")).get("loyalty"));
        assertEquals(false, store(c, "surferinfo").get("enabled"));
        Map<String, Object> weather = store(c, "weather");
        assertEquals("json", weather.get("type"));
        assertEquals(30L, weather.get("refresh"));
        assertEquals("current", weather.get("select"));
        List<Map<String, Object>> modules = list(c, "modules");
        assertEquals(2, modules.size());
        assertEquals(List.of("newsletter", "ads"), modules.get(0).get("options"));
        assertTrue(ContextHubConfig.properties(c).contains("campaign/wave"));

        Map<String, Object> client = ContextHubConfig.forClient(c);
        assertFalse(list(client, "stores").get(1).containsKey("initial"));
        assertEquals("https://api.example.com/weather?city=Cluj", store(client, "weather").get("url"));

        // a store taken off the list goes
        ContextHubConfig.save(session, "/conf/site", Json.parse("{\"stores\":[" + THREE + "],\"modules\":[]}"));
        session.save();
        assertFalse(root.hasNode("weather"));
        assertEquals(0, list(ContextHubConfig.effective(rr, "/conf/site"), "modules").size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsAndUndoesNothingOfAnAemConfiguration() throws Exception {
        // a site's ContextHub as AEM keeps it (WKND's): stores, a store Sensible Studio does not run, a UI mode
        Node root = session.getNode("/conf/site").addNode("settings", "sling:Folder").addNode("cloudsettings", "sling:Folder")
                .addNode("default", "sling:Folder").addNode("contexthub", "sling:OrderedFolder");
        root.setProperty("sling:resourceType", ContextHubConfig.CONFIG_RT);
        for (String[] s : new String[][] {{"profile", "granite.profile"}, {"geolocation", "contexthub.geolocation"},
                {"surferinfo", "contexthub.surferinfo"}, {"segmentation", "aem.segmentation"}}) {
            Node n = root.addNode(s[0], "nt:unstructured");
            n.setProperty("sling:resourceType", ContextHubConfig.STORE_RT);
            n.setProperty("storeType", s[1]);
            n.setProperty("enabled", true);
        }
        root.getNode("geolocation").setProperty("googleClientId", "gme-x");
        root.getNode("geolocation").setProperty("configjson", "{\r\n    \"geocoder\": {\r\n        \"enabled\": true\r\n    }\r\n}");
        root.getNode("surferinfo").setProperty("configjson", "{ \"keep\": \"as written\" }");
        Node persona = root.addNode("persona", "sling:OrderedFolder");
        persona.setProperty("sling:resourceType", ContextHubConfig.MODE_RT);
        persona.setProperty("jcr:title", "Persona");
        persona.addNode("profile", "nt:unstructured").setProperty("moduleType", "granite.profile");
        Node resolution = persona.addNode("resolution", "nt:unstructured");
        resolution.setProperty("moduleType", "contexthub.base");
        resolution.setProperty("jcr:title", "Screen Resolution");
        resolution.setProperty("configjson", "{\"title\":\"Resolution\",\"storeMapping\":{\"emulator\":\"emulators\"},"
                + "\"template\":\"<p>{{{emulator.currentDevice.width}}}</p>\"}");
        session.save();

        Map<String, Object> c = ContextHubConfig.effective(rr, "/content/site/en");
        assertEquals(true, c.get("custom"));
        assertEquals(false, c.get("madeHere"));
        assertEquals("other", store(c, "segmentation").get("type"));
        assertEquals("Persona", list(c, "modes").get(0).get("title"));
        // what the bar in the page shows of AEM's modules: their type, title, stores and template
        List<Map<String, Object>> items = (List<Map<String, Object>>) list(c, "modes").get(0).get("items");
        assertEquals("granite.profile", items.get(0).get("type"));
        assertEquals("Resolution", items.get(1).get("title"));
        assertEquals("emulators", ((Map<?, ?>) items.get(1).get("storeMapping")).get("emulator"));
        assertEquals("<p>{{{emulator.currentDevice.width}}}</p>", items.get(1).get("template"));
        assertEquals(9, list(c, "modules").size(), "the usual fields of its stores");
        assertFalse(store(ContextHubConfig.forClient(c), "geolocation") == null);
        assertNull(store(ContextHubConfig.forClient(c), "segmentation"), "the browser runs only the stores it knows");

        ContextHubConfig.save(session, "/conf/site", Json.parse("{\"stores\":[{\"name\":\"profile\"},"
                + "{\"name\":\"geolocation\",\"initial\":{\"country\":\"RO\"}},{\"name\":\"surferinfo\"},"
                + "{\"name\":\"segmentation\",\"type\":\"other\",\"enabled\":false},"
                + "{\"name\":\"campaign\",\"type\":\"generic\"}],"
                + "\"modules\":[{\"title\":\"Country\",\"store\":\"geolocation\",\"property\":\"country\"}]}"));
        session.save();
        assertEquals("gme-x", root.getNode("geolocation").getProperty("googleClientId").getString());
        assertTrue(root.getNode("geolocation").getProperty("configjson").getString().contains("\"geocoder\""));
        assertTrue(root.getNode("geolocation").getProperty("configjson").getString().contains("\"RO\""));
        assertFalse(root.getNode("segmentation").getProperty("enabled").getBoolean());
        assertTrue(root.hasNode("persona/profile"));
        assertTrue(root.hasNode("studio-bar"));
        assertEquals("{ \"keep\": \"as written\" }", root.getNode("surferinfo").getProperty("configjson").getString(), "untouched when unchanged");

        ContextHubConfig.reset(session, "/conf/site");
        session.save();
        assertTrue(session.nodeExists("/conf/site" + ContextHubConfig.SUBPATH), "an AEM configuration stays");
        assertFalse(root.hasNode("campaign"));
        assertFalse(root.hasNode("studio-bar"));
        assertTrue(root.getNode("segmentation").getProperty("enabled").getBoolean(), "on again, as it was");
        assertEquals("{\r\n    \"geocoder\": {\r\n        \"enabled\": true\r\n    }\r\n}", root.getNode("geolocation").getProperty("configjson").getString(),
                "as AEM wrote it");
        assertFalse(root.getNode("geolocation").hasProperty(ContextHubConfig.CONFIG_WAS));
        assertEquals("gme-x", root.getNode("geolocation").getProperty("googleClientId").getString());
        assertTrue(root.hasNode("persona"));
    }

    @Test
    void refusesWhatTheClientCouldNotUse() {
        assertThrows(IllegalArgumentException.class, () -> ContextHubConfig.save(session, "/conf/site/x", Json.parse("{}")));
        assertThrows(IllegalArgumentException.class, () -> ContextHubConfig.save(session, "/conf/nope", Json.parse("{}")));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[{\"name\":\"Bad Name\",\"type\":\"generic\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[{\"name\":\"x\",\"type\":\"profile\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[{\"name\":\"w\",\"type\":\"json\",\"url\":\"javascript:alert(1)\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[{\"name\":\"w\",\"type\":\"generic\",\"initial\":[1]}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[" + THREE + "],\"modules\":[{\"title\":\"X\",\"store\":\"nowhere\",\"property\":\"a\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[" + THREE + "],\"modules\":[{\"title\":\"X\",\"store\":\"profile\",\"property\":\"a\",\"type\":\"select\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> save("{\"stores\":[" + THREE + "],\"modules\":[{\"title\":\"X\",\"store\":\"profile\",\"property\":\"a b\"}]}"));
        assertFalse(sessionHas("/conf/site" + ContextHubConfig.SUBPATH), "nothing written when something is wrong");
    }

    private boolean sessionHas(String path) {
        try {
            return session.nodeExists(path);
        } catch (javax.jcr.RepositoryException e) {
            return false;
        }
    }

    private void save(String json) throws Exception {
        ContextHubConfig.save(session, "/conf/site", Json.parse(json));
    }
}
