package io.sensible.studio.compat.grid;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GridClassResolverTest {

    @Test
    public void testResolveLegacyAemGridClasses() {
        GridClassResolver resolver = new GridClassResolver();

        // Simulate JCR cq:responsive structure:
        // default: width=6, offset=0
        // phone: width=12, behavior="newline"
        Map<String, Map<String, Object>> responsiveData = new LinkedHashMap<>();

        Map<String, Object> defaultBp = new HashMap<>();
        defaultBp.put("width", 6);
        defaultBp.put("offset", 0);
        responsiveData.put("default", defaultBp);

        Map<String, Object> phoneBp = new HashMap<>();
        phoneBp.put("width", 12);
        phoneBp.put("behavior", "newline");
        responsiveData.put("phone", phoneBp);

        String cssClasses = resolver.resolveColumnClasses(responsiveData);

        // Expect: aem-GridColumn aem-GridColumn--default--6 aem-GridColumn--phone--12 aem-GridColumn--phone--newline
        assertTrue(cssClasses.contains("aem-GridColumn"));
        assertTrue(cssClasses.contains("aem-GridColumn--default--6"));
        assertTrue(cssClasses.contains("aem-GridColumn--phone--12"));
        assertTrue(cssClasses.contains("aem-GridColumn--phone--newline"));

        System.out.println("Resolved Grid Classes: " + cssClasses);
    }

    @Test
    public void testCustomPrefix() {
        GridClassResolver resolver = new GridClassResolver("sxm-Grid", 12);
        Map<String, Map<String, Object>> responsiveData = new LinkedHashMap<>();
        Map<String, Object> defaultBp = new HashMap<>();
        defaultBp.put("width", 4);
        defaultBp.put("offset", 2);
        responsiveData.put("default", defaultBp);

        String cssClasses = resolver.resolveColumnClasses(responsiveData);
        assertEquals("sxm-GridColumn sxm-GridColumn--default--4 sxm-GridColumn--offset--default--2", cssClasses);
    }
}
