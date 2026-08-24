/*
 * PngInjectorDialog.java
 * UI Dialog for managing LivePngInjector resource overrides.
 *
 * Shows all currently injected resources, allows adding new injections
 * from a file path, removing individual entries, and clearing all.
 */

package javax.microedition.shell;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.microedition.util.LivePngInjector;

/**
 * DialogFragment that provides a simple UI to manage LivePngInjector overrides.
 *
 * Features:
 *  - ListView showing every injected resource with path and byte size
 *  - "Inject PNG dari File" button — opens a dialog to enter resource path + file path
 *  - "Clear All" button — removes all active injections
 *  - Long-tap (or tap) on a list item — prompts to remove that single injection
 *  - Header shows total number of active injections
 */
public class PngInjectorDialog extends DialogFragment {

    public static final String TAG = "PngInjectorDialog";

    // ------------------------------------------------------------------ //
    //  Factory                                                             //
    // ------------------------------------------------------------------ //

    /**
     * Create a new instance of PngInjectorDialog.
     */
    public static PngInjectorDialog newInstance() {
        return new PngInjectorDialog();
    }

    // ------------------------------------------------------------------ //
    //  State                                                               //
    // ------------------------------------------------------------------ //

    /** Flat list of display strings: "path  (N bytes)" */
    private final List<String> mDisplayList = new ArrayList<>();
    /** Parallel list of normalized resource paths corresponding to mDisplayList entries. */
    private final List<String> mPathList = new ArrayList<>();

    private ArrayAdapter<String> mAdapter;
    private TextView mTotalTextView;

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                           //
    // ------------------------------------------------------------------ //

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = requireContext();

        // Root layout
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(context, 12);
        root.setPadding(padding, padding, padding, padding);

        // --- Total header ---
        mTotalTextView = new TextView(context);
        mTotalTextView.setTextSize(14f);
        mTotalTextView.setPadding(0, 0, 0, dpToPx(context, 8));
        root.addView(mTotalTextView);

