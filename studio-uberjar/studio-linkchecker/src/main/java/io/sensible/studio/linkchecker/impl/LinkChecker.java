/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.linkchecker.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.jcr.Node;
import javax.jcr.Session;

import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the link checker: a scan of content (what links exist and where), then a check of the links that
 * are due. One run at a time, in the background; {@link #progress()} says how far it is. The Sling scheduler
 * starts a run every {@code scanPeriodHours}; the console starts one on demand.
 */
@Component(service = { LinkChecker.class, Runnable.class }, property = {
        "scheduler.period:Long=21600",
        "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE",
        "scheduler.name=studio-linkchecker" })
@Designate(ocd = LinkChecker.Config.class)
public class LinkChecker implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(LinkChecker.class);

    public static final String SUBSERVICE = "linkchecker";

    @ObjectClassDefinition(name = "Sensible Studio Link Checker", description = "Finds the links in content and checks them.")
    public @interface Config {
        @AttributeDefinition(name = "Check again after (hours)", description = "A link checked more recently is not checked by the scheduled run.")
        long checkPeriodHours() default 24;

        @AttributeDefinition(name = "Timeout (seconds)", description = "How long to wait for an external site.")
        int timeoutSeconds() default 10;

        @AttributeDefinition(name = "Parallel checks")
        int threads() default 8;

        @AttributeDefinition(name = "Scanned paths")
        String[] roots() default { Links.DEFAULT_SCAN_ROOT };

        @AttributeDefinition(name = "Scheduled runs", description = "Off: links are only scanned and checked from the console.")
        boolean scheduled() default true;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    private volatile Config config;
    private ExecutorService pool;
    private final Object lock = new Object();
    private final Progress progress = new Progress();

    @Activate
    @Modified
    void activate(Config c) {
        this.config = c;
        if (pool != null) {
            pool.shutdownNow();
        }
        pool = Executors.newFixedThreadPool(Math.max(1, c.threads()), r -> {
            Thread t = new Thread(r, "studio-linkchecker");
            t.setDaemon(true);
            return t;
        });
    }

    @Deactivate
    void deactivate() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** The scheduled run: scan, then check what is due. */
    @Override
    public void run() {
        if (config != null && config.scheduled()) {
            start(true, null, false, "scheduled");
        }
    }

    public long checkPeriodMillis() {
        return TimeUnit.HOURS.toMillis(config == null ? 24 : config.checkPeriodHours());
    }

    /**
     * Starts a run in the background. {@code scan}: collect links first. {@code urls}: check only these
     * (all due links when null). {@code force}: check even if checked recently. False when a run is going.
     */
    public boolean start(boolean scan, Collection<String> urls, boolean force, String by) {
        synchronized (lock) {
            if (progress.running) {
                return false;
            }
            progress.begin(scan ? "scan" : "check", by);
        }
        Thread t = new Thread(() -> {
            try {
                execute(scan, urls, force);
            } catch (Exception e) {
                LOG.warn("Link checker run failed", e);
                progress.error = e.getMessage();
            } finally {
                progress.end();
            }
        }, "studio-linkchecker-run");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void execute(boolean scan, Collection<String> urls, boolean force) throws Exception {
        Config c = config;
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            if (scan) {
                Map<String, Links.Found> found = Links.collect(session, Arrays.asList(c.roots()));
                Links.ScanResult r = Links.store(session, found);
                progress.scan = r.toJson();
                LOG.info("Link checker scan: {}", progress.scan);
            }
            progress.phase = "check";
            List<Node> todo = new ArrayList<>();
            long now = System.currentTimeMillis();
            if (urls != null) {
                for (String u : urls) {
                    Node n = Links.find(session, u);
                    if (n != null) {
                        todo.add(n);
                    }
                }
            } else {
                for (Node n : Links.all(session)) {
                    if (force || Links.due(n, checkPeriodMillis(), now)) {
                        todo.add(n);
                    }
                }
            }
            progress.total = todo.size();
            int timeout = (int) TimeUnit.SECONDS.toMillis(c.timeoutSeconds());
            // internal links: here, with the session; external ones: in parallel, written back here
            Map<Node, Future<Links.Check>> external = new LinkedHashMap<>();
            int dirty = 0;
            for (Node n : todo) {
                String url = n.getProperty("url").getString();
                if (Links.INTERNAL.equals(n.hasProperty("kind") ? n.getProperty("kind").getString() : null)) {
                    Links.record(n, Links.checkInternal(session, url));
                    progress.done++;
                    if (++dirty % 200 == 0) {
                        session.save();
                    }
                } else {
                    external.put(n, pool.submit(() -> HttpCheck.check(url, timeout)));
                }
            }
            session.save();
            for (Map.Entry<Node, Future<Links.Check>> e : external.entrySet()) {
                Links.Check check;
                try {
                    check = e.getValue().get(timeout * 3L, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    e.getValue().cancel(true);
                    check = new Links.Check(false, 0, "No answer", null);
                }
                Links.record(e.getKey(), check);
                progress.done++;
                if (++dirty % 50 == 0) {
                    session.save();
                }
            }
            session.save();
        }
    }

    public ResourceResolver serviceResolver() throws org.apache.sling.api.resource.LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    public Map<String, Object> progress() {
        return progress.toJson();
    }

    /** How the current (or the last) run went. */
    static final class Progress {
        volatile boolean running;
        volatile String phase;
        volatile String by;
        volatile int done;
        volatile int total;
        volatile long started;
        volatile long finished;
        volatile String error;
        volatile Map<String, Object> scan;

        void begin(String phase, String by) {
            running = true;
            this.phase = phase;
            this.by = by;
            done = 0;
            total = 0;
            started = System.currentTimeMillis();
            finished = 0;
            error = null;
            scan = null;
        }

        void end() {
            running = false;
            finished = System.currentTimeMillis();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", running);
            m.put("phase", phase);
            m.put("by", by);
            m.put("done", done);
            m.put("total", total);
            m.put("started", started == 0 ? null : java.time.Instant.ofEpochMilli(started).toString());
            m.put("finished", finished == 0 ? null : java.time.Instant.ofEpochMilli(finished).toString());
            m.put("error", error);
            m.put("scan", scan);
            return m;
        }
    }
}
