/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

/**
 * JSON API of the Assets UI. Everything runs as the requesting user, so repository ACLs decide
 * what each person can see and change.
 *
 * <pre>
 * GET  /bin/studio/dam.json?op=list&amp;path=&amp;offset=&amp;limit=&amp;sort=name|title|modified|size|type&amp;order=asc|desc
 * GET  /bin/studio/dam.json?op=asset&amp;path=          details: metadata, renditions, failures
 * GET  /bin/studio/dam.json?op=folders&amp;path=        sub-folders (move/copy destination picker)
 * GET  /bin/studio/dam.json?op=versions&amp;path=       versions of an asset, newest first
 * GET  /bin/studio/dam.json?op=search&amp;path=&amp;fulltext=&amp;type=&amp;tag=&amp;modified=&amp;minSize=&amp;maxSize=
 *                                          &amp;orientation=&amp;meta=prop=value&amp;metaText=prop=text
 *                                          &amp;sort=&amp;order=&amp;offset=&amp;limit=   (see DamSearch)
 * GET  /bin/studio/dam.json?op=searchForm             the filters of the search (see DamSearchForms)
 * GET  /bin/studio/dam.json?op=metadataValues&amp;path=&amp;property=   values of a metadata property, most used first
 * POST /bin/studio/dam.json  op=saveSearchForm facets (JSON array); op=resetSearchForm   (admin)
 * POST                    op=colorTag      path (repeatable: files or folders), [again]   color tags (see DamColors)
 * GET  /bin/studio/dam.json?op=smartTagTraining[&amp;tag=]   learned tags (see DamSmartTagTraining)
 * POST                    op=trainSmartTags tag (repeatable); op=forgetSmartTag tag   (admin)
 * POST /bin/studio/dam.json  op=createFolder  parent, title, [name]
 * POST                    op=delete        path (repeatable)
 * POST                    op=move          path (repeatable), dest, [name] (rename; single path only),
 *                                          [adjust] (repeatable: pages or resources whose references to the
 *                                          moved paths are rewritten, see {@link DamReferences})
 * POST                    op=copy          path (repeatable), dest
 * POST                    op=reprocess     path (repeatable)
 * POST                    op=metadata      path, then dc:title, dc:description, dc:language, dc:rights,
 *                                          dc:subject, dc:creator, dc:contributor, cq:tags (repeatable;
 *                                          {@code <name>@Delete} clears, {@code <name>@Append=true} adds)
 * POST                    op=folderTitle   path, title
 * POST                    op=createVersion path, [label], [comment]
 * GET  op=notifications [limit]                  the signed-in user's @mentions, newest first, and how many are unread
 * POST op=readNotifications|removeNotifications [id…]   the asker's own (none: all of them)
 * POST                    op=restoreVersion path, version (the current state is saved as a version first)
 * POST                    op=edit          path, [operations] (JSON: crop / rotate / flip, see {@link ImageEdit}),
 *                                          [imageMap] (JSON areas; "[]" removes the map)
 * POST                    op=createSet     parent, title, member... (in order), [kind=gallery|spin]: an image set, a file
 *                                          that names several pictures to be shown together (a gallery may hold videos too;
 *                                          a spin is turned by dragging); op=asset lists them (setMembers, setKind)
 * POST                    op=setMembers    path (a set), member... (replaces them, in this order), [kind]
 * GET  ?op=similar&path=<asset>[&limit=]  the pictures most like this one (items with score; compared, notIndexed)
 * GET  ?op=pictures&q=<phrase>[&path=<folder>][&limit=][&min=][&translate=true]  the pictures that fit a phrase (items with
 *                                          score, best first; those scoring below min, 0.01 unless given, are only counted:
 *                                          weaker). translate=true: the phrase is put into English first by the instance's
 *                                          translation provider (searchedAs says how it was searched; 409 when there is none)
 * POST op=similarIndex [path=<folder>...] queues the embedding of every picture that has none (default: all of the DAM)
 * </pre>
 * {@code edit} replaces the original with the edited image (made by studio-dam), which queues the
 * usual processing, so every rendition follows.
 * POSTs need the header {@code X-Studio-CSRF: 1} (a cross-site form cannot set it).
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/studio/dam",
        "sling.servlet.extensions=json",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class DamApiServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    static final String CSRF_HEADER = "X-Studio-CSRF";
    static final int MAX_LIMIT = 500;

    /** Editable metadata (Basic tab). Multi-valued ones take repeated parameters. */
    static final Set<String> SINGLE = new TreeSet<>(Arrays.asList("dc:title", "dc:description", "dc:language", "dc:rights"));
    static final Set<String> MULTI = new TreeSet<>(Arrays.asList("dc:subject", "dc:creator", "dc:contributor", "cq:tags"));
    /** Tag IDs as AEM writes them: {@code namespace:path/to/tag} (the namespace alone for a namespace). */
    static final java.util.regex.Pattern TAG_ID = java.util.regex.Pattern.compile("^[A-Za-z0-9_\\-.]+:[A-Za-z0-9_\\-./]*$");

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile JobManager jobManager;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile com.day.cq.replication.Replicator replicator;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile DamClient damClient;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile DamEmbeddingIndex embeddingIndex;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile DamSearchIndex searchIndex;

    /** @mentions in comments: tells the users mentioned. */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private transient volatile DamNotifications notifications;

    private transient volatile org.osgi.framework.BundleContext bundleContext;

    @org.osgi.service.component.annotations.Activate
    protected void activate(org.osgi.framework.BundleContext context) {
        this.bundleContext = context;
    }

    public DamApiServlet() {
    }

    DamApiServlet(JobManager jobManager) {
        this.jobManager = jobManager;
    }

    DamApiServlet(JobManager jobManager, DamClient damClient) {
        this.jobManager = jobManager;
        this.damClient = damClient;
    }

    // ------------------------------------------------------------------------------------ GET

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        String op = param(request, "op");
        String path = normalize(param(request, "path"));
        ResourceResolver resolver = request.getResourceResolver();
        if (path == null) {
            path = DamItems.ROOT;
        }
        if (!DamItems.inDam(path)) {
            send(response, 400, error("path must be in " + DamItems.ROOT));
            return;
        }
        Resource r = resolver.getResource(path);
        if (r == null) {
            send(response, 404, error("not found: " + path));
            return;
        }
        if ("assetsConfigurations".equals(op)) {
            // Tools › Assets › Assets Configurations: read by everyone who uses Media, changed by administrators
            Map<String, Object> out = DamAssetsConfig.describe(resolver);
            out.put("admin", DamAccess.isAdmin(resolver));
            out.put("mayShare", DamAssetsConfig.mayShare(resolver));
            send(response, 200, out);
        } else if ("licenses".equals(op)) {
            // DRM: the licensed files among (and below) the items to download, when DRM is on
            Map<String, Object> out = new LinkedHashMap<>();
            boolean on = DamAssetsConfig.drmEnabled(resolver);
            out.put("enabled", on);
            out.put("usageTermsUrl", DamAssetsConfig.usageTermsUrl(resolver));
            out.put("licenses", on ? DamAssetsConfig.licenses(resolver, values(request, "item"), 500) : Collections.emptyList());
            send(response, 200, out);
        } else if ("list".equals(op)) {
            send(response, 200, list(request, r));
        } else if ("asset".equals(op)) {
            if (!DamItems.isAsset(r)) {
                send(response, 400, error("not an asset: " + path));
                return;
            }
            send(response, 200, details(r));
        } else if ("liveCopy".equals(op)) {
            // where a folder or file stands in Live Copy: its source, its status, the live copies following it
            if (!(DamItems.isAsset(r) || DamItems.isFolder(r))) {
                send(response, 400, error("not a Media item: " + path));
                return;
            }
            send(response, 200, DamLiveCopy.describe(resolver, r));
        } else if ("comments".equals(op)) {
            // the comments and notes on the picture of a file (Timeline, Annotate)
            if (!DamItems.isAsset(r)) {
                send(response, 400, error("not an asset: " + path));
                return;
            }
            send(response, 200, DamComments.list(resolver, r));
        } else if ("folderMembers".equals(op)) {
            // the Permissions tab of a folder's properties: members with a role, and "private"
            if (!DamItems.isFolder(r)) {
                send(response, 400, error("not a folder: " + path));
                return;
            }
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                send(response, 501, error("folder members need the JCR"));
                return;
            }
            try {
                send(response, 200, new DamFolderMembers(session).describe(r.getPath()));
            } catch (javax.jcr.RepositoryException e) {
                send(response, 500, error(e.getMessage()));
            }
        } else if ("duplicates".equals(op)) {
            if (!DamAssetsConfig.duplicatesEnabled(resolver)) {
                // Assets Configurations › Asset Duplication Detector is off
                Map<String, Object> off = new LinkedHashMap<>();
                off.put("duplicates", Collections.emptyList());
                off.put("enabled", false);
                send(response, 200, off);
                return;
            }
            String sha1 = Objects.toString(param(request, "sha1"), "").trim();
            if (!sha1.matches("^[0-9a-fA-F]{40}$")) {
                send(response, 400, error("sha1 must be 40 hex digits"));
                return;
            }
            // the whole DAM, as AEM's duplicate detection: a copy elsewhere is still a copy
            Resource root = resolver.getResource(DamItems.ROOT);
            String hex = sha1.toLowerCase(Locale.ROOT);
            List<Map<String, Object>> dups = null;
            Session session = resolver.adaptTo(Session.class);
            if (session != null && root != null && DamIndex.ready(session)) {
                try {
                    dups = new ArrayList<>();
                    for (String p : DamIndexQueries.bySha1(session, hex)) {
                        Resource a = resolver.getResource(p);
                        if (DamItems.isAsset(a) && dups.size() < 10) {
                            dups.add(DamItems.asset(a));
                        }
                    }
                } catch (RepositoryException | RuntimeException e) {
                    dups = null; // no index: walk
                }
            }
            if (dups == null) {
                dups = root == null ? Collections.emptyList() : DamSearch.bySha1(root, hex, 10);
            }
            send(response, 200, Collections.singletonMap("duplicates", dups));
        } else if ("pictures".equals(op)) {
            DamClient c = damClient;
            String q = Objects.toString(param(request, "q"), "").trim();
            if (q.isEmpty() || q.length() > DamSimilar.MAX_TEXT) {
                send(response, 400, error("q: a phrase of 1 to " + DamSimilar.MAX_TEXT + " characters"));
                return;
            }
            if (c == null) {
                send(response, 503, error("studio-dam is not available"));
                return;
            }
            try {
                double min = DamSimilar.MIN_SCORE;
                String m = param(request, "min");
                if (m != null) {
                    try {
                        min = Double.parseDouble(m.trim());
                    } catch (NumberFormatException e) {
                        min = Double.NaN;
                    }
                    if (!(min >= 0 && min <= 1)) {
                        send(response, 400, error("min: a number from 0 to 1"));
                        return;
                    }
                }
                // a description that is not English is put into English first (the model reads only that)
                String asked = q;
                if ("true".equals(param(request, "translate"))) {
                    try {
                        q = PhraseTranslator.toEnglish(bundleContext, resolver, r.getPath(), q);
                    } catch (PhraseTranslator.Unavailable e) {
                        send(response, 409, error(e.getMessage()));
                        return;
                    } catch (PhraseTranslator.Failed e) {
                        send(response, 502, error("The description could not be translated: " + e.getMessage()));
                        return;
                    }
                }
                // with the multilingual model installed a description is read in its own language,
                // unless translating was asked for (the English model knows English best)
                boolean anyLanguage = q.equals(asked) && DamSimilar.available(c, DamSimilar.Space.SEARCH);
                Map<String, Object> found = anyLanguage
                        ? DamSimilar.pictures(resolver, c, searchIndex, r, q, clamp(intParam(request, "limit", 60), 1, 200), min, DamSimilar.Space.SEARCH)
                        : DamSimilar.pictures(resolver, c, embeddingIndex, r, q, clamp(intParam(request, "limit", 60), 1, 200), min);
                if (anyLanguage) {
                    found.put("anyLanguage", true);
                }
                if (!q.equals(asked)) {
                    found.put("searchedAs", q);
                }
                send(response, 200, found);
            } catch (DamClient.DamProcessingException e) {
                boolean noModel = e.getStatus() == 409;
                send(response, noModel ? 409 : 502, error(noModel ? "the model that looks at pictures is not installed" : "studio-dam: " + e.getMessage()));
            } catch (IOException e) {
                send(response, 503, error("studio-dam: " + e.getMessage()));
            }
        } else if ("similar".equals(op)) {
            DamClient c = damClient;
            if (!DamItems.isAsset(r)) {
                send(response, 400, error("not an asset: " + path));
                return;
            }
            if (c == null) {
                send(response, 503, error("studio-dam is not available"));
                return;
            }
            try {
                send(response, 200, DamSimilar.similar(resolver, c, embeddingIndex, r, clamp(intParam(request, "limit", 24), 1, 100)));
            } catch (DamClient.DamProcessingException e) {
                boolean noModel = e.getStatus() == 409;
                send(response, noModel ? 409 : 502, error(noModel ? "the model that compares pictures is not installed" : "studio-dam: " + e.getMessage()));
            } catch (IOException e) {
                send(response, 503, error("studio-dam: " + e.getMessage()));
            }
        } else if ("smartTags".equals(op)) {
            Map<String, Object> st = smartTagsStatus(resolver);
            if (DamItems.isFolder(r) || DamItems.ROOT.equals(r.getPath())) {
                // what a folder here would get when it inherits
                st.put("effective", DamSmartTags.enabledIn(r, DamSmartTags.settings(resolver)));
            }
            send(response, 200, st);
        } else if ("collections".equals(op)) {
            send(response, 200, Collections.singletonMap("collections", DamCollections.list(resolver)));
        } else if ("collection".equals(op)) {
            try {
                Resource c = DamCollections.get(resolver, path, false);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("collection", DamCollections.describe(resolver, c));
                List<Map<String, Object>> items = DamCollections.members(resolver, c);
                out.put("total", items.size());
                int offset = Math.max(0, intParam(request, "offset", 0));
                int limit = clamp(intParam(request, "limit", 500), 1, MAX_LIMIT);
                out.put("items", items.subList(Math.min(offset, items.size()), Math.min(items.size(), offset + limit)));
                send(response, 200, out);
            } catch (ApiException e) {
                send(response, e.status, error(e.getMessage()));
            }
        } else if ("schemas".equals(op)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("schemas", DamSchemas.list(resolver));
            if (DamItems.isFolder(r) || DamItems.isAsset(r)) {
                Resource probe = DamItems.isFolder(r) ? r.getParent() : r;
                Resource schema = probe == null ? null : DamSchemas.schemaFor(probe);
                out.put("effective", schema == null ? null : schema.getPath());
            }
            send(response, 200, out);
        } else if ("forms".equals(op)) {
            // folder metadata schemas or metadata profiles; with a folder or asset, the one that applies there
            try {
                DamForms.Kind kind = DamForms.Kind.of(param(request, "kind"));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("forms", DamForms.list(resolver, kind));
                if (DamItems.isFolder(r) || DamItems.isAsset(r)) {
                    Resource f = DamForms.formFor(r, kind);
                    out.put("effective", f == null ? null : f.getPath());
                }
                send(response, 200, out);
            } catch (ApiException e) {
                send(response, e.status, error(e.getMessage()));
            }
        } else if ("smartTagTraining".equals(op)) {
            // Smart Tag training: the learned tags; with tag=, how many pictures could teach each
            send(response, 200, DamSmartTagTraining.status(resolver, values(request, "tag")));
        } else if ("notifications".equals(op)) {
            // the signed-in user's notifications (@mentions in comments), newest first
            DamNotifications n = notifications;
            Map<String, Object> out = new LinkedHashMap<>();
            if (n == null) {
                out.put("notifications", Collections.emptyList());
                out.put("unread", 0);
            } else {
                out = n.list(resolver.getUserID(), clamp(intParam(request, "limit", 50), 1, 200));
            }
            send(response, 200, out);
        } else if ("searchForm".equals(op)) {
            // Search Forms: the filters the Media search shows
            send(response, 200, DamSearchForms.read(resolver));
        } else if ("metadataValues".equals(op)) {
            String property = param(request, "property");
            if (property == null || !DamSearchForms.PROPERTY.matcher(property).matches()) {
                send(response, 400, error("property is a metadata property, such as dc:creator"));
            } else if (!DamItems.isFolder(r) && !DamItems.ROOT.equals(r.getPath())) {
                send(response, 400, error("not a folder: " + path));
            } else {
                send(response, 200, DamSearchForms.values(r, property, clamp(intParam(request, "limit", 50), 1, 200)));
            }
        } else if ("folderMetadata".equals(op)) {
            if (DamItems.isFolder(r)) {
                send(response, 200, DamForms.folderMetadata(r));
            } else {
                send(response, 400, error("not a folder: " + path));
            }
        } else if ("imageProfiles".equals(op)) {
            // Smart Crop profiles; with a folder, the one that applies there
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profiles", DamSmartCrop.profiles(resolver));
            if (DamItems.isFolder(r) || DamItems.isAsset(r)) {
                Resource probe = DamItems.isFolder(r) ? r.getChild("jcr:content") != null ? r.getChild("jcr:content") : r : r;
                Resource p = DamSmartCrop.profileFor(DamItems.isFolder(r) ? probe : r);
                out.put("effective", p == null ? null : p.getPath());
            }
            out.put("admin", DamAccess.isAdmin(resolver));
            send(response, 200, out);
        } else if ("galleryPresets".equals(op)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("presets", DamGalleryPresets.list(resolver));
            out.put("admin", DamAccess.isAdmin(resolver));
            send(response, 200, out);
        } else if ("imagePresets".equals(op)) {
            // Dynamic Media: the presets, and for an asset the URL of each
            Map<String, Object> out = new LinkedHashMap<>();
            List<Map<String, Object>> presets = DynamicMedia.presets(resolver);
            String publicPath = DamItems.isAsset(r) ? DynamicMedia.publicPath(r.getPath()) : null;
            if (publicPath != null) {
                for (Map<String, Object> p : presets) {
                    p.put("url", publicPath + "?$" + p.get("name") + "$");
                }
                out.put("url", publicPath);
            }
            out.put("presets", presets);
            out.put("parameters", new ArrayList<>(DynamicMedia.parameterNames()));
            out.put("admin", DamAccess.isAdmin(resolver));
            send(response, 200, out);
        } else if ("profiles".equals(op)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("profiles", DamProfiles.list(resolver));
            out.put("watermark", DamWatermark.describe(resolver));
            out.put("admin", DamAccess.isAdmin(resolver));
            if (DamItems.isFolder(r)) {
                // what applies here, and from where
                Resource probe = r.getChild("jcr:content") != null ? r.getChild("jcr:content") : r;
                Resource profile = DamProfiles.profileFor(probe);
                out.put("effective", profile == null ? null : profile.getPath());
            }
            send(response, 200, out);
        } else if ("search".equals(op)) {
            if (!DamItems.isFolder(r) && !DamItems.ROOT.equals(r.getPath())) {
                send(response, 400, error("search scope must be a folder: " + path));
                return;
            }
            DamSearch.Query q = new DamSearch.Query();
            q.fulltext = Objects.toString(param(request, "fulltext"), "");
            for (String k : values(request, "type")) {
                if (DamSearch.KINDS.contains(k)) {
                    q.kinds.add(k);
                }
            }
            for (String t : values(request, "tag")) {
                if (TAG_ID.matcher(t).matches()) {
                    q.tags.add(t);
                }
            }
            String modified = Objects.toString(param(request, "modified"), "");
            if (modified.matches("\\d{1,4}d")) {
                q.modifiedDays = Integer.parseInt(modified.substring(0, modified.length() - 1));
            }
            q.minSize = longParam(request, "minSize");
            q.maxSize = longParam(request, "maxSize");
            String o = param(request, "orientation");
            q.orientation = "landscape".equals(o) || "portrait".equals(o) || "square".equals(o) ? o : null;
            for (String m : values(request, "meta")) {
                q.addMeta(m);
            }
            for (String m : values(request, "metaText")) {
                q.addMetaText(m);
            }
            String sort = param(request, "sort");
            q.sort = sort != null && sort.matches("relevance|name|title|modified|size") ? sort : null;
            q.descending = "desc".equals(param(request, "order"));
            DamSearch.Result result = DamSearch.find(resolver.adaptTo(Session.class), r, q, System.currentTimeMillis());
            int offset = Math.max(0, intParam(request, "offset", 0));
            int limit = clamp(intParam(request, "limit", 100), 1, MAX_LIMIT);
            Map<String, Object> found = DamSearch.response(r, result, offset, limit);
            // Assets Omnisearch: counted up to the limit, "1000+" past it (AEM's guessTotal)
            long guess = DamAssetsConfig.guessTotal(resolver);
            if (found.get("total") instanceof Number && ((Number) found.get("total")).longValue() > guess) {
                found.put("total", guess);
                found.put("more", true);
            }
            send(response, 200, found);
        } else if ("versions".equals(op)) {
            if (!DamItems.isAsset(r)) {
                send(response, 400, error("not an asset: " + path));
                return;
            }
            Session session = resolver.adaptTo(Session.class);
            try {
                send(response, 200, Collections.singletonMap("versions", session == null ? Collections.emptyList()
                        : DamVersions.list(session, path)));
            } catch (RepositoryException e) {
                send(response, 500, error("cannot read versions: " + e.getMessage()));
            }
        } else if ("folders".equals(op)) {
            List<Object> folders = new ArrayList<>();
            for (Resource c : r.getChildren()) {
                if (DamItems.isFolder(c)) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("path", c.getPath());
                    f.put("name", c.getName());
                    f.put("title", DamItems.title(c));
                    f.put("hasChildren", hasSubfolders(c));
                    folders.add(f);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("path", r.getPath());
            out.put("folders", folders);
            send(response, 200, out);
        } else {
            send(response, 400, error("unknown op"));
        }
    }

    private Map<String, Object> list(SlingHttpServletRequest request, Resource folder) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", folder.getPath());
        out.put("title", folder.getPath().equals(DamItems.ROOT) ? "Assets" : DamItems.title(folder));
        out.put("type", DamItems.isAsset(folder) ? "asset" : "folder");
        out.put("breadcrumbs", breadcrumbs(folder));
        ResourceResolver resolver = folder.getResourceResolver();
        out.putAll(DamAccess.permissions(resolver, folder.getPath(), !DamItems.isAsset(folder)));

        List<Map<String, Object>> items = new ArrayList<>();
        if (!DamItems.isAsset(folder)) {
            for (Resource c : folder.getChildren()) {
                if (DamItems.isAsset(c) || DamItems.isFolder(c)) {
                    Map<String, Object> item = DamItems.item(c);
                    DamAccess.putDenied(item, resolver, c.getPath(), !DamItems.isAsset(c));
                    items.add(item);
                }
            }
        }
        sort(items, param(request, "sort"), !"desc".equals(param(request, "order")));
        int total = items.size();
        int offset = clamp(intParam(request, "offset", 0), 0, total);
        int limit = clamp(intParam(request, "limit", 100), 1, MAX_LIMIT);
        out.put("total", total);
        out.put("offset", offset);
        out.put("items", items.subList(offset, Math.min(total, offset + limit)));
        return out;
    }

    static void sort(List<Map<String, Object>> items, String by, boolean asc) {
        if (by == null || by.isEmpty() || "none".equals(by)) {
            return; // repository order, like AEM's "None"
        }
        Comparator<Map<String, Object>> cmp;
        switch (by) {
            case "title":
                cmp = Comparator.comparing(m -> lower(m.get("title")));
                break;
            case "modified":
                cmp = Comparator.comparingLong(m -> m.get("modified") instanceof Calendar
                        ? ((Calendar) m.get("modified")).getTimeInMillis() : 0L);
                break;
            case "size":
                cmp = Comparator.comparingLong(m -> m.get("size") instanceof Number ? ((Number) m.get("size")).longValue() : -1L);
                break;
            case "type":
                cmp = Comparator.comparing(m -> lower(m.get("type")) + "/" + lower(m.get("mimeType")));
                break;
            default:
                cmp = Comparator.comparing(m -> lower(m.get("name")));
        }
        items.sort(asc ? cmp : cmp.reversed());
    }

    private static String lower(Object o) {
        return o == null ? "" : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    private static List<Object> breadcrumbs(Resource r) {
        List<Object> crumbs = new ArrayList<>();
        for (Resource p = r; p != null && DamItems.inDam(p.getPath()); p = p.getParent()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("path", p.getPath());
            c.put("title", p.getPath().equals(DamItems.ROOT) ? "Assets" : DamItems.title(p));
            crumbs.add(0, c);
        }
        return crumbs;
    }

    private static boolean hasSubfolders(Resource r) {
        for (Resource c : r.getChildren()) {
            if (DamItems.isFolder(c)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> details(Resource asset) {
        Map<String, Object> out = DamItems.asset(asset);
        Resource mdRes = asset.getChild("jcr:content/metadata");
        Map<String, Object> md = new LinkedHashMap<>();
        if (mdRes != null) {
            for (Map.Entry<String, Object> e : new java.util.TreeMap<>(mdRes.getValueMap()).entrySet()) {
                if (!e.getKey().startsWith("jcr:primaryType") && !e.getKey().startsWith("jcr:mixinTypes")) {
                    md.put(e.getKey(), e.getValue() instanceof InputStream ? null : e.getValue());
                }
            }
        }
        out.put("metadata", md);
        Object mime = out.get("mimeType");
        DamClient tools = damClient;
        if (tools != null && AssetProcessor.isVideo(mime == null ? null : mime.toString())) {
            // without ffmpeg next to studio-dam a video gets no poster, thumbnails or browser copy: the console says so
            Boolean ffmpeg = tools.videoTools();
            if (Boolean.FALSE.equals(ffmpeg)) {
                out.put("videoTools", false);
            }
        }
        if (DamSets.isSet(asset)) {
            // the pictures of a set, in order; and how many it names that are gone or hidden from this reader
            List<Object> pictures = new ArrayList<>();
            for (Resource m : DamSets.members(asset)) {
                pictures.add(DamItems.asset(m));
            }
            out.put("setMembers", pictures);
            out.put("setMissing", DamSets.memberPaths(asset).size() - pictures.size());
        }
        List<Object> renditions = new ArrayList<>();
        Resource folder = asset.getChild("jcr:content/renditions");
        if (folder != null) {
            for (Resource rend : folder.getChildren()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", rend.getName());
                Resource c = rend.getChild("jcr:content");
                ValueMap cv = c == null ? ValueMap.EMPTY : c.getValueMap();
                m.put("mimeType", cv.get("jcr:mimeType", String.class));
                Resource rmd = rend.getChild("jcr:content/metadata");
                ValueMap rv = rmd == null ? ValueMap.EMPTY : rmd.getValueMap();
                long len = rend.getResourceMetadata().getContentLength();
                m.put("size", rv.get("dam:size", len > 0 ? len : null));
                m.put("width", rv.get("tiff:ImageWidth", Long.class));
                m.put("height", rv.get("tiff:ImageLength", Long.class));
                m.put("url", DamItems.url(rend));
                renditions.add(m);
            }
        }
        out.put("renditions", renditions);
        List<Object> failed = new ArrayList<>();
        Resource fr = asset.getChild("jcr:content/dam:failedRenditions");
        if (fr != null) {
            for (Resource f : fr.getChildren()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getName());
                m.put("reason", f.getValueMap().get("reason", String.class));
                failed.add(m);
            }
        }
        out.put("failed", failed);
        Resource content = asset.getChild("jcr:content");
        out.put("processingError", content == null ? null : content.getValueMap().get("dam:processingError", String.class));
        out.put("imageMap", imageMap(asset));
        out.put("breadcrumbs", breadcrumbs(asset));
        out.put("related", DamRelations.describe(asset.getResourceResolver(), asset));
        out.putAll(DamAccess.permissions(asset.getResourceResolver(), asset.getPath(), false));
        Resource schema = DamSchemas.schemaFor(asset);
        out.put("schema", schema == null ? null : DamSchemas.describe(schema));
        out.put("smartTags", DamSmartTags.describe(asset.getResourceResolver(), asset));
        out.put("colors", DamColors.describe(asset));
        out.put("smartCrops", DamSmartCrop.crops(asset));
        return out;
    }

    // ------------------------------------------------------------------------------------ POST

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!"1".equals(request.getHeader(CSRF_HEADER))) {
            send(response, 403, error("missing " + CSRF_HEADER + " header"));
            return;
        }
        ResourceResolver resolver = request.getResourceResolver();
        String op = param(request, "op");
        try {
            Map<String, Object> result;
            switch (op == null ? "" : op) {
                case "createFolder":
                    result = createFolder(resolver, normalize(param(request, "parent")), param(request, "title"), param(request, "name"));
                    break;
                case "delete":
                    result = delete(resolver, paths(request));
                    break;
                case "move":
                    result = move(resolver, paths(request), normalize(param(request, "dest")), param(request, "name"),
                            values(request, "adjust"));
                    break;
                case "copy":
                    result = copy(resolver, paths(request), normalize(param(request, "dest")));
                    break;
                case "reprocess":
                    result = reprocess(resolver, paths(request));
                    break;
                case "metadata":
                    result = metadata(request, resolver, normalize(param(request, "path")));
                    break;
                case "folderTitle":
                    result = folderTitle(resolver, normalize(param(request, "path")), param(request, "title"));
                    break;
                case "edit":
                    result = edit(request, resolver, normalize(param(request, "path")));
                    break;
                case "createVersion":
                case "restoreVersion":
                    result = version(op, request, resolver, normalize(param(request, "path")));
                    break;
                case "checkout":
                case "checkin": {
                    List<String> done = new ArrayList<>();
                    for (Resource a : assets(resolver, paths(request))) {
                        if ("checkout".equals(op)) {
                            DamLocks.checkout(resolver, a);
                        } else {
                            DamLocks.checkin(resolver, a);
                        }
                        done.add(a.getPath());
                    }
                    result = Collections.singletonMap("paths", done);
                    break;
                }
                case "createSet": {
                    Resource parent = folder(resolver, normalize(param(request, "parent")));
                    String title = Objects.toString(param(request, "title"), "").trim();
                    String n = nodeName(title);
                    if (n == null) {
                        throw new ApiException(400, "a title is required");
                    }
                    Resource set = DamSets.create(resolver, parent, uniqueName(parent, n), title, values(request, "member"),
                            DamSets.validKind(param(request, "kind")));
                    result = Collections.singletonMap("path", set.getPath());
                    break;
                }
                case "setMembers": {
                    Resource set = writable(resolver, normalize(param(request, "path")));
                    if (!DamSets.isSet(set)) {
                        throw new ApiException(400, "not an image set: " + set.getPath());
                    }
                    String kind = param(request, "kind");
                    DamSets.setMembers(resolver, set, values(request, "member"), kind == null ? null : DamSets.validKind(kind));
                    result = Collections.singletonMap("path", set.getPath());
                    break;
                }
                case "relate": {
                    List<Resource> list = assets(resolver, paths(request));
                    if (list.size() < 2) {
                        throw new ApiException(400, "relate needs two assets or more");
                    }
                    result = Collections.singletonMap("added", DamRelations.relate(resolver, list));
                    break;
                }
                case "unrelate": {
                    Resource a = assets(resolver, Collections.singletonList(normalize(param(request, "path")))).get(0);
                    List<Resource> others = new ArrayList<>();
                    for (String o : values(request, "other")) {
                        Resource r = resolver.getResource(o);
                        if (r != null) {
                            others.add(r);
                        }
                    }
                    DamRelations.unrelate(resolver, a, others);
                    result = Collections.singletonMap("path", a.getPath());
                    break;
                }
                case "createCollection":
                    result = DamCollections.create(resolver, param(request, "title"), param(request, "description"),
                            "true".equals(param(request, "public")), normalize(param(request, "scope")), param(request, "query"));
                    break;
                case "updateCollection": {
                    Resource c = DamCollections.get(resolver, normalize(param(request, "collection")), true);
                    String pub = param(request, "public");
                    DamCollections.update(c, param(request, "title"), param(request, "description"), pub == null ? null : "true".equals(pub));
                    result = DamCollections.describe(resolver, c);
                    break;
                }
                case "deleteCollection": {
                    Resource c = DamCollections.get(resolver, normalize(param(request, "collection")), true);
                    if (!DamCollections.mine(resolver, c)) {
                        throw new ApiException(403, "only the owner can delete this collection");
                    }
                    resolver.delete(c);
                    result = Collections.singletonMap("deleted", c.getPath());
                    break;
                }
                case "addToCollection":
                case "removeFromCollection": {
                    Resource c = DamCollections.get(resolver, normalize(param(request, "collection")), true);
                    result = Collections.singletonMap("changed",
                            DamCollections.change(resolver, c, paths(request), "removeFromCollection".equals(op)));
                    break;
                }
                case "saveSchema": {
                    Object def;
                    try {
                        def = Json.parse(Objects.toString(param(request, "definition"), ""));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    result = DamSchemas.save(resolver, param(request, "name"), param(request, "title"), def);
                    break;
                }
                case "deleteSchema":
                    DamSchemas.delete(resolver, param(request, "name"));
                    result = Collections.singletonMap("deleted", param(request, "name"));
                    break;
                case "folderThumbnail": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    String asset = normalize(param(request, "asset"));
                    if (asset != null && !asset.isEmpty() && !(asset.startsWith(f.getPath() + "/") && !asset.contains("..") && DamItems.isAsset(resolver.getResource(asset)))) {
                        throw new ApiException(400, "the thumbnail must be an asset inside the folder");
                    }
                    Resource c = f.getChild("jcr:content");
                    if (c == null) {
                        c = resolver.create(f, "jcr:content", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
                    }
                    ModifiableValueMap m = c.adaptTo(ModifiableValueMap.class);
                    if (m == null) {
                        throw new ApiException(403, "cannot change " + f.getPath());
                    }
                    if (asset == null || asset.isEmpty()) {
                        m.remove(DamItems.PN_THUMBNAIL);
                    } else {
                        m.put(DamItems.PN_THUMBNAIL, asset);
                    }
                    result = Collections.singletonMap("path", f.getPath());
                    break;
                }
                case "folderSmartTags": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    DamSmartTags.setFolder(resolver, f, param(request, "value"));
                    result = Collections.singletonMap("path", f.getPath());
                    break;
                }
                case "saveForm": {
                    Object def;
                    try {
                        def = Json.parse(Objects.toString(param(request, "definition"), ""));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    result = DamForms.save(resolver, DamForms.Kind.of(param(request, "kind")), param(request, "name"), param(request, "title"), def);
                    break;
                }
                case "saveSearchForm": {
                    admin(resolver, "only admin can change the search form");
                    Object facets;
                    try {
                        facets = Json.parse(Objects.toString(param(request, "facets"), ""));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    result = DamSearchForms.save(resolver, facets);
                    break;
                }
                case "resetSearchForm":
                    admin(resolver, "only admin can change the search form");
                    result = DamSearchForms.reset(resolver);
                    break;
                case "deleteForm":
                    DamForms.delete(resolver, DamForms.Kind.of(param(request, "kind")), param(request, "name"));
                    result = Collections.singletonMap("deleted", param(request, "name"));
                    break;
                case "folderForm": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    DamForms.assign(f, DamForms.Kind.of(param(request, "kind")), param(request, "form"));
                    result = Collections.singletonMap("path", f.getPath());
                    break;
                }
                case "applyProfile":
                    result = DamForms.applyProfile(resolver, folder(resolver, normalize(param(request, "path"))));
                    break;
                case "folderMetadataFields": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    Object fields;
                    try {
                        fields = Json.parse(Objects.toString(param(request, "fields"), ""));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    result = Collections.singletonMap("changed", DamForms.writeFolderMetadata(resolver, f, fields));
                    break;
                }
                case "folderSchema": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    DamSchemas.assign(f, param(request, "schema"));
                    result = Collections.singletonMap("path", f.getPath());
                    break;
                }
                case "metadataFields": {
                    Resource a = writable(resolver, normalize(param(request, "path")));
                    Resource mdRes = DamItems.isAsset(a) ? a.getChild("jcr:content/metadata") : null;
                    ModifiableValueMap md = mdRes == null ? null : mdRes.adaptTo(ModifiableValueMap.class);
                    if (md == null) {
                        throw new ApiException(DamItems.isAsset(a) ? 403 : 400, "cannot change the metadata of " + a.getPath());
                    }
                    Object fields;
                    try {
                        fields = Json.parse(Objects.toString(param(request, "fields"), ""));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    List<String> changed = DamSchemas.write(md, fields);
                    DamLiveCopy.keepLocal(a, changed);
                    ModifiableValueMap c = a.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
                    if (c != null) {
                        c.put("jcr:lastModified", Calendar.getInstance());
                        c.put("jcr:lastModifiedBy", resolver.getUserID());
                    }
                    result = Collections.singletonMap("changed", changed);
                    break;
                }
                case "saveProfile": {
                    Object rs;
                    try {
                        rs = Json.parse(Objects.toString(param(request, "renditions"), "[]"));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    if (!(rs instanceof List)) {
                        throw new ApiException(400, "renditions must be a JSON array");
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) rs;
                    result = DamProfiles.save(resolver, param(request, "name"), param(request, "title"), list);
                    break;
                }
                case "saveImageProfile": {
                    admin(resolver, "only admin can change image profiles");
                    Object rs;
                    try {
                        rs = Json.parse(Objects.toString(param(request, "ratios"), "[]"));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    if (!(rs instanceof List)) {
                        throw new ApiException(400, "ratios must be a JSON array");
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) rs;
                    result = DamSmartCrop.save(resolver, param(request, "name"), param(request, "title"), list);
                    break;
                }
                case "deleteImageProfile":
                    admin(resolver, "only admin can change image profiles");
                    DamSmartCrop.delete(resolver, param(request, "name"));
                    result = Collections.singletonMap("deleted", param(request, "name"));
                    break;
                case "createLiveCopy":
                case "liveCopySync":
                case "rollout":
                case "liveCopySuspend":
                case "liveCopyDetach":
                case "liveCopyInherit":
                case "liveCopyPush":
                    result = liveCopy(op, request, resolver);
                    break;
                case "comment":
                case "deleteComment": {
                    Resource a = existing(resolver, normalize(param(request, "path")));
                    if (!DamItems.isAsset(a)) {
                        throw new ApiException(400, "not an asset");
                    }
                    try {
                        if ("comment".equals(op)) {
                            DamComments.Region region = null;
                            String shape = param(request, "shape");
                            if (param(request, "x") != null || param(request, "points") != null) {
                                try {
                                    boolean drawn = "arrow".equals(shape) || "free".equals(shape);
                                    region = DamComments.Region.of(shape, param(request, "points"),
                                            drawn ? 0 : Double.parseDouble(param(request, "x")), drawn ? 0 : Double.parseDouble(param(request, "y")),
                                            drawn ? 0 : Double.parseDouble(param(request, "w")), drawn ? 0 : Double.parseDouble(param(request, "h")));
                                    if (param(request, "time") != null) {
                                        region.time = Double.parseDouble(param(request, "time"));
                                    }
                                } catch (NumberFormatException | NullPointerException e) {
                                    throw new ApiException(400, "a note on the picture needs x, y, w and h, or the points of its line");
                                }
                            }
                            result = DamComments.add(resolver, a, param(request, "text"), region, param(request, "color"));
                        } else {
                            DamComments.remove(resolver, a, param(request, "id"));
                            result = Collections.singletonMap("removed", param(request, "id"));
                        }
                        resolver.commit();
                        DamNotifications n = notifications;
                        if ("comment".equals(op) && n != null) {
                            // after the comment is saved: whoever is @mentioned in it is told
                            Map<String, Object> withTold = new LinkedHashMap<>(result);
                            withTold.put("mentioned", n.mentioned(resolver.getUserID(), a.getPath(), String.valueOf(result.get("id")),
                                    String.valueOf(result.get("text"))));
                            result = withTold;
                        }
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(422, e.getMessage());
                    } catch (SecurityException e) {
                        throw new ApiException(403, e.getMessage());
                    }
                    break;
                }
                case "readNotifications":
                case "removeNotifications": {
                    // id (repeated) or none for all; only the asker's own
                    DamNotifications n = notifications;
                    if (n == null) {
                        throw new ApiException(503, "notifications are not running");
                    }
                    try {
                        result = n.update(resolver.getUserID(), values(request, "id"), "removeNotifications".equals(op));
                    } catch (SecurityException e) {
                        throw new ApiException(403, e.getMessage());
                    }
                    break;
                }
                case "folderMembers": {
                    // members (member=<user or group id>:<owner|editor|viewer>, repeated) and private=true|false,
                    // written with the asker's session: owners and administrators may
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    if (DamItems.ROOT.equals(f.getPath())) {
                        throw new ApiException(400, "members are set on a folder inside " + DamItems.ROOT);
                    }
                    Session session = resolver.adaptTo(Session.class);
                    if (session == null) {
                        throw new ApiException(501, "folder members need the JCR");
                    }
                    try {
                        DamFolderMembers fm = new DamFolderMembers(session);
                        if (!fm.canManage(f.getPath())) {
                            throw new ApiException(403, "only the folder's owners and administrators change its members");
                        }
                        List<DamFolderMembers.Member> members = new ArrayList<>();
                        for (String v : values(request, "member")) {
                            int i = v.lastIndexOf(':');
                            if (i <= 0) {
                                throw new ApiException(400, "member is <id>:<role>: " + v);
                            }
                            members.add(new DamFolderMembers.Member(v.substring(0, i), v.substring(i + 1)));
                        }
                        fm.save(f.getPath(), members, "true".equals(param(request, "private")));
                        session.save();
                        result = fm.describe(f.getPath());
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(422, e.getMessage());
                    } catch (javax.jcr.AccessDeniedException e) {
                        throw new ApiException(403, "not allowed: " + e.getMessage());
                    } catch (javax.jcr.RepositoryException e) {
                        throw new ApiException(500, e.getMessage());
                    }
                    break;
                }
                case "folderImageProfile": {
                    // the folder's Smart Crop profile; its images are cropped in the background
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    DamSmartCrop.assign(f, param(request, "profile"));
                    resolver.commit();
                    int queued = queueSmartCrops(resolver, Collections.singletonList(f.getPath()));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("path", f.getPath());
                    out.put("queued", queued);
                    result = out;
                    break;
                }
                case "smartCropRun":
                    // crops for the images of paths (folders: everything below), in the background
                    result = Collections.singletonMap("queued", queueSmartCrops(resolver, paths(request)));
                    break;
                case "smartCrop": {
                    // an author's own crop
                    Resource a = writable(resolver, normalize(param(request, "path")));
                    result = DamSmartCrop.adjust(resolver, a, param(request, "name"), dbl(request, "left"), dbl(request, "top"),
                            dbl(request, "width"));
                    break;
                }
                case "smartCropReset": {
                    // back to the computed crops, manual ones included
                    Resource a = writable(resolver, normalize(param(request, "path")));
                    DamClient c = damClient;
                    if (c == null || !c.isAvailable()) {
                        throw new ApiException(503, "studio-dam is not available");
                    }
                    try {
                        DamSmartCrop.compute(resolver, c, a, true);
                    } catch (IOException e) {
                        throw new ApiException(503, "studio-dam: " + e.getMessage());
                    }
                    result = Collections.singletonMap("smartCrops", DamSmartCrop.crops(a));
                    break;
                }
                case "saveImagePreset": {
                    admin(resolver, "only admin can change image presets");
                    Map<String, String> params = new LinkedHashMap<>();
                    for (String k : DynamicMedia.PRESET_PARAMS) {
                        params.put(k, param(request, k));
                    }
                    result = DynamicMedia.save(resolver, param(request, "name"), param(request, "title"), param(request, "description"), params);
                    break;
                }
                case "deleteImagePreset":
                    admin(resolver, "only admin can change image presets");
                    DynamicMedia.delete(resolver, param(request, "name"));
                    result = Collections.singletonMap("deleted", param(request, "name"));
                    break;
                case "saveGalleryPreset": {
                    admin(resolver, "only an administrator changes gallery presets");
                    Map<String, Object> saved;
                    try {
                        saved = DamGalleryPresets.save(resolver, param(request, "name"), param(request, "title"), param(request, "thumbs"),
                                param(request, "arrows"), param(request, "ratio"), param(request, "zoom"));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(400, e.getMessage());
                    }
                    resolver.commit();
                    // the published site renders galleries with it
                    saved.put("published", publish(resolver, String.valueOf(saved.get("path")), true));
                    result = saved;
                    break;
                }
                case "deleteGalleryPreset": {
                    admin(resolver, "only an administrator changes gallery presets");
                    String path = DamGalleryPresets.ROOT + "/" + param(request, "name");
                    boolean unpublished = resolver.getResource(path) != null && publish(resolver, path, false);
                    try {
                        DamGalleryPresets.delete(resolver, param(request, "name"));
                    } catch (IllegalArgumentException e) {
                        throw new ApiException(404, e.getMessage());
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("deleted", param(request, "name"));
                    m.put("unpublished", unpublished);
                    result = m;
                    break;
                }
                case "saveAssetsConfiguration": {
                    admin(resolver, "only an administrator changes the Assets Configurations");
                    Map<String, Object> config;
                    try {
                        config = Json.parseObject(Objects.toString(param(request, "config"), ""));
                    } catch (RuntimeException e) {
                        throw new ApiException(400, "config: a JSON object");
                    }
                    result = DamAssetsConfig.save(resolver, param(request, "name"), config);
                    result.put("admin", true);
                    result.put("mayShare", true);
                    break;
                }
                case "watermark":
                    admin(resolver, "only an administrator changes the watermark");
                    result = DamWatermark.save(resolver, param(request, "image"), param(request, "position"),
                            param(request, "opacity"), param(request, "size"));
                    break;
                case "deleteProfile":
                    DamProfiles.delete(resolver, param(request, "name"));
                    result = Collections.singletonMap("deleted", param(request, "name"));
                    break;
                case "folderProfile": {
                    Resource f = folder(resolver, normalize(param(request, "path")));
                    DamProfiles.assign(f, param(request, "profile"));
                    result = Collections.singletonMap("path", f.getPath());
                    break;
                }
                case "importMetadata": {
                    RequestParameter csv = request.getRequestParameter("csv");
                    if (csv == null) {
                        throw new ApiException(400, "no csv");
                    }
                    if (csv.getSize() > 50L * 1024 * 1024) {
                        throw new ApiException(413, "the CSV is larger than 50 MB");
                    }
                    result = DamCsv.importCsv(resolver, new String(csv.get(), java.nio.charset.StandardCharsets.UTF_8));
                    break;
                }
                case "extractArchive":
                    result = extractArchive(request, resolver, normalize(param(request, "path")));
                    break;
                case "smartTagsSettings": {
                    admin(resolver);
                    DamSmartTags.Settings st = DamSmartTags.settings(resolver);
                    st.enabled = "true".equals(param(request, "enabled"));
                    st.tagRoots = values(request, "tagRoot");
                    st.keywords = !"false".equals(param(request, "keywords"));
                    st.max = clamp(intParam(request, "max", st.max), 1, 20);
                    try {
                        String mc = param(request, "minConfidence");
                        if (mc != null) {
                            st.minConfidence = Math.max(0, Math.min(1, Double.parseDouble(mc)));
                        }
                    } catch (NumberFormatException e) {
                        throw new ApiException(400, "minConfidence must be a number");
                    }
                    DamSmartTags.save(resolver, st);
                    result = DamSmartTags.describe(st);
                    break;
                }
                case "blobGc": {
                    // studio-dam's disk: remove the blobs no asset refers to (dryRun: only count them)
                    admin(resolver);
                    DamClient c = damClient;
                    if (c == null || !c.isAvailable()) {
                        throw new ApiException(503, "studio-dam is not available");
                    }
                    try {
                        result = DamHousekeeping.gc(resolver, c, !"false".equals(request.getParameter("dryRun")));
                    } catch (IOException e) {
                        throw new ApiException(503, "studio-dam: " + e.getMessage());
                    }
                    break;
                }
                case "smartTagsInstall": {
                    admin(resolver);
                    DamClient c = damClient;
                    if (c == null) {
                        throw new ApiException(503, "studio-dam is not available");
                    }
                    try {
                        DamSmartTags.startInstall(c);
                    } catch (IOException e) {
                        throw new ApiException(503, "studio-dam: " + e.getMessage());
                    }
                    result = DamSmartTags.INSTALL.describe();
                    break;
                }
                case "searchModelInstall": {
                    admin(resolver);
                    DamClient c = damClient;
                    if (c == null) {
                        throw new ApiException(503, "studio-dam is not available");
                    }
                    try {
                        DamSmartTags.startSearchInstall(c);
                    } catch (IOException e) {
                        throw new ApiException(503, "studio-dam: " + e.getMessage());
                    }
                    result = DamSmartTags.SEARCH_INSTALL.describe();
                    break;
                }
                case "trainSmartTags": {
                    admin(resolver, "only admin can train Auto tags");
                    result = Collections.singletonMap("results", DamSmartTagTraining.train(resolver, values(request, "tag")));
                    break;
                }
                case "forgetSmartTag":
                    admin(resolver, "only admin can train Auto tags");
                    DamSmartTagTraining.forget(resolver, param(request, "tag"));
                    result = Collections.singletonMap("forgotten", param(request, "tag"));
                    break;
                case "colorTag": {
                    // color tags of the files named, or of the files below the folders named (those without, or all again)
                    List<Resource> roots = new ArrayList<>();
                    for (String p : paths(request)) {
                        Resource r0 = resolver.getResource(normalize(p));
                        if (r0 == null || !(DamItems.isAsset(r0) || DamItems.isFolder(r0) || DamItems.ROOT.equals(r0.getPath()))) {
                            throw new ApiException(404, "no file or folder " + p);
                        }
                        roots.add(r0);
                    }
                    if (roots.isEmpty()) {
                        throw new ApiException(400, "path is required");
                    }
                    result = DamColors.tagAll(resolver, roots, "true".equals(param(request, "again")));
                    break;
                }
                case "smartTag": {
                    JobManager jm = jobManager;
                    if (jm == null) {
                        throw new ApiException(503, "job manager not available");
                    }
                    List<String> queued = DamSmartTags.assets(resolver, paths(request), 10_000);
                    for (String p : queued) {
                        jm.addJob(DamSmartTagJob.TOPIC, Collections.singletonMap(AssetProcessingJob.PROP_PATH, p));
                    }
                    result = Collections.singletonMap("queued", queued.size());
                    break;
                }
                case "similarIndex": {
                    JobManager jm = jobManager;
                    if (jm == null) {
                        throw new ApiException(503, "job manager not available");
                    }
                    List<String> p0 = paths(request);
                    DamClient sc = damClient;
                    List<String> queued = DamSimilar.missing(resolver, p0.isEmpty() ? Collections.singletonList(DamItems.ROOT) : p0, 10_000,
                            sc != null && DamSimilar.available(sc, DamSimilar.Space.SEARCH));
                    for (String p : queued) {
                        Map<String, Object> props = new LinkedHashMap<>();
                        props.put(AssetProcessingJob.PROP_PATH, p);
                        props.put(DamSmartTagJob.PROP_EMBED_ONLY, true);
                        jm.addJob(DamSmartTagJob.TOPIC, props);
                    }
                    result = Collections.singletonMap("queued", queued.size());
                    break;
                }
                case "smartTagApply":
                case "smartTagRemove": {
                    Resource a = writable(resolver, normalize(param(request, "path")));
                    if (!DamItems.isAsset(a)) {
                        throw new ApiException(400, "not an asset");
                    }
                    DamSmartTags.change(a, param(request, "tag"), "smartTagApply".equals(op));
                    result = DamSmartTags.describe(resolver, a);
                    break;
                }
                case "poster":
                    result = poster(request, resolver, normalize(param(request, "path")));
                    break;
                default:
                    send(response, 400, error("unknown op"));
                    return;
            }
            resolver.commit();
            send(response, 200, result);
        } catch (ApiException e) {
            revert(resolver);
            send(response, e.status, error(e.getMessage()));
        } catch (PersistenceException e) {
            revert(resolver);
            // Usually missing write permission.
            send(response, 403, error("cannot save: " + e.getMessage()));
        }
    }

    /** Drops pending changes of a failed request (the JCR mock used in tests cannot). */
    private static void revert(ResourceResolver resolver) {
        try {
            resolver.revert();
        } catch (UnsupportedOperationException e) {
            // test repositories only
        }
    }

    /** A request error with its HTTP status. */
    static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static Resource existing(ResourceResolver resolver, String path) throws ApiException {
        if (!DamItems.inDam(path) || DamItems.ROOT.equals(path)) {
            throw new ApiException(400, "not a DAM item: " + path);
        }
        Resource r = resolver.getResource(path);
        if (r == null || !(DamItems.isAsset(r) || DamItems.isFolder(r))) {
            throw new ApiException(404, "not found: " + path);
        }
        return r;
    }

    private static Resource folder(ResourceResolver resolver, String path) throws ApiException {
        if (!DamItems.inDam(path)) {
            throw new ApiException(400, "destination must be in " + DamItems.ROOT);
        }
        Resource r = resolver.getResource(path);
        if (r == null || !(DamItems.isFolder(r) || DamItems.ROOT.equals(path))) {
            throw new ApiException(404, "folder not found: " + path);
        }
        return r;
    }

    Map<String, Object> createFolder(ResourceResolver resolver, String parentPath, String title, String name)
            throws ApiException, PersistenceException {
        Resource parent = folder(resolver, parentPath);
        String t = title == null ? "" : title.trim();
        String n = nodeName(name == null || name.trim().isEmpty() ? t : name);
        if (n == null) {
            throw new ApiException(400, "a title or name is required");
        }
        if (parent.getChild(n) != null) {
            throw new ApiException(409, "\"" + n + "\" already exists");
        }
        Resource folder = newFolder(resolver, parent, n, t.isEmpty() ? n : t);
        return Collections.singletonMap("path", folder.getPath());
    }

    /** A DAM folder with its title, as the console creates them. */
    static Resource newFolder(ResourceResolver resolver, Resource parent, String name, String title) throws PersistenceException {
        Map<String, Object> props = new HashMap<>();
        props.put("jcr:primaryType", "sling:OrderedFolder");
        Resource folder = resolver.create(parent, name, props);
        Map<String, Object> content = new HashMap<>();
        content.put("jcr:primaryType", "nt:unstructured");
        content.put("jcr:title", title);
        resolver.create(folder, "jcr:content", content);
        return folder;
    }

    /** {@link #existing} that someone else has not checked out. */
    private static Resource writable(ResourceResolver resolver, String path) throws ApiException {
        Resource r = existing(resolver, path);
        DamLocks.checkWritable(resolver, r);
        return r;
    }

    private static List<Resource> assets(ResourceResolver resolver, List<String> paths) throws ApiException {
        List<Resource> out = new ArrayList<>();
        for (String p : paths) {
            Resource r = existing(resolver, p);
            if (!DamItems.isAsset(r)) {
                throw new ApiException(400, "not an asset: " + p);
            }
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> delete(ResourceResolver resolver, List<String> paths)
            throws ApiException, PersistenceException {
        List<String> done = new ArrayList<>();
        for (String p : paths) {
            writable(resolver, p); // all checked before the first delete
        }
        for (String p : paths) {
            resolver.delete(existing(resolver, p));
            done.add(p);
        }
        return Collections.singletonMap("deleted", done);
    }

    private static Map<String, Object> move(ResourceResolver resolver, List<String> paths, String dest, String rename,
            List<String> adjust) throws ApiException, PersistenceException {
        if (paths.isEmpty()) {
            throw new ApiException(400, "nothing to move");
        }
        Resource target = folder(resolver, dest);
        if (rename != null && !rename.trim().isEmpty() && paths.size() != 1) {
            throw new ApiException(400, "rename needs exactly one item");
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new ApiException(500, "repository session not available");
        }
        List<String> roots;
        try {
            roots = DamReferences.checkedRoots(adjust, paths); // before anything moves
        } catch (DamReferences.AdjustException e) {
            throw new ApiException(403, e.getMessage());
        }
        List<String> moved = new ArrayList<>();
        Map<String, String> moves = new LinkedHashMap<>();
        for (String p : paths) {
            Resource src = writable(resolver, p);
            String name = rename == null || rename.trim().isEmpty() ? src.getName() : nodeName(rename);
            if (name == null) {
                throw new ApiException(400, "invalid name");
            }
            String to = target.getPath() + "/" + name;
            if (to.equals(src.getPath())) {
                moved.add(to);
                continue;
            }
            if (target.getPath().equals(src.getPath()) || target.getPath().startsWith(src.getPath() + "/")) {
                throw new ApiException(400, "cannot move a folder into itself");
            }
            if (resolver.getResource(to) != null) {
                throw new ApiException(409, "\"" + name + "\" already exists in " + target.getPath());
            }
            try {
                session.move(src.getPath(), to);
            } catch (RepositoryException e) {
                throw new ApiException(403, "cannot move " + src.getPath() + ": " + e.getMessage());
            }
            moved.add(to);
            moves.put(src.getPath(), to);
        }
        int adjusted;
        try {
            adjusted = DamReferences.rewrite(resolver, roots, moves);
        } catch (DamReferences.AdjustException e) {
            throw new ApiException(403, e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paths", moved);
        out.put("adjusted", adjusted);
        out.put("followed", DamMoveFollow.follow(resolver, moves));
        return out;
    }

    private static Map<String, Object> copy(ResourceResolver resolver, List<String> paths, String dest)
            throws ApiException, PersistenceException {
        if (paths.isEmpty()) {
            throw new ApiException(400, "nothing to copy");
        }
        Resource target = folder(resolver, dest);
        List<String> copies = new ArrayList<>();
        for (String p : paths) {
            Resource src = existing(resolver, p);
            if (target.getPath().equals(src.getPath()) || target.getPath().startsWith(src.getPath() + "/")) {
                throw new ApiException(400, "cannot copy a folder into itself");
            }
            String name = uniqueName(target, src.getName());
            copyTree(resolver, src, target, name);
            copies.add(target.getPath() + "/" + name);
        }
        return Collections.singletonMap("paths", copies);
    }

    /** {@code photo.jpg} → {@code photo1.jpg}, {@code photo2.jpg}, ... like AEM's paste. */
    static String uniqueName(Resource parent, String name) {
        if (parent.getChild(name) == null) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = base + i + ext;
            if (parent.getChild(candidate) == null) {
                return candidate;
            }
        }
    }

    /** Protected / generated properties the repository sets itself. */
    private static final Set<String> SKIP = new TreeSet<>(Arrays.asList(
            "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:baseVersion", "jcr:predecessors", "jcr:versionHistory",
            "jcr:isCheckedOut", "jcr:lockOwner", "jcr:lockIsDeep", "rep:policy"));

    private static void copyTree(ResourceResolver resolver, Resource src, Resource parent, String name)
            throws PersistenceException {
        Map<String, Object> props = new HashMap<>();
        for (Map.Entry<String, Object> e : src.getValueMap().entrySet()) {
            if (!SKIP.contains(e.getKey())) {
                props.put(e.getKey(), e.getValue());
            }
        }
        Resource copy = resolver.create(parent, name, props);
        for (Resource child : src.getChildren()) {
            if (!child.getName().equals("rep:policy")) {
                copyTree(resolver, child, copy, child.getName());
            }
        }
    }

    private Map<String, Object> reprocess(ResourceResolver resolver, List<String> paths) throws ApiException {
        JobManager jm = jobManager;
        if (jm == null) {
            throw new ApiException(503, "job manager not available");
        }
        List<String> queued = new ArrayList<>();
        for (String p : paths) {
            Resource r = existing(resolver, p);
            if (!DamItems.isAsset(r)) {
                continue;
            }
            ModifiableValueMap c = r.getChild("jcr:content") == null ? null
                    : r.getChild("jcr:content").adaptTo(ModifiableValueMap.class);
            if (c == null) {
                throw new ApiException(403, "cannot change " + p);
            }
            c.put(AssetProcessor.PN_ASSET_STATE, "processing");
            jm.addJob(AssetProcessingJob.TOPIC, Collections.singletonMap(AssetProcessingJob.PROP_PATH, p));
            queued.add(p);
        }
        return Collections.singletonMap("queued", queued);
    }

    /** Settings, model and download state of Smart Tags; the model part says why when studio-dam cannot answer. */
    private Map<String, Object> smartTagsStatus(ResourceResolver resolver) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", DamSmartTags.describe(DamSmartTags.settings(resolver)));
        DamClient c = damClient;
        try {
            out.put("model", c == null ? Collections.singletonMap("error", "studio-dam is not available") : c.smartTagsStatus());
        } catch (IOException e) {
            out.put("model", Collections.singletonMap("error", e.getMessage()));
        }
        out.put("install", DamSmartTags.INSTALL.describe());
        try {
            out.put("searchModel", c == null ? Collections.singletonMap("error", "studio-dam is not available") : c.searchModelStatus());
        } catch (IOException e) {
            out.put("searchModel", Collections.singletonMap("error", e.getMessage()));
        }
        out.put("searchInstall", DamSmartTags.SEARCH_INSTALL.describe());
        out.put("admin", DamAccess.isAdmin(resolver));
        return out;
    }

    private int queueSmartCrops(ResourceResolver resolver, List<String> paths) throws ApiException {
        JobManager jm = jobManager;
        if (jm == null) {
            throw new ApiException(503, "job manager not available");
        }
        List<String> assets = DamSmartTags.assets(resolver, paths, 10_000);
        for (String p : assets) {
            jm.addJob(DamSmartCropJob.TOPIC, Collections.singletonMap(AssetProcessingJob.PROP_PATH, p));
        }
        return assets.size();
    }

    private static double dbl(SlingHttpServletRequest request, String name) throws ApiException {
        String v = param(request, name);
        try {
            return Double.parseDouble(v == null ? "" : v.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, name + " must be a number");
        }
    }

    /** Settings that change what every upload gets, and a download of 800 MB: admin only. */
    private static void admin(ResourceResolver resolver) throws ApiException {
        admin(resolver, "only admin can change Smart Tags");
    }

    private static void admin(ResourceResolver resolver, String message) throws ApiException {
        if (!DamAccess.isAdmin(resolver)) {
            throw new ApiException(403, message);
        }
    }

    /** Largest poster frame accepted (a 4K JPEG is a few MB). */
    static final long MAX_POSTER_BYTES = 20L * 1024 * 1024;

    /**
     * Sets ({@code file}, a JPEG or PNG) or removes ({@code remove=true}) the poster frame of a
     * video, the image its thumbnails are made from, and queues the asset for processing.
     */
    private Map<String, Object> poster(SlingHttpServletRequest request, ResourceResolver resolver, String path)
            throws ApiException, PersistenceException {
        Resource asset = writable(resolver, path);
        if (!DamItems.isAsset(asset)) {
            throw new ApiException(400, "not an asset");
        }
        Object mime = DamItems.asset(asset).get("mimeType");
        if (!AssetProcessor.isVideo(mime == null ? null : mime.toString())) {
            throw new ApiException(400, "only videos have a poster frame");
        }
        Resource renditions = asset.getChild("jcr:content/renditions");
        if (renditions == null) {
            throw new ApiException(400, "the asset has no renditions folder");
        }
        Resource old = renditions.getChild(AssetProcessor.POSTER);
        if ("true".equals(param(request, "remove"))) {
            if (old != null) {
                resolver.delete(old);
            }
        } else {
            RequestParameter file = request.getRequestParameter("file");
            if (file == null) {
                throw new ApiException(400, "no file");
            }
            if (file.getSize() > MAX_POSTER_BYTES) {
                throw new ApiException(413, "the poster frame is larger than 20 MB");
            }
            byte[] data = file.get();
            String type = imageType(data);
            if (type == null) {
                throw new ApiException(400, "the poster frame must be a JPEG or PNG image");
            }
            if (old != null) {
                resolver.delete(old);
            }
            Resource f = resolver.create(renditions, AssetProcessor.POSTER, Collections.singletonMap("jcr:primaryType", "nt:file"));
            Map<String, Object> binary = new HashMap<>();
            binary.put("jcr:primaryType", "oak:Resource");
            binary.put("jcr:data", new java.io.ByteArrayInputStream(data));
            binary.put("jcr:mimeType", type);
            binary.put("jcr:lastModified", Calendar.getInstance());
            binary.put("jcr:lastModifiedBy", resolver.getUserID());
            resolver.create(f, "jcr:content", binary);
        }
        Map<String, Object> out = new LinkedHashMap<>(reprocess(resolver, Collections.singletonList(asset.getPath())));
        out.put("path", asset.getPath());
        return out;
    }

    /** JPEG or PNG by their signatures, else null: nothing else is stored as a poster. */
    static String imageType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        return null;
    }

    /** {@code 2026-12-31} → the end of that day here; anything else must be an ISO-8601 date and time. */
    static Calendar expiry(String v) throws ApiException {
        try {
            if (v.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                java.time.ZonedDateTime end = java.time.LocalDate.parse(v).atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault());
                return java.util.GregorianCalendar.from(end);
            }
            return java.util.GregorianCalendar.from(java.time.ZonedDateTime.parse(v));
        } catch (java.time.DateTimeException e) {
            throw new ApiException(400, "Expires is a date (yyyy-MM-dd): " + v);
        }
    }

    /** Publishes (or unpublishes) a configuration node; false when no publishing is set up here. */
    private boolean publish(ResourceResolver resolver, String path, boolean activate) {
        com.day.cq.replication.Replicator r = replicator;
        Session session = resolver.adaptTo(Session.class);
        if (r == null || session == null) {
            return false;
        }
        try {
            r.replicate(session, activate ? com.day.cq.replication.ReplicationActionType.ACTIVATE
                    : com.day.cq.replication.ReplicationActionType.DEACTIVATE, path);
            return true;
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(DamApiServlet.class).warn("{} {} not done: {}", activate ? "Publishing" : "Unpublishing", path, e.getMessage());
            return false;
        }
    }

    /** Live Copy of Media folders ({@link DamLiveCopy}). */
    private static Map<String, Object> liveCopy(String op, SlingHttpServletRequest request, ResourceResolver resolver)
            throws ApiException, PersistenceException {
        try {
            switch (op) {
                case "createLiveCopy": {
                    Resource source = existing(resolver, normalize(param(request, "source")));
                    Resource parent = folder(resolver, normalize(param(request, "parent")));
                    String title = param(request, "title");
                    String given = param(request, "name") == null ? "" : param(request, "name").trim();
                    // a file keeps its name (and extension) unless another is given; a folder's comes from its title
                    String name = DamItems.isAsset(source) ? (given.isEmpty() ? source.getName() : given)
                            : nodeName(given.isEmpty() ? (title == null || title.trim().isEmpty() ? source.getName() : title) : given);
                    if (name == null) {
                        throw new ApiException(400, "a title or name is required");
                    }
                    return DamLiveCopy.create(resolver, source, parent, name, title, "true".equals(param(request, "push")));
                }
                case "liveCopySync": {
                    Resource item = existing(resolver, normalize(param(request, "path")));
                    return DamLiveCopy.synchronize(resolver, item, "true".equals(param(request, "reset"))).toMap();
                }
                case "rollout":
                    return DamLiveCopy.rollout(resolver, existing(resolver, normalize(param(request, "path"))));
                case "liveCopySuspend": {
                    Resource item = existing(resolver, normalize(param(request, "path")));
                    boolean suspend = !"false".equals(param(request, "suspend"));
                    DamLiveCopy.suspend(resolver, item, suspend);
                    if (!suspend) {
                        // following again: the source's state comes back at once
                        return DamLiveCopy.synchronize(resolver, item, false, true).toMap();
                    }
                    return Collections.singletonMap("suspended", item.getPath());
                }
                case "liveCopyPush": {
                    Resource item = existing(resolver, normalize(param(request, "path")));
                    boolean push = "true".equals(param(request, "push"));
                    DamLiveCopy.setPushOnModify(resolver, item, push);
                    return Collections.singletonMap("pushOnModify", push);
                }
                case "liveCopyDetach": {
                    Resource item = existing(resolver, normalize(param(request, "path")));
                    DamLiveCopy.detach(resolver, item);
                    return Collections.singletonMap("detached", item.getPath());
                }
                default: {
                    Resource item = existing(resolver, normalize(param(request, "path")));
                    if (!DamItems.isAsset(item)) {
                        throw new ApiException(400, "not an asset");
                    }
                    DamLiveCopy.inheritAgain(resolver, item, param(request, "field"));
                    return Collections.singletonMap("inherited", param(request, "field"));
                }
            }
        } catch (IllegalStateException e) {
            throw new ApiException(409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static Map<String, Object> metadata(SlingHttpServletRequest request, ResourceResolver resolver, String path)
            throws ApiException, PersistenceException {
        Resource asset = writable(resolver, path);
        if (!DamItems.isAsset(asset)) {
            throw new ApiException(400, "not an asset");
        }
        Resource content = asset.getChild("jcr:content");
        if (content == null) {
            throw new ApiException(400, "asset has no jcr:content");
        }
        Resource mdRes = content.getChild("metadata");
        if (mdRes == null) {
            Map<String, Object> p = new HashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            mdRes = resolver.create(content, "metadata", p);
        }
        ModifiableValueMap md = mdRes.adaptTo(ModifiableValueMap.class);
        if (md == null) {
            throw new ApiException(403, "cannot change metadata of " + path);
        }
        List<String> changed = new ArrayList<>();
        for (String key : SINGLE) {
            RequestParameter p = request.getRequestParameter(key);
            if (p == null) {
                continue;
            }
            String v = p.getString().trim();
            if (v.isEmpty()) {
                md.remove(key);
            } else {
                md.put(key, v);
            }
            changed.add(key);
        }
        for (String key : MULTI) {
            // "key@Delete" clears; repeated "key" params set the list.
            RequestParameter[] ps = request.getRequestParameters(key);
            if (ps == null && request.getRequestParameter(key + "@Delete") == null) {
                continue;
            }
            List<String> values = new ArrayList<>();
            if ("true".equals(param(request, key + "@Append"))) {
                // Bulk editing: add to what each asset has instead of replacing it.
                for (String v : md.get(key, new String[0])) {
                    if (!values.contains(v)) {
                        values.add(v);
                    }
                }
            }
            if (ps != null) {
                for (RequestParameter p : ps) {
                    String v = p.getString().trim();
                    if ("cq:tags".equals(key) && !v.isEmpty() && !TAG_ID.matcher(v).matches()) {
                        throw new ApiException(400, "not a tag ID: " + v);
                    }
                    if (!v.isEmpty() && !values.contains(v)) {
                        values.add(v);
                    }
                }
            }
            if (values.isEmpty()) {
                md.remove(key);
                md.remove(key + "_xmpArrayType");
            } else {
                md.put(key, values.toArray(new String[0]));
            }
            changed.add(key);
        }
        RequestParameter expires = request.getRequestParameter(DamItems.PN_EXPIRES);
        if (expires != null) {
            // the day (yyyy-MM-dd, the end of it in the server's time zone) or a full ISO date; empty clears
            String v = expires.getString().trim();
            if (v.isEmpty()) {
                md.remove(DamItems.PN_EXPIRES);
            } else {
                md.put(DamItems.PN_EXPIRES, expiry(v));
            }
            changed.add(DamItems.PN_EXPIRES);
        }
        // in a live copy, what is changed here stops following the source
        DamLiveCopy.keepLocal(asset, changed);
        ModifiableValueMap c = content.adaptTo(ModifiableValueMap.class);
        if (c != null) {
            c.put("jcr:lastModified", Calendar.getInstance());
            c.put("jcr:lastModifiedBy", resolver.getUserID());
        }
        return Collections.singletonMap("changed", changed);
    }

    private static Map<String, Object> folderTitle(ResourceResolver resolver, String path, String title)
            throws ApiException, PersistenceException {
        Resource folder = existing(resolver, path);
        if (!DamItems.isFolder(folder)) {
            throw new ApiException(400, "not a folder");
        }
        Resource content = folder.getChild("jcr:content");
        if (content == null) {
            Map<String, Object> p = new HashMap<>();
            p.put("jcr:primaryType", "nt:unstructured");
            content = resolver.create(folder, "jcr:content", p);
        }
        ModifiableValueMap c = content.adaptTo(ModifiableValueMap.class);
        if (c == null) {
            throw new ApiException(403, "cannot change " + path);
        }
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            c.remove("jcr:title");
        } else {
            c.put("jcr:title", t);
        }
        return Collections.singletonMap("path", path);
    }

    // ------------------------------------------------------------------------------------ versions

    /** Like AEM: a version of the asset before its original is replaced, so the change can be undone. */
    static void versionBefore(ResourceResolver resolver, String path, String comment) throws ApiException {
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            return; // test repositories without JCR
        }
        try {
            DamVersions.create(session, path, null, comment, resolver.getUserID());
        } catch (UnsupportedOperationException e) {
            // JCR mock: no versioning
        } catch (RepositoryException e) {
            throw new ApiException(500, "cannot save a version first: " + e.getMessage());
        }
    }

    private static Map<String, Object> version(String op, SlingHttpServletRequest request, ResourceResolver resolver, String path)
            throws ApiException {
        Resource asset = "restoreVersion".equals(op) ? writable(resolver, path) : existing(resolver, path);
        if (!DamItems.isAsset(asset)) {
            throw new ApiException(400, "not an asset");
        }
        Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            throw new ApiException(500, "repository session not available");
        }
        try {
            if ("createVersion".equals(op)) {
                return Collections.singletonMap("version",
                        DamVersions.create(session, path, param(request, "label"), param(request, "comment"), resolver.getUserID()));
            }
            String version = param(request, "version");
            if (version == null || version.isEmpty()) {
                throw new ApiException(400, "version is required");
            }
            return Collections.singletonMap("saved", DamVersions.restore(session, path, version, resolver.getUserID()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (javax.jcr.version.VersionException e) {
            throw new ApiException(404, "no such version: " + e.getMessage());
        } catch (javax.jcr.AccessDeniedException e) {
            throw new ApiException(403, "no permission to version " + path);
        } catch (RepositoryException e) {
            throw new ApiException(500, "versioning failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> extractArchive(SlingHttpServletRequest request, ResourceResolver resolver, String path)
            throws ApiException, PersistenceException {
        Resource archive = assets(resolver, Collections.singletonList(path)).get(0);
        Map<String, Object> item = DamItems.asset(archive);
        if (!"archive".equals(DamSearch.kind(item))) {
            throw new ApiException(400, "not a ZIP archive: " + path);
        }
        Resource parent = archive.getParent();
        String destParam = normalize(param(request, "dest"));
        Resource dest;
        if ("folder".equals(param(request, "into"))) {
            // a new folder named after the archive, next to it
            String base = archive.getName().replaceAll("\\.[^.]*$", "");
            String name = nodeName(base);
            if (name == null) {
                throw new ApiException(400, "invalid name");
            }
            dest = parent.getChild(name);
            if (dest == null) {
                dest = newFolder(resolver, parent, name, base);
            } else if (!DamItems.isFolder(dest)) {
                throw new ApiException(409, name + " exists and is not a folder");
            }
        } else {
            dest = destParam == null ? parent : folder(resolver, destParam);
        }
        try {
            Map<String, Object> out = DamArchives.json(DamArchives.extract(resolver, archive, dest, param(request, "conflict")));
            out.put("into", dest.getPath());
            return out;
        } catch (IOException e) {
            throw new ApiException(400, "cannot read the archive: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------ image editor

    static final String PN_IMAGE_MAP = "imageMap";

    private Map<String, Object> edit(SlingHttpServletRequest request, ResourceResolver resolver, String path)
            throws ApiException, PersistenceException {
        Resource asset = writable(resolver, path);
        if (!DamItems.isAsset(asset)) {
            throw new ApiException(400, "not an asset");
        }
        Resource content = asset.getChild("jcr:content");
        Resource originalContent = asset.getChild("jcr:content/renditions/original/jcr:content");
        ModifiableValueMap c = content == null ? null : content.adaptTo(ModifiableValueMap.class);
        if (c == null || originalContent == null) {
            throw new ApiException(400, "asset has no original");
        }
        List<Map<String, Object>> ops;
        List<ImageEdit.Area> areas;
        String mapParam = param(request, PN_IMAGE_MAP);
        try {
            ops = ImageEdit.operations(param(request, "operations"));
            areas = mapParam == null ? null : ImageEdit.areas(mapParam);
        } catch (ImageEdit.InvalidEdit e) {
            throw new ApiException(400, e.getMessage());
        }
        if (ops.isEmpty() && areas == null) {
            throw new ApiException(400, "nothing to change");
        }

        Resource md = asset.getChild("jcr:content/metadata");
        ValueMap mv = md == null ? ValueMap.EMPTY : md.getValueMap();
        long width = mv.get("tiff:ImageWidth", 0L);
        long height = mv.get("tiff:ImageLength", 0L);
        boolean edited = false;
        if (!ops.isEmpty()) {
            DamClient client = damClient;
            if (client == null || !client.isAvailable()) {
                throw new ApiException(503, "the image service (studio-dam) is not running");
            }
            ModifiableValueMap oc = originalContent.adaptTo(ModifiableValueMap.class);
            if (oc == null) {
                throw new ApiException(403, "cannot change " + path);
            }
            versionBefore(resolver, path, "Before image edit");
            DamClient.EditResult result;
            try (InputStream in = originalContent.adaptTo(InputStream.class)) {
                if (in == null) {
                    throw new ApiException(400, "the original has no binary");
                }
                // Imported assets may never have been through studio-dam: upload first (content-addressed, so a no-op otherwise).
                String blobId = client.putBlob(in);
                result = client.edit(blobId, ops);
                try (InputStream editedBytes = client.getBlob(result.blobId)) {
                    oc.put("jcr:data", new ByteArrayInputStream(editedBytes.readAllBytes()));
                }
            } catch (DamClient.DamProcessingException e) {
                throw new ApiException(e.getStatus() == 422 ? 415 : 400, "cannot edit this image: " + e.getMessage());
            } catch (IOException e) {
                throw new ApiException(503, "the image service failed: " + e.getMessage());
            }
            oc.put("jcr:mimeType", result.mimeType);
            oc.put("jcr:lastModified", Calendar.getInstance());
            oc.put("jcr:lastModifiedBy", resolver.getUserID());
            // The renditions are regenerated from the new original (AssetChangeListener queues the job).
            c.put(AssetProcessor.PN_ASSET_STATE, "processing");
            c.remove(AssetProcessor.PN_BLOB_ID);
            width = result.width;
            height = result.height;
            edited = true;
        }
        if (areas != null) {
            // the map lives in the metadata (where AEM packages have it); the place it had before 20.11.2026 is cleared
            c.remove(PN_IMAGE_MAP);
            ModifiableValueMap m = md == null ? null : md.adaptTo(ModifiableValueMap.class);
            if (areas.isEmpty()) {
                if (m != null) {
                    m.remove(PN_IMAGE_MAP);
                }
            } else {
                if (width <= 0 || height <= 0) {
                    throw new ApiException(409, "the image size is not known yet; wait for processing to finish");
                }
                if (m == null) {
                    m = resolver.create(content, "metadata", Collections.singletonMap("jcr:primaryType", (Object) "nt:unstructured"))
                            .adaptTo(ModifiableValueMap.class);
                }
                m.put(PN_IMAGE_MAP, ImageEdit.format(areas, width, height));
            }
        }
        c.put("jcr:lastModified", Calendar.getInstance());
        c.put("jcr:lastModifiedBy", resolver.getUserID());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path);
        out.put("edited", edited);
        out.put("width", width);
        out.put("height", height);
        out.put(PN_IMAGE_MAP, imageMap(asset));
        return out;
    }

    /**
     * The asset's image map: {@code jcr:content/metadata/imageMap}, or the {@code jcr:content/imageMap} Sensible
     * Studio wrote before it moved (an empty value counts as none).
     */
    static String imageMap(Resource asset) {
        for (String rel : new String[] {"jcr:content/metadata", "jcr:content"}) {
            Resource r = asset.getChild(rel);
            String map = r == null ? null : r.getValueMap().get(PN_IMAGE_MAP, String.class);
            if (map != null && !map.trim().isEmpty()) {
                return map;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------ helpers

    /** Lower-case, accents removed, anything outside [a-z0-9._-] → '-', like AEM's folder names. */
    static String nodeName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = java.text.Normalizer.normalize(raw.trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|-+$", "");
        if (s.isEmpty() || s.length() > 150) {
            return null;
        }
        return s;
    }

    /** Removes a trailing slash; {@code null} for empty values. */
    static String normalize(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        String p = path.trim();
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static List<String> paths(SlingHttpServletRequest request) {
        List<String> out = new ArrayList<>();
        RequestParameter[] ps = request.getRequestParameters("path");
        if (ps != null) {
            for (RequestParameter p : ps) {
                String n = normalize(p.getString());
                if (n != null && !out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** All values of a repeatable parameter, trimmed, without blanks. */
    private static List<String> values(SlingHttpServletRequest request, String name) {
        List<String> out = new ArrayList<>();
        RequestParameter[] ps = request.getRequestParameters(name);
        if (ps != null) {
            for (RequestParameter p : ps) {
                String v = p.getString() == null ? "" : p.getString().trim();
                if (!v.isEmpty() && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String param(SlingHttpServletRequest request, String name) {
        RequestParameter p = request.getRequestParameter(name);
        return p == null ? null : p.getString();
    }

    private static int intParam(SlingHttpServletRequest request, String name, int fallback) {
        try {
            String v = param(request, name);
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** -1 when absent or not a number. */
    private static long longParam(SlingHttpServletRequest request, String name) {
        try {
            String v = param(request, name);
            return v == null || v.isEmpty() ? -1 : Math.max(-1, Long.parseLong(v));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static Map<String, Object> error(String message) {
        return Collections.singletonMap("error", message);
    }

    private static void send(SlingHttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(JsonOut.write(body));
    }
}