        // --- ListView ---
        ListView listView = new ListView(context);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(context, 240));
        listView.setLayoutParams(listParams);
        root.addView(listView);

        // Adapter
        mAdapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, mDisplayList);
        listView.setAdapter(mAdapter);

        // Tap item → remove confirmation
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= mPathList.size()) return;
                final String path = mPathList.get(position);
                showRemoveConfirmDialog(path);
            }
        });

        // --- Divider ---
        View divider = new View(context);
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = dpToPx(context, 8);
        dividerParams.bottomMargin = dpToPx(context, 8);
        divider.setLayoutParams(dividerParams);
        root.addView(divider);

        // --- Button row ---
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);

        Button injectButton = new Button(context);
        injectButton.setText("Inject PNG dari File");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.rightMargin = dpToPx(context, 4);
        injectButton.setLayoutParams(btnParams);
        injectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInjectFileDialog();
            }
        });
        buttonRow.addView(injectButton);

        Button clearButton = new Button(context);
        clearButton.setText("Clear All");
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clearParams.leftMargin = dpToPx(context, 4);
        clearButton.setLayoutParams(clearParams);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showClearAllConfirmDialog();
            }
        });
        buttonRow.addView(clearButton);

        root.addView(buttonRow);

        // Populate initial data
        refreshList();

        // Build the dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("LivePngInjector");
        builder.setView(root);
        builder.setNegativeButton("Tutup", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });

        return builder.create();
    }

    // ------------------------------------------------------------------ //
    //  Data helpers                                                        //
    // ------------------------------------------------------------------ //

    /**
     * Refresh the list view to reflect the current state of LivePngInjector.
     */
    private void refreshList() {
        mDisplayList.clear();
        mPathList.clear();

        LivePngInjector injector = LivePngInjector.getInstance();
        Set<String> paths = injector.getInjectedPaths();

        List<String> sortedPaths = new ArrayList<>(paths);
        Collections.sort(sortedPaths);

        for (String path : sortedPaths) {
            byte[] bytes = injector.getInjectedBytes(path);
            int size = (bytes != null) ? bytes.length : 0;
            mDisplayList.add(path + "  (" + size + " bytes)");
            mPathList.add(path);
        }

        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }

        updateTotalHeader(sortedPaths.size());
    }

    private void updateTotalHeader(int count) {
        if (mTotalTextView != null) {
            mTotalTextView.setText("Total injected resources: " + count);
        }
    }

    // ------------------------------------------------------------------ //
    //  Sub-dialogs                                                         //
    // ------------------------------------------------------------------ //

    /**
     * Show a dialog asking the user for:
     *  1. Resource path (as used by the game, e.g. /sprites/player.png)
     *  2. Source file path on device storage (e.g. /sdcard/Download/player.png)
     */
    private void showInjectFileDialog() {
        Context context = requireContext();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(context, 16);
        layout.setPadding(padding, padding, padding, 0);

        // Resource path input
        TextView resLabel = new TextView(context);
        resLabel.setText("Resource path (dalam game):");
        layout.addView(resLabel);

        final EditText resPathEdit = new EditText(context);
        resPathEdit.setHint("Contoh: /sprites/hero.png");
        resPathEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(resPathEdit);

        // File path input
        TextView fileLabel = new TextView(context);
        fileLabel.setText("Path file sumber di perangkat:");
        fileLabel.setPadding(0, dpToPx(context, 8), 0, 0);
        layout.addView(fileLabel);

        final EditText filePathEdit = new EditText(context);
        filePathEdit.setHint("Contoh: /sdcard/Download/hero.png");
        filePathEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(filePathEdit);

        new AlertDialog.Builder(context)
                .setTitle("Inject PNG dari File")
                .setView(layout)
                .setPositiveButton("Inject", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String resPath = resPathEdit.getText().toString().trim();
                        String filePath = filePathEdit.getText().toString().trim();

                        if (resPath.isEmpty()) {
                            Toast.makeText(context, "Resource path tidak boleh kosong", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (filePath.isEmpty()) {
                            Toast.makeText(context, "Path file sumber tidak boleh kosong", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        performInjectFromFile(resPath, filePath);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Execute the actual injection and handle errors gracefully.
     */
    private void performInjectFromFile(String resPath, String filePath) {
        Context context = requireContext();
        try {
            File sourceFile = new File(filePath);
            LivePngInjector.getInstance().injectPngFromFile(resPath, sourceFile);
            Toast.makeText(context,
                    "Berhasil inject: " + resPath,
                    Toast.LENGTH_SHORT).show();
            Log.d(TAG, "performInjectFromFile: success resPath=" + resPath + " file=" + filePath);
            refreshList();
        } catch (Exception e) {
            Log.e(TAG, "performInjectFromFile: failed", e);
            Toast.makeText(context,
                    "Gagal inject: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Confirm before removing a single injection.
     */
    private void showRemoveConfirmDialog(final String path) {
        Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("Hapus Injection")
                .setMessage("Hapus override untuk:\n" + path + "?")
                .setPositiveButton("Hapus", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LivePngInjector.getInstance().removeInjection(path);
                        Toast.makeText(context,
                                "Injection dihapus: " + path,
                                Toast.LENGTH_SHORT).show();
                        refreshList();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Confirm before clearing all injections.
     */
    private void showClearAllConfirmDialog() {
        Context context = requireContext();
        int count = LivePngInjector.getInstance().getInjectionCount();
        new AlertDialog.Builder(context)
                .setTitle("Clear All Injections")
                .setMessage("Hapus semua " + count + " injection? Tindakan ini tidak dapat dibatalkan.")
                .setPositiveButton("Hapus Semua", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        LivePngInjector.getInstance().clearAll();
                        Toast.makeText(context,
                                "Semua injection telah dihapus",
                                Toast.LENGTH_SHORT).show();
                        refreshList();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ //
    //  Utilities                                                           //
    // ------------------------------------------------------------------ //

    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
