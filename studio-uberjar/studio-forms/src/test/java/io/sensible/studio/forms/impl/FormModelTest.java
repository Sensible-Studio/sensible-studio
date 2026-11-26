/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

/** The pieces of FormModel that need no repository: rules, verdicts, the checks of one value or file. */
class FormModelTest {

    private static ValueMap vm(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new ValueMapDecorator(m);
    }

    private static FormModel.Rule rule(String action, String field, String op, String value) {
        return new FormModel.Rule(vm("action", action, "field", field, "op", op, "value", value));
    }

    private static FormModel.Field field(String name, String type, List<FormModel.Rule> rules, Object... props) {
        return new FormModel.Field(name, type, vm(props), rules);
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void rulesHoldLikeTheBrowser() {
        assertTrue(rule("show", "topic", "is", "A trip").holds(values("topic", "A trip")));
        assertTrue(rule("show", "topic", "is", "A trip").holds(values("topic", " A trip ")));
        assertFalse(rule("show", "topic", "is", "A trip").holds(values()));
        assertTrue(rule("show", "topic", "isnot", "A trip").holds(values()));
        assertTrue(rule("show", "msg", "contains", "HELP").holds(values("msg", "please help me")));
        assertTrue(rule("show", "n", "empty", "").holds(values("n", "  ")));
        assertTrue(rule("show", "n", "notempty", "").holds(values("n", "x")));
        assertTrue(rule("show", "n", "gt", "3").holds(values("n", "4")));
        assertFalse(rule("show", "n", "gt", "3").holds(values("n", "")));
        assertFalse(rule("show", "n", "gt", "3").holds(values("n", "abc")));
        assertTrue(rule("show", "n", "lt", "3").holds(values("n", "2.5")));
        assertFalse(rule("show", "n", "weird", "3").holds(values("n", "2")));
    }

    @Test
    void showNeedsEveryRuleHideAnyRequireAllDisableAny() {
        FormModel.Field f = field("travellers", "number", Arrays.asList(
                rule("show", "topic", "is", "A trip"),
                rule("show", "country", "notempty", ""),
                rule("require", "topic", "is", "A trip"),
                rule("disable", "locked", "is", "yes")));
        FormModel.Verdict v = FormModel.verdict(f, values("topic", "A trip", "country", "FR"));
        assertTrue(v.visible);
        assertEquals(Boolean.TRUE, v.required);
        assertFalse(v.disabled);

        v = FormModel.verdict(f, values("topic", "A trip"));
        assertFalse(v.visible, "one show rule fails: hidden");

        v = FormModel.verdict(f, values("topic", "The magazine", "country", "FR"));
        assertFalse(v.visible);
        assertEquals(Boolean.FALSE, v.required);

        v = FormModel.verdict(f, values("topic", "A trip", "country", "FR", "locked", "yes"));
        assertTrue(v.disabled);

        FormModel.Field hidden = field("x", "textfield", Arrays.asList(rule("hide", "a", "is", "1"), rule("hide", "b", "is", "1")));
        assertFalse(FormModel.verdict(hidden, values("b", "1")).visible, "any hide rule hides");
        assertTrue(FormModel.verdict(hidden, values()).visible);
        assertNull(FormModel.verdict(hidden, values()).required, "no require rule: the field's own setting counts");
    }

    @Test
    void datesAreCheckedForShapeAndRange() {
        FormModel.Field when = field("when", "date", Collections.emptyList(), "minDate", "2026-11-01", "maxDate", "2026-12-31");
        assertEquals("Enter a date.", check(when, "next week"));
        assertEquals("Enter a date from 2026-11-01 on.", check(when, "2026-10-01"));
        assertEquals("Enter a date up to 2026-12-31.", check(when, "2027-01-01"));
        assertNull(check(when, "2026-12-20"));
    }

    @Test
    void filesAreCheckedForSizeAndKind() {
        FormModel.Field f = field("scan", "fileupload", Collections.emptyList(), "accept", ".pdf, image/*", "maxSize", 2L);
        assertNull(FormModel.checkFile(f, "passport.PDF", "application/octet-stream", 1000));
        assertNull(FormModel.checkFile(f, "me.jpg", "image/jpeg", 1000));
        assertEquals("This kind of file is not accepted (.pdf, image/*).", FormModel.checkFile(f, "virus.exe", "application/x-msdownload", 10));
        assertEquals("The file is larger than 2 MB.", FormModel.checkFile(f, "big.pdf", "application/pdf", 3L * 1024 * 1024));
        FormModel.Field any = field("any", "fileupload", Collections.emptyList());
        assertNull(FormModel.checkFile(any, "whatever.bin", null, 10));
        assertEquals(10L, any.maxSizeMb);
    }

    @Test
    void choicesMustBeAmongTheOptions() {
        FormModel.Field one = field("topic", "dropdown", Collections.emptyList(), "options", new String[] {"A", "B"});
        assertNull(check(one, "A"));
        assertEquals("Choose one of the options.", check(one, "C"));
        FormModel.Field many = field("days", "checkboxgroup", Collections.emptyList(), "options", new String[] {"Mon", "Tue"});
        assertNull(check(many, "Mon, Tue"));
        assertEquals("Choose from the options.", check(many, "Mon, Sun"));
    }

    @Test
    void uploadedNamesAreMadeSafe() {
        assertEquals("passport.png", SubmissionStore.safeName("C:\\Users\\me\\passport.png"));
        assertEquals("a_b__.txt", SubmissionStore.safeName("../a[b]*.txt"));
        assertEquals("file", SubmissionStore.safeName("..."));
        assertEquals("file", SubmissionStore.safeName(null));
    }

    private static String check(FormModel.Field f, String value) throws RuntimeException {
        try {
            java.lang.reflect.Method m = FormModel.class.getDeclaredMethod("check", FormModel.Field.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, f, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
