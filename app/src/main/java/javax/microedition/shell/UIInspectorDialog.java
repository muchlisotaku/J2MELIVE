/*
 * UIInspectorDialog - full-detail inspector dialog for the active Displayable.
 * Part of J2ME-Loader-LiveEditor
 */
package javax.microedition.shell;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.util.List;

import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.overlay.InspectorOverlayLayer;
import javax.microedition.util.ContextHolder;
import javax.microedition.util.UIOverlayInspector;
import javax.microedition.util.UIOverlayInspector.InspectInfo;

/**
 * A {@link DialogFragment} that shows full inspection details for the currently
 * active {@link Displayable} and refreshes every second.
 *
 * <p>Usage:
 * <pre>
 *   UIInspectorDialog.newInstance()
 *       .show(getSupportFragmentManager(), UIInspectorDialog.TAG);
 * </pre>
 */
public class UIInspectorDialog extends DialogFragment {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    public static final String TAG = "UIInspectorDialog";

    private static final int REFRESH_INTERVAL_MS = 1000;

    // -----------------------------------------------------------------------
    // Views
    // -----------------------------------------------------------------------

    private TextView tvClassName;
    private TextView tvClassHierarchy;
    private TextView tvType;
    private TextView tvDimensions;
    private TextView tvTitle;
    private TextView tvCommandCount;
    private LinearLayout commandsContainer;
    private CheckBox cbOverlayEnabled;

    // -----------------------------------------------------------------------
    // Shared overlay layer instance
    // -----------------------------------------------------------------------

    /**
     * The single instance that is added to / removed from the OverlayView.
     * Kept here as a static field so it persists across dialog re-creation.
     */
    private static InspectorOverlayLayer overlayLayer;

    // -----------------------------------------------------------------------
    // Refresh handler
    // -----------------------------------------------------------------------

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshInfo();
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    // -----------------------------------------------------------------------
    // Factory
    // -----------------------------------------------------------------------

    public static UIInspectorDialog newInstance() {
        return new UIInspectorDialog();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Build layout programmatically (no XML required)
        LinearLayout root = buildLayout();

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        builder.setTitle("UI Inspector");
        builder.setView(root);
        builder.setPositiveButton("Close", (d, w) -> dismiss());
        return builder.create();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Start periodic refresh
        refreshHandler.post(refreshRunnable);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop periodic refresh
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    // -----------------------------------------------------------------------
    // Layout builder (programmatic, no layout XML needed)
    // -----------------------------------------------------------------------

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(requireActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        int paddingPx = dp(16);
        root.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        // Overlay toggle checkbox
        cbOverlayEnabled = new CheckBox(requireActivity());
        cbOverlayEnabled.setText("Show overlay HUD");
        cbOverlayEnabled.setChecked(UIOverlayInspector.getInstance().isEnabled());
        cbOverlayEnabled.setOnCheckedChangeListener((btn, checked) -> {
            UIOverlayInspector.getInstance().setEnabled(checked);
            updateOverlayLayer(checked);
        });
        root.addView(cbOverlayEnabled, matchWrap());

        root.addView(divider(), matchWrap());

        // --- Class name
        tvClassName = addLabeledRow(root, "Class:");
        // --- Class hierarchy
        tvClassHierarchy = addLabeledRow(root, "Hierarchy:");
        // --- Type
        tvType = addLabeledRow(root, "Type:");
        // --- Dimensions
        tvDimensions = addLabeledRow(root, "Dimensions:");
        // --- Title
        tvTitle = addLabeledRow(root, "Title:");
        // --- Command count
        tvCommandCount = addLabeledRow(root, "Commands:");

        root.addView(divider(), matchWrap());

        // Command list label
        TextView cmdLabel = new TextView(requireActivity());
        cmdLabel.setText("Command list:");
        cmdLabel.setTextSize(13);
        root.addView(cmdLabel, matchWrap());

        // Scrollable container for command entries
        ScrollView scroll = new ScrollView(requireActivity());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(120));
        scroll.setLayoutParams(scrollParams);

        commandsContainer = new LinearLayout(requireActivity());
        commandsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(commandsContainer, matchWrap());
        root.addView(scroll);

        return root;
    }

