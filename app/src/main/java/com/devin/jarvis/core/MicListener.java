package com.devin.jarvis.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Always-listening microphone pipeline that uses Vosk for offline speech
 * recognition. Replaces the legacy Android SpeechRecognizer wrapper, which on
 * many devices (especially without Google Speech Services) silently never even
 * opens the microphone.
 *
 * Flow:
 *   AudioRecord (VOICE_RECOGNITION, 16 kHz mono PCM 16-bit)
 *   -> Vosk Recognizer (per-language model loaded from assets)
 *   -> partial / final results posted on the main thread.
 *
 * Mute simply pauses the read loop without releasing the AudioRecord, so the
 * service stays warm and the mic indicator drops away from the user's view.
 */
public class MicListener {

    public interface Callback {
        void onPartial(String text);
        void onResult(String text);
        void onError(int code, String message);
        void onListeningStateChanged(boolean listening);
        default void onStatus(String text) {}
    }

    private static final String TAG = "Jarvis.MicListener";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private final Context ctx;
    private final Settings settings;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Callback callback;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean muted = new AtomicBoolean(false);

    private Thread audioThread;
    private AudioRecord audioRecord;
    private Model modelEn, modelRu;
    private Recognizer recognizer;
    private String currentLang;
    private int activeSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private String lastPartial = "";
    private long lastLevelStatusMs = 0;

    // Rolling PCM-16 ring buffer of the last ~14 s of mic audio. Used by the
    // optional Whisper re-transcription path to grab a complete utterance
    // *after* Vosk has signalled end-of-speech. Always allocated (~448 KB)
    // because the cost is trivial and toggling it on/off would race with the
    // capture thread.
    private static final int RING_SECONDS = 14;
    private final short[] pcmRing = new short[SAMPLE_RATE * RING_SECONDS];
    private int pcmRingWrite = 0;          // monotonically-increasing total samples written
    private final Object pcmRingLock = new Object();

