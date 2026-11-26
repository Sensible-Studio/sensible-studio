/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** How a submission's values become a record's typed element values, and how records are named. */
class RecordWriterTest {

    private static RecordWriter.Element el(String metaType, String valueType) {
        return new RecordWriter.Element("e", metaType, valueType);
    }

    @Test
    void textStaysText() {
        assertEquals("Hello", RecordWriter.convert(el("text-single", "string"), " Hello ", "textfield"));
        assertTrue(el("text-multi", "string").text());
        assertTrue(!el("number", "long").text());
        assertTrue(!el("reference", "string/reference").text());
    }

    @Test
    void numbersBooleansAndDates() {
        assertEquals(4L, RecordWriter.convert(el("number", "long"), "4", "number"));
        assertEquals(4L, RecordWriter.convert(el("number", "long"), "4.0", "number"));
        assertThrows(IllegalArgumentException.class, () -> RecordWriter.convert(el("number", "long"), "4.5", "number"));
        assertEquals(2.5, RecordWriter.convert(el("number", "double"), "2.5", "number"));
        assertEquals(Boolean.TRUE, RecordWriter.convert(el("boolean", "boolean"), "yes", "checkbox"));
        assertEquals(Boolean.FALSE, RecordWriter.convert(el("boolean", "boolean"), "off", "checkbox"));
        Calendar c = (Calendar) RecordWriter.convert(el("calendar", "calendar"), "2026-12-20", "date");
        assertEquals(2026, c.get(Calendar.YEAR));
        assertEquals(Calendar.DECEMBER, c.get(Calendar.MONTH));
        assertEquals(20, c.get(Calendar.DAY_OF_MONTH));
        assertThrows(IllegalArgumentException.class, () -> RecordWriter.convert(el("calendar", "calendar"), "soon", "date"));
    }

    @Test
    void severalValuesForAMultipleElement() {
        assertArrayEquals(new String[] {"Mon", "Fri"}, (String[]) RecordWriter.convert(el("enumeration", "string[]"), "Mon, Fri", "checkboxgroup"));
        assertArrayEquals(new String[] {"Mon, Fri"}, (String[]) RecordWriter.convert(el("text-single", "string[]"), "Mon, Fri", "textfield"),
                "only a checkbox group is split: a text with a comma is one value");
        assertNull(RecordWriter.convert(el("enumeration", "string[]"), " ", "checkboxgroup"));
    }

    @Test
    void titlesAndNames() {
        Calendar when = new GregorianCalendar(2026, Calendar.OCTOBER, 30, 14, 5);
        FormModel form = FormModelTestSupport.form("/content/forms/wknd/booking", "Book a trip");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", "Ana Pop");
        values.put("destination", "Bali");
        assertEquals("Book a trip 2026-10-30 14:05", RecordWriter.title(null, form, values, when));
        assertEquals("Ana Pop — Bali", RecordWriter.title("{name} — {destination}", form, values, when));
        assertEquals("Book a trip: Ana Pop (2026-10-30 14:05)", RecordWriter.title("{form}: {name} ({date})", form, values, when));
        assertEquals("Book a trip 2026-10-30 14:05", RecordWriter.title("{missing}", form, values, when), "nothing left: the default");
        assertEquals("ana-pop-bali", RecordWriter.slug("Ana Pop — Bali"));
        assertEquals("sectiune-sa-tie", RecordWriter.slug("Secțiune șă ție"));
        assertEquals("record", RecordWriter.slug("—"));
    }
}
