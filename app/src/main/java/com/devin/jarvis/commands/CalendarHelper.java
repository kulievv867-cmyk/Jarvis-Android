package com.devin.jarvis.commands;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Thin wrapper around {@link CalendarContract} for reading and creating
 * events without OAuth. Uses the user's own Android calendars (Google,
 * Exchange, local). Requires READ_CALENDAR / WRITE_CALENDAR permissions.
 */
public class CalendarHelper {

    private final Context ctx;

    public CalendarHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static class Event {
        public final long id;
        public final String title;
        public final String location;
        public final long startMs;
        public final long endMs;
        public final boolean allDay;
        public final String calendarName;

        public Event(long id, String title, String location, long startMs,
                     long endMs, boolean allDay, String calendarName) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.location = location == null ? "" : location;
            this.startMs = startMs;
            this.endMs = endMs;
            this.allDay = allDay;
            this.calendarName = calendarName == null ? "" : calendarName;
        }
    }

    public boolean canRead() {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean canWrite() {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Lists events whose instance falls inside [fromMs, toMs).
     * Sorted by start time ascending. Returns empty list on any failure.
     */
    public List<Event> listEvents(long fromMs, long toMs) {
        List<Event> out = new ArrayList<>();
        if (!canRead()) return out;
        if (toMs <= fromMs) return out;
        ContentResolver cr = ctx.getContentResolver();
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, fromMs);
        ContentUris.appendId(b, toMs);
        String[] projection = new String[]{
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        };
        try (Cursor c = cr.query(b.build(), projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String title = c.getString(1);
                String loc = c.getString(2);
                long bMs = c.getLong(3);
                long eMs = c.getLong(4);
                boolean allDay = c.getInt(5) != 0;
                String calName = c.getString(6);
                out.add(new Event(id, title, loc, bMs, eMs, allDay, calName));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** Finds events in the next {@code daysAhead} days whose title contains the
     *  query (case-insensitive). */
    public List<Event> searchEvents(String query, int daysAhead) {
        long now = System.currentTimeMillis();
        long until = now + daysAhead * 24L * 3600_000L;
        List<Event> all = listEvents(now, until);
        if (query == null || query.isEmpty()) return all;
        String q = query.toLowerCase().trim();
        List<Event> out = new ArrayList<>();
        for (Event e : all) {
            if (e.title.toLowerCase().contains(q)) out.add(e);
        }
        return out;
    }

    /**
     * Finds the calendar ID of the first locally-writable calendar (sync_account
     * is com.google or local). Returns -1 if none.
     */
    public long defaultWritableCalendarId() {
        if (!canRead()) return -1;
        ContentResolver cr = ctx.getContentResolver();
        String[] projection = new String[]{
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.SYNC_EVENTS,
        };
        // CAL_ACCESS_CONTRIBUTOR = 500. Anything ≥ 500 can insert events.
        String selection = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= 500 AND "
                + CalendarContract.Calendars.VISIBLE + " = 1";
        long bestId = -1;
        int bestPrimary = -1;
        try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)) {
            if (c == null) return -1;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                int isPrimary = c.isNull(2) ? 0 : c.getInt(2);
                if (isPrimary > bestPrimary) {
                    bestPrimary = isPrimary;
                    bestId = id;
                }
            }
        } catch (Throwable ignored) {}
        return bestId;
    }

    /**
     * Creates an event on the user's primary writable calendar.
     * Returns the new event ID, or -1 on failure.
     */
    public long createEvent(String title, long startMs, long durationMinutes,
                             String location, String description) {
        if (!canWrite()) return -1;
        long calId = defaultWritableCalendarId();
        if (calId < 0) return -1;
        if (durationMinutes <= 0) durationMinutes = 60;
        long endMs = startMs + durationMinutes * 60_000L;
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, title == null ? "" : title);
        v.put(CalendarContract.Events.DTSTART, startMs);
        v.put(CalendarContract.Events.DTEND, endMs);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        if (location != null && !location.isEmpty()) {
            v.put(CalendarContract.Events.EVENT_LOCATION, location);
        }
        if (description != null && !description.isEmpty()) {
            v.put(CalendarContract.Events.DESCRIPTION, description);
        }
        v.put(CalendarContract.Events.HAS_ALARM, 1);
        try {
            Uri u = ctx.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) return -1;
            long eventId = Long.parseLong(u.getLastPathSegment());
            // Default reminder 15 minutes before.
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.MINUTES, 15);
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            try {
                ctx.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
            } catch (Throwable ignored) {}
            return eventId;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Renders an event into a short voice-friendly string like
     * "сегодня в 18:00 — Стенд-ап" / "tomorrow at 6 pm — Stand-up".
     * Used by {@link com.devin.jarvis.core.Brain}'s calendar query handler.
     */
     public static String formatEventLine(Event e, boolean ru) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        long todayStart = startOfDay(now);
        long evStart = startOfDay(e.startMs);
        long daysOff = (evStart - todayStart) / (24L * 3600_000L);
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(e.startMs);
        if (ru) {
            String when;
            if (daysOff == 0) when = "сегодня";
            else if (daysOff == 1) when = "завтра";
            else if (daysOff == 2) when = "послезавтра";
            else if (daysOff > 2 && daysOff < 7) {
                String[] dows = {"в воскресенье", "в понедельник", "во вторник",
                        "в среду", "в четверг", "в пятницу", "в субботу"};
                when = dows[cal.get(Calendar.DAY_OF_WEEK) - 1];
            } else {
                when = String.format("%02d.%02d", cal.get(Calendar.DAY_OF_MONTH),
                        cal.get(Calendar.MONTH) + 1);
            }
            sb.append(when);
            if (!e.allDay) {
                sb.append(String.format(" в %02d:%02d", cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE)));
            }
            if (!e.title.isEmpty()) sb.append(" — ").append(e.title);
        } else {
            String when;
            if (daysOff == 0) when = "today";
            else if (daysOff == 1) when = "tomorrow";
            else if (daysOff > 1 && daysOff < 7) {
                String[] dows = {"Sunday", "Monday", "Tuesday", "Wednesday",
                        "Thursday", "Friday", "Saturday"};
                when = "on " + dows[cal.get(Calendar.DAY_OF_WEEK) - 1];
            } else {
                when = String.format("on %02d/%02d", cal.get(Calendar.MONTH) + 1,
                        cal.get(Calendar.DAY_OF_MONTH));
            }
            sb.append(when);
            if (!e.allDay) {
                sb.append(String.format(" at %02d:%02d", cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE)));
            }
            if (!e.title.isEmpty()) sb.append(" — ").append(e.title);
        }
        return sb.toString();
    }

    // ---------- Date helpers ----------

    public static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static long endOfDay(long ms) {
        return startOfDay(ms) + 24L * 3600_000L;
    }
}
