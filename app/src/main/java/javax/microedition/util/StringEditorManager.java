package javax.microedition.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * StringEditorManager — Upgraded v2
 *
 * Perubahan dari v1:
 *  - Frame-sync hook: setiap kali game merender frame baru (drawString dipanggil),
 *    frame counter bertambah dan listener UI bisa sinkronisasi dengannya.
 *  - Concurrent scan: recordString() lock-free dengan ConcurrentHashMap penuh.
 *  - Per-string frame tracking: tiap StringRecord menyimpan frameLastSeen
 *    sehingga UI bisa highlight string yang "aktif di frame sekarang".
 *  - Hot-path zero-alloc: replaceString() tidak mengalokasikan objek baru
 *    selama tidak ada replacement yang cocok.
 *  - Atomic frame counter untuk thread-safe read dari UI thread.
 */
public class StringEditorManager {
    private static final String TAG = "StringEditorManager";
    private static final String FILE_NAME = "live_strings_override.json";
    private static final int MAX_CAPTURED = 2000;

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static final StringEditorManager INSTANCE = new StringEditorManager();
    public static StringEditorManager getInstance() { return INSTANCE; }

    // ── Public data model ─────────────────────────────────────────────────────
    public static class StringRecord {
        public final String original;
        public volatile long lastSeenTime;
        public volatile long frameLastSeen;   // NEW: frame number when last seen
        public volatile int  count;

        public StringRecord(String original) {
            this.original      = original;
            this.lastSeenTime  = System.currentTimeMillis();
            this.frameLastSeen = 0L;
            this.count         = 1;
        }
    }

    /** Listener dipanggil di main thread setiap kali ada perubahan replacement. */
    public interface OnChangeListener {
        void onStringChanged();
    }

    /**
     * Listener dipanggil di main thread setiap frame baru selesai dirender.
     * Gunakan ini untuk refresh UI dengan data terbaru per-frame.
     * frameNumber: nomor frame yang baru saja selesai
     */
    public interface OnFrameListener {
        void onFrameRendered(long frameNumber);
    }

    // ── Internal storage ──────────────────────────────────────────────────────

