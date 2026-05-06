package com.devin.jarvis.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.devin.jarvis.core.Settings;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Jarvis voice" pipeline.
 *
 * Primary path:
 *   text -> {@link EdgeTts} (Microsoft Edge neural TTS over WebSocket) -> MP3
 *        -> {@link Mp3Decoder} -> float PCM
 *        -> DSP chain -> {@link AudioTrack}
 *
 * Fallback path (used only if Edge TTS errors out, e.g. no internet):
 *   text -> system TextToSpeech.synthesizeToFile() -> WAV -> DSP chain -> AudioTrack
 *
 * The DSP chain mimics the JARVIS character (warmth, presence peak, slight pitch
 * shift, small reverb).
 */
public class JarvisVoice {

    private static final String TAG = "Jarvis.Voice";

    public interface Listener {
        void onSpeechStart();
        void onSpeechDone();
    }

    private final Context ctx;
    private TextToSpeech tts;
    private final AtomicBoolean ttsReady = new AtomicBoolean(false);
    private boolean modulationEnabled = true;
    private boolean reverbEnabled = true;

    private Listener listener;
    private final AtomicLong utterIdGen = new AtomicLong(1);
    private final Map<String, String> pendingWavs = new HashMap<>();
    private Thread workerThread;
    private final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private volatile AudioTrack currentTrack;
    private volatile Settings settings;

    private final EdgeTts edge = new EdgeTts();
    private final Mp3Decoder mp3 = new Mp3Decoder();

    public JarvisVoice(Context ctx, boolean modulation, Runnable onReady) {
        this.ctx = ctx.getApplicationContext();
        this.modulationEnabled = modulation;
        this.settings = new Settings(this.ctx);

        // System TTS is initialized only as a fallback. We don't fail-hard if
        // it can't init — Edge TTS is our primary backend.
        try {
            this.tts = new TextToSpeech(this.ctx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    configureSystemVoice();
                    ttsReady.set(true);
                } else {
                    Log.i(TAG, "System TTS init failed (will use Edge TTS only): " + status);
                }
                if (onReady != null) onReady.run();
            });
            if (this.tts != null) {
                this.tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onError(String id) { cleanupOnError(id); }
                    @Override public void onError(String id, int errorCode) { cleanupOnError(id); }
                    @Override public void onDone(String id) {
                        // No-op: we synthesize to file and process synchronously
                        // in the worker thread, so the file is read after this fires.
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "System TTS unavailable", t);
            tts = null;
            if (onReady != null) onReady.run();
        }

