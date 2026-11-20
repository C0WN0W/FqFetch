package com.cownow.fqfetch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 反射工具：全部通过宿主 ClassLoader 工作。
 * <p>
 * 对应 BeanShell 脚本里的 rfGet / rfSet / rfStaticField / rfStaticMethod / findClass。
 */
final class Ref {

    private Ref() {
    }

    // ---------------- 类查找 ----------------

    static Class<?> findClass(ClassLoader cl, String name) {
        if (cl == null || name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Class<?> findClass(ClassLoader cl, String... names) {
        if (names == null) {
            return null;
        }
        for (String n : names) {
            Class<?> c = findClass(cl, n);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    // ---------------- 实例化 ----------------

    static Object newInstance(Class<?> cls) {
        if (cls == null) {
            return null;
        }
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            L.file("实例化失败 " + cls.getName() + " : " + D.err(t));
            return null;
        }
    }

    // ---------------- 字段 ----------------

    static Field field(Class<?> cls, String name) {
        if (cls == null || name == null) {
            return null;
        }
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            for (Class<?> itf : k.getInterfaces()) {
                try {
                    Field f = itf.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return null;
    }

    static Object get(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Field f = field(obj.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Object getStatic(Class<?> cls, String name) {
        Field f = field(cls, name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean set(Object obj, String name, Object value) {
        if (obj == null) {
            return false;
        }
        Field f = field(obj.getClass(), name);
        if (f == null) {
            return false;
        }
        try {
            f.set(obj, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean setStatic(Class<?> cls, String name, Object value) {
        Field f = field(cls, name);
        if (f == null) {
            return false;
        }
        try {
            f.set(null, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 先写字段，字段不存在就退回 setter（脚本只支持字段，这里多一层兜底）。 */
    static boolean put(Object obj, String name, Object value) {
        if (obj == null || name == null || name.isEmpty()) {
            return false;
        }
        if (set(obj, name, value)) {
            return true;
        }
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            call(obj, setter, value);
            return true;
        } catch (Throwable t) {
            L.file("put 失败 " + obj.getClass().getName() + "#" + name + " : " + D.err(t));
            return false;
        }
    }

    /** 先读字段，字段不存在就退回 getter。 */
    static Object read(Object obj, String name) {
        Object v = get(obj, name);
        if (v != null) {
            return v;
        }
        try {
            return call(obj, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- 方法 ----------------

    static Method method(Class<?> cls, String name, Object[] args) {
        if (cls == null) {
            return null;
        }
        int n = args == null ? 0 : args.length;
        for (Class<?> k = cls; k != null; k = k.getSuperclass()) {
            Method m = pick(k.getDeclaredMethods(), name, n, args);
            if (m != null) {
                return m;
            }
        }
        return pick(cls.getMethods(), name, n, args);
    }

    private static Method pick(Method[] methods, String name, int n, Object[] args) {
        for (Method m : methods) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != n) {
                continue;
            }
            if (!fitsAll(m.getParameterTypes(), args)) {
                continue;
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    static Object call(Object target, String name, Object... args) {
        Class<?> cls = target instanceof Class ? (Class<?>) target : (target == null ? null : target.getClass());
        Method m = method(cls, name, args);
        if (m == null) {
            throw wrap(new NoSuchMethodException((cls == null ? "?" : cls.getName()) + "#" + name));
        }
        try {
            return m.invoke(target instanceof Class ? null : target, args);
        } catch (InvocationTargetException e) {
            throw wrap(e.getCause() == null ? e : e.getCause());
        } catch (Throwable t) {
            throw wrap(t);
        }
    }

    static Object callStatic(Class<?> cls, String name, Object... args) {
        if (cls == null) {
            return null;
        }
        return call(cls, name, args);
    }

    /** 同名方法不存在时返回 null，不抛异常。 */
    static Object callOrNull(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---------------- 参数匹配 ----------------

    private static boolean fitsAll(Class<?>[] params, Object[] args) {
        if (args == null) {
            return params.length == 0;
        }
        for (int i = 0; i < params.length; i++) {
            if (!fits(params[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean fits(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        return wrap(param).isInstance(arg);
    }

    private static Class<?> wrap(Class<?> p) {
        if (!p.isPrimitive()) {
            return p;
        }
        if (p == int.class) return Integer.class;
        if (p == long.class) return Long.class;
        if (p == boolean.class) return Boolean.class;
        if (p == double.class) return Double.class;
        if (p == float.class) return Float.class;
        if (p == short.class) return Short.class;
        if (p == byte.class) return Byte.class;
        if (p == char.class) return Character.class;
        return p;
    }

    static RuntimeException wrap(Throwable t) {
        if (t instanceof RuntimeException) {
            return (RuntimeException) t;
        }
        return new RuntimeException(t);
    }
}
