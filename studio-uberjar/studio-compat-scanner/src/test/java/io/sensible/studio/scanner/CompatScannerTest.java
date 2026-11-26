/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;

import org.junit.jupiter.api.Test;

class CompatScannerTest {

    @Test
    void parsesSharedClausesQuotesAndDirectives() {
        List<Header> h = Header.parse(
                "com.day.cq.wcm.api;com.day.cq.commons;version=\"[1.2,2)\",org.x;resolution:=optional;version=\"1.0\"");
        assertEquals(3, h.size());
        assertEquals("[1.2,2)", h.get(1).attr("version", null));
        assertEquals("optional", h.get(2).directive("resolution"));
    }

    @Test
    void versionRanges() {
        assertTrue(VersionRange.parse("[1.30,2)").includes("1.30.0"));
        assertFalse(VersionRange.parse("[1.30,2)").includes("2.0.0"));
        assertFalse(VersionRange.parse("(1.0,2]").includes("1.0.0"));
        assertTrue(VersionRange.parse("1.5").includes("9.0"));
    }

    @Test
    void reportClassifiesImports() {
        CompatScanner s = new CompatScanner();
        Attributes a = new Attributes();
        a.putValue("Bundle-SymbolicName", "com.adobe.aem.guides.wknd.core");
        a.putValue("Export-Package", "com.adobe.aem.guides.wknd.core.models;version=\"1.0.0\"");
        a.putValue("Import-Package", "com.day.cq.wcm.api;version=\"[1.27,2)\","
                + "com.adobe.cq.wcm.core.components.models;version=\"[12.0,13)\","
                + "com.adobe.aem.guides.wknd.core.models;version=\"[1.0,2)\","
                + "com.day.cq.dam.api;version=\"[1.0,2)\";resolution:=optional,"
                + "com.adobe.granite.ui.components;version=\"[1.0,2)\","
                + "org.apache.sling.api;version=\"[2.3,3)\"");
        s.addBundle("/x/wknd.core-3.0.jar", a);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int missing = s.report(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String out = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(2, missing, out); // wcm.api and granite.ui.components; dam is optional
        assertTrue(out.contains("deploy-oss-bundle"), out);
        assertTrue(out.contains("in-project"), out);
        assertTrue(out.contains("missing (optional)"), out);
        assertFalse(out.contains("org.apache.sling.api"), out);
    }
}
