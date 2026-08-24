package javax.microedition.shell;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
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

import javax.microedition.util.StringEditorManager;
import ru.playsoftware.j2meloader.R;

public class LiveStringEditorDialog extends DialogFragment {
	public static final String TAG = "LiveStringEditorDialog";

	private final StringEditorManager manager = StringEditorManager.getInstance();
	private StringAdapter adapter;
	private TextView tvStats;
	private boolean showModifiedOnly = false;
	private String currentFilter = "";

	public static LiveStringEditorDialog newInstance() {
		return new LiveStringEditorDialog();
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		Context context = requireActivity();
		View view = LayoutInflater.from(context).inflate(R.layout.dialog_live_string_editor, null);

		EditText searchEdit = view.findViewById(R.id.search_edit_text);
		RadioGroup filterGroup = view.findViewById(R.id.filter_radio_group);
		RadioButton radioAll = view.findViewById(R.id.radio_all);
		RadioButton radioModified = view.findViewById(R.id.radio_modified);
		CheckBox cbSubstring = view.findViewById(R.id.checkbox_substring);
		tvStats = view.findViewById(R.id.tv_stats);
		ListView listView = view.findViewById(R.id.string_list_view);

		Button btnClear = view.findViewById(R.id.btn_clear_history);
		Button btnExport = view.findViewById(R.id.btn_export);
		Button btnImport = view.findViewById(R.id.btn_import);
		Button btnClose = view.findViewById(R.id.btn_close);

		cbSubstring.setChecked(manager.isSubstringMatchEnabled());
		cbSubstring.setOnCheckedChangeListener((buttonView, isChecked) -> manager.setSubstringMatchEnabled(isChecked));

		adapter = new StringAdapter(context);
		listView.setAdapter(adapter);

		searchEdit.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				currentFilter = s == null ? "" : s.toString().trim().toLowerCase();
				refreshData();
			}

