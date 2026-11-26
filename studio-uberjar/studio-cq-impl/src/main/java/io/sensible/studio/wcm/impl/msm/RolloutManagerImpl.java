/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.msm;

import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_COPY;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_RELATIONSHIP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_LIVE_SYNC_CONFIG;
import static com.day.cq.wcm.msm.api.MSMNameConstants.NT_PROPERTY_LIVE_SYNC_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_EXCLUDED_PATHS;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_CANCELLED_FOR_CHILDREN;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_IS_DEEP;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_MASTER;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_PROPERTY_INHERITANCE_CANCELLED;
import static com.day.cq.wcm.msm.api.MSMNameConstants.PN_ROLLOUT_CONFIGS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.WCMException;
import com.day.cq.wcm.msm.api.LiveAction;
import com.day.cq.wcm.msm.api.LiveActionFactory;
import com.day.cq.wcm.msm.api.LiveRelationship;
import com.day.cq.wcm.msm.api.LiveRelationshipManager;
import com.day.cq.wcm.msm.api.RolloutConfig;
import com.day.cq.wcm.msm.api.RolloutConfigManager;
import com.day.cq.wcm.msm.api.RolloutManager;

/**
 * Rolls blueprint pages out to their live copies.
 *
 * <p>For each live copy of the page (filtered by trigger and targets), the actions of its rollout configurations run
 * on every source / target pair of the page content tree, parents first; {@code orderChildren} and
 * {@code referencesUpdate} run after the children. Then, for a deep rollout, the child pages follow in blueprint order:
 * missing ones are created, those whose source is gone are deleted (if they were rolled out and are not suspended),
 * local pages stay.
 *
 * <p>Rules that keep authors' work in the live copy:
 * <ul>
 *   <li>a node without the {@code cq:LiveRelationship} mixin was added locally and is never changed or deleted;</li>
 *   <li>a node with {@code cq:LiveSyncCancelled} is skipped, with its descendants;</li>
 *   <li>properties and child nodes listed in {@code cq:propertyInheritanceCancelled} keep their local values;</li>
 *   <li>a suspended page (its content node cancelled) is skipped, its child pages too if cancelled for children;</li>
 *   <li>a local page in the way of a new blueprint page is renamed to {@code <name>_msm_moved} (conflict handling,
 *       on by default as in AEM); with conflict handling off it stays and the blueprint page is left out.</li>
 * </ul>
 * A reset rollout first removes the cancellations of the targets it reaches.
 */
// AEM's configuration PID, so a project's OSGi configuration of the rollout manager applies unchanged
@Component(service = {RolloutManager.class, RolloutManagerImpl.class},
        configurationPid = "com.day.cq.wcm.msm.impl.RolloutManagerImpl")
@Designate(ocd = RolloutManagerImpl.Config.class)
public class RolloutManagerImpl implements RolloutManager {

    @ObjectClassDefinition(name = "Sensible Studio MSM Rollout Manager",
            description = "Rollouts from blueprints to live copies.")
    public @interface Config {
        @AttributeDefinition(name = "Handle conflicts with local pages",
                description = "Rename a local page that has the name of a new blueprint page to <name>_msm_moved "
                        + "instead of leaving the blueprint page out.")
        boolean rolloutmgr_conflicthandling_enabled() default true;
    }

    /** Actions that need the children of the pair in place. */
    static final Set<String> AFTER_CHILDREN = new HashSet<>(Arrays.asList("orderChildren", "referencesUpdate",
            "referencesUpdateXF", "referencesUpdateXFEmbedded", "referencesUpdateCF"));
    static final String MOVED_SUFFIX = "_msm_moved";

    private static final Logger LOG = LoggerFactory.getLogger(RolloutManagerImpl.class);

    @Reference
    volatile LiveRelationshipManager relationshipManager;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    volatile RolloutConfigManager configManager;

    private final Map<String, LiveActionFactory<?>> factories = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    volatile boolean conflictHandling;

    @Activate
    @Modified
    void activate(Config config) {
        conflictHandling = config.rolloutmgr_conflicthandling_enabled();
    }

