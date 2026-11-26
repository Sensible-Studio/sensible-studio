/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class SiteConfigServletTest {

    @Test
    void namesTheTypesTheFormKnows() {
        assertEquals("string", SiteConfigServlet.typeName(String.class));
        assertEquals("string", SiteConfigServlet.typeName(String[].class));
        assertEquals("boolean", SiteConfigServlet.typeName(boolean.class));
        assertEquals("integer", SiteConfigServlet.typeName(long[].class));
        assertEquals("number", SiteConfigServlet.typeName(Double.class));
    }

    @Test
    void convertsWhatWasPostedToTheTypeOfTheProperty() {
        assertEquals("a", SiteConfigServlet.convert(new String[] {"a"}, String.class));
        assertNull(SiteConfigServlet.convert(new String[] {""}, String.class));
        assertEquals(Boolean.TRUE, SiteConfigServlet.convert(new String[] {"true"}, boolean.class));
        assertEquals(42, SiteConfigServlet.convert(new String[] {" 42 "}, int.class));
        assertEquals(1.5d, SiteConfigServlet.convert(new String[] {"1.5"}, double.class));
        assertArrayEquals(new String[] {"a", "b"}, (String[]) SiteConfigServlet.convert(new String[] {"a", "", "b"}, String[].class));
        assertArrayEquals(new Integer[] {1, 2}, (Integer[]) SiteConfigServlet.convert(new String[] {"1", "2"}, int[].class));
        assertNull(SiteConfigServlet.convert(new String[] {""}, String[].class));
        assertThrows(NumberFormatException.class, () -> SiteConfigServlet.convert(new String[] {"x"}, int.class));
    }

    @Test
    void anArrayIsAListInJson() {
        assertEquals(Arrays.asList("a", "b"), SiteConfigServlet.json(new String[] {"a", "b"}));
        assertEquals(Arrays.asList(1, 2), SiteConfigServlet.json(new int[] {1, 2}));
        assertEquals(7L, SiteConfigServlet.json(7L));
        assertNull(SiteConfigServlet.json(null));
    }
}
