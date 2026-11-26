/*
 * Licensed under the Apache License, Version 2.0. Sensible Studio project.
 */
package io.sensible.studio.health.impl;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

/**
 * Oak's revision garbage collection (compaction of the segment store), reached through the MBean Oak
 * registers ({@code org.apache.jackrabbit.oak:type=RepositoryManagement}): the same thing AEM's Revision
 * Cleanup task calls. Starts it, waits for it to end, and reads its status.
 */
final class RevisionGc {

    /** Oak's status codes, as its {@code Status} composite data reports them. */
    static final int UNAVAILABLE = 0;
    static final int NONE = 1;
    static final int INITIATED = 2;
    static final int RUNNING = 3;
    static final int SUCCEEDED = 4;
    static final int FAILED = 5;

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private volatile BundleContext context;

    void context(BundleContext ctx) {
        this.context = ctx;
    }

    /**
     * The bean, wherever it is. Oak registers it as an OSGi service carrying {@code jmx.objectname}
     * ({@code type=RevisionGarbageCollection} for the segment store, {@code type=RepositoryManagement}
     * from oak-core); a JMX whiteboard, when one is installed, copies such services into the platform
     * MBean server. Without one the service is called directly, by reflection, so this bundle does not
     * depend on Oak's classes.
     */
    private Object target() throws Exception {
        BundleContext ctx = context;
        if (ctx != null) {
            for (String clazz : new String[] { "org.apache.jackrabbit.oak.spi.state.RevisionGCMBean", "org.apache.jackrabbit.oak.api.jmx.RepositoryManagementMBean" }) {
                ServiceReference<?>[] refs = ctx.getAllServiceReferences(clazz, null);
                if (refs != null && refs.length > 0) {
                    Object svc = ctx.getService(refs[0]);
                    if (svc != null) {
                        return svc;
                    }
                }
            }
        }
        for (String type : new String[] { "RevisionGarbageCollection", "RepositoryManagement" }) {
            Set<ObjectName> names = server.queryNames(new ObjectName("org.apache.jackrabbit.oak:type=" + type + ",*"), null);
            if (!names.isEmpty()) {
                return names.iterator().next();
            }
        }
        return null;
    }

    private CompositeData call(Object target, String op) throws Exception {
        if (target instanceof ObjectName) {
            return (CompositeData) server.invoke((ObjectName) target, op, new Object[0], new String[0]);
        }
        Method m = null;
        for (Class<?> c = target.getClass(); c != null && m == null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                try {
                    m = i.getMethod(op);
                    break;
                } catch (NoSuchMethodException e) {
                    // next interface
                }
            }
        }
        if (m == null) {
            m = target.getClass().getMethod(op);
        }
        m.setAccessible(true);
        return (CompositeData) m.invoke(target);
    }

    boolean available() {
        try {
            return target() != null;
        } catch (Exception e) {
            return false;
        }
    }

    Map<String, Object> status() throws Exception {
        Object t = target();
        if (t == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", UNAVAILABLE);
            m.put("message", "Oak's revision garbage collection is not available");
            return m;
        }
        return toMap(call(t, "getRevisionGCStatus"));
    }

    /** Starts the collection and waits for it (at most {@code timeoutMillis}); the final status. */
    Map<String, Object> run(long timeoutMillis) throws Exception {
        Object t = target();
        if (t == null) {
            throw new IllegalStateException("Oak's revision garbage collection is not available");
        }
        Map<String, Object> s = toMap(call(t, "startRevisionGC"));
        long until = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < until) {
            int code = ((Number) s.getOrDefault("code", NONE)).intValue();
            if (code != INITIATED && code != RUNNING) {
                break;
            }
            Thread.sleep(2000);
            s = toMap(call(t, "getRevisionGCStatus"));
        }
        return s;
    }

    private static Map<String, Object> toMap(CompositeData d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) {
            m.put("code", NONE);
            return m;
        }
        for (String key : d.getCompositeType().keySet()) {
            Object v = d.get(key);
            m.put(key, v instanceof Number || v instanceof Boolean ? v : v == null ? null : String.valueOf(v));
        }
        return m;
    }
}
