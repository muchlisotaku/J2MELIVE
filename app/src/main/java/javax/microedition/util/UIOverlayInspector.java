/*
 * UIOverlayInspector - Visual UI Overlay & Inspection Tools
 * Part of J2ME-Loader-LiveEditor
 */
package javax.microedition.util;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.TextBox;

/**
 * Singleton inspector for the currently active Displayable.
 * Records each Displayable set via {@link javax.microedition.shell.MicroActivity#setCurrent}
 * and provides snapshot information useful for the overlay and dialog.
 */
public class UIOverlayInspector {

    // ---------------------------------------------------------------------------
    // Singleton
    // ---------------------------------------------------------------------------

    private static volatile UIOverlayInspector instance;

    private UIOverlayInspector() {}

    public static UIOverlayInspector getInstance() {
        if (instance == null) {
            synchronized (UIOverlayInspector.class) {
                if (instance == null) {
                    instance = new UIOverlayInspector();
                }
            }
        }
        return instance;
    }

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private boolean enabled = false;

    /** Weak reference so we never prevent GC of a Displayable. */
    private static WeakReference<Displayable> lastDisplayable = new WeakReference<>(null);

    // ---------------------------------------------------------------------------
    // Enable / disable
    // ---------------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ---------------------------------------------------------------------------
    // Record / query
    // ---------------------------------------------------------------------------

    /**
     * Call this every time {@code Display.setCurrent()} / {@code MicroActivity.setCurrent()}
     * is invoked so the inspector always knows which Displayable is live.
     */
    public void recordDisplayable(Displayable d) {
        lastDisplayable = new WeakReference<>(d);
    }

    /**
     * Returns an {@link InspectInfo} snapshot for the given Displayable,
     * or an empty info object if {@code d} is {@code null}.
     */
    public InspectInfo inspectDisplayable(Displayable d) {
        InspectInfo info = new InspectInfo();
        if (d == null) {
            info.className = "(none)";
            info.displayableType = "Unknown";
            return info;
        }

        info.className = d.getClass().getName();
        info.title = d.getTitle();
        info.lastRepaintTime = System.currentTimeMillis();

        // Determine type
        if (d instanceof Canvas) {
            info.displayableType = "Canvas";
            info.width = ((Canvas) d).getWidth();
            info.height = ((Canvas) d).getHeight();
        } else if (d instanceof Form) {
            info.displayableType = "Form";
            info.width = d.getWidth();
            info.height = d.getHeight();
        } else if (d instanceof Alert) {
            info.displayableType = "Alert";
            info.width = d.getWidth();
            info.height = d.getHeight();
        } else if (d instanceof javax.microedition.lcdui.List) {
            info.displayableType = "List";
            info.width = d.getWidth();
            info.height = d.getHeight();
        } else if (d instanceof TextBox) {
            info.displayableType = "TextBox";
            info.width = d.getWidth();
            info.height = d.getHeight();
        } else {
            info.displayableType = d.getClass().getSimpleName();
            info.width = d.getWidth();
            info.height = d.getHeight();
        }

        // Collect commands
        Command[] cmds = d.getCommands();
        info.commandCount = cmds.length;
        info.commandNames = new ArrayList<>(cmds.length);
        for (Command cmd : cmds) {
            String typeName = commandTypeName(cmd.getCommandType());
            info.commandNames.add(cmd.getLabel() + " [" + typeName + "]");
        }

        return info;
    }

    /**
     * Returns an {@link InspectInfo} for whichever Displayable was last recorded,
     * or an empty info if no Displayable has been recorded yet.
     */
    public InspectInfo getCurrentInfo() {
        Displayable d = lastDisplayable.get();
        return inspectDisplayable(d);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static String commandTypeName(int type) {
        switch (type) {
            case Command.SCREEN:  return "SCREEN";
            case Command.BACK:    return "BACK";
            case Command.CANCEL:  return "CANCEL";
            case Command.OK:      return "OK";
            case Command.HELP:    return "HELP";
            case Command.STOP:    return "STOP";
            case Command.EXIT:    return "EXIT";
            case Command.ITEM:    return "ITEM";
            default:              return "UNKNOWN";
        }
    }

    // ---------------------------------------------------------------------------
    // Inner class: InspectInfo
    // ---------------------------------------------------------------------------

    /**
     * Immutable snapshot of a Displayable's inspection data at a point in time.
     */
    public static class InspectInfo {
        /** Fully-qualified class name of the Displayable. */
        public String className = "";

        /** Human-readable type string: Canvas / Form / Alert / List / TextBox / &lt;simple name&gt;. */
        public String displayableType = "";

        /** Virtual width in pixels (non-zero for Canvas). */
        public int width;

        /** Virtual height in pixels (non-zero for Canvas). */
        public int height;

        /** Title from {@link Displayable#getTitle()}, may be {@code null}. */
        public String title;

        /** Number of Commands attached to the Displayable. */
        public int commandCount;

        /** List of command labels with their type appended, e.g. "Back [BACK]". */
        public java.util.List<String> commandNames = new ArrayList<>();

        /**
         * Timestamp (ms since epoch) captured when this info was built.
         * Consumers can use this to detect stale info.
         */
        public long lastRepaintTime;

        /**
         * Returns a compact single-line summary suitable for on-screen display.
         * Example:  {@code Canvas | 240x320 | "My Game" | 2 cmd(s)}
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(displayableType);
            if (width > 0 || height > 0) {
                sb.append(" | ").append(width).append('x').append(height);
            }
            if (title != null && !title.isEmpty()) {
                sb.append(" | \"").append(title).append('"');
            }
            sb.append(" | ").append(commandCount).append(" cmd(s)");
            return sb.toString();
        }
    }
}
