/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.scanner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lists the proprietary packages an AEM project's bundles import, and checks them against what
 * Sensible Studio exports.
 *
 * <pre>
 * java -jar studio-compat-scanner.jar [--studio studio-cq-api.jar] &lt;jar|zip|dir&gt;...
 * </pre>
 *
 * <p>Inputs may be bundle jars, content packages (e.g. {@code wknd.all-x.zip}, which embed
 * bundles under {@code jcr_root/apps/.../install}) or directories searched recursively. Only
 * the project's own manifests are read; nothing is decompiled.
 */
public final class CompatScanner {

    /** Packages that come from Adobe's proprietary runtime. */
    static final String[] PROPRIETARY = {"com.day.", "com.adobe."};

    /** Adobe-namespaced but Apache-licensed; deploy the real bundle instead of reimplementing. */
    static final String[] OPEN_SOURCE_ADOBE = {"com.adobe.cq.wcm.core.components", "com.adobe.cq.wcm.style.",
            "com.adobe.cq.commerce.core.components"};

    static final class Need {
        final String pkg;
        final String range;
        final boolean optional;
        final String bundle;

        Need(String pkg, String range, boolean optional, String bundle) {
            this.pkg = pkg;
            this.range = range;
            this.optional = optional;
            this.bundle = bundle;
        }
    }

    private final List<Need> needs = new ArrayList<>();
    private final Map<String, String> bundleExports = new TreeMap<>();
    private final Map<String, String> studioExports = new TreeMap<>();

