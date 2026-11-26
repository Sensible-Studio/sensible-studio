/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;

/**
 * AEM's Tools › Assets › Assets Configurations: the settings of Media that are not per folder.
 *
 * <ul>
 * <li><b>Upload Restrictions</b>: the MIME types that may be uploaded ({@code image/*} for a family); none listed: any;</li>
 * <li><b>DRM Configuration</b>: whether a file with a license ({@code adobe_dam:restrictions}, the license's text,
 * or {@code xmpRights:WebStatement}, a page with it, in its metadata, as in AEM) is downloaded only after
 * its license is agreed to, and the address of the general usage terms;</li>
 * <li><b>Assets Omnisearch</b>: how many results are counted ({@code guessTotal}: 1000, 500, 200; past it the
 * count says "1000+") and whether a search starts in the current folder or in all of Media;</li>
 * <li><b>Asset Duplication Detector</b>: whether an upload is checked for the same file elsewhere (by
 * {@code dam:sha1}, the only field this check can compute in the browser);</li>
 * <li><b>Link Share</b>: what the Share Link dialog starts with (originals, renditions: included or not;
 * editable, read-only or hidden), how long a link is valid by default, and the groups that may (or may not)
 * share links;</li>
 * <li><b>System Watermarking Profile</b> is {@link DamWatermark}.</li>
 * </ul>
 *
 * Kept where AEM keeps the ones it keeps in the repository: Omnisearch at
 * {@code /conf/global/settings/dam/assetsomnisearch/jcr:content}; the others below {@link #ROOT}, one node each.
 * Read by everyone who uses Media (they change what the console does); changed by administrators.
 */
final class DamAssetsConfig {

    static final String ROOT = "/conf/global/settings/dam/assetsconfigurations";
    static final String OMNISEARCH = "/conf/global/settings/dam/assetsomnisearch/jcr:content";
    static final List<String> NAMES = Collections.unmodifiableList(
            Arrays.asList("uploadrestrictions", "drm", "omnisearch", "duplicates", "linkshare"));
    static final List<Long> GUESS_TOTALS = Collections.unmodifiableList(Arrays.asList(1000L, 500L, 200L));
    static final List<String> MODES = Collections.unmodifiableList(Arrays.asList("editable", "readonly", "hidden"));
    static final List<String> UNITS = Collections.unmodifiableList(Arrays.asList("hours", "days", "months", "years"));
    /** {@code xmpRights:WebStatement}: a page with the license; {@code adobe_dam:restrictions}: the license as HTML. */
    static final String WEB_STATEMENT = "xmpRights:WebStatement";
    static final String RESTRICTIONS = "adobe_dam:restrictions";
    static final String DUPLICATE_FIELD = "dam:sha1";
    private static final Pattern MIME = Pattern.compile("^[a-z0-9][a-z0-9!#$&^_.+-]{0,126}/(\\*|[a-z0-9][a-z0-9!#$&^_.+-]{0,126})$");
    private static final Pattern GROUP = Pattern.compile("^[^/\\\\\\s][^/\\\\]{0,199}$");

    private DamAssetsConfig() {
    }

    // ------------------------------------------------------------------------------------ reading

    private static ValueMap node(ResourceResolver rr, String name) {
        Resource r = rr.getResource("omnisearch".equals(name) ? OMNISEARCH : ROOT + "/" + name);
        return r == null ? ValueMap.EMPTY : r.getValueMap();
    }

    static List<String> allowedMimeTypes(ResourceResolver rr) {
        return list(node(rr, "uploadrestrictions").get("allowedMimeTypes", String[].class));
    }

    static boolean drmEnabled(ResourceResolver rr) {
        return node(rr, "drm").get("enable_drm", false);
    }

    static String usageTermsUrl(ResourceResolver rr) {
        return node(rr, "drm").get("drm_usage_terms_url", "");
    }

    static boolean duplicatesEnabled(ResourceResolver rr) {
        return node(rr, "duplicates").get("enabled", true);
    }

    static long guessTotal(ResourceResolver rr) {
        long g = node(rr, "omnisearch").get("guessTotal", 1000L);
        return GUESS_TOTALS.contains(g) ? g : 1000L;
    }

