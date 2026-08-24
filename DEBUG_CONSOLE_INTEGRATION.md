# Panduan Integrasi: In-Game Logger & Debug Console

Dokumen ini menjelaskan cara mengintegrasikan sistem debug logging yang baru ke dalam
proyek J2ME-Loader LiveEditor secara lengkap.

---

## Daftar File yang Dibuat

| File | Package | Deskripsi |
|---|---|---|
| `LogEntry.java` | `ru.playsoftware.j2meloader.debug` | Model data immutable satu entri log |
| `LogFilter.java` | `ru.playsoftware.j2meloader.debug` | Filter per-level untuk console |
| `GameLogger.java` | `ru.playsoftware.j2meloader.debug` | Singleton core logger + System.out interceptor |
| `LogExporter.java` | `ru.playsoftware.j2meloader.debug` | Ekspor ke file .txt + Share Intent |
| `GameCrashHandler.java` | `ru.playsoftware.j2meloader.debug` | Uncaught exception handler + crash dialog |
| `DebugConsoleLayer.java` | `javax.microedition.lcdui.overlay` | Custom View overlay UI console |

---

## Arsitektur & Alur Data

```
Game J2ME (System.out / System.err)
          │
          ▼
  InterceptOutputStream   ◄──────────────────────────────────┐
  (dalam GameLogger)                                         │
          │                                                  │
          ▼                                              MidletThread
   GameLogger.log()      ◄── emulator code (error, warn)  (lifecycle)
          │
   ┌──────┼──────────────┐
   ▼      ▼              ▼
Logcat  circular       LogListeners
(Log.d) buffer[]       (DebugConsoleLayer)
           │                  │
           ▼                  ▼
       LogExporter        invalidate()
       (file .txt)       (redraw overlay)
           │
       Share Intent
       (Gmail, WA, dll.)
```

### Komponen dan Tanggung Jawabnya

- **`GameLogger`** — Pusat sistem. Semua log masuk ke sini. Memanage circular buffer,
  intercept stream, dan notifikasi listener. Singleton thread-safe.

- **`LogEntry`** — Value object immutable. Satu baris log = satu LogEntry.
  Berisi: timestamp, level, tag, message, stackTrace.

- **`LogFilter`** — State filter level yang digunakan oleh DebugConsoleLayer
  untuk memilih entri mana yang ditampilkan.

- **`DebugConsoleLayer`** — Android custom View yang di-render sebagai overlay
  di atas canvas game. Mendaftar diri ke GameLogger sebagai LogListener.

- **`GameCrashHandler`** — Dipasang sebagai default uncaught exception handler.
  Mencegat crash dari thread MIDlet, log ke GameLogger, auto-save, tampilkan dialog.

- **`LogExporter`** — Utility class untuk menulis log ke file .txt di cache
  dan memulai Share Intent Android.

---

## Langkah Integrasi

### 1. Integrasi di `MicroActivity.java`

File: `app/src/main/java/javax/microedition/shell/MicroActivity.java`

#### a. Tambah import di bagian atas:

```java
import ru.playsoftware.j2meloader.debug.GameCrashHandler;
import ru.playsoftware.j2meloader.debug.GameLogger;
import javax.microedition.lcdui.overlay.DebugConsoleLayer;
```

#### b. Tambah field di class:

```java
private DebugConsoleLayer debugConsoleLayer;
```

#### c. Di `onCreate()`, setelah baris `microLoader.applyConfiguration()`:

```java
// ---- Debug Console Setup ----
GameCrashHandler.install();
GameLogger.getInstance().startIntercept();
GameLogger.getInstance().info("MicroActivity", "Loading game: " + appName);

// Buat dan tambahkan overlay console
debugConsoleLayer = new DebugConsoleLayer(this);
debugConsoleLayer.setGameName(appName);
binding.overlayView.addView(debugConsoleLayer,
        new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
// ---- End Debug Console Setup ----
```

> **Catatan:** `binding.overlayView` adalah `OverlayView` yang sudah ada sebagai
> container untuk overlay layer. Tambahkan `DebugConsoleLayer` di sini agar
> ia berada di atas semua elemen UI lainnya.

#### d. Di `onDestroy()`:

