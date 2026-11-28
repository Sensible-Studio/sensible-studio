package com.example.wfqa;

import java.util.List;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.HistoryItem;
import com.adobe.granite.workflow.exec.ParticipantStepChooser;
import com.adobe.granite.workflow.exec.Route;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;

/** Process steps the way a customer writes them for AEM, against the Granite workflow API. */
public final class Steps {

    private Steps() {
    }

    static Resource content(WorkItem item, WorkflowSession session) throws WorkflowException {
        WorkflowData data = item.getWorkflowData();
        if (!"JCR_PATH".equals(data.getPayloadType())) {
            throw new WorkflowException("Not a path payload: " + data.getPayloadType());
        }
        String path = data.getPayload().toString();
        ResourceResolver resolver = session.adaptTo(ResourceResolver.class);
        if (resolver == null) {
            throw new WorkflowException("The session gives no resource resolver");
        }
        Resource page = resolver.getResource(path);
        if (page == null) {
            throw new WorkflowException("No payload at " + path);
        }
        Resource content = page.getChild("jcr:content");
        return content == null ? page : content;
    }

    /** Writes what it knows of the flow onto the payload, through the resource API. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Stamp")
    public static class Stamp implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            Resource content = content(item, session);
            ModifiableValueMap vm = content.adaptTo(ModifiableValueMap.class);
            vm.put("qaStamp", args.get("PROCESS_ARGS", ""));
            vm.put("qaInitiator", String.valueOf(item.getWorkflow().getInitiator()));
            vm.put("qaModel", String.valueOf(item.getWorkflow().getWorkflowModel().getTitle()));
            vm.put("qaNode", String.valueOf(item.getNode().getTitle()));
            vm.put("qaState", String.valueOf(item.getWorkflow().getState()));
            vm.put("qaType", String.valueOf(item.getItemType()));
            // what one step leaves for the next: the workflow's own metadata
            MetaDataMap wf = item.getWorkflowData().getMetaDataMap();
            long visits = wf.get("qa.visits", 0L) + 1;
            wf.put("qa.visits", visits);
            vm.put("qaVisits", visits);
            vm.put("qaTitle", String.valueOf(item.getWorkflow().getMetaDataMap().get("workflowTitle", "")));
        }
    }

    /** The same through JCR, with a save of its own, as much old code does. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Jcr Stamp")
    public static class JcrStamp implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            try {
                Session jcr = session.adaptTo(Session.class);
                Node node = jcr.getNode(content(item, session).getPath());
                node.setProperty("qaJcr", args.get("PROCESS_ARGS", "jcr"));
                node.setProperty("qaJcrUser", jcr.getUserID());
                jcr.save();
            } catch (javax.jcr.RepositoryException e) {
                throw new WorkflowException("JCR stamp failed", e);
            }
        }
    }

    /** Fails until the payload says qaFixed=true: the Failures tab and Retry. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Fail Until Fixed")
    public static class Fail implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            Resource content = content(item, session);
            // written before failing: must not stay when the step fails
            content.adaptTo(ModifiableValueMap.class).put("qaHalfDone", true);
            if (!content.getValueMap().get("qaFixed", false)) {
                throw new WorkflowException("The payload is not fixed yet (qaFixed)");
            }
            content.adaptTo(ModifiableValueMap.class).put("qaPassedFail", true);
        }
    }

    /** Throws something that is not a WorkflowException. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Blow Up")
    public static class BlowUp implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {
            throw new IllegalStateException("blown up on purpose");
        }
    }

    /**
     * Chooses the branch after it by code: the step has Handler Advance off and completes its own item
     * along the route whose name the payload's qaRoute names.
     */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Route By Payload")
    public static class Router implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            Resource content = content(item, session);
            String want = content.getValueMap().get("qaRoute", "");
            List<Route> routes = session.getRoutes(item, true);
            StringBuilder names = new StringBuilder();
            Route chosen = null;
            for (Route r : routes) {
                names.append(r.getName()).append('|');
                if (r.getName().equals(want)) {
                    chosen = r;
                }
            }
            content.adaptTo(ModifiableValueMap.class).put("qaRoutes", names.toString());
            if (chosen == null) {
                if (routes.isEmpty()) {
                    throw new WorkflowException("No route out of " + item.getNode().getTitle());
                }
                chosen = routes.get(0);
            }
            session.complete(item, chosen);
        }
    }

    /** Ends the flow from inside a step. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Terminate")
    public static class Terminate implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            content(item, session).adaptTo(ModifiableValueMap.class).put("qaTerminated", true);
            session.terminateWorkflow(item.getWorkflow());
        }
    }

    /** Starts another flow on the same payload from inside a step. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Start Another")
    public static class StartAnother implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            String id = session.startWorkflow(session.getModel(args.get("PROCESS_ARGS", "")),
                    session.newWorkflowData("JCR_PATH", item.getWorkflowData().getPayload())).getId();
            content(item, session).adaptTo(ModifiableValueMap.class).put("qaStarted", id);
        }
    }

    /**
     * Reads the history of its flow and hands every other waiting step of it to the user the payload
     * names in qaDelegate.
     */
    @Component(service = WorkflowProcess.class, property = "process.label=QA History And Delegate")
    public static class HistoryAndDelegate implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            Resource content = content(item, session);
            StringBuilder told = new StringBuilder();
            for (HistoryItem h : session.getHistory(item.getWorkflow())) {
                told.append(h.getWorkItem().getNode().getTitle()).append(':').append(h.getAction()).append(':')
                        .append(h.getUserId()).append(':').append(h.getComment() == null ? "" : h.getComment()).append('|');
            }
            ModifiableValueMap vm = content.adaptTo(ModifiableValueMap.class);
            vm.put("qaHistory", told.toString());
            String to = content.getValueMap().get("qaDelegate", "");
            int given = 0;
            for (WorkItem other : session.getAllWorkItems()) {
                if (!other.getId().equals(item.getId()) && other.getWorkflow().getId().equals(item.getWorkflow().getId()) && !to.isEmpty()) {
                    session.delegateWorkItem(other, new com.adobe.granite.workflow.exec.Participant() {
                        @Override
                        public String getID() {
                            return to;
                        }

                        @Override
                        public String getName() {
                            return to;
                        }

                        @Override
                        public boolean isGroup() {
                            return false;
                        }
                    });
                    given++;
                }
            }
            vm.put("qaDelegated", given);
            vm.put("qaSuperuser", session.isSuperuser());
        }
    }

    /** Gives the step to whoever the payload names, else to who started the flow. */
    @Component(service = ParticipantStepChooser.class, property = "chooser.label=QA Reviewer Of The Payload")
    public static class Reviewer implements ParticipantStepChooser {
        @Override
        public String getParticipant(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            String who = content(item, session).getValueMap().get("qaReviewer", "");
            return who.isEmpty() ? item.getWorkflow().getInitiator() : who;
        }
    }
}
