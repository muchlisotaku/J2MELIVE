/*
 * BytecodeHotswap - Dynamic Bytecode Injection & Class Hotswapping
 * for J2ME-Loader-LiveEditor
 *
 * IMPORTANT LIMITATION:
 * DVM/ART does NOT support true class redefinition at runtime (unlike JVM's
 * HotSwap/JVMTI). "Hotswap" here means: a new class definition is loaded
 * from a patch DEX file via a fresh DexClassLoader, and stored in an internal
 * registry. Any NEW object instantiated using the patched class will use the
 * new bytecode. However, EXISTING objects (already instantiated before the
 * hotswap) will continue to use the old class definition for the remainder
 * of their lifecycle. To fully replace behaviour, you must restart the MIDlet
 * (or at least re-create the relevant objects) after applying the patch.
 */

package javax.microedition.util;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dalvik.system.DexClassLoader;

/**
 * BytecodeHotswap provides a simple runtime class-patching mechanism for
 * J2ME-Loader-LiveEditor.
 *
 * <h3>How it works</h3>
 * A patch DEX file (compiled separately, e.g. with {@code dx} or {@code d8})
 * is loaded via a new {@link DexClassLoader}. The desired class is resolved
 * from that loader and stored in an internal registry. Subsequent code that
 * calls {@link #getHotswappedClass(String)} receives the patched
 * {@link Class} object and can instantiate new objects from it via reflection.
 *
 * <h3>ART / DVM limitation</h3>
 * ART does <b>not</b> support true class redefinition (i.e., replacing an
 * already-loaded class in-place, as JVM JVMTI / Instrumentation allows).
 * Hotswap here means:
 * <ul>
 *   <li>The patched class is available for <b>new</b> object instantiation.</li>
 *   <li><b>Existing</b> objects retain their original class and behaviour.</li>
 *   <li>Static fields / initialisers are not re-run for the old class.</li>
 * </ul>
 * To fully replace a running MIDlet's behaviour, restart (destroy + start)
 * the MIDlet after applying the patch.
 *
 * <h3>Usage example</h3>
 * <pre>
 * BytecodeHotswap hs = BytecodeHotswap.getInstance();
 * Class<?> patched = hs.hotswapClass("com.game.MainClass",
 *                                    new File("/sdcard/patch.dex"));
 * Object instance = patched.newInstance();
 * </pre>
 */
public class BytecodeHotswap {

    private static final String TAG = "BytecodeHotswap";

    /** Singleton instance — access via {@link #getInstance()}. */
    private static volatile BytecodeHotswap instance;

    /**
     * Registry of hotswapped classes: className → patched Class object.
     * LinkedHashMap preserves insertion order for {@link #getHotswappedList()}.
     */
    private final Map<String, Class<?>> hotswappedClasses =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private BytecodeHotswap() {}