    // -----------------------------------------------------------------------
    // Refresh
    // -----------------------------------------------------------------------

    private void refreshInfo() {
        if (!isAdded() || getActivity() == null) return;

        InspectInfo info = UIOverlayInspector.getInstance().getCurrentInfo();
        if (info == null) return;

        // Class name (simple)
        String simpleName = info.className;
        int dot = simpleName.lastIndexOf('.');
        if (dot >= 0 && dot < simpleName.length() - 1) {
            simpleName = simpleName.substring(dot + 1);
        }
        tvClassName.setText(simpleName);

        // Full class hierarchy
        tvClassHierarchy.setText(buildHierarchy(info.className));

        // Type
        tvType.setText(info.displayableType);

        // Dimensions
        tvDimensions.setText(info.width + " × " + info.height + " px");

        // Title
        tvTitle.setText(info.title != null ? info.title : "(no title)");

        // Command count
        tvCommandCount.setText(String.valueOf(info.commandCount));

        // Command list
        commandsContainer.removeAllViews();
        List<String> names = info.commandNames;
        if (names == null || names.isEmpty()) {
            TextView empty = new TextView(requireActivity());
            empty.setText("  (no commands)");
            empty.setTextSize(12);
            commandsContainer.addView(empty, matchWrap());
        } else {
            for (int i = 0; i < names.size(); i++) {
                TextView tv = new TextView(requireActivity());
                tv.setText("  " + (i + 1) + ". " + names.get(i));
                tv.setTextSize(12);
                commandsContainer.addView(tv, matchWrap());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Overlay layer management
    // -----------------------------------------------------------------------

    private void updateOverlayLayer(boolean enable) {
        MicroActivity activity = ContextHolder.getActivity();
        if (activity == null || activity.binding == null) return;

        if (enable) {
            if (overlayLayer == null) {
                overlayLayer = new InspectorOverlayLayer();
            }
            // Add at position 0 (bottom-most overlay layer)
            activity.binding.overlayView.addLayer(overlayLayer, 0);
            activity.binding.overlayView.setVisibility(true);
        } else {
            if (overlayLayer != null) {
                activity.binding.overlayView.removeLayer(overlayLayer);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Walks the class hierarchy of {@code className} (up to Object) and returns
     * a compact arrow-separated string, e.g.
     * {@code GameCanvas → Canvas → Displayable → Object}.
     */
    private String buildHierarchy(String className) {
        try {
            Class<?> cls = Class.forName(className);
            StringBuilder sb = new StringBuilder();
            while (cls != null) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(cls.getSimpleName());
                cls = cls.getSuperclass();
            }
            return sb.toString();
        } catch (ClassNotFoundException e) {
            return className;
        }
    }

    /** Adds a two-row label+value section and returns the value TextView. */
    private TextView addLabeledRow(LinearLayout parent, String label) {
        TextView tvLabel = new TextView(requireActivity());
        tvLabel.setText(label);
        tvLabel.setTextSize(11);
        tvLabel.setAlpha(0.6f);
        parent.addView(tvLabel, matchWrap());

        TextView tvValue = new TextView(requireActivity());
        tvValue.setText("—");
        tvValue.setTextSize(13);
        tvValue.setPadding(dp(8), 0, 0, dp(4));
        parent.addView(tvValue, matchWrap());

        return tvValue;
    }

    private View divider() {
        View v = new View(requireActivity());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        p.topMargin = dp(6);
        p.bottomMargin = dp(6);
        v.setLayoutParams(p);
        v.setBackgroundColor(0x33FFFFFF);
        return v;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        float density = requireActivity().getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
