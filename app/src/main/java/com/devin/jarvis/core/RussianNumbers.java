package com.devin.jarvis.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort conversion of Russian number words to digits, plus a few handy
 * time idioms ("полседьмого", "без четверти восемь"). Used to normalise
 * recogniser output before applying command regexes — Vosk emits numbers as
 * words, which our regex patterns wouldn't otherwise match.
 */
public final class RussianNumbers {

    private RussianNumbers() {}

    private static final Map<String, Integer> ONES = new HashMap<>();
    private static final Map<String, Integer> TEENS = new HashMap<>();
    private static final Map<String, Integer> TENS = new HashMap<>();
    private static final Map<String, Integer> HUNDREDS = new HashMap<>();

    static {
        // 0..9 — including grammatical forms Vosk tends to emit.
        String[][] ones = {
                {"ноль", "нуль"},
                {"один", "одну", "одна", "одно"},
                {"два", "две", "двух"},
                {"три", "трёх", "трех"},
                {"четыре", "четырёх", "четырех"},
                {"пять", "пяти"},
                {"шесть", "шести"},
                {"семь", "семи"},
                {"восемь", "восьми"},
                {"девять", "девяти"},
        };
        for (int i = 0; i < ones.length; i++) for (String w : ones[i]) ONES.put(w, i);

        String[][] teens = {
                {"десять", "десяти"},
                {"одиннадцать", "одиннадцати"},
                {"двенадцать", "двенадцати"},
                {"тринадцать", "тринадцати"},
                {"четырнадцать", "четырнадцати"},
                {"пятнадцать", "пятнадцати"},
                {"шестнадцать", "шестнадцати"},
                {"семнадцать", "семнадцати"},
                {"восемнадцать", "восемнадцати"},
                {"девятнадцать", "девятнадцати"},
        };
        for (int i = 0; i < teens.length; i++) for (String w : teens[i]) TEENS.put(w, 10 + i);

        String[][] tens = {
                {"двадцать", "двадцати"},
                {"тридцать", "тридцати"},
                {"сорок", "сорока"},
                {"пятьдесят", "пятидесяти"},
                {"шестьдесят", "шестидесяти"},
                {"семьдесят", "семидесяти"},
                {"восемьдесят", "восьмидесяти"},
                {"девяносто", "девяноста"},
        };
        for (int i = 0; i < tens.length; i++) for (String w : tens[i]) TENS.put(w, 20 + i * 10);

        String[][] hundreds = {
                {"сто"},
                {"двести"},
                {"триста"},
                {"четыреста"},
                {"пятьсот"},
                {"шестьсот"},
                {"семьсот"},
                {"восемьсот"},
                {"девятьсот"},
        };
        for (int i = 0; i < hundreds.length; i++) for (String w : hundreds[i]) HUNDREDS.put(w, 100 + i * 100);
    }

    // Ordinal hours from "полседьмого" / "пол шестого" idiom — maps to the
    // hour BEFORE that ordinal (полседьмого = 6:30).
    private static final Map<String, Integer> ORDINAL_HOURS = new HashMap<>();
    static {
        String[][] ord = {
                {"первого", "1"},
                {"второго", "2"},
                {"третьего", "3"},
                {"четвёртого", "4"}, {"четвертого", "4"},
                {"пятого", "5"},
                {"шестого", "6"},
                {"седьмого", "7"},
                {"восьмого", "8"},
                {"девятого", "9"},
                {"десятого", "10"},
                {"одиннадцатого", "11"},
                {"двенадцатого", "12"},
        };
        for (String[] e : ord) ORDINAL_HOURS.put(e[0], Integer.parseInt(e[1]));
    }

    /**
     * Replaces Russian number words in {@code text} with their digit form.
     * Handles 0..999 and several common time idioms.
     */
    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        String t = text.toLowerCase();

        // "полседьмого" / "пол седьмого" / "пол шестого" -> "X-1:30".
        t = expandPolHour(t);
        // "без четверти восемь" -> "7:45".
        t = expandQuarterTo(t);
        // "четверть восьмого" -> "7:15".
        t = expandQuarterPast(t);
        // "половина восьмого" -> "7:30".
        t = expandHalfPast(t);

