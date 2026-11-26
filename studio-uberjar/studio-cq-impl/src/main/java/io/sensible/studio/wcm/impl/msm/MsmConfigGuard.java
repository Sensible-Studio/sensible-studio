/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CONFIG;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.ModificationType;
import org.apache.sling.servlets.post.SlingPostProcessor;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.MSMNameConstants;

/**
 * A live copy configuration may only name a blueprint ({@code cq:master}) its author can read. Push on modify runs as a
 * service user, so without this check anyone able to write a page could point it at a blueprint they cannot read and
 * receive its content with the next change made there. Checked for every Sling POST that creates, changes, copies,
 * moves or imports a {@code cq:LiveSyncConfig} (the MSM endpoint itself only creates live copies of pages the user
 * reads). The POST fails and nothing is saved; a version restore ({@code :operation=restore}), which the repository
 * applies at once, instead loses the configuration naming a blueprint its author cannot read.
 */
@Component(service = SlingPostProcessor.class, property = "service.ranking:Integer=1000")
public class MsmConfigGuard implements SlingPostProcessor {

    @Override
    public void process(SlingHttpServletRequest request, List<Modification> changes) throws Exception {
        ResourceResolver resolver = request.getResourceResolver();
        for (Modification m : changes) {
            if (m.getType() == ModificationType.DELETE || m.getType() == ModificationType.CHECKIN
                    || m.getType() == ModificationType.CHECKOUT) {
                continue;
            }
            // a restore names the node restored as its source and the version as its destination
            String path = m.getType() == ModificationType.RESTORE || m.getDestination() == null ? m.getSource() : m.getDestination();
            if (path == null) {
                continue;
            }
            for (Resource cfg : configs(resolver, path)) {
                String master = cfg.getValueMap().get(PN_MASTER, String.class);
                if (master == null || (Msm.contentPath(master) != null && resolver.getResource(master) != null)) {
                    continue;
                }
                if (m.getType() == ModificationType.RESTORE) {
                    // already in the workspace (a version restore is not part of the request's changes): undoing the
                    // POST cannot take it back, so the page is detached from the blueprint its author cannot read
                    Resource content = cfg.getParent();
                    resolver.delete(cfg);
                    if (content != null) {
                        Msm.removeMixin(content, MSMNameConstants.NT_LIVE_SYNC);
                    }
                    continue;
                }
                throw new WCMException("A linked copy can only follow a source you can read: " + master);
            }
        }
    }

    /** @return the {@code cq:master} values of the live copy configurations at, above (the node itself) or below path */
    static List<String> masters(ResourceResolver resolver, String path) {
        List<String> out = new ArrayList<>();
        for (Resource cfg : configs(resolver, path)) {
            add(cfg, out);
        }
        return out;
    }

    /**
     * @return the live copy configurations at or above (the node itself, for a path inside one) or below path. Below,
     *         pages, their content node and folders are walked, not component trees: configurations sit on page content.
     *         A POST that changes a large site tree (a move, copy or import of {@code /content/site}) walks all its
     *         pages; that is the price of not letting any of them through.
     */
    static List<Resource> configs(ResourceResolver resolver, String path) {
        List<Resource> out = new ArrayList<>();
        int i = path.indexOf("/" + NT_LIVE_SYNC_CONFIG);
        if (i >= 0) {
            Resource cfg = resolver.getResource(path.substring(0, i + NT_LIVE_SYNC_CONFIG.length() + 1));
            if (cfg != null) {
                out.add(cfg);
            }
            return out;
        }
        collect(resolver.getResource(path), out, 0);
        return out;
    }

    private static final java.util.Set<String> FOLDERS = java.util.Set.of("sling:Folder", "sling:OrderedFolder", "nt:folder");

    private static void collect(Resource r, List<Resource> out, int depth) {
        if (r == null || depth > 64) {
            return;
        }
        if (NT_LIVE_SYNC_CONFIG.equals(r.getName())) {
            out.add(r);
            return;
        }
        for (Resource c : r.getChildren()) {
            if (Msm.isPage(c) || Msm.JCR_CONTENT.equals(c.getName()) || NT_LIVE_SYNC_CONFIG.equals(c.getName())
                    || FOLDERS.contains(c.getValueMap().get("jcr:primaryType", String.class))) {
                collect(c, out, depth + 1);
            }
        }
    }

    private static void add(Resource cfg, List<String> out) {
        String master = cfg == null ? null : cfg.getValueMap().get(PN_MASTER, String.class);
        if (master != null) {
            out.add(master);
        }
    }
}
