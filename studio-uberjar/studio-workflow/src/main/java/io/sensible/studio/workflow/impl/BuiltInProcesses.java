/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.workflow.impl;

import java.util.Map;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;

/** Process steps that come with Sensible Studio. */
final class BuiltInProcesses {

    private BuiltInProcesses() {
    }

    static String args(MetaDataMap args) {
        String a = args == null ? null : args.get("PROCESS_ARGS", String.class);
        return a == null ? "" : a.trim();
    }

    /** Does nothing; useful as a placeholder or to end a branch. */
    @Component(service = WorkflowProcess.class, property = "process.label=No Operation")
    public static class Noop implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {
            // nothing
        }
    }

    /** Writes the arguments (and the payload) to the log. */
    @Component(service = WorkflowProcess.class, property = "process.label=Log Message")
    public static class Log implements WorkflowProcess {
        private static final Logger LOG = LoggerFactory.getLogger("io.sensible.studio.workflow.log");

        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) {
            LOG.info("[{}] {} (payload {})", item.getWorkflow().getId(), args(args), item.getWorkflowData().getPayload());
        }
    }

    /**
     * Sets properties on the payload (on its {@code jcr:content} when it has one, as for pages and assets).
     * Arguments: {@code name=value} pairs, one per line or separated by commas; an empty value removes the property.
     */
    @Component(service = WorkflowProcess.class, property = "process.label=Set Payload Properties")
    public static class SetProperties implements WorkflowProcess {
        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            ResourceResolver resolver = session.adaptTo(ResourceResolver.class);
            Object payload = item.getWorkflowData().getPayload();
            Resource target = resolver == null || payload == null ? null : resolver.getResource(String.valueOf(payload));
            if (target == null) {
                throw new WorkflowException("The payload " + payload + " does not exist");
            }
            if (target.getChild("jcr:content") != null) {
                target = target.getChild("jcr:content");
            }
            ModifiableValueMap vm = target.adaptTo(ModifiableValueMap.class);
            if (vm == null) {
                throw new WorkflowException("Cannot modify " + target.getPath());
            }
            for (Map.Entry<String, String> e : pairs(args(args)).entrySet()) {
                if (e.getValue().isEmpty()) {
                    vm.remove(e.getKey());
                } else {
                    vm.put(e.getKey(), e.getValue());
                }
            }
        }

        static Map<String, String> pairs(String args) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            for (String part : args.split("[\\n,]")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    String name = part.substring(0, eq).trim();
                    if (!name.isEmpty() && !name.startsWith("jcr:primaryType")) {
                        out.put(name, part.substring(eq + 1).trim());
                    }
                }
            }
            return out;
        }
    }
}
