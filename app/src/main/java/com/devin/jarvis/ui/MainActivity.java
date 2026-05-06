package com.devin.jarvis.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.devin.jarvis.R;
import com.devin.jarvis.core.JarvisService;
import com.devin.jarvis.core.Settings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

public class MainActivity extends AppCompatActivity implements JarvisService.UiBridge {

    private MaterialButton powerBtn, muteBtn, sendBtn;
    private TextView statusText;
    private TextInputEditText textInput;
    private TranscriptAdapter adapter;

    private Settings settings;
    private String appliedTheme;
    private JarvisService service;
    private boolean bound = false;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((JarvisService.LocalBinder) b).getService();
            bound = true;
            service.registerBridge(MainActivity.this);
            updateUi();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.unregisterBridge(MainActivity.this);
            service = null; bound = false; updateUi();
        }
    };

    private final ActivityResultLauncher<String> micPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> { if (granted) requestNotifAndStart(); });

    private final ActivityResultLauncher<String> notifPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> requestMediaAndStart());

    private final ActivityResultLauncher<String> mediaPerm =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> startService());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settings = new Settings(this);
        appliedTheme = settings.theme();
        ThemeManager.apply(this, settings);
        // Make sure the launcher alias matches the saved theme. This is a
        // no-op if the user hasn't changed themes since install (or has
        // already synced via SettingsActivity).
        ThemeManager.applyLauncherIcon(getApplicationContext(), appliedTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        powerBtn = findViewById(R.id.powerBtn);
        muteBtn = findViewById(R.id.muteBtn);
        sendBtn = findViewById(R.id.sendBtn);
        statusText = findViewById(R.id.statusText);
        textInput = findViewById(R.id.textInput);
        View settingsBtn = findViewById(R.id.settingsBtn);

        RecyclerView rv = findViewById(R.id.transcriptList);
        adapter = new TranscriptAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        powerBtn.setOnClickListener(v -> onPowerClick());
        muteBtn.setOnClickListener(v -> onMuteClick());
        sendBtn.setOnClickListener(v -> onSendText());
        if (textInput != null) {
            textInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEND) { onSendText(); return true; }
                return false;
            });
        }
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Bind if service is already running
        bindService(new Intent(this, JarvisService.class), conn, 0);
    }

    private void onSendText() {
        if (textInput == null) return;
        CharSequence cs = textInput.getText();
        String text = cs == null ? "" : cs.toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (!bound || service == null) {
            Toast.makeText(this, R.string.text_input_inactive, Toast.LENGTH_SHORT).show();
            return;
        }
        textInput.setText("");
        // Hide soft keyboard.
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        service.submitTypedText(text);
    }

    private void onPowerClick() {
        if (bound && service != null) {
            // Service running -> shut down
            Intent stop = new Intent(this, JarvisService.class)
                    .setAction(JarvisService.ACTION_SHUTDOWN);
            startService(stop);
            unbindIfBound();
            updateUi();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        requestNotifAndStart();
    }

    private void onMuteClick() {
        if (!bound || service == null) return;
        Intent toggle = new Intent(this, JarvisService.class)
                .setAction(JarvisService.ACTION_TOGGLE_MUTE);
        startService(toggle);
    }

    private void requestNotifAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        requestMediaAndStart();
    }

    private void requestMediaAndStart() {
        // For in-app music playback we need to read the device audio library.
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                mediaPerm.launch(Manifest.permission.READ_MEDIA_AUDIO);
                return;
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                mediaPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                return;
            }
        }
        startService();
    }

    private void startService() {
        Intent svc = new Intent(this, JarvisService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
        else startService(svc);
        bindService(svc, conn, Context.BIND_AUTO_CREATE);
        updateUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-apply settings (modulation/reverb toggles) when returning from settings.
        if (bound && service != null) service.applySettingsRefresh();
        // If user changed the colour theme in settings, rebuild this activity
        // with the new palette.
        if (settings != null) {
            String now = settings.theme();
            if (appliedTheme != null && !appliedTheme.equals(now)) {
                appliedTheme = now;
                recreate();
            }
        }
    }

    private void unbindIfBound() {
        if (bound) {
            try { service.unregisterBridge(this); } catch (Exception ignored) {}
            try { unbindService(conn); } catch (Exception ignored) {}
            service = null; bound = false;
        }
    }

    private void updateUi() {
        boolean running = bound && service != null;
        boolean muted = running && service.isMuted();
        powerBtn.setText(running ? R.string.shutdown : R.string.power_on);
        muteBtn.setEnabled(running);
        muteBtn.setText(muted ? R.string.unmute : R.string.mute);
        if (!running) {
            statusText.setText(R.string.status_off);
        } else {
            statusText.setText(muted ? R.string.status_muted : R.string.status_listening);
        }
    }

    @Override
    public void onTranscript(String text, boolean fromUser) {
        adapter.append(text, fromUser);
        RecyclerView rv = findViewById(R.id.transcriptList);
        if (rv != null) rv.scrollToPosition(adapter.getItemCount() - 1);
    }

    @Override
    public void onStatus(String status) {
        statusText.setText(status);
    }

    @Override
    public void onListeningStateChanged(boolean listening, boolean muted) {
        if (muted) statusText.setText(R.string.status_muted);
        else if (listening) statusText.setText(R.string.status_listening);
        else statusText.setText(R.string.status_processing);
        muteBtn.setText(muted ? R.string.unmute : R.string.mute);
    }

    @Override
    protected void onDestroy() {
        unbindIfBound();
        super.onDestroy();
    }
}