    @Reference(service = LiveActionFactory.class, cardinality = ReferenceCardinality.MULTIPLE,
            policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    void bindFactory(LiveActionFactory<?> factory, Map<String, Object> props) {
        String name = name(factory, props);
        if (name != null) {
            factories.put(name, factory);
        }
    }

    void unbindFactory(LiveActionFactory<?> factory, Map<String, Object> props) {
        String name = name(factory, props);
        if (name != null) {
            factories.remove(name, factory);
        }
    }

    private static String name(LiveActionFactory<?> factory, Map<String, Object> props) {
        Object n = props == null ? null : props.get(LiveActionFactory.LIVE_ACTION_NAME);
        if (n instanceof String[] && ((String[]) n).length > 0) {
            return ((String[]) n)[0];
        }
        return n instanceof String ? (String) n : factory.createsAction();
    }

    // ------------------------------------------------------------------ options

    /** How to roll out. */
    public static final class Options {
        /** also child pages */
        public boolean deep;
        /** only these live copy target pages (and, deep, their descendants); {@code null}: all */
        public Set<String> targets;
        /** only live copies with a configuration for this trigger and only those configurations; {@code null}: all */
        public Trigger trigger = Trigger.ROLLOUT;
        /** remove cancellations on the way */
        public boolean reset;
        public RolloutProgressListener listener;

        public static Options of(boolean deep, Trigger trigger) {
            Options o = new Options();
            o.deep = deep;
            o.trigger = trigger;
            return o;
        }
    }

    // ------------------------------------------------------------------ API

    @Override
    public void rollout(RolloutParams params) throws WCMException {
        if (params == null || params.master == null) {
            throw new WCMException("No source page to sync");
        }
        Resource master = params.master.adaptTo(Resource.class);
        if (master == null) {
            throw new WCMException("No source page to sync");
        }
        Options o = Options.of(params.isDeep, params.trigger);
        o.reset = params.reset;
        o.listener = params.listener;
        if (params.targets != null) {
            o.targets = new LinkedHashSet<>(Arrays.asList(params.targets));
        }
        ResourceResolver resolver = master.getResourceResolver();
        rollout(resolver, params.master.getPath(), o);
        Msm.commit(resolver);
    }

    @Override
    public boolean isExcludedProperty(String propertyName) {
        return SyncFilter.isInternal(propertyName);
    }

    /**
     * Rolls a blueprint page out to its live copies. The page may have been deleted (its live copies lose their
     * rolled out copies). A path inside a page's content rolls out that component only. Nothing is committed.
     */
    public RolloutResult rollout(ResourceResolver resolver, String sourcePage, Options o) throws WCMException {
        RolloutResult result = new RolloutResult();
        Relationships rels = new Relationships(resolver, configManager);
        if (Msm.isInPageContent(sourcePage)) {
            // AEM's component rollout: just this component (and what is below it) goes to every live copy of its page
            String component = sourcePage;
            for (LiveRelationshipImpl rel : relationshipsOf(resolver, component)) {
                if (o.targets != null && !matchesTargets(rel.getTargetPath(), o)) {
                    continue;
                }
                List<LiveAction> actions = actions(resolver, rel.liveCopy(), o.trigger, result);
                if (actions.isEmpty()) {
                    continue;
                }
                if (names(actions).contains(SyncActions.TARGET_VERSION)) {
                    version(resolver, new LinkedHashSet<>(java.util.Collections.singleton(Msm.pagePathOf(rel.getTargetPath()))),
                            "Before rollout of " + component, result);
                }
                syncNode(rels, rel.liveCopy(), component, rel.getTargetPath(), actions, o, result, true);
                String page = Msm.pagePathOf(rel.getTargetPath());
                if (resolver.getResource(page + Msm.CONTENT_SEGMENT) != null) {
                    Msm.stamp(resolver.getResource(page + Msm.CONTENT_SEGMENT), Calendar.getInstance(), SyncActions.user(resolver));
                }
                result.updated.add(page);
            }
            return result;
        }
        List<LiveRelationshipImpl> todo = new ArrayList<>();
        Map<LiveRelationshipImpl, List<LiveAction>> actionsOf = new HashMap<>();
        for (LiveRelationshipImpl rel : relationshipsOf(resolver, sourcePage)) {
            if (o.targets != null && !matchesTargets(rel.getTargetPath(), o)) {
                continue;
            }
            if (Msm.overlaps(rel.getTargetPath(), sourcePage) || Msm.overlaps(rel.liveCopy().getPath(), rel.liveCopy().getBlueprintPath())) {
                // a live copy inside its own blueprint (or around it) would copy itself into itself, again and again
                LOG.warn("Not rolling {} out to {}: the live copy and its blueprint overlap", sourcePage, rel.getTargetPath());
                result.skipped.add(rel.getTargetPath());
                continue;
            }
            List<LiveAction> actions = actions(resolver, rel.liveCopy(), o.trigger, result);
            if (!actions.isEmpty()) {
                todo.add(rel);
                actionsOf.put(rel, actions);
            }
        }
        // versions first, all of them, before anything changes: a version is taken from saved content, so taking them
        // during the rollout would commit half of it, which a failure could then no longer undo
        Set<String> toVersion = new LinkedHashSet<>();
        for (LiveRelationshipImpl rel : todo) {
            if (names(actionsOf.get(rel)).contains(SyncActions.TARGET_VERSION)) {
                pagesToVersion(resolver, rel.liveCopy(), rel.getTargetPath(), o, toVersion);
            }
        }
        version(resolver, toVersion, "Before rollout from " + sourcePage, result);
        for (LiveRelationshipImpl rel : todo) {
            rolloutPage(rels, rel.liveCopy(), sourcePage, rel.getTargetPath(), actionsOf.get(rel), o, result);
        }
        return result;
    }

    /**
     * The rolled out pages a rollout to {@code targetPath} may change: the page and, deep, its descendants in the same
     * live copy; not those suspended (unless the rollout resets them), not local pages, not nested live copies.
     */
    private void pagesToVersion(ResourceResolver resolver, LiveCopyImpl lc, String targetPath, Options o, Set<String> out) {
        Resource page = resolver.getResource(targetPath);
        if (page == null || !lc.covers(targetPath)
                || (!targetPath.equals(lc.getPath()) && isOtherLiveCopyRoot(resolver, targetPath))) {
            return;
        }
        Resource content = page.getChild(Msm.JCR_CONTENT);
        if (content == null || !Msm.hasMixin(content, NT_LIVE_RELATIONSHIP)) {
            return; // local, or not rolled out yet: nothing of it to lose
        }
        boolean cancelled = Msm.hasMixin(content, NT_LIVE_SYNC_CANCELLED) && !o.reset;
        if (!cancelled) {
            out.add(targetPath);
        }
        if (!o.deep || (cancelled && Boolean.TRUE.equals(content.getValueMap().get(PN_IS_CANCELLED_FOR_CHILDREN, Boolean.class)))) {
            return;
        }
        for (Resource child : page.getChildren()) {
            if (Msm.isPage(child)) {
                pagesToVersion(resolver, lc, child.getPath(), o, out);
            }
        }
    }

    private static void version(ResourceResolver resolver, Set<String> pages, String comment, RolloutResult result)
            throws WCMException {
        if (pages.isEmpty() || resolver.adaptTo(Session.class) == null) {
            return; // no JCR, no versions
        }
        if (resolver.hasChanges()) {
            throw new WCMException("Save the pending changes before a sync that versions its linked copies");
        }
        io.sensible.studio.wcm.impl.PageRevisions revisions = new io.sensible.studio.wcm.impl.PageRevisions(resolver);
        for (String page : pages) {
            revisions.create(page, null, comment);
            result.versioned.add(page);
        }
    }

    private static boolean matchesTargets(String target, Options o) {
        for (String t : o.targets) {
            if (target.equals(t) || (o.deep && target.startsWith(t + "/"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Synchronises a live copy target (a page, or a node of page content) from its source with the live copy's
     * rollout configurations, whatever their trigger. Nothing is committed.
     */
    public RolloutResult synchronize(ResourceResolver resolver, String targetPath, boolean deep, boolean reset)
            throws WCMException {
        Relationships rels = new Relationships(resolver, configManager);
        LiveRelationshipImpl rel = rels.relationshipOf(targetPath);
        if (rel == null) {
            throw new WCMException(targetPath + " is not in a linked copy");
        }
        Resource existing = resolver.getResource(targetPath);
        Resource marker = existing == null || Msm.isInPageContent(targetPath) ? existing : existing.getChild(Msm.JCR_CONTENT);
        if (existing != null && (marker == null || !Msm.hasMixin(marker, NT_LIVE_RELATIONSHIP))) {
            // local content: synchronising would replace it (or move a local page aside)
            throw new WCMException(targetPath + " was not synced from the source");
        }
        RolloutResult result = new RolloutResult();
        Options o = Options.of(deep, null);
        o.reset = reset;
        List<LiveAction> actions = actions(resolver, rel.liveCopy(), null, result);
        if (names(actions).contains(SyncActions.TARGET_VERSION)) {
            Set<String> toVersion = new LinkedHashSet<>();
            pagesToVersion(resolver, rel.liveCopy(), Msm.pagePathOf(targetPath), Options.of(deep && !Msm.isInPageContent(targetPath), null), toVersion);
            version(resolver, toVersion, "Before synchronizing from " + Msm.pagePathOf(rel.getSourcePath()), result);
        }
        if (Msm.isInPageContent(targetPath)) {
            syncNode(rels, rel.liveCopy(), rel.getSourcePath(), targetPath, actions, o, result, true);
            String page = Msm.pagePathOf(targetPath);
            if (resolver.getResource(page + Msm.CONTENT_SEGMENT) != null) {
                Msm.stamp(resolver.getResource(page + Msm.CONTENT_SEGMENT), Calendar.getInstance(), SyncActions.user(resolver));
            }
            result.updated.add(page);
        } else {
            rolloutPage(rels, rel.liveCopy(), rel.getSourcePath(), targetPath, actions, o, result);
        }
        return result;
    }

    /**
     * Creates a live copy of {@code sourcePage} at {@code parent/name} and rolls the blueprint out to it with all its
     * rollout configurations. Nothing is committed.
     *
     * @param rolloutConfigs configuration paths ({@code null} or empty: inherit, i.e. the default configuration)
     * @param excluded child pages, relative to the new root, left out
     */
    public RolloutResult createLiveCopy(ResourceResolver resolver, String sourcePage, String parent, String name,
            String title, boolean deep, String[] rolloutConfigs, String[] excluded) throws WCMException {
        if (Msm.contentPath(sourcePage) == null || Msm.contentPath(parent) == null) {
            throw new WCMException("Source and parent must be pages or folders below /content");
        }
        if (!Msm.isValidName(name)) {
            throw new WCMException("Invalid page name: " + name);
        }
        Resource source = resolver.getResource(sourcePage);
        if (!Msm.isPage(source)) {
            throw new WCMException("No page at " + sourcePage);
        }
        Resource parentRes = resolver.getResource(parent);
        if (parentRes == null) {
            throw new WCMException("No parent at " + parent);
        }
        String target = ("/".equals(parent) ? "" : parent) + "/" + name;
        if (resolver.getResource(target) != null) {
            throw new WCMException(target + " already exists");
        }
        if (Msm.overlaps(target, sourcePage)) {
            throw new WCMException("A linked copy cannot be created inside its own source");
        }
        if (inBlueprintOfAncestor(resolver, target, sourcePage)) {
            throw new WCMException("The source is itself synced from a tree containing " + parent
                    + ": the live copy would copy itself");
        }
        String[] configs = rolloutConfigs == null ? new String[0] : rolloutConfigs;
        Relationships rels = new Relationships(resolver, configManager);
        List<RolloutConfig> resolved = new ArrayList<>();
        for (String c : configs.length == 0 ? new String[] {RolloutConfigManager.DEFAULT_CONFIG} : configs) {
            RolloutConfig rc = configManager == null ? null : configManager.getRolloutConfig(resolver, c);
            if (rc == null && configs.length > 0) {
                throw new WCMException("No sync rule at " + c);
            }
            if (rc != null) {
                resolved.add(rc);
            }
        }
        LiveCopyImpl lc = new LiveCopyImpl(target, sourcePage, deep, true,
                excluded == null ? new String[0] : excluded, () -> resolved);
        RolloutResult result = new RolloutResult();
        List<LiveAction> actions = actions(resolver, lc, null, result);
        Set<String> names = new HashSet<>();
        actions.forEach(a -> names.add(a.getName()));
        if (!names.contains("contentCopy")) {
            // the configurations only react to events: the first copy still needs the content
            for (String n : new String[] {"contentCopy", "contentUpdate", "orderChildren", "referencesUpdate"}) {
                if (!names.contains(n)) {
                    actions.add(action(resolver, n, null, result));
                }
            }
            actions.removeIf(a -> a == null);
        }
        Options o = Options.of(deep, null);
        rolloutPage(rels, lc, sourcePage, target, actions, o, result);
        Resource content = resolver.getResource(target + Msm.CONTENT_SEGMENT);
        if (content == null) {
            Map<String, Object> props = new HashMap<>();
            props.put(Msm.PRIMARY, "cq:PageContent");
            content = Msm.createNode(resolver, resolver.getResource(target), Msm.JCR_CONTENT, props);
        }
        Msm.addMixin(content, NT_LIVE_RELATIONSHIP);
        Msm.addMixin(content, NT_LIVE_SYNC);
        if (title != null && !title.trim().isEmpty()) {
            Msm.writable(content).put("jcr:title", title.trim());
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put(Msm.PRIMARY, NT_LIVE_COPY);
        cfg.put(PN_MASTER, sourcePage);
        cfg.put(PN_IS_DEEP, deep);
        if (configs.length > 0) {
            cfg.put(PN_ROLLOUT_CONFIGS, configs);
        }
        if (excluded != null && excluded.length > 0) {
            cfg.put(PN_EXCLUDED_PATHS, excluded);
        }
        Msm.createNode(resolver, content, NT_LIVE_SYNC_CONFIG, cfg);
        if (relationshipManager instanceof LiveRelationshipManagerImpl) {
            ((LiveRelationshipManagerImpl) relationshipManager).index.invalidate();
        }
        return result;
    }

    // ------------------------------------------------------------------ engine

    private List<LiveRelationshipImpl> relationshipsOf(ResourceResolver resolver, String sourcePage) {
        if (relationshipManager instanceof LiveRelationshipManagerImpl) {
            return ((LiveRelationshipManagerImpl) relationshipManager).relationshipsOf(resolver, sourcePage);
        }
        List<LiveRelationshipImpl> out = new ArrayList<>();
        Resource source = resolver.getResource(sourcePage);
        if (source == null) {
            source = new NonExistingResource(resolver, sourcePage);
        }
        javax.jcr.RangeIterator it = relationshipManager.getLiveRelationships(source, null, null);
        Relationships rels = new Relationships(resolver, configManager);
        while (it.hasNext()) {
            LiveRelationship r = (LiveRelationship) it.next();
            LiveRelationshipImpl impl = rels.relationshipOf(r.getTargetPath());
            if (impl != null) {
                out.add(impl);
            }
        }
        return out;
    }

    /** @return the actions of the live copy's configurations for {@code trigger} ({@code null}: all), in order, once each */
    List<LiveAction> actions(ResourceResolver resolver, LiveCopyImpl lc, Trigger trigger, RolloutResult result) {
        Map<String, LiveAction> byName = new LinkedHashMap<>();
        for (RolloutConfig c : lc.getRolloutConfigs()) {
            if (trigger != null && c.getTrigger() != trigger) {
                continue;
            }
            List<String> names = c.getActionNames();
            List<String> paths = c instanceof RolloutConfigImpl ? ((RolloutConfigImpl) c).getActionPaths() : null;
            for (int i = 0; i < names.size(); i++) {
                String n = names.get(i);
                if (!byName.containsKey(n)) {
                    LiveAction a = action(resolver, n, paths == null ? null : paths.get(i), result);
                    if (a != null) {
                        byName.put(n, a);
                    }
                }
            }
        }
        List<LiveAction> out = new ArrayList<>(byName.values());
        // a stable, safe order whatever the order of the configuration nodes (which the repository may not keep):
        // the version first, then creation, update and deletion, other (project) actions, link rewriting last
        out.sort(java.util.Comparator.comparingInt(x -> rank(x.getName())));
        return out;
    }

    private static int rank(String name) {
        switch (name) {
            case "targetVersion":
                return 0;
            case "contentCopy":
                return 10;
            case "contentUpdate":
                return 20;
            case "contentDelete":
                return 30;
            default:
                return AFTER_CHILDREN.contains(name) ? 90 : 50;
        }
    }

    private LiveAction action(ResourceResolver resolver, String name, String configPath, RolloutResult result) {
        LiveActionFactory<?> f = factories.get(name);
        try {
            if (f != null) {
                return f.createAction(configPath == null ? null : resolver.getResource(configPath));
            }
        } catch (WCMException e) {
            LOG.warn("Could not create MSM action {}: {}", name, e.getMessage());
            return null;
        }
        LiveAction a = ActionFactories.builtin(name);
        if (a == null) {
            if (!result.unknownActions.contains(name)) {
                result.unknownActions.add(name);
            }
            if (warned.add(name)) {
                LOG.warn("Rollout configurations use the action {}, which Sensible Studio does not know; it is skipped", name);
            }
        }
        return a;
    }

    private void rolloutPage(Relationships rels, LiveCopyImpl lc, String sourcePath, String targetPath,
            List<LiveAction> actions, Options o, RolloutResult result) throws WCMException {
        ResourceResolver resolver = rels.resolver();
        if (!lc.covers(targetPath)) {
            result.skipped.add(targetPath);
            return;
        }
        if (!targetPath.equals(lc.getPath()) && isOtherLiveCopyRoot(resolver, targetPath)) {
            return; // a nested live copy, rolled out from its own blueprint
        }
        Set<String> names = names(actions);
        Resource source = resolver.getResource(sourcePath);
        Resource target = resolver.getResource(targetPath);
        LiveStatusImpl status = rels.status(lc, sourcePath, targetPath);

        if (source == null) {
            if (target != null && status.related && (!status.cancelled || o.reset) && names.contains("contentDelete")
                    && !targetPath.equals(lc.getPath()) && !hasLocalContent(target)) {
                delete(resolver, target);
                result.deleted.add(targetPath);
                progress(o, "deleted " + targetPath);
            }
            return;
        }
        if (!Msm.isPage(source)) {
            return;
        }
        if (target == null) {
            if (!names.contains("contentCopy")) {
                return;
            }
            if (status.cancelled && !o.reset) {
                result.skipped.add(targetPath); // below a page suspended for its children
                return;
            }
            Resource parent = resolver.getResource(Msm.parentOf(targetPath));
            if (parent == null) {
                result.skipped.add(targetPath);
                return;
            }
            Map<String, Object> props = new HashMap<>();
            props.put(Msm.PRIMARY, Msm.NT_PAGE);
            target = Msm.createNode(resolver, parent, Msm.nameOf(targetPath), props);
            result.created.add(targetPath);
            progress(o, "created " + targetPath);
        } else if (!targetPath.equals(lc.getPath()) && !status.related && target.getChild(Msm.JCR_CONTENT) != null) {
            if (!conflictHandling) {
                result.conflicts.add(targetPath);
                return;
            }
            String moved = rename(resolver, target, Msm.nameOf(targetPath) + MOVED_SUFFIX);
            if (moved == null) {
                result.conflicts.add(targetPath);
                return;
            }
            result.renamed.add(moved);
            Map<String, Object> props = new HashMap<>();
            props.put(Msm.PRIMARY, Msm.NT_PAGE);
            target = Msm.createNode(resolver, resolver.getResource(Msm.parentOf(targetPath)), Msm.nameOf(targetPath), props);
            result.created.add(targetPath);
        }

        boolean skipChildren = false;
        if (status.cancelled) {
            if (o.reset) {
                Resource content = target.getChild(Msm.JCR_CONTENT);
                Msm.removeMixin(content, NT_LIVE_SYNC_CANCELLED);
                Msm.writable(content).remove(PN_IS_CANCELLED_FOR_CHILDREN);
            } else {
                result.skipped.add(targetPath);
                skipChildren = status.cancelledForChildren;
                if (skipChildren || !o.deep) {
                    return;
                }
            }
        }
        if (!result.skipped.contains(targetPath)) {
            if (source.getChild(Msm.JCR_CONTENT) != null) {
                syncNode(rels, lc, sourcePath + Msm.CONTENT_SEGMENT, targetPath + Msm.CONTENT_SEGMENT, actions, o, result, false);
                Resource content = resolver.getResource(targetPath + Msm.CONTENT_SEGMENT);
                if (content != null) {
                    Msm.stamp(content, Calendar.getInstance(), SyncActions.user(resolver));
                }
            }
            if (!result.created.contains(targetPath)) {
                result.updated.add(targetPath);
            }
            progress(o, "rolled out " + sourcePath + " to " + targetPath);
        }
        if (!o.deep || skipChildren) {
            return;
        }
        List<String> sourceChildren = new ArrayList<>();
        for (Resource child : source.getChildren()) {
            if (Msm.isPage(child)) {
                sourceChildren.add(child.getName());
                if (isLiveCopyOf(resolver, child.getPath(), lc.getPath())) {
                    continue; // a live copy of this live copy inside the blueprint: rolling it out would loop
                }
                rolloutPage(rels, lc, child.getPath(), targetPath + "/" + child.getName(), actions, o, result);
            }
        }
        target = resolver.getResource(targetPath);
        if (target == null) {
            return;
        }
        for (Resource child : new ArrayList<>(toList(target.getChildren()))) {
            if (Msm.isPage(child) && !sourceChildren.contains(child.getName())) {
                rolloutPage(rels, lc, sourcePath + "/" + child.getName(), child.getPath(), actions, o, result);
            }
        }
        if (names.contains("orderChildren")) {
            Resource s = resolver.getResource(sourcePath);
            Resource t = resolver.getResource(targetPath);
            LiveAction order = actions.stream().filter(a -> "orderChildren".equals(a.getName())).findFirst().orElse(null);
            if (order != null && s != null && t != null) {
                order.execute(s, t, new LiveRelationshipImpl(lc, sourcePath, targetPath, rels), false, o.reset);
            }
        }
    }

    /**
     * Synchronises one node pair and its children.
     *
     * @param single synchronise this node even if it is cancelled (the caller re-enabled it)
     */
    private void syncNode(Relationships rels, LiveCopyImpl lc, String sourcePath, String targetPath,
            List<LiveAction> actions, Options o, RolloutResult result, boolean single) throws WCMException {
        ResourceResolver resolver = rels.resolver();
        Resource source = resolver.getResource(sourcePath);
        Resource target = resolver.getResource(targetPath);
        if (source == null && target == null) {
            return;
        }
        if (target != null) {
            if (Msm.hasMixin(target, NT_LIVE_SYNC_CANCELLED)) {
                if (!o.reset) {
                    return;
                }
                Msm.removeMixin(target, NT_LIVE_SYNC_CANCELLED);
                Msm.writable(target).remove(PN_IS_CANCELLED_FOR_CHILDREN);
            }
            if (o.reset && Msm.hasMixin(target, NT_PROPERTY_LIVE_SYNC_CANCELLED)) {
                Msm.removeMixin(target, NT_PROPERTY_LIVE_SYNC_CANCELLED);
                Msm.writable(target).remove(PN_PROPERTY_INHERITANCE_CANCELLED);
            }
            if (!Msm.isPageContentNode(targetPath) && !Msm.hasMixin(target, NT_LIVE_RELATIONSHIP)) {
                return; // local
            }
        }
        if (source != null && SyncFilter.INTERNAL_NODES.contains(source.getName())) {
            return;
        }
        LiveRelationshipImpl rel = new LiveRelationshipImpl(lc, sourcePath, targetPath, rels);
        Resource s = source != null ? source : new NonExistingResource(resolver, sourcePath);
        Resource t = target != null ? target : new NonExistingResource(resolver, targetPath);
        for (LiveAction a : actions) {
            if (!AFTER_CHILDREN.contains(a.getName())) {
                a.execute(s, t, rel, false, o.reset);
                t = refreshed(resolver, targetPath);
            }
        }
        result.nodes++;
        target = resolver.getResource(targetPath);
        if (target == null || source == null) {
            return;
        }
        Set<String> cancelledNames = new HashSet<>(Arrays.asList(Msm.strings(target.getValueMap(), PN_PROPERTY_INHERITANCE_CANCELLED)));
        List<String> seen = new ArrayList<>();
        for (Resource child : source.getChildren()) {
            String n = child.getName();
            seen.add(n);
            if (!cancelledNames.contains(n) && !SyncFilter.INTERNAL_NODES.contains(n)) {
                syncNode(rels, lc, child.getPath(), targetPath + "/" + n, actions, o, result, false);
            }
        }
        for (Resource child : new ArrayList<>(toList(target.getChildren()))) {
            String n = child.getName();
            if (!seen.contains(n) && !cancelledNames.contains(n) && !SyncFilter.INTERNAL_NODES.contains(n)) {
                syncNode(rels, lc, sourcePath + "/" + n, child.getPath(), actions, o, result, false);
            }
        }
        Resource s2 = resolver.getResource(sourcePath);
        Resource t2 = resolver.getResource(targetPath);
        for (LiveAction a : actions) {
            if (AFTER_CHILDREN.contains(a.getName()) && s2 != null && t2 != null) {
                a.execute(s2, t2, rel, false, o.reset);
            }
        }
    }

    private static Resource refreshed(ResourceResolver resolver, String path) {
        Resource r = resolver.getResource(path);
        return r != null ? r : new NonExistingResource(resolver, path);
    }

    private static Set<String> names(List<LiveAction> actions) {
        Set<String> out = new HashSet<>();
        for (LiveAction a : actions) {
            out.add(a.getName());
        }
        return out;
    }

    static List<Resource> childrenOf(Resource node) {
        return new ArrayList<>(toList(node.getChildren()));
    }

    private static List<Resource> toList(Iterable<Resource> it) {
        List<Resource> out = new ArrayList<>();
        it.forEach(out::add);
        return out;
    }

    private static boolean isOtherLiveCopyRoot(ResourceResolver resolver, String pagePath) {
        Resource cfg = resolver.getResource(Msm.configPath(pagePath));
        return cfg != null && cfg.getValueMap().get(PN_MASTER, String.class) != null;
    }

    /** @return {@code true} if the page at {@code pagePath} is the root of a live copy whose blueprint overlaps {@code tree} */
    private static boolean isLiveCopyOf(ResourceResolver resolver, String pagePath, String tree) {
        Resource cfg = resolver.getResource(Msm.configPath(pagePath));
        String master = cfg == null ? null : cfg.getValueMap().get(PN_MASTER, String.class);
        return master != null && Msm.overlaps(master, tree);
    }

    /**
     * @return {@code true} if a new live copy at {@code target} would sit inside a live copy (or the blueprint chain) that
     *         {@code sourcePage} itself comes from: walks the blueprints of the source upwards
     */
    private static boolean inBlueprintOfAncestor(ResourceResolver resolver, String target, String sourcePage) {
        Set<String> seen = new HashSet<>();
        String bp = sourcePage;
        while (bp != null && seen.add(bp)) {
            String master = null;
            for (String p = bp; p.startsWith("/content/"); p = Msm.parentOf(p)) {
                Resource cfg = resolver.getResource(Msm.configPath(p));
                if (cfg != null && cfg.getValueMap().get(PN_MASTER, String.class) != null) {
                    master = cfg.getValueMap().get(PN_MASTER, String.class) + bp.substring(p.length());
                    break;
                }
            }
            if (master != null && Msm.overlaps(target, master)) {
                return true;
            }
            bp = master;
        }
        return false;
    }

    /** @return {@code true} if the page or a page below it holds content that was not rolled out (local) */
    private static boolean hasLocalContent(Resource page) {
        Resource content = page.getChild(Msm.JCR_CONTENT);
        if (content != null && hasLocalNodes(content)) {
            return true;
        }
        for (Resource child : page.getChildren()) {
            if (Msm.isPage(child)) {
                Resource c = child.getChild(Msm.JCR_CONTENT);
                if (c == null || !Msm.hasMixin(c, NT_LIVE_RELATIONSHIP) || hasLocalContent(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** @return {@code true} if a component below {@code node} was added in the live copy */
    static boolean hasLocalNodes(Resource node) {
        for (Resource child : node.getChildren()) {
            if (SyncFilter.INTERNAL_NODES.contains(child.getName())) {
                continue;
            }
            if (Msm.isComponent(child) && !Msm.hasMixin(child, NT_LIVE_RELATIONSHIP)) {
                return true;
            }
            if (hasLocalNodes(child)) {
                return true;
            }
        }
        return false;
    }

    private static void delete(ResourceResolver resolver, Resource r) throws WCMException {
        try {
            resolver.delete(r);
        } catch (PersistenceException e) {
            throw new WCMException("Could not delete " + r.getPath(), e);
        }
    }

    /** @return the new path, or {@code null} if the repository cannot rename */
    private static String rename(ResourceResolver resolver, Resource page, String newName) throws WCMException {
        String parent = Msm.parentOf(page.getPath());
        String name = newName;
        for (int i = 1; resolver.getResource(parent + "/" + name) != null; i++) {
            name = newName + i;
        }
        String dest = parent + "/" + name;
        Session session = resolver.adaptTo(Session.class);
        try {
            if (session != null) {
                session.move(page.getPath(), dest);
            } else {
                // no JCR (resource-only resolvers): copy the tree under the new name, then remove the original
                copyTree(resolver, page, resolver.getResource(parent), name);
                resolver.delete(page);
            }
            return dest;
        } catch (RepositoryException | PersistenceException e) {
            throw new WCMException("Could not move " + page.getPath() + " to " + dest, e);
        }
    }

    private static void copyTree(ResourceResolver resolver, Resource src, Resource parent, String name)
            throws PersistenceException {
        Resource copy = resolver.create(parent, name, new HashMap<>(src.getValueMap()));
        for (Resource child : src.getChildren()) {
            copyTree(resolver, child, copy, child.getName());
        }
    }

    private static void progress(Options o, String status) {
        if (o.listener != null) {
            o.listener.reportProgress(status);
        }
    }

    /**
     * @return the regular expressions of page properties that rollouts do not update (the configured
     *         {@code contentUpdate} exclusions): such fields of a live copy page are not inherited
     */
    public List<String> pageExclusions() {
        LiveActionFactory<?> f = factories.get("contentUpdate");
        SyncFilter filter = f instanceof ActionFactories.Factory ? ((ActionFactories.Factory) f).filter : ActionFactories.UPDATE;
        return filter.propPatterns();
    }

    /** For tests: registers a factory without OSGi. */
    void addFactory(String name, LiveActionFactory<?> factory) {
        factories.put(name, factory);
    }

    /** @return the action names Sensible Studio can run (built in and registered) */
    public Set<String> knownActions() {
        Set<String> out = new LinkedHashSet<>(Arrays.asList("contentUpdate", "contentCopy", "contentDelete",
                "orderChildren", "referencesUpdate", "referencesUpdateXF", "referencesUpdateXFEmbedded", "referencesUpdateCF",
                "targetVersion", "targetActivate", "targetDeactivate", "markLiveRelationship", "personalizationContentRollout",
                "updateField"));
        out.addAll(factories.keySet());
        return Collections.unmodifiableSet(out);
    }
}
