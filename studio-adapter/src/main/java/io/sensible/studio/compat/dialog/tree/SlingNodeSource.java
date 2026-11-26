package io.sensible.studio.compat.dialog.tree;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** {@link NodeSource} over a live Sling {@link ResourceResolver}. */
public final class SlingNodeSource implements NodeSource {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_NODES = 20000;

    private final ResourceResolver resolver;

    public SlingNodeSource(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public DialogNode load(String absolutePath) {
        Resource resource = resolver.getResource(absolutePath);
        if (resource == null) return null;
        int[] budget = {MAX_NODES};
        return copy(resource, 0, budget, true);
    }

    @Override
    public DialogNode loadShallow(String absolutePath) {
        Resource resource = resolver.getResource(absolutePath);
        if (resource == null) return null;
        return copy(resource, 0, new int[]{1}, false);
    }

    @Override
    public List<String> searchPaths() {
        String[] paths = resolver.getSearchPath();
        return paths == null || paths.length == 0 ? Arrays.asList("/apps/", "/libs/") : Arrays.asList(paths);
    }

    private static DialogNode copy(Resource resource, int depth, int[] budget, boolean deep) {
        DialogNode node = new DialogNode(resource.getName());
        budget[0]--;
        ValueMap props = resource.getValueMap();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object value = toPlain(e.getValue());
            if (value != null) node.put(e.getKey(), value);
        }
        if (deep && depth < MAX_DEPTH) {
            for (Resource child : resource.getChildren()) {
                if (budget[0] <= 0) break;
                node.addChild(copy(child, depth + 1, budget, true));
            }
        }
        return node;
    }

    private static Object toPlain(Object value) {
        if (value == null || value instanceof InputStream) return null;
        if (value instanceof Calendar) return iso(((Calendar) value).getTime());
        if (value instanceof Date) return iso((Date) value);
        if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                Object v = toPlain(java.lang.reflect.Array.get(value, i));
                if (v != null) out.add(v);
            }
            return out.toArray();
        }
        return String.valueOf(value);
    }

    private static String iso(Date date) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(date);
    }
}
