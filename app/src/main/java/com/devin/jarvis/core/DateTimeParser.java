package com.devin.jarvis.core;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Russian and English date/time phrases into absolute millisecond
 * timestamps relative to the device's local time. Used by calendar and
 * reminder commands.
 *
 * Supported phrases (RU):
 *   сегодня, завтра, послезавтра, вчера
 *   на этой неделе, на следующей неделе
 *   в понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье
 *   через N (минут|часов|дней|недель)
 *   N (числа|марта|...) — basic month-name + day
 *   полдень, полночь, утро (=10:00), день (=14:00), вечер (=19:00), ночь (=23:00)
 *   HH (часов|утра|дня|вечера|ночи), HH:MM, HH MM
 */
public final class DateTimeParser {
    private DateTimeParser() {}

    public static class Result {
        /** Absolute millis. -1 if no date/time was recognised. */
        public long startMs = -1;
        /** True if only a date was recognised (no specific time). */
        public boolean dateOnly = false;
        /** True if a range like "this week" was parsed. */
        public boolean range = false;
        /** Range end, only valid when range==true. */
        public long endMs = -1;
        /** The portion of input we consumed, useful for extracting the title. */
        public String matched = "";
    }

    private static final String[] WEEKDAYS_RU = {
            "понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье"
    };
    private static final int[] WEEKDAY_CAL_RU = {
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    };

    private static final Pattern THROUGH_RU = Pattern.compile(
            "(?iu)через\\s+(\\d+)\\s*(минут|мин|часов|час|дня|день|дней|недел[ьюи])");

    private static final Pattern HHMM_RU = Pattern.compile(
            "(?iu)(?:в\\s+)?(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(?:часов|час|часа)?\\s*(утра|дня|вечера|ночи|пополудни|пополуночи)?");

    /**
     * Parses RU phrase. Returns Result with startMs=-1 if nothing matched.
     * Date words must come first; time after.
     */
    public static Result parseRu(String text) {
        Result r = new Result();
        if (text == null) return r;
        String t = RussianNumbers.normalize(text.trim().toLowerCase());

        Calendar c = Calendar.getInstance();
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        boolean dateMatched = false;
        long dateStart = -1;

        // -------- Date keywords --------
        if (containsWord(t, "послезавтра")) {
            c.add(Calendar.DAY_OF_YEAR, 2);
            dateStart = atMidnight(c).getTimeInMillis();
            dateMatched = true;
            t = t.replaceAll("\\bпослезавтра\\b", " ").trim();
        } else if (containsWord(t, "завтра")) {
            c.add(Calendar.DAY_OF_YEAR, 1);
            dateStart = atMidnight(c).getTimeInMillis();
            dateMatched = true;
            t = t.replaceAll("\\bзавтра\\b", " ").trim();
        } else if (containsWord(t, "сегодня") || containsWord(t, "сейчас")) {
            dateStart = atMidnight(c).getTimeInMillis();
            dateMatched = true;
            t = t.replaceAll("\\b(сегодня|сейчас)\\b", " ").trim();
        } else if (containsWord(t, "вчера")) {
            c.add(Calendar.DAY_OF_YEAR, -1);
            dateStart = atMidnight(c).getTimeInMillis();
            dateMatched = true;
            t = t.replaceAll("\\bвчера\\b", " ").trim();
        } else if (t.contains("на этой неделе") || t.contains("эта неделя") || t.contains("на этой нед")) {
            r.range = true;
            // Range from start of today through end of this Sunday.
            Calendar end = (Calendar) c.clone();
            int dow = end.get(Calendar.DAY_OF_WEEK);
            int daysToSunday = (Calendar.SUNDAY - dow + 7) % 7;
            if (daysToSunday == 0) daysToSunday = 7;
            end.add(Calendar.DAY_OF_YEAR, daysToSunday);
            r.startMs = atMidnight(c).getTimeInMillis();
            r.endMs = atMidnight(end).getTimeInMillis() + 24L * 3600_000L;
            r.dateOnly = true;
            r.matched = "на этой неделе";
            return r;
        } else if (t.contains("на следующей неделе") || t.contains("на след неделе")
                || t.contains("на той неделе") || t.contains("через неделю")) {
            r.range = true;
            // Next Monday 00:00 → next Monday + 7 days 00:00.
            Calendar start = (Calendar) c.clone();
            int dow = start.get(Calendar.DAY_OF_WEEK);
            int daysToMon = (Calendar.MONDAY - dow + 7) % 7;
            if (daysToMon == 0) daysToMon = 7;
            start.add(Calendar.DAY_OF_YEAR, daysToMon);
            r.startMs = atMidnight(start).getTimeInMillis();
            r.endMs = r.startMs + 7L * 24L * 3600_000L;
            r.dateOnly = true;
            r.matched = "на следующей неделе";
            return r;
        } else {
            // Weekday names: "в пятницу", "в понедельник" etc.
            for (int i = 0; i < WEEKDAYS_RU.length; i++) {
                Pattern pw = Pattern.compile("(?iu)\\b(?:в\\s+|во\\s+)?" + WEEKDAYS_RU[i] + "\\b");
                Matcher mw = pw.matcher(t);
                if (mw.find()) {
                    Calendar wd = (Calendar) c.clone();
                    int dow = wd.get(Calendar.DAY_OF_WEEK);
                    int target = WEEKDAY_CAL_RU[i];
                    int delta = (target - dow + 7) % 7;
                    if (delta == 0) delta = 7;
                    wd.add(Calendar.DAY_OF_YEAR, delta);
                    dateStart = atMidnight(wd).getTimeInMillis();
                    dateMatched = true;
                    t = t.replaceFirst(pw.pattern(), " ").trim();
                    break;
                }
            }
        }

        // через N мин/час/дн
        Matcher mt = THROUGH_RU.matcher(t);
        if (!dateMatched && mt.find()) {
            int n;
            try { n = Integer.parseInt(mt.group(1)); } catch (Exception e) { n = 0; }
            String unit = mt.group(2);
            long ms = System.currentTimeMillis();
            if (unit.startsWith("мин")) ms += n * 60_000L;
            else if (unit.startsWith("час")) ms += n * 3600_000L;
            else if (unit.startsWith("ден") || unit.startsWith("дн")) ms += n * 24L * 3600_000L;
            else if (unit.startsWith("нед")) ms += n * 7L * 24L * 3600_000L;
            r.startMs = ms;
            r.matched = mt.group();
            return r;
        }

        // -------- Time --------
        // полдень / полночь / утро / день / вечер / ночь
        int hour = -1, minute = 0;
        if (t.contains("полдень") || t.contains("в полдень")) {
            hour = 12; minute = 0;
            t = t.replaceAll("\\bв\\s+полдень\\b|\\bполдень\\b", " ").trim();
        } else if (t.contains("полночь") || t.contains("в полночь")) {
            hour = 0; minute = 0;
            t = t.replaceAll("\\bв\\s+полночь\\b|\\bполночь\\b", " ").trim();
        }

        if (hour < 0) {
            // Try HH(:MM) [meridiem]
            Matcher mh = HHMM_RU.matcher(t);
            if (mh.find()) {
                try {
                    hour = Integer.parseInt(mh.group(1));
                } catch (Exception e) { hour = -1; }
                if (hour >= 0) {
                    String mm = mh.group(2);
                    if (mm != null) try { minute = Integer.parseInt(mm); } catch (Exception ignored) {}
                    String md = mh.group(3);
                    if (md != null) {
                        if ("вечера".equals(md) || "пополудни".equals(md)) {
                            if (hour < 12) hour += 12;
                        } else if ("ночи".equals(md) || "пополуночи".equals(md)) {
                            if (hour == 12) hour = 0;
                        } else if ("утра".equals(md)) {
                            if (hour == 12) hour = 0;
                        } else if ("дня".equals(md)) {
                            if (hour < 12) hour += 12;
                        }
                    }
                }
            }
        }

        if (hour < 0 && !dateMatched && !r.range) {
            return r; // nothing matched
        }

        if (dateMatched) {
            Calendar out = Calendar.getInstance();
            out.setTimeInMillis(dateStart);
            if (hour >= 0) {
                out.set(Calendar.HOUR_OF_DAY, hour);
                out.set(Calendar.MINUTE, minute);
                r.startMs = out.getTimeInMillis();
                r.dateOnly = false;
            } else {
                r.startMs = dateStart;
                r.dateOnly = true;
            }
        } else if (hour >= 0) {
            // Time only — assume today. If past, push to tomorrow.
            Calendar out = Calendar.getInstance();
            out.set(Calendar.SECOND, 0);
            out.set(Calendar.MILLISECOND, 0);
            out.set(Calendar.HOUR_OF_DAY, hour);
            out.set(Calendar.MINUTE, minute);
            if (out.getTimeInMillis() <= System.currentTimeMillis()) {
                out.add(Calendar.DAY_OF_YEAR, 1);
            }
            r.startMs = out.getTimeInMillis();
            r.dateOnly = false;
        }
        return r;
    }

