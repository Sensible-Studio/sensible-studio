/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.wcm.api.NameConstants;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.WCMException;

/**
 * "Export to Experience Fragment" of Generate Variations, like AEM's: the variations chosen become the variations of a
 * new experience fragment (the first its master), each made of the site's Title, Text and Button components.
 *
 * <p>A part is {@code <variation>:<kind>:<text>}, the kinds {@code title}, {@code text} (lines become paragraphs) and
 * {@code button}. The components are the site's own (an {@code /apps} component whose super type is the core one),
 * so they render with the site's styles; without one, the core component.</p>
 */
final class XfExport {

    static final int MAX_VARIATIONS = 10;
    static final int MAX_PARTS = 100;

    private static final Map<String, String> CORE = new LinkedHashMap<>();

    static {
        CORE.put("title", "core/wcm/components/title/v3/title");
        CORE.put("text", "core/wcm/components/text/v2/text");
        CORE.put("button", "core/wcm/components/button/v2/button");
    }

    private XfExport() {
    }

    /** {@code <variation>:<kind>:<text>} as {variation -> [{kind, text}]}, in order. */
    static Map<Integer, List<String[]>> parts(String[] raw) {
        if (raw == null || raw.length == 0) {
            throw new IllegalArgumentException("No variation to export");
        }
        if (raw.length > MAX_PARTS) {
            throw new IllegalArgumentException("At most " + MAX_PARTS + " parts");
        }
        Map<Integer, List<String[]>> out = new TreeMap<>();
        for (String p : raw) {
            String[] s = p.split(":", 3);
            if (s.length < 3 || !s[0].matches("\\d{1,2}") || !CORE.containsKey(s[1])) {
                throw new IllegalArgumentException("A part is <variation>:<title|text|button>:<text>");
            }
            if (!s[2].trim().isEmpty()) {
                out.computeIfAbsent(Integer.parseInt(s[0]), k -> new ArrayList<>()).add(new String[] {s[1], s[2].trim()});
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The variations are empty");
        }
        if (out.size() > MAX_VARIATIONS) {
            throw new IllegalArgumentException("At most " + MAX_VARIATIONS + " variations");
        }
        return out;
    }

    /**
     * @param parent the folder the fragment goes in (under /content/experience-fragments)
     * @return the fragment and its variations
     */
    static Map<String, Object> export(ResourceResolver resolver, String parent, String title, String template, String[] rawParts)
            throws WCMException, PersistenceException {
        if (parent == null || !parent.startsWith("/content/experience-fragments") || parent.contains("..")) {
            throw new IllegalArgumentException("The fragment goes in a folder under /content/experience-fragments");
        }
        if (title == null || title.trim().isEmpty() || template == null) {
            throw new IllegalArgumentException("title and template are required");
        }
        Map<Integer, List<String[]>> variations = parts(rawParts);
        Resource parentRes = resolver.getResource(parent);
        if (parentRes == null) {
            throw new IllegalArgumentException("No folder " + parent);
        }
        if (resolver.getResource(template) == null) {
            throw new IllegalArgumentException("No template " + template);
        }
        String site = siteOf(parent);
        Map<String, String> types = new HashMap<>();
        for (String kind : CORE.keySet()) {
            types.put(kind, component(resolver, kind, site));
        }

        PageManagerImpl pm = new PageManagerImpl(resolver);
        String name = Pages.toValidName(title.trim());
        Resource xf = resolver.create(parentRes, unique(parentRes, name), props("jcr:primaryType", NameConstants.NT_PAGE));
        Resource xfContent = resolver.create(xf, NameConstants.NN_CONTENT, props("jcr:primaryType", "cq:PageContent", "sling:resourceType",
                XfConversion.XF_TYPE, "cq:template", XfConversion.XF_TEMPLATE, "jcr:title", title.trim()));
        stamp(xfContent);

        List<String> made = new ArrayList<>();
        int n = 0;
        for (List<String[]> parts : variations.values()) {
            n++;
            Page v = pm.create(xf.getPath(), n == 1 ? "master" : "variation-" + n, template, "Variation " + n, false);
            Resource content = v.getContentResource();
            ModifiableValueMap m = content.adaptTo(ModifiableValueMap.class);
            if (n == 1) {
                m.put("cq:xfMasterVariation", Boolean.TRUE);
            }
            if (m.get("cq:xfVariantType") == null) {
                m.put("cq:xfVariantType", "web");
            }
            Resource target = content.getChild("root/container");
            if (target == null) {
                target = content.getChild("root");
            }
            if (target == null) {
                target = resolver.create(content, "root", props("jcr:primaryType", "nt:unstructured", "sling:resourceType",
                        "wcm/foundation/components/responsivegrid"));
            }
            for (String[] part : parts) {
                String kind = part[0];
                Map<String, Object> p = props("jcr:primaryType", "nt:unstructured", "sling:resourceType", types.get(kind));
                if ("text".equals(kind)) {
                    p.put("text", html(part[1]));
                    p.put("textIsRich", "true");
                } else {
                    p.put("jcr:title", part[1]);
                }
                resolver.create(target, unique(target, kind), p);
            }
            made.add(v.getPath());
        }
        resolver.commit();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fragment", xf.getPath());
        out.put("variations", made);
        return out;
    }

    /** {@code /content/experience-fragments/wknd/...} → {@code wknd}. */
    static String siteOf(String parent) {
        String rest = parent.substring("/content/experience-fragments".length());
        String[] segs = rest.split("/");
        return segs.length > 1 ? segs[1] : null;
    }

    /** The site's component of a kind: one in /apps/&lt;site&gt; whose super type is the core one, else any site's, else core. */
    static String component(ResourceResolver resolver, String kind, String site) {
        String core = "core/wcm/components/" + kind + "/";
        Resource apps = resolver.getResource("/apps");
        if (apps == null) {
            return CORE.get(kind);
        }
        String other = null;
        Deque<Resource> queue = new ArrayDeque<>();
        Deque<Integer> depth = new ArrayDeque<>();
        for (Resource s : apps.getChildren()) {
            queue.add(s);
            depth.add(1);
        }
        while (!queue.isEmpty()) {
            Resource r = queue.poll();
            int d = depth.poll();
            String sup = r.getValueMap().get("sling:resourceSuperType", String.class);
            if (sup != null && sup.startsWith(core)) {
                String type = r.getPath().substring("/apps/".length());
                if (site != null && type.startsWith(site + "/")) {
                    return type;
                }
                if (other == null) {
                    other = type;
                }
                continue;
            }
            if (d < 4) {
                for (Resource c : r.getChildren()) {
                    if (!c.getName().startsWith("jcr:") && !c.getName().equals("cq:dialog") && !c.getName().equals("_cq_dialog")) {
                        queue.add(c);
                        depth.add(d + 1);
                    }
                }
            }
        }
        return other != null ? other : CORE.get(kind);
    }

    /** Plain text as paragraphs. */
    static String html(String text) {
        StringBuilder b = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                b.append("<p>").append(escape(line.trim())).append("</p>");
            }
        }
        return b.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String unique(Resource parent, String name) {
        if (parent.getChild(name) == null) {
            return name;
        }
        for (int i = 1; ; i++) {
            if (parent.getChild(name + "_" + i) == null) {
                return name + "_" + i;
            }
        }
    }

    private static void stamp(Resource r) {
        ModifiableValueMap m = r.adaptTo(ModifiableValueMap.class);
        if (m != null) {
            m.put("cq:lastModified", java.util.Calendar.getInstance());
            m.put("cq:lastModifiedBy", r.getResourceResolver().getUserID());
        }
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static List<String> kinds() {
        return Collections.unmodifiableList(new ArrayList<>(CORE.keySet()));
    }
}
