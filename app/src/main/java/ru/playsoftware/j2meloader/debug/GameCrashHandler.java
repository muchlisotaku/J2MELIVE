/*
 * Copyright 2024 J2ME-Loader LiveEditor Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.debug;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.microedition.util.ContextHolder;

/**
 * Crash handler yang mencegat uncaught exception dari thread game J2ME
 * agar emulator tidak langsung force-close secara kasar.
 *
 * <h3>Alur kerja saat game crash:</h3>
 * <pre>
 * Game Thread (MidletMain)
 *   └─► uncaught exception (NullPointerException, dll.)
 *               │
 *               ▼
 *   GameCrashHandler.uncaughtException()
 *               │
 *     ┌─────────┼─────────────────────┐
 *     ▼         ▼                     ▼
 * GameLogger  Auto-save log        Dialog Android
 * .error()    (LogExporter)        ┌─ [View Log] → Share log
 *                                  └─ [Close Game] → cleanup
 * </pre>
 *
 * <h3>Cara integrasi:</h3>
 * <pre>
 * // Di MicroActivity.onCreate() – sebelum MIDlet diload:
 * GameCrashHandler.install();
 *
 * // Di MicroActivity.onDestroy():
 * GameCrashHandler.uninstall();
 * </pre>
 *
 * <p>Handler ini menggunakan pola <em>chaining</em>: jika exception bukan dari
 * thread game, handler asli (ACRA/system) tetap dipanggil untuk menangani crash.</p>
 */