    private static Calendar atMidnight(Calendar c) {
        Calendar out = (Calendar) c.clone();
        out.set(Calendar.HOUR_OF_DAY, 0);
        out.set(Calendar.MINUTE, 0);
        out.set(Calendar.SECOND, 0);
        out.set(Calendar.MILLISECOND, 0);
        return out;
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("(?iu)\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    /**
     * Extracts the event title from a phrase by stripping the matched
     * date/time portion and common stop-words. Used by calendar create.
     */
    public static String stripDateTimeRu(String text) {
        if (text == null) return "";
        String t = text;
        // Remove date keywords.
        t = t.replaceAll("(?iu)\\b(послезавтра|завтра|сегодня|вчера|сейчас)\\b", " ");
        t = t.replaceAll("(?iu)\\bна\\s+(этой|следующей|той)\\s+неделе\\b", " ");
        t = t.replaceAll("(?iu)\\bв\\s+полдень\\b|\\bполдень\\b|\\bв\\s+полночь\\b|\\bполночь\\b", " ");
        for (String w : WEEKDAYS_RU) {
            t = t.replaceAll("(?iu)\\b(?:во\\s+|в\\s+)?" + w + "\\b", " ");
        }
        // Remove "в HH(:MM)" patterns.
        t = t.replaceAll("(?iu)\\bв\\s+\\d{1,2}(?:[:.\\s]\\d{2})?\\s*(?:часов|час|часа)?\\s*(?:утра|дня|вечера|ночи)?\\b", " ");
        t = t.replaceAll("(?iu)\\bна\\s+\\d{1,2}(?:[:.\\s]\\d{2})?\\s*(?:часов|час|часа)?\\s*(?:утра|дня|вечера|ночи)?\\b", " ");
        t = t.replaceAll("(?iu)\\bчерез\\s+\\d+\\s*(?:минут|мин|часов|час|дня|день|дней|недел[ьюи])\\b", " ");
        // Collapse leading prepositions.
        t = t.replaceAll("(?iu)^\\s*(на|в|во|с)\\s+", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }
}
