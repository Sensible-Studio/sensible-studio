/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.wcm.shown;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a page shows of other pages while it is rendered, for the cache in front of a publish instance:
 * the pages that rendering obtained through the Page API (a navigation's entries, a breadcrumb, a
 * teaser's target, a link's page) and the pages whose children it listed. The publish's
 * {@code DependsFilter} starts a recording around a page's rendering and sends what was recorded with
 * the answer ({@code X-Studio-Depends}); the page API implementation and the search implementations
 * record into it. Outside a recording every call does nothing.
 * <p>
 * Paths in a recording: {@code <page>/jcr:content} for a page that was shown (a publication of that
 * page takes the answer out, not one of a page below it), {@code <page>/*} for a page whose children
 * were listed (a publication of a page directly below it takes the answer out). A query whose results
 * cannot be known in advance (search, tags) makes the recording <i>unknown</i>: the answer then says
 * nothing, and the cache takes it out on every publication, as before.
 * <p>
 * One recording per thread; Sling renders a request and the resources it includes on one thread.
 */
public final class Shown {

    private static final ThreadLocal<Recording> CURRENT = new ThreadLocal<>();
    private static final String CONTENT = "/content/";

    private Shown() {
    }

    /** A recording in progress; closing it ends it (and puts back one it was started inside of). */
    public static final class Recording implements AutoCloseable {
        private final Set<String> paths = new LinkedHashSet<>();
        private final Recording outer;
        private final int limit;
        /** The page being rendered: what it reads of itself is no dependency. */
        private final String own;
        private boolean unknown;
        private String why;

        private Recording(Recording outer, int limit, String own) {
            this.outer = outer;
            this.limit = limit;
            this.own = own;
        }

        /** The paths recorded, in the order they came; empty when {@link #isUnknown()}. */
        public Set<String> paths() {
            return unknown ? Collections.emptySet() : Collections.unmodifiableSet(paths);
        }

        /** Whether the rendering showed something that cannot be told in advance, or too much. */
        public boolean isUnknown() {
            return unknown;
        }

        /** Why it is unknown, for the log. */
        public String why() {
            return why;
        }

        void add(String path) {
            if (unknown) {
                return;
            }
            paths.add(path);
            if (paths.size() > limit) {
                unknown("more than " + limit + " pages");
            }
        }

        void unknown(String reason) {
            if (!unknown) {
                unknown = true;
                why = reason;
                paths.clear();
            }
        }

        /** Ends it; one it was started inside of gets what it recorded (a rendering inside a rendering). */
        @Override
        public void close() {
            if (CURRENT.get() == this) {
                if (outer == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set(outer);
                    if (unknown) {
                        outer.unknown(why);
                    } else {
                        paths.forEach(outer::add);
                    }
                }
            }
        }
    }

    /** Starts recording on this thread; at most {@code limit} paths, past that it is unknown. */
    public static Recording start(int limit) {
        return start(limit, null);
    }

    /** As {@link #start(int)}, for the rendering of the page {@code own}. */
    public static Recording start(int limit, String own) {
        Recording r = new Recording(CURRENT.get(), limit, own);
        CURRENT.set(r);
        return r;
    }

    /**
     * A resource was read through the resource resolver (any code, not only the Page API). When it is
     * in the content of a page other than the one being rendered ({@code <page>/jcr:content/…}), that
     * page is shown: a component that reads a sibling's title through
     * {@code resource.getChild("jcr:content")} is seen too. Media are left to what the content names.
     * A page listed this way is recorded page by page: a page added later to such a list is not
     * seen (lists made with the Page API are, as {@link #children(String)}).
     */
    public static void content(String path) {
        Recording r = CURRENT.get();
        if (r == null || r.unknown || path == null || !path.startsWith(CONTENT) || path.startsWith("/content/dam/")) {
            return;
        }
        int at = path.indexOf("/jcr:content");
        if (at <= CONTENT.length() || (path.length() > at + 12 && path.charAt(at + 12) != '/')) {
            return; // not inside a page's content
        }
        String page = path.substring(0, at);
        if (!page.equals(r.own)) {
            r.add(page + "/jcr:content");
        }
    }

    /** Whether something is recording on this thread. */
    public static boolean recording() {
        return CURRENT.get() != null;
    }

    /** A page was shown (obtained through the Page API). */
    public static void page(String path) {
        Recording r = CURRENT.get();
        if (r != null && path != null && path.startsWith(CONTENT)) {
            r.add(path + "/jcr:content");
        }
    }

    /**
     * Something under {@code /conf} decided how the page looks: an editable template (its structure,
     * its policy mapping) or a component's policy. Recorded as it is: a publication of it, of
     * something below it or above it counts.
     */
    public static void conf(String path) {
        Recording r = CURRENT.get();
        if (r != null && path != null && path.startsWith("/conf/") && path.indexOf(',') < 0) {
            r.add(path);
        }
    }

    /** The children of a page were listed. */
    public static void children(String path) {
        Recording r = CURRENT.get();
        if (r != null && path != null && path.startsWith(CONTENT)) {
            r.add(path + "/*");
        }
    }

    /**
     * The rendering showed what a search found below {@code path} (the first image in a page, the pages
     * with a tag in a section): a publication of anything there, and only there, may change it, so the
     * path is recorded as it is (a publication at or below it, or above it, counts). A search below no
     * path or below all of {@code /content} cannot be told in advance: {@link #unknown(String)}.
     */
    public static void search(String path, String what) {
        Recording r = CURRENT.get();
        if (r == null) {
            return;
        }
        String p = path == null ? null : path.replaceAll("/+$", "");
        if (p != null && p.startsWith(CONTENT) && p.length() > CONTENT.length() && p.indexOf(',') < 0) {
            r.add(p);
        } else {
            unknown(what);
        }
    }

    /**
     * The rendering showed the results of a search, which any publication may change. The reason names
     * the code that asked (the first class on the stack that is not the search's own), so that whoever
     * reads it knows which component lists things that way.
     */
    public static void unknown(String reason) {
        Recording r = CURRENT.get();
        if (r != null && !r.isUnknown()) {
            String caller = StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                    .filter(c -> !c.startsWith("io.sensible.studio.wcm.") && !c.startsWith("java.") && !c.startsWith("jdk.")
                            && !c.startsWith("com.day.cq.search.") && !c.startsWith("com.day.cq.tagging."))
                    .findFirst().orElse(null));
            r.unknown(caller == null ? reason : reason + " in " + caller);
        }
    }
}
