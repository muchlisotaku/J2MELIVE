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

import java.util.EnumSet;
import java.util.Set;

/**
 * Filter yang dapat dikonfigurasi untuk menentukan level log mana yang
 * ditampilkan di debug console.
 *
 * <p>Default: semua level (DEBUG, INFO, WARN, ERROR) aktif.</p>
 *
 * <p>Semua metode thread-safe via synchronized.</p>
 */
public class LogFilter {

    private final Set<LogEntry.Level> enabledLevels =
            EnumSet.allOf(LogEntry.Level.class);

    /**
     * Aktifkan atau nonaktifkan level tertentu.
     *
     * @param level   level yang ingin diubah
     * @param enabled true untuk aktifkan, false untuk nonaktifkan
     */
    public synchronized void setLevelEnabled(LogEntry.Level level, boolean enabled) {
        if (enabled) {
            enabledLevels.add(level);
        } else {
            enabledLevels.remove(level);
        }
    }

    /**
     * Periksa apakah sebuah entri log lolos filter ini.
     *
     * @param entry entri yang akan diperiksa
     * @return true jika level entri aktif di filter ini
     */
    public synchronized boolean accepts(LogEntry entry) {
        return enabledLevels.contains(entry.level);
    }

    /**
     * Periksa apakah level tertentu sedang aktif.
     *
     * @param level level yang ingin dicek
     * @return true jika aktif
     */
    public synchronized boolean isLevelEnabled(LogEntry.Level level) {
        return enabledLevels.contains(level);
    }

    /**
     * Reset filter ke keadaan default (semua level aktif).
     */
    public synchronized void reset() {
        enabledLevels.clear();
        enabledLevels.addAll(EnumSet.allOf(LogEntry.Level.class));
    }

    /**
     * Toggle level: jika aktif → nonaktifkan, jika nonaktif → aktifkan.
     *
     * @param level level yang ingin di-toggle
     */
    public synchronized void toggleLevel(LogEntry.Level level) {
        setLevelEnabled(level, !enabledLevels.contains(level));
    }

    /**
     * Mengembalikan snapshot (copy) dari set level yang sedang aktif.
     * Aman untuk iterasi tanpa memegang lock.
     *
     * @return EnumSet berisi level-level yang aktif
     */
    public synchronized Set<LogEntry.Level> getEnabledLevels() {
        return EnumSet.copyOf(enabledLevels);
    }
}
