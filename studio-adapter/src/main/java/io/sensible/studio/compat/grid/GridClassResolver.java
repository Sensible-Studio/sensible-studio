package io.sensible.studio.compat.grid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves layout container grid CSS classes for components based on cq:responsive data in JCR.
 * Supports both legacy 'aem-Grid' prefix (for 100% backward compatibility) and custom prefix.
 */
public class GridClassResolver {

    private final String prefix;
    private final int totalColumns;

    public GridClassResolver() {
        this("aem-Grid", 12);
    }

    public GridClassResolver(String prefix, int totalColumns) {
        this.prefix = prefix;
        this.totalColumns = totalColumns;
    }

    public String resolveContainerClass() {
        return String.format("%s %s--%d %s--default--%d", prefix, totalColumns, prefix, totalColumns);
    }

    /**
     * Resolves the CSS class list for a component item inside the grid.
     * 
     * @param breakpointConfigs Map of breakpoint name (e.g. "default", "phone", "tablet") 
     *                          to a map of properties like "width", "offset", "behavior"
     * @return CSS class string e.g. "aem-GridColumn aem-GridColumn--default--6 aem-GridColumn--phone--12"
     */
    public String resolveColumnClasses(Map<String, Map<String, Object>> breakpointConfigs) {
        List<String> classes = new ArrayList<>();
        String columnBaseClass = prefix + "Column";
        classes.add(columnBaseClass);

        if (breakpointConfigs == null || breakpointConfigs.isEmpty()) {
            // Default span full width
            classes.add(String.format("%s--default--%d", columnBaseClass, totalColumns));
            return String.join(" ", classes);
        }

        for (Map.Entry<String, Map<String, Object>> entry : breakpointConfigs.entrySet()) {
            String breakpoint = entry.getKey();
            Map<String, Object> props = entry.getValue();

            if (props.containsKey("width")) {
                int width = toInt(props.get("width"), totalColumns);
                classes.add(String.format("%s--%s--%d", columnBaseClass, breakpoint, width));
            }

            if (props.containsKey("offset")) {
                int offset = toInt(props.get("offset"), 0);
                if (offset > 0) {
                    classes.add(String.format("%s--offset--%s--%d", columnBaseClass, breakpoint, offset));
                }
            }

            if (props.containsKey("behavior")) {
                String behavior = String.valueOf(props.get("behavior"));
                if ("newline".equalsIgnoreCase(behavior)) {
                    classes.add(String.format("%s--%s--newline", columnBaseClass, breakpoint));
                }
            }
        }

        return String.join(" ", classes);
    }

    private int toInt(Object val, int fallback) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val != null) {
            try {
                return Integer.parseInt(val.toString());
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public String getPrefix() {
        return prefix;
    }

    public int getTotalColumns() {
        return totalColumns;
    }
}
