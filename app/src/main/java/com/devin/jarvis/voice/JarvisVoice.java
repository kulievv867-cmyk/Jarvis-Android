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

    private final EdgeTts edge;
    private final Mp3Decoder mp3 = new Mp3Decoder();
    private final TtsCache cache;

    public JarvisVoice(Context ctx, boolean modulation, Runnable onReady) {
        this.ctx = ctx.getApplicationContext();
        this.modulationEnabled = modulation;
        this.settings = new Settings(this.ctx);
        this.edge = new EdgeTts(this.settings);
        this.cache = new TtsCache(this.ctx);

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
                    // "Fast TTS" mode: use Android's on-device TTS engine
                    // directly. Synthesis is ~100 ms vs Edge's ~500–1500 ms
                    // network round-trip. Sacrifices the cinematic British
                    // butler character for snappy responsiveness.
                    boolean played = false;
                    if (settings != null && settings.fastTts()) {
                        synthesizeAndPlayViaSystem(text);
                        played = true;
                    }
                    if (!played) played = synthesizeAndPlayViaEdge(text);
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
            String rate = "-8%";
            String pitch = "-2Hz";
            byte[] mp3Bytes = null;
            // Disk cache: short, frequently-spoken phrases («Слушаю.»,
            // «Готово.», «Открываю Telegram.») get re-rendered the
            // same way every time. Caching the MP3 by content+voice cuts
            // perceived latency for these acks from ~700 ms to <50 ms.
            if (cache != null && cache.isCacheable(text)) {
                mp3Bytes = cache.get(text, voice, rate, pitch);
            }
            if (mp3Bytes == null) {
                // Slow + slightly lower pitch puts Edge TTS into "calm British
                // butler" territory — closer to film-Jarvis than the default
                // perky news-reader cadence.
                mp3Bytes = edge.synthesize(text, voice, rate, pitch);
                if (cache != null && mp3Bytes != null && cache.isCacheable(text)) {
                    cache.put(text, voice, rate, pitch, mp3Bytes);
                }
            }
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

    /**
     * Pre-renders the very short phrases that Jarvis says repeatedly
     * («Слушаю.», «Сэр?», «Готово.», «Слушаю, сэр.» …) so that the first
     * audible response after a wake-word feels instantaneous instead of
     * being gated by the ~600–1500 ms Edge TTS round-trip. Cached MP3 is
     * keyed by (text, voice, rate, pitch); subsequent renders are a
     * sub-50 ms disk read.
     *
     * Runs on its own daemon thread so service startup is not blocked.
     */
    public void prewarmCommonPhrases() {
        if (cache == null || edge == null) return;
        Thread t = new Thread(() -> {
            try {
                String[] phrasesRu = new String[] {
                        "Слушаю.", "Слушаю, сэр.", "Сэр?", "Да, сэр.",
                        "Готово.", "Сделано.", "Минуту.", "Записал.",
                        "Конечно.", "Сейчас."
                };
                String[] phrasesEn = new String[] {
                        "Yes, sir.", "Sir?", "Done.", "Right away.",
                        "Listening.", "Of course.", "One moment.", "Noted."
                };
                String[] all = new String[phrasesRu.length + phrasesEn.length];
                System.arraycopy(phrasesRu, 0, all, 0, phrasesRu.length);
                System.arraycopy(phrasesEn, 0, all, phrasesRu.length, phrasesEn.length);
                String rate = "-8%";
                String pitch = "-2Hz";
                for (String p : all) {
                    if (!alive.get()) return;
                    String voice = looksRussian(p)
                            ? EdgeTts.VOICE_RUSSIAN_MALE
                            : EdgeTts.VOICE_BRITISH_MALE;
                    if (cache.get(p, voice, rate, pitch) != null) continue;
                    try {
                        byte[] mp3Bytes = edge.synthesize(p, voice, rate, pitch);
                        if (mp3Bytes != null && mp3Bytes.length >= 64) {
                            cache.put(p, voice, rate, pitch, mp3Bytes);
                        }
                    } catch (Throwable ignored) {
                        // Network / proxy unavailable — skip silently and
                        // keep going with the next phrase.
                    }
                }
            } catch (Throwable t2) {
                Log.w(TAG, "prewarm failed: " + t2.getMessage());
            }
        }, "Jarvis-Voice-Prewarm");
        t.setDaemon(true);
        t.start();
    }

    private static boolean looksRussian(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0400 && c <= 0x04FF) return true;
        }
        return false;
    }

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
        // Split the reply into sentences and queue each one separately. The
        // worker synthesises sentence N+1 while the user is still hearing
        // sentence N, so perceived latency is dominated by the *first*
        // sentence — which is typically very short ("Готово.", "Слушаю.")
        // and therefore comes out of Edge TTS in well under a second.
        for (String chunk : splitForStreaming(text)) {
            requests.offer(chunk);
        }
    }

    /**
     * Append a chunk to the speech queue without interrupting the current
     * utterance. Used for streaming LLM replies so the next sentence is
     * synthesised while the previous one is still playing.
     */
    public void speakAppend(String text) {
        if (text == null || text.isEmpty()) return;
        if (!alive.get()) return;
        for (String chunk : splitForStreaming(text)) {
            requests.offer(chunk);
        }
    }

    /**
     * Split a reply into chunks no larger than ~120 characters, breaking on
     * sentence boundaries. This lets the speech worker synthesise + play
     * the first chunk while the rest is still in flight, halving perceived
     * latency on multi-sentence answers.
     */
    private static java.util.List<String> splitForStreaming(String text) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (text == null) return out;
        text = text.trim();
        if (text.isEmpty()) return out;
        // Short text — no point splitting.
        if (text.length() <= 80) {
            out.add(text);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            boolean atBoundary = (c == '.' || c == '!' || c == '?' || c == '…')
                    && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)));
            if (atBoundary && cur.length() >= 24) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else if (cur.length() > 220) {
                // No sentence boundary in sight; soft-break on a comma or
                // space so we still flush something to TTS reasonably soon.
                int comma = lastIndexOf(cur, ',');
                int space = lastIndexOf(cur, ' ');
                int cut = comma > 0 ? comma + 1 : (space > 0 ? space : -1);
                if (cut > 80) {
                    out.add(cur.substring(0, cut).trim());
                    String rem = cur.substring(cut).trim();
                    cur.setLength(0);
                    cur.append(rem);
                }
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        if (out.isEmpty()) out.add(text);
        return out;
    }

    private static int lastIndexOf(StringBuilder sb, char ch) {
        for (int i = sb.length() - 1; i >= 0; i--) if (sb.charAt(i) == ch) return i;
        return -1;
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

    /** Apply the Jarvis modulation chain in-place on float samples [-1, 1].
     *  Tuning notes:
     *   - HP at 70 Hz removes mic-style rumble without thinning the chest.
     *   - Slight warm bump (130 Hz, +2 dB) for chest resonance.
     *   - Presence (3 kHz, +3 dB) gives Bettany-like consonant clarity.
     *   - Pitch ratio 0.94 takes Dmitry/Ryan a touch deeper — the movie
     *     Jarvis is noticeably below ordinary newsreader pitch.
     *   - Reverb at 0.10 wet sounds like "next to you" rather than "in a
     *     cathedral" — the previous 0.18 was too washy and made each
     *     reply feel slow.
     */
    private void applyDsp(float[] samples, int sr) {
        Biquad hp = new Biquad();
        hp.setHighpass(sr, 70f, 0.707f);
        Biquad warm = new Biquad();
        warm.setPeak(sr, 130f, 0.9f, 2.0f);
        Biquad presence = new Biquad();
        presence.setPeak(sr, 3000f, 1.1f, 3.0f);
        Biquad tame = new Biquad();
        tame.setPeak(sr, 6500f, 0.9f, -2.0f);
        PitchShifter pitch = new PitchShifter(1024, 4);
        pitch.setRatio(0.94f);
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
                v = v * 0.90f + wet * 0.10f;
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
            // No tail-sleep here. AudioTrack.MODE_STREAM blocks in write()
            // until each buffer is consumed, so by the time we exit the
            // loop above the speech has already played out. The previous
            // 60 ms sleep was just dead time before starting the next
            // chunk.
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
