/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.AssetManager;

/**
 * Extract Archive: the files of an uploaded ZIP become assets, its directories folders.
 *
 * <p>Guarded against archive tricks: entry names are reduced to safe node names (no {@code ..},
 * no absolute paths), the number of files and the bytes written are capped (a "zip bomb" stops
 * at {@link #MAX_BYTES}), and only the file types the upload accepts are created.</p>
 */
final class DamArchives {

    static final int MAX_FILES = 2000;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

    private DamArchives() {
    }

    /** What happened, for the console's message. */
    static final class Result {
        final List<String> created = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> folders = new ArrayList<>();
    }

    /**
     * @param conflict for files whose asset already exists: {@code skip} (default), {@code replace}
     *                 or {@code rename}
     */
    static Result extract(ResourceResolver resolver, Resource archive, Resource dest, String conflict)
            throws DamApiServlet.ApiException, PersistenceException, IOException {
        Resource original = archive.getChild("jcr:content/renditions/original");
        InputStream raw = original == null ? null : original.adaptTo(InputStream.class);
        if (raw == null) {
            throw new DamApiServlet.ApiException(400, "the archive has no original");
        }
        AssetManager manager = resolver.adaptTo(AssetManager.class);
        if (manager == null) {
            throw new DamApiServlet.ApiException(503, "AssetManager not available");
        }
        Result result = new Result();
        // Assets Configurations › Upload Restrictions apply to what an archive brings too
        java.util.List<String> allowed = DamAssetsConfig.allowedMimeTypes(resolver);
        Map<String, Resource> folders = new HashMap<>();
        folders.put("", dest);
        long[] written = {0};
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                String[] segments = segments(e.getName());
                if (segments == null) {
                    if (!e.isDirectory() && !ignored(e.getName())) {
                        result.skipped.add(e.getName());
                    }
                    continue;
                }
                int dirs = e.isDirectory() ? segments.length : segments.length - 1;
                Resource parent = dest;
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < dirs; i++) {
                    key.append('/').append(segments[i]);
                    Resource f = folders.get(key.toString());
                    if (f == null) {
                        f = parent.getChild(segments[i]);
                        if (f == null) {
                            f = DamApiServlet.newFolder(resolver, parent, segments[i], segments[i]);
                            result.folders.add(f.getPath());
                        } else if (!DamItems.isFolder(f)) {
                            throw new DamApiServlet.ApiException(409, f.getPath() + " exists and is not a folder");
                        }
                        folders.put(key.toString(), f);
                    }
                    parent = f;
                }
                if (e.isDirectory()) {
                    continue;
                }
                String name = segments[segments.length - 1];
                String mime = AssetUploadServlet.mimeFor(name);
                if (mime == null || !DamAssetsConfig.mimeAllowed(allowed, mime)) {
                    result.skipped.add(e.getName());
                    continue;
                }
                if (result.created.size() >= MAX_FILES) {
                    throw new DamApiServlet.ApiException(413, "more than " + MAX_FILES + " files in the archive");
                }
                Resource existing = parent.getChild(name);
                if (existing != null) {
                    if ("rename".equals(conflict)) {
                        name = DamApiServlet.uniqueName(parent, name);
                    } else if ("replace".equals(conflict) && DamItems.isAsset(existing)) {
                        DamLocks.checkWritable(resolver, existing);
                    } else {
                        result.skipped.add(e.getName());
                        continue;
                    }
                }
                Asset asset = manager.createAsset(parent.getPath() + "/" + name, new Capped(zip, written), mime, false);
                if (asset == null) {
                    throw new DamApiServlet.ApiException(403, "cannot create " + parent.getPath() + "/" + name);
                }
                result.created.add(asset.getPath());
            }
        } catch (CappedException e) {
            throw new DamApiServlet.ApiException(413, "the archive unpacks to more than " + (MAX_BYTES >> 20) + " MB");
        } catch (java.util.zip.ZipException e) {
            throw new DamApiServlet.ApiException(400, "not a valid ZIP archive: " + e.getMessage());
        }
        return result;
    }

    static Map<String, Object> json(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("created", r.created);
        m.put("folders", r.folders);
        m.put("skipped", r.skipped);
        return m;
    }

    /** Housekeeping entries of archivers, not worth reporting. */
    private static boolean ignored(String entry) {
        return entry.startsWith("__MACOSX/") || entry.endsWith(".DS_Store") || entry.endsWith("Thumbs.db");
    }

    /** Safe node names of the entry's path, or null when the entry should not be extracted. */
    static String[] segments(String entry) {
        if (entry == null || ignored(entry)) {
            return null;
        }
        String[] raw = entry.replace('\\', '/').split("/");
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s.isEmpty() || ".".equals(s)) {
                continue;
            }
            if ("..".equals(s)) {
                return null; // zip slip
            }
            if (s.startsWith(".")) {
                return null; // hidden files and directories
            }
            String safe = AssetUploadServlet.safeName(s);
            if (safe == null) {
                return null;
            }
            out.add(safe);
        }
        return out.isEmpty() ? null : out.toArray(new String[0]);
    }

    private static final class CappedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** One entry of the zip, not closable (the asset manager closes what it reads), counting bytes. */
    private static final class Capped extends FilterInputStream {
        private final long[] written;

        Capped(InputStream in, long[] written) {
            super(in);
            this.written = written;
        }

        private int count(int n) throws CappedException {
            if (n > 0 && (written[0] += n) > MAX_BYTES) {
                throw new CappedException();
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return count(super.read(b, off, len));
        }

        @Override
        public void close() {
            // the next entry follows in the same stream
        }
    }
}
