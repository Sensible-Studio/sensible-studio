/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonAndXmpTest {

    @Test
    void parsesServiceResponses() {
        Map<String, Object> m = Json.parseObject(
                "{\"a\":1,\"b\":-2.5,\"c\":\"x\\\"y\\u00e9\",\"d\":[1,2],\"e\":{\"f\":null},\"g\":true}");
        assertEquals(1L, m.get("a"));
        assertEquals(-2.5, m.get("b"));
        assertEquals("x\"yé", m.get("c"));
        assertEquals(Arrays.asList(1L, 2L), m.get("d"));
        assertTrue(((Map<?, ?>) m.get("e")).containsKey("f"));
        assertEquals(Boolean.TRUE, m.get("g"));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }

    @Test
    void quotesStrings() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.quote("a\"b\\c\n\u0001"));
    }

    @Test
    void readsXmpLikeAem() throws Exception {
        Map<String, Object> m = XmpReader.read(xmpOf("/wknd/freeride.jpg"));
        // Same values AEM stored for this WKND asset.
        assertEquals("Freeride", m.get("dc:title"));
        assertEquals("Skier in deep powder, extreme mountain freeride", m.get("dc:description"));
        assertEquals("Adobe Photoshop CC 2015 (Macintosh)", m.get("xmp:CreatorTool"));
        assertEquals("C738604493C4D887C1BACCBF0D2F3018", m.get("xmpMM:DocumentID"));
        // dam: values embedded by a previous system are ignored: Sensible Studio computes its own.
        assertFalse(m.containsKey("dam:sha1"));
        // xmpMM:History is a list of structures: skipped.
        assertFalse(m.containsKey("xmpMM:History"));
    }

    @Test
    void readsBagsWithTheirArrayType() {
        String xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='" + XmpReader.RDF + "'>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/' xmlns:foo='urn:unknown'>"
                + "<dc:subject><rdf:Bag><rdf:li>surf</rdf:li><rdf:li>ocean</rdf:li></rdf:Bag></dc:subject>"
                + "<foo:secret>dropped</foo:secret>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>";
        Map<String, Object> m = XmpReader.read(xmp);
        assertEquals(List.of("surf", "ocean"), m.get("dc:subject"));
        assertEquals("rdf:Bag", m.get("dc:subject_xmpArrayType"));
        assertEquals(2, m.size(), "unknown namespaces are not written: " + m);
    }

    @Test
    void refusesDoctypesAndGarbage() {
        String xxe = "<?xml version='1.0'?><!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]>"
                + "<rdf:RDF xmlns:rdf='" + XmpReader.RDF + "'><rdf:Description xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:title>&e;</dc:title></rdf:Description></rdf:RDF>";
        assertTrue(XmpReader.read(xxe).isEmpty());
        assertTrue(XmpReader.read("not xml").isEmpty());
        assertTrue(XmpReader.read(null).isEmpty());
    }

    @Test
    void repoinitRegistersEveryNamespaceWeWrite() throws Exception {
        // A property in an unregistered namespace makes the whole commit fail in Oak.
        String repoinit = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../studio-compat-config/org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-dam.cfg.json")),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> ns : XmpReader.PREFIXES.entrySet()) {
            assertTrue(repoinit.contains("register namespace (" + ns.getValue() + ") " + ns.getKey()), ns.getValue());
        }
        assertTrue(repoinit.contains("register namespace (studio) "), "studio:blobId");
    }

    @Test
    void capsWhatAFileCanWriteThroughXmp() {
        StringBuilder x = new StringBuilder("<rdf:RDF xmlns:rdf='" + XmpReader.RDF + "'>"
                + "<rdf:Description xmlns:xmpMM='http://ns.adobe.com/xap/1.0/mm/' xmlns:dc='http://purl.org/dc/elements/1.1/'>");
        for (int i = 0; i < 1000; i++) {
            x.append("<xmpMM:p").append(i).append(">v</xmpMM:p").append(i).append('>');
        }
        x.append("<dc:title>").append("t".repeat(50_000)).append("</dc:title></rdf:Description></rdf:RDF>");
        Map<String, Object> m = XmpReader.read(x.toString());
        assertEquals(XmpReader.MAX_PROPERTIES, m.size());
        assertTrue(m.values().stream().allMatch(v -> !(v instanceof String) || ((String) v).length() <= XmpReader.MAX_VALUE_LENGTH));
    }

    static String xmpOf(String resource) throws Exception {
        try (InputStream in = JsonAndXmpTest.class.getResourceAsStream(resource)) {
            String s = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            int start = s.indexOf("<x:xmpmeta");
            int end = s.indexOf("</x:xmpmeta>") + "</x:xmpmeta>".length();
            byte[] raw = s.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
            return new String(raw, StandardCharsets.UTF_8);
        }
    }
}
