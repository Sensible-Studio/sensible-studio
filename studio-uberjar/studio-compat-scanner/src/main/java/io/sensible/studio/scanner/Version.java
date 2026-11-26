/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.scanner;

/**
 * OSGi version {@code major.minor.micro.qualifier}.
 */
final class Version implements Comparable<Version> {

    final int major;
    final int minor;
    final int micro;
    final String qualifier;

    private Version(int major, int minor, int micro, String qualifier) {
        this.major = major;
        this.minor = minor;
        this.micro = micro;
        this.qualifier = qualifier;
    }

    static Version parse(String s) {
        String[] p = s.trim().split("\\.", 4);
        return new Version(num(p, 0), num(p, 1), num(p, 2), p.length > 3 ? p[3] : "");
    }

    private static int num(String[] p, int i) {
        return i < p.length && !p[i].isEmpty() ? Integer.parseInt(p[i]) : 0;
    }

    @Override
    public int compareTo(Version o) {
        if (major != o.major) {
            return Integer.compare(major, o.major);
        }
        if (minor != o.minor) {
            return Integer.compare(minor, o.minor);
        }
        if (micro != o.micro) {
            return Integer.compare(micro, o.micro);
        }
        return qualifier.compareTo(o.qualifier);
    }
}

/**
 * OSGi version range: {@code [1.2,2)}, {@code (1,2]} or a bare floor {@code 1.2} (meaning at
 * least 1.2, no ceiling).
 */
final class VersionRange {

    final String floor;
    private final boolean floorInclusive;
    private final String ceiling;
    private final boolean ceilingInclusive;

    private VersionRange(String floor, boolean floorInclusive, String ceiling, boolean ceilingInclusive) {
        this.floor = floor;
        this.floorInclusive = floorInclusive;
        this.ceiling = ceiling;
        this.ceilingInclusive = ceilingInclusive;
    }

    static VersionRange parse(String s) {
        String t = s.trim();
        if (t.startsWith("[") || t.startsWith("(")) {
            String[] parts = t.substring(1, t.length() - 1).split(",");
            return new VersionRange(parts[0].trim(), t.startsWith("["), parts[1].trim(), t.endsWith("]"));
        }
        return new VersionRange(t, true, null, false);
    }

    boolean includes(String version) {
        Version v = Version.parse(version);
        int lo = v.compareTo(Version.parse(floor));
        if (lo < 0 || (lo == 0 && !floorInclusive)) {
            return false;
        }
        if (ceiling == null) {
            return true;
        }
        int hi = v.compareTo(Version.parse(ceiling));
        return hi < 0 || (hi == 0 && ceilingInclusive);
    }
}
