/*
 * RuntimeMemoryEditor - GameGuardian/Cheat Engine style memory editor
 * for J2ME MIDlet instances running inside J2ME-Loader.
 *
 * Supports scanning and freezing int, float, and String fields
 * via Java reflection on live MIDlet object graphs (max depth 3).
 */

package javax.microedition.util;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton runtime memory editor that allows scanning and freezing fields
 * of a live MIDlet object graph, similar to GameGuardian / Cheat Engine.
 *
 * Usage:
 *   RuntimeMemoryEditor editor = RuntimeMemoryEditor.getInstance();
 *   editor.setTargetMidlet(midletInstance);
 *   List<FieldEntry> results = editor.searchByValue(100);
 *   // ... after in-game value changes ...
 *   results = editor.narrowSearch(results, 99);
 *   editor.freezeField(results.get(0), 100);
 */
public class RuntimeMemoryEditor {

    private static final String TAG = "RuntimeMemoryEditor";

    /** Maximum number of distinct objects visited during a scan to prevent OOM. */
    public static final int MAX_SCAN_OBJECTS = 5000;

    /** Maximum recursion depth when traversing the object graph. */
    private static final int MAX_DEPTH = 3;

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static final RuntimeMemoryEditor INSTANCE = new RuntimeMemoryEditor();

    private RuntimeMemoryEditor() {
        // Private constructor — use getInstance()
    }

    public static RuntimeMemoryEditor getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The MIDlet instance that is the root of the object graph scan. */
    private volatile Object targetMidlet;

    /** Fields currently frozen: maps a FieldEntry key to its frozen value. */
    private final ConcurrentHashMap<String, FrozenEntry> frozenFields = new ConcurrentHashMap<>();

    /** Background HandlerThread that runs the freeze loop every 100 ms. */
    private HandlerThread freezeThread;
    private Handler freezeHandler;
    private final Runnable freezeRunnable = new Runnable() {
        @Override
        public void run() {
            applyFrozenFields();
            if (freezeHandler != null) {
                freezeHandler.postDelayed(this, 100);
            }
        }
    };

    // -------------------------------------------------------------------------
    // Public API — target
    // -------------------------------------------------------------------------

    /**
     * Sets the MIDlet instance whose object graph will be scanned.
     * Safe to call from any thread.
     */
    public void setTargetMidlet(Object midlet) {
        this.targetMidlet = midlet;
        Log.d(TAG, "Target MIDlet set: " + (midlet != null ? midlet.getClass().getName() : "null"));
    }

    public Object getTargetMidlet() {
        return targetMidlet;
    }

    // -------------------------------------------------------------------------
    // Inner class: FieldEntry
    // -------------------------------------------------------------------------

    /**
     * Represents a single field discovered during a scan.
     * Carries enough information to read and write the field on its owner object.
     */
    public static class FieldEntry {
        /** The object that owns this field. */
        public final Object owner;
        /** The reflective Field descriptor. */
        public final Field field;
        /** Human-readable dot-separated path from the scan root, e.g. "this.player.hp". */
        public final String path;

        public FieldEntry(Object owner, Field field, String path) {
            this.owner = owner;
            this.field = field;
            this.path = path;
            try {
                field.setAccessible(true);
            } catch (SecurityException e) {
                // Silently ignored — getValue/setValue will return null / no-op
                Log.w(TAG, "setAccessible failed for " + path + ": " + e.getMessage());
            }
        }

        /**
         * Returns the current value of the field on its owner.
         * Returns {@code null} on any error.
         */
        public Object getValue() {
            try {
                return field.get(owner);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                Log.w(TAG, "getValue failed for " + path + ": " + e.getMessage());
                return null;
            }
        }

        /**
         * Sets the field on its owner to {@code v}.
         * Silently ignores access / type errors.
         */
        public void setValue(Object v) {
            try {
                field.set(owner, v);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                Log.w(TAG, "setValue failed for " + path + ": " + e.getMessage());
            }
        }

        /**
         * A stable key used to identify this entry in the frozen-fields map.
         * Composed of identity hash of owner + field signature.
         */
        String frozenKey() {
            return System.identityHashCode(owner) + "#" + field.getDeclaringClass().getName() + "." + field.getName();
        }

        @Override
        public String toString() {
            return path + " = " + getValue();
        }
    }

    // -------------------------------------------------------------------------
    // Internal freeze bookkeeping
    // -------------------------------------------------------------------------

    private static class FrozenEntry {
        final FieldEntry entry;
        volatile Object value;

        FrozenEntry(FieldEntry entry, Object value) {
            this.entry = entry;
            this.value = value;
        }
    }

    // -------------------------------------------------------------------------
    // Public API — scanning
    // -------------------------------------------------------------------------

