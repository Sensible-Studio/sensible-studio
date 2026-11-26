/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads prepared in the background (AEM's asynchronous download): the archive is built after
 * the request, kept for {@value #KEEP_DAYS} days, and offered in Media's Downloads list.
 *
 * <pre>
 * /var/studio/dam/downloads/&lt;id&gt;   studio:owner, studio:name, studio:status (queued | working | ready | failed),
 *                                 studio:files, studio:done, studio:size, studio:created, studio:expires, studio:error,
 *                                 studio:entries (String[]: "&lt;entry name&gt;\t&lt;binary path&gt;")
 *   archive.zip                   the ZIP (nt:file), when ready
 * </pre>
 *
 * The list of files is made with the asker's resolver, so an archive holds only what they may
 * read; the service resolver ({@value #SUBSERVICE}) reads those binaries later and keeps the
 * downloads, which only their owner lists, takes and removes.
 */
@Component(service = DamDownloads.class)
public class DamDownloads {

    private static final Logger LOG = LoggerFactory.getLogger(DamDownloads.class);

    static final String SUBSERVICE = "downloads";
    static final String ROOT = "/var/studio/dam/downloads";
    static final String ARCHIVE = "archive.zip";
    static final int KEEP_DAYS = 7;
    /** Files of one background archive at most (a direct download allows {@link AssetDownloadServlet#MAX_FILES}). */
    static final int MAX_FILES = 50_000;
    /** Downloads one person keeps at most; the oldest go first. */
    static final int MAX_PER_USER = 20;

    @Reference
    private ResourceResolverFactory factory;

    private ScheduledExecutorService executor;

    public DamDownloads() {
    }

    DamDownloads(ResourceResolverFactory factory) {
        this.factory = factory;
    }

    @Activate
    void activate() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "studio-dam-downloads");
            t.setDaemon(true);
            return t;
        });
        // archives an earlier start did not finish are built again; expired ones go every hour
        executor.execute(this::resume);
        executor.scheduleWithFixedDelay(this::purge, 1, 60, TimeUnit.MINUTES);
    }

    @Deactivate
    void deactivate() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    ResourceResolver service() throws LoginException {
        return factory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    /** Records a download for {@code owner} and queues its archive. */
    Map<String, Object> start(String owner, String name, List<String[]> files) throws LoginException, PersistenceException {
        Map<String, Object> out;
        try (ResourceResolver service = service()) {
            String id = create(service, owner, name, files);
            out = describe(service.getResource(ROOT + "/" + id));
        }
        String id = String.valueOf(out.get("id"));
        if (executor != null) {
            executor.execute(() -> buildQuietly(id));
        }
        return out;
    }

    List<Map<String, Object>> list(String owner) throws LoginException {
        try (ResourceResolver service = service()) {
            return list(service, owner);
        }
    }

    void remove(String owner, String id) throws LoginException, PersistenceException {
        try (ResourceResolver service = service()) {
            remove(service, owner, id);
        }
    }

    /** The archive of a ready download of {@code owner}, or null. */
    interface Archive {
        String name();

        void copyTo(OutputStream out) throws IOException;
    }

    Archive archive(String owner, String id) throws LoginException {
        try (ResourceResolver service = service()) {
            Resource r = own(service, owner, id);
            if (r == null || r.getChild(ARCHIVE) == null) {
                return null;
            }
            String name = r.getValueMap().get("studio:name", "assets.zip");
            String path = r.getChild(ARCHIVE).getPath();
            return new Archive() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public void copyTo(OutputStream out) throws IOException {
                    try (ResourceResolver s = service(); InputStream in = streamOf(s.getResource(path))) {
                        if (in != null) {
                            in.transferTo(out);
                        }
                    } catch (LoginException e) {
                        throw new IOException(e);
                    }
                }
            };
        }
    }

    private void buildQuietly(String id) {
        try (ResourceResolver service = service()) {
            build(service, id);
        } catch (Exception e) {
            LOG.warn("Download {} could not be prepared: {}", id, e.toString());
        }
    }

    private void resume() {
        try (ResourceResolver service = service()) {
            Resource root = service.getResource(ROOT);
            if (root == null) {
                return;
            }
            for (Resource r : root.getChildren()) {
                String status = r.getValueMap().get("studio:status", "");
                if ("queued".equals(status) || "working".equals(status)) {
                    executor.execute(() -> buildQuietly(r.getName()));
                }
            }
        } catch (Exception e) {
            LOG.warn("Unfinished downloads not resumed: {}", e.toString());
        }
    }

    private void purge() {
        try (ResourceResolver service = service()) {
            purge(service, Calendar.getInstance());
        } catch (Exception e) {
            LOG.warn("Expired downloads not removed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------ the work

    static String create(ResourceResolver service, String owner, String name, List<String[]> files) throws PersistenceException {
        Resource root = ensureRoot(service);
        // the oldest of this person's go when there are too many
        List<Resource> mine = new ArrayList<>();
        for (Resource r : root.getChildren()) {
            if (owner.equals(r.getValueMap().get("studio:owner", String.class))) {
                mine.add(r);
            }
        }
        mine.sort((a, b) -> created(a).compareTo(created(b)));
        for (int i = 0; i <= mine.size() - MAX_PER_USER; i++) {
            service.delete(mine.get(i));
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Calendar now = Calendar.getInstance();
        Calendar expires = (Calendar) now.clone();
        expires.add(Calendar.DAY_OF_MONTH, KEEP_DAYS);
        List<String> entries = new ArrayList<>(files.size());
        for (String[] f : files) {
            entries.add(f[0].replace('\t', ' ') + "\t" + f[1]);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("jcr:primaryType", "nt:unstructured");
        p.put("studio:owner", owner);
        p.put("studio:name", name);
        p.put("studio:status", "queued");
        p.put("studio:files", (long) files.size());
        p.put("studio:done", 0L);
        p.put("studio:created", now);
        p.put("studio:expires", expires);
        p.put("studio:entries", entries.toArray(new String[0]));
        service.create(root, id, p);
        service.commit();
        return id;
    }

    /** Builds the archive of one download (a temporary file, then stored next to its record). */
    static void build(ResourceResolver service, String id) throws IOException {
        Resource r = service.getResource(ROOT + "/" + id);
        if (r == null) {
            return;
        }
        ModifiableValueMap mv = r.adaptTo(ModifiableValueMap.class);
        String[] entries = mv.get("studio:entries", new String[0]);
        mv.put("studio:status", "working");
        mv.put("studio:done", 0L);
        service.commit();
        Path tmp = Files.createTempFile("studio-download-", ".zip");
        try {
            long done = 0;
            long lastSaved = System.currentTimeMillis();
            try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
                for (String e : entries) {
                    int tab = e.indexOf('\t');
                    if (tab < 0) {
                        continue;
                    }
                    Resource binary = service.getResource(e.substring(tab + 1));
                    try (InputStream in = binary == null ? null : streamOf(binary)) {
                        if (in != null) {
                            zip.putNextEntry(new ZipEntry(e.substring(0, tab)));
                            in.transferTo(zip);
                            zip.closeEntry();
                        }
                    }
                    done++;
                    if (System.currentTimeMillis() - lastSaved > 2000) {
                        if (service.getResource(r.getPath()) == null) {
                            return; // removed while being prepared
                        }
                        mv.put("studio:done", done);
                        service.commit();
                        lastSaved = System.currentTimeMillis();
                    }
                }
            }
            if (service.getResource(r.getPath()) == null) {
                return;
            }
            try (InputStream in = Files.newInputStream(tmp)) {
                Resource old = r.getChild(ARCHIVE);
                if (old != null) {
                    service.delete(old);
                }
                Resource file = service.create(r, ARCHIVE, Collections.singletonMap("jcr:primaryType", "nt:file"));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("jcr:primaryType", "nt:resource");
                c.put("jcr:mimeType", "application/zip");
                c.put("jcr:data", in);
                c.put("jcr:lastModified", Calendar.getInstance());
                service.create(file, "jcr:content", c);
                mv.put("studio:status", "ready");
                mv.put("studio:done", done);
                mv.put("studio:size", Files.size(tmp));
                mv.remove("studio:entries");
                service.commit();
            }
        } catch (IOException | RuntimeException e) {
            service.revert();
            Resource again = service.getResource(ROOT + "/" + id);
            ModifiableValueMap m = again == null ? null : again.adaptTo(ModifiableValueMap.class);
            if (m != null) {
                m.put("studio:status", "failed");
                m.put("studio:error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                service.commit();
            }
            throw e;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    static List<Map<String, Object>> list(ResourceResolver service, String owner) {
        List<Map<String, Object>> out = new ArrayList<>();
        Resource root = service.getResource(ROOT);
        if (root != null) {
            Calendar now = Calendar.getInstance();
            for (Resource r : root.getChildren()) {
                ValueMap v = r.getValueMap();
                Calendar expires = v.get("studio:expires", Calendar.class);
                if (owner.equals(v.get("studio:owner", String.class)) && (expires == null || expires.after(now))) {
                    out.add(describe(r));
                }
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("created")).compareTo(String.valueOf(a.get("created"))));
        return out;
    }

    static void remove(ResourceResolver service, String owner, String id) throws PersistenceException {
        Resource r = own(service, owner, id);
        if (r == null) {
            throw new IllegalArgumentException("No download " + id);
        }
        service.delete(r);
        service.commit();
    }

    static int purge(ResourceResolver service, Calendar now) throws PersistenceException {
        Resource root = service.getResource(ROOT);
        int n = 0;
        if (root != null) {
            for (Resource r : new ArrayList<>(toList(root.getChildren()))) {
                Calendar expires = r.getValueMap().get("studio:expires", Calendar.class);
                if (expires != null && !expires.after(now)) {
                    service.delete(r);
                    n++;
                }
            }
            service.commit();
        }
        return n;
    }

    static Map<String, Object> describe(Resource r) {
        ValueMap v = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getName());
        m.put("name", v.get("studio:name", "assets.zip"));
        m.put("status", v.get("studio:status", "queued"));
        m.put("files", v.get("studio:files", 0L));
        m.put("done", v.get("studio:done", 0L));
        m.put("size", v.get("studio:size", Long.class));
        m.put("created", v.get("studio:created", Calendar.class));
        m.put("expires", v.get("studio:expires", Calendar.class));
        m.put("error", v.get("studio:error", String.class));
        return m;
    }

    private static Resource own(ResourceResolver service, String owner, String id) {
        if (id == null || !id.matches("^[0-9a-f]{16}$")) {
            return null;
        }
        Resource r = service.getResource(ROOT + "/" + id);
        return r != null && owner != null && owner.equals(r.getValueMap().get("studio:owner", String.class)) ? r : null;
    }

    private static Calendar created(Resource r) {
        Calendar c = r.getValueMap().get("studio:created", Calendar.class);
        return c == null ? Calendar.getInstance() : c;
    }

    private static List<Resource> toList(Iterable<Resource> it) {
        List<Resource> l = new ArrayList<>();
        it.forEach(l::add);
        return l;
    }

    private static InputStream streamOf(Resource binary) {
        InputStream in = binary.adaptTo(InputStream.class);
        if (in == null) {
            Resource c = binary.getChild("jcr:content");
            in = c == null ? null : c.adaptTo(InputStream.class);
        }
        return in;
    }

    private static Resource ensureRoot(ResourceResolver resolver) throws PersistenceException {
        Resource existing = resolver.getResource(ROOT);
        if (existing != null) {
            return existing; // made by repoinit; the service user cannot read what is above it
        }
        Resource r = resolver.getResource("/");
        for (String seg : ROOT.substring(1).split("/")) {
            Resource c = r.getChild(seg);
            r = c != null ? c : resolver.create(r, seg, Collections.singletonMap("jcr:primaryType", "sling:Folder"));
        }
        return r;
    }
}
