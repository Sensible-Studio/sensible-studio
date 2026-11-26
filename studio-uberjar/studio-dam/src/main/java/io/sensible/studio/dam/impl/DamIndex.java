/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import javax.jcr.Node;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * The Oak Lucene index of the DAM (Sensible Studio's own definition, for Apache Jackrabbit Oak's
 * {@code oak-lucene}): assets by their descriptive metadata, tags, content hash, size and date,
 * plus the text of their originals (Apache Tika, e.g. PDFs), and folders by title.
 *
 * <p>Indexing is asynchronous (Oak's {@code async} lane, every few seconds, with near real-time
 * updates on top), as Oak indexes are. {@link #VERSION} is bumped when the definition changes;
 * the installer then replaces it and Oak reindexes.</p>
 */
final class DamIndex {

    static final String NAME = "studioDamAsset";
    static final String PATH = "/oak:index/" + NAME;
    static final String TAG = "studioDam";
    /** Change when the definition below changes: the installer replaces older ones. */
    static final long VERSION = 3;
    static final String PN_VERSION = "studio:version";

    /**
     * Readable by everyone: whether the index is built ({@code ready}) and for which
     * {@code version}. Queries use the index only then; before, a fresh index would answer with
     * partial results, so searches walk the tree.
     */
    static final String STATUS = "/var/studio/dam/index";

    private DamIndex() {
    }

    /** Whether this session may rely on the index (built, current version). */
    static boolean ready(Session session) {
        try {
            if (!session.nodeExists(STATUS)) {
                return false;
            }
            Node s = session.getNode(STATUS);
            return s.hasProperty("ready") && s.getProperty("ready").getBoolean() && s.hasProperty(PN_VERSION)
                    && s.getProperty(PN_VERSION).getLong() == VERSION;
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** Oak clears {@code reindex} when the (re)indexing of a definition has finished. */
    static boolean built(Session session) throws RepositoryException {
        session.refresh(false);
        return session.nodeExists(PATH) && !(session.getNode(PATH).hasProperty("reindex") && session.getNode(PATH).getProperty("reindex").getBoolean());
    }

    static void markReady(Session session, boolean ready) throws RepositoryException {
        if (!session.nodeExists(STATUS)) {
            return; // created by repoinit
        }
        Node s = session.getNode(STATUS);
        s.setProperty("ready", ready);
        s.setProperty(PN_VERSION, VERSION);
        session.save();
    }

    /** Whether the installed definition is missing or older than this code. */
    static boolean outdated(Session session) throws RepositoryException {
        if (!session.nodeExists(PATH)) {
            return true;
        }
        Node n = session.getNode(PATH);
        return !n.hasProperty(PN_VERSION) || n.getProperty(PN_VERSION).getLong() < VERSION;
    }

    /**
     * Writes the definition (replacing an older one) with {@code reindex = true}, and saves.
     * {@code async} false makes a synchronous index (tests); Sensible Studio uses the async lane.
     */
    static void install(Session session, boolean async) throws RepositoryException {
        Node oakIndex = session.getNode("/oak:index");
        if (oakIndex.hasNode(NAME)) {
            oakIndex.getNode(NAME).remove();
        }
        Node idx = oakIndex.addNode(NAME, "oak:QueryIndexDefinition");
        idx.setProperty("type", "lucene");
        if (async) {
            idx.setProperty("async", new String[] {"async", "nrt"});
        }
        idx.setProperty("compatVersion", 2L);
        idx.setProperty("evaluatePathRestrictions", true);
        idx.setProperty("includedPaths", new String[] {DamItems.ROOT});
        idx.setProperty("queryPaths", new String[] {DamItems.ROOT});
        idx.setProperty("tags", new String[] {TAG});
        idx.setProperty(PN_VERSION, VERSION);
        idx.setProperty("reindex", true);

        Node rules = idx.addNode("indexRules", "nt:unstructured");
        Node asset = rules.addNode("dam:Asset", "nt:unstructured").addNode("properties", "nt:unstructured");
        text(asset, "title", "jcr:content/metadata/dc:title");
        text(asset, "description", "jcr:content/metadata/dc:description");
        text(asset, "subject", "jcr:content/metadata/dc:subject").setProperty("propertyIndex", true);
        text(asset, "creator", "jcr:content/metadata/dc:creator");
        text(asset, "rights", "jcr:content/metadata/dc:rights");
        // "wknd-shared:activity/surfing" is found by "surfing" and filtered by the exact ID
        text(asset, "tags", "jcr:content/metadata/cq:tags").setProperty("propertyIndex", true);
        // Smart Tags (v2): found by their words like the author's tags
        text(asset, "smartTags", "jcr:content/metadata/" + DamSmartTags.PN_TAGS).setProperty("propertyIndex", true);
        text(asset, "smartKeywords", "jcr:content/metadata/" + DamSmartTags.PN_KEYWORDS);
        exact(asset, "format", "jcr:content/metadata/dc:format", PropertyType.STRING, false);
        exact(asset, "sha1", "jcr:content/metadata/dam:sha1", PropertyType.STRING, false);
        exact(asset, "size", "jcr:content/metadata/dam:size", PropertyType.LONG, true);
        exact(asset, "modified", "jcr:content/jcr:lastModified", PropertyType.DATE, true);
        // (v3) the image sets a picture is in, to refresh a set's picture when its first one changes
        // (v3) an asset by its studio-dam blob, to deliver an image whose blob this instance lacks
        exact(asset, "blobId", "jcr:content/" + AssetProcessor.PN_BLOB_ID, PropertyType.STRING, false);
        exact(asset, "setMembers", "jcr:content/" + DamSets.PN_MEMBERS, PropertyType.STRING, false);

        Node folder = rules.addNode("sling:Folder", "nt:unstructured").addNode("properties", "nt:unstructured");
        text(folder, "title", "jcr:content/jcr:title");

        // The text of the original (Tika: PDF, Office, plain text) counts as the asset's own text.
        Node aggregates = idx.addNode("aggregates", "nt:unstructured");
        Node a = aggregates.addNode("dam:Asset", "nt:unstructured");
        a.addNode("include0", "nt:unstructured").setProperty("path", "jcr:content/renditions/original/jcr:content");
        session.save();
    }

    private static Node text(Node props, String name, String property) throws RepositoryException {
        Node p = props.addNode(name, "nt:unstructured");
        p.setProperty("name", property);
        p.setProperty("analyzed", true);
        p.setProperty("nodeScopeIndex", true);
        return p;
    }

    private static void exact(Node props, String name, String property, int type, boolean ordered) throws RepositoryException {
        Node p = props.addNode(name, "nt:unstructured");
        p.setProperty("name", property);
        p.setProperty("propertyIndex", true);
        p.setProperty("type", PropertyType.nameFromValue(type));
        if (ordered) {
            p.setProperty("ordered", true);
        }
    }
}
