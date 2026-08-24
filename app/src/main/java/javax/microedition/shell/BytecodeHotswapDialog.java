/*
 * BytecodeHotswapDialog - UI for BytecodeHotswap (Dynamic Bytecode Injection)
 * J2ME-Loader-LiveEditor
 *
 * This dialog allows the user to load a patch DEX file at runtime and hotswap
 * a specific class. See BytecodeHotswap.java for limitations regarding ART/DVM.
 */

package javax.microedition.shell;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import java.io.File;
import java.util.List;

import javax.microedition.util.BytecodeHotswap;

/**
 * A {@link DialogFragment} that provides a UI for the
 * {@link BytecodeHotswap} feature.
 *
 * <h3>What it does</h3>
 * <ul>
 *   <li>Lets the user enter the path to a patch DEX file on the device.</li>
 *   <li>Lets the user enter the fully-qualified class name to hotswap.</li>
 *   <li>Loads the class from the patch DEX and registers it via
 *       {@link BytecodeHotswap#hotswapClass(String, File)}.</li>
 *   <li>Shows a list of all currently hotswapped classes.</li>
 *   <li>Provides a button to clear all hotswaps at once.</li>
 * </ul>
 *
 * <h3>Important warning (shown to user)</h3>
 * Hotswap is only effective for new object instances. Existing objects
 * already in memory continue to use their original class.
 */
public class BytecodeHotswapDialog extends DialogFragment {

    /** Fragment / back-stack tag. */
    public static final String TAG = "BytecodeHotswapDialog";

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private EditText etDexPath;
    private EditText etClassName;
    private TextView tvStatus;
    private ListView lvHotswapped;
    private ArrayAdapter<String> listAdapter;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a new instance of this dialog.
     *
     * @return a freshly constructed {@link BytecodeHotswapDialog}
     */
    public static BytecodeHotswapDialog newInstance() {
        return new BytecodeHotswapDialog();
    }

    // -------------------------------------------------------------------------
    // DialogFragment lifecycle
    // -------------------------------------------------------------------------

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context context = requireActivity();

        // ── Build the layout programmatically (no XML resource required) ──
        View root = buildView(context);

