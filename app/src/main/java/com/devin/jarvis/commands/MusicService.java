package com.devin.jarvis.commands;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.devin.jarvis.R;
import com.devin.jarvis.ui.MainActivity;

/**
 * Foreground service that streams a single audio track in-app via ExoPlayer.
 * Driven by the Brain — accepts ACTION_PLAY (URI / title / artist) and
 * ACTION_STOP intents.
 */
public class MusicService extends Service {

    public static final String ACTION_PLAY = "com.devin.jarvis.MUSIC_PLAY";
    public static final String ACTION_STOP = "com.devin.jarvis.MUSIC_STOP";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ARTIST = "artist";

    private static final String CHANNEL_ID = "jarvis_music";
    private static final int NOTIF_ID = 7711;

    private ExoPlayer player;
    private String currentTitle = "";
    private String currentArtist = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Live reference to the running service for cross-component ducking. */
    private static volatile MusicService INSTANCE;

    @Override public void onCreate() { super.onCreate(); INSTANCE = this; }
    @Override public IBinder onBind(Intent intent) { return null; }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PLAY.equals(action)) {
            final String uriStr = intent.getStringExtra(EXTRA_URI);
            currentTitle = nz(intent.getStringExtra(EXTRA_TITLE));
            currentArtist = nz(intent.getStringExtra(EXTRA_ARTIST));
            startForegroundNotification();
            // ExoPlayer must run on the main thread.
            main.post(() -> {
                try {
                    Uri uri = Uri.parse(uriStr);
                    stopPlaybackOnMain();
                    ensurePlayerOnMain();
                    player.setMediaItem(MediaItem.fromUri(uri));
                    player.prepare();
                    player.play();
                } catch (Throwable t) {
                    stopForeground(true);
                    stopSelf();
                }
            });
        }
        return START_NOT_STICKY;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void ensurePlayerOnMain() {
        if (player != null) return;
        ExoPlayer p = new ExoPlayer.Builder(this).build();
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        p.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    stopForeground(true);
                    stopSelf();
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                stopForeground(true);
                stopSelf();
            }
        });
        player = p;
    }

    private void stopPlayback() {
        // Cancel any pending volume-restore from a previous duck so the
        // listener can't bring the music back up after we've stopped.
        main.removeCallbacks(restoreVolumeTask);
        // Mute immediately on whatever thread we're on — ExoPlayer.setVolume
        // is safe from the binder thread; the audible silence is what matters.
        ExoPlayer p = player;
        if (p != null) try { p.setVolume(0f); } catch (Throwable ignored) {}
        // Then do the actual stop on the main thread (ExoPlayer requires it).
        main.post(this::stopPlaybackOnMain);
    }

    private void stopPlaybackOnMain() {
        main.removeCallbacks(restoreVolumeTask);
        ExoPlayer p = player;
        player = null;
        if (p != null) {
            try { p.setVolume(0f); } catch (Throwable ignored) {}
            try { p.setPlayWhenReady(false); } catch (Throwable ignored) {}
            try { p.stop(); } catch (Throwable ignored) {}
            try { p.clearMediaItems(); } catch (Throwable ignored) {}
            try { p.release(); } catch (Throwable ignored) {}
        }
    }

    /** Hard-mute and stop — safe from any thread. Idempotent. */
    public void hardStop() {
        // Cancel any scheduled volume restore.
        main.removeCallbacks(restoreVolumeTask);
        ExoPlayer p = player;
        if (p != null) try { p.setVolume(0f); } catch (Throwable ignored) {}
        main.post(() -> {
            stopPlaybackOnMain();
            try { stopForeground(true); } catch (Throwable ignored) {}
            try { stopSelf(); } catch (Throwable ignored) {}
        });
    }

    /** Convenience: hard-stop the running service if there is one. */
    public static void stopIfPlaying() {
        MusicService s = INSTANCE;
        if (s != null) s.hardStop();
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        stopPlayback();
        super.onDestroy();
    }

    private final Runnable restoreVolumeTask = () -> {
        ExoPlayer p = player;
        if (p != null) try { p.setVolume(1.0f); } catch (Throwable ignored) {}
    };

    /**
     * Lowers playback volume for {@code millis} milliseconds so the speech
     * recognizer can clearly hear the user's command over the music. This
     * is called by JarvisService whenever a partial wake-word transcript
     * arrives during music playback. Idempotent — extends the duck window
     * if called repeatedly while music is still ducked.
     */
    public void duckPlayback(int millis) {
        main.post(() -> {
            ExoPlayer p = player;
            if (p == null) return;
            try { p.setVolume(0.08f); } catch (Throwable ignored) {}
            main.removeCallbacks(restoreVolumeTask);
            main.postDelayed(restoreVolumeTask, Math.max(500, millis));
        });
    }

    /** True if a track is currently loaded/playing. */
    public boolean isActive() {
        ExoPlayer p = player;
        return p != null;
    }

    /** Convenience: duck the running service if there is one. */
    public static void duckIfPlaying(int millis) {
        MusicService s = INSTANCE;
        if (s != null && s.isActive()) s.duckPlayback(millis);
    }

    /** True if a music service is currently active and playing. */
    public static boolean isPlayingActive() {
        MusicService s = INSTANCE;
        return s != null && s.isActive();
    }

    private void startForegroundNotification() {
        ensureChannel();
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, MusicService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = currentTitle.isEmpty() ? "Jarvis Music" : currentTitle;
        String text = currentArtist.isEmpty()
                ? getString(R.string.music_playing)
                : currentArtist;

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(R.drawable.ic_launcher, getString(R.string.music_stop), stopPi)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.music_channel_desc));
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public static void play(Context ctx, Uri uri, String title, String artist) {
        Intent i = new Intent(ctx, MusicService.class)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title == null ? "" : title)
                .putExtra(EXTRA_ARTIST, artist == null ? "" : artist);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, MusicService.class).setAction(ACTION_STOP);
        try { ctx.startService(i); } catch (Throwable ignored) {}
    }
}
