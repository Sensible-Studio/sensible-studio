package io.sensible.studio.workflow.impl;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Between the two workflow APIs. {@code com.day.cq.workflow} is the older one and
 * {@code com.adobe.granite.workflow} mirrors it type for type and method for method, so one
 * object stands for the other by name: a call on a {@code com.day.cq.workflow.exec.WorkItem} is
 * the call of the same name on the Granite work item behind it, with what goes in and what comes
 * out carried over the same way. The engine knows the Granite API only.
 */
final class Legacy {

    static final String GRANITE = "com.adobe.granite.workflow";
    static final String OLD = "com.day.cq.workflow";

    /**
     * The class loader that sees every type of both APIs: the API bundle's. This bundle imports only the
     * packages its own code names, so a type it never mentions (the older model types) is not visible from it.
     */
    private static final ClassLoader API = com.day.cq.workflow.WorkflowException.class.getClassLoader();

    private Legacy() {
    }

    /** {@code granite} as the older type of the same name. */
    static <T> T old(Object granite, Class<T> type) {
        return type.cast(over(granite, GRANITE, OLD, type));
    }

    /** {@code old} as the Granite type of the same name. */
    static <T> T granite(Object old, Class<T> type) {
        return type.cast(over(old, OLD, GRANITE, type));
    }

    /** What stands behind an object made here, or the object itself. */
    static Object behind(Object o) {
        if (o != null && Proxy.isProxyClass(o.getClass())) {
            InvocationHandler h = Proxy.getInvocationHandler(o);
            if (h instanceof Over) {
                return ((Over) h).target;
            }
        }
        return o;
    }

    private static Object over(Object value, String from, String to, Class<?> wanted) {
        if (value == null) {
            return null;
        }
        Object behind = behind(value);
        if (behind != value && (wanted == null || wanted.isInstance(behind))) {
            return behind; // it came from the other side: give back what it stands for
        }
        Set<Class<?>> types = new LinkedHashSet<>();
        if (wanted != null && wanted.isInterface() && wanted.getName().startsWith(to)) {
            types.add(wanted);
        }
        collect(value.getClass(), from, to, types);
        if (types.isEmpty()) {
            return value;
        }
        return Proxy.newProxyInstance(API, types.toArray(new Class<?>[0]), new Over(value, from, to));
    }

    /** The interfaces of the other API that {@code c} has a namesake for. */
    private static void collect(Class<?> c, String from, String to, Set<Class<?>> out) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (i.getName().startsWith(from + ".") || i.getName().equals(from)) {
                    try {
                        out.add(Class.forName(to + i.getName().substring(from.length()), false, API));
                    } catch (ClassNotFoundException e) {
                        // no namesake: the type exists on one side only
                    }
                }
                collect(i, from, to, out);
            }
        }
    }

    private static final class Over implements InvocationHandler {
        private final Object target;
        private final String from;
        private final String to;

        Over(Object target, String from, String to) {
            this.target = target;
            this.from = from;
            this.to = to;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] in = args == null ? new Object[0] : args.clone();
            if (method.getDeclaringClass() == Object.class) {
                switch (method.getName()) {
                    case "equals":
                        return behind(in[0]) == target || target.equals(behind(in[0]));
                    case "hashCode":
                        return target.hashCode();
                    default:
                        return String.valueOf(target);
                }
            }
            Method there = find(method, in);
            if (there == null) {
                throw new UnsupportedOperationException(method.getName() + " is not available in Sensible Studio");
            }
            Class<?>[] wanted = there.getParameterTypes();
            for (int i = 0; i < in.length; i++) {
                in[i] = carry(in[i], to, from, wanted[i]);
            }
            try {
                there.setAccessible(true);
                return carry(there.invoke(target, in), from, to, method.getReturnType());
            } catch (InvocationTargetException e) {
                throw thrown(e.getCause(), method);
            }
        }

        /** The method of the same name on the other side whose parameters take what is given. */
        private Method find(Method method, Object[] in) {
            for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
                for (Class<?> i : allInterfaces(k)) {
                    for (Method m : i.getMethods()) {
                        if (m.getName().equals(method.getName()) && m.getParameterCount() == in.length && fits(m, method)) {
                            return m;
                        }
                    }
                }
            }
            return null;
        }

        private boolean fits(Method there, Method here) {
            Class<?>[] a = there.getParameterTypes();
            Class<?>[] b = here.getParameterTypes();
            for (int i = 0; i < a.length; i++) {
                String x = a[i].getName();
                String y = b[i].getName();
                if (!x.equals(y) && !x.replace(from, to).equals(y) && !x.replace(to, from).equals(y)) {
                    return false;
                }
            }
            return true;
        }

        private static List<Class<?>> allInterfaces(Class<?> k) {
            List<Class<?>> out = new ArrayList<>();
            for (Class<?> i : k.getInterfaces()) {
                out.add(i);
                out.addAll(allInterfaces(i));
            }
            return out;
        }

        /** An exception of one API is the exception of the same name of the other. */
        private Throwable thrown(Throwable cause, Method method) {
            if (cause == null) {
                return new IllegalStateException(method.getName() + " failed");
            }
            String name = cause.getClass().getName();
            if (name.startsWith(from + ".")) {
                for (Class<?> declared : method.getExceptionTypes()) {
                    if (declared.getName().equals(to + name.substring(from.length()))) {
                        try {
                            return (Throwable) declared.getConstructor(String.class, Throwable.class).newInstance(cause.getMessage(), cause);
                        } catch (ReflectiveOperationException e) {
                            break;
                        }
                    }
                }
            }
            return cause;
        }
    }

    /** A value on its way across: objects of the API by their namesake, lists, arrays and iterators one by one. */
    private static Object carry(Object value, String from, String to, Class<?> wanted) {
        if (value == null) {
            return null;
        }
        if (value instanceof List && !(value instanceof java.util.Map)) {
            List<Object> out = new ArrayList<>();
            for (Object o : (List<?>) value) {
                out.add(carry(o, from, to, null));
            }
            return out;
        }
        if (value instanceof Iterator) {
            Iterator<?> it = (Iterator<?>) value;
            return new Iterator<Object>() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public Object next() {
                    return carry(it.next(), from, to, null);
                }
            };
        }
        if (value.getClass().isArray() && wanted != null && wanted.isArray() && !wanted.getComponentType().isPrimitive()
                && wanted.getComponentType().getName().startsWith(to)) {
            int n = Array.getLength(value);
            Object out = Array.newInstance(wanted.getComponentType(), n);
            for (int i = 0; i < n; i++) {
                Array.set(out, i, carry(Array.get(value, i), from, to, wanted.getComponentType()));
            }
            return out;
        }
        if (value.getClass().isArray() || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return over(value, from, to, wanted);
    }
}
