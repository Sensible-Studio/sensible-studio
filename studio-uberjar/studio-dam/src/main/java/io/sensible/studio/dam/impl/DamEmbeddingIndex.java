/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The embeddings of every picture, kept in memory so that "find similar" and "pictures of …"
 * do not read each asset from the repository at every search: with it a search is one pass over
 * an array (about 0.1 s for 100 000 pictures), without it a walk of the whole library.
 *
 * <p>Built at the first search (one walk, as a search used to cost) and kept current from then
 * on by listening to what changes below {@code /content/dam}. Each number is kept as a half
 * float: about 150 MB for 100 000 pictures, at a precision the scores do not notice (a few
 * 1/10000 on a dot product).</p>
 *
 * <p>The index is read with the processing service user, so it holds every picture; what a
 * person gets is still only what they may read: {@link DamSimilar} resolves each hit with the
 * asking user's resolver before listing it.</p>
 */
@Component(service = {DamEmbeddingIndex.class, ResourceChangeListener.class}, property = {
        ResourceChangeListener.PATHS + "=/content/dam",
        ResourceChangeListener.CHANGES + "=ADDED",
        ResourceChangeListener.CHANGES + "=CHANGED",
        ResourceChangeListener.CHANGES + "=REMOVED"})
public class DamEmbeddingIndex implements ResourceChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(DamEmbeddingIndex.class);
    private static final int DIM = DamSimilar.DIMENSIONS;
    /** Vectors per block (a block is 6 MB). */
    private static final int BLOCK_BITS = 12;
    private static final int BLOCK = 1 << BLOCK_BITS;
    /** More pictures than this are not held (1.5 GB); the rest are found by nothing. */
    static final int MAX_PICTURES = 1_000_000;

    /** Every half float as a float: decoding is one array read. */
    private static final float[] HALF = new float[1 << 16];

    static {
        for (int h = 0; h < HALF.length; h++) {
            HALF[h] = halfToFloat((short) h);
        }
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** whose embeddings are held */
    private final DamSimilar.Space space;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<short[]> blocks = new ArrayList<>();
    /** slot → path, null where a picture was removed. */
    private final List<String> paths = new ArrayList<>();
    private final Map<String, Integer> slots = new HashMap<>();
    private final Deque<Integer> free = new ArrayDeque<>();
    /** Pictures that have no embedding yet. */
    private final Set<String> missing = new HashSet<>();
    private volatile boolean built;

    public DamEmbeddingIndex() {
        this(DamSimilar.Space.TAGS);
    }

    protected DamEmbeddingIndex(DamSimilar.Space space) {
        this.space = space;
    }

    DamEmbeddingIndex(ResourceResolverFactory resolverFactory) {
        this(resolverFactory, DamSimilar.Space.TAGS);
    }

    DamEmbeddingIndex(ResourceResolverFactory resolverFactory, DamSimilar.Space space) {
        this.space = space;
        this.resolverFactory = resolverFactory;
    }

    /** The factory the index reads with (a subclass registered on its own brings its own). */
    protected ResourceResolverFactory factory() {
        return resolverFactory;
    }

    @Deactivate
    protected void deactivate() {
        lock.writeLock().lock();
        try {
            clear();
            built = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** One picture found by {@link #search}. */
    static final class Hit {
        final String path;
        final double dot;

        Hit(String path, double dot) {
            this.path = path;
            this.dot = dot;
        }
    }

    /** What a search found. */
    static final class Result {
        /** Best first; only those at or above the asked minimum. */
        final List<Hit> hits = new ArrayList<>();
        /** Pictures compared (below the folder asked about). */
        int compared;
        /** Of those, how many scored under the minimum. */
        int below;
        /** Pictures below the folder that have no embedding yet. */
        int notIndexed;
    }

    /**
     * The {@code limit} pictures below {@code scope} whose embedding is closest to {@code query}
     * (a unit vector), leaving out {@code exclude} and what scores under {@code minDot}.
     * Null when the index cannot be built here (no service user): the caller walks instead.
     */
    Result search(float[] query, String scope, String exclude, int limit, double minDot) {
        if (!ensureBuilt()) {
            return null;
        }
        String prefix = scope == null || scope.equals(DamItems.ROOT) ? null : scope + "/";
        Result out = new Result();
        PriorityQueue<Hit> best = new PriorityQueue<>((a, b) -> Double.compare(a.dot, b.dot));
        lock.readLock().lock();
        try {
            for (int slot = 0; slot < paths.size(); slot++) {
                String path = paths.get(slot);
                if (path == null || path.equals(exclude) || (prefix != null && !path.startsWith(prefix))) {
                    continue;
                }
                short[] block = blocks.get(slot >> BLOCK_BITS);
                int off = (slot & (BLOCK - 1)) * DIM;
                float sum = 0;
                for (int i = 0; i < DIM; i++) {
                    sum += query[i] * HALF[block[off + i] & 0xFFFF];
                }
                out.compared++;
                if (sum < minDot) {
                    out.below++;
                } else if (best.size() < limit) {
                    best.add(new Hit(path, sum));
                } else if (sum > best.peek().dot) {
                    best.poll();
                    best.add(new Hit(path, sum));
                }
            }
            for (String p : missing) {
                if (!p.equals(exclude) && (prefix == null || p.startsWith(prefix))) {
                    out.notIndexed++;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        out.hits.addAll(best);
        out.hits.sort((a, b) -> Double.compare(b.dot, a.dot));
        return out;
    }

    /** How many pictures the index holds (0 before the first search). */
    int size() {
        lock.readLock().lock();
        try {
            return slots.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean ensureBuilt() {
        if (built) {
            return true;
        }
        lock.writeLock().lock();
        try {
            if (built) {
                return true;
            }
            long start = System.currentTimeMillis();
            try (ResourceResolver resolver = serviceResolver()) {
                clear();
                for (String path : DamSmartTags.assets(resolver, Collections.singletonList(DamItems.ROOT), MAX_PICTURES)) {
                    Resource r = resolver.getResource(path);
                    if (r != null) {
                        take(r);
                    }
                }
            } catch (LoginException e) {
                LOG.warn("The picture index cannot be built: no service user for subservice {}", AssetProcessingJob.SUBSERVICE);
                return false;
            }
            built = true;
            LOG.info("Picture index ({}) built: {} pictures, {} without an embedding, {} ms", space.model,
                    slots.size(), missing.size(), System.currentTimeMillis() - start);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ResourceResolver serviceResolver() throws LoginException {
        return factory().getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, AssetProcessingJob.SUBSERVICE));
    }

    // ------------------------------------------------------------------ kept current

    @Override
    public void onChange(List<ResourceChange> changes) {
        if (!built) {
            return; // the first search reads everything as it is then
        }
        Set<String> gone = new HashSet<>();
        Set<String> touched = new HashSet<>();
        Set<String> added = new HashSet<>();
        for (ResourceChange change : changes) {
            String path = change.getPath();
            int content = path.indexOf("/jcr:content");
            if (content >= 0) {
                // the embedding is a property of jcr:content; renditions (the picture itself) are below it
                String rest = path.substring(content + "/jcr:content".length());
                if (rest.isEmpty() || rest.startsWith("/renditions")) {
                    touched.add(path.substring(0, content));
                }
            } else if (change.getType() == ResourceChange.ChangeType.REMOVED) {
                gone.add(path);
            } else if (change.getType() == ResourceChange.ChangeType.ADDED) {
                added.add(path); // some stores report only the root of an added subtree
            }
        }
        if (gone.isEmpty() && touched.isEmpty() && added.isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try (ResourceResolver resolver = serviceResolver()) {
            for (String root : gone) {
                dropBelow(root);
            }
            for (String root : added) {
                for (String path : DamSmartTags.assets(resolver, Collections.singletonList(root), MAX_PICTURES)) {
                    touched.add(path);
                }
            }
            for (String path : touched) {
                Resource r = resolver.getResource(path);
                if (r == null || !DamItems.isAsset(r) || DamItems.isContentFragment(r)) {
                    drop(path);
                } else {
                    take(r);
                }
            }
        } catch (LoginException e) {
            built = false; // read everything again at the next search rather than answer from a stale index
        } catch (RuntimeException e) {
            LOG.warn("The picture index is read again at the next search: {}", e.toString());
            built = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------ storage (write lock held)

    private void clear() {
        blocks.clear();
        paths.clear();
        slots.clear();
        free.clear();
        missing.clear();
    }

    /** Reads one asset: its embedding into the index, or its path among those that have none. */
    private void take(Resource asset) {
        String path = asset.getPath();
        float[] e = DamSimilar.stored(asset, space);
        if (e == null) {
            drop(path);
            if (DamSmartTags.source(asset) != null) {
                missing.add(path);
            }
            return;
        }
        missing.remove(path);
        put(path, e);
    }

    /** Keeps {@code e} as the embedding of {@code path} (under the write lock, or in tests). */
    void put(String path, float[] e) {
        Integer slot = slots.get(path);
        if (slot == null) {
            if (slots.size() >= MAX_PICTURES) {
                return;
            }
            slot = free.isEmpty() ? paths.size() : free.poll();
            if (slot == paths.size()) {
                paths.add(path);
                if ((slot >> BLOCK_BITS) >= blocks.size()) {
                    blocks.add(new short[BLOCK * DIM]);
                }
            } else {
                paths.set(slot, path);
            }
            slots.put(path, slot);
        }
        short[] block = blocks.get(slot >> BLOCK_BITS);
        int off = (slot & (BLOCK - 1)) * DIM;
        for (int i = 0; i < DIM; i++) {
            block[off + i] = floatToHalf(e[i]);
        }
    }

    private void drop(String path) {
        missing.remove(path);
        Integer slot = slots.remove(path);
        if (slot != null) {
            paths.set(slot, null);
            free.add(slot);
        }
    }

    private void dropBelow(String root) {
        String prefix = root + "/";
        List<String> out = new ArrayList<>();
        for (String p : slots.keySet()) {
            if (p.equals(root) || p.startsWith(prefix)) {
                out.add(p);
            }
        }
        for (String p : missing) {
            if (p.equals(root) || p.startsWith(prefix)) {
                out.add(p);
            }
        }
        out.forEach(this::drop);
    }

    // ------------------------------------------------------------------ half floats (IEEE 754 binary16)

    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp = ((bits >>> 23) & 0xFF) - 127 + 15;
        int mant = bits & 0x7FFFFF;
        if (exp >= 31) {
            return (short) (sign | 0x7C00 | (((bits >>> 23) & 0xFF) == 0xFF && mant != 0 ? 0x200 : 0)); // too large: infinity (NaN stays NaN)
        }
        if (exp <= 0) {
            if (exp < -10) {
                return (short) sign; // too small: zero
            }
            mant |= 0x800000;
            int shift = 14 - exp;
            int half = mant >>> shift;
            if (((mant >>> (shift - 1)) & 1) != 0) {
                half++; // round to nearest
            }
            return (short) (sign | half);
        }
        int half = (exp << 10) | (mant >>> 13);
        if ((mant & 0x1000) != 0) {
            half++; // round to nearest; a carry moves into the exponent, which is what it should do
        }
        return (short) (sign | half);
    }

    static float halfToFloat(short h) {
        int bits = h & 0xFFFF;
        int sign = (bits & 0x8000) << 16;
        int exp = (bits >>> 10) & 0x1F;
        int mant = bits & 0x3FF;
        if (exp == 0) {
            if (mant == 0) {
                return Float.intBitsToFloat(sign);
            }
            // subnormal: normalise
            int e = -1;
            do {
                e++;
                mant <<= 1;
            } while ((mant & 0x400) == 0);
            mant &= 0x3FF;
            return Float.intBitsToFloat(sign | ((127 - 15 - e) << 23) | (mant << 13));
        }
        if (exp == 31) {
            return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        }
        return Float.intBitsToFloat(sign | ((exp - 15 + 127) << 23) | (mant << 13));
    }
}
