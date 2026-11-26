/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.Statistics;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

/**
 * The checks on the Health page, each a card: OK, warning or problem, with the numbers behind it. They
 * ask the running system directly (the JVM, the OSGi framework, Sling's job manager, the log file on
 * disk), so the page says how this instance is right now.
 */
final class Checks {

    private Checks() {
    }

    static final String OK = "ok";
    static final String WARN = "warn";
    static final String ERROR = "error";

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    static Map<String, Object> check(String id, String title, String status, String summary, Map<String, Object> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("status", status);
        m.put("summary", summary);
        m.put("details", details);
        return m;
    }

    /** Free space on the disk the repository lives on. */
    static Map<String, Object> disk(File repository) {
        File probe = repository.exists() ? repository : repository.getParentFile();
        long total = probe.getTotalSpace();
        long free = probe.getUsableSpace();
        double freePct = total == 0 ? 0 : 100.0 * free / total;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("free", free);
        d.put("total", total);
        d.put("path", probe.getAbsolutePath());
        String status = free < 2L * 1024 * 1024 * 1024 ? ERROR : freePct < 10 ? WARN : OK;
        return check("disk", "Disk space", status, bytes(free) + " free of " + bytes(total), d);
    }

    /** JVM heap: used against the maximum it may grow to. */
    static Map<String, Object> memory() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        double pct = max == 0 ? 0 : 100.0 * used / max;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("used", used);
        d.put("max", max);
        d.put("percent", Math.round(pct));
        String status = pct > 92 ? ERROR : pct > 80 ? WARN : OK;
        return check("memory", "Memory", status, bytes(used) + " of " + bytes(max) + " heap in use", d);
    }

    /** Every bundle should be active (fragments are resolved, which is their active). */
    static Map<String, Object> bundles(BundleContext context) {
        List<String> notActive = new ArrayList<>();
        int total = 0;
        for (Bundle b : context.getBundles()) {
            total++;
            boolean fragment = b.getHeaders().get(Constants.FRAGMENT_HOST) != null;
            int state = b.getState();
            boolean fine = state == Bundle.ACTIVE || (fragment && state == Bundle.RESOLVED);
            if (!fine) {
                notActive.add(b.getSymbolicName() + " (" + stateName(state) + ")");
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("total", total);
        d.put("notActive", notActive);
        String status = notActive.isEmpty() ? OK : notActive.size() > 3 ? ERROR : WARN;
        return check("bundles", "Bundles", status, notActive.isEmpty() ? total + " bundles, all active" : notActive.size() + " of " + total + " not active", d);
    }

    private static String stateName(int state) {
        switch (state) {
            case Bundle.INSTALLED:
                return "installed";
            case Bundle.RESOLVED:
                return "resolved";
            case Bundle.STARTING:
                return "starting";
            case Bundle.STOPPING:
                return "stopping";
            case Bundle.UNINSTALLED:
                return "uninstalled";
            default:
                return "active";
        }
    }

    /** Sling's job queues: media processing, publishing, flows, translations all run as jobs. */
    static Map<String, Object> jobs(JobManager jobs) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (jobs == null) {
            return check("jobs", "Jobs", WARN, "The job manager is not available", d);
        }
        Statistics s = jobs.getStatistics();
        d.put("queued", s.getNumberOfQueuedJobs());
        d.put("active", s.getNumberOfActiveJobs());
        d.put("failed", s.getNumberOfFailedJobs());
        d.put("cancelled", s.getNumberOfCancelledJobs());
        d.put("finished", s.getNumberOfFinishedJobs());
        d.put("averageProcessingMillis", s.getAverageProcessingTime());
        long queued = s.getNumberOfQueuedJobs();
        long failed = s.getNumberOfFailedJobs() + s.getNumberOfCancelledJobs();
        String status = queued > 500 ? ERROR : queued > 50 || failed > 0 ? WARN : OK;
        String summary = queued + " waiting, " + s.getNumberOfActiveJobs() + " running" + (failed > 0 ? ", " + failed + " failed" : "");
        return check("jobs", "Jobs", status, summary, d);
    }

    /** Errors in the last 24 hours of the log, and the last few of them. */
    static Map<String, Object> log(File errorLog) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("path", errorLog.getAbsolutePath());
        if (!errorLog.isFile()) {
            return check("log", "Errors in the log", WARN, "No error.log at " + errorLog.getAbsolutePath(), d);
        }
        d.put("size", errorLog.length());
        List<String> last = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        for (String line : tail(errorLog, 4L * 1024 * 1024)) {
            if (line.length() < 20 || !(line.contains("*ERROR*") || line.contains("*WARN*"))) {
                continue;
            }
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(line.substring(0, 19), LOG_TIME);
            } catch (DateTimeParseException e) {
                continue;
            }
            if (at.isBefore(since)) {
                continue;
            }
            if (line.contains("*ERROR*")) {
                errors++;
                last.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
                if (last.size() > 8) {
                    last.remove(0);
                }
            } else {
                warnings++;
            }
        }
        d.put("errors", errors);
        d.put("warnings", warnings);
        d.put("last", last);
        String status = errors > 50 ? ERROR : errors > 0 ? WARN : OK;
        return check("log", "Errors in the log", status, errors + " error" + (errors == 1 ? "" : "s") + ", " + warnings + " warning" + (warnings == 1 ? "" : "s") + " in the last 24 hours", d);
    }

    /** The last up to {@code maxBytes} of a file, as lines. */
    static List<String> tail(File file, long maxBytes) {
        List<String> lines = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - maxBytes);
            raf.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            for (String l : text.split("\n")) {
                lines.add(l);
            }
            if (from > 0 && !lines.isEmpty()) {
                lines.remove(0); // a cut line
            }
        } catch (IOException e) {
            // no log
        }
        return lines;
    }

    /** The maintenance itself: when it last ran, and whether it is overdue. */
    static Map<String, Object> maintenance(Map<String, Object> lastRun, Maintenance.Settings settings, String next) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", settings.enabled);
        d.put("next", next);
        if (lastRun != null) {
            d.put("last", lastRun.get("finished"));
            d.put("recovered", lastRun.get("recovered"));
        }
        if (!settings.enabled) {
            return check("maintenance", "Nightly maintenance", WARN, "Switched off: the repository is not compacted and versions are not purged", d);
        }
        if (lastRun == null) {
            return check("maintenance", "Nightly maintenance", WARN, "Has not run yet; the first run is tonight at " + String.format("%02d:00", settings.hour), d);
        }
        Instant finished = Instant.parse(String.valueOf(lastRun.get("finished")));
        long hours = (System.currentTimeMillis() - finished.toEpochMilli()) / 3_600_000L;
        Object recovered = lastRun.get("recovered");
        String summary = "Last run " + ago(finished) + ", " + bytes(recovered instanceof Number ? ((Number) recovered).longValue() : 0) + " recovered";
        return check("maintenance", "Nightly maintenance", hours > 48 ? WARN : OK, summary, d);
    }

    /** The repository on disk and how it changed over the sampled days. */
    static Map<String, Object> repository(long size, Map<String, Long> samples) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("size", size);
        d.put("samples", samples);
        Long weekAgo = null;
        if (!samples.isEmpty()) {
            String key = java.time.LocalDate.now().minusDays(7).toString();
            for (Map.Entry<String, Long> e : samples.entrySet()) {
                if (e.getKey().compareTo(key) >= 0) {
                    weekAgo = e.getValue();
                    break;
                }
            }
        }
        String summary = bytes(size);
        if (weekAgo != null && weekAgo > 0) {
            long delta = size - weekAgo;
            summary += delta >= 0 ? ", +" + bytes(delta) + " in 7 days" : ", −" + bytes(-delta) + " in 7 days";
            d.put("delta7d", delta);
        }
        return check("repository", "Repository size", OK, summary, d);
    }

    static String bytes(long n) {
        long abs = Math.abs(n);
        if (abs < 1024) {
            return n + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double v = abs;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (n < 0 ? "-" : "") + (v >= 100 ? String.format("%.0f", v) : String.format("%.1f", v)) + " " + units[u];
    }

    static String ago(Instant t) {
        long s = (System.currentTimeMillis() - t.toEpochMilli()) / 1000;
        if (s < 90) {
            return "just now";
        }
        if (s < 3600) {
            return s / 60 + " min ago";
        }
        if (s < 86_400) {
            return s / 3600 + " h ago";
        }
        return s / 86_400 + " d ago";
    }

    static String isoNow() {
        return Instant.now().atZone(ZoneId.systemDefault()).toInstant().toString();
    }
}
