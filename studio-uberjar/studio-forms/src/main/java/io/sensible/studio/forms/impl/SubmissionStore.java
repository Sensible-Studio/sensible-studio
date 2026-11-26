/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.forms.impl;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import java.io.InputStream;

import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Submissions, under {@code /var/studio/forms/submissions/<form relative to /content/forms>/<yyyy>/<mm>/<id>}:
 * one node per submission ({@code studio/forms/submission}) with the form's path, when it came, where from,
 * the values as properties ({@code values/<field>}) and the whole as JSON ({@code data}). Written and read
 * with the forms service user, so a visitor on Live can submit and an author can read, each with its own rights.
 */
@Component(service = SubmissionStore.class)
public class SubmissionStore {

    public static final String ROOT = "/var/studio/forms/submissions";
    public static final String FORMS_ROOT = "/content/forms";
    public static final String RESOURCE_TYPE = "studio/forms/submission";
    public static final String SUBSERVICE = "forms";

    @Reference
    private ResourceResolverFactory resolverFactory;

    public ResourceResolver serviceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    /** The folder of a form's submissions: {@code /var/studio/forms/submissions/wknd/contact}. */
    public static String folderOf(String formPath) {
        if (FORMS_ROOT.equals(formPath)) {
            return ROOT;
        }
        String rel = formPath.startsWith(FORMS_ROOT + "/") ? formPath.substring(FORMS_ROOT.length()) : formPath;
        return ROOT + rel;
    }