    /**
     * Returns the singleton instance, creating it on first call (thread-safe).
     *
     * @return the singleton {@link BytecodeHotswap} instance
     */
    public static BytecodeHotswap getInstance() {
        if (instance == null) {
            synchronized (BytecodeHotswap.class) {
                if (instance == null) {
                    instance = new BytecodeHotswap();
                }
            }
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Core API
    // -------------------------------------------------------------------------

    /**
     * Creates a new {@link DexClassLoader} that can load classes from the
     * supplied DEX file.
     *
     * <p>The optimised DEX output will be placed in {@code optimizedDir}. If
     * that directory does not exist, this method attempts to create it.
     * If {@code optimizedDir} is {@code null} or empty, the application's
     * code-cache directory is used as a fallback.</p>
     *
     * @param dexFile      the DEX / APK / JAR file containing the patch bytecode;
     *                     must not be {@code null} and must exist
     * @param optimizedDir directory for optimised (ODEX) output; may be
     *                     {@code null} to use the default cache location
     * @return a {@link DexClassLoader} ready to load classes from {@code dexFile}
     * @throws IllegalArgumentException if {@code dexFile} is null or does not exist
     */
    public DexClassLoader loadDexPatch(File dexFile, String optimizedDir) {
        if (dexFile == null) {
            throw new IllegalArgumentException("dexFile must not be null");
        }
        if (!dexFile.exists()) {
            throw new IllegalArgumentException("dexFile does not exist: " + dexFile.getAbsolutePath());
        }

        // Resolve / create the optimised output directory
        String optDir = resolveOptDir(optimizedDir);

        Log.d(TAG, "loadDexPatch: dex=" + dexFile.getAbsolutePath()
                + " optDir=" + optDir);

        // Use the system class loader as parent so that Android framework
        // classes (and the MIDlet framework itself) remain accessible from
        // patch classes.
        ClassLoader parent = BytecodeHotswap.class.getClassLoader();

        return new DexClassLoader(
                dexFile.getAbsolutePath(),
                optDir,
                null,          // no native library path needed
                parent
        );
    }

    /**
     * Loads the named class from the supplied patch DEX file and registers it
     * as the hotswapped version of {@code className}.
     *
     * <p><b>ART / DVM note:</b> The class is loaded from a fresh
     * {@link DexClassLoader}. Any <em>new</em> objects created from the
     * returned {@link Class} will use the patched bytecode. Existing objects
     * are unaffected.</p>
     *
     * @param className    fully-qualified class name, e.g.
     *                     {@code "com.game.MainClass"}
     * @param patchDexFile the DEX file that contains the patched version of
     *                     the class; must exist
     * @return the patched {@link Class} object
     * @throws ClassNotFoundException   if the class is not found in the DEX file
     * @throws IllegalArgumentException if either argument is invalid
     */
    public Class<?> hotswapClass(String className, File patchDexFile)
            throws ClassNotFoundException {

        if (className == null || className.trim().isEmpty()) {
            throw new IllegalArgumentException("className must not be null or empty");
        }
        if (patchDexFile == null || !patchDexFile.exists()) {
            throw new IllegalArgumentException(
                    "patchDexFile is null or does not exist: " + patchDexFile);
        }

        Log.i(TAG, "hotswapClass: loading '" + className
                + "' from " + patchDexFile.getAbsolutePath());

        // Build an optimised-DEX output directory alongside the patch file
        String optDir = resolveOptDir(patchDexFile.getParent() + "/opt");

        // Create a fresh DexClassLoader for the patch DEX
        DexClassLoader patchLoader = loadDexPatch(patchDexFile, optDir);

        // Load the class — throws ClassNotFoundException if absent
        Class<?> patchedClass = patchLoader.loadClass(className);

        // Register the patched class
        hotswappedClasses.put(className, patchedClass);

        Log.i(TAG, "hotswapClass: successfully hotswapped '" + className + "'");
        return patchedClass;
    }

    /**
     * Returns the hotswapped (patched) {@link Class} for {@code className}, or
     * {@code null} if no hotswap has been applied for that class.
     *
     * @param className fully-qualified class name
     * @return the patched {@link Class}, or {@code null}
     */
    public Class<?> getHotswappedClass(String className) {
        return hotswappedClasses.get(className);
    }

    /**
     * Returns {@code true} if a hotswap patch has been applied for the named class.
     *
     * @param className fully-qualified class name
     * @return {@code true} if class is currently hotswapped
     */
    public boolean isHotswapped(String className) {
        return hotswappedClasses.containsKey(className);
    }

    /**
     * Removes the hotswap registration for the named class. This does
     * <em>not</em> affect already-created instances; it merely removes the
     * entry from the registry so that subsequent calls to
     * {@link #getHotswappedClass(String)} return {@code null}.
     *
     * @param className fully-qualified class name to un-register
     */
    public void clearHotswap(String className) {
        if (hotswappedClasses.remove(className) != null) {
            Log.d(TAG, "clearHotswap: removed '" + className + "'");
        } else {
            Log.d(TAG, "clearHotswap: no entry for '" + className + "'");
        }
    }

    /**
     * Removes all hotswap registrations.
     *
     * <p>As with {@link #clearHotswap(String)}, this does not affect
     * already-created object instances.</p>
     */
    public void clearAll() {
        int count = hotswappedClasses.size();
        hotswappedClasses.clear();
        Log.i(TAG, "clearAll: removed " + count + " hotswapped class(es)");
    }

    /**
     * Returns a snapshot list of fully-qualified class names that are
     * currently registered as hotswapped.
     *
     * @return a new {@link List} of class names (may be empty, never null)
     */
    public List<String> getHotswappedList() {
        synchronized (hotswappedClasses) {
            return new ArrayList<>(hotswappedClasses.keySet());
        }
    }

    /**
     * Returns a human-readable status string describing the current state of
     * hotswapped classes.
     *
     * @return status message, never {@code null}
     */
    public String getStatusMessage() {
        List<String> list = getHotswappedList();
        if (list.isEmpty()) {
            return "BytecodeHotswap: no classes currently hotswapped.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("BytecodeHotswap: ")
          .append(list.size())
          .append(" class(es) hotswapped:\n");
        for (String name : list) {
            Class<?> cls = hotswappedClasses.get(name);
            sb.append("  • ").append(name);
            if (cls != null) {
                sb.append(" [").append(cls.getClassLoader().getClass().getSimpleName()).append("]");
            }
            sb.append('\n');
        }
        sb.append("NOTE: Only new object instances use patched bytecode. "
                + "Existing objects are unchanged.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves or creates the optimised-DEX output directory.
     *
     * @param preferred the preferred directory path; may be null/empty
     * @return an absolute path to a writable directory
     */
    private String resolveOptDir(String preferred) {
        if (preferred != null && !preferred.trim().isEmpty()) {
            File dir = new File(preferred.trim());
            if (dir.isDirectory() || dir.mkdirs()) {
                return dir.getAbsolutePath();
            }
            Log.w(TAG, "resolveOptDir: could not create '" + preferred
                    + "', falling back to app cache dir");
        }
        // Fallback: application's code-cache directory (always writable)
        try {
            android.content.Context ctx = ContextHolder.getAppContext();
            if (ctx != null) {
                File codeCache = ctx.getCodeCacheDir();
                if (codeCache != null) {
                    return codeCache.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveOptDir: could not get codeCacheDir", t);
        }
        // Last resort: system temp directory
        return System.getProperty("java.io.tmpdir", "/data/local/tmp");
    }
}