        // The worker thread is the single point where we serialize all speech
        // synthesis + playback. Speak requests are queued and processed
        // sequentially.
        startWorker();
    }

    private void startWorker() {
        workerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (alive.get()) {
                String text;
                try {
                    text = requests.take();
                } catch (InterruptedException e) {
                    if (!alive.get()) return;
                    Thread.currentThread().interrupt();
                    continue;
                }
                if (text == null || text.isEmpty()) continue;
                Listener l = listener;
                if (l != null) l.onSpeechStart();
                try {
                    boolean played = synthesizeAndPlayViaEdge(text);
                    if (!played) {
                        synthesizeAndPlayViaSystem(text);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "speak failed", t);
                } finally {
                    if (l != null) l.onSpeechDone();
                }
            }
        }, "Jarvis-Voice-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private boolean synthesizeAndPlayViaEdge(String text) {
        try {
            String voice = "ru".equalsIgnoreCase(settings.language())
                    ? EdgeTts.VOICE_RUSSIAN_MALE
                    : EdgeTts.VOICE_BRITISH_MALE;
            byte[] mp3Bytes = edge.synthesize(text, voice, "-3%", "+0Hz");
            if (mp3Bytes == null || mp3Bytes.length < 64) {
                Log.w(TAG, "Edge TTS returned no audio");
                return false;
            }
            File scratch = new File(ctx.getCacheDir(), "tts");
            if (!scratch.exists()) //noinspection ResultOfMethodCallIgnored
                scratch.mkdirs();
            Mp3Decoder.Result decoded = mp3.decode(mp3Bytes, scratch);
            if (decoded.samples == null || decoded.samples.length == 0) {
                Log.w(TAG, "Edge TTS decoded to 0 samples");
                return false;
            }
            float[] samples = decoded.samples;
            if (modulationEnabled) applyDsp(samples, decoded.sampleRate);
            playFloats(samples, decoded.sampleRate);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Edge TTS path failed: " + t.getMessage());
            return false;
        }
    }

    private void synthesizeAndPlayViaSystem(String text) {
        if (tts == null || !ttsReady.get()) {
            Log.w(TAG, "No TTS backend available");
            return;
        }
        try {
            File dir = new File(ctx.getCacheDir(), "tts");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            File wav = new File(dir, "tts_" + System.nanoTime() + ".wav");
            String uid = "u" + utterIdGen.getAndIncrement();
            Bundle p = new Bundle();
            p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            int rc = tts.synthesizeToFile(text, p, wav, uid);
            if (rc != TextToSpeech.SUCCESS) {
                // Last-ditch: just speak() through system TTS (no DSP).
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, uid);
                return;
            }
            // Wait for the file to be fully written
            long deadline = System.currentTimeMillis() + 30_000;
            long lastSize = -1;
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); return;
                }
                long s = wav.length();
                if (s > 0 && s == lastSize) break;
                lastSize = s;
            }
            Wav data = readWav(wav);
            if (data == null) return;
            if (modulationEnabled) applyDsp(data.samples, data.sampleRate);
            playFloats(data.samples, data.sampleRate);
            try { //noinspection ResultOfMethodCallIgnored
                wav.delete(); } catch (Exception ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "System TTS fallback failed", t);
        }
    }

    private void configureSystemVoice() {
        if (tts == null) return;
        try {
            int code = tts.setLanguage(Locale.UK);
            if (code < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US);
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                Voice best = null;
                for (Voice v : voices) {
                    if (v == null || v.getLocale() == null) continue;
                    String lang = v.getLocale().toLanguageTag().toLowerCase();
                    String name = v.getName() == null ? "" : v.getName().toLowerCase();
                    boolean gb = lang.startsWith("en-gb");
                    boolean male = name.contains("male") || name.contains("-m-")
                            || name.contains("uk-male") || name.contains("british-male")
                            || name.contains("rishi") || name.contains("eng-gbr-x-gba");
                    if (gb && male) { best = v; break; }
                    if (gb && best == null) best = v;
                }
                if (best != null) tts.setVoice(best);
            }
            tts.setPitch(0.96f);
            tts.setSpeechRate(0.97f);
        } catch (Exception e) {
            Log.w(TAG, "configureVoice", e);
        }
    }

    public void setModulation(boolean v) { this.modulationEnabled = v; }
    public void setReverb(boolean v) { this.reverbEnabled = v; }
    public boolean isReady() { return alive.get(); }
    public void setListener(Listener l) { this.listener = l; }

    /** True iff there are no pending utterance chunks queued. */
    public boolean isQueueEmpty() { return requests.isEmpty(); }

    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!alive.get()) return;
        // Stop any ongoing playback so the new phrase takes priority.
        AudioTrack at = currentTrack;
        if (at != null) {
            try { at.pause(); at.flush(); at.stop(); } catch (Exception ignored) {}
        }
        // Drop any queued text to avoid backing up an angry queue if the user
        // talks fast.
        requests.clear();
        requests.offer(text);
    }

    /**
     * Append a chunk to the speech queue without interrupting the current
     * utterance. Used for streaming LLM replies so the next sentence is
     * synthesised while the previous one is still playing.
     */
    public void speakAppend(String text) {
        if (text == null || text.isEmpty()) return;
        if (!alive.get()) return;
        requests.offer(text);
    }

    public void stop() {
        AudioTrack at = currentTrack;
        if (at != null) {
            try { at.pause(); at.flush(); at.stop(); } catch (Exception ignored) {}
        }
        requests.clear();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) {}
    }

    public void shutdown() {
        alive.set(false);
        AudioTrack at = currentTrack;
        if (at != null) {
            try { at.pause(); at.flush(); at.stop(); at.release(); } catch (Exception ignored) {}
        }
        requests.clear();
        if (workerThread != null) workerThread.interrupt();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) {}
        tts = null;
    }

    private void cleanupOnError(String id) {
        String path = pendingWavs.remove(id);
        if (path != null) {
            try { //noinspection ResultOfMethodCallIgnored
                new File(path).delete(); } catch (Exception ignored) {}
        }
    }

    /** Apply the Jarvis modulation chain in-place on float samples [-1, 1]. */
    private void applyDsp(float[] samples, int sr) {
        Biquad hp = new Biquad();
        hp.setHighpass(sr, 80f, 0.707f);
        Biquad warm = new Biquad();
        warm.setPeak(sr, 130f, 0.9f, 2.0f);
        Biquad presence = new Biquad();
        presence.setPeak(sr, 3000f, 1.1f, 3.0f);
        Biquad tame = new Biquad();
        tame.setPeak(sr, 6500f, 0.9f, -2.0f);
        PitchShifter pitch = new PitchShifter(1024, 4);
        pitch.setRatio(0.961f);
        Reverb reverb = new Reverb(sr);

        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            v = hp.process(v);
            v = warm.process(v);
            v = pitch.process(v);
            v = presence.process(v);
            v = tame.process(v);
            v = (float) Math.tanh(v * 1.35);
            if (reverbEnabled) {
                float wet = reverb.process(v);
                v = v * 0.85f + wet * 0.18f;
            }
            if (v > 0.985f) v = 0.985f;
            else if (v < -0.985f) v = -0.985f;
            samples[i] = v;
        }
    }

    private void playFloats(float[] samples, int sr) {
        int chBuf = AudioTrack.getMinBufferSize(sr,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (chBuf <= 0) chBuf = 4096;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        AudioTrack at = new AudioTrack(attrs, fmt, Math.max(chBuf, 8192),
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        currentTrack = at;
        try {
            at.play();
            short[] buf = new short[2048];
            int idx = 0;
            while (idx < samples.length && alive.get()) {
                int n = Math.min(buf.length, samples.length - idx);
                for (int i = 0; i < n; i++) {
                    float s = samples[idx + i];
                    int v = (int) (s * 32767f);
                    if (v > 32767) v = 32767;
                    if (v < -32768) v = -32768;
                    buf[i] = (short) v;
                }
                int written = at.write(buf, 0, n);
                if (written < 0) break;
                idx += n;
            }
            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        } finally {
            currentTrack = null;
            try { at.stop(); } catch (Exception ignored) {}
            try { at.release(); } catch (Exception ignored) {}
        }
    }

    // --- WAV parsing ---

    private static class Wav {
        int sampleRate;
        int channels;
        float[] samples; // mono float [-1,1]
    }

    private static Wav readWav(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            if (!(hdr[0] == 'R' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == 'F'
                    && hdr[8] == 'W' && hdr[9] == 'A' && hdr[10] == 'V' && hdr[11] == 'E')) {
                Log.w(TAG, "not a WAV: " + f);
                return null;
            }
            Wav w = new Wav();
            byte[] chunkHdr = new byte[8];
            int bitsPerSample = 16;
            byte[] dataBytes = null;
            while (raf.getFilePointer() + 8 <= raf.length()) {
                raf.readFully(chunkHdr);
                int size = ((chunkHdr[4] & 0xFF))
                        | ((chunkHdr[5] & 0xFF) << 8)
                        | ((chunkHdr[6] & 0xFF) << 16)
                        | ((chunkHdr[7] & 0xFF) << 24);
                if (chunkHdr[0] == 'f' && chunkHdr[1] == 'm' && chunkHdr[2] == 't' && chunkHdr[3] == ' ') {
                    byte[] fmt = new byte[size];
                    raf.readFully(fmt);
                    ByteBuffer bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    int audioFormat = bb.getShort() & 0xFFFF;
                    int ch = bb.getShort() & 0xFFFF;
                    int sr = bb.getInt();
                    bb.getInt(); // byte rate
                    bb.getShort(); // block align
                    int bps = bb.getShort() & 0xFFFF;
                    w.channels = ch;
                    w.sampleRate = sr;
                    bitsPerSample = bps;
                    if (audioFormat != 1) {
                        Log.w(TAG, "non-PCM WAV, format=" + audioFormat);
                    }
                } else if (chunkHdr[0] == 'd' && chunkHdr[1] == 'a' && chunkHdr[2] == 't' && chunkHdr[3] == 'a') {
                    dataBytes = new byte[size];
                    raf.readFully(dataBytes);
                    break;
                } else {
                    raf.skipBytes(size);
                }
            }
            if (dataBytes == null) return null;
            int bytesPerSample = bitsPerSample / 8;
            int frames = dataBytes.length / (bytesPerSample * w.channels);
            float[] out = new float[frames];
            ByteBuffer bb = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bitsPerSample == 16) {
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < w.channels; c++) sum += bb.getShort();
                    out[i] = (sum / (float) w.channels) / 32768f;
                }
            } else if (bitsPerSample == 8) {
                for (int i = 0; i < frames; i++) {
                    int sum = 0;
                    for (int c = 0; c < w.channels; c++) sum += ((bb.get() & 0xFF) - 128);
                    out[i] = (sum / (float) w.channels) / 128f;
                }
            } else {
                Log.w(TAG, "unsupported bits per sample: " + bitsPerSample);
                return null;
            }
            w.samples = out;
            return w;
        } catch (Exception e) {
            Log.w(TAG, "readWav failed", e);
            return null;
        }
    }
}
