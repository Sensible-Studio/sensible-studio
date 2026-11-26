/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;

import com.day.cq.wcm.msm.api.LiveAction;
import com.day.cq.wcm.msm.api.LiveActionFactory;

/**
 * The built-in synchronisation actions as {@link LiveActionFactory} services. Their configuration PIDs are the ones
 * AEM projects already configure (for example in {@code ui.config}), with the same property names
 * ({@link SyncFilter}), so a project's exclusions apply unchanged. The defaults are AEM 6.5's out-of-the-box values,
 * observed on a running instance.
 */
public final class ActionFactories {

    private ActionFactories() {
    }

    static final String[] PAGE_NODE_TYPES = {"cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "cq:LiveSyncAction",
            "cq:CatalogSyncConfig", "cq:CatalogSyncAction", "cq:meta"};
    static final String[] STRUCTURE_NODE_TYPES = {"cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "cq:LiveSyncAction",
            "cq:meta"};
    static final String[] ALL_SYSTEM_PROPS = {"jcr:.*", "sling:.*", "cq:.*"};

    static final SyncFilter UPDATE = new SyncFilter(PAGE_NODE_TYPES,
            new String[] {"cq:propertyInheritanceCancelled", "dc:title", "cq:targetMetadata", "cq:targetMetadataJson"},
            new String[] {"jcr:.*", "sling:(?!(resourceType|resourceSuperType)$).*",
                    "cq:(?!(designPath|template|lastTranslationUpdate|targetEngine|robotsTags)$).*", "publishCampaignId"});
    static final SyncFilter COPY = new SyncFilter(
            new String[] {"cq:LiveSyncConfig", "cq:BlueprintSyncConfig", "cq:CatalogSyncConfig", "cq:meta"},
            new String[] {"cq:annotations", "cq:targetMetadata", "cq:targetMetadataJson"},
            new String[] {"[^.]", "cq:authorExternalId", "publishCampaignId", "cq:publishExternalId", "cq:xfMasterVariation",
                    "postId", "cq:xfSocialActionDate", "cq:xfSocialActionStatus", "cq:targetOfferId"});
    static final SyncFilter DELETE = new SyncFilter(PAGE_NODE_TYPES, new String[] {"cq:propertyInheritanceCancelled"},
            ALL_SYSTEM_PROPS);
    static final SyncFilter STRUCTURE = new SyncFilter(STRUCTURE_NODE_TYPES,
            new String[] {"cq:propertyInheritanceCancelled"}, ALL_SYSTEM_PROPS);

    /** Base: keeps the configured filter. */
    abstract static class Factory implements LiveActionFactory<LiveAction> {
        volatile SyncFilter filter = defaults();

        abstract SyncFilter defaults();

        @Activate
        @Modified
        void activate(Map<String, Object> config) {
            filter = SyncFilter.of(config, defaults());
        }

        abstract LiveAction create(SyncFilter f);

        @Override
        public LiveAction createAction(Resource config) {
            return create(filter);
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ContentUpdateActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=contentUpdate")
    public static class ContentUpdateFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return UPDATE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.ContentUpdate(f);
        }

        @Override
        public String createsAction() {
            return "contentUpdate";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ContentCopyActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=contentCopy")
    public static class ContentCopyFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return COPY;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.ContentCopy(f);
        }

        @Override
        public String createsAction() {
            return "contentCopy";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ContentDeleteActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=contentDelete")
    public static class ContentDeleteFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return DELETE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.ContentDelete(f);
        }

        @Override
        public String createsAction() {
            return "contentDelete";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.OrderChildrenActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=orderChildren")
    public static class OrderChildrenFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return STRUCTURE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.OrderChildren(f);
        }

        @Override
        public String createsAction() {
            return "orderChildren";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ReferencesUpdateActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=referencesUpdate")
    public static class ReferencesUpdateFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return STRUCTURE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.ReferencesUpdate(f);
        }

        @Override
        public String createsAction() {
            return "referencesUpdate";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ReferencesUpdateXFActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=referencesUpdateXF")
    public static class ReferencesUpdateXFFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return STRUCTURE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.LocalizedReferencesUpdate("referencesUpdateXF", XF_ROOT, f);
        }

        @Override
        public String createsAction() {
            return "referencesUpdateXF";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ReferencesUpdateXFEmbeddedActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=referencesUpdateXFEmbedded")
    public static class ReferencesUpdateXFEmbeddedFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return STRUCTURE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.LocalizedReferencesUpdate("referencesUpdateXFEmbedded", XF_ROOT, f);
        }

        @Override
        public String createsAction() {
            return "referencesUpdateXFEmbedded";
        }
    }

    @Component(service = LiveActionFactory.class,
            configurationPid = "com.day.cq.wcm.msm.impl.actions.ReferencesUpdateCFActionFactory",
            property = LiveActionFactory.LIVE_ACTION_NAME + "=referencesUpdateCF")
    public static class ReferencesUpdateCFFactory extends Factory {
        @Override
        SyncFilter defaults() {
            return STRUCTURE;
        }

        @Override
        LiveAction create(SyncFilter f) {
            return new SyncActions.LocalizedReferencesUpdate("referencesUpdateCF", DAM_ROOT, f);
        }

        @Override
        public String createsAction() {
            return "referencesUpdateCF";
        }
    }

    static final String XF_ROOT = "/content/experience-fragments/";
    static final String DAM_ROOT = "/content/dam/";

    /** The built-in actions without OSGi (tests, or before the factories are up), by name; {@code null} if unknown. */
    static LiveAction builtin(String name) {
        switch (name) {
            case "contentUpdate":
                return new SyncActions.ContentUpdate(UPDATE);
            case "contentCopy":
                return new SyncActions.ContentCopy(COPY);
            case "contentDelete":
                return new SyncActions.ContentDelete(DELETE);
            case "orderChildren":
                return new SyncActions.OrderChildren(STRUCTURE);
            case "referencesUpdate":
                return new SyncActions.ReferencesUpdate(STRUCTURE);
            case "referencesUpdateXF":
            case "referencesUpdateXFEmbedded":
                return new SyncActions.LocalizedReferencesUpdate(name, XF_ROOT, STRUCTURE);
            case "referencesUpdateCF":
                return new SyncActions.LocalizedReferencesUpdate(name, DAM_ROOT, STRUCTURE);
            case "targetVersion":
                return new SyncActions.TargetVersion();
            case "targetActivate":
            case "targetDeactivate":
            case "markLiveRelationship":
            case "personalizationContentRollout":
            case "updateField":
                return new SyncActions.Noop(name);
            default:
                return null;
        }
    }
}