```java
if (debugConsoleLayer != null) {
    debugConsoleLayer.onDestroy(); // Lepas listener agar tidak memory leak
}
GameLogger.getInstance().stopIntercept();
GameCrashHandler.uninstall();
```

#### e. Toggle console dari menu — di `onOptionsItemSelected()`:

```java
// Tambah case di switch atau if-else menu handling:
// Misalnya dari menu item yang sudah ada atau item baru:
case R.id.action_debug_console:
    if (debugConsoleLayer != null) {
        debugConsoleLayer.toggleConsole();
    }
    return true;
```

---

### 2. Tambah Menu Item "Debug Console" (Opsional tapi Disarankan)

File: `app/src/main/res/menu/` (sesuaikan nama file menu yang digunakan MicroActivity)

Cari file menu yang digunakan di MicroActivity (biasanya `micro_menu.xml` atau sejenisnya):

```xml
<item
    android:id="@+id/action_debug_console"
    android:title="Debug Console"
    android:icon="@drawable/ic_debug"
    android:showAsAction="never" />
```

---

### 3. Integrasi di `MidletThread.java` (Error Interceptor di Game Loop)

File: `app/src/main/java/javax/microedition/shell/MidletThread.java`

Tambah import:
```java
import ru.playsoftware.j2meloader.debug.GameCrashHandler;
import ru.playsoftware.j2meloader.debug.GameLogger;
```

Modifikasi `handleMessage()` — wrap tiap state transition dengan try-catch yang
mencatat ke GameLogger **sebelum** melempar ulang atau menghentikan proses:

```java
case START:
    if (state != PAUSED) { break; }
    try {
        state = STARTED;
        GameLogger.getInstance().info("MidletThread", "startApp() called");
        midlet.startApp();
        GameLogger.getInstance().info("MidletThread", "startApp() returned");
    } catch (MIDletStateChangeException e) {
        state = PAUSED;
        GameLogger.getInstance().warn("MidletThread", "Midlet refused to start: " + e.getMessage());
        Log.w(TAG, "Midlet doesn't want to start!", e);
    } catch (Throwable t) {
        state = DESTROYED;
        // Log sebelum wrap agar tersimpan
        GameCrashHandler.handleGameException("startApp", t);
        throw new RuntimeException("Failed startApp", t);
    }
    break;
```

Terapkan pola yang sama untuk case `PAUSE` dan `DESTROY`.

---

### 4. Konfigurasi FileProvider di AndroidManifest.xml

File: `app/src/main/AndroidManifest.xml`

Tambahkan di dalam tag `<application>`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.provider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/debug_log_file_paths" />
</provider>
```

Buat file `app/src/main/res/xml/debug_log_file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- Izinkan berbagi file dari direktori cache internal -->
    <cache-path name="debug_logs" path="debug_logs/" />
</paths>
```

> **Mengapa FileProvider?** Android 7+ (API 24+) melarang berbagi file via
> `file://` URI langsung ke aplikasi lain. FileProvider menghasilkan `content://` URI
> yang aman dan tidak memerlukan permission WRITE_EXTERNAL_STORAGE.

---

### 5. Penggunaan Logger dari Kode Emulator Lain

Dari kelas apapun di seluruh proyek, gunakan:

```java
// Log biasa
GameLogger.getInstance().debug("MyClass", "Detail debug info");
GameLogger.getInstance().info("MyClass", "Operation completed");
GameLogger.getInstance().warn("MyClass", "Unusual condition detected");
GameLogger.getInstance().error("MyClass", "Something went wrong");

// Log error dengan exception (stack trace otomatis disertakan)
GameLogger.getInstance().error("MyClass", "Failed to load resource", exception);

// Manual try-catch di game loop
try {
    // kode game kritis
} catch (Throwable t) {
    GameCrashHandler.handleGameException("NamaOperasi", t);
    // lanjutkan recovery atau rethrow sesuai kebutuhan
}
```

---

## Alur Kerja Debug Console (UX Flow)

