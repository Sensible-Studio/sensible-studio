package io.sensible.studio.compat.dialog.tree;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The merge rules of the Apache Sling Resource Merger, applied to detached {@link DialogNode} trees.
 *
 * <p>AEM projects rely on these rules in two places: overlays ({@code /apps} over {@code /libs}) and
 * overrides along {@code sling:resourceSuperType} (e.g. {@code wknd/components/byline} reusing the dialog of
 * {@code core/wcm/components/image/v3/image} and hiding its "metadata" tab). The behaviour implemented here
 * follows the public Sling documentation:</p>
 * <ul>
 *   <li>{@code sling:hideProperties} hides inherited properties (names or {@code *}).</li>
 *   <li>{@code sling:hideResource} hides the inherited resource including its children.</li>
 *   <li>{@code sling:hideChildren} hides inherited children (names, {@code *}, and {@code !name} as an exception
 *       to {@code *}); children defined by the overlaying resource stay visible.</li>
 *   <li>{@code sling:orderBefore} moves a child in front of the named sibling.</li>
 * </ul>
 */
public final class ResourceMergeRules {

    public static final String HIDE_PROPERTIES = "sling:hideProperties";
    public static final String HIDE_RESOURCE = "sling:hideResource";
    public static final String HIDE_CHILDREN = "sling:hideChildren";
    public static final String ORDER_BEFORE = "sling:orderBefore";

    private ResourceMergeRules() {
    }

    /**
     * Merges {@code overlay} on top of {@code base}. Neither input is modified.
     *
     * @return the merged tree, or {@code null} if the overlay hides the resource
     */
    public static DialogNode merge(DialogNode base, DialogNode overlay) {
        if (overlay == null) return base == null ? null : base.deepCopy();
        if (overlay.getBoolean(HIDE_RESOURCE)) return null;
        if (base == null) return stripMergeProperties(overlay.deepCopy());

        DialogNode result = new DialogNode(overlay.getName());

        // Properties: inherited ones first (unless hidden), local ones win.
        List<String> hiddenProps = overlay.getStrings(HIDE_PROPERTIES);
        for (Map.Entry<String, Object> e : base.getProperties().entrySet()) {
            if (!isHidden(e.getKey(), hiddenProps)) {
                result.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, Object> e : overlay.getProperties().entrySet()) {
            result.put(e.getKey(), e.getValue());
        }

        // Children: inherited first, in inherited order (unless hidden), then merged or appended local ones.
        List<String> hiddenChildren = overlay.getStrings(HIDE_CHILDREN);
        List<DialogNode> ordered = new ArrayList<>();
        for (DialogNode inherited : base.getChildren().values()) {
            if (isHidden(inherited.getName(), hiddenChildren)) continue;
            DialogNode local = overlay.getChild(inherited.getName());
            if (local == null) {
                ordered.add(inherited.deepCopy());
            } else {
                DialogNode merged = merge(inherited, local);
                if (merged != null) ordered.add(merged);
            }
        }
        for (DialogNode local : overlay.getChildren().values()) {
            if (base.getChild(local.getName()) != null && !isHidden(local.getName(), hiddenChildren)) {
                continue; // already merged above
            }
            if (base.getChild(local.getName()) != null) {
                // inherited child was hidden by sling:hideChildren; the local definition replaces it entirely
                DialogNode replacement = merge(null, local);
                if (replacement != null) ordered.add(replacement);
                continue;
            }
            DialogNode added = merge(null, local);
            if (added != null) ordered.add(added);
        }

        applyOrderBefore(ordered, overlay);
        result.setChildrenInOrder(ordered);
        return stripMergeProperties(result);
    }

    static boolean isHidden(String name, List<String> rules) {
        if (rules.isEmpty()) return false;
        if (rules.contains("!" + name)) return false;
        return rules.contains("*") || rules.contains(name);
    }

    private static void applyOrderBefore(List<DialogNode> ordered, DialogNode overlay) {
        for (DialogNode local : overlay.getChildren().values()) {
            String before = local.getString(ORDER_BEFORE);
            if (before == null || before.isEmpty()) continue;
            int from = indexOf(ordered, local.getName());
            if (from < 0) continue;
            DialogNode moving = ordered.remove(from);
            int to = indexOf(ordered, before);
            if (to < 0) {
                ordered.add(from, moving); // target missing: keep position
            } else {
                ordered.add(to, moving);
            }
        }
    }

    private static int indexOf(List<DialogNode> nodes, String name) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /** Removes the merge instructions so they do not leak into the rendered tree. Recursive. */
    static DialogNode stripMergeProperties(DialogNode node) {
        node.put(HIDE_PROPERTIES, null);
        node.put(HIDE_RESOURCE, null);
        node.put(HIDE_CHILDREN, null);
        node.put(ORDER_BEFORE, null);
        Iterator<DialogNode> it = node.getChildren().values().iterator();
        while (it.hasNext()) {
            stripMergeProperties(it.next());
        }
        return node;
    }
}
