/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.impl.clientlibs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.ui.clientlibs.ClientLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibrary;
import com.adobe.granite.ui.clientlibs.HtmlLibraryManager;
import com.adobe.granite.ui.clientlibs.LibraryType;

/**
 * Client libraries, Sensible Studio style:
 * <ul>
 *   <li>libraries are {@code cq:ClientLibraryFolder} nodes with {@code categories}, {@code embed},
 *       {@code dependencies} and {@code allowProxy};</li>
 *   <li>content comes from {@code css.txt} / {@code js.txt} (with {@code #base=} support), embedded
 *       categories first;</li>
 *   <li>relative {@code url(...)} in embedded CSS is rewritten to absolute public paths so it keeps
 *       working from the embedding library's URL;</li>
 *   <li>output is cached in memory and dropped whenever anything below {@code /apps} or
 *       {@code /libs} changes.</li>
 * </ul>
 * Minification is not done; {@code .min} URLs serve the same bytes.
 */
@Component(
        service = {HtmlLibraryManager.class, ResourceChangeListener.class},
        property = {
                ResourceChangeListener.PATHS + "=/apps",
                ResourceChangeListener.PATHS + "=/libs"
        })
public class HtmlLibraryManagerImpl implements HtmlLibraryManager, ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(HtmlLibraryManagerImpl.class);
    static final String SUBSERVICE = "clientlibs";
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)");

    @Reference
    private ResourceResolverFactory resolverFactory;

    private volatile ClientLibraryIndex index;
    private final Map<String, Built> cache = new ConcurrentHashMap<>();

    /** For tests: use a fixed resolver instead of a service login. */
    private ResourceResolver fixedResolver;

    public HtmlLibraryManagerImpl() {
    }

    HtmlLibraryManagerImpl(ResourceResolver fixedResolver) {
        this.fixedResolver = fixedResolver;
    }

    // ================================================================== resolver & index

    interface WithResolver<T> {
        T apply(ResourceResolver resolver) throws IOException;
    }

    <T> T withResolver(WithResolver<T> fn) throws IOException {
        if (fixedResolver != null) {
            return fn.apply(fixedResolver);
        }
        try (ResourceResolver rr = resolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            return fn.apply(rr);
        } catch (LoginException e) {
            throw new IOException("Sensible Studio clientlibs service user is not configured (studio-compat-config)", e);
        }
    }

    ClientLibraryIndex index() throws IOException {
        ClientLibraryIndex i = index;
        if (i == null) {
            i = withResolver(rr -> ClientLibraryIndex.build(rr, rr.getSearchPath()));
            index = i;
        }
        return i;
    }

    @Override
    public void onChange(List<ResourceChange> changes) {
        index = null;
        cache.clear();
    }

    // ================================================================== building

    private static final class Built {
        final byte[] bytes;
        final long lastModified;

        Built(byte[] bytes, long lastModified) {
            this.bytes = bytes;
            this.lastModified = lastModified;
        }
    }

    private Built built(ClientLibraryImpl lib, LibraryType type) throws IOException {
        String key = type.name() + ":" + lib.getPath();
        Built b = cache.get(key);
        if (b == null) {
            ClientLibraryIndex idx = index();
            b = withResolver(rr -> {
                long[] lastMod = {0};
                StringBuilder sb = new StringBuilder();
                build(rr, idx, lib, type, new HashSet<>(), sb, lastMod);
                return new Built(sb.toString().getBytes(StandardCharsets.UTF_8), lastMod[0]);
            });
            cache.put(key, b);
        }
        return b;
    }

    private void build(ResourceResolver rr, ClientLibraryIndex idx, ClientLibraryImpl lib, LibraryType type,
            Set<String> visited, StringBuilder out, long[] lastMod) {
        if (!visited.add(lib.getPath())) {
            return;
        }
        for (String cat : lib.getEmbeddedCategories()) {
            for (ClientLibraryImpl embedded : idx.category(cat)) {
                build(rr, idx, embedded, type, visited, out, lastMod);
            }
        }
        appendOwn(rr, lib, type, out, lastMod);
    }

    private void appendOwn(ResourceResolver rr, ClientLibraryImpl lib, LibraryType type, StringBuilder out,
            long[] lastMod) {
        Resource manifest = rr.getResource(lib.getPath() + "/" + (type == LibraryType.CSS ? "css.txt" : "js.txt"));
        if (manifest == null) {
            return;
        }
        String base = lib.getPath();
        for (String raw : read(manifest).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("#base=")) {
                    String b = line.substring("#base=".length()).trim();
                    base = b.startsWith("/") ? b : normalize(lib.getPath() + "/" + b);
                }
                continue;
            }
            String filePath = line.startsWith("/") ? line : normalize(base + "/" + line);
            Resource file = rr.getResource(filePath);
            if (file == null) {
                LOG.debug("clientlib {} lists missing file {}", lib.getPath(), filePath);
                continue;
            }
            lastMod[0] = Math.max(lastMod[0], file.getResourceMetadata().getModificationTime());
            String text = read(file);
            if (type == LibraryType.CSS) {
                text = rewriteUrls(text, publicDirOf(lib, filePath));
            }
            out.append(text);
            if (!text.endsWith("\n")) {
                out.append('\n');
            }
            if (type == LibraryType.JS) {
                out.append(";\n"); // guard against files that do not end their last statement
            }
        }
    }

    /** Public URL of the directory that contains {@code filePath}. */
    static String publicDirOf(ClientLibraryImpl lib, String filePath) {
        String dir = filePath.substring(0, filePath.lastIndexOf('/'));
        if (dir.startsWith(lib.getPath())) {
            return lib.getPublicPath() + dir.substring(lib.getPath().length());
        }
        return dir;
    }

    /** Makes relative {@code url()} references absolute against {@code publicDir}. */
    static String rewriteUrls(String css, String publicDir) {
        Matcher m = CSS_URL.matcher(css);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = m.group(2).trim();
            String replacement = m.group(0);
            if (!url.startsWith("/") && !url.startsWith("data:") && !url.startsWith("#") && !url.contains("://")) {
                replacement = "url(" + m.group(1) + normalize(publicDir + "/" + url) + m.group(1) + ")";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String normalize(String path) {
        List<String> parts = new ArrayList<>();
        String query = "";
        String p = path;
        int q = indexOfAny(p, '?', '#');
        if (q >= 0) {
            query = p.substring(q);
            p = p.substring(0, q);
        }
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
            } else {
                parts.add(seg);
            }
        }
        return "/" + String.join("/", parts) + query;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    }

    private static String read(Resource file) {
        Resource content = file.getChild("jcr:content");
        InputStream in = file.adaptTo(InputStream.class);
        if (in == null && content != null) {
            in = content.adaptTo(InputStream.class);
        }
        if (in == null) {
            return "";
        }
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ================================================================== ordering

    /** Libraries for the categories, dependencies first, de-duplicated, restricted to the type. */
    List<ClientLibraryImpl> ordered(String[] categories, LibraryType type, boolean transitive) throws IOException {
        ClientLibraryIndex idx = index();
        LinkedHashMap<String, ClientLibraryImpl> out = new LinkedHashMap<>();
        Set<String> seenCategories = new HashSet<>();
        for (String c : categories) {
            addCategory(idx, c.trim(), transitive, out, seenCategories);
        }
        List<ClientLibraryImpl> result = new ArrayList<>();
        for (ClientLibraryImpl lib : out.values()) {
            if (type == null || built(lib, type).bytes.length > 0) {
                result.add(lib);
            }
        }
        return result;
    }

    private void addCategory(ClientLibraryIndex idx, String category, boolean transitive,
            Map<String, ClientLibraryImpl> out, Set<String> seen) {
        if (category.isEmpty() || !seen.add(category)) {
            return;
        }
        for (ClientLibraryImpl lib : idx.category(category)) {
            if (transitive) {
                for (String dep : lib.getDependentCategories()) {
                    addCategory(idx, dep, true, out, seen);
                }
            }
            out.putIfAbsent(lib.getPath(), lib);
        }
    }

    // ================================================================== HtmlLibraryManager

    @Override
    public void writeIncludes(SlingHttpServletRequest request, Writer out, String... categories) throws IOException {
        writeCssInclude(request, out, categories);
        writeJsInclude(request, out, categories);
    }

    @Override
    public void writeCssInclude(SlingHttpServletRequest request, Writer out, String... categories) throws IOException {
        for (ClientLibraryImpl lib : ordered(categories, LibraryType.CSS, true)) {
            out.write("<link rel=\"stylesheet\" href=\"" + url(request, lib, LibraryType.CSS) + "\" type=\"text/css\">\n");
        }
    }

    @Override
    public void writeJsInclude(SlingHttpServletRequest request, Writer out, String... categories) throws IOException {
        for (ClientLibraryImpl lib : ordered(categories, LibraryType.JS, true)) {
            out.write("<script src=\"" + url(request, lib, LibraryType.JS) + "\"></script>\n");
        }
    }

    private String url(SlingHttpServletRequest request, ClientLibraryImpl lib, LibraryType type) throws IOException {
        String path = lib.getPublicPath() + (isMinifyEnabled() ? ".min" : "") + type.extension;
        long lm = built(lib, type).lastModified;
        String mapped = request == null ? path : request.getResourceResolver().map(request, path);
        return lm > 0 ? mapped + "?v=" + Long.toString(lm, 36) : mapped;
    }

    @Override
    public Map<String, ClientLibrary> getLibraries() {
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(index().byPath));
        } catch (IOException e) {
            LOG.warn("cannot list client libraries: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public Collection<ClientLibrary> getLibraries(String[] categories, LibraryType type, boolean ignoreThemed,
            boolean transitive) {
        try {
            return new ArrayList<>(ordered(categories, type, transitive));
        } catch (IOException e) {
            LOG.warn("cannot resolve client libraries: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public HtmlLibrary getLibrary(LibraryType type, String path) {
        try {
            ClientLibraryImpl lib = find(path);
            if (lib == null) {
                return null;
            }
            Built b = built(lib, type);
            lib.setTypes(typesOf(lib));
            return new HtmlLibraryImpl(lib, type, b.bytes, b.lastModified);
        } catch (IOException e) {
            LOG.warn("cannot build client library {}: {}", path, e.getMessage());
            return null;
        }
    }

    private Set<LibraryType> typesOf(ClientLibraryImpl lib) throws IOException {
        Set<LibraryType> types = EnumSet.noneOf(LibraryType.class);
        for (LibraryType t : LibraryType.values()) {
            if (built(lib, t).bytes.length > 0) {
                types.add(t);
            }
        }
        return types;
    }

    /** Accepts the folder path or the {@code /etc.clientlibs} proxy path. */
    ClientLibraryImpl find(String path) throws IOException {
        ClientLibraryIndex idx = index();
        if (path.startsWith(ClientLibraryImpl.PROXY_PREFIX)) {
            String rest = path.substring(ClientLibraryImpl.PROXY_PREFIX.length());
            for (String root : new String[] {"/apps/", "/libs/"}) {
                ClientLibraryImpl lib = idx.byPath.get(root + rest);
                if (lib != null && lib.allowProxy()) {
                    return lib;
                }
            }
            return null;
        }
        return idx.byPath.get(path);
    }

    @Override
    public boolean isMinifyEnabled() {
        return false;
    }

    @Override
    public boolean isDebugEnabled() {
        return false;
    }

    // ================================================================== output

    static final class HtmlLibraryImpl implements HtmlLibrary {
        private final ClientLibraryImpl lib;
        private final LibraryType type;
        private final byte[] bytes;
        private final long lastModified;

        HtmlLibraryImpl(ClientLibraryImpl lib, LibraryType type, byte[] bytes, long lastModified) {
            this.lib = lib;
            this.type = type;
            this.bytes = bytes;
            this.lastModified = lastModified;
        }

        @Override
        public LibraryType getType() {
            return type;
        }

        @Override
        public String getLibraryPath() {
            return lib.getPath();
        }

        @Override
        public String getPath() {
            return lib.getPublicPath() + type.extension;
        }

        @Override
        public String getName() {
            return lib.getPath().substring(lib.getPath().lastIndexOf('/') + 1);
        }

        @Override
        public long getLastModified() {
            return lastModified;
        }

        @Override
        public InputStream getInputStream(boolean minified) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public InputStream getInputStream() {
            return getInputStream(false);
        }

        int length() {
            return bytes.length;
        }
    }
}
