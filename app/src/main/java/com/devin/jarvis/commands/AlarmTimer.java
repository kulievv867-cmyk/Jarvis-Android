package com.devin.jarvis.commands;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.AlarmClock;

import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Timers and alarms scheduled via our own AlarmManager + AlertReceiver, so
 * they always fire with sound and a notification regardless of whether the
 * device has a system Clock app installed.
 */
public class AlarmTimer {

    private static final AtomicInteger nextId = new AtomicInteger(8000);
    private final Context ctx;

    public AlarmTimer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static class Result {
        public final boolean ok;
        public final boolean usedSystemClock;
        public Result(boolean ok, boolean sys) { this.ok = ok; this.usedSystemClock = sys; }
    }

    public Result setTimer(int seconds, String message) {
        if (seconds <= 0) seconds = 60;
        // 1) Try the system Clock app first (Google Clock, Mi Clock, Samsung
        // Clock, etc. all handle ACTION_SET_TIMER). EXTRA_SKIP_UI=true makes
        // the timer start without opening the clock app.
        boolean sys = trySystemTimer(seconds, message);
        // 2) Only fall back to our own AlarmManager if the system Clock
        // didn't accept the intent (no Clock app installed / unsupported
        // ROM). Otherwise scheduling both would result in a double-alert
        // 3 seconds apart at the trigger time.
        if (sys) {
            return new Result(true, true);
        }
        long triggerAt = System.currentTimeMillis() + seconds * 1000L;
        boolean own = schedule(triggerAt,
                "Таймер истёк",
                message == null || message.isEmpty()
                        ? humanDurationRu(seconds)
                        : message);
        return new Result(own, false);
    }

    public Result setAlarm(int hour24, int minute, String message) {
        boolean sys = trySystemAlarm(hour24, minute, message);
        // Same reasoning as setTimer: only fall back to internal AlarmManager
        // when the system Clock app isn't around to take ownership of the
        // alarm. Otherwise the user would get one notification from the
        // Clock app and a second one (3 sec internal ping) from us.
        if (sys) {
            return new Result(true, true);
        }
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour24);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        boolean own = schedule(c.getTimeInMillis(),
                "Будильник",
                message == null || message.isEmpty()
                        ? String.format("Будильник на %02d:%02d", hour24, minute)
                        : message);
        return new Result(own, false);
    }

    private boolean trySystemTimer(int seconds, String message) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (message != null && !message.isEmpty()) {
                i.putExtra(AlarmClock.EXTRA_MESSAGE, message);
            }
            if (resolves(i)) {
                ctx.startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean trySystemAlarm(int hour24, int minute, String message) {
        try {
            Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour24)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (message != null && !message.isEmpty()) {
                i.putExtra(AlarmClock.EXTRA_MESSAGE, message);
            } else {
                i.putExtra(AlarmClock.EXTRA_MESSAGE, "Будильник");
            }
            if (resolves(i)) {
                ctx.startActivity(i);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean resolves(Intent i) {
        try {
            java.util.List<ResolveInfo> r = ctx.getPackageManager().queryIntentActivities(i, 0);
            return r != null && !r.isEmpty();
        } catch (Throwable t) { return false; }
    }

    private boolean schedule(long triggerAt, String title, String text) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        Intent i = new Intent(ctx, AlertReceiver.class)
                .setAction(AlertReceiver.ACTION_FIRE)
                .putExtra("title", title)
                .putExtra("text", text);
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

    private static String humanDurationRu(int seconds) {
        if (seconds < 60) return "Прошло " + seconds + " секунд.";
        if (seconds < 3600) return "Прошло " + (seconds / 60) + " минут.";
        return "Прошёл " + (seconds / 3600) + " час.";
    }
}
