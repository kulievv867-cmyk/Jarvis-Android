package com.devin.jarvis.commands;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.AlarmClock;

import com.devin.jarvis.core.Settings;

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
        Settings s = new Settings(ctx);
        boolean direct = s.directAlarm();
        boolean sys = false;
        if (!direct) {
            sys = trySystemTimer(seconds, message);
        }
        // Internal AlarmManager fallback: always run unless the user
        // disabled it. We can't reliably tell whether the OEM Clock app
        // actually saved the timer (Xiaomi/MIUI silently swallows the
        // intent), so the safest contract is "timer always rings".
        boolean fallback = s.internalAlarmFallback();
        if (sys && !fallback) return new Result(true, true);
        long triggerAt = System.currentTimeMillis() + seconds * 1000L;
        boolean own = schedule(triggerAt,
                "Таймер истёк",
                message == null || message.isEmpty()
                        ? humanDurationRu(seconds)
                        : message,
                AlertService.KIND_TIMER);
        return new Result(sys || own, sys);
    }

    public Result setAlarm(int hour24, int minute, String message) {
        Settings s = new Settings(ctx);
        boolean direct = s.directAlarm();
        // When directAlarm is on (default), skip the system Clock entirely
        // and rely on our internal AlarmManager. The user reported the
        // system path silently fails on their device, so we just don't
        // bother handing the intent off any more.
        boolean sys = false;
        if (!direct) {
            sys = trySystemAlarm(hour24, minute, message);
        }
        boolean fallback = s.internalAlarmFallback();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour24);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (sys && !fallback) return new Result(true, true);
        boolean own = schedule(c.getTimeInMillis(),
                "Будильник",
                message == null || message.isEmpty()
                        ? String.format("Будильник на %02d:%02d", hour24, minute)
                        : message,
                AlertService.KIND_ALARM);
        return new Result(sys || own, sys);
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

    private boolean schedule(long triggerAt, String title, String text, String kind) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        Intent i = new Intent(ctx, AlertReceiver.class)
                .setAction(AlertReceiver.ACTION_FIRE)
                .putExtra("title", title)
                .putExtra("text", text)
                .putExtra(AlertService.EXTRA_KIND, kind);
        int id = nextId.getAndIncrement();
        PendingIntent pi = PendingIntent.getBroadcast(ctx, id, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            // For alarms specifically we use setAlarmClock so the OS treats
            // it as a wake-up alarm of the highest priority — works even in
            // Doze, shows the lock-screen alarm icon, and bypasses Doze
            // window throttling. AlarmManager.set / setExact get throttled
            // when the device is sleeping.
            if (AlertService.KIND_ALARM.equals(kind)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AlarmManager.AlarmClockInfo info =
                        new AlarmManager.AlarmClockInfo(triggerAt, pi);
                am.setAlarmClock(info, pi);
                return true;
            }
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
