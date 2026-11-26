/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;

import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutConfigManager;

/**
 * Reads rollout configurations from the repository. Projects add their own below {@code /apps/msm} (and may overlay
 * the Sensible Studio ones there); Sensible Studio ships its standard configurations in the {@code studio-msm-content} package under
 * {@code /libs/msm/wcm/rolloutconfigs}.
 */
@Component(service = RolloutConfigManager.class)
public class RolloutConfigManagerImpl implements RolloutConfigManager {

    static final String[] ROOTS = {"/apps/msm/wcm/rolloutconfigs", "/libs/msm/wcm/rolloutconfigs"};

    @Override
    public RolloutConfig getRolloutConfig(ResourceResolver resolver, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        Resource node = null;
        if (path.startsWith("/libs/")) {
            node = resolver.getResource("/apps/" + path.substring("/libs/".length()));
        }
        if (node == null) {
            node = resolver.getResource(path);
        }
        if (node == null && !path.startsWith("/")) {
            for (String root : ROOTS) {
                node = resolver.getResource(root + "/" + path);
                if (node != null) {
                    break;
                }
            }
        }
        return node == null ? null : new RolloutConfigImpl(node);
    }

    @Override
    public List<RolloutConfig> getRolloutConfigs(ResourceResolver resolver) {
        Map<String, RolloutConfig> byName = new LinkedHashMap<>();
        for (int i = ROOTS.length - 1; i >= 0; i--) {
            Resource root = resolver.getResource(ROOTS[i]);
            if (root == null) {
                continue;
            }
            for (Resource child : root.getChildren()) {
                if (!child.getName().startsWith("jcr:") && !child.getName().startsWith("rep:")) {
                    // /apps comes last and replaces a /libs configuration of the same name
                    byName.put(child.getName(), new RolloutConfigImpl(child));
                }
            }
        }
        return new ArrayList<>(byName.values());
    }
}
