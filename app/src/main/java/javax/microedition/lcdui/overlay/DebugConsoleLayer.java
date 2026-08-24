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

package javax.microedition.lcdui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import ru.playsoftware.j2meloader.debug.GameLogger;
import ru.playsoftware.j2meloader.debug.LogEntry;
import ru.playsoftware.j2meloader.debug.LogExporter;
import ru.playsoftware.j2meloader.debug.LogFilter;

import javax.microedition.util.ContextHolder;

/**
 * Custom View yang menampilkan debug console sebagai overlay transparan
 * di atas layar game, mengikuti pola layer yang ada di paket overlay.
 *
 * <h3>Fitur:</h3>
 * <ul>
 *   <li>Semi-transparent background (80% opak)</li>
 *   <li>Auto-scroll ke baris terbaru saat log baru masuk</li>
 *   <li>4 tombol filter level: DEBUG / INFO / WARN / ERROR</li>
 *   <li>Tombol Clear (hapus semua log) dan Export/Share</li>
 *   <li>Drag header untuk memindahkan panel</li>
 *   <li>Scroll vertikal di area log</li>
 *   <li>Minimize ke ikon kecil di pojok kanan</li>
 * </ul>
 *
 * <h3>Cara integrasi di MicroActivity:</h3>
 * <pre>
 * // Di layout XML atau inflate programmatically:
 * DebugConsoleLayer console = new DebugConsoleLayer(this);
 * console.setGameName("SpeedRacer");
 * overlayContainer.addView(console,
 *     new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
 *
 * // Tampilkan saat user long-press menu:
 * console.showConsole();
 *
 * // Cleanup saat activity destroy:
 * console.onDestroy();
 * </pre>
 */
public class DebugConsoleLayer extends View implements GameLogger.LogListener {

    private static final String TAG = "DebugConsoleLayer";

    // ------------------------------------------------------------------ //
    //  Konstanta Dimensi UI (dalam dp/sp, dikonversi di runtime)
    // ------------------------------------------------------------------ //

    private static final float CONSOLE_WIDTH_RATIO  = 0.95f; // 95% lebar layar
    private static final float CONSOLE_HEIGHT_RATIO = 0.55f; // 55% tinggi layar
    private static final float FONT_SIZE_LOG    = 11f; // sp
    private static final float FONT_SIZE_HEADER = 13f; // sp
    private static final float LINE_HEIGHT_FACTOR = 1.6f;
    private static final float BUTTON_HEIGHT = 36f; // dp
    private static final float PADDING = 8f;        // dp
    private static final float CORNER_RADIUS = 10f; // dp

    // ------------------------------------------------------------------ //
    //  Konstanta Warna (ARGB)
    // ------------------------------------------------------------------ //

    private static final int COLOR_BG         = 0xCC000000; // Hitam 80% opak
    private static final int COLOR_HEADER_BG  = 0xEE1A1A2E; // Navy gelap
    private static final int COLOR_BORDER     = 0xFF4444AA; // Biru muda
    private static final int COLOR_BTN_OFF    = 0xFF1E1E2E;
    private static final int COLOR_TEXT_WHITE = 0xFFFFFFFF;
    private static final int COLOR_TEXT_GRAY  = 0xFF666688;
    private static final int COLOR_CLEAR_BTN  = 0xAA882222;
    private static final int COLOR_EXPORT_BTN = 0xAA226644;
    private static final int COLOR_MINIMIZE   = 0xFF888888;
    private static final int COLOR_ICON_BG    = 0xFF2244AA;

    // ------------------------------------------------------------------ //
    //  State
    // ------------------------------------------------------------------ //

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LogFilter filter = new LogFilter();
    private final List<LogEntry> filteredEntries = new ArrayList<>(512);

    // Panel posisi dan ukuran (dalam pixel)
    private float panelX, panelY, panelW, panelH;

    // Scroll state
    private float scrollY = 0f;
    private float maxScrollY = 0f;

    // Touch drag state
    private float lastTouchX, lastTouchY;
    private boolean isDraggingPanel = false;
    private float dragOffsetX, dragOffsetY;

    // Visibility state
    private boolean consoleVisible = false;
    private boolean minimized = false;

    // Nama game saat ini (ditampilkan di header)
    private String gameName = "Unknown";

    // ------------------------------------------------------------------ //
    //  Paint Objects (dibuat sekali, digunakan berulang untuk performa)
    // ------------------------------------------------------------------ //

    private final Paint bgPaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dividerPaint = new Paint();

