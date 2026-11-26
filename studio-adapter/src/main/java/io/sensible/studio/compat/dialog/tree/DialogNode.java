package io.sensible.studio.compat.dialog.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A detached, ordered copy of a JCR/Sling resource subtree.
 *
 * <p>Granite dialogs are resource trees: every node is rendered by the component named in its
 * {@code sling:resourceType}, containers render their {@code items} children. We keep the tree as it is
 * (no flattening into "tabs" and "fields") so the author UI can apply the same rules Granite does.</p>
 */
public final class DialogNode {

    private final String name;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, DialogNode> children = new LinkedHashMap<>();

    public DialogNode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Object get(String property) {
        return properties.get(property);
    }

    public String getString(String property) {
        Object v = properties.get(property);
        if (v == null) return null;
        if (v instanceof Object[]) {
            Object[] arr = (Object[]) v;
            return arr.length == 0 ? null : String.valueOf(arr[0]);
        }
        return String.valueOf(v);
    }

    /** True for {@code true} as a Boolean or as the string "true" (FileVault files often omit the type hint). */
    public boolean getBoolean(String property) {
        Object v = properties.get(property);
        if (v instanceof Boolean) return (Boolean) v;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
    }

    /** Multi-value view of a property: arrays are returned as-is, single values become a one element list. */
    public List<String> getStrings(String property) {
        Object v = properties.get(property);
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof Object[]) {
            for (Object o : (Object[]) v) out.add(String.valueOf(o));
        } else {
            out.add(String.valueOf(v));
        }
        return out;
    }

    public void put(String property, Object value) {
        if (value == null) {
            properties.remove(property);
        } else {
            properties.put(property, value);
        }
    }

    public Map<String, DialogNode> getChildren() {
        return children;
    }

    public DialogNode getChild(String childName) {
        return children.get(childName);
    }

    public void addChild(DialogNode child) {
        children.put(child.getName(), child);
    }

    /** Walks a relative path ("a/b/c"). Returns null when any segment is missing. */
    public DialogNode getDescendant(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return this;
        DialogNode current = this;
        for (String segment : relativePath.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) continue;
            current = current.getChild(segment);
            if (current == null) return null;
        }
        return current;
    }

    public DialogNode deepCopy() {
        return deepCopy(name);
    }

    public DialogNode deepCopy(String newName) {
        DialogNode copy = new DialogNode(newName);
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Object[]) {
                Object[] arr = (Object[]) v;
                v = Arrays.copyOf(arr, arr.length);
            }
            copy.properties.put(e.getKey(), v);
        }
        for (DialogNode child : children.values()) {
            copy.addChild(child.deepCopy());
        }
        return copy;
    }

    /** Replaces the children map keeping the given order. */
    void setChildrenInOrder(List<DialogNode> ordered) {
        children.clear();
        for (DialogNode n : ordered) children.put(n.getName(), n);
    }

    @Override
    public String toString() {
        return "DialogNode{" + name + ", props=" + properties.keySet() + ", children=" + children.keySet() + "}";
    }
}
