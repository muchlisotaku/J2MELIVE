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

import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton logger utama untuk sistem debug J2ME-Loader.
 *
 * <p>Alur data:</p>
 * <pre>
 * Game J2ME (System.out / System.err)
 *       │
 *       ▼
 * InterceptOutputStream ──► GameLogger.log()
 *                                   │
 *                         ┌─────────┼──────────┐
 *                         ▼         ▼          ▼
 *                   circular    LogListeners  android.util.Log
 *                   buffer[]  (UI overlay)    (Logcat)
 * </pre>
 *
 * <h3>Cara penggunaan dasar:</h3>
 * <pre>
 * // Mulai intercept sebelum MIDlet di-load:
 * GameLogger.getInstance().startIntercept();
 *
 * // Log dari kode emulator:
 * GameLogger.getInstance().info("MyTag", "Game started");
 * GameLogger.getInstance().error("MyTag", "Something failed", exception);
 *
 * // Hentikan intercept saat emulator selesai:
 * GameLogger.getInstance().stopIntercept();
 * </pre>
 */
public class GameLogger {

    private static final String ANDROID_TAG = "J2ME-GameLogger";

    /** Kapasitas maksimum circular buffer. */
    private static final int MAX_ENTRIES = 2000;

    private static final String TAG_STDOUT = "stdout";
    private static final String TAG_STDERR = "stderr";

    // ------------------------------------------------------------------ //
    //  Singleton – Double-Checked Locking
    // ------------------------------------------------------------------ //

    private static volatile GameLogger instance;

