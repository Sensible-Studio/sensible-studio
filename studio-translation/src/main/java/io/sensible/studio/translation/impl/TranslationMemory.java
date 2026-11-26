package io.sensible.studio.translation.impl;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exact-match translation memory: a text translated once into a language is translated the same way everywhere
 * (pages, fragments, experience fragments) without asking the model again. Translations people approved win
 * over machine ones. Stored at {@code /var/studio/translation/memory/<source>_<target>/<xx>/<hash>} with
 * {@code source}, {@code text}, {@code origin} (the connector, or {@code approved}), {@code updated}, {@code uses}.
 */
final class TranslationMemory {

    static final String ROOT = "/var/studio/translation/memory";
    static final String APPROVED = "approved";

    private final ResourceResolver resolver;

    TranslationMemory(ResourceResolver resolver) {
        this.resolver = resolver;
    }

    /** The remembered translations of {@code texts}, by source text. */
    Map<String, String> lookup(String from, String to, Collection<String> texts) {
        Map<String, String> out = new LinkedHashMap<>();
        if (from == null || to == null) return out;
        for (String t : texts) {
            Resource r = resolver.getResource(path(from, to, t));
            if (r == null) continue;
            String source = r.getValueMap().get("source", String.class);
            String text = r.getValueMap().get("text", String.class);
            if (t.equals(source) && text != null) out.put(t, text);
        }
        return out;
    }

    /** Counts uses of remembered translations (best effort). */
    void used(String from, String to, Collection<String> texts) throws PersistenceException {
        for (String t : texts) {
            Resource r = resolver.getResource(path(from, to, t));
            if (r == null) continue;
            ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
            if (vm != null) vm.put("uses", vm.get("uses", 0L) + 1);
        }
        resolver.commit();
    }

    /** Remembers translations; a machine translation does not replace an approved one. */
    void store(String from, String to, Map<String, String> pairs, String origin) throws PersistenceException {
        if (from == null || to == null || pairs.isEmpty()) return;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getKey().trim().isEmpty()) continue;
            String path = path(from, to, e.getKey());
            Resource r = resolver.getResource(path);
            if (r != null && APPROVED.equals(r.getValueMap().get("origin", String.class)) && !APPROVED.equals(origin)) continue;
            if (r == null) {
                Resource parent = ensure(ResourceUtil.getParent(path));
                Map<String, Object> p = new HashMap<>();
                p.put("jcr:primaryType", "nt:unstructured");
                r = resolver.create(parent, ResourceUtil.getName(path), p);
            }
            ModifiableValueMap vm = r.adaptTo(ModifiableValueMap.class);
            vm.put("source", e.getKey());
            vm.put("text", e.getValue());
            vm.put("origin", origin);
            vm.put("updated", Calendar.getInstance());
        }
        resolver.commit();
    }

    static String path(String from, String to, String text) {
        String h = hash(text);
        return ROOT + "/" + from.toLowerCase() + "_" + to.toLowerCase() + "/" + h.substring(0, 2) + "/" + h;
    }

    private Resource ensure(String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) return r;
        Resource parent = ensure(ResourceUtil.getParent(path));
        Map<String, Object> p = new HashMap<>();
        p.put("jcr:primaryType", path.length() > ROOT.length() ? "nt:unstructured" : "sling:Folder");
        return resolver.create(parent, ResourceUtil.getName(path), p);
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