    // ------------------------------------------------------------------ //
    //  Constructors
    // ------------------------------------------------------------------ //

    public DebugConsoleLayer(Context context) {
        super(context);
        init();
    }

    public DebugConsoleLayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DebugConsoleLayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Hardware layer acceleration untuk rendering smoother
        setLayerType(LAYER_TYPE_HARDWARE, null);

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        // Setup paints
        bgPaint.setColor(COLOR_BG);

        headerPaint.setColor(COLOR_HEADER_BG);

        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1.5f * density);

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(FONT_SIZE_LOG * scaledDensity);
        textPaint.setColor(COLOR_TEXT_WHITE);

        dividerPaint.setColor(0x33FFFFFF);
        dividerPaint.setStrokeWidth(1f);

        // Mulai dalam keadaan tersembunyi
        setVisibility(GONE);

        // Daftarkan sebagai listener ke GameLogger
        GameLogger.getInstance().addListener(this);

        // Muat log yang sudah ada sebelum view ini dibuat
        rebuildFilteredList();
    }

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        panelW = w * CONSOLE_WIDTH_RATIO;
        panelH = h * CONSOLE_HEIGHT_RATIO;
        panelX = (w - panelW) / 2f;
        panelY = h * 0.12f; // Mulai 12% dari atas layar
    }

    /**
     * Harus dipanggil saat Activity/View dihancurkan untuk mencegah memory leak.
     */
    public void onDestroy() {
        GameLogger.getInstance().removeListener(this);
    }

    // ------------------------------------------------------------------ //
    //  GameLogger.LogListener
    // ------------------------------------------------------------------ //

    @Override
    public void onNewEntry(LogEntry entry) {
        // onNewEntry dipanggil dari thread logger, bukan main thread
        mainHandler.post(() -> {
            if (filter.accepts(entry)) {
                filteredEntries.add(entry);
                scrollToBottom();
                if (consoleVisible && !minimized) {
                    invalidate();
                }
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Rendering
    // ------------------------------------------------------------------ //

    @Override
    protected void onDraw(Canvas canvas) {
        if (!consoleVisible) return;

        if (minimized) {
            drawMinimizedIcon(canvas);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float pad = PADDING * density;
        float btnH = BUTTON_HEIGHT * density;
        float lineH = textPaint.getTextSize() * LINE_HEIGHT_FACTOR;
        float headerH = FONT_SIZE_HEADER * scaledDensity + pad * 2.8f;
        float filterBarH = btnH + pad;
        float footerH = btnH + pad * 2;
        float logAreaH = panelH - headerH - filterBarH - footerH;

        float cr = CORNER_RADIUS * density;
        RectF panelRect = new RectF(panelX, panelY, panelX + panelW, panelY + panelH);

        // --- Panel background ---
        canvas.drawRoundRect(panelRect, cr, cr, bgPaint);
        canvas.drawRoundRect(panelRect, cr, cr, borderPaint);

        // --- Header ---
        drawHeader(canvas, pad, headerH, scaledDensity, cr);

        // --- Filter buttons row ---
        float filterY = panelY + headerH;
        drawFilterBar(canvas, filterY, pad, btnH, density, scaledDensity);

        // --- Divider ---
        float logAreaTop = filterY + filterBarH;
        canvas.drawLine(panelX + pad, logAreaTop - pad / 2,
                panelX + panelW - pad, logAreaTop - pad / 2, dividerPaint);

        // --- Log entries (clipped area) ---
        float logAreaBottom = logAreaTop + logAreaH;
        drawLogArea(canvas, logAreaTop, logAreaBottom, pad, lineH);

        // --- Footer ---
        float footerY = panelY + panelH - footerH;
        canvas.drawLine(panelX + pad, footerY, panelX + panelW - pad, footerY, dividerPaint);
        drawFooter(canvas, footerY, pad, btnH, density, scaledDensity);
    }

    private void drawHeader(Canvas canvas, float pad, float headerH,
                             float scaledDensity, float cr) {
        // Header background (rounded top)
        RectF headerRect = new RectF(panelX, panelY, panelX + panelW, panelY + headerH);
        canvas.drawRoundRect(headerRect, cr, cr, headerPaint);
        // Cover rounded corners at bottom of header
        canvas.drawRect(panelX, panelY + headerH / 2,
                panelX + panelW, panelY + headerH, headerPaint);

        // Title text
        Paint hp = new Paint(textPaint);
        hp.setTextSize(FONT_SIZE_HEADER * scaledDensity);
        hp.setColor(COLOR_TEXT_WHITE);
        hp.setFakeBoldText(true);
        canvas.drawText("⬛ Debug Console [" + gameName + "]",
                panelX + pad, panelY + headerH - pad, hp);

        // Entry count badge
        int count = filteredEntries.size();
        Paint cp = new Paint(hp);
        cp.setColor(0xFFAAAAAA);
        cp.setFakeBoldText(false);
        String countStr = count + " entries";
        canvas.drawText(countStr,
                panelX + panelW - pad - hp.measureText(countStr) - 30 * getResources().getDisplayMetrics().density,
                panelY + headerH - pad, cp);

        // Minimize button "–"
        Paint mp = new Paint(hp);
        mp.setColor(COLOR_MINIMIZE);
        mp.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("–", panelX + panelW - pad, panelY + headerH - pad, mp);
    }

    private void drawFilterBar(Canvas canvas, float filterY, float pad,
                                float btnH, float density, float scaledDensity) {
        float btnW = (panelW - pad * 5) / 4f;
        LogEntry.Level[] levels = LogEntry.Level.values();

        for (int i = 0; i < levels.length; i++) {
            LogEntry.Level lv = levels[i];
            boolean active = filter.isLevelEnabled(lv);
            float bx = panelX + pad + i * (btnW + pad);
            RectF btnRect = new RectF(bx, filterY + pad / 2,
                    bx + btnW, filterY + btnH + pad / 2);

            // Button background
            btnPaint.setColor(active
                    ? (lv.color & 0x00FFFFFF) | 0x55000000
                    : COLOR_BTN_OFF);
            canvas.drawRoundRect(btnRect, 6 * density, 6 * density, btnPaint);

            // Button border jika aktif
            if (active) {
                Paint abp = new Paint(borderPaint);
                abp.setColor(lv.color);
                abp.setStrokeWidth(density);
                canvas.drawRoundRect(btnRect, 6 * density, 6 * density, abp);
            }

            // Button label
            Paint bp = new Paint(textPaint);
            bp.setTextSize(FONT_SIZE_LOG * scaledDensity);
            bp.setTextAlign(Paint.Align.CENTER);
            bp.setColor(active ? lv.color : COLOR_TEXT_GRAY);
            bp.setFakeBoldText(active);
            float textY = filterY + pad / 2 + (btnH + pad) / 2 + bp.getTextSize() / 3;
            canvas.drawText(lv.name(), bx + btnW / 2, textY, bp);
        }
    }

    private void drawLogArea(Canvas canvas, float logAreaTop, float logAreaBottom,
                              float pad, float lineH) {
        // Kunci area log
        canvas.save();
        canvas.clipRect(panelX + pad, logAreaTop, panelX + panelW - pad, logAreaBottom);

        // Hitung maxScroll
        maxScrollY = Math.max(0f, filteredEntries.size() * lineH - (logAreaBottom - logAreaTop));

        float drawY = logAreaTop + lineH - scrollY;
        for (int i = 0; i < filteredEntries.size(); i++) {
            float entryBottom = drawY;
            if (entryBottom < logAreaTop - lineH) {
                drawY += lineH;
                continue;
            }
            if (drawY - lineH > logAreaBottom) break;

            LogEntry entry = filteredEntries.get(i);
            textPaint.setColor(entry.level.color);
            canvas.drawText(
                    truncateText(entry.toDisplayString(), panelW - pad * 2),
                    panelX + pad, drawY, textPaint);
            drawY += lineH;
        }

        canvas.restore();
    }

    private void drawFooter(Canvas canvas, float footerY, float pad, float btnH,
                             float density, float scaledDensity) {
        float halfW = (panelW - pad * 3) / 2f;

        // --- Clear button ---
        RectF clearRect = new RectF(
                panelX + pad, footerY + pad,
                panelX + pad + halfW, footerY + pad + btnH);
        btnPaint.setColor(COLOR_CLEAR_BTN);
        canvas.drawRoundRect(clearRect, 6 * density, 6 * density, btnPaint);

        Paint fp = new Paint(textPaint);
        fp.setTextSize(FONT_SIZE_LOG * scaledDensity);
        fp.setTextAlign(Paint.Align.CENTER);
        fp.setColor(COLOR_TEXT_WHITE);
        float textY = footerY + pad + btnH / 2 + fp.getTextSize() / 3;
        canvas.drawText("Clear", panelX + pad + halfW / 2, textY, fp);

        // --- Export/Share button ---
        RectF exportRect = new RectF(
                panelX + pad * 2 + halfW, footerY + pad,
                panelX + panelW - pad, footerY + pad + btnH);
        btnPaint.setColor(COLOR_EXPORT_BTN);
        canvas.drawRoundRect(exportRect, 6 * density, 6 * density, btnPaint);
        canvas.drawText("Export / Share",
                panelX + pad * 2 + halfW + halfW / 2, textY, fp);
    }

    private void drawMinimizedIcon(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float iconSize = 42 * density;
        float iconX = getWidth() - iconSize - 10 * density;
        float iconY = getHeight() * 0.28f;

        RectF iconRect = new RectF(iconX, iconY, iconX + iconSize, iconY + iconSize);
        Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG);
        ip.setColor(COLOR_ICON_BG);
        canvas.drawRoundRect(iconRect, 8 * density, 8 * density, ip);

        // Border
        Paint ibp = new Paint(borderPaint);
        ibp.setStrokeWidth(density);
        canvas.drawRoundRect(iconRect, 8 * density, 8 * density, ibp);

        // Label "DBG"
        Paint it = new Paint(Paint.ANTI_ALIAS_FLAG);
        it.setTypeface(Typeface.MONOSPACE);
        it.setTextAlign(Paint.Align.CENTER);
        it.setColor(COLOR_TEXT_WHITE);
        it.setTextSize(9 * density);
        it.setFakeBoldText(true);
        canvas.drawText("DBG", iconX + iconSize / 2, iconY + iconSize * 0.65f, it);

        // Badge merah jika ada error
        long errorCount = countLevel(LogEntry.Level.ERROR);
        if (errorCount > 0) {
            float badgeR = 8 * density;
            float badgeX = iconX + iconSize - badgeR;
            float badgeY = iconY + badgeR;
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setColor(0xFFFF4444);
            canvas.drawCircle(badgeX, badgeY, badgeR, bp);
        }
    }

    // ------------------------------------------------------------------ //
    //  Touch Handling
    // ------------------------------------------------------------------ //

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!consoleVisible) return false;

        float x = event.getX();
        float y = event.getY();
        float density = getResources().getDisplayMetrics().density;
        float pad = PADDING * density;

        // ---- Minimized mode: tap ikon untuk restore ----
        if (minimized) {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                float iconSize = 42 * density;
                float iconX = getWidth() - iconSize - 10 * density;
                float iconY = getHeight() * 0.28f;
                if (x >= iconX && x <= iconX + iconSize
                        && y >= iconY && y <= iconY + iconSize) {
                    minimized = false;
                    invalidate();
                    return true;
                }
            }
            return minimized; // konsumsi touch jika di atas ikon
        }

        // ---- Normal mode: touch hanya valid di dalam panel ----
        if (!isInsidePanel(x, y)) return false;

        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float btnH = BUTTON_HEIGHT * density;
        float headerH = FONT_SIZE_HEADER * scaledDensity + pad * 2.8f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (y < panelY + headerH) {
                    // Cek tombol minimize (kanan header)
                    if (x > panelX + panelW - 40 * density) {
                        minimized = true;
                        invalidate();
                        return true;
                    }
                    // Drag mode
                    isDraggingPanel = true;
                    dragOffsetX = x - panelX;
                    dragOffsetY = y - panelY;
                } else {
                    isDraggingPanel = false;
                    lastTouchY = y;
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDraggingPanel) {
                    panelX = Math.max(0, Math.min(x - dragOffsetX, getWidth() - panelW));
                    panelY = Math.max(0, Math.min(y - dragOffsetY, getHeight() - panelH));
                    invalidate();
                } else {
                    // Scroll log area
                    float delta = lastTouchY - y;
                    scrollY = Math.max(0f, Math.min(scrollY + delta, maxScrollY));
                    lastTouchY = y;
                    invalidate();
                }
                break;

            case MotionEvent.ACTION_UP:
                isDraggingPanel = false;
                handleTap(x, y, density, scaledDensity, pad, btnH, headerH);
                break;
        }
        return true;
    }

    private void handleTap(float x, float y, float density, float scaledDensity,
                            float pad, float btnH, float headerH) {
        // ---- Filter bar tap ----
        float filterY = panelY + headerH;
        float btnW = (panelW - pad * 5) / 4f;
        if (y >= filterY + pad / 2 && y <= filterY + btnH + pad / 2) {
            LogEntry.Level[] levels = LogEntry.Level.values();
            for (int i = 0; i < levels.length; i++) {
                float bx = panelX + pad + i * (btnW + pad);
                if (x >= bx && x <= bx + btnW) {
                    filter.toggleLevel(levels[i]);
                    rebuildFilteredList();
                    invalidate();
                    return;
                }
            }
        }

        // ---- Footer tap ----
        float footerY = panelY + panelH - (BUTTON_HEIGHT * density) - pad * 2;
        float halfW = (panelW - pad * 3) / 2f;
        if (y >= footerY && y <= footerY + btnH + pad) {
            if (x >= panelX + pad && x <= panelX + pad + halfW) {
                // Clear button
                GameLogger.getInstance().clear();
                filteredEntries.clear();
                scrollY = 0f;
                invalidate();
            } else if (x >= panelX + pad * 2 + halfW) {
                // Export button
                Context ctx = ContextHolder.getAppContext();
                if (ctx != null) {
                    LogExporter.exportAndShare(ctx, gameName,
                            new LogExporter.ExportCallback() {
                                @Override public void onSuccess(java.io.File f) {}
                                @Override public void onFailure(Exception e) {}
                            });
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Set nama game yang ditampilkan di header console.
     * @param name nama game (misalnya dari Descriptor)
     */
    public void setGameName(String name) {
        this.gameName = (name != null && !name.isEmpty()) ? name : "Unknown";
    }

    /**
     * Tampilkan debug console overlay.
     * Jika sebelumnya minimized, akan di-restore ke full view.
     */
    public void showConsole() {
        consoleVisible = true;
        minimized = false;
        setVisibility(VISIBLE);
        invalidate();
    }

    /**
     * Sembunyikan debug console sepenuhnya (GONE).
     * Untuk minimize ke ikon kecil, gunakan {@link #minimizeConsole()}.
     */
    public void hideConsole() {
        consoleVisible = false;
        setVisibility(GONE);
    }

    /**
     * Minimize ke ikon kecil di pojok kanan.
     * Console tetap VISIBLE tapi hanya menampilkan ikon.
     */
    public void minimizeConsole() {
        minimized = true;
        if (!consoleVisible) {
            consoleVisible = true;
            setVisibility(VISIBLE);
        }
        invalidate();
    }

    /** Toggle antara tampil dan tersembunyi. */
    public void toggleConsole() {
        if (consoleVisible && !minimized) {
            minimizeConsole();
        } else {
            showConsole();
        }
    }

    /** Mengembalikan true jika console sedang dalam keadaan visible (termasuk minimized). */
    public boolean isConsoleVisible() {
        return consoleVisible;
    }

    // ------------------------------------------------------------------ //
    //  Private Helpers
    // ------------------------------------------------------------------ //

    private boolean isInsidePanel(float x, float y) {
        return x >= panelX && x <= panelX + panelW
                && y >= panelY && y <= panelY + panelH;
    }

    private void scrollToBottom() {
        if (filteredEntries.isEmpty()) {
            scrollY = 0f;
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float pad = PADDING * density;
        float btnH = BUTTON_HEIGHT * density;
        float lineH = textPaint.getTextSize() * LINE_HEIGHT_FACTOR;
        float headerH = FONT_SIZE_HEADER * scaledDensity + pad * 2.8f;
        float filterBarH = btnH + pad;
        float footerH = btnH + pad * 2;
        float logAreaH = panelH - headerH - filterBarH - footerH;
        maxScrollY = Math.max(0f, filteredEntries.size() * lineH - logAreaH);
        scrollY = maxScrollY;
    }

    private void rebuildFilteredList() {
        filteredEntries.clear();
        List<LogEntry> all = GameLogger.getInstance().getEntries();
        for (LogEntry e : all) {
            if (filter.accepts(e)) {
                filteredEntries.add(e);
            }
        }
        scrollToBottom();
    }

    private long countLevel(LogEntry.Level level) {
        long count = 0;
        for (LogEntry e : filteredEntries) {
            if (e.level == level) count++;
        }
        return count;
    }

    /**
     * Potong teks agar tidak melebihi lebar panel.
     * Mencegah teks panjang keluar dari area clipping saat diukur dengan Paint.
     */
    private String truncateText(String text, float maxWidth) {
        if (textPaint.measureText(text) <= maxWidth) return text;
        // Binary search sederhana untuk menemukan panjang yang pas
        int len = text.length();
        while (len > 0 && textPaint.measureText(text, 0, len) > maxWidth) {
            len--;
        }
        return len > 3 ? text.substring(0, len - 3) + "..." : text.substring(0, len);
    }
}