    /** Kembalikan instance singleton. Thread-safe (double-checked locking). */
    public static GameLogger getInstance() {
        if (instance == null) {
            synchronized (GameLogger.class) {
                if (instance == null) {
                    instance = new GameLogger();
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------------ //
    //  Internals
    // ------------------------------------------------------------------ //

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<LogEntry> entries = new ArrayList<>(MAX_ENTRIES + 1);

    /**
     * CopyOnWriteArrayList agar iterasi listener di log() tidak perlu lock,
     * dan aman saat listener ditambah/dihapus dari thread manapun.
     */
    private final CopyOnWriteArrayList<LogListener> listeners = new CopyOnWriteArrayList<>();

    private PrintStream originalOut;
    private PrintStream originalErr;
    private boolean intercepting = false;

    private GameLogger() {}

    // ------------------------------------------------------------------ //
    //  Public API – Logging Methods
    // ------------------------------------------------------------------ //

    /** Log level DEBUG. */
    public void debug(String tag, String msg) {
        log(LogEntry.Level.DEBUG, tag, msg, null);
    }

    /** Log level INFO. */
    public void info(String tag, String msg) {
        log(LogEntry.Level.INFO, tag, msg, null);
    }

    /** Log level WARN. */
    public void warn(String tag, String msg) {
        log(LogEntry.Level.WARN, tag, msg, null);
    }

    /** Log level ERROR tanpa exception. */
    public void error(String tag, String msg) {
        log(LogEntry.Level.ERROR, tag, msg, null);
    }

    /** Log level ERROR dengan exception (stack trace disertakan). */
    public void error(String tag, String msg, Throwable t) {
        log(LogEntry.Level.ERROR, tag, msg, throwableToString(t));
    }

    /** Log level ERROR – pesan diambil dari exception. */
    public void error(String tag, Throwable t) {
        log(LogEntry.Level.ERROR, tag,
                t.getClass().getSimpleName() + ": " + t.getMessage(),
                throwableToString(t));
    }

    /**
     * Entry point utama. Semua metode log memanggil metode ini.
     *
     * <p>Thread-safe: writeLock digunakan saat memodifikasi buffer.</p>
     *
     * @param level      level log
     * @param tag        tag/sumber log
     * @param message    isi pesan
     * @param stackTrace stack trace yang sudah diformat (boleh null)
     */
    public void log(LogEntry.Level level, String tag, String message, String stackTrace) {
        LogEntry entry = new LogEntry(level, tag, message, stackTrace);

        // --- Tulis ke Logcat Android (secondary output) ---
        String logcatMsg = "[" + tag + "] " + message;
        switch (level) {
            case DEBUG: Log.d(ANDROID_TAG, logcatMsg); break;
            case INFO:  Log.i(ANDROID_TAG, logcatMsg); break;
            case WARN:  Log.w(ANDROID_TAG, logcatMsg); break;
            case ERROR: Log.e(ANDROID_TAG, logcatMsg); break;
        }

        // --- Tambahkan ke circular buffer ---
        lock.writeLock().lock();
        try {
            entries.add(entry);
            // Circular buffer: hapus entry paling lama jika melebihi kapasitas
            if (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
            }
        } finally {
            lock.writeLock().unlock();
        }

        // --- Notifikasi semua listener (biasanya DebugConsoleLayer di UI) ---
        // Menggunakan CopyOnWriteArrayList sehingga tidak perlu lock di sini
        for (LogListener listener : listeners) {
            try {
                listener.onNewEntry(entry);
            } catch (Exception e) {
                // Jangan sampai error listener merusak alur logging
                Log.w(ANDROID_TAG, "LogListener threw exception", e);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  System.out / System.err Interceptor
    // ------------------------------------------------------------------ //

    /**
     * Mulai intercept {@link System#out} dan {@link System#err} dari game.
     *
     * <p><b>Harus dipanggil sebelum MIDlet di-load</b> (idealnya di
     * {@code MicroActivity.onCreate} atau setelah {@code GameCrashHandler.install()}).</p>
     *
     * <p>Output game tetap diteruskan ke stream asli (Logcat) agar tidak hilang.</p>
     */
    public synchronized void startIntercept() {
        if (intercepting) return;

        originalOut = System.out;
        originalErr = System.err;

        System.setOut(new PrintStream(
                new InterceptOutputStream(LogEntry.Level.INFO, TAG_STDOUT), true));
        System.setErr(new PrintStream(
                new InterceptOutputStream(LogEntry.Level.ERROR, TAG_STDERR), true));

        intercepting = true;
        info(ANDROID_TAG, "System.out/err interception started");
    }

    /**
     * Hentikan intercept dan kembalikan System.out/err ke stream asli.
     * Aman dipanggil meskipun intercept belum dimulai.
     */
    public synchronized void stopIntercept() {
        if (!intercepting) return;

        System.setOut(originalOut);
        System.setErr(originalErr);
        intercepting = false;

        info(ANDROID_TAG, "System.out/err interception stopped");
    }

    /** Mengembalikan true jika intercept sedang aktif. */
    public synchronized boolean isIntercepting() {
        return intercepting;
    }

    // ------------------------------------------------------------------ //
    //  Listener Management
    // ------------------------------------------------------------------ //

    /**
     * Daftarkan listener untuk menerima notifikasi real-time setiap ada log baru.
     * Aman dipanggil dari thread manapun. Listener tidak akan terduplikasi.
     */
    public void addListener(LogListener listener) {
        listeners.addIfAbsent(listener);
    }

    /**
     * Batalkan pendaftaran listener.
     * Aman dipanggil dari thread manapun.
     */
    public void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ //
    //  Data Access
    // ------------------------------------------------------------------ //

    /**
     * Kembalikan snapshot immutable dari semua entries yang ada di buffer.
     * Aman untuk dibaca dari thread manapun.
     *
     * @return list yang tidak dapat dimodifikasi
     */
    public List<LogEntry> getEntries() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(entries));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Kembalikan jumlah entries saat ini di buffer.
     */
    public int getEntryCount() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Hapus semua log entries dari buffer.
     * Satu entri INFO otomatis ditambahkan setelah clear.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
        } finally {
            lock.writeLock().unlock();
        }
        info(ANDROID_TAG, "--- Log cleared ---");
    }

    // ------------------------------------------------------------------ //
    //  Helper
    // ------------------------------------------------------------------ //

    /**
     * Konversi Throwable ke String lengkap (dengan stack trace).
     * Mengembalikan null jika t adalah null.
     */
    public static String throwableToString(Throwable t) {
        if (t == null) return null;
        StringWriter sw = new StringWriter(512);
        t.printStackTrace(new PrintWriter(sw, true));
        return sw.toString();
    }

    // ------------------------------------------------------------------ //
    //  LogListener Interface
    // ------------------------------------------------------------------ //

    /**
     * Callback untuk komponen yang ingin menerima notifikasi log secara real-time.
     * Dipanggil dari thread yang memanggil {@link #log}, bukan dari main thread.
     * Implementasi harus thread-safe dan tidak boleh memblokir.
     */
    public interface LogListener {
        /**
         * Dipanggil setiap kali ada entri log baru masuk.
         *
         * @param entry entri log yang baru dibuat
         */
        void onNewEntry(LogEntry entry);
    }

    // ------------------------------------------------------------------ //
    //  InterceptOutputStream – OutputStream wrapper untuk System.out/err
    // ------------------------------------------------------------------ //

    /**
     * OutputStream yang menangkap output byte-per-byte, mengumpulkan
     * karakter sampai newline ditemukan, lalu mengirim baris lengkap ke logger.
     * Output asli tetap diteruskan ke stream original.
     */
    private class InterceptOutputStream extends OutputStream {

        private final LogEntry.Level level;
        private final String tag;
        private final StringBuilder lineBuffer = new StringBuilder(256);

        InterceptOutputStream(LogEntry.Level level, String tag) {
            this.level = level;
            this.tag   = tag;
        }

        @Override
        public void write(int b) throws IOException {
            // Teruskan ke stream original agar Logcat tetap menerima output
            PrintStream original = (level == LogEntry.Level.ERROR) ? originalErr : originalOut;
            if (original != null) {
                original.write(b);
            }

            char c = (char) (b & 0xFF);
            if (c == '\n') {
                String line = lineBuffer.toString().trim();
                if (!line.isEmpty()) {
                    log(level, tag, line, null);
                }
                lineBuffer.setLength(0);
            } else if (c != '\r') {
                lineBuffer.append(c);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            // Teruskan bulk ke original terlebih dahulu
            PrintStream original = (level == LogEntry.Level.ERROR) ? originalErr : originalOut;
            if (original != null) {
                original.write(b, off, len);
            }

            // Proses byte satu per satu untuk line detection
            for (int i = off; i < off + len; i++) {
                char c = (char) (b[i] & 0xFF);
                if (c == '\n') {
                    String line = lineBuffer.toString().trim();
                    if (!line.isEmpty()) {
                        log(level, tag, line, null);
                    }
                    lineBuffer.setLength(0);
                } else if (c != '\r') {
                    lineBuffer.append(c);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            // Flush sisa buffer yang belum diakhiri newline
            if (lineBuffer.length() > 0) {
                String remaining = lineBuffer.toString().trim();
                if (!remaining.isEmpty()) {
                    log(level, tag, remaining, null);
                }
                lineBuffer.setLength(0);
            }
        }
    }
}
