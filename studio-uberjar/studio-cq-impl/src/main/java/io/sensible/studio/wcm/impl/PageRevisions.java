/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Revision;
import com.day.cq.wcm.api.WCMException;

/**
 * Page versions, kept like AEM keeps them: JCR versions of the page's {@code jcr:content} node (made
 * {@code mix:versionable} by its first version). A version may carry a label (a JCR version label, unique per page)
 * and a comment ({@code cq:versionComment}, with {@code cq:versionCreator}, in the frozen node).
 */
public final class PageRevisions {

    public static final String COMMENT = "cq:versionComment";
    public static final String CREATOR = "cq:versionCreator";

    private final ResourceResolver resolver;

    public PageRevisions(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    private Session session() throws WCMException {
        Session s = resolver.adaptTo(Session.class);
        if (s == null) {
            throw new WCMException("Page versions need a JCR repository");
        }
        return s;
    }

    private static String contentPath(String pagePath) {
        return pagePath + "/" + NameConstants.NN_CONTENT;
    }

    /**
     * Saves the current page content as a new version. A version is taken from saved content, so pending changes of
     * the resolver are committed first (a rollout takes its versions before it changes anything, see
     * {@code RolloutManagerImpl}). A label already used is refused before anything is saved.
     */
    public Revision create(String pagePath, String label, String comment) throws WCMException {
        Session s = session();
        String path = contentPath(pagePath);
        try {
            if (!s.nodeExists(path)) {
                throw new WCMException("No page content at " + path);
            }
            Node content = s.getNode(path);
            VersionManager vm = s.getWorkspace().getVersionManager();
            String l = label == null ? "" : label.trim();
            if (!l.isEmpty() && content.isNodeType("mix:versionable") && vm.getVersionHistory(path).hasVersionLabel(l)) {
                throw new WCMException("The label " + l + " is already used by another version");
            }
            if (!content.isNodeType("mix:versionable")) {
                content.addMixin("mix:versionable");
            }
            if (content.isNodeType("mix:versionable") && !vm.isCheckedOut(path)) {
                vm.checkout(path);
            }
            content.setProperty(COMMENT, comment);
            content.setProperty(CREATOR, s.getUserID());
            s.save();
            Version v = vm.checkpoint(path);
            if (!l.isEmpty()) {
                vm.getVersionHistory(path).addVersionLabel(v.getName(), l, false);
            }
            return revision(pagePath, v, vm.getVersionHistory(path));
        } catch (RepositoryException e) {
            try {
                s.refresh(false);
            } catch (RepositoryException ignored) {
                // nothing more to undo
            }
            throw new WCMException("Could not create a version of " + pagePath + ": " + e.getMessage(), e);
        }
    }

    /** @return the versions of the page, newest first (up to {@code cal}, if given); empty when it has none */
    public Collection<Revision> list(String pagePath, Calendar cal) throws WCMException {
        Session s = session();
        String path = contentPath(pagePath);
        List<Revision> out = new ArrayList<>();
        try {
            if (!s.nodeExists(path) || !s.getNode(path).isNodeType("mix:versionable")) {
                return out;
            }
            VersionHistory history = s.getWorkspace().getVersionManager().getVersionHistory(path);
            for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
                Version v = it.nextVersion();
                if ("jcr:rootVersion".equals(v.getName())) {
                    continue;
                }
                if (cal != null && v.getCreated().after(cal)) {
                    continue;
                }
                out.add(revision(pagePath, v, history));
            }
        } catch (RepositoryException e) {
            throw new WCMException("Could not read the versions of " + pagePath, e);
        }
        out.sort((a, b) -> b.getCreated().compareTo(a.getCreated()));
        return out;
    }

    /** Properties that describe the page's state in the system, not its content: a restore keeps the current ones. */
    static final String[] SYSTEM_PROPS = {"studio:lockOwner", "cq:lastReplicated", "cq:lastReplicatedBy",
            "cq:lastReplicationAction", "cq:lastRolledout", "cq:lastRolledoutBy"};
    static final String LIVE_SYNC_CONFIG = "cq:LiveSyncConfig";

