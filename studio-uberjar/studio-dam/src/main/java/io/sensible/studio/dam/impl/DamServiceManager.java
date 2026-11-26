/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.dam.impl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the connection to studio-dam and, in {@code embedded} mode, the process itself.
 *
 * <ul>
 * <li>{@code embedded}: starts the binary when Sensible Studio starts, restarts it if it dies (with
 * backoff), stops it with SIGTERM when Sensible Studio stops. Listens on loopback only, with a token that
 * is generated once and kept in {@code dam/token} unless one is configured.</li>
 * <li>{@code external}: talks to a service someone else runs (Docker, Kubernetes).</li>
 * <li>{@code disabled}: no processing; jobs wait and retry.</li>
 * </ul>
 * Relative paths are resolved against {@code sling.home} (the {@code instances/author} folder).
 */
@Component(service = DamClient.class, configurationPolicy = ConfigurationPolicy.OPTIONAL, immediate = true)
@Designate(ocd = DamServiceManager.Config.class)
public class DamServiceManager implements DamClient {

    @ObjectClassDefinition(name = "Sensible Studio DAM service", description = "Connection to the Rust studio-dam process")
    public @interface Config {
        @AttributeDefinition(name = "Mode", options = {
                @Option(label = "Embedded (Sensible Studio starts the binary)", value = "embedded"),
                @Option(label = "External (already running)", value = "external"),
                @Option(label = "Disabled", value = "disabled")})
        String mode() default "embedded";

        @AttributeDefinition(name = "URL", description = "Base URL; in embedded mode only host and port are used")
        String url() default "http://127.0.0.1:4510";

        @AttributeDefinition(name = "Token", type = AttributeType.PASSWORD,
                description = "Shared secret. Required in external mode; generated once into dam/token in embedded mode when empty")
        String token() default "";

        @AttributeDefinition(name = "Binary", description = "Path to studio-dam (embedded mode)")
        String binary() default "dam/bin/studio-dam";

        @AttributeDefinition(name = "Blob store", description = "Where studio-dam keeps originals and renditions (embedded mode)")
        String store() default "dam/store";

        @AttributeDefinition(name = "Delivery cache")
        String cache() default "dam/cache";

        @AttributeDefinition(name = "Log file")
        String log() default "logs/dam-service.log";

        @AttributeDefinition(name = "Workers", description = "Concurrent image jobs; 0 = number of CPUs")
        int workers() default 0;

        @AttributeDefinition(name = "Startup timeout (seconds)")
        int startupTimeoutSeconds() default 20;

        @AttributeDefinition(name = "Request timeout (seconds)")
        int requestTimeoutSeconds() default 300;
    }

    private static final Logger LOG = LoggerFactory.getLogger(DamServiceManager.class);
    private static final long MAX_BACKOFF_MS = 60_000;

    private volatile DamClient delegate;
    /** Same token as {@link #delegate}, but set as soon as the configuration is known. */
    private volatile DamClient signer;
    private volatile Process process;
    private volatile boolean stopping;
    private Thread supervisor;
    private String mode = "disabled";

    @Activate
    protected void activate(BundleContext context, Config config) {
        String home = context == null ? null : context.getProperty("sling.home");
        start(config, home == null ? new File(".") : new File(home));
    }

    /** Separate from {@link #activate} so tests can run it without OSGi. */
    void start(Config config, File slingHome) {
        stopping = false;
        mode = config.mode() == null ? "embedded" : config.mode().trim().toLowerCase(Locale.ROOT);
        Duration timeout = Duration.ofSeconds(Math.max(10, config.requestTimeoutSeconds()));
        switch (mode) {
            case "external":
                if (config.token().isEmpty()) {
                    LOG.error("studio-dam: external mode needs a token; processing stays disabled");
                    return;
                }
                java.net.URI external = java.net.URI.create(config.url());
                if ("http".equalsIgnoreCase(external.getScheme()) && !isLoopback(external.getHost())) {
                    LOG.warn("studio-dam: {} is plain HTTP to another host; the token travels unencrypted. Use https.",
                            config.url());
                }
                delegate = new HttpDamClient(config.url(), config.token(), timeout);
                signer = delegate;
                LOG.info("studio-dam: using external service at {} (healthy: {})", config.url(), delegate.isHealthy());
                return;
            case "embedded":
                startEmbedded(config, slingHome, timeout);
                return;
            default:
                LOG.info("studio-dam: disabled; assets will not be processed");
        }
    }

