package com.example.wfqa;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;

import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;

import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.collection.util.ResultSet;
import com.adobe.granite.workflow.exec.Participant;
import com.adobe.granite.workflow.exec.Route;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.model.WorkflowModel;

/**
 * The calls of the two workflow APIs that a process step seldom makes, made as the signed-in user, one
 * per {@code do}: POST /bin/qa/wfapi?do=…  Answers plain text, "key=value" lines.
 */
@Component(service = Servlet.class, property = {"sling.servlet.paths=/bin/qa/wfapi", "sling.servlet.methods=POST"})
public class ApiServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        WorkflowSession session = request.getResourceResolver().adaptTo(WorkflowSession.class);
        com.day.cq.workflow.WorkflowSession old = request.getResourceResolver().adaptTo(com.day.cq.workflow.WorkflowSession.class);
        StringBuilder out = new StringBuilder();
        String id = request.getParameter("id");
        try {
            switch (String.valueOf(request.getParameter("do"))) {
                case "model": {
                    WorkflowModel made = session.createNewModel(request.getParameter("title"), id);
                    out.append("id=").append(made.getId()).append("\nversion=").append(made.getVersion()).append("\nnodes=").append(made.getNodes().size());
                    session.deployModel(made);
                    out.append("\ndeployed=").append(session.getModel(made.getId()).getVersion());
                    out.append("\nsame=").append(session.getModel(made.getId(), session.getModel(made.getId()).getVersion()).getId());
                    try {
                        session.getModel(made.getId(), "0.1");
                        out.append("\nold=found");
                    } catch (com.adobe.granite.workflow.WorkflowException e) {
                        out.append("\nold=refused");
                    }
                    break;
                }
                case "build": {
                    // a model made in code, as AEM projects do it at install time
                    WorkflowModel model = session.createNewModel(request.getParameter("title"), id);
                    com.adobe.granite.workflow.model.WorkflowNode stamp = model.createNode("Stamp", com.adobe.granite.workflow.model.WorkflowNode.TYPE_PROCESS, "by code");
                    stamp.getMetaDataMap().put("PROCESS", "QA Stamp");
                    stamp.getMetaDataMap().put("PROCESS_ARGS", "built");
                    stamp.getMetaDataMap().put("PROCESS_AUTO_ADVANCE", "true");
                    com.adobe.granite.workflow.model.WorkflowNode look = model.createNode();
                    look.setTitle("Look");
                    look.setType(com.adobe.granite.workflow.model.WorkflowNode.TYPE_PARTICIPANT);
                    look.getMetaDataMap().put("PARTICIPANT", "administrators");
                    try {
                        model.validate();
                        out.append("loose=valid\n");
                    } catch (com.adobe.granite.workflow.model.ValidationException e) {
                        out.append("loose=").append(e.getMessage()).append('\n');
                    }
                    model.createTransition(model.getRootNode(), stamp, null);
                    com.adobe.granite.workflow.model.WorkflowTransition t = model.createTransition();
                    t.setFrom(stamp);
                    t.setTo(look);
                    model.createTransition(look, model.getEndNode(), null);
                    model.setDescription("Built in code");
                    model.validate();
                    session.deployModel(model);
                    WorkflowModel stored = session.getModel(model.getId());
                    out.append("id=").append(stored.getId()).append("\nnodes=").append(stored.getNodes().size()).append("\ntransitions=")
                            .append(stored.getTransitions().size()).append("\ndescription=").append(stored.getDescription());
                    break;
                }
                case "filters": {
                    String title = request.getParameter("title");
                    ResultSet<WorkItem> some = session.getActiveWorkItems(0, 10, item -> item.getNode() != null && title.equals(item.getNode().getTitle()));
                    WorkflowModel[] models = session.getModels(model -> model.getId().contains("/zz-"));
                    ResultSet<WorkflowModel> one = session.getModels(0, 1, model -> model.getId().contains("/zz-"));
                    out.append("some=").append(some.getTotalSize());
                    out.append("\nmodels=").append(models.length > 0 && models.length < session.getModels().length);
                    out.append("\none=").append(one.getItems().length).append('/').append(one.getTotalSize() == models.length);
                    if (some.getItems().length > 0) {
                        WorkItem item = some.getItems()[0];
                        out.append("\npath=").append(item.getContentPath());
                        out.append("\nfiltered=").append(item.getWorkflow().getWorkItems(i -> false).size()).append('/')
                                .append(item.getWorkflow().getWorkItems(i -> true).size());
                    }
                    break;
                }
                case "deleteModel":
                    session.deleteModel(id);
                    out.append("deleted=").append(id);
                    break;
                case "pages": {
                    ResultSet<Workflow> all = session.getWorkflows(null, 0, -1);
                    ResultSet<Workflow> two = session.getWorkflows(null, 1, 2);
                    ResultSet<WorkItem> items = session.getActiveWorkItems(0, 1);
                    ResultSet<WorkItem> every = session.getAllWorkItems(0, 500);
                    ResultSet<WorkflowModel> models = session.getModels(0, 2);
                    out.append("all=").append(all.getItems().length).append('/').append(all.getTotalSize());
                    out.append("\ntwo=").append(two.getItems().length).append('/').append(two.getTotalSize());
                    out.append("\nsecond=").append(two.getItems().length > 0 && all.getItems().length > 1 && two.getItems()[0].getId().equals(all.getItems()[1].getId()));
                    out.append("\nitems=").append(items.getItems().length).append('/').append(items.getTotalSize());
                    out.append("\nevery=").append(every.getTotalSize());
                    out.append("\nmodels=").append(models.getItems().length).append('/').append(models.getTotalSize());
                    out.append("\nallWorkflows=").append(session.getAllWorkflows().length);
                    break;
                }
                case "item": {
                    WorkItem item = session.getWorkItem(id);
                    out.append("type=").append(item.getItemType()).append("\nsubType=").append(item.getItemSubType()).append("\ndue=").append(item.getDueTime() != null);
                    int users = 0;
                    boolean group = false;
                    for (Iterator<Participant> it = session.getDelegates(item); it.hasNext();) {
                        Participant p = it.next();
                        users++;
                        group |= p.isGroup() && "content-authors".equals(p.getID());
                    }
                    out.append("\ndelegates=").append(users > 3).append("\ngroupOffered=").append(group);
                    break;
                }
                case "back": {
                    WorkItem item = session.getWorkItem(id);
                    List<Route> back = session.getBackRoutes(item, false);
                    out.append("back=");
                    for (Route r : back) {
                        out.append(r.getName()).append('|');
                    }
                    if (!back.isEmpty() && "true".equals(request.getParameter("go"))) {
                        session.complete(item, back.get(back.size() - 1));
                        out.append("\nwent=").append(back.get(back.size() - 1).getName());
                    }
                    break;
                }
                case "restart":
                    session.restartWorkflow(session.getWorkflow(id));
                    out.append("restarted=").append(id);
                    break;
                case "old": {
                    com.day.cq.workflow.exec.WorkItem item = old.getWorkItem(id);
                    out.append("user=").append(old.getUser() == null ? null : old.getUser().getID());
                    out.append("\nroutes=").append(old.getRoutes(item).size()).append("\nback=").append(old.getBackRoutes(item).size());
                    out.append("\nyes=").append(old.evaluate(item.getWorkflowData(), "function check(){ return workflowData.getPayload() != null; }"));
                    out.append("\nno=").append(old.evaluate(item.getWorkflowData(), "function check(){ return workflowData.getPayloadType() == 'X'; }"));
                    List<org.apache.jackrabbit.api.security.user.Authorizable> who = old.getDelegatees(item);
                    out.append("\ndelegatees=").append(who.size() > 3);
                    String to = request.getParameter("to");
                    for (org.apache.jackrabbit.api.security.user.Authorizable a : who) {
                        if (a.getID().equals(to)) {
                            old.delegateWorkItem(item, a);
                            out.append("\ngiven=").append(a.getID());
                        }
                    }
                    java.util.Hashtable<String, String> meta = new java.util.Hashtable<>();
                    meta.put("workflowTitle", "by dictionary");
                    com.day.cq.workflow.exec.Workflow started = old.startWorkflow(item.getWorkflow().getWorkflowModel(),
                            old.newWorkflowData("JCR_PATH", item.getWorkflowData().getPayload()), meta);
                    out.append("\nstarted=").append(started.getId());
                    out.append("\ntitle=").append(started.getMetaDataMap().get("workflowTitle", ""));
                    out.append("\nsession=").append(old.getSession().getUserID().equals(request.getResourceResolver().adaptTo(Session.class).getUserID()));
                    break;
                }
                default:
                    response.sendError(400, "do what?");
                    return;
            }
            response.setContentType("text/plain");
            response.getWriter().write(out.toString());
        } catch (Exception e) {
            response.sendError(500, e.getClass().getSimpleName() + ": " + e.getMessage() + "\n" + out);
        }
    }
}
