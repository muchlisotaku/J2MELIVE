/*
 * Contoh MIDlet Demo: Live String Editor / Viewer
 *
 * Demo ini menunjukkan bagaimana fitur Live String Editor bekerja di J2ME-Loader.
 * Semua teks yang digambar oleh g.drawString() secara otomatis:
 *   1. Dicatat (captured) oleh StringEditorManager
 *   2. Dapat diganti secara real-time lewat menu "Live String Editor"
 *
 * Cara penggunaan:
 *   1. Jalankan MIDlet ini di J2ME-Loader
 *   2. Tekan tombol Menu (atau tahan Back) → pilih "Live String Editor"
 *   3. Ketuk string yang ingin diubah, masukkan teks pengganti, tekan Apply
 *   4. Game langsung memperbarui tampilan teksnya
 */

package com.example.porting;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

public class LiveEditorDemoMidlet extends MIDlet implements CommandListener, Runnable {

    // ────────────────────────────────────────────────────────────────────────
    // Variabel string yang ditampilkan di game.
    // Nilai awal ini dapat diubah secara real-time lewat Live String Editor.
    // Tidak perlu deklarasikan field terpisah — StringEditorManager menanganinya
    // secara transparan di dalam Graphics.drawString().
    // ────────────────────────────────────────────────────────────────────────

    /** Judul utama game. Coba ubah ke "Level 1" lewat editor! */
    private static final String TITLE    = "DEMO GAME";

    /** Teks skor. Bagian "Score:" bisa diubah, misal ke "Poin:" */
    private static final String SCORE_LBL = "Score:";

    /** Teks nyawa. Coba ubah ke "Lives:" → "Nyawa:" */
    private static final String LIVES_LBL = "Lives:";

    /** Pesan status tengah layar — yang paling mudah untuk demo. */
    private static final String MSG_RUNNING = "Game Running!";
    private static final String MSG_PAUSED  = "-- PAUSED --";

    private GameCanvas canvas;
    private Thread gameThread;
    private volatile boolean running = false;

    // State sederhana game
    private int score = 0;
    private int lives = 3;
    private int frameCount = 0;
    private boolean paused = false;

    private final Command pauseCmd = new Command("Pause/Resume", Command.SCREEN, 1);
    private final Command exitCmd  = new Command("Exit",         Command.EXIT,   2);

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle MIDlet
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void startApp() throws MIDletStateChangeException {
        canvas = new DemoCanvas(true);
        canvas.addCommand(pauseCmd);
        canvas.addCommand(exitCmd);
        canvas.setCommandListener(this);

        Display.getDisplay(this).setCurrent(canvas);

        running = true;
        gameThread = new Thread(this, "GameLoop");
        gameThread.start();
    }

    @Override
    protected void pauseApp() {
        paused = true;
    }

    @Override
    protected void destroyApp(boolean unconditional) throws MIDletStateChangeException {
        running = false;
        try {
            if (gameThread != null) gameThread.join(1000);
        } catch (InterruptedException ignored) {}
    }

    @Override
    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            try { destroyApp(true); } catch (MIDletStateChangeException ignored) {}
            notifyDestroyed();
        } else if (c == pauseCmd) {
            paused = !paused;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Game loop
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void run() {
        while (running) {
            if (!paused) {
                frameCount++;
                // Tambah skor setiap 60 frame (~1 detik)
                if (frameCount % 60 == 0) {
                    score += 10;
                    if (score > 9990) score = 0;
                }
                render();
            }
            try {
                Thread.sleep(16); // ~60 FPS
            } catch (InterruptedException e) {
                running = false;
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Rendering — semua drawString() di sini otomatis dicapture & bisa diganti
    // ──────────────────────────────────────────────────────────────────────────

    private void render() {
        Graphics g = canvas.getGraphics();
        int w = canvas.getWidth();
        int h = canvas.getHeight();

        // ── Background ───────────────────────────────────────────────────────
        g.setColor(0x1A1A2E); // Biru gelap
        g.fillRect(0, 0, w, h);

        // ── Header bar ───────────────────────────────────────────────────────
        g.setColor(0x16213E);
        g.fillRect(0, 0, w, 28);
        g.setColor(0x0F3460);
        g.drawLine(0, 28, w, 28);

        // ── Judul (TITLE) — akan ter-capture, bisa diganti real-time ─────────
        g.setFont(Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM));
        g.setColor(0xE94560);
        // drawString memanggil StringEditorManager.recordString(TITLE)
        // dan StringEditorManager.replaceString(TITLE) secara otomatis
        g.drawString(TITLE, w / 2, 4, Graphics.HCENTER | Graphics.TOP);

        // ── Skor ─────────────────────────────────────────────────────────────
        g.setFont(Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_PLAIN, Font.SIZE_SMALL));
        g.setColor(0xFFFFFF);
        // "Score:" dan "Lives:" pun akan ter-capture
        g.drawString(SCORE_LBL + " " + score, 6, 4, Graphics.LEFT | Graphics.TOP);
        g.drawString(LIVES_LBL + " " + lives, w - 6, 4, Graphics.RIGHT | Graphics.TOP);

        // ── Area tengah — kotak animasi sederhana ────────────────────────────
        int cx = w / 2;
        int cy = h / 2 - 20;
        int size = 40;
        int offset = (frameCount % 60) - 30; // Oscillate -30..+30

        g.setColor(0x533483);
        g.fillRoundRect(cx - size / 2 + offset / 3, cy - size / 2, size, size, 12, 12);
        g.setColor(0xE94560);
        g.drawRoundRect(cx - size / 2 + offset / 3, cy - size / 2, size, size, 12, 12);

        // ── Pesan status — paling mudah untuk demonstrasi editor ─────────────
        g.setFont(Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_ITALIC, Font.SIZE_MEDIUM));
        g.setColor(0xA8DADC);
        String statusMsg = paused ? MSG_PAUSED : MSG_RUNNING;
        g.drawString(statusMsg, cx, cy + size, Graphics.HCENTER | Graphics.TOP);

        // ── Instruksi di bawah ───────────────────────────────────────────────
        g.setFont(Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL));
        g.setColor(0x888888);
        // String petunjuk ini juga bisa diubah lewat editor!
        g.drawString("Menu > Live String Editor to edit", cx, h - 20, Graphics.HCENTER | Graphics.TOP);
        g.drawString("Tap any string in the editor list", cx, h - 8,  Graphics.HCENTER | Graphics.TOP);

        // ── FPS info ─────────────────────────────────────────────────────────
        g.setColor(0x555555);
        int fps = frameCount % 1000; // sekedar counter angka
        g.drawString("f:" + fps, w - 4, h - 8, Graphics.RIGHT | Graphics.TOP);

        canvas.flushGraphics();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inner class GameCanvas
    // ──────────────────────────────────────────────────────────────────────────

    private static class DemoCanvas extends GameCanvas {
        DemoCanvas(boolean suppressKeyEvents) {
            super(suppressKeyEvents);
        }

        @Override
        protected void paint(Graphics g) {
            // Dipanggil saat platform meminta repaint (misalnya setelah
            // StringEditorManager.notifyListeners() → Canvas.repaint()).
            // Render utama dilakukan oleh game loop di Runnable.run().
        }
    }
}
