package com.devin.jarvis.commands;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.concurrent.atomic.AtomicInteger;

public class ReminderManager {

    private static final AtomicInteger nextId = new AtomicInteger(9000);
    private final Context ctx;

    public ReminderManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public boolean schedule(int delaySeconds, String message) {
        if (delaySeconds <= 0) delaySeconds = 1;
        long triggerAt = System.currentTimeMillis() + delaySeconds * 1000L;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        Intent i = new Intent(ctx, AlertReceiver.class)
                .setAction(AlertReceiver.ACTION_FIRE)
                .putExtra("title", "Напоминание")
                .putExtra("text", message == null ? "" : message)
                .putExtra(AlertService.EXTRA_KIND, AlertService.KIND_REMINDER);
        int id = nextId.getAndIncrement();
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            return true;
        } catch (SecurityException se) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
