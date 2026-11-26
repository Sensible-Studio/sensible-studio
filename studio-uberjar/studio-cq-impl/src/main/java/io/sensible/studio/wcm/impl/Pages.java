/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.jetbrains.annotations.Nullable;

import com.day.cq.tagging.Tag;
import com.day.cq.wcm.api.NameConstants;

/**
 * Shared helpers for reading page-shaped content through the Sling resource API.
 */
final class Pages {

    static final String PN_PRIMARY_TYPE = "jcr:primaryType";
    static final String PN_LANGUAGE = "jcr:language";
    static final String PN_POLICY = "cq:policy";

    /** Sensible Studio stores page locks as a plain property; JCR locks are not portable across stores. */
    static final String PN_LOCK_OWNER = "studio:lockOwner";

    /** Tag roots, current location first, legacy second. */
    static final String[] TAG_ROOTS = {"/content/cq:tags", "/etc/tags"};

    /** Properties the repository manages itself; copying them would fail or corrupt identity. */
    static final Set<String> NON_COPYABLE = new HashSet<>(Arrays.asList(
            "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion", "jcr:predecessors",
            "jcr:versionHistory", "jcr:isCheckedOut", "jcr:lockOwner", "jcr:lockIsDeep",
            PN_LOCK_OWNER));

    private static final Set<String> ISO_LANGUAGES = new HashSet<>(Arrays.asList(Locale.getISOLanguages()));

    private Pages() {
    }

    static boolean isPage(@Nullable Resource resource) {
        return hasType(resource, NameConstants.NT_PAGE);
    }

    static boolean isTemplate(@Nullable Resource resource) {
        return hasType(resource, NameConstants.NT_TEMPLATE);
    }

    private static boolean hasType(@Nullable Resource resource, String type) {
        if (resource == null || ResourceUtil.isNonExistingResource(resource)) {
            return false;
        }
        return type.equals(resource.getValueMap().get(PN_PRIMARY_TYPE, String.class))
                || type.equals(resource.getResourceType());
    }

    /**
     * Parses {@code en}, {@code en_US}, {@code en-us} style names. Only ISO 639 languages are
     * accepted, so country folders such as {@code us} are not mistaken for languages.
     */
    @Nullable
    static Locale parseLanguage(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.split("[_-]");
        String language = parts[0].toLowerCase(Locale.ROOT);
        if (language.length() != 2 || !ISO_LANGUAGES.contains(language)) {
            return null;
        }
        if (parts.length == 1) {
            return new Locale(language);
        }
        if (parts.length == 2 && parts[1].length() == 2) {
            return new Locale(language, parts[1].toUpperCase(Locale.ROOT));
        }
        return null;
    }

    /** Turns a title into a repository-safe node name, e.g. "Ski Touring!" to "ski-touring". */
    static String toValidName(@Nullable String title) {
        if (title == null) {
            return "page";
        }
        String name = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return name.isEmpty() ? "page" : name;
    }

    static String parentPath(String path) {
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? "/" : path.substring(0, idx);
    }

    /** For relative paths: {@code a/b/c} to {@code a/b}, {@code a} to the empty string. */
    static String parentPathOrEmpty(String relPath) {
        int idx = relPath.lastIndexOf('/');
        return idx < 0 ? "" : relPath.substring(0, idx);
    }

    static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static String child(String parent, String name) {
        return "/".equals(parent) ? "/" + name : parent + "/" + name;
    }

    /** Resolves {@code ns:path/x}, {@code path/x} (default namespace) or an absolute tag path. */
    @Nullable
    static Tag resolveTag(ResourceResolver resolver, @Nullable String tagId) {
        if (tagId == null || tagId.isEmpty()) {
            return null;
        }
        if (tagId.startsWith("/")) {
            for (String root : TAG_ROOTS) {
                if (tagId.startsWith(root + "/")) {
                    Resource r = resolver.getResource(tagId);
                    return r == null ? null : new TagImpl(r, root);
                }
            }
            return null;
        }
        int colon = tagId.indexOf(':');
        String namespace = colon < 0 ? "default" : tagId.substring(0, colon);
        String local = colon < 0 ? tagId : tagId.substring(colon + 1);
        for (String root : TAG_ROOTS) {
            String path = root + "/" + namespace + (local.isEmpty() ? "" : "/" + local);
            Resource r = resolver.getResource(path);
            if (r != null) {
                return new TagImpl(r, root);
            }
        }
        return null;
    }
}
