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

package ru.playsoftware.j2meloader.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Menginstal file shader bawaan dari assets ke folder shaders di storage
 * saat pertama kali aplikasi dijalankan atau saat ada update.
 *
 * <p>Dipanggil dari {@code EmulatorApplication.onCreate()} di background thread.</p>
 *
 * <p>File shader yang disalin: crt, crt_arcade, crt_lcd, crt_tv beserta
 * file .ini, .fsh, dan .vsh-nya.</p>
 */
public class ShaderInstaller {

    private static final String TAG = "ShaderInstaller";
    private static final String PREF_SHADERS_VERSION = "shaders_installed_version";

    /**
     * Versi shader bawaan. Naikkan angka ini jika ada update shader
     * agar file lama otomatis ditimpa saat update aplikasi.
     */
    private static final int SHADERS_VERSION = 2;

    /**
     * Daftar lengkap file shader yang perlu disalin dari assets/shaders/
     * ke emulatorDir/shaders/
     */
    private static final String[] SHADER_FILES = {
            "crt.ini",
            "crt.fsh",
            "crt.vsh",
            "crt_arcade.ini",
            "crt_arcade.fsh",
            "crt_lcd.ini",
            "crt_lcd.fsh",
            "crt_tv.ini",
            "crt_tv.fsh",
    };

    /**
     * Cek dan install shader jika belum ada atau versi lama.
     * Aman dipanggil berkali-kali. Operasi dijalankan di thread pemanggil
     * (sebaiknya background thread).
     *
     * @param context Android context
     */
    public static void installIfNeeded(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int installedVersion = prefs.getInt(PREF_SHADERS_VERSION, 0);

        if (installedVersion >= SHADERS_VERSION) {
            Log.d(TAG, "Shaders up to date (v" + installedVersion + "), skipping install.");
            return;
        }

        Log.i(TAG, "Installing shaders v" + SHADERS_VERSION
                + " (was v" + installedVersion + ")...");

        String shadersDir = Config.getShadersDir();
        File dir = new File(shadersDir);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create shaders dir: " + shadersDir);
            return;
        }

        int successCount = 0;
        for (String fileName : SHADER_FILES) {
            try {
                copyAssetToFile(context, "shaders/" + fileName,
                        new File(dir, fileName));
                successCount++;
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy shader: " + fileName, e);
            }
        }

        if (successCount > 0) {
            prefs.edit().putInt(PREF_SHADERS_VERSION, SHADERS_VERSION).apply();
            Log.i(TAG, "Shaders installed: " + successCount + "/" + SHADER_FILES.length
                    + " files to " + shadersDir);
        }
    }

    /**
     * Salin satu file dari assets ke path tujuan di storage.
     * File tujuan akan ditimpa jika sudah ada.
     */
    private static void copyAssetToFile(Context context, String assetPath, File dest)
            throws IOException {
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }
}