    /**
     * Stores one submission.
     *
     * @return the path of the new node
     */
    public String store(ResourceResolver resolver, String formPath, Map<String, Object> values, Map<String, Object> meta)
            throws PersistenceException, RepositoryException {
        Calendar now = Calendar.getInstance();
        String folder = folderOf(formPath) + "/" + now.get(Calendar.YEAR) + "/" + String.format("%02d", now.get(Calendar.MONTH) + 1);
        Resource parent = ensureFolder(resolver, folder);
        String id = UUID.randomUUID().toString();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jcr:primaryType", "nt:unstructured");
        props.put("sling:resourceType", RESOURCE_TYPE);
        props.put("form", formPath);
        props.put("submittedAt", now);
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            if (e.getValue() != null) {
                props.put(e.getKey(), e.getValue());
            }
        }
        props.put("data", Json.write(values));
        Resource node = resolver.create(parent, id, props);
        Map<String, Object> vp = new LinkedHashMap<>();
        vp.put("jcr:primaryType", "nt:unstructured");
        for (Map.Entry<String, Object> e : values.entrySet()) {
            vp.put(e.getKey(), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        resolver.create(node, "values", vp);
        resolver.commit();
        return node.getPath();
    }

    /**
     * Keeps an uploaded file with its submission, as {@code <submission>/files/<field>/<name>} ({@code nt:file}).
     *
     * @return the file's path
     */
    public String storeFile(ResourceResolver resolver, Resource submission, String field, String fileName, String mimeType, InputStream data)
            throws PersistenceException {
        Resource files = submission.getChild("files");
        if (files == null) {
            files = resolver.create(submission, "files", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        Resource fieldFolder = files.getChild(field);
        if (fieldFolder == null) {
            fieldFolder = resolver.create(files, field, Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        String name = safeName(fileName);
        String unique = name;
        for (int i = 2; fieldFolder.getChild(unique) != null; i++) {
            int dot = name.lastIndexOf('.');
            unique = dot > 0 ? name.substring(0, dot) + "-" + i + name.substring(dot) : name + "-" + i;
        }
        Resource file = resolver.create(fieldFolder, unique, Collections.singletonMap("jcr:primaryType", "nt:file"));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("jcr:primaryType", "nt:resource");
        content.put("jcr:mimeType", mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
        content.put("jcr:lastModified", Calendar.getInstance());
        content.put("jcr:data", data);
        resolver.create(file, "jcr:content", content);
        resolver.commit();
        return file.getPath();
    }

    /** A file name a repository accepts: no path separators, no leading dots, nothing too long. */
    static String safeName(String fileName) {
        String n = fileName == null ? "file" : fileName;
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).trim();
        n = n.replaceAll("[\\[\\]*|:\"]", "_").replaceAll("^\\.+", "");
        if (n.isEmpty()) {
            n = "file";
        }
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    /** What an after-submit action did, kept with the submission: {@code actions/<name>} with status, time and detail. */
    public void recordAction(ResourceResolver resolver, String submissionPath, String name, String status, String detail) throws PersistenceException {
        Resource submission = resolver.getResource(submissionPath);
        if (submission == null) {
            return;
        }
        Resource actions = submission.getChild("actions");
        if (actions == null) {
            actions = resolver.create(submission, "actions", Collections.singletonMap("jcr:primaryType", "nt:unstructured"));
        }
        Resource a = actions.getChild(name);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("status", status);
        props.put("at", Calendar.getInstance());
        if (detail != null) {
            props.put("detail", detail.length() > 1000 ? detail.substring(0, 1000) : detail);
        }
        if (a == null) {
            props.put("jcr:primaryType", "nt:unstructured");
            resolver.create(actions, name, props);
        } else {
            ModifiableValueMap mvm = a.adaptTo(ModifiableValueMap.class);
            mvm.putAll(props);
        }
        resolver.commit();
    }

    /** total and unseen per form, for every form whose submissions live under {@code folder} (a form page or a folder of forms). */
    public Map<String, int[]> countsUnder(ResourceResolver resolver, String folder) {
        Map<String, int[]> out = new LinkedHashMap<>();
        Resource root = resolver.getResource(folderOf(folder));
        if (root == null) {
            return out;
        }
        List<Resource> all = new ArrayList<>();
        walk(root, all);
        for (Resource r : all) {
            ValueMap vm = r.getValueMap();
            String form = vm.get("form", "");
            int[] c = out.computeIfAbsent(form, k -> new int[2]);
            c[0]++;
            if (!vm.get("seen", false)) {
                c[1]++;
            }
        }
        return out;
    }

    private static Resource ensureFolder(ResourceResolver resolver, String path) throws PersistenceException {
        Resource r = resolver.getResource(path);
        if (r != null) {
            return r;
        }
        int slash = path.lastIndexOf('/');
        Resource parent = ensureFolder(resolver, path.substring(0, slash));
        return resolver.create(parent, path.substring(slash + 1), Collections.singletonMap("jcr:primaryType", "sling:Folder"));
    }

    /** Every submission of a form, newest first. */
    public List<Resource> list(ResourceResolver resolver, String formPath) {
        List<Resource> out = new ArrayList<>();
        Resource folder = resolver.getResource(folderOf(formPath));
        if (folder != null) {
            walk(folder, out);
        }
        out.sort(Comparator.comparing((Resource r) -> {
            Calendar c = r.getValueMap().get("submittedAt", Calendar.class);
            return c == null ? 0L : c.getTimeInMillis();
        }).reversed());
        return out;
    }

    private static void walk(Resource r, List<Resource> out) {
        for (Resource c : r.getChildren()) {
            if (c.isResourceType(RESOURCE_TYPE)) {
                out.add(c);
            } else {
                walk(c, out);
            }
        }
    }

    public int count(ResourceResolver resolver, String formPath) {
        return list(resolver, formPath).size();
    }

    /** A submission as the API hands it out. */
    public static Map<String, Object> toJson(Resource r, boolean full) {
        ValueMap vm = r.getValueMap();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", r.getPath());
        m.put("id", r.getName());
        m.put("form", vm.get("form", String.class));
        Calendar at = vm.get("submittedAt", Calendar.class);
        m.put("submittedAt", at == null ? null : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.toInstant().atZone(at.getTimeZone().toZoneId())));
        m.put("seen", vm.get("seen", false));
        Map<String, Object> values = new LinkedHashMap<>();
        Resource v = r.getChild("values");
        if (v != null) {
            for (Map.Entry<String, Object> e : v.getValueMap().entrySet()) {
                if (!e.getKey().startsWith("jcr:")) {
                    values.put(e.getKey(), e.getValue());
                }
            }
        }
        m.put("values", values);
        List<Object> files = new ArrayList<>();
        Resource fr = r.getChild("files");
        if (fr != null) {
            for (Resource fieldFolder : fr.getChildren()) {
                for (Resource file : fieldFolder.getChildren()) {
                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("field", fieldFolder.getName());
                    fm.put("name", file.getName());
                    fm.put("path", file.getPath());
                    Resource content = file.getChild("jcr:content");
                    if (content != null) {
                        fm.put("type", content.getValueMap().get("jcr:mimeType", String.class));
                        try {
                            javax.jcr.Node n = content.adaptTo(javax.jcr.Node.class);
                            if (n != null && n.hasProperty("jcr:data")) {
                                fm.put("size", n.getProperty("jcr:data").getLength());
                            }
                        } catch (RepositoryException e) {
                            // size is a nicety
                        }
                    }
                    files.add(fm);
                }
            }
        }
        if (!files.isEmpty()) {
            m.put("files", files);
        }
        if (full) {
            Resource ar = r.getChild("actions");
            if (ar != null) {
                List<Object> actions = new ArrayList<>();
                for (Resource a : ar.getChildren()) {
                    Map<String, Object> am = new LinkedHashMap<>();
                    am.put("name", a.getName());
                    am.put("status", a.getValueMap().get("status", String.class));
                    am.put("detail", a.getValueMap().get("detail", String.class));
                    Calendar when = a.getValueMap().get("at", Calendar.class);
                    am.put("at", when == null ? null : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(when.toInstant().atZone(when.getTimeZone().toZoneId())));
                    actions.add(am);
                }
                m.put("actions", actions);
            }
            m.put("locale", vm.get("locale", String.class));
            m.put("userAgent", vm.get("userAgent", String.class));
            m.put("origin", vm.get("origin", String.class));
            m.put("referrer", vm.get("referrer", String.class));
        }
        return m;
    }

    public void markSeen(Resource r) throws PersistenceException {
        ModifiableValueMap mvm = r.adaptTo(ModifiableValueMap.class);
        if (mvm != null && !mvm.get("seen", false)) {
            mvm.put("seen", true);
            r.getResourceResolver().commit();
        }
    }
}
