package io.sensible.studio.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StudioQuickstartTest {

    @Test
    public void testDefaultArguments() {
        StudioQuickstart quickstart = new StudioQuickstart();
        quickstart.parseArgs(new String[0]);

        assertEquals(4502, quickstart.getPort());
        assertEquals("author", quickstart.getRunMode());
        assertEquals("./instances/author", quickstart.getHomeDir());
    }

    @Test
    public void testCustomArguments() {
        StudioQuickstart quickstart = new StudioQuickstart();
        quickstart.parseArgs(new String[]{"-p", "4503", "-r", "publish", "-c", "/tmp/studio-data"});

        assertEquals(4503, quickstart.getPort());
        assertEquals("publish", quickstart.getRunMode());
        assertEquals("/tmp/studio-data", quickstart.getHomeDir());
    }
}
