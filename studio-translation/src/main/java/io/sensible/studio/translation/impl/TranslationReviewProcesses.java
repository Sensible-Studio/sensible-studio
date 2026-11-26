package io.sensible.studio.translation.impl;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import io.sensible.studio.translation.api.ReferenceTranslator;
import io.sensible.studio.translation.api.Translator;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The process steps of the "Translation Review" workflow: the reviewer's decision (the participant step completed
 * before them) approves the translated copy, which feeds the translation memory, or asks for changes with the
 * reviewer's comment. The payload is the copy: a page, an experience fragment or a content fragment.
 */
final class TranslationReviewProcesses {

    private TranslationReviewProcesses() {
    }

    /** Who decided, and their comment: the last completed step before this one (the review). */
    static String[] decision(WorkItem item, ResourceResolver resolver) {
        String by = item.getWorkflow() == null ? null : item.getWorkflow().getInitiator();
        String comment = null;
        Resource current = resolver.getResource(item.getId());
        Calendar latest = null;
        if (current != null && current.getParent() != null) {
            for (Resource other : current.getParent().getChildren()) {
                if (other.getPath().equals(current.getPath())) continue;
                String completedBy = other.getValueMap().get("completedBy", String.class);
                Calendar ended = other.getValueMap().get("endTime", Calendar.class);
                if (completedBy == null || ended == null || (latest != null && ended.before(latest))) continue;
                latest = ended;
                by = completedBy;
                comment = other.getValueMap().get("comment", String.class);
            }
        }
        return new String[]{by, comment};
    }

    static String payload(WorkItem item) throws WorkflowException {
        Object p = item.getWorkflowData() == null ? null : item.getWorkflowData().getPayload();
        if (p == null) throw new WorkflowException("The workflow has no payload");
        return String.valueOf(p);
    }

    /** Shared references of both steps. */
    abstract static class Base implements WorkflowProcess {
        @Reference
        volatile Translator translator;

        @Reference
        volatile PageTranslationRules rules;

        @Reference
        volatile ResourceResolverFactory resolverFactory;

        final List<ReferenceTranslator> referenceTranslators = new CopyOnWriteArrayList<>();

        @Reference(service = ReferenceTranslator.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
        void bindReferenceTranslator(ReferenceTranslator t) {
            referenceTranslators.add(t);
        }

        void unbindReferenceTranslator(ReferenceTranslator t) {
            referenceTranslators.remove(t);
        }

        ResourceResolver service() {
            try {
                return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) "translation"));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void execute(WorkItem item, WorkflowSession session, MetaDataMap args) throws WorkflowException {
            ResourceResolver resolver = session.adaptTo(ResourceResolver.class);
            if (resolver == null) throw new WorkflowException("No session");
            String payload = payload(item);
            String[] decision = decision(item, resolver);
            ResourceResolver service = service();
            try {
                PageTranslation pages = new PageTranslation(resolver, translator, rules, referenceTranslators).records(service);
                ResourceResolver records = service == null ? resolver : service;
                boolean page = records.getResource(PageTranslation.RECORDS + payload) != null;
                run(pages, page, resolver, payload, decision[0], decision[1]);
                resolver.commit();
            } catch (WorkflowException e) {
                throw e;
            } catch (Exception e) {
                throw new WorkflowException("Could not record the review of " + payload + ": " + e.getMessage(), e);
            } finally {
                if (service != null) service.close();
            }
        }

        abstract void run(PageTranslation pages, boolean page, ResourceResolver resolver, String payload, String by, String comment) throws Exception;
    }

    @Component(service = WorkflowProcess.class, property = "process.label=Approve Translation")
    public static class Approve extends Base {
        @Override
        void run(PageTranslation pages, boolean page, ResourceResolver resolver, String payload, String by, String comment) throws Exception {
            if (page) {
                pages.approve(payload, by);
                return;
            }
            for (ReferenceTranslator t : referenceTranslators) {
                if (t.approve(resolver, payload, by)) return;
            }
            throw new WorkflowException(payload + " is not a language copy Sensible Studio translated");
        }
    }

    @Component(service = WorkflowProcess.class, property = "process.label=Request Translation Changes")
    public static class RequestChanges extends Base {
        @Override
        void run(PageTranslation pages, boolean page, ResourceResolver resolver, String payload, String by, String comment) throws Exception {
            if (page) {
                pages.requestChanges(payload, by, comment);
                return;
            }
            // a content fragment keeps its record in itself
            Resource record = resolver.getResource(payload + "/jcr:content/translation");
            if (record == null || record.getValueMap().get("sourcePath") == null) {
                throw new WorkflowException(payload + " is not a language copy Sensible Studio translated");
            }
            ModifiableValueMap vm = record.adaptTo(ModifiableValueMap.class);
            vm.put("status", "CHANGES");
            vm.put("changesRequestedAt", Calendar.getInstance());
            vm.put("changesRequestedBy", by);
            if (comment != null && !comment.trim().isEmpty()) vm.put("reviewComment", comment.trim());
            else vm.remove("reviewComment");
            vm.remove("approvedAt");
            vm.remove("approvedBy");
        }
    }
}