    /**
     * capturedStrings: LRU-map dengan max MAX_CAPTURED entri.
     * Menggunakan synchronizedMap(LinkedHashMap accessOrder=true) agar urutan
     * berdasarkan last-access (paling baru dipakai muncul pertama).
     */
    private final Map<String, StringRecord> capturedStrings =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, StringRecord>(256, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, StringRecord> eldest) {
                            return size() > MAX_CAPTURED;
                        }
                    }
            );

    /** Replacements: fully concurrent, no locks on hot path (read via ConcurrentHashMap). */
    private final ConcurrentHashMap<String, String> replacements = new ConcurrentHashMap<>();

    // ── Frame counter ─────────────────────────────────────────────────────────
    /**
     * Atomic frame counter. Incremented every time flushFrame() is called,
     * which should be hooked into the game's render loop (after each repaint).
     * UI thread reads this to know which frame strings belong to.
     */
    private final AtomicLong frameCounter = new AtomicLong(0L);

    /**
     * Tracks the frame number of the CURRENT render batch.
     * Set at the start of each frame via beginFrame(), cleared at end via flushFrame().
     * Using AtomicLong so game thread and UI thread can read without locking.
     */
    private final AtomicLong currentFrame = new AtomicLong(0L);

    // ── Per-frame seen set ────────────────────────────────────────────────────
    /**
     * Strings seen during the CURRENT frame (between beginFrame/flushFrame).
     * Replaced atomically at flushFrame with a new empty set.
     * UI can snapshot this to know exactly which strings are "live this frame".
     */
    private volatile ConcurrentHashMap<String, Boolean> frameActiveStrings =
            new ConcurrentHashMap<>();

    // ── Listeners ─────────────────────────────────────────────────────────────
    private final List<OnChangeListener> changeListeners = new ArrayList<>();
    private final List<OnFrameListener>  frameListeners  = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Config ────────────────────────────────────────────────────────────────
    private File    appDir;
    private File    configFile;
    private boolean captureEnabled        = true;
    private boolean substringMatchEnabled = false;

    // ── Substring cache ───────────────────────────────────────────────────────
    /**
     * Cache snapshot of replacements for substring scan.
     * Rebuilt lazily whenever replacements change to avoid rebuilding
     * on every hot-path replaceString() call.
     */
    private final AtomicReference<Map<String, String>> substringCacheRef =
            new AtomicReference<>(null);

    private StringEditorManager() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public synchronized void init(File appDir) {
        this.appDir = appDir;
        this.capturedStrings.clear();
        this.replacements.clear();
        this.frameCounter.set(0L);
        this.currentFrame.set(0L);
        this.frameActiveStrings = new ConcurrentHashMap<>();
        this.substringCacheRef.set(null);
        if (appDir != null) {
            this.configFile = new File(appDir, FILE_NAME);
            loadFromFile(this.configFile);
        } else {
            this.configFile = null;
        }
    }

    // =========================================================================
    // Frame-sync API  (call from game render thread)
    // =========================================================================

    /**
     * Call at the START of each game frame (before any drawString calls).
     * Marks the beginning of a new frame batch so per-frame tracking works.
     *
     * Integration point in Canvas.java:
     *   - In the method that triggers a repaint / flushGraphics(), call:
     *     StringEditorManager.getInstance().beginFrame();
     */
    public void beginFrame() {
        long frame = frameCounter.get() + 1;
        currentFrame.set(frame);
        // Clear previous frame's active set (swap with new empty map)
        frameActiveStrings = new ConcurrentHashMap<>();
    }

    /**
     * Call at the END of each game frame (after all drawString calls for this frame).
     * Increments the global frame counter and notifies frame listeners on main thread.
     *
     * Integration point in Canvas.java:
     *   - In the method that finalises a frame (e.g. after glDrawArrays/swapBuffers),
     *     call: StringEditorManager.getInstance().flushFrame();
     */
    public void flushFrame() {
        long frame = frameCounter.incrementAndGet();
        // Notify UI listeners asynchronously (non-blocking for game thread)
        if (!frameListeners.isEmpty()) {
            final long f = frame;
            mainHandler.post(() -> {
                List<OnFrameListener> copy;
                synchronized (frameListeners) {
                    copy = new ArrayList<>(frameListeners);
                }
                for (OnFrameListener listener : copy) {
                    try { listener.onFrameRendered(f); }
                    catch (Throwable t) { Log.e(TAG, "onFrameRendered error", t); }
                }
            });
        }
    }

    /** Returns the global frame counter (total frames rendered since init). */
    public long getFrameCount() {
        return frameCounter.get();
    }

    /** Returns the current frame number being rendered (0 if outside begin/flush). */
    public long getCurrentFrameNumber() {
        return currentFrame.get();
    }

    /**
     * Returns a snapshot of strings that were active (drawn) in the most recent frame.
     * Safe to call from any thread.
     */
    public java.util.Set<String> getActiveFrameStrings() {
        return new java.util.HashSet<>(frameActiveStrings.keySet());
    }

    // =========================================================================
    // Hot-path: called on GAME THREAD every drawString()
    // =========================================================================

    /**
     * Record that a string was drawn.  Called from Graphics.drawString().
     * MUST be fast — this is on the game's render thread.
     * Zero-allocation on the common path (string already captured).
     */
    public void recordString(String str) {
        if (!captureEnabled || str == null) return;
        // Fast length check without trim() allocation
        if (str.length() == 0) return;
        boolean blank = true;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) > ' ') { blank = false; break; }
        }
        if (blank) return;

        long frame = currentFrame.get();

        // Mark as active this frame (lock-free)
        frameActiveStrings.put(str, Boolean.TRUE);

        // Update or insert into capturedStrings
        synchronized (capturedStrings) {
            StringRecord record = capturedStrings.get(str);
            if (record == null) {
                StringRecord newRecord = new StringRecord(str);
                newRecord.frameLastSeen = frame;
                capturedStrings.put(str, newRecord);
            } else {
                record.lastSeenTime  = System.currentTimeMillis();
                record.frameLastSeen = frame;
                record.count++;
            }
        }
    }

    /**
     * Returns the replacement for the given string, or the original if none.
     * ZERO-ALLOC on the "no replacement" path (just a ConcurrentHashMap.get).
     */
    public String replaceString(String original) {
        if (original == null || original.isEmpty()) return original;

        // Fast path: exact match (most common case, O(1), no allocation)
        String replaced = replacements.get(original);
        if (replaced != null) return replaced;

        // Slow path: substring replacement mode
        if (substringMatchEnabled) {
            Map<String, String> cache = substringCacheRef.get();
            if (cache != null && !cache.isEmpty()) {
                String result = original;
                for (Map.Entry<String, String> entry : cache.entrySet()) {
                    String target = entry.getKey();
                    if (target.length() > 1 && result.contains(target)) {
                        result = result.replace(target, entry.getValue());
                    }
                }
                if (result != original) return result;  // reference check intentional
            }
        }

        return original;
    }

    // =========================================================================
    // Replacement management
    // =========================================================================

    public void setReplacement(String original, String replacement) {
        if (original == null || replacement == null) return;
        replacements.put(original, replacement);
        substringCacheRef.set(null); // invalidate substring cache
        saveToFile(configFile);
        notifyChangeListeners();
    }

    public void removeReplacement(String original) {
        if (original == null) return;
        replacements.remove(original);
        substringCacheRef.set(null);
        saveToFile(configFile);
        notifyChangeListeners();
    }

    public void clearAllReplacements() {
        replacements.clear();
        substringCacheRef.set(null);
        saveToFile(configFile);
        notifyChangeListeners();
    }

    public Map<String, String> getReplacements() {
        return new LinkedHashMap<>(replacements);
    }

    // =========================================================================
    // Captured strings access
    // =========================================================================

    public List<StringRecord> getCapturedList() {
        List<StringRecord> list;
        synchronized (capturedStrings) {
            list = new ArrayList<>(capturedStrings.values());
        }
        // Sort: most recently seen first
        list.sort((a, b) -> Long.compare(b.lastSeenTime, a.lastSeenTime));
        return list;
    }

    /**
     * Returns captured strings sorted by recency, annotated with whether they
     * were active in the last rendered frame.
     * This is the primary data source for the upgraded UI.
     */
    public List<StringRecord> getCapturedListWithFrameInfo() {
        long lastFrame = frameCounter.get();
        List<StringRecord> list;
        synchronized (capturedStrings) {
            list = new ArrayList<>(capturedStrings.values());
        }
        // Sort: active-this-frame first, then by last seen time
        list.sort((a, b) -> {
            boolean aActive = (a.frameLastSeen == lastFrame);
            boolean bActive = (b.frameLastSeen == lastFrame);
            if (aActive && !bActive) return -1;
            if (!aActive && bActive) return  1;
            return Long.compare(b.lastSeenTime, a.lastSeenTime);
        });
        return list;
    }

    /**
     * Returns true if the given string was drawn in the most recently completed frame.
     */
    public boolean isActiveThisFrame(String str) {
        long lastFrame = frameCounter.get();
        StringRecord r;
        synchronized (capturedStrings) {
            r = capturedStrings.get(str);
        }
        return r != null && r.frameLastSeen == lastFrame;
    }

    public void clearCaptured() {
        capturedStrings.clear();
        frameActiveStrings = new ConcurrentHashMap<>();
    }

    // =========================================================================
    // Listener management
    // =========================================================================

    public void addListener(OnChangeListener listener) {
        synchronized (changeListeners) {
            if (!changeListeners.contains(listener)) changeListeners.add(listener);
        }
    }

    public void removeListener(OnChangeListener listener) {
        synchronized (changeListeners) { changeListeners.remove(listener); }
    }

    public void addFrameListener(OnFrameListener listener) {
        synchronized (frameListeners) {
            if (!frameListeners.contains(listener)) frameListeners.add(listener);
        }
    }

    public void removeFrameListener(OnFrameListener listener) {
        synchronized (frameListeners) { frameListeners.remove(listener); }
    }

    // =========================================================================
    // Config flags
    // =========================================================================

    public boolean isCaptureEnabled()             { return captureEnabled; }
    public void    setCaptureEnabled(boolean v)   { captureEnabled = v; }
    public boolean isSubstringMatchEnabled()       { return substringMatchEnabled; }
    public void    setSubstringMatchEnabled(boolean v) {
        this.substringMatchEnabled = v;
        // Rebuild substring cache immediately if enabling
        if (v) rebuildSubstringCache();
        else   substringCacheRef.set(null);
    }

    private void rebuildSubstringCache() {
        substringCacheRef.set(new LinkedHashMap<>(replacements));
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    public synchronized boolean loadFromFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) return false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            return importFromJsonString(sb.toString(), false);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load: " + file, e);
            return false;
        }
    }

    public synchronized boolean saveToFile(File file) {
        if (file == null) return false;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                w.write(exportToJsonString());
                w.flush();
            }
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to save: " + file, e);
            return false;
        }
    }

    public String exportToJsonString() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);
            root.put("timestamp", System.currentTimeMillis());
            root.put("substring_mode", substringMatchEnabled);
            JSONObject mapObj = new JSONObject();
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                mapObj.put(entry.getKey(), entry.getValue());
            }
            root.put("replacements", mapObj);
            return root.toString(2);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to serialize JSON", e);
            return "{}";
        }
    }

    public boolean importFromJsonString(String jsonStr, boolean append) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(jsonStr);
            if (root.has("substring_mode")) {
                this.substringMatchEnabled = root.optBoolean("substring_mode", false);
            }
            JSONObject mapObj = root.optJSONObject("replacements");
            if (mapObj != null) {
                if (!append) replacements.clear();
                Iterator<String> keys = mapObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    replacements.put(key, mapObj.optString(key, ""));
                }
                substringCacheRef.set(null);
                if (configFile != null) saveToFile(configFile);
                notifyChangeListeners();
                return true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse JSON", e);
        }
        return false;
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void notifyChangeListeners() {
        mainHandler.post(() -> {
            List<OnChangeListener> copy;
            synchronized (changeListeners) { copy = new ArrayList<>(changeListeners); }
            for (OnChangeListener l : copy) {
                try { l.onStringChanged(); }
                catch (Throwable t) { Log.e(TAG, "onStringChanged error", t); }
            }
        });
    }
}