        // Greedy left-to-right replacement of multi-word number runs.
        String[] tokens = t.split("\\s+");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tokens.length) {
            int[] match = consumeNumber(tokens, i);
            if (match != null) {
                if (out.length() > 0) out.append(' ');
                out.append(match[0]);
                i = match[1];
            } else {
                if (out.length() > 0) out.append(' ');
                out.append(tokens[i]);
                i++;
            }
        }
        return out.toString();
    }

    private static int[] consumeNumber(String[] tokens, int from) {
        // Try to greedily consume hundreds + tens + ones (or teens) up to 3
        // tokens.
        int total = 0;
        int consumed = 0;
        // Hundred
        Integer h = HUNDREDS.get(tokens[from]);
        if (h != null) { total += h; consumed++; }
        // Teens (10..19) — exclusive with tens+ones.
        if (from + consumed < tokens.length) {
            Integer teen = TEENS.get(tokens[from + consumed]);
            if (teen != null) {
                total += teen; consumed++;
                return consumed == 0 ? null : new int[]{total, from + consumed};
            }
        }
        // Tens
        if (from + consumed < tokens.length) {
            Integer ten = TENS.get(tokens[from + consumed]);
            if (ten != null) { total += ten; consumed++; }
        }
        // Ones
        if (from + consumed < tokens.length) {
            Integer one = ONES.get(tokens[from + consumed]);
            if (one != null && consumed > 0) { total += one; consumed++; }
            else if (one != null && consumed == 0) { total += one; consumed++; }
        }
        if (consumed == 0) return null;
        return new int[]{total, from + consumed};
    }

    private static String expandPolHour(String t) {
        Pattern p = Pattern.compile("\\bпол\\s*([а-яё]+ого)\\b");
        Matcher m = p.matcher(t);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(t, last, m.start());
            Integer h = ORDINAL_HOURS.get(m.group(1));
            if (h != null) {
                int hour = h == 1 ? 12 : h - 1;
                sb.append(hour).append(":30");
            } else {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(t, last, t.length());
        return sb.toString();
    }

    private static String expandQuarterTo(String t) {
        // "без четверти восемь / без пятнадцати восемь / без 15 восемь"
        Pattern p = Pattern.compile("\\bбез\\s+(четверти|пятнадцати|15|двадцати|20|десяти|10|пяти|5)\\s+([а-яё]+)\\b");
        Matcher m = p.matcher(t);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(t, last, m.start());
            int minutesBefore;
            String w = m.group(1);
            if ("четверти".equals(w) || "пятнадцати".equals(w) || "15".equals(w)) minutesBefore = 15;
            else if ("двадцати".equals(w) || "20".equals(w)) minutesBefore = 20;
            else if ("десяти".equals(w) || "10".equals(w)) minutesBefore = 10;
            else if ("пяти".equals(w) || "5".equals(w)) minutesBefore = 5;
            else minutesBefore = 0;
            Integer hOrd = ORDINAL_HOURS.get(m.group(2));
            int hour;
            if (hOrd != null) hour = hOrd == 1 ? 12 : hOrd - 1;
            else {
                // Try "восемь" (numeral) as the next-hour numeral too.
                Integer cardinal = ONES.get(m.group(2));
                if (cardinal == null) cardinal = TEENS.get(m.group(2));
                if (cardinal == null) { sb.append(m.group()); last = m.end(); continue; }
                hour = cardinal == 0 ? 23 : cardinal - 1;
            }
            int minutes = 60 - minutesBefore;
            sb.append(hour).append(':').append(minutes < 10 ? "0" + minutes : minutes);
            last = m.end();
        }
        sb.append(t, last, t.length());
        return sb.toString();
    }

    private static String expandQuarterPast(String t) {
        Pattern p = Pattern.compile("\\bчетверть\\s+([а-яё]+ого)\\b");
        Matcher m = p.matcher(t);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(t, last, m.start());
            Integer h = ORDINAL_HOURS.get(m.group(1));
            if (h != null) sb.append(h == 1 ? 12 : h - 1).append(":15");
            else sb.append(m.group());
            last = m.end();
        }
        sb.append(t, last, t.length());
        return sb.toString();
    }

    private static String expandHalfPast(String t) {
        Pattern p = Pattern.compile("\\bполовина\\s+([а-яё]+ого)\\b");
        Matcher m = p.matcher(t);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(t, last, m.start());
            Integer h = ORDINAL_HOURS.get(m.group(1));
            if (h != null) sb.append(h == 1 ? 12 : h - 1).append(":30");
            else sb.append(m.group());
            last = m.end();
        }
        sb.append(t, last, t.length());
        return sb.toString();
    }
}
