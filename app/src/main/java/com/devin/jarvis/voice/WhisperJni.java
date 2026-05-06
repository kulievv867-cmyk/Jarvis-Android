package com.devin.jarvis.voice;

/**
 * Thin JNI bridge to the bundled whisper.cpp shared library.
 *
 * All methods are static and thread-safe at the granularity of distinct
 * context pointers; do NOT call {@link #transcribe(long, float[], String, int)}
 * concurrently on the same context. Higher-level synchronization lives in
 * {@link WhisperRecognizer}.
 */
public final class WhisperJni {
    private WhisperJni() {}

    private static volatile boolean libLoaded = false;
    private static volatile boolean libLoadFailed = false;
    private static volatile String  libLoadError = "";

    /** Lazy-loads jarvis_whisper.so. Returns true if available on this device. */
    public static synchronized boolean ensureLoaded() {
        if (libLoaded) return true;
        if (libLoadFailed) return false;
        try {
            System.loadLibrary("jarvis_whisper");
            libLoaded = true;
            return true;
        } catch (Throwable t) {
            libLoadFailed = true;
            libLoadError = t.toString();
            android.util.Log.w("Jarvis-Whisper", "loadLibrary jarvis_whisper failed: " + t);
            return false;
        }
    }

    public static String lastLoadError() { return libLoadError; }

    /** Loads a ggml model file from disk; returns 0 on failure. */
    public static native long initContext(String modelPath);

    /** Frees a context obtained from {@link #initContext}. */
    public static native void freeContext(long contextPtr);

    /**
     * Transcribes a mono float32 PCM buffer (samples in [-1.0, 1.0], 16 kHz).
     * @param lang        ISO-639-1 code, e.g. "ru" or "en".
     * @param numThreads  threads for inference; 4 is a good default.
     * @return concatenated text, or "" on failure.
     */
    public static native String transcribe(long contextPtr, float[] pcmF32, String lang, int numThreads);

    public static native String systemInfo();
}
