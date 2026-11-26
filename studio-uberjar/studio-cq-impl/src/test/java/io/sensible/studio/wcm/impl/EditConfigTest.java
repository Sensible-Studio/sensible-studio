/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** The parts of {@code cq:editConfig} and the icon the editor gets, inherited along the super types. */
@ExtendWith(SlingContextExtension.class)
class EditConfigTest {

    private final SlingContext context = new SlingContext();

    @Test
    void actionConfigsAndIconComeFromTheSuperType() {
        String core = "/apps/core/wcm/components/experiencefragment/v2/experiencefragment";
        context.create().resource(core, "jcr:primaryType", "cq:Component", "jcr:title", "Experience Fragment", "cq:icon", "experience");
        context.create().resource(core + "/cq:editConfig", "jcr:primaryType", "cq:EditConfig", "cq:actions",
                new String[] {"EDIT", "DELETE", "INSERT", "COPYMOVE"});
        context.create().resource(core + "/cq:editConfig/cq:actionConfigs/editInNewTab", "icon", "edit", "text", "Edit",
                "handler", "CQ.CoreComponents.experiencefragment.v2.actions.edit");
        context.create().resource("/apps/site/components/xf", "jcr:primaryType", "cq:Component", "jcr:title", "XF",
                "sling:resourceSuperType", "core/wcm/components/experiencefragment/v2/experiencefragment");
        context.create().resource("/apps/site/components/text", "jcr:primaryType", "cq:Component", "jcr:title", "Text");

        ComponentManagerImpl cm = new ComponentManagerImpl(context.resourceResolver());
        Map<String, Object> xf = EditConfig.describe(cm.getComponent("/apps/site/components/xf"));
        assertEquals("experience", xf.get("icon"));
        assertEquals(List.of("EDIT", "DELETE", "INSERT", "COPYMOVE"), xf.get("actions"));
        List<?> configs = (List<?>) xf.get("actionConfigs");
        assertEquals(1, configs.size());
        Map<?, ?> edit = (Map<?, ?>) configs.get(0);
        assertEquals("editInNewTab", edit.get("name"));
        assertEquals("edit", edit.get("icon"));
        assertEquals("CQ.CoreComponents.experiencefragment.v2.actions.edit", edit.get("handler"));

        Map<String, Object> text = EditConfig.describe(cm.getComponent("/apps/site/components/text"));
        assertNull(text.get("icon"));
        assertNull(text.get("actionConfigs"));
    }
}
