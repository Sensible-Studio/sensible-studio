/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.personalization.htl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.script.Bindings;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.scripting.SlingBindings;
import org.apache.sling.scripting.sightly.pojo.Use;

import com.day.cq.wcm.api.WCMMode;

import io.sensible.studio.personalization.impl.ContextHubConfig;
import io.sensible.studio.personalization.impl.Json;
import io.sensible.studio.personalization.impl.Segments;

/**
 * The Target component's view: its experiences (default first) with their audiences' expression trees,
 * the one the editor selected ({@code studio.experience=<target path>=<experience name>}), and whether the
 * page is being authored (then only the selected experience renders; otherwise all of them do and the
 * ContextHub client script shows the one whose audience matches).
 */
public class TargetUse implements Use {

    private static final String SCRIPT_FLAG = "studio.contexthub.script";

    private boolean authoring;
    private String path;
    private final List<Map<String, Object>> experiences = new ArrayList<>();
    private Map<String, Object> selected;
    private boolean includeScript;
    private String goalType = "none";
    private String goalValue = "";
    /** targeting (by audience) or abtest (the visitors split by each experience's share). */
    private String mode = "targeting";
    /** Visitors are counted only outside authoring and preview (no {@code wcmmode} parameter in the request). */
    private boolean track;
    /** ContextHub's configuration for the page (stores, the bar's fields), with the client script. */
    private String contexthub = "";

    @Override
    public void init(Bindings bindings) {
        Resource resource = (Resource) bindings.get(SlingBindings.RESOURCE);
        SlingHttpServletRequest request = (SlingHttpServletRequest) bindings.get(SlingBindings.REQUEST);
        path = resource.getPath();
        authoring = WCMMode.fromRequest(request) == WCMMode.EDIT;
        track = request.getParameter("wcmmode") == null && request.getParameter("timewarp") == null;
        ValueMap own = resource.getValueMap();
        goalType = own.get("goalType", "none");
        goalValue = own.get("goalValue", "");
        mode = "abtest".equals(own.get("activityType", "targeting")) ? "abtest" : "targeting";

        Map<String, Object> dflt = null;
        for (Resource child : resource.getChildren()) {
            String name = child.getName();
            if ("cq:responsive".equals(name) || name.startsWith("jcr:") || name.startsWith("rep:")) {
                continue;
            }
            ValueMap vm = child.getValueMap();
            String segmentPath = vm.get("cq:segment", String.class);
            Resource segment = segmentPath == null ? null : resource.getResourceResolver().getResource(segmentPath);
            Segments.Segment s = Segments.read(segment);
            Map<String, Object> e = new LinkedHashMap<>();
            boolean isDefault = "default".equals(name);
            e.put("name", name);
            e.put("path", child.getPath());
            e.put("title", isDefault ? "Default" : vm.get("experienceTitle", name));
            e.put("segmentPath", segmentPath == null ? "" : segmentPath);
            e.put("segmentTitle", s == null ? "" : s.title);
            e.put("expression", s == null || s.expression == null ? "" : Json.write(s.expression));
            e.put("boost", s == null ? 0L : s.boost);
            e.put("isDefault", isDefault);
            e.put("share", vm.get("trafficShare", 0L));
            if (isDefault) {
                dflt = e;
            } else {
                experiences.add(e);
            }
        }
        if (dflt != null) {
            experiences.add(0, dflt);
        }
        selected = dflt;
        String[] wanted = request.getParameterValues("studio.experience");
        if (wanted != null) {
            for (String w : wanted) {
                int eq = w.lastIndexOf('=');
                if (eq > 0 && path.equals(w.substring(0, eq))) {
                    String expName = w.substring(eq + 1);
                    for (Map<String, Object> e : experiences) {
                        if (expName.equals(e.get("name"))) {
                            selected = e;
                        }
                    }
                }
            }
        }
        if (!authoring && request.getAttribute(SCRIPT_FLAG) == null) {
            request.setAttribute(SCRIPT_FLAG, Boolean.TRUE);
            includeScript = true;
            int jc = path.indexOf("/jcr:content");
            String page = jc > 0 ? path.substring(0, jc) : path;
            contexthub = Json.write(ContextHubConfig.forClient(ContextHubConfig.effective(resource.getResourceResolver(), page)));
        }
    }

    public boolean isAuthoring() {
        return authoring;
    }

    public String getPath() {
        return path;
    }

    public List<Map<String, Object>> getExperiences() {
        return experiences;
    }

    public Map<String, Object> getSelected() {
        return selected;
    }

    public boolean isIncludeScript() {
        return includeScript;
    }

    public String getGoalType() {
        return goalType;
    }

    public String getGoalValue() {
        return goalValue;
    }

    public String getMode() {
        return mode;
    }

    public String getContexthub() {
        return contexthub;
    }

    public boolean isTrack() {
        return track;
    }
}
