/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.settings.SlingSettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The repository's housekeeping, run by itself every night the way AEM as a Cloud Service does it, and
 * visible: compaction of the segment store (Oak's revision GC) and a purge of old versions, each run kept
 * under {@code /var/studio/health/runs} with how long it took and what it recovered. A sample of the
 * repository's size on disk is kept per day under {@code /var/studio/health/size}, so the console can
 * show how it grows.
 *
 * <p>Settings live in the repository ({@code /var/studio/health/settings}) so the console can change
 * them: {@code enabled}, {@code hour} (the night window, local time), {@code compact}, {@code
 * purgeVersions}, {@code keepVersions}, {@code keepDays}. The scheduler wakes this up every 15 minutes;
 * a run starts in the window's hour when none ran that day. The console starts one on demand.</p>
 */
@Component(service = { Maintenance.class, Runnable.class }, property = {
        "scheduler.period:Long=900",
        "scheduler.concurrent:Boolean=false",
        "scheduler.runOn=SINGLE",
        "scheduler.name=studio-health" })
public class Maintenance implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Maintenance.class);

    public static final String SUBSERVICE = "health";
    public static final String ROOT = "/var/studio/health";
    static final String RUNS = ROOT + "/runs";
    static final String SIZE = ROOT + "/size";
    static final String SETTINGS = ROOT + "/settings";
    static final List<String> VERSION_ROOTS = Collections.unmodifiableList(Arrays.asList("/content", "/conf"));
    private static final DateTimeFormatter RUN_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final long BACKOFF_MILLIS = 10L * 60 * 60 * 1000;
    static final int SETTLE_SAMPLES = 20;
    static final long SETTLE_MILLIS = 3_000;
    private static final long GC_TIMEOUT = 2L * 60 * 60 * 1000;

    /** What the console may change. */
    public static final class Settings {
        public boolean enabled = true;
        public int hour = 2;
        public boolean compact = true;
        public boolean purgeVersions = true;
        public int keepVersions = 5;
        public int keepDays = 30;

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("hour", hour);
            m.put("compact", compact);
            m.put("purgeVersions", purgeVersions);
            m.put("keepVersions", keepVersions);
            m.put("keepDays", keepDays);
            return m;
        }
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private SlingSettingsService slingSettings;

    private final RevisionGc gc = new RevisionGc();
    private final Object lock = new Object();
    private final Progress progress = new Progress();
    private volatile String lastSampleDay;

    @Activate
    void activate(org.osgi.framework.BundleContext ctx) {
        gc.context(ctx);
        LOG.info("Health: nightly maintenance armed (settings under {})", SETTINGS);
    }

    // ------------------------------------------------------------------------------------------ schedule

    @Override
    public void run() {
        try {
            sampleSize();
            Settings s = settings();
            if (!s.enabled || progress.running) {
                return;
            }
            Map<String, Object> last = lastRun();
            Object started = last == null ? null : last.get("started");
            Instant lastStarted = started == null ? null : Instant.parse(String.valueOf(started));
            // a machine asleep at the hour (a laptop at night) makes the run up when it is back, once it has settled
            long up = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            if (!due(LocalDateTime.now(), s.hour, lastStarted, ZoneId.systemDefault(), up >= CATCH_UP_AFTER_MILLIS)) {
                return;
            }
            start("all", "scheduled");
        } catch (Exception e) {
            LOG.warn("Health: scheduled check failed", e);
        }
    }

    // ------------------------------------------------------------------------------------------ runs

    /** Starts a run in the background: {@code all}, {@code compact} or {@code versions}. False when one is going. */
    public boolean start(String kind, String by) {
        synchronized (lock) {
            if (progress.running) {
                return false;
            }
            progress.begin(kind, by);
        }
        Thread t = new Thread(() -> {
            try {
                execute(kind, by);
            } catch (Exception e) {
                LOG.warn("Health: maintenance run failed", e);
                progress.error = e.getMessage();
            } finally {
                progress.end();
            }
        }, "studio-health-maintenance");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void execute(String kind, String by) throws Exception {
        Settings s = settings();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("kind", kind);
        run.put("by", by);
        run.put("started", Instant.now().toString());
        long before = repositorySize();
        run.put("sizeBefore", before);
        long lastCompaction = lastCompactionMillis();
        long t0 = System.currentTimeMillis();

        if (("all".equals(kind) && s.compact) || "compact".equals(kind)) {
            progress.phase = "compact";
            if (!gc.available()) {
                run.put("compaction", "unavailable");
            } else {
                long c0 = System.currentTimeMillis();
                Map<String, Object> status = gc.run(GC_TIMEOUT);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("status", status.get("code"));
                c.put("message", status.get("message"));
                long millis = System.currentTimeMillis() - c0;
                c.put("millis", millis);
                // Oak reports "succeeded" even when it skipped the collection: because one ran less than
                // ten hours ago (its back-off), or because its estimation found the store had not grown by
                // compaction.sizeDeltaEstimation (1 GB) since the last one. The segment store's own gc.log
                // gets a line only for a compaction that really ran.
                long previous = lastCompaction;
                long latest = lastCompactionMillis();
                if (Integer.valueOf(RevisionGc.SUCCEEDED).equals(status.get("code")) && latest <= previous) {
                    c.put("skipped", true);
                    boolean backoff = previous > 0 && c0 - previous < BACKOFF_MILLIS;
                    c.put("message", backoff
                            ? "Oak skipped it: a compaction ran less than 10 hours ago (" + Checks.ago(Instant.ofEpochMilli(previous)) + ")"
                            : "Oak estimated too little garbage since the last compaction and skipped it"
                                    + " (compaction.sizeDeltaEstimation); set compaction.disableEstimation=true on the"
                                    + " SegmentNodeStoreService to compact whenever maintenance asks");
                }
                run.put("compaction", c);
            }
        }
        if (("all".equals(kind) && s.purgeVersions) || "versions".equals(kind)) {
            progress.phase = "versions";
            try (ResourceResolver rr = serviceResolver()) {
                Session session = rr.adaptTo(Session.class);
                Versions.Result r = Versions.purge(session, VERSION_ROOTS, s.keepVersions, s.keepDays);
                session.save();
                run.put("versions", r.toJson());
            }
        }
        long after = settledSize();
        run.put("sizeAfter", after);
        run.put("recovered", Math.max(0, before - after));
        run.put("millis", System.currentTimeMillis() - t0);
        run.put("finished", Instant.now().toString());
        record(run);
        progress.last = run;
        LOG.info("Health: maintenance {} by {} — {} recovered", kind, by, Checks.bytes(before - after));
    }

    private void record(Map<String, Object> run) throws LoginException, RepositoryException {
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            Node runs = folder(session, RUNS);
            String name = LocalDateTime.now().format(RUN_NAME);
            Node n = runs.addNode(name, "nt:unstructured");
            n.setProperty("json", Json.write(run));
            n.setProperty("started", Calendar.getInstance());
            n.setProperty("recovered", ((Number) run.get("recovered")).longValue());
            // keep the last 60 runs
            List<String> names = new ArrayList<>();
            for (NodeIterator it = runs.getNodes(); it.hasNext(); ) {
                names.add(it.nextNode().getName());
            }
            Collections.sort(names);
            for (int i = 0; i < names.size() - 60; i++) {
                runs.getNode(names.get(i)).remove();
            }
            session.save();
        }
    }

    public List<Map<String, Object>> runs(int limit) throws LoginException, RepositoryException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            if (!session.nodeExists(RUNS)) {
                return out;
            }
            TreeMap<String, Node> sorted = new TreeMap<>(Collections.reverseOrder());
            for (NodeIterator it = session.getNode(RUNS).getNodes(); it.hasNext(); ) {
                Node n = it.nextNode();
                sorted.put(n.getName(), n);
            }
            for (Node n : sorted.values()) {
                if (out.size() >= limit) {
                    break;
                }
                if (n.hasProperty("json")) {
                    Map<String, Object> m = new LinkedHashMap<>(parseObject(n.getProperty("json").getString()));
                    m.put("id", n.getName());
                    out.add(m);
                }
            }
        }
        return out;
    }

    public Map<String, Object> lastRun() throws LoginException, RepositoryException {
        if (progress.last != null) {
            return progress.last;
        }
        List<Map<String, Object>> runs = runs(1);
        return runs.isEmpty() ? null : runs.get(0);
    }

    // ------------------------------------------------------------------------------------------ size

    /** The repository on disk: {@code <sling home>/repository}. */
    /**
     * The repository's size once Oak has finished with it: the segment store removes the tar files a
     * compaction made obsolete a few seconds after the collection returns (its file reaper), so right
     * after a run the directory is at its largest — old and new files side by side. Samples every few
     * seconds until two in a row agree, at most a minute.
     */
    long settledSize() {
        long last = repositorySize();
        for (int i = 0; i < SETTLE_SAMPLES; i++) {
            try {
                Thread.sleep(SETTLE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return last;
            }
            long now = repositorySize();
            if (now == last && i > 0) {
                return now;
            }
            last = now;
        }
        return last;
    }

    /**
     * When the segment store last compacted for real, from its {@code segmentstore/gc.log} (a CSV line per
     * compaction: size, reclaimed, time in epoch milliseconds, generation…), or 0 without one.
     */
    long lastCompactionMillis() {
        File log = new File(repositoryDir(), "segmentstore/gc.log");
        if (!log.isFile()) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(log.toPath());
            for (int i = lines.size() - 1; i >= 0; i--) {
                String[] f = lines.get(i).split(",");
                if (f.length > 2) {
                    return Long.parseLong(f[2].trim());
                }
            }
        } catch (IOException | NumberFormatException e) {
            LOG.debug("Health: cannot read {}", log, e);
        }
        return 0;
    }

    File repositoryDir() {
        return new File(slingSettings.getSlingHomePath(), "repository");
    }

    public long repositorySize() {
        File dir = repositoryDir();
        if (!dir.isDirectory()) {
            return 0;
        }
        AtomicLong total = new AtomicLong();
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            s.filter(Files::isRegularFile).forEach(p -> total.addAndGet(p.toFile().length()));
        } catch (IOException e) {
            LOG.debug("Could not measure {}", dir, e);
        }
        return total.get();
    }

    /** One sample per day, the first time the scheduler runs that day (and after each maintenance run). */
    void sampleSize() {
        String today = LocalDate.now().toString();
        if (today.equals(lastSampleDay)) {
            return;
        }
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            Node size = folder(session, SIZE);
            if (!size.hasProperty(today)) {
                size.setProperty(today, repositorySize());
                // keep 90 days
                List<String> names = new ArrayList<>();
                for (javax.jcr.PropertyIterator it = size.getProperties(); it.hasNext(); ) {
                    String n = it.nextProperty().getName();
                    if (n.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        names.add(n);
                    }
                }
                Collections.sort(names);
                for (int i = 0; i < names.size() - 90; i++) {
                    size.getProperty(names.get(i)).remove();
                }
                session.save();
            }
            lastSampleDay = today;
        } catch (Exception e) {
            LOG.debug("Could not sample the repository size", e);
        }
    }

    public Map<String, Long> sizeSamples() throws LoginException, RepositoryException {
        Map<String, Long> out = new TreeMap<>();
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            if (!session.nodeExists(SIZE)) {
                return out;
            }
            for (javax.jcr.PropertyIterator it = session.getNode(SIZE).getProperties(); it.hasNext(); ) {
                javax.jcr.Property p = it.nextProperty();
                if (p.getName().matches("\\d{4}-\\d{2}-\\d{2}")) {
                    out.put(p.getName(), p.getLong());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ settings

    public Settings settings() throws LoginException, RepositoryException {
        Settings s = new Settings();
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            if (!session.nodeExists(SETTINGS)) {
                return s;
            }
            Node n = session.getNode(SETTINGS);
            s.enabled = !n.hasProperty("enabled") || n.getProperty("enabled").getBoolean();
            s.hour = n.hasProperty("hour") ? (int) n.getProperty("hour").getLong() : s.hour;
            s.compact = !n.hasProperty("compact") || n.getProperty("compact").getBoolean();
            s.purgeVersions = !n.hasProperty("purgeVersions") || n.getProperty("purgeVersions").getBoolean();
            s.keepVersions = n.hasProperty("keepVersions") ? (int) n.getProperty("keepVersions").getLong() : s.keepVersions;
            s.keepDays = n.hasProperty("keepDays") ? (int) n.getProperty("keepDays").getLong() : s.keepDays;
        }
        return s;
    }

    public Settings saveSettings(Map<String, String> values) throws LoginException, RepositoryException {
        Settings s = settings();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            switch (e.getKey()) {
                case "enabled":
                    s.enabled = Boolean.parseBoolean(v);
                    break;
                case "hour":
                    s.hour = Math.max(0, Math.min(23, Integer.parseInt(v)));
                    break;
                case "compact":
                    s.compact = Boolean.parseBoolean(v);
                    break;
                case "purgeVersions":
                    s.purgeVersions = Boolean.parseBoolean(v);
                    break;
                case "keepVersions":
                    s.keepVersions = Math.max(1, Integer.parseInt(v));
                    break;
                case "keepDays":
                    s.keepDays = Math.max(0, Integer.parseInt(v));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown setting " + e.getKey());
            }
        }
        try (ResourceResolver rr = serviceResolver()) {
            Session session = rr.adaptTo(Session.class);
            Node n = session.nodeExists(SETTINGS) ? session.getNode(SETTINGS) : folder(session, ROOT).addNode("settings", "nt:unstructured");
            n.setProperty("enabled", s.enabled);
            n.setProperty("hour", s.hour);
            n.setProperty("compact", s.compact);
            n.setProperty("purgeVersions", s.purgeVersions);
            n.setProperty("keepVersions", s.keepVersions);
            n.setProperty("keepDays", s.keepDays);
            session.save();
        }
        return s;
    }

    // ------------------------------------------------------------------------------------------ helpers

    public ResourceResolver serviceResolver() throws LoginException {
        return resolverFactory.getServiceResourceResolver(Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseObject(String json) {
        Object o = Json.parse(json);
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    static Node folder(Session session, String path) throws RepositoryException {
        if (session.nodeExists(path)) {
            return session.getNode(path);
        }
        Node parent = folder(session, path.substring(0, Math.max(1, path.lastIndexOf('/'))));
        return parent.addNode(path.substring(path.lastIndexOf('/') + 1), "sling:Folder");
    }

    public Map<String, Object> progress() {
        return progress.toJson();
    }

    RevisionGc gc() {
        return gc;
    }

    /** A missed run is made up only when the instance has been up this long: not in the middle of a start. */
    static final long CATCH_UP_AFTER_MILLIS = 10 * 60 * 1000L;

    /**
     * Whether the nightly run should start now: in its hour when it has not run today, or later (when
     * {@code catchUp}) when the last run is older than the most recent time it was due, because nothing
     * was awake at that hour.
     */
    static boolean due(LocalDateTime now, int hour, Instant lastStarted, ZoneId zone, boolean catchUp) {
        LocalDateTime at = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
        if (at.isAfter(now)) {
            at = at.minusDays(1);
        }
        boolean ranSince = lastStarted != null && !lastStarted.isBefore(at.atZone(zone).toInstant());
        if (ranSince) {
            return false;
        }
        return now.getHour() == hour || catchUp;
    }

    /** When the next scheduled run would start, from the settings. */
    public String nextRun(Settings s) {
        if (!s.enabled) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.withHour(s.hour).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(now)) {
            next = next.plusDays(1);
        }
        return next.atZone(ZoneId.systemDefault()).toInstant().toString();
    }

    static final class Progress {
        volatile boolean running;
        volatile String kind;
        volatile String phase;
        volatile String by;
        volatile long started;
        volatile long finished;
        volatile String error;
        volatile Map<String, Object> last;

        void begin(String kind, String by) {
            running = true;
            this.kind = kind;
            this.by = by;
            phase = "size";
            started = System.currentTimeMillis();
            finished = 0;
            error = null;
        }

        void end() {
            running = false;
            finished = System.currentTimeMillis();
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", running);
            m.put("kind", kind);
            m.put("phase", phase);
            m.put("by", by);
            m.put("started", started == 0 ? null : Instant.ofEpochMilli(started).toString());
            m.put("finished", finished == 0 ? null : Instant.ofEpochMilli(finished).toString());
            m.put("error", error);
            return m;
        }
    }
}