public class GameCrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "GameCrashHandler";

    /** Nama-nama thread game yang diidentifikasi sebagai "game thread". */
    private static final String[] GAME_THREAD_NAMES = {
            "MidletMain", "GameCanvas", "Midlet", "J2ME-"
    };

    private final Thread.UncaughtExceptionHandler previousHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Flag untuk menghindari recursive handling jika crash handler
     * sendiri melempar exception.
     */
    private volatile boolean handlingCrash = false;

    // Singleton instance yang sedang ter-install
    private static volatile GameCrashHandler installed;

    private GameCrashHandler(Thread.UncaughtExceptionHandler previous) {
        this.previousHandler = previous;
    }

    // ------------------------------------------------------------------ //
    //  Install / Uninstall
    // ------------------------------------------------------------------ //

    /**
     * Install {@code GameCrashHandler} sebagai default uncaught exception handler.
     * Handler sebelumnya disimpan dan akan dipanggil sebagai fallback untuk
     * thread non-game.
     *
     * <p>Aman dipanggil berkali-kali; jika sudah ter-install, panggilan berikutnya
     * diabaikan.</p>
     */
    public static synchronized void install() {
        if (installed != null) return;

        Thread.UncaughtExceptionHandler current =
                Thread.getDefaultUncaughtExceptionHandler();
        installed = new GameCrashHandler(current);
        Thread.setDefaultUncaughtExceptionHandler(installed);

        GameLogger.getInstance().info(TAG, "GameCrashHandler installed (chaining: "
                + (current != null ? current.getClass().getSimpleName() : "none") + ")");
    }

    /**
     * Uninstall handler dan kembalikan handler sebelumnya.
     * Aman dipanggil meskipun belum ter-install.
     */
    public static synchronized void uninstall() {
        if (installed == null) return;

        Thread.setDefaultUncaughtExceptionHandler(installed.previousHandler);
        GameLogger.getInstance().info(TAG, "GameCrashHandler uninstalled");
        installed = null;
    }

    /** Mengembalikan true jika handler sedang ter-install. */
    public static boolean isInstalled() {
        return installed != null;
    }

    // ------------------------------------------------------------------ //
    //  UncaughtExceptionHandler Implementation
    // ------------------------------------------------------------------ //

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        // Hindari recursive crash handling
        if (handlingCrash) {
            Log.e(TAG, "Recursive crash detected, delegating to previous handler");
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, ex);
            }
            return;
        }
        handlingCrash = true;

        final String threadName  = thread.getName();
        final String errorType   = ex.getClass().getName();
        final String errorMsg    = ex.getMessage();
        final boolean isGameThread = isGameThread(threadName);

        // --- 1. Catat ke GameLogger ---
        GameLogger.getInstance().error(
                TAG,
                "CRASH in [" + threadName + "] " + errorType
                        + (errorMsg != null ? ": " + errorMsg : ""),
                ex
        );

        Log.e(TAG, "Uncaught exception in thread: " + threadName, ex);

        // --- 2. Auto-save log ke file (background) ---
        Context appContext = ContextHolder.getAppContext();
        if (appContext != null) {
            LogExporter.exportToFile(
                    appContext,
                    "[CRASH] " + threadName,
                    new LogExporter.ExportCallback() {
                        @Override
                        public void onSuccess(File logFile) {
                            Log.i(TAG, "Crash log auto-saved: " + logFile.getAbsolutePath());
                        }
                        @Override
                        public void onFailure(Exception error) {
                            Log.e(TAG, "Failed to auto-save crash log", error);
                        }
                    });
        }

        // --- 3. Tampilkan dialog atau delegate ---
        if (isGameThread && appContext != null) {
            final String shortStack = getShortStackTrace(ex, 10);
            final Context ctx = appContext;

            // Dialog harus dijalankan di main thread
            mainHandler.post(() -> {
                try {
                    showCrashDialog(ctx, errorType, errorMsg, shortStack);
                } catch (Exception dialogEx) {
                    Log.e(TAG, "Failed to show crash dialog", dialogEx);
                    // Fallback ke previous handler
                    delegateToPrevious(thread, ex);
                }
            });
        } else {
            // Bukan thread game: delegate ke handler asli (ACRA, system handler)
            delegateToPrevious(thread, ex);
        }
    }

    private void delegateToPrevious(Thread thread, Throwable ex) {
        if (previousHandler != null) {
            previousHandler.uncaughtException(thread, ex);
        }
    }

    // ------------------------------------------------------------------ //
    //  Crash Dialog
    // ------------------------------------------------------------------ //

    private void showCrashDialog(Context context, String errorType,
                                  String errorMsg, String shortStack) {
        new AlertDialog.Builder(context)
                .setTitle("⚠ Game Crash Detected")
                .setMessage(
                        "Error Type:\n" + errorType + "\n\n" +
                        "Message:\n" + (errorMsg != null ? errorMsg : "(no message)") + "\n\n" +
                        "Stack Trace (partial):\n" + shortStack + "\n" +
                        "Log has been auto-saved to cache."
                )
                .setPositiveButton("📤 View & Share Log", (dialog, which) -> {
                    // Ekspor dan share log agar pengguna bisa kirim ke developer
                    Context appCtx = ContextHolder.getAppContext();
                    if (appCtx != null) {
                        LogExporter.exportAndShare(
                                appCtx,
                                "CrashReport",
                                new LogExporter.ExportCallback() {
                                    @Override public void onSuccess(File f) {}
                                    @Override public void onFailure(Exception e) {
                                        Log.e(TAG, "Export failed from dialog", e);
                                    }
                                });
                    }
                })
                .setNegativeButton("❌ Close Game", (dialog, which) -> {
                    // Tutup game secara bersih
                    javax.microedition.shell.MidletThread.notifyDestroyed();
                })
                .setNeutralButton("⏭ Continue", (dialog, which) -> {
                    // Coba lanjutkan (mungkin tidak semua crash bisa dilanjutkan)
                    handlingCrash = false;
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------ //
    //  Static Utility – untuk try-catch manual di game loop
    // ------------------------------------------------------------------ //

    /**
     * Wrapper static untuk menangkap exception secara manual di try-catch,
     * tanpa mengandalkan uncaught handler.
     *
     * <p>Cocok digunakan di dalam game loop atau metode kritis:</p>
     * <pre>
     * try {
     *     midlet.startApp();
     * } catch (Throwable t) {
     *     GameCrashHandler.handleGameException("startApp", t);
     * }
     * </pre>
     *
     * @param context nama konteks/operasi saat error (untuk tag log)
     * @param t       exception yang terjadi
     */
    public static void handleGameException(String context, Throwable t) {
        GameLogger.getInstance().error(
                TAG,
                "[" + context + "] " + t.getClass().getSimpleName()
                        + ": " + t.getMessage(),
                t
        );
    }

    /**
     * Versi handleGameException dengan pesan custom tambahan.
     */
    public static void handleGameException(String context, String extraInfo, Throwable t) {
        GameLogger.getInstance().error(
                TAG,
                "[" + context + "] " + extraInfo + " | "
                        + t.getClass().getSimpleName() + ": " + t.getMessage(),
                t
        );
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /**
     * Tentukan apakah sebuah thread adalah "thread game" berdasarkan namanya.
     * Thread game adalah thread yang menjalankan kode MIDlet langsung.
     */
    private static boolean isGameThread(String threadName) {
        if (threadName == null) return false;
        for (String name : GAME_THREAD_NAMES) {
            if (threadName.contains(name)) return true;
        }
        return false;
    }

    /**
     * Format stack trace menjadi string dengan jumlah baris dibatasi.
     *
     * @param t        throwable yang akan diformat
     * @param maxLines jumlah baris maksimum
     * @return string stack trace yang sudah dipotong
     */
    private static String getShortStackTrace(Throwable t, int maxLines) {
        StringWriter sw = new StringWriter(512);
        t.printStackTrace(new PrintWriter(sw, true));
        String[] lines = sw.toString().split("\n");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxLines, lines.length);
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i]).append("\n");
        }
        if (lines.length > maxLines) {
            sb.append("  ... (").append(lines.length - maxLines).append(" more lines)");
        }
        return sb.toString();
    }
}