        return new AlertDialog.Builder(context)
                .setTitle("Bytecode Hotswap")
                .setView(root)
                .setNegativeButton("Close", (d, w) -> dismiss())
                .create();
    }

    // -------------------------------------------------------------------------
    // Layout construction
    // -------------------------------------------------------------------------

    /**
     * Builds the dialog content view entirely in code to avoid a dependency on
     * a layout XML resource (keeping this feature self-contained).
     */
    private View buildView(Context context) {
        // Root scroll + linear layout
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        android.widget.LinearLayout root = new android.widget.LinearLayout(context);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // ── Instruction text ──────────────────────────────────────────────────
        TextView tvInstruction = new TextView(context);
        tvInstruction.setText(
                "Bytecode Hotswap memuat ulang sebuah class dari file DEX patch.\n\n" +
                "CATATAN PENTING: DVM/ART tidak mendukung true class redefinition. " +
                "Hotswap hanya berlaku untuk objek BARU yang dibuat setelah patch diterapkan. " +
                "Objek yang sudah ada di memori TIDAK akan berubah.\n\n" +
                "Untuk efek penuh, restart MIDlet setelah hotswap."
        );
        tvInstruction.setTextSize(13f);
        root.addView(tvInstruction, linearParams(0));

        addVerticalSpace(root, context, 12);

        // ── DEX path input ────────────────────────────────────────────────────
        TextView tvDexLabel = new TextView(context);
        tvDexLabel.setText("Path file DEX patch:");
        root.addView(tvDexLabel, linearParams(0));

        etDexPath = new EditText(context);
        etDexPath.setHint("/sdcard/patch.dex");
        etDexPath.setSingleLine(true);
        root.addView(etDexPath, linearParams(0));

        addVerticalSpace(root, context, 8);

        // ── Class name input ──────────────────────────────────────────────────
        TextView tvClassLabel = new TextView(context);
        tvClassLabel.setText("Nama class yang di-hotswap (fully qualified):");
        root.addView(tvClassLabel, linearParams(0));

        etClassName = new EditText(context);
        etClassName.setHint("com.game.MainClass");
        etClassName.setSingleLine(true);
        root.addView(etClassName, linearParams(0));

        addVerticalSpace(root, context, 12);

        // ── Action buttons ────────────────────────────────────────────────────
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(context);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);

        Button btnLoad = new Button(context);
        btnLoad.setText("Load DEX Patch");
        android.widget.LinearLayout.LayoutParams btnParams =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnParams.setMarginEnd(dp(context, 4));
        btnRow.addView(btnLoad, btnParams);

        Button btnClear = new Button(context);
        btnClear.setText("Clear All Hotswap");
        android.widget.LinearLayout.LayoutParams btnParams2 =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        btnRow.addView(btnClear, btnParams2);

        root.addView(btnRow, linearParams(0));

        addVerticalSpace(root, context, 12);

        // ── Status / result text ──────────────────────────────────────────────
        tvStatus = new TextView(context);
        tvStatus.setText("Siap. Masukkan path DEX dan nama class lalu tekan 'Load DEX Patch'.");
        tvStatus.setTextSize(12f);
        root.addView(tvStatus, linearParams(0));

        addVerticalSpace(root, context, 8);

        // ── Hotswapped class list ─────────────────────────────────────────────
        TextView tvListLabel = new TextView(context);
        tvListLabel.setText("Class yang sudah di-hotswap:");
        root.addView(tvListLabel, linearParams(0));

        listAdapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1);
        lvHotswapped = new ListView(context);
        lvHotswapped.setAdapter(listAdapter);

        // Fixed height so it sits nicely inside the ScrollView
        android.widget.LinearLayout.LayoutParams listParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(context, 160));
        root.addView(lvHotswapped, listParams);

        // ── Wire up buttons ───────────────────────────────────────────────────
        btnLoad.setOnClickListener(v -> onLoadDexPatchClicked());
        btnClear.setOnClickListener(v -> onClearAllClicked());

        // Initial list refresh
        refreshList();

        return scroll;
    }

    // -------------------------------------------------------------------------
    // Button handlers
    // -------------------------------------------------------------------------

    /**
     * Called when the user taps "Load DEX Patch".
     * Shows a warning dialog before proceeding.
     */
    private void onLoadDexPatchClicked() {
        String dexPath  = etDexPath.getText().toString().trim();
        String className = etClassName.getText().toString().trim();

        if (TextUtils.isEmpty(dexPath)) {
            setStatus("ERROR: Path file DEX tidak boleh kosong.");
            return;
        }
        if (TextUtils.isEmpty(className)) {
            setStatus("ERROR: Nama class tidak boleh kosong.");
            return;
        }

        // Show mandatory warning before applying the hotswap
        showWarningAndProceed(dexPath, className);
    }

    /**
     * Presents the mandatory warning dialog before applying the hotswap, as
     * required by the feature specification.
     */
    private void showWarningAndProceed(String dexPath, String className) {
        Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("Peringatan — Hotswap")
                .setMessage(
                        "Hotswap hanya berlaku untuk objek baru. " +
                        "Objek yang sudah ada tidak berubah.\n\n" +
                        "Lanjutkan hotswap untuk class:\n" + className + " ?")
                .setPositiveButton("Ya, Lanjutkan", (d, w) ->
                        applyHotswap(dexPath, className))
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Executes the actual hotswap on a background thread to avoid blocking the
     * UI while the DEX is being loaded and optimised.
     */
    private void applyHotswap(String dexPath, String className) {
        setStatus("Memuat patch DEX, harap tunggu...");

        new Thread(() -> {
            String resultMsg;
            boolean success = false;
            try {
                File dexFile = new File(dexPath);
                Class<?> patched = BytecodeHotswap.getInstance()
                        .hotswapClass(className, dexFile);
                success = true;
                resultMsg = "SUKSES: Class '" + className + "' berhasil di-hotswap.\n"
                        + "ClassLoader: " + patched.getClassLoader().getClass().getSimpleName() + "\n"
                        + "INGAT: Hanya objek baru yang akan menggunakan bytecode baru.";
                Log.i(TAG, "Hotswap applied: " + className);
            } catch (ClassNotFoundException e) {
                resultMsg = "GAGAL: Class '" + className
                        + "' tidak ditemukan di dalam DEX file.\nDetail: " + e.getMessage();
                Log.e(TAG, "hotswapClass ClassNotFoundException", e);
            } catch (IllegalArgumentException e) {
                resultMsg = "GAGAL: Argumen tidak valid.\nDetail: " + e.getMessage();
                Log.e(TAG, "hotswapClass IllegalArgumentException", e);
            } catch (Throwable e) {
                resultMsg = "GAGAL: " + e.getClass().getSimpleName()
                        + " — " + e.getMessage();
                Log.e(TAG, "hotswapClass unexpected error", e);
            }

            final String finalMsg = resultMsg;
            final boolean finalSuccess = success;

            // Post UI updates back to the main thread
            android.os.Handler mainHandler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
            mainHandler.post(() -> {
                setStatus(finalMsg);
                refreshList();
                if (finalSuccess) {
                    Toast.makeText(requireContext(),
                            "Hotswap sukses: " + className,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }, "BytecodeHotswap-Loader").start();
    }

    /**
     * Clears all registered hotswaps after a confirmation dialog.
     */
    private void onClearAllClicked() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Hapus Semua Hotswap")
                .setMessage("Hapus semua registrasi hotswap class?\n\n"
                        + "Objek yang sudah ada tidak akan terpengaruh.")
                .setPositiveButton("Ya, Hapus Semua", (d, w) -> {
                    BytecodeHotswap.getInstance().clearAll();
                    refreshList();
                    setStatus("Semua hotswap telah dihapus dari registry.");
                    Toast.makeText(requireContext(),
                            "Semua hotswap dihapus.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    /** Updates the status text view. */
    private void setStatus(String message) {
        if (tvStatus != null) {
            tvStatus.setText(message);
        }
    }

    /** Refreshes the ListView with the current hotswapped class list. */
    private void refreshList() {
        if (listAdapter == null) return;
        listAdapter.clear();
        List<String> list = BytecodeHotswap.getInstance().getHotswappedList();
        if (list.isEmpty()) {
            listAdapter.add("(belum ada class yang di-hotswap)");
        } else {
            for (String name : list) {
                listAdapter.add(name);
            }
        }
        listAdapter.notifyDataSetChanged();
    }

    /** Returns a {@link android.widget.LinearLayout.LayoutParams} with MATCH_PARENT width. */
    private static android.widget.LinearLayout.LayoutParams linearParams(int heightDp) {
        if (heightDp <= 0) {
            return new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        return new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, heightDp);
    }

    /** Adds a vertical space view. */
    private static void addVerticalSpace(android.widget.LinearLayout parent,
                                         Context context, int heightDp) {
        View space = new View(context);
        parent.addView(space, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)));
    }

    /** Converts dp to pixels. */
    private static int dp(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
