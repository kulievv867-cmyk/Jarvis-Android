package com.devin.jarvis.commands;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.devin.jarvis.R;
import com.devin.jarvis.ui.MainActivity;

public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "jarvis_reminders";
    private static final int BASE_NOTIF_ID = 5000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra("text");
        int id = intent.getIntExtra("id", BASE_NOTIF_ID);
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(text == null ? "" : text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text == null ? "" : text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pi)
                .build();
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(id, n);
    }

    private void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.reminders_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(ctx.getString(R.string.reminders_channel_desc));
            nm.createNotificationChannel(ch);
        }
    }
}
