package com.devin.jarvis.commands;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.devin.jarvis.music.MusicSearch;
import com.devin.jarvis.music.PipedSearch;

/**
 * Plays a song by spoken title, streamed directly inside the app via
 * {@link MusicService}. The search and stream-extraction is performed by
 * {@link MusicSearch} (NewPipeExtractor → YouTube). No external music app is
 * launched.
 */
public class MusicPlayer {

    private static final String TAG = "Jarvis.MusicPlayer";

    public interface Callback {
        void onPlaying(Result r);
        void onError(String reason);
    }

    public static class Result {
        public final boolean played;
        public final String title;
        public final String artist;
        public Result(boolean played, String title, String artist) {
            this.played = played;
            this.title = title;
            this.artist = artist;
        }
    }

    private final Context ctx;

    public MusicPlayer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /**
     * Asynchronous: searches YouTube for the requested track and starts
     * streaming it through {@link MusicService}. The callback fires on a
     * worker thread; marshal to main if needed.
     */
    public void playByNameAsync(String songName, Callback cb) {
        if (songName == null || songName.trim().isEmpty()) {
            if (cb != null) cb.onError("empty_query");
            return;
        }
        final String q = songName.trim();
        new Thread(() -> {
            // Try NewPipe first; fall back to Piped public instances if YouTube
            // returns its "page needs to be reloaded" anti-scrape challenge or
            // any other extraction failure. Either source ends up with a
            // playable googlevideo audio URL.
            MusicSearch.Result r = null;
            String firstErr = null;
            try {
                r = MusicSearch.findFirst(q);
            } catch (Throwable t) {
                firstErr = t.getMessage();
                Log.w(TAG, "NewPipe search failed: " + firstErr);
            }
            if (r == null || r.streamUrl == null) {
                try {
                    r = PipedSearch.findFirst(q);
                } catch (Throwable t) {
                    Log.w(TAG, "Piped search failed", t);
                    if (firstErr == null) firstErr = t.getMessage();
                }
            }
            if (r == null || r.streamUrl == null) {
                if (cb != null) cb.onError(firstErr != null ? firstErr : "no_results");
                return;
            }
            try {
                MusicService.play(ctx, Uri.parse(r.streamUrl), r.title, r.uploader);
                if (cb != null) cb.onPlaying(new Result(true, r.title, r.uploader));
            } catch (Throwable t) {
                Log.w(TAG, "music play failed", t);
                if (cb != null) cb.onError(t.getMessage() == null ? "music_failed" : t.getMessage());
            }
        }, "Jarvis-Music").start();
    }

    public void stopInApp() {
        MusicService.stop(ctx);
    }
}
