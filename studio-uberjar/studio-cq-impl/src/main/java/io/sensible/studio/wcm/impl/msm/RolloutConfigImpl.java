/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.wcm.msm.api.MSMNameConstants;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutManager.Trigger;

/** A rollout configuration read from its node: title, trigger and its action child nodes in order. */
final class RolloutConfigImpl implements RolloutConfig {

    private final String path;
    private final String title;
    private final String description;
    private final Trigger trigger;
    private final List<String> actionNames;
    private final List<String> actionPaths;

    RolloutConfigImpl(Resource node) {
        ValueMap vm = node.getValueMap();
        this.path = node.getPath();
        this.title = vm.get("jcr:title", node.getName());
        this.description = vm.get("jcr:description", String.class);
        Trigger t = Trigger.fromName(vm.get(MSMNameConstants.PN_TRIGGER, String.class));
        this.trigger = t == null ? Trigger.ROLLOUT : t;
        List<String> names = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (Resource child : node.getChildren()) {
            if (!child.getName().startsWith("jcr:") && !child.getName().startsWith("rep:")) {
                names.add(child.getName());
                paths.add(child.getPath());
            }
        }
        this.actionNames = Collections.unmodifiableList(names);
        this.actionPaths = Collections.unmodifiableList(paths);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getName() {
        return Msm.nameOf(path);
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Trigger getTrigger() {
        return trigger;
    }

    @Override
    public List<String> getActionNames() {
        return actionNames;
    }

    /** @return paths of the action nodes, aligned with {@link #getActionNames()} */
    List<String> getActionPaths() {
        return actionPaths;
    }

    @Override
    public String toString() {
        return path + " (" + trigger.getName() + ": " + actionNames + ")";
    }
}