    /**
     * Restores a version of the page content; the current state is saved as a version first. What is not content stays
     * as it is now: the live copy configuration ({@code cq:LiveSyncConfig}, so an old version can neither re-attach the
     * page to a blueprint nor detach it), the lock, the replication status and the last rollout.
     */
    public void restore(String pagePath, String revisionId) throws WCMException {
        Session s = session();
        String path = contentPath(pagePath);
        try {
            Version target = null;
            if (s.nodeExists(path) && s.getNode(path).isNodeType("mix:versionable")) {
                VersionHistory history = s.getWorkspace().getVersionManager().getVersionHistory(path);
                for (VersionIterator it = history.getAllVersions(); it.hasNext();) {
                    Version v = it.nextVersion();
                    if (v.getIdentifier().equals(revisionId) || v.getName().equals(revisionId)) {
                        target = v;
                    }
                }
            }
            if (target == null || "jcr:rootVersion".equals(target.getName())) {
                throw new WCMException("No version " + revisionId + " of " + pagePath);
            }
            Node content = s.getNode(path);
            // through the value map: it stores names whose prefix the repository does not know (studio:) escaped
            ValueMap current = resolver.getResource(path).getValueMap();
            String owner = current.get("studio:lockOwner", String.class);
            if (owner != null && !owner.equals(s.getUserID())) {
                throw new WCMException(pagePath + " is locked by " + owner);
            }
            Map<String, Object> system = new HashMap<>();
            for (String p : SYSTEM_PROPS) {
                system.put(p, current.get(p));
            }
            Map<String, Object> config = content.hasNode(LIVE_SYNC_CONFIG) ? snapshot(content.getNode(LIVE_SYNC_CONFIG)) : null;
            boolean liveSync = content.isNodeType("cq:LiveSync");

            create(pagePath, null, "Before restoring version " + target.getName());
            VersionManager vm = s.getWorkspace().getVersionManager();
            vm.restore(target, true);
            vm.checkout(path); // stays editable, like AEM

            content = s.getNode(path);
            org.apache.sling.api.resource.ModifiableValueMap restored =
                    resolver.getResource(path).adaptTo(org.apache.sling.api.resource.ModifiableValueMap.class);
            for (Map.Entry<String, Object> e : system.entrySet()) {
                if (e.getValue() == null) {
                    restored.remove(e.getKey());
                } else {
                    restored.put(e.getKey(), e.getValue());
                }
            }
            if (content.hasNode(LIVE_SYNC_CONFIG)) {
                content.getNode(LIVE_SYNC_CONFIG).remove();
            }
            if (config != null) {
                Node cfg = content.addNode(LIVE_SYNC_CONFIG, (String) config.remove(TYPE));
                for (Map.Entry<String, Object> e : config.entrySet()) {
                    if (e.getValue() instanceof Value[]) {
                        cfg.setProperty(e.getKey(), (Value[]) e.getValue());
                    } else {
                        cfg.setProperty(e.getKey(), (Value) e.getValue());
                    }
                }
            }
            if (liveSync && !content.isNodeType("cq:LiveSync")) {
                content.addMixin("cq:LiveSync");
            } else if (!liveSync && content.isNodeType("cq:LiveSync")) {
                content.removeMixin("cq:LiveSync");
            }
            s.save();
        } catch (RepositoryException e) {
            try {
                s.refresh(false);
            } catch (RepositoryException ignored) {
                // nothing more to undo
            }
            throw new WCMException("Could not restore " + pagePath + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> snapshot(Node node) throws RepositoryException {
        Map<String, Object> m = new HashMap<>();
        for (PropertyIterator it = node.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getName().startsWith("jcr:")) {
                continue; // type, mixins and other protected properties
            }
            m.put(p.getName(), p.isMultiple() ? p.getValues() : p.getValue());
        }
        m.put(TYPE, node.getPrimaryNodeType().getName());
        return m;
    }

    private static final String TYPE = " type";

    private static Revision revision(String pagePath, Version v, VersionHistory history) throws RepositoryException {
        Node frozen = v.getFrozenNode();
        String[] labels = history.getVersionLabels(v);
        Map<String, Object> props = new HashMap<>();
        for (PropertyIterator it = frozen.getProperties(); it.hasNext();) {
            Property p = it.nextProperty();
            if (p.getName().startsWith("jcr:frozen") || p.getType() == PropertyType.BINARY) {
                continue;
            }
            props.put(p.getName(), p.isMultiple() ? strings(p.getValues()) : p.getValue().getString());
        }
        ValueMap vm = new ValueMapDecorator(props);
        return new Revision(v.getIdentifier(), v.getName(), pagePath, labels.length > 0 ? labels[0] : null,
                vm.get(COMMENT, String.class), v.getCreated(), vm);
    }

    private static String[] strings(Value[] values) throws RepositoryException {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].getString();
        }
        return out;
    }

}
