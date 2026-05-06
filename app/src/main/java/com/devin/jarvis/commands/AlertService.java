package com.devin.jarvis.commands;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

import com.devin.jarvis.R;
import com.devin.jarvis.ui.MainActivity;

/**
 * Foreground service that fires when a timer/reminder/alarm goes off.
 *
 * Plays a loud alarm tone on STREAM_ALARM (so it works even when the phone
 * is in silent mode for notifications) and shows a high-priority full-screen
 * notification with a "Stop" action. Auto-stops after 60 seconds.
 *
 * We use a foreground service rather than relying on notification sound
 * because some ROMs don't actually play notification sounds for short-lived
 * receivers.
 */
public class AlertService extends Service {

    public static final String ACTION_FIRE = "com.devin.jarvis.ALERT_FIRE";
    public static final String ACTION_STOP = "com.devin.jarvis.ALERT_STOP";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";

    private static final String CHANNEL_ID = "jarvis_alerts";
    private static final int NOTIF_ID = 7000;
    private static final long MAX_RUN_MS = 60_000L;

    private MediaPlayer player;
    private Vibrator vibrator;
    private Handler handler;
    private Runnable autoStop;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAlerting();
            stopSelf();
            return START_NOT_STICKY;
        }

        String title = intent != null ? intent.getStringExtra(EXTRA_TITLE) : null;
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        if (title == null) title = getString(R.string.reminder_title);
        if (text == null) text = "";

        ensureChannel();
        startForeground(NOTIF_ID, buildNotification(title, text));
        startAlerting();
        return START_NOT_STICKY;
    }

    private void startAlerting() {
        // Play alarm tone on STREAM_ALARM so it's audible even when ringer
        // is in silent mode.
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (uri != null) {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                player.setLooping(true);
                player.setDataSource(this, uri);
                player.prepare();
                player.start();
            }
        } catch (Throwable ignored) {}
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pat = new long[]{0, 700, 400, 700, 400};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pat, 0));
                } else {
                    vibrator.vibrate(pat, 0);
                }
            }
        } catch (Throwable ignored) {}

        handler = new Handler(Looper.getMainLooper());
        autoStop = () -> {
            stopAlerting();
            stopSelf();
        };
        handler.postDelayed(autoStop, MAX_RUN_MS);
    }

    private void stopAlerting() {
        if (handler != null && autoStop != null) handler.removeCallbacks(autoStop);
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
        if (vibrator != null) {
            try { vibrator.cancel(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        stopAlerting();
        super.onDestroy();
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent piOpen = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, AlertService.class).setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                .setFullScreenIntent(piOpen, true)
                .setContentIntent(piOpen)
                .addAction(0, getString(R.string.alert_stop), piStop)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.alerts_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(getString(R.string.alerts_channel_desc));
            ch.enableVibration(true);
            // Sound is played by us via MediaPlayer; turn off channel sound to
            // avoid double-playing.
            ch.setSound(null,
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build());
            nm.createNotificationChannel(ch);
        }
    }
}
