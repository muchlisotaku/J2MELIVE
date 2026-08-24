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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Model immutable yang merepresentasikan satu entri log.
 * Berisi level, tag, pesan, timestamp, dan optional stack trace.
 *
 * <p>Kelas ini bersifat immutable dan thread-safe karena semua field final.</p>
 */
public final class LogEntry {

    /** Enum level log yang didukung oleh sistem debug. */
    public enum Level {
        DEBUG("[DEBUG]", 0xFFAAAAAA),
        INFO ("[INFO] ", 0xFF88DDFF),
        WARN ("[WARN] ", 0xFFFFDD44),
        ERROR("[ERROR]", 0xFFFF4444);

        /** Label teks untuk ditampilkan di konsol dan file log. */
        public final String label;

        /**
         * Warna ARGB untuk teks level ini di console overlay.
         * Format: 0xAARRGGBB
         */
        public final int color;

        Level(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    // Format timestamp yang sama digunakan di display dan file untuk konsistensi
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Epoch millis saat log dibuat. */
    public final long timestamp;

    /** Level log (DEBUG/INFO/WARN/ERROR). */
    public final Level level;

    /**
     * Tag/sumber log, misalnya nama class atau "stdout"/"stderr".
     * Direkomendasikan ≤ 20 karakter.
     */
    public final String tag;

    /** Isi pesan log. */
    public final String message;

    /**
     * Full stack trace yang diformat sebagai String.
     * {@code null} jika bukan log error / tidak ada exception.
     */
    public final String stackTrace;

    /**
     * Buat entri log baru. Timestamp diset otomatis ke waktu sekarang.
     *
     * @param level      level log
     * @param tag        tag/sumber log
     * @param message    isi pesan
     * @param stackTrace stack trace (boleh null)
     */
    public LogEntry(Level level, String tag, String message, String stackTrace) {
        this.timestamp  = System.currentTimeMillis();
        this.level      = level;
        this.tag        = tag;
        this.message    = message;
        this.stackTrace = stackTrace;
    }

    /**
     * Format satu baris untuk ditampilkan di UI konsol.
     * Contoh: {@code 13:45:02.123 [ERROR] MidletMain: NullPointerException}
     */
    public String toDisplayString() {
        synchronized (DATE_FORMAT) {
            return DATE_FORMAT.format(new Date(timestamp))
                    + " " + level.label
                    + " " + tag
                    + ": " + message;
        }
    }

    /**
     * Format lengkap untuk ditulis ke file .txt,
     * termasuk stack trace di bawah pesan jika tersedia.
     */
    public String toFileString() {
        StringBuilder sb = new StringBuilder(128);
        synchronized (DATE_FORMAT) {
            sb.append(DATE_FORMAT.format(new Date(timestamp)));
        }
        sb.append(" ").append(level.label)
          .append(" ").append(tag)
          .append(": ").append(message);
        if (stackTrace != null && !stackTrace.isEmpty()) {
            sb.append("\n").append(stackTrace);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}
