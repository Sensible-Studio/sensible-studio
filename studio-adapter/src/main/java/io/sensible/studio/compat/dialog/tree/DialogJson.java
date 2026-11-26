package io.sensible.studio.compat.dialog.tree;

import java.util.Collection;
import java.util.Map;

/**
 * Serializes a resolved dialog to the JSON contract consumed by {@code @sensible-studio/granite-ui}.
 *
 * <pre>
 * {
 *   "resourceType": "wknd/components/byline",
 *   "dialogName": "cq:dialog",
 *   "chain": ["wknd/components/byline", "core/wcm/components/image/v3/image"],
 *   "warnings": [],
 *   "policyPath": "/conf/wknd/settings/wcm/policies/wknd/components/image/policy_1", // or null
 *   "policy": { ...node... },                                                     // or null
 *   "root": { "name": "cq:dialog", "properties": { ... }, "children": [ { ...node... } ] }
 * }
 * </pre>
 *
 * Children are an array so their order (which Granite renders in) survives every JSON parser.
 * Hand written on purpose: no JSON library has to be deployed next to the bundle.
 */
public final class DialogJson {

    private DialogJson() {
    }

    public static String toJson(DialogTreeResolver.Result result) {
        return toJson(result, null);
    }

    /**
     * With a policy, adds {@code "policyPath"} and {@code "policy"} (the policy subtree in node format); dialogs
     * evaluate {@code ${cqDesign...}} against it and the Style System reads its style groups.
     */
    public static String toJson(DialogTreeResolver.Result result, PolicyResolver.Policy policy) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append('{');
        field(sb, "resourceType").append(quote(result.getResourceType())).append(',');
        field(sb, "dialogName").append(quote(result.getDialogName())).append(',');
        field(sb, "chain");
        value(sb, result.getChain());
        sb.append(',');
        field(sb, "warnings");
        value(sb, result.getWarnings());
        sb.append(',');
        field(sb, "policyPath").append(policy == null ? "null" : quote(policy.getPath())).append(',');
        field(sb, "policy");
        if (policy == null) {
            sb.append("null");
        } else {
            node(sb, policy.getNode());
        }
        sb.append(',');
        field(sb, "root");
        if (result.getDialog() == null) {
            sb.append("null");
        } else {
            node(sb, result.getDialog());
        }
        sb.append('}');
        return sb.toString();
    }

    public static String toJson(DialogNode node) {
        StringBuilder sb = new StringBuilder();
        node(sb, node);
        return sb.toString();
    }

    private static void node(StringBuilder sb, DialogNode node) {
        sb.append('{');
        field(sb, "name").append(quote(node.getName())).append(',');
        field(sb, "properties").append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : node.getProperties().entrySet()) {
            if (!first) sb.append(',');
            first = false;
            field(sb, e.getKey());
            value(sb, e.getValue());
        }
        sb.append("},");
        field(sb, "children").append('[');
        first = true;
        for (DialogNode child : node.getChildren().values()) {
            if (!first) sb.append(',');
            first = false;
            node(sb, child);
        }
        sb.append("]}");
    }

    private static StringBuilder field(StringBuilder sb, String name) {
        return sb.append(quote(name)).append(':');
    }

    private static void value(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            sb.append(Double.isFinite(d) ? v.toString() : "null");
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) v;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                value(sb, arr[i]);
            }
            sb.append(']');
        } else if (v instanceof Collection) {
            value(sb, ((Collection<?>) v).toArray());
        } else {
            sb.append(quote(String.valueOf(v)));
        }
    }

    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
