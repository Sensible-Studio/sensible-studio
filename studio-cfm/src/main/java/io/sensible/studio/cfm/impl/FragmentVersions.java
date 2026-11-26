package io.sensible.studio.cfm.impl;

import org.apache.sling.api.resource.ResourceResolver;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.LabelExistsVersionException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionException;
import javax.jcr.version.VersionHistory;
import javax.jcr.version.VersionIterator;
import javax.jcr.version.VersionManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Versions of content fragments, as AEM keeps them: JCR versions of the fragment asset (made
 * {@code mix:versionable} by its first version). A version may carry a label (a JCR version label, unique per
 * fragment) and a comment ({@code jcr:content/cq:versionComment} in the frozen node, with
 * {@code cq:versionCreator}). Restoring first saves the current state as a version, as AEM's "Revert" does.
 */
final class FragmentVersions {

    static final String COMMENT = "cq:versionComment";
    static final String CREATOR = "cq:versionCreator";
    private static final Pattern LABEL = Pattern.compile("^[^/:\\[\\]*|'\"]{1,100}$");

    private final ResourceResolver resolver;

    FragmentVersions(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    private Session session() {
        Session s = resolver.adaptTo(Session.class);
        if (s == null) throw new UnsupportedOperationException("Versions need a JCR repository");
        return s;
    }

    private Node fragment(Session s, String path) throws RepositoryException {
        if (path == null || !ContentFragmentImpl.isFragment(resolver.getResource(path))) {
            throw new IllegalArgumentException("No content fragment at " + path);
        }
        return s.getNode(path);
    }

    /** Versions of a fragment, newest first; empty when it has none. */
    List<Map<String, Object>> list(String path) throws RepositoryException {
        Session s = session();
        Node node = fragment(s, path);
        if (!node.isNodeType("mix:versionable")) return Collections.emptyList();
        VersionHistory history = s.getWorkspace().getVersionManager().getVersionHistory(path);
        List<Map<String, Object>> out = new ArrayList<>();
        for (VersionIterator it = history.getAllVersions(); it.hasNext(); ) {
            Version v = it.nextVersion();
            if ("jcr:rootVersion".equals(v.getName())) continue;
            out.add(describe(history, v));
        }
        Collections.reverse(out);
        return out;
    }

    private static Map<String, Object> describe(VersionHistory history, Version v) throws RepositoryException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", v.getName());
        m.put("created", FragmentStore.iso(v.getCreated()));
        m.put("labels", Arrays.asList(history.getVersionLabels(v)));
        Node frozen = v.getFrozenNode();
        Node content = frozen.hasNode("jcr:content") ? frozen.getNode("jcr:content") : null;
        m.put("title", text(content, "jcr:title"));
        m.put("comment", text(content, COMMENT));
        m.put("createdBy", text(content, CREATOR));
        return m;
    }

    private static String text(Node n, String property) throws RepositoryException {
        return n != null && n.hasProperty(property) ? n.getProperty(property).getString() : null;
    }

    /** A new version of the fragment's current state; answers it as {@link #list} describes versions. */
    Map<String, Object> create(String path, String label, String comment) throws RepositoryException,
            FragmentStore.ValidationException {
        Session s = session();
        Node node = fragment(s, path);
        String l = label == null ? "" : label.trim();
        if (!l.isEmpty() && !LABEL.matcher(l).matches()) {
            throw invalid("label", "A label has at most 100 characters and none of / : [ ] * | ' \".");
        }
        VersionManager vm = s.getWorkspace().getVersionManager();
        if (!node.isNodeType("mix:versionable")) {
            node.addMixin("mix:versionable");
            s.save();
        } else if (!vm.isCheckedOut(path)) {
            vm.checkout(path);
        }
        if (!l.isEmpty() && vm.getVersionHistory(path).hasVersionLabel(l)) {
            throw invalid("label", "Another version is labelled \"" + l + "\".");
        }
        Node content = node.getNode("jcr:content");
        String c = comment == null ? "" : comment.trim();
        if (c.isEmpty()) {
            if (content.hasProperty(COMMENT)) content.getProperty(COMMENT).remove();
        } else {
            content.setProperty(COMMENT, c);
        }
        content.setProperty(CREATOR, s.getUserID());
        s.save();
        Version v = vm.checkpoint(path);
        VersionHistory history = vm.getVersionHistory(path);
        if (!l.isEmpty()) {
            try {
                history.addVersionLabel(v.getName(), l, false);
            } catch (LabelExistsVersionException e) {
                throw invalid("label", "Another version is labelled \"" + l + "\".");
            }
        }
        // the comment belongs to the version, not to the fragment's next one
        if (content.hasProperty(COMMENT)) content.getProperty(COMMENT).remove();
        if (content.hasProperty(CREATOR)) content.getProperty(CREATOR).remove();
        s.save();
        return describe(history, v);
    }

    /** The fragment as it was in a version: the shape of {@link FragmentStore#fragment}, plus {@code version}. */
    Map<String, Object> content(String path, String versionName) throws RepositoryException {
        Session s = session();
        fragment(s, path);
        Version v = version(s, path, versionName);
        Map<String, Object> m = new FragmentStore(resolver).fragment(v.getFrozenNode().getPath());
        if (m == null) throw new IllegalArgumentException("Version " + versionName + " holds no content fragment");
        m.put("path", path);
        m.put("name", path.substring(path.lastIndexOf('/') + 1));
        m.put("version", describe(s.getWorkspace().getVersionManager().getVersionHistory(path), v));
        return m;
    }

    /**
     * Restores a version. The current state is saved first as a version commented "Before restoring ...",
     * which is answered so the user can go back.
     */
    Map<String, Object> restore(String path, String versionName) throws RepositoryException,
            FragmentStore.ValidationException {
        Session s = session();
        fragment(s, path);
        Version target = version(s, path, versionName);
        String[] labels = s.getWorkspace().getVersionManager().getVersionHistory(path).getVersionLabels(target);
        Map<String, Object> safety = create(path, null, "Before restoring " + (labels.length > 0 ? labels[0] : "version " + versionName));
        VersionManager vm = s.getWorkspace().getVersionManager();
        vm.restore(target, true);
        vm.checkout(path);
        // restoring is a change of the fragment: it is saved now (the version brought back its own dates)
        Node content = s.getNode(path).getNode("jcr:content");
        Calendar now = Calendar.getInstance();
        content.setProperty("lastFragmentSave", now);
        content.setProperty("jcr:lastModified", now);
        content.setProperty("jcr:lastModifiedBy", s.getUserID());
        if (content.hasProperty(COMMENT)) content.getProperty(COMMENT).remove();
        if (content.hasProperty(CREATOR)) content.getProperty(CREATOR).remove();
        s.save();
        return safety;
    }

    private Version version(Session s, String path, String versionName) throws RepositoryException {
        Node node = s.getNode(path);
        if (versionName == null || !node.isNodeType("mix:versionable")) throw new IllegalArgumentException("No version " + versionName);
        try {
            Version v = s.getWorkspace().getVersionManager().getVersionHistory(path).getVersion(versionName);
            if ("jcr:rootVersion".equals(v.getName())) throw new IllegalArgumentException("No version " + versionName);
            return v;
        } catch (VersionException e) {
            throw new IllegalArgumentException("No version " + versionName);
        }
    }

    private static FragmentStore.ValidationException invalid(String field, String message) {
        List<FragmentStore.FieldError> errors = new ArrayList<>();
        errors.add(new FragmentStore.FieldError(field, message));
        return new FragmentStore.ValidationException(errors);
    }
}
