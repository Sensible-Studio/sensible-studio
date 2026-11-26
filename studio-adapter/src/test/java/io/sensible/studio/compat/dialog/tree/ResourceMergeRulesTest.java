package io.sensible.studio.compat.dialog.tree;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceMergeRulesTest {

    private static DialogNode node(String name, Object... props) {
        DialogNode n = new DialogNode(name);
        for (int i = 0; i < props.length; i += 2) n.put((String) props[i], props[i + 1]);
        return n;
    }

    private static DialogNode with(DialogNode parent, DialogNode... children) {
        for (DialogNode c : children) parent.addChild(c);
        return parent;
    }

    private static List<String> names(DialogNode n) {
        return new ArrayList<>(n.getChildren().keySet());
    }

    @Test
    void localPropertiesWinAndInheritedOnesStay() {
        DialogNode base = node("f", "fieldLabel", "Base", "name", "./x");
        DialogNode local = node("f", "fieldLabel", "Local");
        DialogNode merged = ResourceMergeRules.merge(base, local);
        assertEquals("Local", merged.getString("fieldLabel"));
        assertEquals("./x", merged.getString("name"));
    }

    @Test
    void hidePropertiesRemovesInheritedProperties() {
        DialogNode base = node("f", "a", "1", "b", "2", "c", "3");
        DialogNode local = node("f", ResourceMergeRules.HIDE_PROPERTIES, new Object[]{"a", "b"}, "c", "33");
        DialogNode merged = ResourceMergeRules.merge(base, local);
        assertNull(merged.get("a"));
        assertNull(merged.get("b"));
        assertEquals("33", merged.getString("c"));
        assertNull(merged.get(ResourceMergeRules.HIDE_PROPERTIES), "merge instructions must not leak");
    }

    @Test
    void hideResourceAcceptsBooleanAndString() {
        DialogNode base = with(node("items"), node("a"), node("b"), node("c"));
        DialogNode local = with(node("items"),
                node("a", ResourceMergeRules.HIDE_RESOURCE, true),
                node("b", ResourceMergeRules.HIDE_RESOURCE, "true"),
                node("c", ResourceMergeRules.HIDE_RESOURCE, false));
        assertEquals(Arrays.asList("c"), names(ResourceMergeRules.merge(base, local)));
    }

    @Test
    void hideChildrenWildcardKeepsLocalChildrenAndExceptions() {
        DialogNode base = with(node("items"), node("a"), node("b"), node("c"));
        DialogNode local = with(node("items", ResourceMergeRules.HIDE_CHILDREN, new Object[]{"*", "!b"}), node("mine"));
        assertEquals(Arrays.asList("b", "mine"), names(ResourceMergeRules.merge(base, local)));
    }

    @Test
    void hiddenInheritedChildIsReplacedByLocalDefinition() {
        DialogNode base = with(node("items"), with(node("a", "x", "1"), node("deep")));
        DialogNode local = with(node("items", ResourceMergeRules.HIDE_CHILDREN, "a"), node("a", "y", "2"));
        DialogNode merged = ResourceMergeRules.merge(base, local);
        DialogNode a = merged.getChild("a");
        assertNull(a.get("x"));
        assertEquals("2", a.getString("y"));
        assertTrue(a.getChildren().isEmpty());
    }

    @Test
    void orderBeforeMovesChildren() {
        DialogNode base = with(node("items"), node("a"), node("b"), node("c"));
        DialogNode local = with(node("items"),
                node("c", ResourceMergeRules.ORDER_BEFORE, "a"),
                node("new", ResourceMergeRules.ORDER_BEFORE, "b"));
        assertEquals(Arrays.asList("c", "a", "new", "b"), names(ResourceMergeRules.merge(base, local)));
    }

    @Test
    void mergeIsRecursiveAndDoesNotTouchInputs() {
        DialogNode base = with(node("root"), with(node("tabs"), node("one", "t", "1"), node("two", "t", "2")));
        DialogNode local = with(node("root"), with(node("tabs"), node("two", "t", "2b")));
        DialogNode merged = ResourceMergeRules.merge(base, local);
        assertEquals("2b", merged.getDescendant("tabs/two").getString("t"));
        assertEquals("1", merged.getDescendant("tabs/one").getString("t"));
        assertEquals("2", base.getDescendant("tabs/two").getString("t"));
    }
}
