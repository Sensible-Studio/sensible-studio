package io.sensible.studio.adapter.servlet;

import io.sensible.studio.compat.dialog.tree.DialogJson;
import io.sensible.studio.compat.dialog.tree.DialogTreeResolver;
import io.sensible.studio.compat.dialog.tree.PolicyResolver;
import io.sensible.studio.compat.dialog.tree.SlingNodeSource;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import javax.servlet.Servlet;
import java.io.IOException;

/**
 * Returns the effective (merged) Granite dialog of a component as a resource tree.
 *
 * <pre>
 * GET /bin/studio/dialog?resourceType=wknd/components/byline
 * GET /bin/studio/dialog?resourceType=wknd/components/page&amp;dialog=cq:dialog
 * GET /bin/studio/dialog?resourceType=wknd/components/title&amp;dialog=cq:design_dialog
 * GET /bin/studio/dialog?resourceType=wknd/components/title&amp;path=/content/wknd/us/en/x/jcr:content/root/container/title
 * </pre>
 *
 * With {@code path} (the component instance being edited) the response also carries the instance's content policy,
 * which dialogs read as {@code cqDesign}.
 *
 * The tree is read with the caller's resource resolver, so the usual ACLs on {@code /apps} and {@code /libs}
 * apply. See {@link DialogTreeResolver} for the merge rules and {@link DialogJson} for the format.
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/studio/dialog",
        "sling.servlet.methods=GET"
    }
)
public class DialogServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 2L;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");

        String resourceType = request.getParameter("resourceType");
        if (isBlank(resourceType)) {
            resourceType = request.getParameter("component"); // accepted for older clients
        }
        String dialogName = request.getParameter("dialog");
        if (isBlank(dialogName)) dialogName = "cq:dialog";

        if (isBlank(resourceType)) {
            response.setStatus(400);
            response.getWriter().write("{\"error\":\"Missing required parameter 'resourceType'\"}");
            return;
        }
        if (!"cq:dialog".equals(dialogName) && !"cq:design_dialog".equals(dialogName)) {
            response.setStatus(400);
            response.getWriter().write("{\"error\":\"Parameter 'dialog' must be cq:dialog or cq:design_dialog\"}");
            return;
        }

        SlingNodeSource source = new SlingNodeSource(request.getResourceResolver());
        DialogTreeResolver.Result result = new DialogTreeResolver(source).resolve(resourceType.trim(), dialogName);
        String path = request.getParameter("path");
        PolicyResolver.Policy policy = isBlank(path) ? null : new PolicyResolver(source).resolve(path.trim(), resourceType.trim());
        if (result.getDialog() == null) {
            response.setStatus(404);
        }
        response.getWriter().write(DialogJson.toJson(result, policy));
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