    public static void main(String[] args) throws IOException {
        CompatScanner scanner = new CompatScanner();
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--studio".equals(args[i]) && i + 1 < args.length) {
                scanner.loadStudioExports(Paths.get(args[++i]));
            } else {
                inputs.add(args[i]);
            }
        }
        if (inputs.isEmpty()) {
            System.err.println("usage: java -jar studio-compat-scanner.jar [--studio studio-cq-api.jar] <jar|zip|dir>...");
            System.exit(2);
        }
        for (String input : inputs) {
            scanner.scan(Paths.get(input));
        }
        int missing = scanner.report(System.out);
        System.exit(missing == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ input

    void loadStudioExports(Path jar) throws IOException {
        try (InputStream in = Files.newInputStream(jar)) {
            Manifest mf = manifestOf(in);
            if (mf != null) {
                for (Header h : Header.parse(mf.getMainAttributes().getValue("Export-Package"))) {
                    studioExports.put(h.name, h.attr("version", "0.0.0"));
                }
            }
        }
    }

    void scan(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> files = Files.walk(path)) {
                for (Path p : (Iterable<Path>) files.filter(CompatScanner::isArchive)::iterator) {
                    scanArchive(p.toString(), Files.readAllBytes(p));
                }
            }
        } else {
            scanArchive(path.toString(), Files.readAllBytes(path));
        }
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".jar") || n.endsWith(".zip");
    }

    /** Reads the manifest if this is a bundle, and recurses into embedded jars/zips. */
    private void scanArchive(String name, byte[] bytes) throws IOException {
        Manifest manifest = null;
        List<String> childNames = new ArrayList<>();
        List<byte[]> children = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String en = entry.getName();
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(en)) {
                    manifest = new Manifest(zip);
                } else if (!entry.isDirectory() && (en.endsWith(".jar") || en.endsWith(".zip"))) {
                    childNames.add(name + "!/" + en);
                    children.add(zip.readAllBytes());
                }
            }
        }
        if (manifest != null) {
            addBundle(name, manifest.getMainAttributes());
        }
        for (int i = 0; i < children.size(); i++) {
            scanArchive(childNames.get(i), children.get(i));
        }
    }

    void addBundle(String source, Attributes attrs) {
        String bsn = attrs.getValue("Bundle-SymbolicName");
        if (bsn == null) {
            return; // not an OSGi bundle
        }
        String bundle = bsn.split(";")[0].trim() + " (" + shortName(source) + ")";
        for (Header h : Header.parse(attrs.getValue("Export-Package"))) {
            bundleExports.put(h.name, h.attr("version", "0.0.0"));
        }
        for (Header h : Header.parse(attrs.getValue("Import-Package"))) {
            if (isProprietary(h.name)) {
                boolean optional = "optional".equals(h.directive("resolution"));
                needs.add(new Need(h.name, h.attr("version", "0.0.0"), optional, bundle));
            }
        }
    }

    private static String shortName(String source) {
        String s = source.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1);
    }

    static boolean isProprietary(String pkg) {
        for (String p : PROPRIETARY) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    static boolean isOpenSourceAdobe(String pkg) {
        for (String p : OPEN_SOURCE_ADOBE) {
            if (pkg.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ report

    /** @return number of mandatory packages Sensible Studio does not provide yet */
    int report(PrintStream out) {
        Map<String, List<Need>> byPkg = new TreeMap<>();
        for (Need n : needs) {
            byPkg.computeIfAbsent(n.pkg, k -> new ArrayList<>()).add(n);
        }
        int missing = 0;
        int satisfied = 0;
        int projectInternal = 0;
        int openSource = 0;
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, List<Need>> e : byPkg.entrySet()) {
            String pkg = e.getKey();
            List<Need> list = e.getValue();
            boolean optional = list.stream().allMatch(n -> n.optional);
            String range = narrowest(list);
            TreeSet<String> bundles = new TreeSet<>();
            list.forEach(n -> bundles.add(n.bundle));

            String status;
            if (bundleExports.containsKey(pkg)) {
                status = "in-project";
                projectInternal++;
            } else if (isOpenSourceAdobe(pkg)) {
                status = "deploy-oss-bundle";
                openSource++;
            } else if (studioExports.containsKey(pkg)) {
                String v = studioExports.get(pkg);
                boolean ok = list.stream().allMatch(n -> VersionRange.parse(n.range).includes(v));
                status = ok ? "OK (" + v + ")" : "VERSION " + v + " outside range";
                if (ok) {
                    satisfied++;
                } else if (!optional) {
                    missing++;
                }
            } else {
                status = optional ? "missing (optional)" : "MISSING";
                if (!optional) {
                    missing++;
                }
            }
            rows.add(new String[] {pkg, range, status, String.join(", ", bundles)});
        }

        out.println("Proprietary imports found: " + byPkg.size());
        out.printf("  provided by Sensible Studio: %d   missing (mandatory): %d   exported by the project itself: %d"
                + "   Apache-licensed Adobe bundles: %d%n%n", satisfied, missing, projectInternal, openSource);
        int w0 = 7;
        int w1 = 5;
        int w2 = 6;
        for (String[] r : rows) {
            w0 = Math.max(w0, r[0].length());
            w1 = Math.max(w1, r[1].length());
            w2 = Math.max(w2, r[2].length());
        }
        String fmt = "%-" + w0 + "s  %-" + w1 + "s  %-" + w2 + "s  %s%n";
        out.printf(fmt, "PACKAGE", "RANGE", "STATUS", "IMPORTED BY");
        for (String[] r : rows) {
            out.printf(fmt, (Object[]) r);
        }
        if (studioExports.isEmpty()) {
            out.println();
            out.println("(no --studio jar given: every package is reported against an empty Sensible Studio)");
        }
        return missing;
    }

    /** Picks the range with the highest floor, which is the one that constrains Sensible Studio most. */
    private static String narrowest(List<Need> list) {
        List<String> ranges = new ArrayList<>();
        list.forEach(n -> ranges.add(n.range));
        Collections.sort(ranges, (a, b) -> Version.parse(VersionRange.parse(b).floor)
                .compareTo(Version.parse(VersionRange.parse(a).floor)));
        return ranges.get(0);
    }

    static Manifest manifestOf(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) {
                    return new Manifest(zip);
                }
            }
        }
        return null;
    }

    List<Need> needs() {
        return needs;
    }
}
