package com.example.wfqa;

import java.io.IOException;

import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.day.cq.workflow.WorkflowService;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.Workflow;
import com.day.cq.workflow.model.WorkflowModel;

/**
 * Code outside a flow, as the signed-in user, through the older API: starts a flow with the
 * WorkflowService, or tells what the user's work items are. POST /bin/qa/wf?model=&payload= starts;
 * GET lists.
 */
@Component(service = Servlet.class, property = {"sling.servlet.paths=/bin/qa/wf", "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class OldStartServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Reference
    private transient WorkflowService workflows;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        WorkflowSession session = workflows.getWorkflowSession(request.getResourceResolver().adaptTo(Session.class));
        try {
            WorkflowModel model = session.getModel(request.getParameter("model"));
            Workflow started = session.startWorkflow(model, session.newWorkflowData("JCR_PATH", request.getParameter("payload")));
            response.setContentType("text/plain");
            response.getWriter().write(started.getId());
        } catch (Exception e) {
            response.sendError(500, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        // the other way to a session: adapting the resolver
        WorkflowSession session = request.getResourceResolver().adaptTo(WorkflowSession.class);
        response.setContentType("text/plain");
        try {
            StringBuilder out = new StringBuilder("user=" + session.getSession().getUserID() + " superuser=" + session.isSuperuser() + "\n");
            for (WorkItem item : session.getActiveWorkItems()) {
                out.append(item.getNode() == null ? "?" : item.getNode().getTitle()).append(" <- ").append(item.getCurrentAssignee())
                        .append(" in ").append(item.getWorkflow().getId()).append('\n');
            }
            out.append("workflows=").append(session.getAllWorkflows().length).append('\n');
            response.getWriter().write(out.toString());
        } catch (Exception e) {
            response.sendError(500, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