    /** All the settings, as the console and Media read them. */
    static Map<String, Object> describe(ResourceResolver rr) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> up = new LinkedHashMap<>();
        up.put("allowedMimeTypes", allowedMimeTypes(rr));
        out.put("uploadRestrictions", up);

        ValueMap d = node(rr, "drm");
        Map<String, Object> drm = new LinkedHashMap<>();
        drm.put("enabled", d.get("enable_drm", false));
        drm.put("usageTermsUrl", d.get("drm_usage_terms_url", ""));
        out.put("drm", drm);

        ValueMap o = node(rr, "omnisearch");
        Map<String, Object> omni = new LinkedHashMap<>();
        omni.put("guessTotal", guessTotal(rr));
        // AEM's enableSearchCurrentFolder: true = only in the current folder
        omni.put("currentFolder", o.get("enableSearchCurrentFolder", true));
        out.put("omnisearch", omni);

        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("enabled", duplicatesEnabled(rr));
        dup.put("field", DUPLICATE_FIELD);
        out.put("duplicates", dup);

        out.put("linkShare", linkShare(rr));
        out.put("watermark", DamWatermark.describe(rr));
        return out;
    }

    static Map<String, Object> linkShare(ResourceResolver rr) {
        ValueMap l = node(rr, "linkshare");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("includeOriginals", l.get("includeOriginals", true));
        m.put("originalsMode", mode(l.get("originalsMode", String.class)));
        m.put("includeRenditions", l.get("includeRenditions", false));
        m.put("renditionsMode", mode(l.get("renditionsMode", String.class)));
        long amount = l.get("expirationDurationAmount", 7L);
        String unit = l.get("expirationDurationUnit", "days");
        m.put("validityAmount", amount >= 1 ? amount : 7L);
        m.put("validityUnit", UNITS.contains(unit) ? unit : "days");
        String gm = l.get("groupsAllowOrDeny", "allow");
        m.put("groupsMode", "deny".equals(gm) ? "deny" : "allow");
        m.put("groups", list(l.get("groups", String[].class)));
        return m;
    }

    private static String mode(String m) {
        return m != null && MODES.contains(m) ? m : "editable";
    }

    private static List<String> list(String[] a) {
        List<String> out = new ArrayList<>();
        if (a != null) {
            for (String s : a) {
                if (s != null && !s.trim().isEmpty()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------ what they decide

    /** Whether a MIME type may be uploaded: nothing listed allows all; {@code image/*} a family. */
    static boolean mimeAllowed(List<String> allowed, String mime) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT).trim();
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi).trim();
        }
        for (String a : allowed) {
            String x = a.toLowerCase(Locale.ROOT);
            if (x.equals(m) || x.endsWith("/*") && m.startsWith(x.substring(0, x.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** The license of an asset, or null when it has none: its text ({@code html}) and/or the page with it ({@code url}). */
    static Map<String, Object> license(Resource asset) {
        Resource md = asset == null ? null : asset.getChild("jcr:content/metadata");
        if (md == null) {
            return null;
        }
        ValueMap v = md.getValueMap();
        String html = first(v.get(RESTRICTIONS, String[].class));
        String url = first(v.get(WEB_STATEMENT, String[].class));
        if (html == null && url == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", asset.getPath());
        m.put("title", DamItems.asset(asset).get("title"));
        m.put("text", html == null ? null : textOf(html));
        m.put("url", url == null || !(url.startsWith("/") || url.startsWith("https://") || url.startsWith("http://")) ? null : url);
        return m;
    }

    private static String first(String[] a) {
        if (a != null) {
            for (String s : a) {
                if (s != null && !s.trim().isEmpty()) {
                    return s.trim();
                }
            }
        }
        return null;
    }

    /** A license's HTML as plain text: paragraphs and line breaks kept, every tag dropped (it is shown as text, never as HTML). */
    static String textOf(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        return t.replaceAll("[ \\t]+", " ").replaceAll(" *\\n[ \\n]*", "\n\n").trim();
    }

    /** The licensed assets among (and below) the paths, up to {@code max}. */
    static List<Map<String, Object>> licenses(ResourceResolver rr, Collection<String> paths, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Deque<Resource> queue = new java.util.ArrayDeque<>();
        for (String p : paths) {
            Resource r = DamItems.inDam(p) ? rr.getResource(p) : null;
            if (r != null) {
                queue.add(r);
            }
        }
        int visited = 0;
        while (!queue.isEmpty() && out.size() < max && visited++ < 20_000) {
            Resource r = queue.poll();
            if (DamItems.isAsset(r)) {
                Map<String, Object> l = license(r);
                if (l != null) {
                    out.add(l);
                }
            } else if (DamItems.isFolder(r)) {
                for (Resource c : r.getChildren()) {
                    if (!"jcr:content".equals(c.getName())) {
                        queue.add(c);
                    }
                }
            }
        }
        return out;
    }

    /** Whether this user may make share links: admin always; else as the groups say (none listed: everyone). */
    static boolean mayShare(ResourceResolver rr) {
        String user = rr.getUserID();
        if (user == null || "anonymous".equals(user)) {
            return false;
        }
        if (DamAccess.isAdmin(rr)) {
            return true;
        }
        Map<String, Object> l = linkShare(rr);
        @SuppressWarnings("unchecked")
        List<String> groups = (List<String>) l.get("groups");
        if (groups.isEmpty()) {
            return true;
        }
        boolean member = memberOfAny(rr, user, groups);
        return "deny".equals(l.get("groupsMode")) ? !member : member;
    }

    static boolean memberOfAny(ResourceResolver rr, String user, List<String> groups) {
        Session s = rr.adaptTo(Session.class);
        if (!(s instanceof JackrabbitSession)) {
            return false;
        }
        try {
            Authorizable a = ((JackrabbitSession) s).getUserManager().getAuthorizable(user);
            if (a == null) {
                return false;
            }
            Set<String> wanted = new LinkedHashSet<>(groups);
            if (wanted.contains("everyone")) {
                return true;
            }
            for (Iterator<Group> it = a.memberOf(); it.hasNext();) {
                if (wanted.contains(it.next().getID())) {
                    return true;
                }
            }
        } catch (RepositoryException e) {
            return false;
        }
        return false;
    }

    /** When a link made now with the default validity expires. */
    static Calendar defaultExpiry(ResourceResolver rr) {
        Map<String, Object> l = linkShare(rr);
        return expiry(((Number) l.get("validityAmount")).longValue(), (String) l.get("validityUnit"));
    }

    static Calendar expiry(long amount, String unit) {
        Calendar c = Calendar.getInstance();
        int n = (int) Math.min(amount, 10_000);
        switch (unit) {
            case "hours":
                c.add(Calendar.HOUR_OF_DAY, n);
                break;
            case "months":
                c.add(Calendar.MONTH, n);
                break;
            case "years":
                c.add(Calendar.YEAR, n);
                break;
            default:
                c.add(Calendar.DAY_OF_MONTH, n);
        }
        return c;
    }

    // ------------------------------------------------------------------------------------ saving

    /** Saves one setting (administrators only; checked by the caller). Values are checked first, nothing is written on an error. */
    static Map<String, Object> save(ResourceResolver rr, String name, Map<String, Object> c)
            throws DamApiServlet.ApiException, PersistenceException {
        if (name == null || !NAMES.contains(name)) {
            throw new DamApiServlet.ApiException(400, "a setting is one of " + NAMES);
        }
        if (c == null) {
            throw new DamApiServlet.ApiException(400, "config: a JSON object");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        switch (name) {
            case "uploadrestrictions": {
                List<String> types = new ArrayList<>();
                for (Object o : listOf(c.get("allowedMimeTypes"))) {
                    String t = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
                    if (t.isEmpty()) {
                        continue;
                    }
                    if (!MIME.matcher(t).matches()) {
                        throw new DamApiServlet.ApiException(400, "Not a MIME type: " + o + " (as image/png, or image/* for all images)");
                    }
                    if (!types.contains(t)) {
                        types.add(t);
                    }
                }
                props.put("allowedMimeTypes", types.toArray(new String[0]));
                break;
            }
            case "drm": {
                String url = str(c.get("usageTermsUrl"));
                if (!url.isEmpty() && !(url.startsWith("/") || url.startsWith("https://") || url.startsWith("http://"))) {
                    throw new DamApiServlet.ApiException(400, "The usage terms URL is a path on this site or an http(s) address");
                }
                props.put("enable_drm", Boolean.TRUE.equals(c.get("enabled")));
                props.put("drm_usage_terms_url", url);
                break;
            }
            case "omnisearch": {
                long g = c.get("guessTotal") instanceof Number ? ((Number) c.get("guessTotal")).longValue() : -1;
                if (!GUESS_TOTALS.contains(g)) {
                    throw new DamApiServlet.ApiException(400, "The result count limit is one of " + GUESS_TOTALS);
                }
                props.put("guessTotal", g);
                props.put("enableSearchCurrentFolder", !Boolean.FALSE.equals(c.get("currentFolder")));
                break;
            }
            case "duplicates":
                props.put("enabled", !Boolean.FALSE.equals(c.get("enabled")));
                props.put("detectMetadataField", DUPLICATE_FIELD);
                break;
            default: { // linkshare
                boolean originals = !Boolean.FALSE.equals(c.get("includeOriginals"));
                boolean renditions = Boolean.TRUE.equals(c.get("includeRenditions"));
                String om = str(c.get("originalsMode"));
                String rm = str(c.get("renditionsMode"));
                if (!MODES.contains(om) || !MODES.contains(rm)) {
                    throw new DamApiServlet.ApiException(400, "A mode is one of " + MODES);
                }
                if (!originals && !renditions) {
                    throw new DamApiServlet.ApiException(400, "Either originals or renditions must be included, or both");
                }
                long amount = c.get("validityAmount") instanceof Number ? ((Number) c.get("validityAmount")).longValue() : -1;
                String unit = str(c.get("validityUnit"));
                if (!UNITS.contains(unit)) {
                    throw new DamApiServlet.ApiException(400, "The unit is one of " + UNITS);
                }
                if (amount < 1 || amount > maxAmount(unit)) {
                    throw new DamApiServlet.ApiException(400, "The validity period is 1 to " + maxAmount(unit) + " " + unit);
                }
                String gm = str(c.get("groupsMode"));
                List<String> groups = new ArrayList<>();
                for (Object o : listOf(c.get("groups"))) {
                    String g = String.valueOf(o).trim();
                    if (g.isEmpty()) {
                        continue;
                    }
                    if (!GROUP.matcher(g).matches()) {
                        throw new DamApiServlet.ApiException(400, "Not a group id: " + g);
                    }
                    if (!groups.contains(g)) {
                        groups.add(g);
                    }
                }
                props.put("includeOriginals", originals);
                props.put("originalsMode", om);
                props.put("includeRenditions", renditions);
                props.put("renditionsMode", rm);
                props.put("expirationDurationAmount", amount);
                props.put("expirationDurationUnit", unit);
                props.put("groupsAllowOrDeny", "deny".equals(gm) ? "deny" : "allow");
                props.put("groups", groups.toArray(new String[0]));
            }
        }
        Resource node = ensure(rr, "omnisearch".equals(name) ? OMNISEARCH : ROOT + "/" + name);
        ModifiableValueMap mv = node.adaptTo(ModifiableValueMap.class);
        if (mv == null) {
            throw new DamApiServlet.ApiException(403, "This setting cannot be changed here");
        }
        mv.putAll(props);
        return describe(rr);
    }

    /** At most two years, in any unit. */
    static long maxAmount(String unit) {
        switch (unit) {
            case "hours":
                return 24L * 730;
            case "months":
                return 24;
            case "years":
                return 2;
            default:
                return 730;
        }
    }

    private static List<?> listOf(Object o) {
        return o instanceof List ? (List<?>) o : Collections.emptyList();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Resource ensure(ResourceResolver rr, String path) throws PersistenceException {
        Resource r = rr.getResource("/");
        String[] segs = path.substring(1).split("/");
        for (int i = 0; i < segs.length; i++) {
            Resource c = r.getChild(segs[i]);
            if (c == null) {
                String type = i == segs.length - 1 ? "nt:unstructured" : "sling:Folder";
                c = rr.create(r, segs[i], Collections.singletonMap("jcr:primaryType", type));
            }
            r = c;
        }
        return r;
    }
}
