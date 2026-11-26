package io.sensible.studio.translation.impl;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.adobe.granite.workflow.model.WorkflowModel;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Due-date reminders of translation projects in the Inbox: a "Translation Project Due" workflow (payload: the
 * project's console URL) whose one step is the project owner's, due on the project's due date, so the Inbox shows
 * it and marks it when it is late. Started when a project gets a due date; ended when every job is complete or
 * cancelled, the project is deleted or its due date changes.
 */
final class TranslationDueReminders {

    static final String MODEL = "/var/workflow/models/translation_project_due";
    static final String URL_PREFIX = "/translation/projects.html/";

    private static final Logger LOG = LoggerFactory.getLogger(TranslationDueReminders.class);

    private TranslationDueReminders() {
    }

    /** Starts a reminder with the user's session; the workflow's id, or null when workflows are not available. */
    static String start(ResourceResolver user, String name, String title, Calendar due) {
        try {
            WorkflowSession ws = user.adaptTo(WorkflowSession.class);
            if (ws == null) return null;
            WorkflowModel model = ws.getModel(MODEL);
            Map<String, Object> meta = new HashMap<>();
            meta.put("workflowTitle", "Translation project “" + title + "” is due " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.ENGLISH).format(due.getTime()));
            Workflow wf = ws.startWorkflow(model, ws.newWorkflowData("URL", URL_PREFIX + name), meta);
            return wf.getId();
        } catch (WorkflowException | RuntimeException | LinkageError e) {
            LOG.info("No due-date reminder for translation project {}: {}", name, e.getMessage());
            return null;
        }
    }

    /** Ends a reminder (it may be gone already). */
    static void end(ResourceResolver user, String id) {
        if (id == null) return;
        try {
            WorkflowSession ws = user.adaptTo(WorkflowSession.class);
            if (ws == null) return;
            Workflow wf = ws.getWorkflow(id);
            if ("RUNNING".equals(wf.getState()) || "SUSPENDED".equals(wf.getState())) ws.terminateWorkflow(wf);
        } catch (WorkflowException | RuntimeException | LinkageError e) {
            LOG.debug("Reminder {} not ended: {}", id, e.getMessage());
        }
    }

    /** The reminder's step goes to the project's owner (its creator), due on the project's due date. */
    @Component(service = ParticipantStepChooser.class, property = "chooser.label=Translation Project Owner")
    public static class Owner implements ParticipantStepChooser {

        @Reference
        private volatile ResourceResolverFactory resolverFactory;

        @Override
        public String getParticipant(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            String payload = item.getWorkflowData() == null ? null : String.valueOf(item.getWorkflowData().getPayload());
            String initiator = item.getWorkflow() == null ? null : item.getWorkflow().getInitiator();
            if (payload == null || !payload.startsWith(URL_PREFIX)) return initiator;
            String name = payload.substring(URL_PREFIX.length());
            String owner = initiator;
            Calendar due = null;
            try (ResourceResolver service = resolverFactory.getServiceResourceResolver(
                    Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "translation"))) {
                Resource project = service.getResource(TranslationProjects.ROOT + "/" + name);
                if (project != null) {
                    ValueMap v = project.getValueMap();
                    owner = v.get("createdBy", initiator);
                    due = v.get("dueDate", Calendar.class);
                }
            } catch (Exception e) {
                LOG.info("Translation project {} not read for its reminder: {}", name, e.getMessage());
            }
            if (due != null) {
                // the step is due when the project is (the engine saves it with the step)
                ResourceResolver engine = session.adaptTo(ResourceResolver.class);
                Resource r = engine == null ? null : engine.getResource(item.getId());
                ModifiableValueMap mv = r == null ? null : r.adaptTo(ModifiableValueMap.class);
                if (mv != null) mv.put("dueTime", due);
            }
            return owner;
        }
    }
}
