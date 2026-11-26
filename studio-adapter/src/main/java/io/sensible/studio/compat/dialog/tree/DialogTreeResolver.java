package io.sensible.studio.compat.dialog.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces the effective dialog of a component the way AEM authors see it:
 *
 * <ol>
 *   <li>walks the {@code sling:resourceSuperType} chain of the component,</li>
 *   <li>for every component in the chain merges {@code /apps} over {@code /libs} (overlay),</li>
 *   <li>merges the dialogs of the chain from the root super type down to the component (override),</li>
 *   <li>expands {@code granite/ui/components/coral/foundation/include} nodes with static paths.</li>
 * </ol>
 *
 * <p>The result is a plain resource tree; nothing is mapped to UI concepts here. Anything that cannot be
 * resolved statically (EL expressions, missing include targets) is kept in the tree and flagged with a
 * {@code studio:*} property so the UI can show it instead of silently dropping it.</p>
 */
public final class DialogTreeResolver {

    public static final String RESOURCE_TYPE = "sling:resourceType";
    public static final String RESOURCE_SUPER_TYPE = "sling:resourceSuperType";
    public static final String INCLUDE_TYPE = "granite/ui/components/coral/foundation/include";
    /**
     * Core Components' include used by components that delegate their image to the Image component
     * (Teaser, Byline-like components): includes {@code path} like a Granite include.
     */
    public static final String IMAGE_DELEGATE_INCLUDE_TYPE = "core/wcm/components/include/imagedelegate";

    /** Set on an include whose target could not be loaded. Value: the include path. */
    public static final String UNRESOLVED_INCLUDE = "studio:unresolvedInclude";
    /** Set on a node that replaced an include. Value: the include path. */
    public static final String INCLUDED_FROM = "studio:includedFrom";

    private static final int MAX_SUPER_TYPE_DEPTH = 25;
    private static final int MAX_INCLUDE_DEPTH = 8;

    private final NodeSource source;

    public DialogTreeResolver(NodeSource source) {
        this.source = source;
    }

    /** Result of a resolution: the merged dialog (null if none exists) plus diagnostics. */
    public static final class Result {
        private final String resourceType;
        private final String dialogName;
        private final DialogNode dialog;
        private final List<String> chain;
        private final List<String> warnings;

        Result(String resourceType, String dialogName, DialogNode dialog, List<String> chain, List<String> warnings) {
            this.resourceType = resourceType;
            this.dialogName = dialogName;
            this.dialog = dialog;
            this.chain = Collections.unmodifiableList(chain);
            this.warnings = Collections.unmodifiableList(warnings);
        }

        public String getResourceType() { return resourceType; }
        public String getDialogName() { return dialogName; }
        public DialogNode getDialog() { return dialog; }
        /** Resource types from the component itself up to the last super type that exists. */
        public List<String> getChain() { return chain; }
        public List<String> getWarnings() { return warnings; }
    }

    public Result resolve(String resourceType, String dialogName) {
        List<String> warnings = new ArrayList<>();
        String rt = relativize(resourceType);
        List<String> chain = superTypeChain(rt, warnings);
        DialogNode dialog = mergedDialog(chain, dialogName);
        if (dialog != null) {
            ResourceMergeRules.stripMergeProperties(dialog);
            dialog = expandIncludes(dialog, 0, warnings);
        }
        return new Result(rt, dialogName, dialog, chain, warnings);
    }

    // ------------------------------------------------------------------------------------------------------
    // super type chain

