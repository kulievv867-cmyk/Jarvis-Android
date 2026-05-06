package com.devin.jarvis.voice;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

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
 *  • Downloads the ggml-large-v3-turbo-q5_0 model (~547 MB) from
 *    huggingface.co on first use; the file is cached in the app's filesDir
 *    so it's downloaded only once.
 *  • Holds a single long-lived whisper context on a dedicated background
 *    thread to avoid the ~3 sec model-load cost per command.
 *  • Exposes {@link #transcribePcm16Sync(short[], String)} for callers that
 *    already have a 16 kHz mono PCM-16 buffer.
 *
 * If the native lib fails to load (very old devices, missing NEON, OOM at
 * model load) every call returns {@code null} and the caller is expected to
 * fall back to Vosk.
 */
public final class WhisperRecognizer {

    private static final String TAG = "Jarvis-Whisper";

    /** HuggingFace download URL for the ggml model. */
    public static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin";

    /** Filename under filesDir/whisper/ */
    public static final String MODEL_FILE  = "ggml-large-v3-turbo-q5_0.bin";

    /** Expected size of the bundled model, in bytes. Used to detect partial
     *  downloads. Pinned to the size we observed on HF (574_041_195 bytes). */
    public static final long MODEL_SIZE_BYTES = 574_041_195L;

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

    /** Returns true iff the model file is already on disk at full size. */
    public boolean isModelDownloaded() {
        return modelFile().length() == MODEL_SIZE_BYTES;
    }

    /** Returns the local model file path, regardless of whether it exists yet. */
    public File modelFile() {
        File outDir = new File(appCtx.getFilesDir(), "whisper");
        return new File(outDir, MODEL_FILE);
    }

    /** Free disk reclaim helper. */
    public synchronized void deleteCachedModel() {
        close();
        loadAttempted = false;
        File f = modelFile();
        try { if (f.exists()) f.delete(); } catch (Throwable ignored) {}
    }

    /**
     * Asynchronously download (if needed) + load the model. Safe to call
     * multiple times; only the first call performs work. Listeners attached
     * via {@link #addProgressListener} get progress callbacks.
     */
    public void loadAsync() {
        if (loadAttempted) return;
        loadAttempted = true;
        handler.post(this::loadOnWorker);
    }

    private void loadOnWorker() {
        try {
            if (!WhisperJni.ensureLoaded()) {
                fail("native lib failed: " + WhisperJni.lastLoadError());
                return;
            }
            File f = ensureModelDownloaded();
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
            ready = true;
            Log.i(TAG, "Whisper ready. " + WhisperJni.systemInfo());
            postReady();
        } catch (Throwable t) {
            fail("load failed: " + t);
            Log.e(TAG, "load failed", t);
        }
    }

    /** Synchronously transcribe a mono 16 kHz PCM-16 buffer. Returns null on failure. */
    public String transcribePcm16Sync(short[] pcm16, String lang) {
        if (!ready) return null;
        long ptr = contextPtr.get();
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

    /** Releases the native context. */
    public synchronized void close() {
        long ptr = contextPtr.getAndSet(0L);
        if (ptr != 0L) {
            try { WhisperJni.freeContext(ptr); } catch (Throwable ignored) {}
        }
        ready = false;
    }

    // ---------------------------------------------------------------------
    // Download + cache
    // ---------------------------------------------------------------------

    private File ensureModelDownloaded() {
        File outDir = new File(appCtx.getFilesDir(), "whisper");
        if (!outDir.exists()) outDir.mkdirs();
        File out = new File(outDir, MODEL_FILE);

        if (out.exists() && out.length() == MODEL_SIZE_BYTES) {
            return out;
        }
        Log.i(TAG, "Downloading whisper model from " + MODEL_URL);
        downloading = true;
        try {
            File tmp = new File(outDir, MODEL_FILE + ".part");
            URL url = new URL(MODEL_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            if (total <= 0) total = MODEL_SIZE_BYTES;

            try (InputStream in = conn.getInputStream();
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1024 * 64];
                long downloaded = 0;
                long lastReported = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    downloaded += n;
                    // Throttle progress callbacks to once per ~1% so we don't
                    // flood the UI thread.
                    long step = Math.max(total / 100, 1);
                    if (downloaded - lastReported >= step) {
                        lastReported = downloaded;
                        postProgress(downloaded, total);
                    }
                }
                os.flush();
                postProgress(downloaded, total);
            }
            // Verify size matches expected so we don't load a truncated file.
            long got = tmp.length();
            if (MODEL_SIZE_BYTES > 0 && got != MODEL_SIZE_BYTES) {
                Log.w(TAG, "Downloaded model size " + got
                        + " bytes (expected " + MODEL_SIZE_BYTES + ")");
                // We still rename — the file may have changed upstream.
            }
            try { out.delete(); } catch (Throwable ignored) {}
            if (!tmp.renameTo(out)) {
                fail("rename of downloaded model failed");
                return null;
            }
            Log.i(TAG, "Whisper model downloaded: " + out.length() + " bytes");
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
            for (ProgressListener l : new ArrayList<>(progressListeners)) {
                try { l.onProgress(downloaded, total); } catch (Throwable ignored) {}
            }
        });
    }
    private void postReady() {
        main.post(() -> {
            for (ProgressListener l : new ArrayList<>(progressListeners)) {
                try { l.onReady(); } catch (Throwable ignored) {}
            }
        });
    }
    private void fail(String msg) {
        lastError = msg;
        Log.w(TAG, msg);
        main.post(() -> {
            for (ProgressListener l : new ArrayList<>(progressListeners)) {
                try { l.onError(msg); } catch (Throwable ignored) {}
            }
        });
    }
}
