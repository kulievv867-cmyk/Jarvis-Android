package com.devin.jarvis.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.devin.jarvis.R;
import com.devin.jarvis.core.Memory;
import com.devin.jarvis.core.Settings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Arrays;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private Settings settings;
    private String appliedTheme;

    private Spinner themeSpinner;
    private Spinner langSpinner;
    private EditText keyEdit;
    private TextView keyStatus;
    private MaterialSwitch llmSwitch;
    private MaterialSwitch reverbSwitch;
    private MaterialSwitch modulationSwitch;
    private MaterialSwitch whisperSwitch;
    private TextView whisperStatus;
    private MaterialButton whisperDeleteBtn;
    private Spinner whisperModelSpinner;
    private com.devin.jarvis.voice.WhisperRecognizer.ProgressListener whisperProgress;
    private static final List<String> WHISPER_MODEL_VALUES = Arrays.asList(
            "base", "small", "large-turbo");
    private EditText userNameEdit;
    private EditText userCityEdit;
    private EditText userCountryEdit;
    private EditText userTimezoneEdit;

    private static final List<String> LANG_VALUES = Arrays.asList("ru", "en");
    private static final List<String> LANG_LABELS = Arrays.asList("Русский", "English");

    private static final List<String> THEME_VALUES = Arrays.asList(
            Settings.THEME_BLUE,
            Settings.THEME_ORANGE,
            Settings.THEME_GREEN,
            Settings.THEME_GRAPHITE,
            Settings.THEME_LIGHT);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        appliedTheme = settings.theme();
        ThemeManager.apply(this, settings);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        themeSpinner = findViewById(R.id.themeSpinner);
        langSpinner = findViewById(R.id.langSpinner);
        keyEdit = findViewById(R.id.keyEdit);
        keyStatus = findViewById(R.id.keyStatus);
        llmSwitch = findViewById(R.id.llmSwitch);
        reverbSwitch = findViewById(R.id.reverbSwitch);
        modulationSwitch = findViewById(R.id.modulationSwitch);
        whisperSwitch = findViewById(R.id.whisperSwitch);
        whisperStatus = findViewById(R.id.whisperStatus);
        whisperDeleteBtn = findViewById(R.id.whisperDeleteBtn);
        whisperModelSpinner = findViewById(R.id.whisperModelSpinner);
        userNameEdit = findViewById(R.id.userNameEdit);
        userCityEdit = findViewById(R.id.userCityEdit);
        userCountryEdit = findViewById(R.id.userCountryEdit);
        userTimezoneEdit = findViewById(R.id.userTimezoneEdit);
        MaterialButton saveKeyBtn = findViewById(R.id.saveKeyBtn);
        MaterialButton clearKeyBtn = findViewById(R.id.clearKeyBtn);
        MaterialButton clearMemoryBtn = findViewById(R.id.clearMemoryBtn);

        ArrayAdapter<String> a = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, LANG_LABELS);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(a);
        langSpinner.setSelection(LANG_VALUES.indexOf(settings.language()));
        langSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                settings.setLanguage(LANG_VALUES.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        List<String> themeLabels = Arrays.asList(
                getString(R.string.theme_blue),
                getString(R.string.theme_orange),
                getString(R.string.theme_green),
                getString(R.string.theme_graphite),
                getString(R.string.theme_light));
        ArrayAdapter<String> ta = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, themeLabels);
        ta.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themeSpinner.setAdapter(ta);
        int themeIdx = THEME_VALUES.indexOf(settings.theme());
        if (themeIdx < 0) themeIdx = 0;
        themeSpinner.setSelection(themeIdx);
        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String picked = THEME_VALUES.get(position);
                if (!picked.equals(settings.theme())) {
                    settings.setTheme(picked);
                    appliedTheme = picked;
                    // Update the home-screen launcher icon to match the new
                    // palette. The launcher caches icons, so the change may
                    // take a few seconds to reflect.
                    ThemeManager.applyLauncherIcon(getApplicationContext(), picked);
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        refreshKeyStatus();

        saveKeyBtn.setOnClickListener(v -> saveKeyFromEdit(true));
        clearKeyBtn.setOnClickListener(v -> {
            settings.setOpenAiKey("");
            keyEdit.setText("");
            refreshKeyStatus();
            Toast.makeText(this, R.string.key_cleared, Toast.LENGTH_SHORT).show();
        });

        llmSwitch.setChecked(settings.useLlm());
        llmSwitch.setOnCheckedChangeListener((CompoundButton b, boolean v) -> settings.setUseLlm(v));

        reverbSwitch.setChecked(settings.ttsReverb());
        reverbSwitch.setOnCheckedChangeListener((CompoundButton b, boolean v) -> settings.setTtsReverb(v));

        modulationSwitch.setChecked(settings.modulation());
        modulationSwitch.setOnCheckedChangeListener((CompoundButton b, boolean v) -> settings.setModulation(v));

        whisperSwitch.setChecked(settings.useWhisper());
        whisperSwitch.setOnCheckedChangeListener((CompoundButton b, boolean v) -> {
            settings.setUseWhisper(v);
            if (v) {
                // Begin model download/load if not already cached.
                com.devin.jarvis.voice.WhisperRecognizer.get(this).loadAsync();
                refreshWhisperStatus();
            } else {
                whisperStatus.setVisibility(View.GONE);
                refreshWhisperStatus();
            }
        });

        // Model size selector. Switching variants drops the previously
        // cached file from disk to avoid stacking up 0.7 GB of Whisper
        // models the user no longer wants.
        List<String> whisperModelLabels = Arrays.asList(
                getString(R.string.setting_whisper_model_base),
                getString(R.string.setting_whisper_model_small),
                getString(R.string.setting_whisper_model_large));
        ArrayAdapter<String> wma = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, whisperModelLabels);
        wma.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        whisperModelSpinner.setAdapter(wma);
        int wmIdx = WHISPER_MODEL_VALUES.indexOf(settings.whisperModel());
        if (wmIdx < 0) wmIdx = 0;
        whisperModelSpinner.setSelection(wmIdx);
        whisperModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String picked = WHISPER_MODEL_VALUES.get(position);
                if (picked.equals(settings.whisperModel())) return;
                com.devin.jarvis.voice.WhisperRecognizer w =
                        com.devin.jarvis.voice.WhisperRecognizer.get(SettingsActivity.this);
                // Drop the now-stale model file (and any other variant) so
                // the user doesn't accumulate ~0.7 GB of unused Whisper
                // weights. The new variant will download on next loadAsync.
                w.deleteCachedModel();
                settings.setWhisperModel(picked);
                if (settings.useWhisper()) {
                    w.loadAsync();
                }
                refreshWhisperStatus();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        whisperProgress = new com.devin.jarvis.voice.WhisperRecognizer.ProgressListener() {
            @Override public void onProgress(long downloaded, long total) {
                if (whisperStatus == null) return;
                int pct = total > 0 ? (int) (downloaded * 100L / total) : 0;
                String human = humanBytes(downloaded) + " / " + humanBytes(total);
                whisperStatus.setVisibility(View.VISIBLE);
                whisperStatus.setText(getString(R.string.setting_use_whisper_downloading, pct,
                        humanBytes(downloaded), humanBytes(total)));
            }
            @Override public void onReady() {
                if (whisperStatus == null) return;
                whisperStatus.setVisibility(View.VISIBLE);
                whisperStatus.setText(R.string.setting_use_whisper_ready);
                refreshWhisperStatus();
            }
            @Override public void onError(String message) {
                if (whisperStatus == null) return;
                whisperStatus.setVisibility(View.VISIBLE);
                whisperStatus.setText(getString(R.string.setting_use_whisper_failed,
                        message == null ? "?" : message));
            }
        };
        com.devin.jarvis.voice.WhisperRecognizer.get(this).addProgressListener(whisperProgress);

        whisperDeleteBtn.setOnClickListener(v -> {
            com.devin.jarvis.voice.WhisperRecognizer w =
                    com.devin.jarvis.voice.WhisperRecognizer.get(this);
            w.deleteCachedModel();
            Toast.makeText(this, R.string.setting_use_whisper_deleted, Toast.LENGTH_SHORT).show();
            // Force the user to re-enable to re-download.
            settings.setUseWhisper(false);
            whisperSwitch.setChecked(false);
            whisperStatus.setVisibility(View.GONE);
            refreshWhisperStatus();
        });

        refreshWhisperStatus();

        userNameEdit.setText(settings.userName());
        userCityEdit.setText(settings.userCity());
        userCountryEdit.setText(settings.userCountry());
        userTimezoneEdit.setText(settings.userTimezone());

        clearMemoryBtn.setOnClickListener(v -> {
            new Memory(this).clear();
            Toast.makeText(this, R.string.memory_cleared, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onPause() {
        // Save any pending unsaved key automatically when leaving the screen.
        saveKeyFromEdit(false);
        savePersonalisation();
        super.onPause();
    }

    private void savePersonalisation() {
        if (userNameEdit != null) settings.setUserName(text(userNameEdit));
        if (userCityEdit != null) settings.setUserCity(text(userCityEdit));
        if (userCountryEdit != null) settings.setUserCountry(text(userCountryEdit));
        if (userTimezoneEdit != null) settings.setUserTimezone(text(userTimezoneEdit));
    }

    private static String text(EditText e) {
        if (e == null || e.getText() == null) return "";
        return e.getText().toString().trim();
    }

    private void saveKeyFromEdit(boolean toast) {
        if (keyEdit == null) return;
        String s = keyEdit.getText() != null ? keyEdit.getText().toString().trim() : "";
        if (TextUtils.isEmpty(s)) return;
        settings.setOpenAiKey(s);
        keyEdit.setText("");
        refreshKeyStatus();
        if (toast) Toast.makeText(this, R.string.key_saved, Toast.LENGTH_SHORT).show();
    }

    private void refreshKeyStatus() {
        String k = settings.openAiKey();
        if (k == null || k.isEmpty()) {
            keyStatus.setText(R.string.key_status_none);
            keyStatus.setTextColor(getColor(R.color.text_secondary));
        } else {
            String masked = mask(k);
            keyStatus.setText(getString(R.string.key_status_set, masked));
            keyStatus.setTextColor(getColor(R.color.accent));
        }
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private void refreshWhisperStatus() {
        com.devin.jarvis.voice.WhisperRecognizer w =
                com.devin.jarvis.voice.WhisperRecognizer.get(this);
        boolean haveModel = w.isModelDownloaded();
        boolean enabled   = settings.useWhisper();
        whisperDeleteBtn.setVisibility(haveModel ? View.VISIBLE : View.GONE);
        if (enabled && haveModel && !w.isReady() && !w.isDownloading()) {
            // Cached but not yet loaded — kick a load so the next command uses
            // it without delay.
            w.loadAsync();
        }
        if (enabled && haveModel && w.isReady()) {
            whisperStatus.setVisibility(View.VISIBLE);
            whisperStatus.setText(R.string.setting_use_whisper_ready);
        }
    }

    private static String humanBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format("%.0f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.0f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    @Override
    protected void onDestroy() {
        if (whisperProgress != null) {
            try {
                com.devin.jarvis.voice.WhisperRecognizer.get(this)
                        .removeProgressListener(whisperProgress);
            } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }
}