    List<String> superTypeChain(String resourceType, List<String> warnings) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = resourceType;
        while (current != null && !current.isEmpty()) {
            current = relativize(current);
            if (!seen.add(current)) {
                warnings.add("Cycle in sling:resourceSuperType at " + current);
                break;
            }
            if (chain.size() >= MAX_SUPER_TYPE_DEPTH) {
                warnings.add("sling:resourceSuperType chain longer than " + MAX_SUPER_TYPE_DEPTH + ", stopped at " + current);
                break;
            }
            DialogNode component = overlay(current, true);
            if (component == null) {
                if (chain.isEmpty()) {
                    warnings.add("Component not found: " + current);
                } else {
                    warnings.add("Super type not found: " + current);
                }
                break;
            }
            chain.add(current);
            current = component.getString(RESOURCE_SUPER_TYPE);
        }
        return chain;
    }

    private DialogNode mergedDialog(List<String> chain, String dialogName) {
        DialogNode merged = null;
        for (int i = chain.size() - 1; i >= 0; i--) {
            DialogNode dialog = overlay(chain.get(i) + "/" + dialogName, false);
            if (dialog == null) continue;
            merged = merged == null ? dialog : ResourceMergeRules.merge(merged, dialog);
        }
        return merged;
    }

    // ------------------------------------------------------------------------------------------------------
    // overlay (/apps over /libs)

    /**
     * Loads a path relative to the search paths, merging higher priority search paths over lower ones.
     * Absolute paths outside the search paths are loaded as they are.
     */
    DialogNode overlay(String path, boolean shallow) {
        if (path.startsWith("/") && !isInSearchPath(path)) {
            return shallow ? source.loadShallow(path) : source.load(path);
        }
        String relative = relativize(path);
        List<String> searchPaths = new ArrayList<>(source.searchPaths());
        Collections.reverse(searchPaths); // lowest priority first
        DialogNode merged = null;
        for (String sp : searchPaths) {
            String abs = sp + relative;
            DialogNode node = shallow ? source.loadShallow(abs) : source.load(abs);
            if (node == null) continue;
            // A single layer keeps its sling:hide*/orderBefore instructions: they still have to be applied
            // against the super type's dialog. They are stripped once the whole tree is merged.
            merged = merged == null ? node : ResourceMergeRules.merge(merged, node);
        }
        return merged;
    }

    private boolean isInSearchPath(String absolute) {
        for (String sp : source.searchPaths()) {
            if (absolute.startsWith(sp)) return true;
        }
        return false;
    }

    String relativize(String path) {
        if (path == null) return null;
        for (String sp : source.searchPaths()) {
            if (path.startsWith(sp)) return path.substring(sp.length());
        }
        return path;
    }

    // ------------------------------------------------------------------------------------------------------
    // includes

    private DialogNode expandIncludes(DialogNode node, int depth, List<String> warnings) {
        List<DialogNode> ordered = new ArrayList<>();
        for (DialogNode child : node.getChildren().values()) {
            if (isInclude(child)) {
                ordered.add(expandInclude(child, depth, warnings));
            } else {
                ordered.add(expandIncludes(child, depth, warnings));
            }
        }
        node.setChildrenInOrder(ordered);
        return node;
    }

    private DialogNode expandInclude(DialogNode include, int depth, List<String> warnings) {
        String path = include.getString("path");
        if (path == null || path.isEmpty()) {
            include.put(UNRESOLVED_INCLUDE, "");
            warnings.add("Include without path at " + include.getName());
            return include;
        }
        if (path.contains("${")) {
            include.put(UNRESOLVED_INCLUDE, path);
            warnings.add("Include with an expression path is resolved per request and is not supported yet: " + path);
            return include;
        }
        if (depth >= MAX_INCLUDE_DEPTH) {
            include.put(UNRESOLVED_INCLUDE, path);
            warnings.add("Include depth limit reached at " + path);
            return include;
        }
        DialogNode target = loadIncludeTarget(path);
        if (target == null) {
            include.put(UNRESOLVED_INCLUDE, path);
            warnings.add("Include target not found: " + path);
            return include;
        }
        DialogNode copy = ResourceMergeRules.stripMergeProperties(target.deepCopy(include.getName()));
        String overrideType = include.getString("resourceType");
        if (overrideType != null && !overrideType.isEmpty()) {
            copy.put(RESOURCE_TYPE, overrideType);
        }
        copy.put(INCLUDED_FROM, path);
        if (isInclude(copy)) {
            return expandInclude(copy, depth + 1, warnings);
        }
        return expandIncludes(copy, depth + 1, warnings);
    }

    private DialogNode loadIncludeTarget(String rawPath) {
        String path = rawPath;
        if (path.startsWith("/mnt/overlay/")) {
            path = path.substring("/mnt/overlay".length());
        } else if (path.startsWith("/mnt/override/")) {
            path = path.substring("/mnt/override".length());
        }
        // A path into a component dialog must see the merged dialog of that component.
        for (String dialogName : new String[]{"cq:dialog", "cq:design_dialog"}) {
            String marker = "/" + dialogName;
            int idx = path.indexOf(marker);
            if (idx > 0 && (path.length() == idx + marker.length() || path.charAt(idx + marker.length()) == '/')) {
                String componentType = relativize(path.substring(0, idx));
                String rest = path.substring(Math.min(path.length(), idx + marker.length() + 1));
                List<String> chain = superTypeChain(componentType, new ArrayList<>());
                DialogNode dialog = mergedDialog(chain, dialogName);
                return dialog == null ? null : dialog.getDescendant(rest);
            }
        }
        if (!path.startsWith("/") || isInSearchPath(path)) {
            return overlay(path, false);
        }
        // Absolute path outside /apps and /libs (e.g. "/cq/gui/..." left over after stripping /mnt/overlay):
        // try it as is, then through the search paths.
        DialogNode direct = source.load(path);
        return direct != null ? direct : overlay(path.substring(1), false);
    }

    private static boolean isInclude(DialogNode node) {
        String rt = node.getString(RESOURCE_TYPE);
        if (rt == null) return false;
        return rt.equals(INCLUDE_TYPE) || rt.endsWith("/" + INCLUDE_TYPE)
                || rt.equals(IMAGE_DELEGATE_INCLUDE_TYPE) || rt.endsWith("/" + IMAGE_DELEGATE_INCLUDE_TYPE);
    }
}
