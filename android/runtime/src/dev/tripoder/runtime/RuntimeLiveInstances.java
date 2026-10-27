package dev.tripoder.runtime;

import dev.tripoder.selfhook.coverage.LoadedClassInventory;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-scoped live guest object discovery and weak handle registry for
 * {@code heap_query(action=choose_instances)} and {@code guest_invoke(instanceId=...)}.
 *
 * <p>Discovery never initializes guest classes ({@code Class.forName(name, false, loader)})
 * and never retains strong references across calls.
 */
public final class RuntimeLiveInstances {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final WeakHashMap<Object, String> OBJECT_TO_ID = new WeakHashMap<>();
    private static final LinkedHashMap<String, WeakReference<Object>> ID_TO_OBJECT = new LinkedHashMap<>();
    private static final int MAX_HANDLES = 512;

    private RuntimeLiveInstances() {}

    public static synchronized String register(Object instance) {
        String existing = OBJECT_TO_ID.get(instance);
        if (existing != null) {
            ID_TO_OBJECT.put(existing, new WeakReference<>(instance));
            return existing;
        }
        pruneExpiredLocked();
        String id = "inst-" + NEXT_ID.getAndIncrement();
        OBJECT_TO_ID.put(instance, id);
        ID_TO_OBJECT.put(id, new WeakReference<>(instance));
        while (ID_TO_OBJECT.size() > MAX_HANDLES) {
            String eldest = ID_TO_OBJECT.keySet().iterator().next();
            ID_TO_OBJECT.remove(eldest);
        }
        return id;
    }

    public static synchronized Object requireInstance(String instanceId, Class<?> expectedClass) {
        if (instanceId == null || !instanceId.matches("^inst-[0-9]{1,9}$")) {
            throw new IllegalArgumentException("INSTANCE_ID_INVALID");
        }
        pruneExpiredLocked();
        WeakReference<Object> ref = ID_TO_OBJECT.get(instanceId);
        Object target = ref == null ? null : ref.get();
        if (target == null) {
            throw new IllegalStateException("INSTANCE_EXPIRED_OR_NOT_FOUND:" + instanceId);
        }
        if (!expectedClass.isInstance(target)) {
            throw new IllegalArgumentException(
                    "INSTANCE_CLASS_MISMATCH:" + target.getClass().getName() + "!=" + expectedClass.getName());
        }
        return target;
    }

    private static void pruneExpiredLocked() {
        ID_TO_OBJECT.entrySet().removeIf(entry -> entry.getValue().get() == null);
    }

    public static Map<String, Object> chooseInstances(String className, int limit) {
        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("CLASS_NAME_REQUIRED");
        }
        if (limit < 1 || limit > 64) {
            throw new IllegalArgumentException("LIMIT_INVALID");
        }
        RuntimeEngine engine = RuntimeEngine.current;
        if (engine == null) {
            throw new IllegalStateException("RUNTIME_NOT_READY");
        }
        ClassLoader loader = engine.guestLoader();
        Class<?> targetClass;
        try {
            targetClass = Class.forName(className.trim(), false, loader);
        } catch (ClassNotFoundException error) {
            throw new IllegalArgumentException("GUEST_CLASS_NOT_FOUND:" + className.trim());
        }
        if (!engine.ownsLoader(targetClass.getClassLoader())) {
            throw new IllegalArgumentException("CLASS_OUTSIDE_GUEST");
        }
        IdentityHashMap<Object, String> matched = new IdentityHashMap<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();

        // 1. Direct runtime roots (Application, Activities, Services, Providers, Receivers)
        if (engine.application != null) {
            traverseObject(engine.application, "runtime.application", targetClass, matched, visited, 0, limit);
        }
        for (Object activity : engine.diagnosticActivities()) {
            if (matched.size() >= limit) break;
            traverseObject(activity, "runtime.activity", targetClass, matched, visited, 0, limit);
        }
        if (matched.size() < limit && engine.services != null) {
            traverseObject(engine.services, "runtime.services", targetClass, matched, visited, 0, limit);
        }
        if (matched.size() < limit && engine.providers != null) {
            traverseObject(engine.providers, "runtime.providers", targetClass, matched, visited, 0, limit);
        }
        if (matched.size() < limit && engine.receivers != null) {
            traverseObject(engine.receivers, "runtime.receivers", targetClass, matched, visited, 0, limit);
        }

        // 2. Target class own static fields (e.g. Singleton INSTANCE)
        if (matched.size() < limit) {
            inspectClassStatics(targetClass, "class.static:" + targetClass.getName(), targetClass, matched, visited, limit);
        }

        // 3. Loaded guest classes in the same package hierarchy (without initializing unloaded classes)
        if (matched.size() < limit) {
            for (Class<?> loaded : loadedGuestClasses(engine, targetClass)) {
                if (matched.size() >= limit) break;
                if (loaded == targetClass) continue;
                inspectClassStatics(loaded, "class.static:" + loaded.getName(), targetClass, matched, visited, limit);
            }
        }

        List<Map<String, Object>> instances = new ArrayList<>(matched.size());
        for (Map.Entry<Object, String> entry : matched.entrySet()) {
            Object instance = entry.getKey();
            String id = register(instance);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instanceId", id);
            row.put("className", instance.getClass().getName());
            row.put("identityHash", System.identityHashCode(instance));
            row.put("source", entry.getValue());
            row.put("summary", summarize(instance));
            instances.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("className", targetClass.getName());
        out.put("count", instances.size());
        out.put("instances", instances);
        return out;
    }

