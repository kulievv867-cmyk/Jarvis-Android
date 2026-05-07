package com.devin.jarvis.commands;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Receiver that AlarmManager fires when a timer/reminder/alarm goes off.
 * Forwards to {@link AlertService} which actually plays sound + notifies.
 */
public class AlertReceiver extends BroadcastReceiver {
    public static final String ACTION_FIRE = "com.devin.jarvis.ALERT";

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent svc = new Intent(context, AlertService.class)
                .setAction(AlertService.ACTION_FIRE)
                .putExtra(AlertService.EXTRA_TITLE, intent.getStringExtra("title"))
                .putExtra(AlertService.EXTRA_TEXT, intent.getStringExtra("text"))
                .putExtra(AlertService.EXTRA_KIND, intent.getStringExtra(AlertService.EXTRA_KIND));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
    }
}
