package com.example.wfqa;

import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.osgi.service.component.annotations.Component;

import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.JavaProcessExt;
import com.day.cq.workflow.exec.ParticipantStepChooser;
import com.day.cq.workflow.exec.Route;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;

/** The same kind of steps against the older API, com.day.cq.workflow, as many AEM projects still have them. */
public final class OldSteps {

    private OldSteps() {
    }

    static Node content(WorkItem item, WorkflowSession session) throws RepositoryException {
        Session jcr = session.getSession();
        String path = item.getWorkflowData().getPayload().toString();
        return jcr.nodeExists(path + "/jcr:content") ? jcr.getNode(path + "/jcr:content") : jcr.getNode(path);
    }

    @Component(service = WorkflowProcess.class, property = "process.label=QA Old Stamp")
    public static class Stamp implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            try {
                Node node = content(item, session);
                node.setProperty("qaOld", args.get("PROCESS_ARGS", ""));
                node.setProperty("qaOldInitiator", item.getWorkflow().getInitiator());
                node.setProperty("qaOldModel", item.getWorkflow().getWorkflowModel().getTitle());
                node.setProperty("qaOldNode", item.getNode().getTitle());
                MetaDataMap wf = item.getWorkflowData().getMetaDataMap();
                long visits = wf.get("qa.visits", 0L) + 1;
                wf.put("qa.visits", visits);
                node.setProperty("qaOldVisits", visits);
                node.setProperty("qaOldHistory", session.getHistory(item.getWorkflow()).size());
            } catch (RepositoryException e) {
                throw new WorkflowException("Old stamp failed", e);
            }
        }
    }

    /** The oldest shape: arguments as an array. */
    @Component(service = JavaProcessExt.class, property = "process.label=QA Old Java Process")
    public static class Java implements JavaProcessExt {
        @Override
        public void execute(WorkItem item, WorkflowSession session) throws Exception {
            execute(item, session, new String[0]);
        }

        @Override
        public void execute(WorkItem item, WorkflowSession session, String[] args) throws Exception {
            Node node = content(item, session);
            node.setProperty("qaOldArgs", String.join("+", args));
            node.setProperty("qaOldArgCount", (long) args.length);
        }
    }

    @Component(service = WorkflowProcess.class, property = "process.label=QA Old Fail")
    public static class Fail implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            throw new WorkflowException("failed the old way");
        }
    }

    /** Chooses its route by code, through the older session. */
    @Component(service = WorkflowProcess.class, property = "process.label=QA Old Route")
    public static class Router implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            try {
                Node node = content(item, session);
                String want = node.hasProperty("qaRoute") ? node.getProperty("qaRoute").getString() : "";
                List<Route> routes = session.getRoutes(item, true);
                Route chosen = routes.get(0);
                for (Route r : routes) {
                    if (r.getName().equals(want)) {
                        chosen = r;
                    }
                }
                session.complete(item, chosen);
            } catch (RepositoryException e) {
                throw new WorkflowException("Old route failed", e);
            }
        }
    }

    @Component(service = ParticipantStepChooser.class, property = "chooser.label=QA Old Reviewer")
    public static class Reviewer implements ParticipantStepChooser {
        @Override
        public String getParticipant(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            try {
                Node node = content(item, session);
                return node.hasProperty("qaReviewer") ? node.getProperty("qaReviewer").getString() : item.getWorkflow().getInitiator();
            } catch (RepositoryException e) {
                throw new WorkflowException("Old chooser failed", e);
            }
        }
    }
}
