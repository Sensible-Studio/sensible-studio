/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;

import java.util.List;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.ModificationType;
import org.apache.sling.servlets.post.SlingPostProcessor;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.api.WCMException;

/**
 * A copy is the author's own content: copying a rolled-out component or page (Sling POST {@code :operation=copy},
 * the editor's paste, the Sites console) must not carry the MSM markers along, or the next rollout would treat the
 * copy as inherited and delete it (its source does not exist). The copy of a whole live copy root stays a live copy.
 */
@Component(service = SlingPostProcessor.class)
public class MsmCopies implements SlingPostProcessor {

    @Override
    public void process(SlingHttpServletRequest request, List<Modification> changes) throws Exception {
        for (Modification m : changes) {
            if (m.getType() == ModificationType.COPY && m.getDestination() != null) {
                Resource copy = request.getResourceResolver().getResource(m.getDestination());
                if (copy != null) {
                    afterCopy(copy);
                }
            }
        }
    }

    /** Removes the MSM markers from a fresh copy, unless it is the root page of a live copy. */
    public static void afterCopy(Resource copy) throws WCMException {
        if (Msm.isPage(copy)) {
            Resource cfg = copy.getResourceResolver().getResource(Msm.configPath(copy.getPath()));
            if (cfg != null && cfg.getValueMap().get(PN_MASTER, String.class) != null) {
                return;
            }
        }
        LiveRelationshipManagerImpl.strip(copy, true);
    }
}
