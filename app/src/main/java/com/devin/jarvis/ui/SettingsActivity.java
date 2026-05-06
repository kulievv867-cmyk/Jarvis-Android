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
}
