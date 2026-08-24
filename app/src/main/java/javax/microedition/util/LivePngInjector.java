/*
 * LivePngInjector.java
 * Real-Time Asset & Graphic Injector for J2ME-Loader-LiveEditor
 *
 * Allows overriding game PNG/asset resources at runtime without modifying the JAR.
 * Works by intercepting AppClassLoader.getResourceBytes() calls.
 */

package javax.microedition.util;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that holds in-memory overrides for game resources.
 *
 * Usage:
 *   LivePngInjector injector = LivePngInjector.getInstance();
 *   injector.injectPngFromFile("/sprites/player.png", new File("/sdcard/my_player.png"));
 *
 * AppClassLoader.getResourceBytes() will check this map before loading from JAR/disk.
 */
public class LivePngInjector {

    private static final String TAG = "LivePngInjector";

    // ------------------------------------------------------------------ //
    //  Singleton                                                           //
    // ------------------------------------------------------------------ //

    private static volatile LivePngInjector sInstance;

    private LivePngInjector() {
        // private constructor — use getInstance()
    }

    /**
     * Returns the application-wide singleton instance (thread-safe, lazy init).
     */
    public static LivePngInjector getInstance() {
        if (sInstance == null) {
            synchronized (LivePngInjector.class) {
                if (sInstance == null) {
                    sInstance = new LivePngInjector();
                }
            }
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ //
    //  Storage                                                             //
    // ------------------------------------------------------------------ //

    /**
     * Map from normalized resource path → raw PNG/asset bytes.
     * ConcurrentHashMap ensures thread-safe reads and writes.
     */
    private final ConcurrentHashMap<String, byte[]> injectedResources = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ //
    //  Public API                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Inject (or replace) a resource override with raw bytes.
     *
     * @param resourcePath path as used by the game (e.g. "/sprites/hero.png"
     *                     or "sprites/hero.png" — both are normalized)
     * @param pngBytes     raw bytes of the replacement asset (PNG or any format
     *                     the game reads)
     */
    public void injectPng(String resourcePath, byte[] pngBytes) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            Log.w(TAG, "injectPng: resourcePath is null or empty, ignoring");
            return;
        }
        if (pngBytes == null || pngBytes.length == 0) {
            Log.w(TAG, "injectPng: pngBytes is null or empty for path: " + resourcePath);
            return;
        }
        String normalized = normalizeResourcePath(resourcePath);
        injectedResources.put(normalized, pngBytes);
        Log.d(TAG, "injectPng: injected " + pngBytes.length + " bytes for path: " + normalized);
    }

    /**
     * Read a file from disk and inject its contents as a resource override.
     *
     * @param resourcePath path as used by the game
     * @param sourceFile   file to read bytes from
     * @throws IOException if the file cannot be read
     */
    public void injectPngFromFile(String resourcePath, File sourceFile) throws IOException {
        if (resourcePath == null || resourcePath.isEmpty()) {
            throw new IllegalArgumentException("resourcePath must not be null or empty");
        }
        if (sourceFile == null) {
            throw new IllegalArgumentException("sourceFile must not be null");
        }
        if (!sourceFile.exists()) {
            throw new IOException("Source file does not exist: " + sourceFile.getAbsolutePath());
        }
        if (!sourceFile.isFile()) {
            throw new IOException("Source path is not a file: " + sourceFile.getAbsolutePath());
        }

        long fileSize = sourceFile.length();
        if (fileSize == 0) {
            throw new IOException("Source file is empty: " + sourceFile.getAbsolutePath());
        }
        if (fileSize > 50 * 1024 * 1024) { // 50 MB safety limit
            throw new IOException("Source file is too large (> 50 MB): " + sourceFile.getAbsolutePath());
        }

        byte[] bytes = new byte[(int) fileSize];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(sourceFile);
            int totalRead = 0;
            while (totalRead < bytes.length) {
                int read = fis.read(bytes, totalRead, bytes.length - totalRead);
                if (read == -1) {
                    throw new IOException("Unexpected end of file: " + sourceFile.getAbsolutePath());
                }
                totalRead += read;
            }
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                    Log.e(TAG, "injectPngFromFile: error closing stream", e);
                }
            }
        }

        injectPng(resourcePath, bytes);
        Log.d(TAG, "injectPngFromFile: loaded " + bytes.length + " bytes from " + sourceFile.getAbsolutePath());
    }

    /**
     * Remove the injection override for a specific resource path.
     * After calling this, the game will load the original asset again.
     *
     * @param resourcePath path to remove (normalized automatically)
     */
    public void removeInjection(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            Log.w(TAG, "removeInjection: path is null or empty, ignoring");
            return;
        }
        String normalized = normalizeResourcePath(resourcePath);
        byte[] removed = injectedResources.remove(normalized);
        if (removed != null) {
            Log.d(TAG, "removeInjection: removed override for: " + normalized);
        } else {
            Log.w(TAG, "removeInjection: no injection found for: " + normalized);
        }
    }

    /**
     * Clear ALL injected resource overrides.
     */
    public void clearAll() {
        int count = injectedResources.size();
        injectedResources.clear();
        Log.d(TAG, "clearAll: cleared " + count + " injected resources");
    }

    /**
     * Check whether a resource path currently has an active override.
     *
     * @param path resource path (normalized automatically)
     * @return true if an override exists for this path
     */
    public boolean isInjected(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        return injectedResources.containsKey(normalizeResourcePath(path));
    }

    /**
     * Retrieve the injected bytes for a resource path.
     * Called by AppClassLoader.getResourceBytes() as the first check.
     *
     * @param path resource path (normalized automatically)
     * @return injected byte array, or null if no override exists
     */
    public byte[] getInjectedBytes(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        return injectedResources.get(normalizeResourcePath(path));
    }

    /**
     * Return an unmodifiable view of all currently injected resource paths.
     * Paths are in normalized form (no leading slash, forward slashes).
     *
     * @return set of normalized resource paths
     */
    public Set<String> getInjectedPaths() {
        return Collections.unmodifiableSet(injectedResources.keySet());
    }

    /**
     * Return the number of currently active injections.
     */
    public int getInjectionCount() {
        return injectedResources.size();
    }

    // ------------------------------------------------------------------ //
    //  Path normalization                                                  //
    // ------------------------------------------------------------------ //

    /**
     * Normalize a resource path to the canonical form used as map key:
     * <ul>
     *   <li>Replace backslashes with forward slashes</li>
     *   <li>Collapse consecutive slashes (// → /)</li>
     *   <li>Remove leading slash</li>
     *   <li>Trim whitespace</li>
     * </ul>
     *
     * @param path raw path string
     * @return normalized path, never null
     */
    public String normalizeResourcePath(String path) {
        if (path == null) {
            return "";
        }
        // Trim whitespace
        String normalized = path.trim();
        // Replace backslashes (Siemens/Windows paths)
        normalized = normalized.replace('\\', '/');
        // Collapse consecutive slashes
        normalized = normalized.replaceAll("//+", "/");
        // Remove leading slash
        if (!normalized.isEmpty() && normalized.charAt(0) == '/') {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
