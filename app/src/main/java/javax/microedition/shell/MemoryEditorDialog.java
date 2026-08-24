/*
 * MemoryEditorDialog - Android DialogFragment UI for RuntimeMemoryEditor.
 *
 * Provides a GameGuardian-style interface to scan, narrow, edit, and freeze
 * MIDlet fields at runtime.
 *
 * All label strings are hardcoded (not @string resources) — strings.xml
 * is handled in a separate stage.
 */

package javax.microedition.shell;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.util.ArrayList;
import java.util.List;

import javax.microedition.util.RuntimeMemoryEditor;

/**
 * A DialogFragment that exposes a full memory-editor UI on top of the running MIDlet.
 *
 * Flow:
 *  1. User enters a value and type (INT / FLOAT / STRING) then taps "Scan".
 *  2. Results appear in a ListView showing path, current value, and a FROZEN badge.
 *  3. Tap an item → inline edit dialog.
 *  4. Long-tap (or select then tap "Freeze") → field is frozen at current value.
 *  5. "Narrow / Filter" re-scans the previous result list for the new value.
 *  6. "Unfreeze All" clears all frozen fields.
 *  7. ListView refreshes every 500 ms to show live values.
 */
public class MemoryEditorDialog extends DialogFragment {

    public static final String TAG = "MemoryEditorDialog";

    // -------------------------------------------------------------------------
    // Constants for scan type selection
    // -------------------------------------------------------------------------
    private static final int TYPE_INT    = 0;
    private static final int TYPE_FLOAT  = 1;
    private static final int TYPE_STRING = 2;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private final RuntimeMemoryEditor editor = RuntimeMemoryEditor.getInstance();

    /** Current scan results shown in the list. */
    private final List<RuntimeMemoryEditor.FieldEntry> currentResults = new ArrayList<>();

    /** The last full scan result list — used by narrowSearch. */
    private List<RuntimeMemoryEditor.FieldEntry> lastScanResults = new ArrayList<>();

    /** Selected item index in the ListView (for single-selection Freeze action). */
    private int selectedIndex = -1;

    // -------------------------------------------------------------------------
    // UI references
    // -------------------------------------------------------------------------
    private EditText etValue;
    private RadioGroup rgType;
    private RadioButton rbInt;
    private RadioButton rbFloat;
    private RadioButton rbString;
    private Button btnScan;
    private Button btnNarrow;
    private Button btnFreeze;
    private Button btnUnfreezeAll;
    private ListView listView;
    private TextView tvStatus;

    private FieldEntryAdapter adapter;