```
Saat game berjalan
       │
       ├─ Tap menu → "Debug Console"
       │         │
       │         ▼
       │   DebugConsoleLayer.showConsole()
       │         │
       │    ┌────▼─────────────────────────────────┐
       │    │  🐛 Debug Console [SpeedRacer]    –  │  ← tap – untuk minimize
       │    ├─────────────────────────────────────-─┤
       │    │ [DEBUG] [INFO] [WARN] [ERROR]          │  ← tap toggle filter
       │    ├───────────────────────────────────────┤
       │    │ 13:45:01.234 [INFO]  stdout: Game init │
       │    │ 13:45:01.890 [WARN]  Audio: no clip    │  ← scroll vertikal
       │    │ 13:45:02.100 [ERROR] MidletMain: NPE   │
       │    │ ...                                    │
       │    ├───────────────────────────────────────┤
       │    │   [  🗑 Clear  ]  [ 📤 Export/Share ] │  ← footer buttons
       │    └───────────────────────────────────────┘
       │
       ├─ Tap "–" (minimize)
       │         │
       │         ▼
       │    DBG icon (pojok kanan, badge merah jika ada ERROR)
       │         │
       │    Tap icon → restore
       │
       └─ Tap "Export/Share"
                 │
                 ▼
          LogExporter.exportAndShare()
                 │
                 ▼
          File: j2me_debug_log_20240824_134502.txt
                 │
                 ▼
          Android Share Sheet
          (Email / WhatsApp / Telegram / dll.)
```

---

## Alur Kerja Crash Handler

```
Game MIDlet melempar exception (misal: NullPointerException)
         │
         ▼
GameCrashHandler.uncaughtException(thread, exception)
         │
  ┌──────┼──────────────────────────────────┐
  ▼      ▼                                  ▼
[ERROR] Auto-save log                 Dialog Android (Main Thread)
ke GameLogger ke cache/debug_logs/    ┌──────────────────────────┐
                                      │ ⚠ Game Crash Detected    │
                                      │ Error: NullPointerException│
                                      │ At: ...                   │
                                      │                           │
                                      │ [📤 View & Share Log]     │
                                      │ [⏭ Continue] [❌ Close]   │
                                      └──────────────────────────┘
                                               │
                           ┌───────────────────┼────────────────┐
                           ▼                   ▼                ▼
                    Share log file       Continue game     MidletThread
                    via Intent           (jika masih bisa)  .notifyDestroyed()
```

---

## Catatan Teknis & Keputusan Desain

### Thread Safety
- `GameLogger.log()` menggunakan `ReentrantReadWriteLock` untuk proteksi buffer.
- `LogListener` menggunakan `CopyOnWriteArrayList` agar iterasi listener aman
  tanpa lock tambahan.
- `DebugConsoleLayer.onNewEntry()` dipost ke main thread via `Handler` sebelum
  memodifikasi `filteredEntries`.

### Memory
- Circular buffer dibatasi 2000 entries (`MAX_ENTRIES` di `GameLogger`).
  Entry terlama otomatis dihapus saat limit tercapai.
- File log disimpan di `getCacheDir()` — Android boleh menghapusnya saat storage
  penuh. Gunakan `getFilesDir()` jika ingin file persisten lebih lama.

### Performance
- `DebugConsoleLayer` menggunakan `LAYER_TYPE_HARDWARE` untuk GPU-accelerated rendering.
- Rendering menggunakan canvas clipping dan early-exit untuk entri di luar viewport.
- Log ke Logcat (`android.util.Log`) bersifat synchronous; pastikan tidak ada
  terlalu banyak log DEBUG di production build.

### Kompatibilitas Android
- FileProvider: diperlukan Android 7.0+ (API 24+). Sudah di-handle dengan
  branch `Build.VERSION.SDK_INT >= N`.
- AlertDialog crash: memerlukan Activity context (bukan Application context).
  `ContextHolder.getAppContext()` sudah mengembalikan Activity jika masih hidup.

---

## Cara Cepat Test Tanpa Build

Untuk verifikasi log tanpa menjalankan game, tambahkan sementara di `MicroActivity.onCreate()`:

```java
// Test entries – hapus setelah verifikasi
GameLogger.getInstance().debug("Test", "Debug message dari emulator");
GameLogger.getInstance().info("Test", "Game dimuat berhasil");
GameLogger.getInstance().warn("Test", "Memory hampir penuh");
GameLogger.getInstance().error("Test", "Simulasi crash", new RuntimeException("test"));
```

Lalu panggil `debugConsoleLayer.showConsole()` setelah game terbuka untuk melihat hasilnya.