    private static List<Class<?>> loadedGuestClasses(RuntimeEngine engine, Class<?> targetClass) {
        List<Class<?>> out = new ArrayList<>();
        try {
            LoadedClassInventory.Snapshot snapshot = LoadedClassInventory.capture(16384);
            for (Class<?> candidate : snapshot.classes) {
                if (candidate != null && engine.ownsLoader(candidate.getClassLoader())) {
                    out.add(candidate);
                }
            }
        } catch (Throwable ignored) {
            // Native inventory only available when libtripoderselfhook.so is loaded;
            // also check classes discovered via javaObservation in the same package.
        }
        String name = targetClass.getName();
        int dot = name.lastIndexOf('.');
        String prefix = dot > 0 ? name.substring(0, dot) : "";
        try {
            List<String> names = engine.hooks.javaObservation().searchClasses(prefix, 200);
            if (names != null) {
                ClassLoader loader = engine.guestLoader();
                for (String className : names) {
                    if (className != null) {
                        try {
                            Class<?> cls = Class.forName(className, false, loader);
                            if (engine.ownsLoader(cls.getClassLoader())) {
                                out.add(cls);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void inspectClassStatics(
            Class<?> owner,
            String sourcePrefix,
            Class<?> targetClass,
            IdentityHashMap<Object, String> matched,
            IdentityHashMap<Object, Boolean> visited,
            int limit) {
        Field[] fields;
        try {
            fields = owner.getDeclaredFields();
        } catch (Throwable error) {
            return;
        }
        for (Field field : fields) {
            if (matched.size() >= limit) return;
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType().isPrimitive()) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    traverseObject(value, sourcePrefix + "#" + field.getName(), targetClass, matched, visited, 0, limit);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void traverseObject(
            Object current,
            String source,
            Class<?> targetClass,
            IdentityHashMap<Object, String> matched,
            IdentityHashMap<Object, Boolean> visited,
            int depth,
            int limit) {
        if (current == null || matched.size() >= limit || depth > 3) return;
        if (targetClass.isInstance(current)) {
            matched.putIfAbsent(current, source);
            if (matched.size() >= limit) return;
        }
        if (visited.put(current, Boolean.TRUE) != null) return;
        Class<?> cls = current.getClass();
        if (cls == String.class || cls == Class.class || Number.class.isAssignableFrom(cls) || cls == Boolean.class) {
            return;
        }
        if (current instanceof Collection<?> collection) {
            int idx = 0;
            for (Object element : collection) {
                if (matched.size() >= limit || idx >= 32) break;
                traverseObject(element, source + "[" + idx + "]", targetClass, matched, visited, depth + 1, limit);
                idx++;
            }
            return;
        }
        if (current instanceof Map<?, ?> map) {
            int idx = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (matched.size() >= limit || idx >= 32) break;
                traverseObject(entry.getKey(), source + "{k" + idx + "}", targetClass, matched, visited, depth + 1, limit);
                traverseObject(entry.getValue(), source + "{v" + idx + "}", targetClass, matched, visited, depth + 1, limit);
                idx++;
            }
            return;
        }
        if (cls.isArray()) {
            if (cls.getComponentType().isPrimitive()) return;
            int len = Math.min(Array.getLength(current), 32);
            for (int i = 0; i < len && matched.size() < limit; i++) {
                traverseObject(Array.get(current, i), source + "[" + i + "]", targetClass, matched, visited, depth + 1, limit);
            }
            return;
        }
        if (depth >= 2) return;
        for (Class<?> cursor = cls; cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
            String cname = cursor.getName();
            if (cname.startsWith("java.") || cname.startsWith("javax.") || cname.startsWith("dalvik.")) break;
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable error) {
                break;
            }
            for (Field field : fields) {
                if (matched.size() >= limit) return;
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object child = field.get(current);
                    if (child != null) {
                        traverseObject(child, source + "." + field.getName(), targetClass, matched, visited, depth + 1, limit);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static String summarize(Object instance) {
        String raw = instance.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(instance));
        return raw.length() > 160 ? raw.substring(0, 160) : raw;
    }

    public static Map<String, Object> readField(String instanceId, String fieldName) {
        Object target = requireInstance(instanceId, Object.class);
        if (fieldName == null || fieldName.isEmpty() || fieldName.length() > 128) {
            throw new IllegalArgumentException("FIELD_NAME_INVALID");
        }
        Field field = null;
        for (Class<?> cursor = target.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
            try {
                field = cursor.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException ignored) {}
        }
        if (field == null) {
            throw new IllegalArgumentException("FIELD_NOT_FOUND:" + fieldName);
        }
        field.setAccessible(true);
        Object raw;
        try {
            raw = field.get(target);
        } catch (IllegalAccessException impossible) {
            throw new IllegalStateException("FIELD_ACCESS_FAILED", impossible);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.put("className", target.getClass().getName());
        out.put("fieldName", fieldName);
        out.put("fieldType", field.getType().getName());
        if (raw == null
                || raw instanceof String
                || raw instanceof Boolean
                || raw instanceof Number
                || raw instanceof Character
                || raw instanceof byte[]) {
            out.put("value", dev.tripoder.runtime.script.JavaObjectSnapshots.shallowValue(raw));
        } else {
            String childId = register(raw);
            out.put("valueInstanceId", childId);
            out.put("valueClassName", raw.getClass().getName());
            out.put("value", dev.tripoder.runtime.script.JavaObjectSnapshots.shallowValue(raw));
        }
        return out;
    }

    public static Map<String, Object> snapshotContainer(String instanceId, int maxEntries) {
        if (maxEntries < 1 || maxEntries > 64) {
            throw new IllegalArgumentException("MAX_ENTRIES_INVALID");
        }
        Object target = requireInstance(instanceId, Object.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instanceId", instanceId);
        out.putAll(dev.tripoder.runtime.script.JavaObjectSnapshots.snapshotLiveContainer(target, maxEntries));
        return out;
    }
}
