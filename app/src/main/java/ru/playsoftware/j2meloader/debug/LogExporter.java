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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Utility class untuk mengekspor log ke file .txt dan berbagi file tersebut
 * menggunakan Android Share Intent.
 *
 * <h3>Desain keputusan:</h3>
 * <ul>
 *   <li>File log disimpan di {@code Context.getCacheDir()/debug_logs/} sehingga
 *       <b>tidak memerlukan permission WRITE_EXTERNAL_STORAGE</b> di Android 10+.</li>
 *   <li>Operasi I/O dijalankan di background thread untuk menghindari ANR.</li>
 *   <li>FileProvider digunakan untuk Android 7+ agar file dapat dibagikan
 *       ke aplikasi lain secara aman (no exposed file:// URI).</li>
 *   <li>Format nama file: {@code j2me_debug_log_YYYYMMDD_HHmmss.txt}</li>
 * </ul>
 *
 * <h3>Contoh penggunaan:</h3>
 * <pre>
 * // Export dan langsung bagikan (dari tombol Export di konsol):
 * LogExporter.exportAndShare(context, "SpeedRacer", new LogExporter.ExportCallback() {
 *     {@literal @}Override public void onSuccess(File f) { /* log saved *\/ }
 *     {@literal @}Override public void onFailure(Exception e) { /* handle error *\/ }
 * });
 *
 * // Auto-save saat crash (tanpa share):
 * LogExporter.exportToFile(context, "SpeedRacer", callback);
 * </pre>
 */
public final class LogExporter {

    private static final String TAG = "LogExporter";
    private static final String FILE_PREFIX = "j2me_debug_log_";
    private static final String FILE_EXTENSION = ".txt";

    private static final SimpleDateFormat FILE_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat HEADER_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // Prevent instantiation
    private LogExporter() {}

    // ------------------------------------------------------------------ //
    //  Callback Interface
    // ------------------------------------------------------------------ //

    /**
     * Callback untuk hasil operasi export.
     * Selalu dipanggil, baik sukses maupun gagal.
     */
    public interface ExportCallback {
        /**
         * Dipanggil di background thread saat export berhasil.
         * @param logFile file yang berhasil ditulis
         */
        void onSuccess(File logFile);

        /**
         * Dipanggil di background thread saat export gagal.
         * @param error exception yang terjadi
         */
        void onFailure(Exception error);
    }

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Ekspor semua log ke file teks, lalu langsung trigger Share Intent.
     * Cocok untuk tombol "Export/Share" di debug console.
     *
     * @param context  Android context (Activity atau Application)
     * @param gameName nama game yang sedang berjalan (untuk header file)
     * @param callback dipanggil setelah operasi selesai
     */
    public static void exportAndShare(Context context, String gameName,
                                      ExportCallback callback) {
        List<LogEntry> snapshot = GameLogger.getInstance().getEntries();
        writeToFile(context, gameName, snapshot, callback, /* share = */ true);
    }

    /**
     * Simpan log ke file tanpa langsung dibagikan.
     * Cocok untuk auto-save saat crash ({@link GameCrashHandler}).
     *
     * @param context  Android context
     * @param gameName nama game yang sedang berjalan
     * @param callback dipanggil setelah operasi selesai
     */
    public static void exportToFile(Context context, String gameName,
                                    ExportCallback callback) {
        List<LogEntry> snapshot = GameLogger.getInstance().getEntries();
        writeToFile(context, gameName, snapshot, callback, /* share = */ false);
    }

    /**
     * Hapus semua file log yang tersimpan di cache direktori.
     * Aman dipanggil saat aplikasi diinisialisasi ulang.
     *
     * @param context Android context
     */
    public static void clearExportedLogs(Context context) {
        File logDir = getLogDir(context);
        if (logDir.exists()) {
            File[] files = logDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    /**
     * Kembalikan direktori tempat file log disimpan.
     * Direktori dibuat otomatis jika belum ada.
     */
    public static File getLogDir(Context context) {
        return new File(context.getCacheDir(), "debug_logs");
    }

    // ------------------------------------------------------------------ //
    //  Internal Implementation
    // ------------------------------------------------------------------ //

    private static void writeToFile(Context context, String gameName,
                                    List<LogEntry> entries,
                                    ExportCallback callback,
                                    boolean shareAfter) {
        // Jalankan di background thread untuk menghindari ANR
        new Thread(() -> {
            File logDir = getLogDir(context);
            if (!logDir.exists() && !logDir.mkdirs()) {
                callback.onFailure(new IOException(
                        "Cannot create log directory: " + logDir.getAbsolutePath()));
                return;
            }

            String timestamp;
            synchronized (FILE_DATE_FORMAT) {
                timestamp = FILE_DATE_FORMAT.format(new Date());
            }
            File logFile = new File(logDir, FILE_PREFIX + timestamp + FILE_EXTENSION);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
                writeHeader(writer, gameName, entries.size());
                for (LogEntry entry : entries) {
                    writer.write(entry.toFileString());
                    writer.newLine();
                }
                writer.flush();

                Log.i(TAG, "Log exported: " + logFile.getAbsolutePath()
                        + " (" + entries.size() + " entries)");

                if (shareAfter) {
                    shareFile(context, logFile);
                }
                callback.onSuccess(logFile);

            } catch (IOException e) {
                Log.e(TAG, "Export failed", e);
                callback.onFailure(e);
            }
        }, "LogExporter-Thread").start();
    }

    private static void writeHeader(BufferedWriter writer, String gameName, int entryCount)
            throws IOException {
        String now;
        synchronized (HEADER_DATE_FORMAT) {
            now = HEADER_DATE_FORMAT.format(new Date());
        }
        writer.write("=== J2ME-Loader Debug Log ===");
        writer.newLine();
        writer.write("Game   : " + (gameName != null ? gameName : "Unknown"));
        writer.newLine();
        writer.write("Device : " + Build.MANUFACTURER + " " + Build.MODEL);
        writer.newLine();
        writer.write("Android: " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        writer.newLine();
        writer.write("Export : " + now);
        writer.newLine();
        writer.write("Entries: " + entryCount);
        writer.newLine();
        writer.write("==========================================");
        writer.newLine();
        writer.newLine();
    }

    /**
     * Memulai Share Intent Android untuk file log.
     * Menggunakan FileProvider agar kompatibel dengan Android 7+ (API 24+).
     * Tidak memerlukan permission MANAGE_EXTERNAL_STORAGE.
     */
    private static void shareFile(Context context, File file) {
        try {
            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // FileProvider untuk Android Nougat ke atas
                String authority = context.getPackageName() + ".provider";
                uri = FileProvider.getUriForFile(context, authority, file);
            } else {
                @SuppressWarnings("deprecation")
                Uri legacyUri = Uri.fromFile(file);
                uri = legacyUri;
            }

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "J2ME Debug Log");
            shareIntent.putExtra(Intent.EXTRA_TEXT,
                    "Debug log exported from J2ME-Loader\nFile: " + file.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(shareIntent, "Share Debug Log via...");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);

        } catch (Exception e) {
            Log.e(TAG, "Share failed", e);
        }
    }
}
