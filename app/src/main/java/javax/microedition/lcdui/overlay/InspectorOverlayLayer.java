/*
 * InspectorOverlayLayer - draws a compact debug HUD in the top-left corner.
 * Part of J2ME-Loader-LiveEditor
 */
package javax.microedition.lcdui.overlay;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.util.UIOverlayInspector;
import javax.microedition.util.UIOverlayInspector.InspectInfo;

/**
 * An overlay {@link Layer} that paints a semi-transparent debug HUD onto the
 * Android {@link Canvas} in the top-left corner.
 *
 * <p>Displayed information:
 * <ul>
 *   <li>Displayable class name (simple)</li>
 *   <li>Displayable type + dimensions</li>
 *   <li>FPS (repaint rate, updated once per second)</li>
 *   <li>Total repaint count since the last Displayable was set</li>
 * </ul>
 *
 * <p>The layer counts its own {@link #paint(CanvasWrapper)} calls to measure
 * repaint rate; every second the running total is latched as the FPS value.
 */
public class InspectorOverlayLayer implements Layer {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Background alpha (0–255). 160 ≈ 63 % opacity. */
    private static final int BACKGROUND_ALPHA = 160;

    /** Text size in SP – converted to px at construction time. */
    private static final float TEXT_SIZE_SP = 10f;

    /** Padding around the text block (pixels). */
    private static final int PADDING = 4;

    /** Space between consecutive text lines (pixels). */
    private static final int LINE_SPACING = 2;

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private boolean visible = true;

    /** Counts paint() calls in the current 1-second window. */
    private int frameCounter;

    /** Latched FPS value shown to the user. */
    private int currentFps;

    /** Timestamp (ms) when the current 1-second window started. */
    private long windowStart = System.currentTimeMillis();

    /** Total number of paint() calls since last Displayable change. */
    private long totalRepaintCount;

    // -----------------------------------------------------------------------
    // Drawing tools
    // -----------------------------------------------------------------------

    private final Paint backgroundPaint;
    private final Paint textPaint;
    private final Rect textBounds = new Rect();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public InspectorOverlayLayer() {
        backgroundPaint = new Paint();
        backgroundPaint.setStyle(Paint.Style.FILL);
        backgroundPaint.setColor(0x00000000);          // black; alpha set below
        backgroundPaint.setAlpha(BACKGROUND_ALPHA);

        textPaint = new Paint();
        textPaint.setAntiAlias(true);
        textPaint.setColor(0xFFFFFFFF);                // white
        textPaint.setTypeface(Typeface.MONOSPACE);
        // Convert 10sp to px using the default density; CanvasWrapper has no
        // easy density accessor so we use a reasonable constant (160 dpi baseline:
        // 1sp = 1dp, 1dp ≈ 1px on mdpi.  We scale by android.util.DisplayMetrics
        // later if the CanvasWrapper is extended, but for now 10 * 2.5 = 25px
        // gives a readable size on modern devices without android.content.Context).
        textPaint.setTextSize(TEXT_SIZE_SP * 2.5f);    // ~25 px — readable on hdpi+
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    // -----------------------------------------------------------------------
    // Layer interface
    // -----------------------------------------------------------------------

    /**
     * Called by {@link OverlayView#onDraw} for every frame.
     * The supplied {@link CanvasWrapper} wraps an Android {@link Canvas}.
     *
     * <p>We grab the underlying Android Canvas via reflection-free trick: we
     * extend CanvasWrapper to expose the canvas, but since we cannot modify
     * CanvasWrapper here we fall back to drawing via CanvasWrapper's own
     * primitive methods that accept raw positions.
     *
     * <p>To draw arbitrary multi-line text with a background rect we need the
     * raw {@link Canvas}.  CanvasWrapper exposes {@code drawBackgroundedText}
     * for a single line and {@code fillRect} for the background.  We use
     * those plus {@link CanvasWrapper#drawString} for multi-line support.
     */
    @Override
    public void paint(CanvasWrapper g) {
        if (!visible) return;
        if (!UIOverlayInspector.getInstance().isEnabled()) return;

        // ---- repaint rate accounting -----------------------------------
        long now = System.currentTimeMillis();
        frameCounter++;
        totalRepaintCount++;
        if (now - windowStart >= 1000L) {
            currentFps = frameCounter;
            frameCounter = 0;
            windowStart = now;
        }

        // ---- gather info -----------------------------------------------
        InspectInfo info = UIOverlayInspector.getInstance().getCurrentInfo();
        if (info == null) return;

        String[] lines = buildLines(info);

        // ---- measure ---------------------------------------------------
        float lineHeight = textPaint.getTextSize() + LINE_SPACING;
        float maxWidth = 0;
        for (String line : lines) {
            float w = textPaint.measureText(line);
            if (w > maxWidth) maxWidth = w;
        }
        float totalHeight = lineHeight * lines.length + PADDING * 2;
        float totalWidth  = maxWidth + PADDING * 2;

        // ---- draw background -------------------------------------------
        android.graphics.RectF bgRect = new android.graphics.RectF(
                0, 0, totalWidth, totalHeight);
        g.setFillColor(0xA0000000);   // semi-transparent black (alpha ~160)
        g.fillRect(bgRect);

        // ---- draw text lines using CanvasWrapper -----------------------
        g.setTextColor(0xFFFFFFFF);
        g.setTextAlign(Paint.Align.LEFT);
        // CanvasWrapper.drawString draws text centred; we re-align by setting
        // LEFT align above.  Y is top of bounding box, so add ascent.
        textPaint.getTextBounds("A", 0, 1, textBounds);
        float ascent = -textPaint.ascent();   // positive ascent value
        for (int i = 0; i < lines.length; i++) {
            float x = PADDING;
            float y = PADDING + i * lineHeight + ascent;
            g.drawString(lines[i], x, y);
        }
    }

    // -----------------------------------------------------------------------
    // Visibility
    // -----------------------------------------------------------------------

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible;
    }

    // -----------------------------------------------------------------------
    // Counter reset (call when a new Displayable becomes current)
    // -----------------------------------------------------------------------

    public void resetCounters() {
        frameCounter = 0;
        currentFps = 0;
        totalRepaintCount = 0;
        windowStart = System.currentTimeMillis();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String[] buildLines(InspectInfo info) {
        // Simple class name (last segment after '.')
        String simpleName = info.className;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0 && dot < simpleName.length() - 1) {
            simpleName = simpleName.substring(dot + 1);
        }

        return new String[]{
                "Class: " + simpleName,
                "Type:  " + info.displayableType,
                "Size:  " + info.width + "x" + info.height,
                "FPS:   " + currentFps,
                "Repnt: " + totalRepaintCount
        };
    }
}
