package com.devin.jarvis.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.devin.jarvis.R;
import com.devin.jarvis.ui.MainActivity;

import java.util.ArrayList;
import java.util.List;

public class JarvisService extends Service implements Brain.ReplyHandler {

    private static final String TAG = "Jarvis.Service";

    public static final String CHANNEL_ID = "jarvis_main";
    public static final int NOTIF_ID = 1001;

    public static final String ACTION_TOGGLE_MUTE = "jarvis.toggle_mute";
    public static final String ACTION_SHUTDOWN = "jarvis.shutdown";

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public JarvisService getService() { return JarvisService.this; }
    }

    public interface UiBridge {
        void onTranscript(String text, boolean fromUser);
        void onStatus(String status);
        void onListeningStateChanged(boolean listening, boolean muted);
    }

    private final List<UiBridge> bridges = new ArrayList<>();
    public void registerBridge(UiBridge b) { synchronized (bridges) { bridges.add(b); } }
    public void unregisterBridge(UiBridge b) { synchronized (bridges) { bridges.remove(b); } }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private Memory memory;
    private Speaker speaker;
    private MicListener listener;
    private Brain brain;
    private boolean started = false;

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        memory = new Memory(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SHUTDOWN.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_MUTE.equals(action)) {
            toggleMute();
            return START_STICKY;
        }
        startInForeground();
        startEverything();
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Jarvis", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setDescription("Активный голосовой помощник");
            nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        boolean muted = listener != null && listener.isMuted();
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent muteIntent = new Intent(this, JarvisService.class).setAction(ACTION_TOGGLE_MUTE);
        PendingIntent piMute = PendingIntent.getService(this, 1, muteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, JarvisService.class).setAction(ACTION_SHUTDOWN);
        PendingIntent piStop = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(muted ? R.string.notif_muted : R.string.notif_listening))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(piOpen)
                .addAction(0, getString(muted ? R.string.notif_unmute : R.string.notif_mute), piMute)
                .addAction(0, getString(R.string.notif_shutdown), piStop)
                .build();
    }

    private void startInForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void refreshNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    private void startEverything() {
        if (started) return;
        started = true;

        speaker = new Speaker(this, settings.modulation(), settings.ttsReverb(), null);

        listener = new MicListener(this, settings);
        listener.setCallback(new MicListener.Callback() {
            @Override public void onPartial(String text) {
                // Only show user transcripts that contain the wake word so
                // ambient speech doesn't fill the screen and make the user
                // think Jarvis is reacting to everything.
                if (Intents.hasWake(text)) {
                    // Cut speech as soon as we hear "Jarvis" so the user can
                    // barge in without waiting for him to finish.
                    if (speaking.get()) interruptSpeech();
                    // If music is currently playing, STOP it the instant we
                    // hear the wake word. Previously we only ducked the
                    // volume to 8% for 4.5 sec — but if the recogniser
                    // mis-heard the stop verb (e.g. "остановим" vs the
                    // expected "останови"), the duck would simply restore
                    // and the song kept playing. Addressing Jarvis while a
                    // song is on practically always means "stop it" anyway:
                    // either explicitly, or because the user wants a query
                    // they cannot reasonably hear over music. Net effect:
                    // wake-word during music = silence, every time.
                    com.devin.jarvis.commands.MusicService.stopIfPlaying();
                    broadcastTranscript(text, true);
                }
            }
            @Override public void onResult(String text) {
                if (Intents.hasWake(text)) {
                    // Barge-in: if Jarvis is currently speaking, cut him off so
                    // the user's new command takes priority.
                    if (speaking.get()) interruptSpeech();
                    broadcastTranscript(text, true);
                    armWatchdog();
                    handleVoiceCommand(text);
                }
            }
            @Override public void onError(int code, String message) {
                Log.d(TAG, "Recognizer error: " + message);
                String msg;
                if ("no_record_permission".equals(message)) {
                    msg = "ru".equalsIgnoreCase(settings.language())
                            ? "Нет разрешения на микрофон, сэр."
                            : "Microphone permission missing, sir.";
                } else if (message != null && message.startsWith("model_unpack_failed")) {
                    msg = "ru".equalsIgnoreCase(settings.language())
                            ? "Не удалось распаковать модель распознавания, сэр."
                            : "Could not unpack the speech model, sir.";
                } else if (message != null && message.startsWith("audio_record")) {
                    msg = "ru".equalsIgnoreCase(settings.language())
                            ? "Не удалось открыть микрофон, сэр."
                            : "Could not open the microphone, sir.";
                } else {
                    msg = message;
                }
                main.post(() -> {
                    synchronized (bridges) {
                        for (UiBridge b : bridges) b.onStatus(msg);
                    }
                });
            }
            @Override public void onListeningStateChanged(boolean listening) {
                main.post(() -> {
                    synchronized (bridges) {
                        for (UiBridge b : bridges) b.onListeningStateChanged(listening, listener.isMuted());
                    }
                });
            }
            @Override public void onStatus(String text) {
                main.post(() -> {
                    synchronized (bridges) {
                        for (UiBridge b : bridges) b.onStatus(text);
                    }
                });
            }
        });

        brain = new Brain(this, settings, memory, this);

        // Greet on first start
        speaker.setListener(new Speaker.Listener() {
            @Override public void onStart() {}
            @Override public void onDone() {}
        });

        // Greet shortly after TTS warms up. Routes through say() which mutes
        // the mic during playback so we don't catch our own voice.
        main.postDelayed(() -> {
            if (speaker != null) {
                String greet = "ru".equalsIgnoreCase(settings.language())
                        ? "Все системы запущены, сэр. Я к вашим услугам."
                        : "All systems online, sir. At your service.";
                say(greet);
            }
        }, 1200);

        listener.start();

        // If the user has the optional Whisper-medium recognizer enabled,
        // pre-load the model in the background so the first command after
        // service start doesn't pay the ~3 sec model-load cost.
        if (settings != null && settings.useWhisper()) {
            try {
                com.devin.jarvis.voice.WhisperRecognizer.get(this).loadAsync();
            } catch (Throwable t) {
                Log.w(TAG, "Whisper preload skipped: " + t);
            }
        }
    }

    private void shutdown() {
        if (listener != null) listener.destroy();
        if (speaker != null) speaker.shutdown();
        listener = null;
        speaker = null;
        brain = null;
        started = false;
        stopForeground(true);
        stopSelf();
    }

    public void toggleMute() {
        if (listener == null) return;
        boolean newMuted = !listener.isMuted();
        listener.setMuted(newMuted);
        if (speaker != null) {
            String reply = "ru".equalsIgnoreCase(settings.language())
                    ? (newMuted ? "Микрофон выключен, сэр." : "Микрофон включён.")
                    : (newMuted ? "Microphone muted, sir." : "Microphone live again.");
            // Use say() so the response goes through the same mute-during-
            // speech logic. (When unmuting we DO want to hear the voice; the
            // listener was muted before this call, so say() won't auto-unmute
            // afterwards — set the flag manually.)
            if (!newMuted) {
                speaker.speak(reply); // already unmuted, just speak
            } else {
                speaker.speak(reply); // already muted, no need for extra muting
            }
            broadcastTranscript(reply, false);
        }
        refreshNotification();
    }

    public boolean isMuted() { return listener != null && listener.isMuted(); }
    public boolean isListening() { return listener != null && !listener.isMuted(); }

    /**
     * Submit a command from the in-app text input. Bypasses the wake-word
     * requirement (the user typing into Jarvis's UI is itself the wake).
     * The text appears in the transcript like a spoken command would.
     */
    public void submitTypedText(String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        // If Jarvis is currently speaking a previous reply, cut him off.
        if (speaking.get()) interruptSpeech();
        broadcastTranscript(t, true);
        armWatchdog();
        if (brain != null) brain.handleTypedText(t);
    }

    /**
     * Voice path: dispatches a Vosk-final transcript to the brain, optionally
     * re-transcribing the recent mic audio with Whisper-medium for higher
     * accuracy on rare words / names / accents. Whisper inference is heavy
     * (~3–10 sec depending on phone), so we run it on a background thread and
     * speak the user's "thinking" status while we wait. If Whisper fails or
     * is disabled, the original Vosk text is used.
     */
    private void handleVoiceCommand(String voskText) {
        boolean wantWhisper = settings != null
                && settings.useWhisper()
                && listener != null;
        if (!wantWhisper) {
            if (brain != null) brain.handleTranscript(voskText);
            return;
        }
        com.devin.jarvis.voice.WhisperRecognizer w =
                com.devin.jarvis.voice.WhisperRecognizer.get(this);
        if (!w.isReady()) {
            // First call kicks off async load; in the meantime fall back to Vosk.
            w.loadAsync();
            if (brain != null) brain.handleTranscript(voskText);
            return;
        }
        // Snapshot the last ~10 sec of mic audio NOW so we don't lose it
        // while Whisper runs.
        final short[] pcm = listener.lastPcm16(10);
        final String lang = settings.language();
        final String fallback = voskText;
        new Thread(() -> {
            String betterText = null;
            try {
                long t0 = System.currentTimeMillis();
                betterText = w.transcribePcm16Sync(pcm, lang);
                long dt = System.currentTimeMillis() - t0;
                Log.i(TAG, "Whisper(" + lang + ") took " + dt + " ms, said: \"" + betterText + "\"");
            } catch (Throwable t) {
                Log.w(TAG, "Whisper threw, using Vosk text", t);
            }
            String chosen = betterText;
            if (chosen == null || chosen.isEmpty()) chosen = fallback;
            // Re-broadcast the *Whisper* version of the transcript so the user
            // sees what was actually understood.
            final String shown = chosen;
            main.post(() -> {
                broadcastTranscript(shown, true);
                if (brain != null) brain.handleTranscript(shown);
            });
        }, "Jarvis-Whisper-Run").start();
    }

    /** Re-read user-tunable knobs from settings and apply them on the fly. */
    public void applySettingsRefresh() {
        if (settings == null) return;
        if (speaker != null) {
            speaker.setModulation(settings.modulation());
            speaker.setReverb(settings.ttsReverb());
        }
    }

    // ---- Brain.ReplyHandler ----

    private final java.util.concurrent.atomic.AtomicBoolean speaking =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Runnable watchdogTask = () -> {
        boolean ru = settings != null && "ru".equalsIgnoreCase(settings.language());
        say(ru
                ? "Что-то я задумался, сэр. Повторите, пожалуйста."
                : "I'm afraid I lost my train of thought, sir. Try again.");
    };

    /**
     * Starts a watchdog that will speak a recovery message if Jarvis has not
     * produced any reply within ~28 s after a command was accepted. Without
     * this, a hung LLM/TTS call could leave the assistant in a permanent
     * "Thinking..." state until the user power-cycles the service.
     */
    private void armWatchdog() {
        main.removeCallbacks(watchdogTask);
        main.postDelayed(watchdogTask, 28_000);
    }

    private void disarmWatchdog() {
        main.removeCallbacks(watchdogTask);
    }

    @Override
    public void say(String text) {
        if (text == null || text.isEmpty()) return;
        disarmWatchdog();
        if (speaker != null) {
            // Keep the mic open while Jarvis is speaking so the user can
            // interrupt at any time ("barge-in"). The hardware AcousticEcho
            // Canceler attached to AudioRecord plus our wake-word filter keep
            // the recognizer from triggering on Jarvis's own voice.
            speaker.setListener(new Speaker.Listener() {
                @Override public void onStart() { speaking.set(true); }
                @Override public void onDone() {
                    if (speaker != null && speaker.isQueueEmpty()) speaking.set(false);
                }
            });
            speaker.speak(text);
        }
        broadcastTranscript(text, false);
    }

    @Override
    public void sayChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (speaker != null) {
            speaker.speakAppend(chunk);
        }
        broadcastTranscript(chunk, false);
    }

    public boolean isSpeaking() { return speaking.get(); }

    /**
     * Called when a fresh wake-word transcript arrives mid-speech. Cuts off
     * the current TTS so the user can issue a new command without waiting.
     */
    private void interruptSpeech() {
        if (speaker == null) return;
        try { speaker.stop(); } catch (Throwable ignored) {}
        speaking.set(false);
    }

    @Override
    public void status(String text) {
        main.post(() -> {
            synchronized (bridges) {
                for (UiBridge b : bridges) b.onStatus(text);
            }
        });
    }

    @Override
    public void onShutdownRequested() {
        say("ru".equalsIgnoreCase(settings.language())
                ? "Перехожу в спящий режим, сэр."
                : "Going to sleep, sir.");
        main.postDelayed(this::shutdown, 1500);
    }

    @Override
    public void onMuteRequested() {
        if (listener != null && !listener.isMuted()) toggleMute();
    }

    private void broadcastTranscript(String text, boolean fromUser) {
        main.post(() -> {
            synchronized (bridges) {
                for (UiBridge b : bridges) b.onTranscript(text, fromUser);
            }
        });
    }

    @Override
    public void onDestroy() {
        if (listener != null) listener.destroy();
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }
}