			@Override
			public void afterTextChanged(Editable s) {}
		});

		filterGroup.setOnCheckedChangeListener((group, checkedId) -> {
			showModifiedOnly = (checkedId == R.id.radio_modified);
			refreshData();
		});

		listView.setOnItemClickListener((parent, v, position, id) -> {
			StringRecordItem item = adapter.getItem(position);
			if (item != null) {
				showEditSingleStringDialog(item.original);
			}
		});

		btnClear.setOnClickListener(v -> {
			manager.clearCaptured();
			refreshData();
			Toast.makeText(context, R.string.string_editor_clear_captured, Toast.LENGTH_SHORT).show();
		});

		btnExport.setOnClickListener(v -> exportStrings());
		btnImport.setOnClickListener(v -> showImportDialog());
		btnClose.setOnClickListener(v -> dismiss());

		refreshData();

		return new AlertDialog.Builder(context)
				.setTitle(R.string.string_editor_title)
				.setView(view)
				.create();
	}

	private void refreshData() {
		if (adapter == null) return;
		List<StringEditorManager.StringRecord> captured = manager.getCapturedList();
		Map<String, String> replacements = manager.getReplacements();

		List<StringRecordItem> filtered = new ArrayList<>();
		for (StringEditorManager.StringRecord r : captured) {
			String orig = r.original;
			String repl = replacements.get(orig);
			boolean isModified = repl != null;

			if (showModifiedOnly && !isModified) {
				continue;
			}

			if (!currentFilter.isEmpty()) {
				boolean matchOrig = orig.toLowerCase().contains(currentFilter);
				boolean matchRepl = repl != null && repl.toLowerCase().contains(currentFilter);
				if (!matchOrig && !matchRepl) {
					continue;
				}
			}

			filtered.add(new StringRecordItem(orig, repl, r.count));
		}

		// Also include replacements that might not be currently on screen
		if (showModifiedOnly || currentFilter.length() > 0) {
			for (Map.Entry<String, String> entry : replacements.entrySet()) {
				String key = entry.getKey();
				boolean alreadyInList = false;
				for (StringRecordItem item : filtered) {
					if (item.original.equals(key)) {
						alreadyInList = true;
						break;
					}
				}
				if (!alreadyInList) {
					if (currentFilter.isEmpty() || key.toLowerCase().contains(currentFilter) || entry.getValue().toLowerCase().contains(currentFilter)) {
						filtered.add(0, new StringRecordItem(key, entry.getValue(), 0));
					}
				}
			}
		}

		adapter.setItems(filtered);

		if (tvStats != null) {
			tvStats.setText(String.format(getString(R.string.string_editor_all_strings), captured.size())
					+ " | " + String.format(getString(R.string.string_editor_modified_only), replacements.size()));
		}
	}

	private void showEditSingleStringDialog(String originalText) {
		Context context = requireActivity();
		View view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_single_string, null);

		TextView tvOriginal = view.findViewById(R.id.tv_dialog_original);
		EditText etReplacement = view.findViewById(R.id.et_dialog_replacement);
		Button btnReset = view.findViewById(R.id.btn_dialog_reset);
		Button btnCancel = view.findViewById(R.id.btn_dialog_cancel);
		Button btnApply = view.findViewById(R.id.btn_dialog_apply);

		tvOriginal.setText(originalText);

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
			refreshData();
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
			refreshData();
			dialog.dismiss();
		});

		dialog.show();
	}

	private void exportStrings() {
		try {
			Context context = requireActivity();
			String json = manager.exportToJsonString();

			// Also copy to clipboard for convenience
			ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
			if (clipboard != null) {
				clipboard.setPrimaryClip(ClipData.newPlainText("J2ME String Overrides", json));
			}

			File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
			if (!downloadDir.exists()) downloadDir.mkdirs();
			File exportFile = new File(downloadDir, "j2me_strings_" + System.currentTimeMillis() + ".json");
			try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(exportFile), StandardCharsets.UTF_8)) {
				writer.write(json);
			}

			Toast.makeText(context, String.format(getString(R.string.string_editor_exported), exportFile.getName())
					+ " (Copied to Clipboard)", Toast.LENGTH_LONG).show();
		} catch (Throwable t) {
			Toast.makeText(requireActivity(), "Export error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
		}
	}

	private void showImportDialog() {
		Context context = requireActivity();
		EditText input = new EditText(context);
		input.setHint("Paste JSON here...");
		input.setMinLines(5);

		// Try auto-filling from clipboard
		ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
			CharSequence clipText = clipboard.getPrimaryClip().getItemAt(0).getText();
			if (clipText != null && clipText.toString().contains("replacements")) {
				input.setText(clipText);
			}
		}

		new AlertDialog.Builder(context)
				.setTitle(R.string.string_editor_import)
				.setView(input)
				.setPositiveButton("Import", (dialog, which) -> {
					String json = input.getText().toString();
					if (manager.importFromJsonString(json, true)) {
						refreshData();
						Toast.makeText(context, R.string.string_editor_imported, Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(context, R.string.string_editor_import_failed, Toast.LENGTH_SHORT).show();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static class StringRecordItem {
		final String original;
		final String replaced;
		final int count;

		StringRecordItem(String original, String replaced, int count) {
			this.original = original;
			this.replaced = replaced;
			this.count = count;
		}
	}

	private static class StringAdapter extends BaseAdapter {
		private final Context context;
		private final List<StringRecordItem> items = new ArrayList<>();

		StringAdapter(Context context) {
			this.context = context;
		}

		void setItems(List<StringRecordItem> list) {
			items.clear();
			if (list != null) {
				items.addAll(list);
			}
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return items.size();
		}

		@Override
		public StringRecordItem getItem(int position) {
			return items.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			if (convertView == null) {
				convertView = LayoutInflater.from(context).inflate(R.layout.item_captured_string, parent, false);
			}

			StringRecordItem item = getItem(position);
			TextView tvOriginal = convertView.findViewById(R.id.tv_original_string);
			TextView tvReplaced = convertView.findViewById(R.id.tv_replaced_string);
			TextView tvCount = convertView.findViewById(R.id.tv_count_badge);

			tvOriginal.setText(item.original);
			if (item.replaced != null) {
				tvReplaced.setVisibility(View.VISIBLE);
				tvReplaced.setText("➔ " + item.replaced);
			} else {
				tvReplaced.setVisibility(View.GONE);
			}

			if (item.count > 0) {
				tvCount.setText(item.count + "x");
			} else {
				tvCount.setText("mod");
			}

			return convertView;
		}
	}
}
