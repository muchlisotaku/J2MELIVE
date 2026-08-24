package javax.microedition.shell;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.microedition.util.StringEditorManager;
import ru.playsoftware.j2meloader.R;

/**
 * LiveStringEditorDialog — Upgraded v2
 *
 * Perubahan dari v1:
 *  - Frame-sync: daftar string di-refresh setiap frame baru selesai dirender
 *    (via OnFrameListener), bukan hanya saat user mengetuk UI.
 *  - Active highlight: string yang aktif di frame terakhir ditampilkan dengan
 *    warna hijau dan badge "LIVE" berkedip.
 *  - Frame counter di header: menampilkan nomor frame saat ini secara live.
 *  - Throttled refresh: UI tidak di-redraw lebih dari ~30fps untuk efisiensi.
 */
public class LiveStringEditorDialog extends DialogFragment
        implements StringEditorManager.OnFrameListener {

    public static final String TAG = "LiveStringEditorDialog";

    // ── State ────────────────────────────────────────────────────────────────
    private final StringEditorManager manager = StringEditorManager.getInstance();
    private StringAdapter adapter;
    private TextView tvStats;
    private TextView tvFrameCounter;
    private boolean showModifiedOnly = false;
    private String  currentFilter    = "";

    // Throttle: UI refresh tidak lebih dari setiap 33ms (~30fps)
    private static final long REFRESH_INTERVAL_MS = 33;
    private long lastRefreshMs = 0;

    // ── Factory ──────────────────────────────────────────────────────────────
    public static LiveStringEditorDialog newInstance() {
        return new LiveStringEditorDialog();
    }

    // =========================================================================
    // Dialog creation
    // =========================================================================

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = requireActivity();
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_live_string_editor, null);

        // ── Bind views ───────────────────────────────────────────────────────
        EditText   searchEdit   = view.findViewById(R.id.search_edit_text);
        RadioGroup filterGroup  = view.findViewById(R.id.filter_radio_group);
        CheckBox   cbSubstring  = view.findViewById(R.id.checkbox_substring);
        tvStats                 = view.findViewById(R.id.tv_stats);
        ListView   listView     = view.findViewById(R.id.string_list_view);
        Button     btnClear     = view.findViewById(R.id.btn_clear_history);
        Button     btnExport    = view.findViewById(R.id.btn_export);
        Button     btnImport    = view.findViewById(R.id.btn_import);
        Button     btnClose     = view.findViewById(R.id.btn_close);

        // ── Frame counter TextView (injected programmatically if needed) ─────
        tvFrameCounter = view.findViewWithTag("tv_frame_counter");
        if (tvFrameCounter == null) {
            // Create and insert above stats if layout doesn't have it yet
            tvFrameCounter = new TextView(context);
            tvFrameCounter.setTag("tv_frame_counter");
            tvFrameCounter.setTextSize(10f);
            tvFrameCounter.setTypeface(Typeface.MONOSPACE);
            tvFrameCounter.setTextColor(0xFF888888);
            tvFrameCounter.setPadding(4, 2, 4, 0);
            if (tvStats != null && tvStats.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) tvStats.getParent();
                int idx = parent.indexOfChild(tvStats);
                parent.addView(tvFrameCounter, idx);
            }
        }

        // ── Checkbox: substring mode ─────────────────────────────────────────
        cbSubstring.setChecked(manager.isSubstringMatchEnabled());
        cbSubstring.setOnCheckedChangeListener((btn, checked) ->
                manager.setSubstringMatchEnabled(checked));

        // ── ListView ─────────────────────────────────────────────────────────
        adapter = new StringAdapter(context);
        listView.setAdapter(adapter);

        // ── Search filter ────────────────────────────────────────────────────
        searchEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int st, int before, int count) {
                currentFilter = s == null ? "" : s.toString().trim().toLowerCase();
                forceRefresh();
            }
        });

        // ── Radio: All / Modified ─────────────────────────────────────────────
        filterGroup.setOnCheckedChangeListener((group, checkedId) -> {
            showModifiedOnly = (checkedId == R.id.radio_modified);
            forceRefresh();
        });

        // ── Item click → edit dialog ──────────────────────────────────────────
        listView.setOnItemClickListener((parent, v, pos, id) -> {
            StringRecordItem item = adapter.getItem(pos);
            if (item != null) showEditSingleStringDialog(item.original);
        });

        // ── Buttons ───────────────────────────────────────────────────────────
        btnClear.setOnClickListener(v -> {
            manager.clearCaptured();
            forceRefresh();
            Toast.makeText(context, R.string.string_editor_clear_captured,
                    Toast.LENGTH_SHORT).show();
        });
        btnExport.setOnClickListener(v -> exportStrings());
        btnImport.setOnClickListener(v -> showImportDialog());
        btnClose.setOnClickListener(v -> dismiss());

        // ── Initial data load ─────────────────────────────────────────────────
        forceRefresh();

        return new AlertDialog.Builder(context)
                .setTitle(R.string.string_editor_title)
                .setView(view)
                .create();
    }

    // =========================================================================
    // Frame listener lifecycle
    // =========================================================================

    @Override
    public void onResume() {
        super.onResume();
        // Register for per-frame notifications
        manager.addFrameListener(this);
        manager.addListener(this::forceRefresh);
    }

    @Override
    public void onPause() {
        manager.removeFrameListener(this);
        super.onPause();
    }

    /**
     * Called by StringEditorManager every time a new game frame finishes.
     * Runs on main thread (guaranteed by StringEditorManager).
     * Throttled to ~30fps to avoid hammering the ListView adapter.
     */
    @Override
    public void onFrameRendered(long frameNumber) {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            lastRefreshMs = now;
            refreshData(frameNumber);
        }
    }

    // =========================================================================
    // Data refresh
    // =========================================================================

    /** Force immediate refresh regardless of throttle. */
    private void forceRefresh() {
        lastRefreshMs = 0;
        refreshData(manager.getFrameCount());
    }

    private void refreshData(long frameNumber) {
        if (adapter == null) return;

        // ── Update frame counter display ─────────────────────────────────────
        if (tvFrameCounter != null) {
            tvFrameCounter.setText("Frame #" + frameNumber
                    + "  |  Active: " + manager.getActiveFrameStrings().size());
        }

        // ── Get data sorted with frame-active first ───────────────────────────
        List<StringEditorManager.StringRecord> captured =
                manager.getCapturedListWithFrameInfo();
        Map<String, String> reps   = manager.getReplacements();
        long lastFrame             = manager.getFrameCount();

        // ── Apply filters ─────────────────────────────────────────────────────
        List<StringRecordItem> filtered = new ArrayList<>();

        for (StringEditorManager.StringRecord r : captured) {
            String orig     = r.original;
            String repl     = reps.get(orig);
            boolean isModif = (repl != null);
            boolean isLive  = (r.frameLastSeen == lastFrame);

            if (showModifiedOnly && !isModif) continue;

            if (!currentFilter.isEmpty()) {
                boolean matchOrig = orig.toLowerCase().contains(currentFilter);
                boolean matchRepl = repl != null && repl.toLowerCase().contains(currentFilter);
                if (!matchOrig && !matchRepl) continue;
            }

            filtered.add(new StringRecordItem(orig, repl, r.count, isLive));
        }

        // Replacements not in captured list (edge case)
        if (showModifiedOnly || !currentFilter.isEmpty()) {
            for (Map.Entry<String, String> entry : reps.entrySet()) {
                String key = entry.getKey();
                boolean already = false;
                for (StringRecordItem item : filtered) {
                    if (item.original.equals(key)) { already = true; break; }
                }
                if (!already) {
                    boolean matchKey = currentFilter.isEmpty()
                            || key.toLowerCase().contains(currentFilter)
                            || entry.getValue().toLowerCase().contains(currentFilter);
                    if (matchKey) {
                        filtered.add(0, new StringRecordItem(key, entry.getValue(), 0, false));
                    }
                }
            }
        }

        adapter.setItems(filtered);

        // ── Stats line ────────────────────────────────────────────────────────
        if (tvStats != null) {
            long liveCount = filtered.stream().filter(i -> i.isLive).count();
            tvStats.setText(
                    String.format(getString(R.string.string_editor_all_strings), captured.size())
                    + " | "
                    + String.format(getString(R.string.string_editor_modified_only), reps.size())
                    + " | Live: " + liveCount
            );
        }
    }

    // =========================================================================
    // Edit single string dialog
    // =========================================================================

    private void showEditSingleStringDialog(String originalText) {
        Context context = requireActivity();
        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_edit_single_string, null);

        TextView tvOriginal     = view.findViewById(R.id.tv_dialog_original);
        EditText etReplacement  = view.findViewById(R.id.et_dialog_replacement);
        Button   btnReset       = view.findViewById(R.id.btn_dialog_reset);
        Button   btnCancel      = view.findViewById(R.id.btn_dialog_cancel);
        Button   btnApply       = view.findViewById(R.id.btn_dialog_apply);

        tvOriginal.setText(originalText);

        // Show live badge if active this frame
        if (manager.isActiveThisFrame(originalText)) {
            SpannableString ss = new SpannableString("  [LIVE]  " + originalText);
            ss.setSpan(new ForegroundColorSpan(0xFF4CAF50), 2, 8,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tvOriginal.setText(ss);
        }

        String currentReplacement = manager.getReplacements().get(originalText);
        if (currentReplacement != null) {
            etReplacement.setText(currentReplacement);
            etReplacement.setSelection(currentReplacement.length());
            btnReset.setVisibility(View.VISIBLE);
        } else {
            etReplacement.setText(originalText);
            etReplacement.selectAll();
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.string_editor_edit_title)
                .setView(view)
                .create();

        btnReset.setOnClickListener(v -> {
            manager.removeReplacement(originalText);
            forceRefresh();
            dialog.dismiss();
        });
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnApply.setOnClickListener(v -> {
            String newText = etReplacement.getText().toString();
            if (!TextUtils.isEmpty(newText)) {
                manager.setReplacement(originalText, newText);
            } else {
                manager.removeReplacement(originalText);
            }
            forceRefresh();
            dialog.dismiss();
        });

        dialog.show();
    }

    // =========================================================================
    // Export / Import
    // =========================================================================

    private void exportStrings() {
        try {
            Context context = requireActivity();
            String json = manager.exportToJsonString();

            ClipboardManager cb = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("J2ME String Overrides", json));
            }

            File dl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "j2me_strings_" + System.currentTimeMillis() + ".json");
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(out), StandardCharsets.UTF_8)) {
                w.write(json);
            }
            Toast.makeText(context,
                    String.format(getString(R.string.string_editor_exported), out.getName())
                            + " (Copied to Clipboard)",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(requireActivity(), "Export error: " + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showImportDialog() {
        Context context = requireActivity();
        EditText input = new EditText(context);
        input.setHint("Paste JSON here...");
        input.setMinLines(5);

        ClipboardManager cb = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence clip = cb.getPrimaryClip().getItemAt(0).getText();
            if (clip != null && clip.toString().contains("replacements")) {
                input.setText(clip);
            }
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.string_editor_import)
                .setView(input)
                .setPositiveButton("Import", (d, w) -> {
                    String json = input.getText().toString();
                    if (manager.importFromJsonString(json, true)) {
                        forceRefresh();
                        Toast.makeText(context, R.string.string_editor_imported,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, R.string.string_editor_import_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // =========================================================================
    // Data model
    // =========================================================================

    private static class StringRecordItem {
        final String  original;
        final String  replaced;
        final int     count;
        final boolean isLive;  // NEW: active in the most recent frame

        StringRecordItem(String original, String replaced, int count, boolean isLive) {
            this.original = original;
            this.replaced = replaced;
            this.count    = count;
            this.isLive   = isLive;
        }
    }

    // =========================================================================
    // List adapter
    // =========================================================================

    private static class StringAdapter extends BaseAdapter {
        private final Context context;
        private final List<StringRecordItem> items = new ArrayList<>();

        StringAdapter(Context ctx) { this.context = ctx; }

        void setItems(List<StringRecordItem> list) {
            items.clear();
            if (list != null) items.addAll(list);
            notifyDataSetChanged();
        }

        @Override public int     getCount()                    { return items.size(); }
        @Override public StringRecordItem getItem(int pos)     { return items.get(pos); }
        @Override public long    getItemId(int pos)            { return pos; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context)
                        .inflate(R.layout.item_captured_string, parent, false);
            }

            StringRecordItem item = getItem(position);
            TextView tvOriginal = convertView.findViewById(R.id.tv_original_string);
            TextView tvReplaced = convertView.findViewById(R.id.tv_replaced_string);
            TextView tvCount    = convertView.findViewById(R.id.tv_count_badge);

            // ── Highlight LIVE strings ────────────────────────────────────────
            if (item.isLive) {
                // Green tint background for active-this-frame rows
                convertView.setBackgroundColor(0x1A4CAF50); // semi-transparent green
                // Prepend [●] indicator to original text
                SpannableString ss = new SpannableString("● " + item.original);
                ss.setSpan(new ForegroundColorSpan(0xFF4CAF50), 0, 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvOriginal.setText(ss);
                tvOriginal.setTypeface(null, Typeface.BOLD);
            } else {
                convertView.setBackgroundColor(Color.TRANSPARENT);
                tvOriginal.setText(item.original);
                tvOriginal.setTypeface(null, Typeface.NORMAL);
            }

            // ── Replacement preview ───────────────────────────────────────────
            if (item.replaced != null) {
                tvReplaced.setVisibility(View.VISIBLE);
                tvReplaced.setText("➔ " + item.replaced);
            } else {
                tvReplaced.setVisibility(View.GONE);
            }

            // ── Count / status badge ──────────────────────────────────────────
            if (item.isLive && item.count > 0) {
                tvCount.setText("LIVE " + item.count + "x");
                tvCount.setTextColor(0xFF4CAF50);
            } else if (item.count > 0) {
                tvCount.setText(item.count + "x");
                tvCount.setTextColor(0xFF888888);
            } else {
                tvCount.setText("mod");
                tvCount.setTextColor(0xFFFF9800);
            }

            return convertView;
        }
    }
}