    // -------------------------------------------------------------------------
    // Refresh Handler — posts to main thread every 500 ms
    // -------------------------------------------------------------------------
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            updateStatusText();
            refreshHandler.postDelayed(this, 500);
        }
    };

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /** Creates a new instance of MemoryEditorDialog. */
    public static MemoryEditorDialog newInstance() {
        return new MemoryEditorDialog();
    }

    // -------------------------------------------------------------------------
    // DialogFragment lifecycle
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireActivity();

        // Build entire UI programmatically (no XML layouts required)
        LinearLayout root = buildRootLayout(ctx);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Memory Editor")
                .setView(root)
                .setNegativeButton("Close", (d, which) -> dismiss())
                .create();

        return dialog;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshHandler.postDelayed(refreshRunnable, 500);
        Log.d(TAG, "onResume — refresh loop started");
    }

    @Override
    public void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
        Log.d(TAG, "onPause — refresh loop stopped");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    // -------------------------------------------------------------------------
    // UI construction (programmatic — no XML)
    // -------------------------------------------------------------------------

    private LinearLayout buildRootLayout(Context ctx) {
        int dp4  = dp(ctx, 4);
        int dp8  = dp(ctx, 8);
        int dp12 = dp(ctx, 12);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp8, dp8, dp8, dp8);

        // --- Input row: EditText + RadioGroup ---
        LinearLayout inputRow = new LinearLayout(ctx);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);

        etValue = new EditText(ctx);
        etValue.setHint("Search value");
        etValue.setSingleLine(true);
        LinearLayout.LayoutParams etParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etParams.setMargins(0, 0, dp8, 0);
        etValue.setLayoutParams(etParams);
        inputRow.addView(etValue);

        // RadioGroup for type selection
        rgType = new RadioGroup(ctx);
        rgType.setOrientation(RadioGroup.HORIZONTAL);

        rbInt    = makeRadioButton(ctx, "INT",    TYPE_INT);
        rbFloat  = makeRadioButton(ctx, "FLOAT",  TYPE_FLOAT);
        rbString = makeRadioButton(ctx, "STRING", TYPE_STRING);
        rbInt.setId(View.generateViewId());
        rbFloat.setId(View.generateViewId());
        rbString.setId(View.generateViewId());

        rgType.addView(rbInt);
        rgType.addView(rbFloat);
        rgType.addView(rbString);
        rgType.check(rbInt.getId());

        inputRow.addView(rgType);
        root.addView(inputRow);

        // --- Button row 1: Scan + Narrow ---
        LinearLayout btnRow1 = new LinearLayout(ctx);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRowParams.setMargins(0, dp4, 0, dp4);
        btnRow1.setLayoutParams(btnRowParams);

        btnScan   = makeButton(ctx, "Scan (First)", dp8);
        btnNarrow = makeButton(ctx, "Narrow / Filter", dp8);

        btnRow1.addView(btnScan);
        btnRow1.addView(btnNarrow);
        root.addView(btnRow1);

        // --- Button row 2: Freeze + Unfreeze All ---
        LinearLayout btnRow2 = new LinearLayout(ctx);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRow2Params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnRow2Params.setMargins(0, 0, 0, dp4);
        btnRow2.setLayoutParams(btnRow2Params);

        btnFreeze      = makeButton(ctx, "Freeze Selected", dp8);
        btnUnfreezeAll = makeButton(ctx, "Unfreeze All", dp8);

        btnRow2.addView(btnFreeze);
        btnRow2.addView(btnUnfreezeAll);
        root.addView(btnRow2);

        // --- Status label ---
        tvStatus = new TextView(ctx);
        tvStatus.setText("No scan yet");
        tvStatus.setTextSize(11f);
        tvStatus.setTextColor(Color.GRAY);
        tvStatus.setPadding(0, 0, 0, dp4);
        root.addView(tvStatus);

        // --- ListView ---
        listView = new ListView(ctx);
        LinearLayout.LayoutParams lvParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 320));
        listView.setLayoutParams(lvParams);
        listView.setDividerHeight(1);

        adapter = new FieldEntryAdapter(ctx, currentResults);
        listView.setAdapter(adapter);
        root.addView(listView);

        // --- Wire up click listeners ---
        btnScan.setOnClickListener(v -> performFirstScan());
        btnNarrow.setOnClickListener(v -> performNarrowScan());
        btnFreeze.setOnClickListener(v -> freezeSelected());
        btnUnfreezeAll.setOnClickListener(v -> {
            editor.unfreezeAll();
            Toast.makeText(ctx, "All fields unfrozen", Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            highlightSelected(position);
            RuntimeMemoryEditor.FieldEntry entry = currentResults.get(position);
            showEditValueDialog(ctx, entry);
        });

        return root;
    }

    // -------------------------------------------------------------------------
    // Scan actions
    // -------------------------------------------------------------------------

    private void performFirstScan() {
        String rawValue = etValue.getText().toString().trim();
        if (rawValue.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a value to scan", Toast.LENGTH_SHORT).show();
            return;
        }

        int scanType = getSelectedScanType();
        List<RuntimeMemoryEditor.FieldEntry> results;

        try {
            if (scanType == TYPE_INT) {
                int v = Integer.parseInt(rawValue);
                results = editor.searchByValue(v);
            } else if (scanType == TYPE_FLOAT) {
                float v = Float.parseFloat(rawValue);
                // Use a small tolerance for float comparison: 0.001
                results = editor.searchByValue(v, 0.001f);
            } else {
                // STRING
                results = editor.searchByValue(rawValue);
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid number format", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "performFirstScan: number parse error: " + e.getMessage());
            return;
        }

        lastScanResults = new ArrayList<>(results);
        currentResults.clear();
        currentResults.addAll(results);
        selectedIndex = -1;
        adapter.notifyDataSetChanged();
        updateStatusText();
        Log.d(TAG, "First scan complete: " + results.size() + " results");
    }

    private void performNarrowScan() {
        if (lastScanResults.isEmpty()) {
            Toast.makeText(requireContext(), "Run a first scan first", Toast.LENGTH_SHORT).show();
            return;
        }
        String rawValue = etValue.getText().toString().trim();
        if (rawValue.isEmpty()) {
            Toast.makeText(requireContext(), "Enter a value to narrow by", Toast.LENGTH_SHORT).show();
            return;
        }

        int scanType = getSelectedScanType();
        List<RuntimeMemoryEditor.FieldEntry> results;

        try {
            if (scanType == TYPE_INT) {
                int v = Integer.parseInt(rawValue);
                results = editor.narrowSearch(lastScanResults, v);
            } else if (scanType == TYPE_FLOAT) {
                // narrowSearch only supports int; for float we re-scan from lastScanResults manually
                float v = Float.parseFloat(rawValue);
                results = new ArrayList<>();
                for (RuntimeMemoryEditor.FieldEntry e : lastScanResults) {
                    Object val = e.getValue();
                    if (val instanceof Float && Math.abs((Float) val - v) <= 0.001f) {
                        results.add(e);
                    }
                }
            } else {
                // STRING narrow: re-filter from lastScanResults
                results = new ArrayList<>();
                for (RuntimeMemoryEditor.FieldEntry e : lastScanResults) {
                    Object val = e.getValue();
                    if (rawValue.equals(val)) {
                        results.add(e);
                    }
                }
            }
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "Invalid number format", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "performNarrowScan: number parse error: " + e.getMessage());
            return;
        }

        lastScanResults = new ArrayList<>(results);
        currentResults.clear();
        currentResults.addAll(results);
        selectedIndex = -1;
        adapter.notifyDataSetChanged();
        updateStatusText();
        Log.d(TAG, "Narrow scan complete: " + results.size() + " results remaining");
    }

    private void freezeSelected() {
        if (selectedIndex < 0 || selectedIndex >= currentResults.size()) {
            Toast.makeText(requireContext(), "Select a field first (tap item)", Toast.LENGTH_SHORT).show();
            return;
        }
        RuntimeMemoryEditor.FieldEntry entry = currentResults.get(selectedIndex);
        Object currentValue = entry.getValue();
        if (currentValue == null) {
            Toast.makeText(requireContext(), "Cannot freeze — field value is null", Toast.LENGTH_SHORT).show();
            return;
        }
        editor.freezeField(entry, currentValue);
        adapter.notifyDataSetChanged();
        Toast.makeText(requireContext(), "Frozen: " + entry.path, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "freezeSelected: " + entry.path + " = " + currentValue);
    }

    // -------------------------------------------------------------------------
    // Edit value inline dialog
    // -------------------------------------------------------------------------

    /**
     * Shows a small AlertDialog that lets the user manually set the field value
     * and optionally freeze it at that value.
     */
    private void showEditValueDialog(Context ctx, RuntimeMemoryEditor.FieldEntry entry) {
        int dp8 = dp(ctx, 8);

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp8, dp8, dp8, dp8);

        TextView tvPath = new TextView(ctx);
        tvPath.setText(entry.path);
        tvPath.setTypeface(null, Typeface.BOLD);
        tvPath.setPadding(0, 0, 0, dp(ctx, 4));
        layout.addView(tvPath);

        TextView tvCurrent = new TextView(ctx);
        tvCurrent.setText("Current: " + entry.getValue());
        tvCurrent.setTextColor(Color.DKGRAY);
        tvCurrent.setPadding(0, 0, 0, dp(ctx, 4));
        layout.addView(tvCurrent);

        EditText etNew = new EditText(ctx);
        etNew.setHint("New value");
        // Pre-fill with current value
        Object curVal = entry.getValue();
        if (curVal != null) {
            etNew.setText(curVal.toString());
            etNew.selectAll();
        }
        layout.addView(etNew);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("Edit Field")
                .setView(layout)
                .setPositiveButton("Set Value", null)       // set below to prevent auto-dismiss on invalid input
                .setNeutralButton("Set + Freeze", null)
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button btnSet    = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button btnFreeze = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            btnSet.setOnClickListener(v -> {
                if (applyNewValue(ctx, entry, etNew.getText().toString().trim(), false)) {
                    dialog.dismiss();
                }
            });

            btnFreeze.setOnClickListener(v -> {
                if (applyNewValue(ctx, entry, etNew.getText().toString().trim(), true)) {
                    dialog.dismiss();
                }
            });
        });

        dialog.show();
    }

    /**
     * Parses {@code raw} according to the field's actual type, applies the value,
     * and optionally freezes it.
     *
     * @return {@code true} on success, {@code false} on parse / type error.
     */
    private boolean applyNewValue(Context ctx, RuntimeMemoryEditor.FieldEntry entry,
                                   String raw, boolean freeze) {
        if (raw.isEmpty()) {
            Toast.makeText(ctx, "Enter a value", Toast.LENGTH_SHORT).show();
            return false;
        }
        Class<?> fieldType = entry.field.getType();
        Object newValue;
        try {
            if (fieldType == int.class || fieldType == Integer.class) {
                newValue = Integer.parseInt(raw);
            } else if (fieldType == float.class || fieldType == Float.class) {
                newValue = Float.parseFloat(raw);
            } else if (fieldType == long.class || fieldType == Long.class) {
                newValue = Long.parseLong(raw);
            } else if (fieldType == double.class || fieldType == Double.class) {
                newValue = Double.parseDouble(raw);
            } else if (fieldType == short.class || fieldType == Short.class) {
                newValue = Short.parseShort(raw);
            } else if (fieldType == byte.class || fieldType == Byte.class) {
                newValue = Byte.parseByte(raw);
            } else if (fieldType == boolean.class || fieldType == Boolean.class) {
                newValue = Boolean.parseBoolean(raw);
            } else if (fieldType == String.class) {
                newValue = raw;
            } else {
                Toast.makeText(ctx, "Unsupported field type: " + fieldType.getSimpleName(), Toast.LENGTH_SHORT).show();
                return false;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(ctx, "Invalid value for type " + fieldType.getSimpleName(), Toast.LENGTH_SHORT).show();
            Log.w(TAG, "applyNewValue parse error: " + e.getMessage());
            return false;
        }

        entry.setValue(newValue);
        if (freeze) {
            editor.freezeField(entry, newValue);
            Toast.makeText(ctx, "Set + Frozen: " + entry.path, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "applyNewValue + freeze: " + entry.path + " = " + newValue);
        } else {
            Toast.makeText(ctx, "Set: " + entry.path + " = " + newValue, Toast.LENGTH_SHORT).show();
            Log.d(TAG, "applyNewValue: " + entry.path + " = " + newValue);
        }
        adapter.notifyDataSetChanged();
        return true;
    }

    // -------------------------------------------------------------------------
    // Status text
    // -------------------------------------------------------------------------

    private void updateStatusText() {
        if (tvStatus == null) return;
        int total   = currentResults.size();
        int frozen  = countFrozen();
        tvStatus.setText("Results: " + total + "  |  Frozen: " + frozen);
    }

    private int countFrozen() {
        int count = 0;
        for (RuntimeMemoryEditor.FieldEntry e : currentResults) {
            if (editor.isFrozen(e)) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int getSelectedScanType() {
        int checkedId = rgType.getCheckedRadioButtonId();
        if (checkedId == rbFloat.getId())  return TYPE_FLOAT;
        if (checkedId == rbString.getId()) return TYPE_STRING;
        return TYPE_INT;
    }

    private void highlightSelected(int position) {
        // Visual hint: we notify the adapter so it can highlight the selected row
        adapter.setSelectedIndex(position);
        adapter.notifyDataSetChanged();
    }

    private static RadioButton makeRadioButton(Context ctx, String label, int tag) {
        RadioButton rb = new RadioButton(ctx);
        rb.setText(label);
        rb.setTag(tag);
        return rb;
    }

    private static Button makeButton(Context ctx, String label, int hPadding) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextSize(12f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(0, 0, hPadding / 2, 0);
        btn.setLayoutParams(params);
        return btn;
    }

    /** Converts dp to pixels. */
    private static int dp(Context ctx, int dp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // -------------------------------------------------------------------------
    // ListView Adapter
    // -------------------------------------------------------------------------

    /**
     * BaseAdapter that renders each {@link RuntimeMemoryEditor.FieldEntry} as a
     * two-line row with a FROZEN badge when applicable.
     *
     * Row layout (all programmatic):
     *   [ path (bold)           ] [FROZEN badge]
     *   [ current value (gray) ]
     */
    private static class FieldEntryAdapter extends BaseAdapter {

        private final Context ctx;
        private final List<RuntimeMemoryEditor.FieldEntry> items;
        private final RuntimeMemoryEditor editor = RuntimeMemoryEditor.getInstance();
        private int selectedIndex = -1;

        FieldEntryAdapter(Context ctx, List<RuntimeMemoryEditor.FieldEntry> items) {
            this.ctx   = ctx;
            this.items = items;
        }

        void setSelectedIndex(int idx) {
            this.selectedIndex = idx;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public RuntimeMemoryEditor.FieldEntry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = buildItemView(ctx);
                holder = new ViewHolder();
                holder.tvPath    = convertView.findViewWithTag("tvPath");
                holder.tvValue   = convertView.findViewWithTag("tvValue");
                holder.tvFrozen  = convertView.findViewWithTag("tvFrozen");
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            RuntimeMemoryEditor.FieldEntry entry = getItem(position);
            boolean frozen = editor.isFrozen(entry);
            Object val = entry.getValue();

            holder.tvPath.setText(entry.path);
            holder.tvValue.setText("= " + (val != null ? val.toString() : "null"));

            if (frozen) {
                holder.tvFrozen.setVisibility(View.VISIBLE);
            } else {
                holder.tvFrozen.setVisibility(View.GONE);
            }

            // Highlight selected row
            if (position == selectedIndex) {
                convertView.setBackgroundColor(Color.argb(40, 0, 120, 255));
            } else {
                convertView.setBackgroundColor(Color.TRANSPARENT);
            }

            return convertView;
        }

        private static View buildItemView(Context ctx) {
            int dp4 = Math.round(4 * ctx.getResources().getDisplayMetrics().density);
            int dp8 = Math.round(8 * ctx.getResources().getDisplayMetrics().density);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp8, dp4, dp8, dp4);
            row.setGravity(Gravity.CENTER_VERTICAL);

            // Left column: path + value
            LinearLayout leftCol = new LinearLayout(ctx);
            leftCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            leftCol.setLayoutParams(leftParams);

            TextView tvPath = new TextView(ctx);
            tvPath.setTag("tvPath");
            tvPath.setTypeface(null, Typeface.BOLD);
            tvPath.setTextSize(12f);
            tvPath.setSingleLine(true);
            tvPath.setEllipsize(android.text.TextUtils.TruncateAt.START);
            leftCol.addView(tvPath);

            TextView tvValue = new TextView(ctx);
            tvValue.setTag("tvValue");
            tvValue.setTextColor(Color.DKGRAY);
            tvValue.setTextSize(11f);
            leftCol.addView(tvValue);

            row.addView(leftCol);

            // Right: FROZEN badge
            TextView tvFrozen = new TextView(ctx);
            tvFrozen.setTag("tvFrozen");
            tvFrozen.setText("FROZEN");
            tvFrozen.setTextColor(Color.WHITE);
            tvFrozen.setBackgroundColor(Color.rgb(220, 50, 50));
            tvFrozen.setTextSize(9f);
            tvFrozen.setTypeface(null, Typeface.BOLD);
            tvFrozen.setPadding(dp4, 2, dp4, 2);
            tvFrozen.setVisibility(View.GONE);
            row.addView(tvFrozen);

            return row;
        }

        private static class ViewHolder {
            TextView tvPath;
            TextView tvValue;
            TextView tvFrozen;
        }
    }
}
