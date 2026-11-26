package io.sensible.studio.translation.impl;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Proxy;
import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Translation Review workflow's process steps record the reviewer's decision on the copy. */
@ExtendWith(SlingContextExtension.class)
class ReviewProcessesTest {

    private static final String EN = "/content/site/language-masters/en/about";
    private static final String FR = "/content/site/language-masters/fr/about";
    private static final String ITEMS = "/var/workflow/instances/server0/2026-10-18/translation_review_1/workItems";

    private final SlingContext context = new SlingContext(ResourceResolverType.RESOURCERESOLVER_MOCK);
    private PageTranslationTest.Marking translator;

    @BeforeEach
    void setUp() throws Exception {
        context.load().json("/page-site.json", "/content/site");
        context.create().resource("/content/dam/site/en/surf.jpg", "jcr:primaryType", "dam:Asset");
        translator = new PageTranslationTest.Marking();
        new PageTranslation(context.resourceResolver(), translator, new PageTranslationRules()).translate(EN, "fr", null, false, null);
        Calendar earlier = Calendar.getInstance();
        earlier.add(Calendar.MINUTE, -5);
        context.create().resource(ITEMS + "/item_1", "status", "COMPLETED", "completedBy", "maria", "comment", "The title is too literal",
                "endTime", earlier);
        context.create().resource(ITEMS + "/item_2", "status", "ACTIVE");
    }

    private WorkItem item(String payload) {
        WorkflowData data = (WorkflowData) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WorkflowData.class},
                (p, m, a) -> "getPayload".equals(m.getName()) ? payload : null);
        Workflow wf = (Workflow) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Workflow.class},
                (p, m, a) -> "getInitiator".equals(m.getName()) ? "admin" : null);
        return (WorkItem) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WorkItem.class}, (p, m, a) -> {
            switch (m.getName()) {
                case "getId": return ITEMS + "/item_2";
                case "getWorkflow": return wf;
                case "getWorkflowData": return data;
                default: return null;
            }
        });
    }

    private WorkflowSession session() {
        ResourceResolver rr = context.resourceResolver();
        return (WorkflowSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{WorkflowSession.class},
                (p, m, a) -> "adaptTo".equals(m.getName()) && a[0] == ResourceResolver.class ? rr : null);
    }

    private <T extends TranslationReviewProcesses.Base> T step(T s) {
        s.translator = translator;
        s.rules = new PageTranslationRules();
        return s;
    }

    private ValueMap record() {
        return context.resourceResolver().getResource(PageTranslation.RECORDS + FR).getValueMap();
    }

    @Test
    void theReviewerAsksForChangesThenApproves() throws Exception {
        step(new TranslationReviewProcesses.RequestChanges()).execute(item(FR), session(), null);
        assertEquals("CHANGES", record().get("status", String.class));
        assertEquals("maria", record().get("changesRequestedBy", String.class));
        assertEquals("The title is too literal", record().get("reviewComment", String.class));
        assertEquals("changes", new PageTranslation(context.resourceResolver(), translator, new PageTranslationRules()).queue(null, null, null, 10).get(0).get("state"));

        step(new TranslationReviewProcesses.Approve()).execute(item(FR), session(), null);
        assertEquals("APPROVED", record().get("status", String.class));
        assertEquals("maria", record().get("approvedBy", String.class), "the reviewer, not the workflow's service user");
        assertNull(record().get("reviewComment"));
        assertEquals(1, translator.remembered.size(), "approved texts go to the memory");
    }

    @Test
    void refusesWhatIsNotACopy() {
        WorkflowException e = assertThrows(WorkflowException.class,
                () -> step(new TranslationReviewProcesses.Approve()).execute(item("/content/site/language-masters/en"), session(), null));
        assertTrue(e.getMessage().contains("not a language copy"));
    }
}
