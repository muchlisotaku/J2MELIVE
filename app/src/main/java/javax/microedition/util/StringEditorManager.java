package javax.microedition.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StringEditorManager {
	private static final String TAG = "StringEditorManager";
	private static final String FILE_NAME = "live_strings_override.json";
	private static final int MAX_CAPTURED = 2000;

	private static final StringEditorManager INSTANCE = new StringEditorManager();

	public static class StringRecord {
		public final String original;
		public long lastSeenTime;
		public int count;

		public StringRecord(String original) {
			this.original = original;
			this.lastSeenTime = System.currentTimeMillis();
			this.count = 1;
		}
	}

	public interface OnChangeListener {
		void onStringChanged();
	}

	private final Map<String, StringRecord> capturedStrings = Collections.synchronizedMap(
			new LinkedHashMap<String, StringRecord>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, StringRecord> eldest) {
					return size() > MAX_CAPTURED;
				}
			}
	);

	private final ConcurrentHashMap<String, String> replacements = new ConcurrentHashMap<>();
	private final List<OnChangeListener> listeners = new ArrayList<>();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private File appDir;
	private File configFile;
	private boolean captureEnabled = true;
	private boolean substringMatchEnabled = false;

	private StringEditorManager() {
	}

	public static StringEditorManager getInstance() {
		return INSTANCE;
	}

	public synchronized void init(File appDir) {
		this.appDir = appDir;
		this.capturedStrings.clear();
		this.replacements.clear();
		if (appDir != null) {
			this.configFile = new File(appDir, FILE_NAME);
			loadFromFile(this.configFile);
		} else {
			this.configFile = null;
		}
	}

	public void recordString(String str) {
		if (!captureEnabled || str == null) {
			return;
		}
		String trimmed = str.trim();
		if (trimmed.isEmpty()) {
			return;
		}
		synchronized (capturedStrings) {
			StringRecord record = capturedStrings.get(str);
			if (record == null) {
				capturedStrings.put(str, new StringRecord(str));
			} else {
				record.lastSeenTime = System.currentTimeMillis();
				record.count++;
			}
		}
	}

	public String replaceString(String original) {
		if (original == null || original.isEmpty()) {
			return original;
		}
		// Exact match lookup
		String replaced = replacements.get(original);
		if (replaced != null) {
			return replaced;
		}

		// Optional substring replacement mode
		if (substringMatchEnabled && !replacements.isEmpty()) {
			String result = original;
			for (Map.Entry<String, String> entry : replacements.entrySet()) {
				String target = entry.getKey();
				String replacement = entry.getValue();
				if (target.length() > 2 && result.contains(target)) {
					result = result.replace(target, replacement);
				}
			}
			return result;
		}

		return original;
	}

	public void setReplacement(String original, String replacement) {
		if (original == null || replacement == null) return;
		replacements.put(original, replacement);
		saveToFile(configFile);
		notifyListeners();
	}

	public void removeReplacement(String original) {
		if (original == null) return;
		replacements.remove(original);
		saveToFile(configFile);
		notifyListeners();
	}

	public void clearAllReplacements() {
		replacements.clear();
		saveToFile(configFile);
		notifyListeners();
	}

	public Map<String, String> getReplacements() {
		return new LinkedHashMap<>(replacements);
	}

	public List<StringRecord> getCapturedList() {
		List<StringRecord> list;
		synchronized (capturedStrings) {
			list = new ArrayList<>(capturedStrings.values());
		}
		list.sort((o1, o2) -> Long.compare(o2.lastSeenTime, o1.lastSeenTime));
		return list;
	}

	public void clearCaptured() {
		capturedStrings.clear();
	}

	public boolean isCaptureEnabled() {
		return captureEnabled;
	}

	public void setCaptureEnabled(boolean captureEnabled) {
		this.captureEnabled = captureEnabled;
	}

	public boolean isSubstringMatchEnabled() {
		return substringMatchEnabled;
	}

	public void setSubstringMatchEnabled(boolean substringMatchEnabled) {
		this.substringMatchEnabled = substringMatchEnabled;
	}

	public void addListener(OnChangeListener listener) {
		synchronized (listeners) {
			if (!listeners.contains(listener)) {
				listeners.add(listener);
			}
		}
	}

	public void removeListener(OnChangeListener listener) {
		synchronized (listeners) {
			listeners.remove(listener);
		}
	}

	private void notifyListeners() {
		mainHandler.post(() -> {
			List<OnChangeListener> copy;
			synchronized (listeners) {
				copy = new ArrayList<>(listeners);
			}
			for (OnChangeListener listener : copy) {
				try {
					listener.onStringChanged();
				} catch (Throwable t) {
					Log.e(TAG, "Error in listener callback", t);
				}
			}
		});
	}

	public synchronized boolean loadFromFile(File file) {
		if (file == null || !file.exists() || !file.canRead()) {
			return false;
		}
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line).append('\n');
			}
			return importFromJsonString(sb.toString(), false);
		} catch (Throwable e) {
			Log.e(TAG, "Failed to load string overrides from: " + file, e);
			return false;
		}
	}

	public synchronized boolean saveToFile(File file) {
		if (file == null) return false;
		try {
			File parent = file.getParentFile();
			if (parent != null && !parent.exists()) {
				parent.mkdirs();
			}
			String json = exportToJsonString();
			try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
				writer.write(json);
				writer.flush();
			}
			return true;
		} catch (Throwable e) {
			Log.e(TAG, "Failed to save string overrides to: " + file, e);
			return false;
		}
	}

	public String exportToJsonString() {
		try {
			JSONObject root = new JSONObject();
			root.put("version", 1);
			root.put("timestamp", System.currentTimeMillis());
			root.put("substring_mode", substringMatchEnabled);

			JSONObject mapObj = new JSONObject();
			for (Map.Entry<String, String> entry : replacements.entrySet()) {
				mapObj.put(entry.getKey(), entry.getValue());
			}
			root.put("replacements", mapObj);
			return root.toString(2);
		} catch (Throwable e) {
			Log.e(TAG, "Failed to serialize JSON", e);
			return "{}";
		}
	}

	public boolean importFromJsonString(String jsonStr, boolean append) {
		if (jsonStr == null || jsonStr.trim().isEmpty()) return false;
		try {
			JSONObject root = new JSONObject(jsonStr);
			if (root.has("substring_mode")) {
				this.substringMatchEnabled = root.optBoolean("substring_mode", false);
			}
			JSONObject mapObj = root.optJSONObject("replacements");
			if (mapObj != null) {
				if (!append) {
					replacements.clear();
				}
				Iterator<String> keys = mapObj.keys();
				while (keys.hasNext()) {
					String key = keys.next();
					String val = mapObj.optString(key, "");
					replacements.put(key, val);
				}
				if (configFile != null) {
					saveToFile(configFile);
				}
				notifyListeners();
				return true;
			}
		} catch (Throwable e) {
			Log.e(TAG, "Failed to parse JSON string overrides", e);
		}
		return false;
	}
}
