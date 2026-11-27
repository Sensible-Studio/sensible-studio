/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LiveStatsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return (Map<String, Object>) Json.parse(json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counters_of_two_instances_add_up_and_the_rate_is_of_the_sum() {
        Map<String, Object> a = parse("{\"default\":{\"impressions\":10,\"conversions\":1,\"rate\":10.0,"
                + "\"days\":[{\"day\":\"2026-11-26\",\"impressions\":4,\"conversions\":0},{\"day\":\"2026-11-27\",\"impressions\":6,\"conversions\":1}]}}");
        Map<String, Object> b = parse("{\"default\":{\"impressions\":30,\"conversions\":3,\"rate\":10.0,"
                + "\"days\":[{\"day\":\"2026-11-27\",\"impressions\":30,\"conversions\":3}]},"
                + "\"winter\":{\"impressions\":5,\"conversions\":5,\"rate\":100.0,\"days\":[]}}");
        Map<String, Object> sum = LiveStats.merge(a, b);
        Map<String, Object> d = (Map<String, Object>) sum.get("default");
        assertEquals(40L, d.get("impressions"));
        assertEquals(4L, d.get("conversions"));
        assertEquals(10.0, d.get("rate"));
        List<Map<String, Object>> days = (List<Map<String, Object>>) d.get("days");
        assertEquals(2, days.size());
        assertEquals("2026-11-26", days.get(0).get("day"));
        assertEquals(36L, days.get(1).get("impressions"));
        assertEquals(4L, days.get(1).get("conversions"));
        assertEquals(5L, ((Map<String, Object>) sum.get("winter")).get("impressions"));
    }

    @Test
    void nothing_on_one_side_leaves_the_other_as_it_is() {
        Map<String, Object> a = parse("{\"default\":{\"impressions\":2,\"conversions\":1,\"days\":[]}}");
        assertEquals(50.0, ((Map<?, ?>) LiveStats.merge(a, null).get("default")).get("rate"));
        assertEquals(2L, ((Map<?, ?>) LiveStats.merge(null, a).get("default")).get("impressions"));
        assertTrue(LiveStats.merge(null, null).isEmpty());
    }

    @Test
    void only_targets_that_carry_content_to_an_instance_are_asked() {
        assertTrue(LiveStats.isContent(null));
        assertTrue(LiveStats.isContent("studio"));
        assertFalse(LiveStats.isContent("flush"));
        assertFalse(LiveStats.isContent("webhook"));
        assertFalse(LiveStats.isContent("reverse"));
        assertEquals("http://localhost:4503", LiveStats.base("http://localhost:4503/bin/receive"));
        assertEquals("https://live.example.com", LiveStats.base("https://live.example.com/bin/receive?x=1"));
        assertNull(LiveStats.base("/bin/receive"));
        assertNull(LiveStats.base(null));
    }
}