    private void startEmbedded(Config config, File home, Duration timeout) {
        File binary = resolve(home, config.binary());
        if (binary.isFile() && !binary.canExecute() && binary.setExecutable(true, true)) {
            // Copies made by tools that do not keep the mode (archives, file sync) lose the x bit.
            LOG.info("studio-dam: made {} executable (owner only)", binary);
        }
        if (!binary.canExecute()) {
            LOG.warn("studio-dam: binary not found or not executable at {}; build dam-service and copy "
                    + "target/release/studio-dam there. Processing jobs will wait.", binary);
            return;
        }
        java.net.URI url = java.net.URI.create(config.url());
        String host = url.getHost() == null ? "127.0.0.1" : url.getHost();
        if (!isLoopback(host)) {
            LOG.error("studio-dam: embedded mode only listens on loopback, not {}; use external mode for "
                    + "a service other machines can reach", host);
            return;
        }
        int port = url.getPort() < 0 ? 4510 : url.getPort();
        String token;
        try {
            token = config.token().isEmpty() ? persistentToken(resolve(home, "dam/token")) : config.token();
        } catch (IOException e) {
            LOG.error("studio-dam: cannot read or create the token file: {}", e.getMessage());
            return;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("--bind");
        cmd.add(host + ":" + port);
        cmd.add("--datastore");
        cmd.add(resolve(home, config.store()).getAbsolutePath());
        cmd.add("--cache");
        cmd.add(resolve(home, config.cache()).getAbsolutePath());
        cmd.add("--models");
        cmd.add(resolve(home, "dam/models").getAbsolutePath());
        if (config.workers() > 0) {
            cmd.add("--workers");
            cmd.add(String.valueOf(config.workers()));
        }
        File log = resolve(home, config.log());
        if (log.getParentFile() != null) {
            log.getParentFile().mkdirs();
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        pb.environment().put("STUDIO_DAM_TOKEN", token);

        HttpDamClient client = new HttpDamClient("http://" + host + ":" + port, token, timeout);
        signer = client;
        int startupTimeout = Math.max(1, config.startupTimeoutSeconds());
        supervisor = new Thread(() -> supervise(pb, client, startupTimeout), "studio-dam-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    /**
     * Start, wait for health, wait for exit, back off, repeat — until deactivated. Whatever
     * way the loop ends, the process it started is stopped: no orphan keeps the port.
     */
    private void supervise(ProcessBuilder pb, HttpDamClient client, int startupTimeoutSeconds) {
        long backoff = 1000;
        try {
            while (!stopping) {
                long started = System.currentTimeMillis();
                try {
                    process = pb.start();
                    if (stopping) {
                        break;
                    }
                    if (waitHealthy(client, startupTimeoutSeconds)) {
                        delegate = client;
                        LOG.info("studio-dam: started (pid {})", process.pid());
                    } else {
                        LOG.error("studio-dam: did not become healthy within {}s (port taken by another program?), see {}",
                                startupTimeoutSeconds, pb.redirectOutput().file());
                        process.destroy();
                    }
                    int exit = process.waitFor();
                    delegate = null;
                    if (stopping) {
                        break;
                    }
                    LOG.warn("studio-dam: exited with code {}, restarting in {} ms", exit, backoff);
                } catch (IOException e) {
                    LOG.error("studio-dam: cannot start: {}", e.getMessage());
                }
                if (System.currentTimeMillis() - started > 5 * MAX_BACKOFF_MS) {
                    backoff = 1000; // it ran fine for a while: this is a fresh failure
                }
                Thread.sleep(backoff);
                backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
            }
        } catch (InterruptedException e) {
            // deactivated
        } finally {
            delegate = null;
            Thread.interrupted(); // clear the flag so the shutdown wait below works
            stopProcess(process);
        }
    }

    /** SIGTERM (studio-dam finishes in-flight requests), then SIGKILL after 10 s. */
    private static void stopProcess(Process p) {
        if (p == null || !p.isAlive()) {
            return;
        }
        p.destroy();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private boolean waitHealthy(DamClient client, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (process != null && !process.isAlive()) {
                return false;
            }
            if (client.isHealthy()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Deactivate
    protected void deactivate() {
        stopping = true;
        delegate = null;
        signer = null;
        Thread t = supervisor;
        if (t != null) {
            t.interrupt();
            try {
                t.join(15_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopProcess(process); // no-op normally; covers a supervisor that did not finish in time
        LOG.info("studio-dam: stopped");
    }

    boolean isRunning() {
        return delegate != null;
    }

    /** For tests and diagnostics. */
    Process currentProcess() {
        return process;
    }

    private DamClient client() throws IOException {
        DamClient d = delegate;
        if (d == null) {
            throw new IOException("studio-dam is not available (mode " + mode + ")");
        }
        return d;
    }

    @Override
    public String putBlob(InputStream in) throws IOException {
        return client().putBlob(in);
    }

    @Override
    public ProcessResult process(String blobId, String mimeType) throws IOException {
        return client().process(blobId, mimeType);
    }

    @Override
    public ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra) throws IOException {
        return client().process(blobId, mimeType, extra);
    }

    @Override
    public ProcessResult process(String blobId, String mimeType, java.util.List<java.util.Map<String, Object>> extra,
            java.util.Map<String, Object> watermark) throws IOException {
        return client().process(blobId, mimeType, extra, watermark);
    }

    @Override
    public InputStream getBlob(String blobId) throws IOException {
        return client().getBlob(blobId);
    }

    @Override
    public EditResult edit(String blobId, List<Map<String, Object>> operations) throws IOException {
        return client().edit(blobId, operations);
    }

    @Override
    public Map<String, Object> smartTagsStatus() throws IOException {
        return client().smartTagsStatus();
    }

    @Override
    public void installSmartTagsFile(String name, InputStream in) throws IOException {
        client().installSmartTagsFile(name, in);
    }

    @Override
    public List<Map<String, Object>> predictTags(String blobId, List<Map<String, String>> labels, int max, double minConfidence,
            double minScore) throws IOException {
        return client().predictTags(blobId, labels, max, minConfidence, minScore);
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, int height, String format, int quality, String transform) throws IOException {
        return client().deliver(blobId, width, height, format, quality, transform);
    }

    @Override
    public Map<String, Object> smartCrop(String blobId, List<Map<String, Object>> ratios) throws IOException {
        return client().smartCrop(blobId, ratios);
    }

    @Override
    public TextEmbedding embedText(String text) throws IOException {
        return client().embedText(text);
    }

    @Override
    public Map<String, Object> searchModelStatus() throws IOException {
        return client().searchModelStatus();
    }

    @Override
    public void installSearchModelFile(String name, InputStream in) throws IOException {
        client().installSearchModelFile(name, in);
    }

    @Override
    public float[] searchEmbed(String blobId) throws IOException {
        return client().searchEmbed(blobId);
    }

    @Override
    public TextEmbedding searchEmbedText(String text) throws IOException {
        return client().searchEmbedText(text);
    }

    @Override
    public float[] embed(String blobId) throws IOException {
        return client().embed(blobId);
    }

    @Override
    public byte[] videoPoster(String blobId) throws IOException {
        return client().videoPoster(blobId);
    }

    @Override
    public String videoPreview(String blobId) throws IOException {
        return client().videoPreview(blobId);
    }

    @Override
    public String videoEncode(String blobId, String format, int height) throws IOException {
        return client().videoEncode(blobId, format, height);
    }

    @Override
    public Boolean videoTools() {
        try {
            return client().videoTools();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public Map<String, Object> gcBlobs(java.util.Collection<String> keep, long graceSeconds, boolean dryRun) throws IOException {
        return client().gcBlobs(keep, graceSeconds, dryRun);
    }

    @Override
    public boolean isHealthy() {
        DamClient d = delegate;
        return d != null && d.isHealthy();
    }

    @Override
    public boolean isAvailable() {
        return delegate != null;
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality) throws IOException {
        return client().deliver(blobId, width, format, quality);
    }

    @Override
    public DeliveredImage deliver(String blobId, int width, String format, int quality, String transform) throws IOException {
        return client().deliver(blobId, width, format, quality, transform);
    }

    /**
     * Uses the configured client even while the process restarts: URLs rendered into pages must
     * not depend on whether studio-dam happens to be up at that moment.
     */
    @Override
    public String signUrl(String canonical) throws IOException {
        DamClient s = signer;
        if (s == null) {
            throw new IOException("studio-dam is not configured");
        }
        return s.signUrl(canonical);
    }

    private static File resolve(File home, String path) {
        File f = new File(path);
        return f.isAbsolute() ? f : new File(home, path);
    }

    /**
     * The token also signs delivery URLs that end up in cached pages, so it must survive restarts:
     * generated once, stored owner-readable only.
     */
    static String persistentToken(File file) throws IOException {
        java.nio.file.Path path = file.toPath();
        java.nio.file.Path dir = path.getParent();
        boolean posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        if (!java.nio.file.Files.isDirectory(dir)) {
            if (posix) {
                java.nio.file.Files.createDirectories(dir,
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
            } else {
                java.nio.file.Files.createDirectories(dir);
            }
        }
        if (java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (posix && !OWNER_ONLY_FILE.containsAll(java.nio.file.Files.getPosixFilePermissions(path,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                LOG.warn("studio-dam: {} was readable by other users; restricting it to the owner", path);
                java.nio.file.Files.setPosixFilePermissions(path, OWNER_ONLY_FILE);
            }
            String t = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (t.length() >= 32) {
                return t;
            }
        }
        String t = randomToken();
        // Created owner-only from the start (no window with umask permissions), never through a symlink.
        java.nio.file.Path tmp = dir.resolve(file.getName() + "." + randomToken().substring(0, 8) + ".tmp");
        java.util.Set<java.nio.file.OpenOption> opts = new java.util.HashSet<>(java.util.Arrays.asList(
                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
        java.nio.file.attribute.FileAttribute<?>[] attrs = posix
                ? new java.nio.file.attribute.FileAttribute<?>[] {
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE)}
                : new java.nio.file.attribute.FileAttribute<?>[0];
        try (java.nio.channels.SeekableByteChannel ch = java.nio.file.Files.newByteChannel(tmp, opts, attrs)) {
            ch.write(java.nio.ByteBuffer.wrap(t.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        java.nio.file.Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return t;
    }

    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_FILE =
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
    private static final java.util.Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY_DIR =
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");

    static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return "localhost".equalsIgnoreCase(h) || h.startsWith("127.") || "::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h);
    }

    private static String randomToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }
}