    /**
     * Recursively scans all non-static, non-primitive fields of {@code obj}
     * (and its reachable sub-objects) up to {@link #MAX_DEPTH} levels deep,
     * collecting every declared field of the requested {@code type}.
     *
     * @param obj  The root object to scan.
     * @param type The field type to collect (e.g. {@code int.class}, {@code String.class}).
     * @return Mutable list of matching {@link FieldEntry} objects.
     */
    public List<FieldEntry> scanFields(Object obj, Class<?> type) {
        List<FieldEntry> results = new ArrayList<>();
        if (obj == null || type == null) {
            return results;
        }
        Set<Integer> visited = new HashSet<>();
        scanFieldsRecursive(obj, type, "root", 0, visited, results);
        Log.d(TAG, "scanFields(" + type.getSimpleName() + "): " + results.size() + " found, " + visited.size() + " objects visited");
        return results;
    }

    private void scanFieldsRecursive(Object obj, Class<?> targetType, String path,
                                     int depth, Set<Integer> visited, List<FieldEntry> out) {
        if (obj == null || depth > MAX_DEPTH) return;
        if (visited.size() >= MAX_SCAN_OBJECTS) return;

        int id = System.identityHashCode(obj);
        if (!visited.add(id)) return; // already visited — cycle guard

        Class<?> clazz = obj.getClass();

        // Skip JVM / Android internals and primitive wrapper arrays to keep scan fast
        String className = clazz.getName();
        if (className.startsWith("java.lang.reflect.")
                || className.startsWith("dalvik.")
                || className.startsWith("libcore.")
                || clazz.isArray()) {
            return;
        }

        // Walk the entire class hierarchy (including superclasses)
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (SecurityException e) {
                continue;
            }

            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;

                String fieldPath = path + "." + f.getName();
                Class<?> fType = f.getType();

                // --- Match declared target type ---
                boolean typeMatch = false;
                if (targetType == int.class && (fType == int.class || fType == Integer.class)) {
                    typeMatch = true;
                } else if (targetType == float.class && (fType == float.class || fType == Float.class)) {
                    typeMatch = true;
                } else if (targetType == String.class && fType == String.class) {
                    typeMatch = true;
                } else if (fType == targetType) {
                    typeMatch = true;
                }

                if (typeMatch) {
                    try {
                        f.setAccessible(true);
                    } catch (SecurityException ignored) {}
                    out.add(new FieldEntry(obj, f, fieldPath));
                }

                // --- Recurse into non-primitive, non-String reference fields ---
                if (depth < MAX_DEPTH
                        && !fType.isPrimitive()
                        && !fType.isArray()
                        && fType != String.class
                        && !fType.getName().startsWith("java.lang.")
                        && !fType.getName().startsWith("android.")
                        && !fType.getName().startsWith("java.util.")) {
                    try {
                        f.setAccessible(true);
                        Object child = f.get(obj);
                        if (child != null) {
                            scanFieldsRecursive(child, targetType, fieldPath, depth + 1, visited, out);
                        }
                    } catch (SecurityException | IllegalAccessException | IllegalArgumentException ignored) {}
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API — search by value
    // -------------------------------------------------------------------------

    /**
     * Scans the target MIDlet's object graph for all {@code int}/{@code Integer}
     * fields whose current value equals {@code value}.
     *
     * @param value The int value to search for.
     * @return Matching field entries.
     */
    public List<FieldEntry> searchByValue(int value) {
        Object target = targetMidlet;
        if (target == null) {
            Log.w(TAG, "searchByValue(int): targetMidlet is null");
            return Collections.emptyList();
        }
        List<FieldEntry> all = scanFields(target, int.class);
        List<FieldEntry> results = new ArrayList<>();
        for (FieldEntry e : all) {
            Object v = e.getValue();
            if (v instanceof Integer && (Integer) v == value) {
                results.add(e);
            } else if (e.field.getType() == int.class) {
                // primitive — getValue() returns boxed Integer
                if (v instanceof Integer && (Integer) v == value) {
                    results.add(e);
                }
            }
        }
        Log.d(TAG, "searchByValue(int=" + value + "): " + results.size() + " matches");
        return results;
    }

    /**
     * Scans for all {@code float}/{@code Float} fields whose value is within
     * {@code tolerance} of {@code value}.
     *
     * @param value     The float value to search for.
     * @param tolerance Maximum allowed absolute difference.
     * @return Matching field entries.
     */
    public List<FieldEntry> searchByValue(float value, float tolerance) {
        Object target = targetMidlet;
        if (target == null) {
            Log.w(TAG, "searchByValue(float): targetMidlet is null");
            return Collections.emptyList();
        }
        List<FieldEntry> all = scanFields(target, float.class);
        List<FieldEntry> results = new ArrayList<>();
        for (FieldEntry e : all) {
            Object v = e.getValue();
            if (v instanceof Float) {
                if (Math.abs((Float) v - value) <= tolerance) {
                    results.add(e);
                }
            }
        }
        Log.d(TAG, "searchByValue(float=" + value + ", tol=" + tolerance + "): " + results.size() + " matches");
        return results;
    }

    /**
     * Scans for all {@code String} fields whose value equals {@code value}
     * (case-sensitive exact match).
     *
     * @param value The String value to search for.
     * @return Matching field entries.
     */
    public List<FieldEntry> searchByValue(String value) {
        Object target = targetMidlet;
        if (target == null) {
            Log.w(TAG, "searchByValue(String): targetMidlet is null");
            return Collections.emptyList();
        }
        List<FieldEntry> all = scanFields(target, String.class);
        List<FieldEntry> results = new ArrayList<>();
        for (FieldEntry e : all) {
            Object v = e.getValue();
            if (value == null ? v == null : value.equals(v)) {
                results.add(e);
            }
        }
        Log.d(TAG, "searchByValue(String=\"" + value + "\"): " + results.size() + " matches");
        return results;
    }

    // -------------------------------------------------------------------------
    // Public API — narrow search
    // -------------------------------------------------------------------------

    /**
     * Filters a previous scan result list to only those entries whose current
     * value now equals {@code newValue}. Used to progressively narrow down the
     * candidates after additional in-game state changes (classic Cheat Engine
     * "next scan" workflow).
     *
     * @param prev     The list returned by a previous search or narrowSearch call.
     * @param newValue The new int value to filter by.
     * @return A new list containing only entries that currently hold {@code newValue}.
     */
    public List<FieldEntry> narrowSearch(List<FieldEntry> prev, int newValue) {
        if (prev == null || prev.isEmpty()) {
            return Collections.emptyList();
        }
        List<FieldEntry> results = new ArrayList<>();
        for (FieldEntry e : prev) {
            Object v = e.getValue();
            if (v instanceof Integer && (Integer) v == newValue) {
                results.add(e);
            }
        }
        Log.d(TAG, "narrowSearch(newValue=" + newValue + "): " + results.size() + " remaining (was " + prev.size() + ")");
        return results;
    }

    // -------------------------------------------------------------------------
    // Public API — freeze / unfreeze
    // -------------------------------------------------------------------------

    /**
     * Freezes {@code entry} at {@code value}: stores it in the frozen-fields map
     * and starts the background freeze loop (HandlerThread) if it is not running.
     * The loop re-applies all frozen values every 100 ms.
     *
     * @param entry The field entry to freeze.
     * @param value The value to keep writing to the field.
     */
    public void freezeField(FieldEntry entry, Object value) {
        if (entry == null) return;
        String key = entry.frozenKey();
        frozenFields.put(key, new FrozenEntry(entry, value));
        Log.d(TAG, "freezeField: " + entry.path + " = " + value);
        ensureFreezeThreadRunning();
    }

    /**
     * Removes {@code entry} from the frozen-fields map so it is no longer
     * forcibly reset every 100 ms.
     */
    public void unfreezeField(FieldEntry entry) {
        if (entry == null) return;
        String key = entry.frozenKey();
        frozenFields.remove(key);
        Log.d(TAG, "unfreezeField: " + entry.path);
    }

    /**
     * Removes all entries from the frozen-fields map.
     */
    public void unfreezeAll() {
        frozenFields.clear();
        Log.d(TAG, "unfreezeAll");
    }

    /**
     * Stops the background freeze HandlerThread. Any currently frozen fields
     * remain in the map but will no longer be re-applied until a new
     * {@link #freezeField} call restarts the thread.
     */
    public void stopFreezeThread() {
        if (freezeHandler != null) {
            freezeHandler.removeCallbacks(freezeRunnable);
            freezeHandler = null;
        }
        if (freezeThread != null) {
            freezeThread.quit();
            freezeThread = null;
        }
        Log.d(TAG, "stopFreezeThread");
    }

    /**
     * Returns true if a given FieldEntry is currently frozen.
     */
    public boolean isFrozen(FieldEntry entry) {
        if (entry == null) return false;
        return frozenFields.containsKey(entry.frozenKey());
    }

    /**
     * Returns an unmodifiable snapshot of all frozen-field keys.
     * Useful for UI badge display.
     */
    public Set<String> getFrozenKeys() {
        return Collections.unmodifiableSet(frozenFields.keySet());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void ensureFreezeThreadRunning() {
        if (freezeThread == null || !freezeThread.isAlive()) {
            freezeThread = new HandlerThread("MemEditorFreezeLoop");
            freezeThread.start();
            freezeHandler = new Handler(freezeThread.getLooper());
            freezeHandler.postDelayed(freezeRunnable, 100);
            Log.d(TAG, "Freeze thread started");
        }
    }

    private void applyFrozenFields() {
        if (frozenFields.isEmpty()) return;
        Iterator<Map.Entry<String, FrozenEntry>> it = frozenFields.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, FrozenEntry> mapEntry = it.next();
            FrozenEntry fe = mapEntry.getValue();
            try {
                fe.entry.setValue(fe.value);
            } catch (Throwable t) {
                Log.w(TAG, "applyFrozenFields: error on " + fe.entry.path + ": " + t.getMessage());
                // Keep trying on next tick — do not remove
            }
        }
    }
}
