package com.devin.jarvis.core;

import android.content.Context;

import com.devin.jarvis.voice.JarvisVoice;

/**
 * Thin facade over {@link JarvisVoice}. Kept around so the rest of the app
 * can continue to depend on a Speaker abstraction; the actual TTS + DSP
 * pipeline lives in JarvisVoice.
 */
public class Speaker {

    public interface Listener {
        void onStart();
        void onDone();
    }

    private final JarvisVoice voice;
    private Listener listener;

    public Speaker(Context ctx, boolean modulationEnabled, boolean reverbEnabled, Runnable onReady) {
        this.voice = new JarvisVoice(ctx, modulationEnabled, onReady);
        this.voice.setReverb(reverbEnabled);
        this.voice.setListener(new JarvisVoice.Listener() {
            @Override public void onSpeechStart() {
                Listener l = listener; if (l != null) l.onStart();
            }
            @Override public void onSpeechDone() {
                Listener l = listener; if (l != null) l.onDone();
            }
        });
    }

    public boolean isReady() { return voice.isReady(); }

    /** True iff no chunks are currently queued for speech. */
    public boolean isQueueEmpty() { return voice.isQueueEmpty(); }

    public void setListener(Listener l) { this.listener = l; }

    public void setModulation(boolean v) { voice.setModulation(v); }

    public void setReverb(boolean v) { voice.setReverb(v); }

    public void speak(String text) { voice.speak(text); }

    /** Append a chunk to the speech queue without interrupting playback. */
    public void speakAppend(String text) { voice.speakAppend(text); }

    /** Pre-render common ack phrases into the TTS cache. See
     *  {@link JarvisVoice#prewarmCommonPhrases()}. */
    public void prewarmCommonPhrases() { voice.prewarmCommonPhrases(); }

    public void stop() { voice.stop(); }

    public void shutdown() { voice.shutdown(); }
}
