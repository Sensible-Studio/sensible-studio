package com.example.wfqa;

import java.io.Serializable;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowExternalProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;

/**
 * Work done somewhere else: the step starts it, the engine asks whether it is done, the step takes the
 * result. Here "somewhere else" is the payload: the work is done when it says qaExtDone.
 */
public final class External {

    private External() {
    }

    /** An id that is neither text nor a number: it has to come back as itself. */
    public static final class Ticket implements Serializable {
        private static final long serialVersionUID = 1L;
        final String job;
        final int tries;

        Ticket(String job, int tries) {
            this.job = job;
            this.tries = tries;
        }
    }

    @Component(service = WorkflowExternalProcess.class, property = "process.label=QA External")
    public static class Outside implements WorkflowExternalProcess {
        @Override
        public Serializable execute(WorkItem item, WorkflowSession session, MetaDataMap arguments) throws WorkflowException {
            Resource content = Steps.content(item, session);
            if (content.getValueMap().get("qaExtSkip", false)) {
                return null; // nothing to wait for
            }
            content.adaptTo(ModifiableValueMap.class).put("qaExtStarted", arguments.get("PROCESS_ARGS", ""));
            return new Ticket("job-" + content.getName(), 3);
        }

        @Override
        public boolean hasFinished(Serializable externalProcessId, WorkItem item, WorkflowSession session, MetaDataMap arguments) {
            try {
                return ((Ticket) externalProcessId).tries == 3 && Steps.content(item, session).getValueMap().get("qaExtDone") != null;
            } catch (WorkflowException e) {
                return false;
            }
        }

        @Override
        public void handleResult(Serializable externalProcessId, WorkItem workItem, WorkflowSession session, MetaDataMap arguments)
                throws WorkflowException {
            Steps.content(workItem, session).adaptTo(ModifiableValueMap.class).put("qaExtResult", ((Ticket) externalProcessId).job);
        }
    }

    /** The same against the older API, with text for an id. */
    @Component(service = com.day.cq.workflow.exec.WorkflowExternalProcess.class, property = "process.label=QA Old External")
    public static class OldOutside implements com.day.cq.workflow.exec.WorkflowExternalProcess {
        @Override
        public Serializable execute(com.day.cq.workflow.exec.WorkItem item, com.day.cq.workflow.WorkflowSession session,
                com.day.cq.workflow.metadata.MetaDataMap arguments) throws com.day.cq.workflow.WorkflowException {
            return "old-job";
        }

        @Override
        public boolean hasFinished(Serializable externalProcessId, com.day.cq.workflow.exec.WorkItem item,
                com.day.cq.workflow.WorkflowSession session, com.day.cq.workflow.metadata.MetaDataMap arguments) {
            try {
                return "old-job".equals(externalProcessId) && OldSteps.content(item, session).hasProperty("qaExtDone");
            } catch (javax.jcr.RepositoryException e) {
                return false;
            }
        }

        @Override
        public void handleResult(Serializable externalProcessId, com.day.cq.workflow.exec.WorkItem workItem,
                com.day.cq.workflow.WorkflowSession session, com.day.cq.workflow.metadata.MetaDataMap arguments)
                throws com.day.cq.workflow.WorkflowException {
            try {
                OldSteps.content(workItem, session).setProperty("qaOldExtResult", String.valueOf(externalProcessId));
            } catch (javax.jcr.RepositoryException e) {
                throw new com.day.cq.workflow.WorkflowException("Old external result failed", e);
            }
        }
    }

    /** The oldest chooser: its arguments as an array; the first names who. */
    @SuppressWarnings("deprecation")
    @Component(service = com.day.cq.workflow.exec.ParticipantChooser.class, property = "chooser.label=QA Oldest Chooser")
    public static class Oldest implements com.day.cq.workflow.exec.ParticipantChooser {
        @Override
        public String getParticipant(com.day.cq.workflow.exec.WorkItem workItem, com.day.cq.workflow.WorkflowSession workflowSession,
                String[] args) {
            return args.length > 1 ? args[1] : workItem.getWorkflow().getInitiator();
        }
    }
}
