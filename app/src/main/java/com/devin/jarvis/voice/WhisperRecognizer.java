package com.devin.jarvis.voice;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.devin.jarvis.core.NetClient;
import com.devin.jarvis.core.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level wrapper for whisper.cpp inference.
 *
 *  • Selectable model size: "base" (≈57 MB, sub-second on flagships, weaker
 *    on Russian), "small" (≈181 MB, ~1–2 sec, good Russian accuracy),
 *    "large-turbo" (≈547 MB, ~3–5 sec, top accuracy). The active model is
 *    read from {@link Settings#whisperModel()} on each loadAsync; switching
 *    models in Settings frees the previous context and downloads the new
 *    one on demand.
 *  • Holds a single long-lived whisper context on a dedicated background
 *    thread to avoid model-load cost per command.
 *  • Exposes {@link #transcribePcm16Sync(short[], String)} for callers that
 *    already have a 16 kHz mono PCM-16 buffer.
 *
 * If the native lib fails to load every call returns {@code null} and the
 * caller is expected to fall back to Vosk.
 */
public final class WhisperRecognizer {

    private static final String TAG = "Jarvis-Whisper";

    /** Available model variants. The {@code id} is what we persist in
     *  Settings; the {@code fileName} doubles as the filename on disk and
     *  the path under the HF repo. */
    public enum Variant {
        BASE     ("base",        "ggml-base-q5_1.bin",          59_707_625L,  "Base (быстро, ~57 МБ)"),
        SMALL    ("small",       "ggml-small-q5_1.bin",        190_085_487L,  "Small (баланс, ~181 МБ)"),
        LARGE    ("large-turbo", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "Large-v3-turbo (точно, ~547 МБ)");

        public final String id;
        public final String fileName;
        public final long sizeBytes;
        public final String displayLabel;

        Variant(String id, String fileName, long sizeBytes, String displayLabel) {
            this.id = id;
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.displayLabel = displayLabel;
        }

        public String url() {
            return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + fileName;
        }

        /** Returns the variant matching {@code id}, defaulting to BASE. */
        public static Variant byId(String id) {
            if (id == null) return BASE;
            for (Variant v : values()) if (v.id.equals(id)) return v;
            return BASE;
        }
    }

    public interface ProgressListener {
        /** Called on the main thread. */
        void onProgress(long downloadedBytes, long totalBytes);
        /** Called on the main thread once the model is loaded and ready. */
        void onReady();
        /** Called on the main thread on any failure. */
        void onError(String message);
    }

    private static volatile WhisperRecognizer INSTANCE;

    public static WhisperRecognizer get(Context ctx) {
        WhisperRecognizer w = INSTANCE;
        if (w == null) {
            synchronized (WhisperRecognizer.class) {
                w = INSTANCE;
                if (w == null) {
                    INSTANCE = w = new WhisperRecognizer(ctx.getApplicationContext());
                }
            }
        }
        return w;
    }

    private final Context appCtx;
    private final HandlerThread thread;
    private final Handler handler;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong contextPtr = new AtomicLong(0L);
    private volatile boolean loadAttempted = false;
    private volatile boolean ready = false;
    private volatile boolean downloading = false;
    private volatile String lastError = "";
    private volatile Variant loadedVariant = null;
    private final List<ProgressListener> progressListeners = new ArrayList<>();

    private WhisperRecognizer(Context appCtx) {
        this.appCtx = appCtx;
        this.thread = new HandlerThread("Jarvis-Whisper");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public boolean isReady() { return ready; }
    public boolean isDownloading() { return downloading; }
    public String lastError() { return lastError; }

    public synchronized void addProgressListener(ProgressListener l) {
        if (l != null && !progressListeners.contains(l)) progressListeners.add(l);
    }
    public synchronized void removeProgressListener(ProgressListener l) {
        progressListeners.remove(l);
    }

    /** The variant the user has selected in Settings (BASE by default). */
    private Variant selectedVariant() {
        try {
            return Variant.byId(new Settings(appCtx).whisperModel());
        } catch (Throwable t) {
            return Variant.BASE;
        }
    }

    /** Returns true iff the currently-selected model file is on disk at full size. */
    public boolean isModelDownloaded() {
        return isModelDownloaded(selectedVariant());
    }

    public boolean isModelDownloaded(Variant v) {
        return modelFile(v).length() == v.sizeBytes;
    }

    /** Path to the on-disk model file (may not exist yet). */
    public File modelFile() { return modelFile(selectedVariant()); }
    public File modelFile(Variant v) {
        File outDir = new File(appCtx.getFilesDir(), "whisper");
        return new File(outDir, v.fileName);
    }

    /** Free disk reclaim helper. Removes ALL cached model variants — the
     *  user explicitly asked to reclaim space, so we don't leave any
     *  half-present model behind. Runs on the worker thread so the main
     *  thread isn't blocked by {@link WhisperJni#freeContext} for a 500 MB
     *  large-turbo model — that block previously turned a "switch model"
     *  click in Settings into a 1-second freeze and an ANR / SIGSEGV crash
     *  if a transcription was running at the same moment. */
    public void deleteCachedModel() {
        handler.post(this::deleteCachedModelOnWorker);
    }

    private synchronized void deleteCachedModelOnWorker() {
        long ptr = contextPtr.getAndSet(0L);
        if (ptr != 0L) {
            try { WhisperJni.freeContext(ptr); } catch (Throwable ignored) {}
        }
        ready = false;
        loadedVariant = null;
        loadAttempted = false;
        File outDir = new File(appCtx.getFilesDir(), "whisper");
        if (outDir.exists()) {
            File[] all = outDir.listFiles();
            if (all != null) {
                for (File f : all) {
                    try { f.delete(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * Asynchronously download (if needed) + load the currently-selected
     * model. Safe to call multiple times; if the loaded variant differs
     * from the user's current selection, the old context is freed and the
     * new one loaded transparently.
     */
    public void loadAsync() {
        Variant target = selectedVariant();
        if (loadAttempted && ready && target == loadedVariant) return;
        loadAttempted = true;
        handler.post(() -> loadOnWorker(target));
    }

    private synchronized void loadOnWorker(Variant target) {
        try {
            if (!WhisperJni.ensureLoaded()) {
                fail("native lib failed: " + WhisperJni.lastLoadError());
                return;
            }
            // If a different variant was previously loaded, free its
            // context before proceeding so we don't keep two ~500 MB
            // models in RAM at the same time.
            if (loadedVariant != null && loadedVariant != target) {
                long old = contextPtr.getAndSet(0L);
                if (old != 0L) {
                    try { WhisperJni.freeContext(old); } catch (Throwable ignored) {}
                }
                ready = false;
                loadedVariant = null;
            }
            File f = ensureModelDownloaded(target);
            if (f == null) {
                // ensureModelDownloaded already called fail(...) with details.
                return;
            }
            long ptr = WhisperJni.initContext(f.getAbsolutePath());
            if (ptr == 0L) {
                fail("whisper_init returned NULL — model file may be corrupted");
                return;
            }
            contextPtr.set(ptr);
            loadedVariant = target;
            ready = true;
            Log.i(TAG, "Whisper " + target.id + " ready. " + WhisperJni.systemInfo());
            postReady();
        } catch (Throwable t) {
            fail("load failed: " + t);
            Log.e(TAG, "load failed", t);
        }
    }

    /** Synchronously transcribe a mono 16 kHz PCM-16 buffer. Returns null on failure.
     *  <p>Synchronized on this so the native context can't be freed mid-call
     *  if the user switches the model from Settings while a recording is being
     *  transcribed — that race used to crash the app with SIGSEGV when the
     *  user toggled Whisper Base. */
    public String transcribePcm16Sync(short[] pcm16, String lang) {
        if (!ready) return null;
        long ptr;
        synchronized (this) {
            if (!ready) return null;
            ptr = contextPtr.get();
            if (ptr == 0L) return null;
            float[] pcmF = new float[pcm16.length];
            for (int i = 0; i < pcm16.length; i++) {
                pcmF[i] = pcm16[i] / 32768.0f;
            }
            try {
                int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
                String text = WhisperJni.transcribe(ptr, pcmF, lang == null ? "ru" : lang, threads);
                return text == null ? "" : text.trim();
            } catch (Throwable t) {
                Log.e(TAG, "transcribe threw", t);
                return null;
            }
        }
    }

    /** Releases the native context. */
    public synchronized void close() {
        long ptr = contextPtr.getAndSet(0L);
        if (ptr != 0L) {
            try { WhisperJni.freeContext(ptr); } catch (Throwable ignored) {}
        }
        ready = false;
        loadedVariant = null;
    }

    // ---------------------------------------------------------------------
    // Download + cache
    // ---------------------------------------------------------------------

    private File ensureModelDownloaded(Variant v) {
        File outDir = new File(appCtx.getFilesDir(), "whisper");
        if (!outDir.exists()) outDir.mkdirs();
        File out = new File(outDir, v.fileName);

        if (out.exists() && out.length() == v.sizeBytes) {
            return out;
        }
        Log.i(TAG, "Downloading whisper model from " + v.url());
        downloading = true;
        try {
            File tmp = new File(outDir, v.fileName + ".part");
            URL url = new URL(v.url());
            // Honour the user's configured proxy (if any) so the multi-MB
            // model download still works in countries where huggingface.co
            // is blocked or throttled.
            java.net.Proxy proxy = NetClient.javaNetProxy(new Settings(appCtx));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Jarvis-Android/1.0 (whisper.cpp)");
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                fail("model download HTTP " + code);
                return null;
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) total = v.sizeBytes;

            try (InputStream in = conn.getInputStream();
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1024 * 64];
                long downloaded = 0;
                long lastReported = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    downloaded += n;
                    long step = Math.max(total / 100, 1);
                    if (downloaded - lastReported >= step) {
                        lastReported = downloaded;
                        postProgress(downloaded, total);
                    }
                }
                os.flush();
                postProgress(downloaded, total);
            }
            long got = tmp.length();
            if (v.sizeBytes > 0 && got != v.sizeBytes) {
                Log.w(TAG, "Downloaded " + v.id + " size " + got
                        + " bytes (expected " + v.sizeBytes + ")");
            }
            try { out.delete(); } catch (Throwable ignored) {}
            if (!tmp.renameTo(out)) {
                fail("rename of downloaded model failed");
                return null;
            }
            Log.i(TAG, "Whisper " + v.id + " downloaded: " + out.length() + " bytes");
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "download failed", t);
            fail("download failed: " + t);
            return null;
        } finally {
            downloading = false;
        }
    }

    // ---------------------------------------------------------------------
    // Listener dispatch (always on main)
    // ---------------------------------------------------------------------

    private void postProgress(long downloaded, long total) {
        main.post(() -> {
            List<ProgressListener> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(progressListeners); }
            for (ProgressListener l : snapshot) l.onProgress(downloaded, total);
        });
    }
    private void postReady() {
        main.post(() -> {
            List<ProgressListener> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(progressListeners); }
            for (ProgressListener l : snapshot) l.onReady();
        });
    }
    private void fail(String message) {
        Log.w(TAG, "Whisper failed: " + message);
        lastError = message;
        ready = false;
        downloading = false;
        main.post(() -> {
            List<ProgressListener> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(progressListeners); }
            for (ProgressListener l : snapshot) l.onError(message);
        });
    }
}