    public MicListener(Context ctx, Settings settings) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
    }

    public void setCallback(Callback cb) { this.callback = cb; }
    public boolean isMuted() { return muted.get(); }

    public void setMuted(boolean m) {
        muted.set(m);
        Callback cb = callback;
        if (cb != null) main.post(() -> cb.onListeningStateChanged(running.get() && !m));
    }

    /** Async-loads the Vosk models and then begins continuous capture. */
    public void start() {
        if (running.get()) return;

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            postError(-1, "no_record_permission");
            return;
        }

        // Unpack both models from assets to internal storage on first run, then
        // start capture. Vosk's StorageService is a no-op on subsequent runs.
        Callback cb0 = callback;
        if (cb0 != null) main.post(() -> cb0.onStatus(
                "ru".equalsIgnoreCase(settings.language())
                        ? "Загружаю модели распознавания…"
                        : "Loading speech models…"));
        StorageService.unpack(ctx, "model-en", "vosk-models",
                en -> {
                    modelEn = en;
                    postStatus("Модель EN распакована, распаковываю RU…",
                            "EN model ready, unpacking RU…");
                    StorageService.unpack(ctx, "model-ru", "vosk-models",
                            ru -> {
                                modelRu = ru;
                                postStatus("Модели готовы, открываю микрофон…",
                                        "Models ready, opening microphone…");
                                startCapture();
                            },
                            ex2 -> {
                                Log.w(TAG, "Failed to unpack RU model", ex2);
                                postError(-2, "model_unpack_failed_ru: " + ex2.getMessage());
                                // Still attempt capture with EN model only
                                postStatus("RU не распаковалась, пробую только EN…",
                                        "RU unpack failed, falling back to EN only…");
                                startCapture();
                            });
                },
                ex -> {
                    Log.w(TAG, "Failed to unpack EN model", ex);
                    postError(-2, "model_unpack_failed_en: " + ex.getMessage());
                });
    }

    /**
     * Best-effort: enable AGC, noise suppression, and acoustic echo cancellation
     * on the AudioRecord so noisy environments and the device's own playback
     * (Jarvis's TTS) don't bleed into Vosk's input.
     */
    private NoiseSuppressor noiseSuppressor;
    private AutomaticGainControl agc;
    private AcousticEchoCanceler echoCanceler;

    private void attachAudioEffects(int sessionId) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Throwable ignored) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId);
                if (agc != null) agc.setEnabled(true);
            }
        } catch (Throwable ignored) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Throwable ignored) {}
    }

    private void releaseAudioEffects() {
        try { if (noiseSuppressor != null) { noiseSuppressor.release(); noiseSuppressor = null; } } catch (Throwable ignored) {}
        try { if (agc != null) { agc.release(); agc = null; } } catch (Throwable ignored) {}
        try { if (echoCanceler != null) { echoCanceler.release(); echoCanceler = null; } } catch (Throwable ignored) {}
    }

    private void postStatus(String ru, String en) {
        Callback cb = callback;
        if (cb == null) return;
        String msg = "ru".equalsIgnoreCase(settings.language()) ? ru : en;
        main.post(() -> cb.onStatus(msg));
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Thread t = audioThread;
        audioThread = null;
        if (t != null) t.interrupt();
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
        }
        releaseAudioEffects();
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) {}
            recognizer = null;
        }
        Callback cb = callback;
        if (cb != null) main.post(() -> cb.onListeningStateChanged(false));
    }

    public void destroy() {
        stop();
        if (modelEn != null) try { modelEn.close(); } catch (Exception ignored) {}
        if (modelRu != null) try { modelRu.close(); } catch (Exception ignored) {}
        modelEn = null;
        modelRu = null;
    }

    private void startCapture() {
        if (running.get()) return;

        Model active = pickModelForLang(settings.language());
        if (active == null) {
            postError(-3, "no_model_loaded");
            return;
        }
        try {
            recognizer = new Recognizer(active, SAMPLE_RATE);
            // Encourage Vosk to emit partials.
            try { recognizer.setMaxAlternatives(0); } catch (Throwable ignored) {}
            try { recognizer.setWords(false); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "Recognizer init failed", t);
            postError(-4, "recognizer_init_failed");
            return;
        }

        currentLang = settings.language();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuf <= 0) minBuf = 4096;
        int bufSize = Math.max(minBuf * 2, 8192);

        // Try a couple of audio sources. VOICE_RECOGNITION should be best (no
        // AGC) but is broken on some custom ROMs and silently returns silence.
        // MIC is the most universal fallback.
        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };
        AudioRecord ar = null;
        SecurityException secEx = null;
        for (int src : sources) {
            try {
                AudioRecord candidate = new AudioRecord(src, SAMPLE_RATE,
                        CHANNEL, ENCODING, bufSize);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    ar = candidate;
                    activeSource = src;
                    break;
                }
                try { candidate.release(); } catch (Exception ignored) {}
            } catch (SecurityException se) {
                secEx = se;
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord init failed for source " + src + ": " + t);
            }
        }
        if (ar == null) {
            if (secEx != null) postError(-5, "no_record_permission");
            else postError(-5, "audio_record_failed");
            return;
        }
        try {
            ar.startRecording();
        } catch (Throwable t) {
            try { ar.release(); } catch (Exception ignored) {}
            postError(-6, "audio_record_start_failed: " + t.getMessage());
            return;
        }
        if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
            postError(-6, "audio_record_state=" + ar.getRecordingState());
            return;
        }
        audioRecord = ar;
        attachAudioEffects(ar.getAudioSessionId());
        running.set(true);

        postStatus("Слушаю — скажите «Джарвис, …»",
                "Listening — say \"Jarvis, …\"");

        Callback cb = callback;
        if (cb != null) main.post(() -> cb.onListeningStateChanged(!muted.get()));

        audioThread = new Thread(this::captureLoop, "Jarvis-Mic");
        audioThread.start();
    }

    private Model pickModelForLang(String lang) {
        if ("ru".equalsIgnoreCase(lang)) {
            if (modelRu != null) return modelRu;
            return modelEn;
        }
        if (modelEn != null) return modelEn;
        return modelRu;
    }

    private void captureLoop() {
        byte[] buf = new byte[3200]; // 100 ms at 16 kHz mono 16-bit
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            // Hot-swap recognizer on language change
            String want = settings.language();
            if (want != null && currentLang != null && !want.equalsIgnoreCase(currentLang)) {
                Model next = pickModelForLang(want);
                if (next != null) {
                    try {
                        if (recognizer != null) recognizer.close();
                        recognizer = new Recognizer(next, SAMPLE_RATE);
                        currentLang = want;
                        lastPartial = "";
                    } catch (Throwable t) {
                        Log.w(TAG, "Recognizer hot-swap failed", t);
                    }
                }
            }

            int n;
            try {
                n = audioRecord.read(buf, 0, buf.length);
            } catch (Throwable t) {
                Log.w(TAG, "AudioRecord.read failed", t);
                break;
            }
            if (n <= 0) continue;

            // Once per minute remind the UI we are still listening.
            long now = System.currentTimeMillis();
            if (now - lastLevelStatusMs > 60_000) {
                lastLevelStatusMs = now;
                postStatus("Слушаю — скажите «Джарвис, …»",
                        "Listening — say \"Jarvis, …\"");
            }

            if (muted.get()) {
                // Discard but keep mic open? Actually we should release it so
                // the Android mic indicator disappears. To do that we stop the
                // record entirely while muted; un-muting restarts it.
                handleMutePause();
                continue;
            }

            // Mirror the captured PCM into the rolling ring so Whisper can
            // re-transcribe the last few seconds when Vosk fires onResult.
            // Each sample is 2 bytes (PCM-16 little-endian).
            appendToRing(buf, n);

            try {
                if (recognizer.acceptWaveForm(buf, n)) {
                    String json = recognizer.getResult();
                    String text = extractText(json, "text");
                    if (text != null && !text.isEmpty()) {
                        lastPartial = "";
                        Callback cb = callback;
                        if (cb != null) {
                            String finalText = text;
                            main.post(() -> cb.onResult(finalText));
                        }
                    }
                } else {
                    String json = recognizer.getPartialResult();
                    String partial = extractText(json, "partial");
                    if (partial != null && !partial.equals(lastPartial)) {
                        lastPartial = partial;
                        Callback cb = callback;
                        if (cb != null && !partial.isEmpty()) {
                            main.post(() -> cb.onPartial(partial));
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "recognize loop error", t);
            }
        }
    }

    /**
     * When the user mutes JARVIS, we close the AudioRecord so the system mic
     * indicator goes away. We re-open it as soon as un-muted.
     */
    private void handleMutePause() {
        // Close record, wait until un-muted, re-open.
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            try { ar.release(); } catch (Exception ignored) {}
        }
        releaseAudioEffects();
        // Wait until un-muted
        while (running.get() && muted.get()) {
            try { Thread.sleep(120); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!running.get()) return;
        // Re-open
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBuf <= 0) minBuf = 4096;
        int bufSize = Math.max(minBuf * 2, 8192);
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                try { ar.release(); } catch (Exception ignored) {}
                running.set(false);
                postError(-5, "audio_record_reopen_failed");
                return;
            }
            ar.startRecording();
            audioRecord = ar;
            attachAudioEffects(ar.getAudioSessionId());
            // Reset recognizer state so old partials don't bleed into the new session.
            try { if (recognizer != null) recognizer.reset(); } catch (Throwable ignored) {}
            lastPartial = "";
        } catch (Throwable t) {
            Log.w(TAG, "Failed to reopen AudioRecord after mute", t);
            running.set(false);
            postError(-5, "audio_record_reopen_failed");
        }
    }

    /** Mirror raw PCM-16 mic bytes into the rolling buffer. */
    private void appendToRing(byte[] buf, int byteCount) {
        int sampleCount = byteCount / 2;
        if (sampleCount <= 0) return;
        synchronized (pcmRingLock) {
            int ringLen = pcmRing.length;
            for (int i = 0; i < sampleCount; i++) {
                int lo = buf[i * 2]     & 0xFF;
                int hi = buf[i * 2 + 1] & 0xFF;
                short s = (short) ((hi << 8) | lo);
                pcmRing[(pcmRingWrite + i) % ringLen] = s;
            }
            pcmRingWrite += sampleCount;
        }
    }

    /**
     * Returns up to the last {@code seconds} seconds of mic audio captured so
     * far, as a freshly-allocated PCM-16 mono 16 kHz buffer. Returns an empty
     * array if nothing has been captured yet. Safe to call from any thread.
     */
    public short[] lastPcm16(int seconds) {
        if (seconds <= 0) return new short[0];
        if (seconds > RING_SECONDS) seconds = RING_SECONDS;
        int wanted = SAMPLE_RATE * seconds;
        synchronized (pcmRingLock) {
            int have = Math.min(pcmRingWrite, pcmRing.length);
            int n = Math.min(wanted, have);
            if (n <= 0) return new short[0];
            short[] out = new short[n];
            int ringLen = pcmRing.length;
            int startIdx = pcmRingWrite - n;
            for (int i = 0; i < n; i++) {
                int idx = (startIdx + i) % ringLen;
                if (idx < 0) idx += ringLen;
                out[i] = pcmRing[idx];
            }
            return out;
        }
    }

    private static String extractText(String json, String field) {
        try {
            JSONObject o = new JSONObject(json);
            return o.optString(field, "").trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void postError(int code, String msg) {
        Callback cb = callback;
        if (cb != null) main.post(() -> cb.onError(code, msg));
    }

    public boolean isAvailable() {
        // We control the entire stack — mic + Vosk model. As long as the assets
        // unpack OK this is always true. Caller may still get an error during
        // unpack via onError.
        File internal = new File(ctx.getFilesDir(), "vosk-models");
        return internal.exists() || true;
    }
}
