package io.sensible.studio.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Main Entry Point for Sensible Studio Quickstart.
 * Mimics the traditional AEM cq-quickstart.jar experience, booting Apache Sling 12,
 * Jackrabbit Oak JCR, Apache Felix OSGi, and the Sensible Studio Compatibility layer.
 */
public class StudioQuickstart {

    private static final Logger log = LoggerFactory.getLogger(StudioQuickstart.class);

    private int port = 4502;
    private String runMode = "author";
    private String homeDir = "./instances/author";
    private String featureUrl = "mvn:org.apache.sling/org.apache.sling.starter/12/slingosgifeature/oak_tar";
    private boolean help = false;

    public static void main(String[] args) {
        StudioQuickstart quickstart = new StudioQuickstart();
        quickstart.parseArgs(args);

        if (quickstart.help) {
            printHelp();
            return;
        }

        quickstart.start();
    }

    public void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-p".equals(arg) || "--port".equals(arg)) {
                if (i + 1 < args.length) {
                    this.port = Integer.parseInt(args[++i]);
                }
            } else if ("-r".equals(arg) || "--runmode".equals(arg)) {
                if (i + 1 < args.length) {
                    this.runMode = args[++i];
                }
            } else if ("-c".equals(arg) || "--home".equals(arg)) {
                if (i + 1 < args.length) {
                    this.homeDir = args[++i];
                }
            } else if ("-f".equals(arg) || "--feature".equals(arg)) {
                if (i + 1 < args.length) {
                    this.featureUrl = args[++i];
                }
            } else if ("-h".equals(arg) || "--help".equals(arg)) {
                this.help = true;
            }
        }
    }

    public void start() {
        File quickstartFolder = new File(homeDir);
        if (!quickstartFolder.exists()) {
            quickstartFolder.mkdirs();
        }

        StudioBanner.print(port, runMode, quickstartFolder.getAbsolutePath());

        // Configure system properties for Felix / Sling / Oak
        System.setProperty("org.osgi.service.http.port", String.valueOf(port));
        System.setProperty("sling.home", quickstartFolder.getAbsolutePath());
        System.setProperty("sling.run.modes", runMode);

        log.info("Starting Sensible Studio on port {} (runmode: {})...", port, runMode);

        // Feature launcher arguments
        List<String> launcherArgs = new ArrayList<>();
        launcherArgs.add("-f");
        launcherArgs.add(featureUrl);
        launcherArgs.add("-u");
        launcherArgs.add("https://repo.maven.apache.org/maven2");
        launcherArgs.add("-c");
        launcherArgs.add(new File(quickstartFolder, "cache").getAbsolutePath());
        launcherArgs.add("-p");
        launcherArgs.add(quickstartFolder.getAbsolutePath());
        launcherArgs.add("-D");
        launcherArgs.add("org.osgi.service.http.port=" + port);

        // Launch Sling Feature Launcher
        try {
            org.apache.sling.feature.launcher.impl.Main.main(launcherArgs.toArray(new String[0]));
        } catch (Throwable t) {
            log.error("Failed to boot Sensible Studio Engine: " + t.getMessage(), t);
        }
    }

    private static void printHelp() {
        System.out.println("Sensible Studio CLI");
        System.out.println("Usage: java -jar studio-engine-launcher.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -p, --port <port>        HTTP Port to listen on (default: 4502)");
        System.out.println("  -r, --runmode <mode>     Runmode (e.g. author, publish) (default: author)");
        System.out.println("  -c, --home <path>        Quickstart root directory (default: ./instances/author)");
        System.out.println("  -h, --help               Display this help message");
    }

    public int getPort() {
        return port;
    }

    public String getRunMode() {
        return runMode;
    }

    public String getHomeDir() {
        return homeDir;
    }
}
