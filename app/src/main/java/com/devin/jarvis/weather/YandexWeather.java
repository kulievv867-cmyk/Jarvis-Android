package com.devin.jarvis.weather;

import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Lightweight scraper for {@code yandex.ru/pogoda/&lt;city&gt;}.
 *
 * <p>Yandex Pogoda exposes a Russian-language summary sentence near the top of
 * the page that condenses the current conditions ("Краснодар, погода сейчас:
 * туман. Температура воздуха +12°, ощущается как +11°. ..."). We strip the
 * markup and pull the numbers out of that sentence with regular expressions.
 *
 * <p>We never expose this string directly to the user — it's injected as a
 * context block into the LLM system prompt so Jarvis can paraphrase it in
 * his own voice.
 *
 * <p>Caching: a single in-memory {@link Snapshot} is kept per process so
 * repeated weather questions in a short window don't hammer Yandex.
 */
public final class YandexWeather {

    private static final String TAG = "Jarvis.YandexWeather";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();

    /** ~10 minute cache. Yandex updates page ~every hour anyway. */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private static volatile Snapshot cached;
    private static volatile String cachedKey;

    public static class Snapshot {
        public final String city;
        public final String summary;          // "Краснодар, погода сейчас: туман. ..."
        public final Integer tempC;            // current temperature, °C (signed)
        public final Integer feelsLikeC;       // feels-like, °C
        public final String condition;         // "туман", "ясно", "облачно", ...
        public final Integer windMps;          // m/s
        public final String windDir;           // "З", "СЗ", "Ю", ...
        public final Integer humidityPct;      // 0..100
        public final Integer pressureMmHg;     // millimetres of mercury
        public final long fetchedAtMs;

        Snapshot(String city, String summary,
                 Integer tempC, Integer feelsLikeC, String condition,
                 Integer windMps, String windDir, Integer humidityPct, Integer pressureMmHg) {
            this.city = city;
            this.summary = summary;
            this.tempC = tempC;
            this.feelsLikeC = feelsLikeC;
            this.condition = condition;
            this.windMps = windMps;
            this.windDir = windDir;
            this.humidityPct = humidityPct;
            this.pressureMmHg = pressureMmHg;
            this.fetchedAtMs = System.currentTimeMillis();
        }

        public String renderRu() {
            StringBuilder sb = new StringBuilder();
            sb.append("Погода в ").append(city != null ? city : "вашем городе").append(" сейчас");
            if (condition != null && !condition.isEmpty()) sb.append(" — ").append(condition);
            if (tempC != null) {
                sb.append(", температура ");
                if (tempC >= 0) sb.append('+');
                sb.append(tempC).append("°C");
            }
            if (feelsLikeC != null) {
                sb.append(", ощущается как ");
                if (feelsLikeC >= 0) sb.append('+');
                sb.append(feelsLikeC).append("°C");
            }
            if (windMps != null) {
                sb.append(", ветер ").append(windMps).append(" м/с");
                if (windDir != null && !windDir.isEmpty()) sb.append(' ').append(windDir);
            }
            if (humidityPct != null) sb.append(", влажность ").append(humidityPct).append('%');
            if (pressureMmHg != null) sb.append(", давление ").append(pressureMmHg).append(" мм рт. ст");
            sb.append('.');
            return sb.toString();
        }

        public String renderEn() {
            StringBuilder sb = new StringBuilder();
            sb.append("Current weather in ").append(city != null ? city : "the user's city");
            if (condition != null && !condition.isEmpty()) sb.append(" — ").append(condition);
            if (tempC != null) sb.append(", temperature ").append(tempC).append("°C");
            if (feelsLikeC != null) sb.append(", feels like ").append(feelsLikeC).append("°C");
            if (windMps != null) {
                sb.append(", wind ").append(windMps).append(" m/s");
                if (windDir != null && !windDir.isEmpty()) sb.append(' ').append(windDir);
            }
            if (humidityPct != null) sb.append(", humidity ").append(humidityPct).append('%');
            if (pressureMmHg != null) sb.append(", pressure ").append(pressureMmHg).append(" mmHg");
            sb.append('.');
            return sb.toString();
        }
    }

    /**
     * Fetches a current-conditions snapshot for the given city. Blocking —
     * call from a background thread. Returns {@code null} on any error
     * rather than throwing, so callers can simply skip the context block
     * if Yandex is unreachable.
     */
    public static Snapshot fetch(String city) {
        if (city == null || city.trim().isEmpty()) return null;
        String slug = toSlug(city);
        String key = slug;

        Snapshot s = cached;
        if (s != null && key.equals(cachedKey) && (System.currentTimeMillis() - s.fetchedAtMs) < CACHE_TTL_MS) {
            return s;
        }
        try {
            Request req = new Request.Builder()
                    .url("https://yandex.ru/pogoda/" + slug)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept-Language", "ru,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "Yandex Pogoda HTTP " + resp.code());
                    return null;
                }
                String html = resp.body().string();
                Snapshot parsed = parse(html, city);
                if (parsed != null) {
                    cached = parsed;
                    cachedKey = key;
                }
                return parsed;
            }
        } catch (IOException e) {
            Log.w(TAG, "Yandex Pogoda fetch failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Translates a free-form Russian/English city name to the Latin slug
     * used by yandex.ru/pogoda URLs (e.g. "Краснодар" → "krasnodar"). We
     * keep the table small and rely on transliteration for everything else.
     */
    public static String toSlug(String city) {
        String c = city.toLowerCase(Locale.ROOT).trim();
        switch (c) {
            case "краснодар": return "krasnodar";
            case "москва": return "moscow";
            case "санкт-петербург":
            case "санкт петербург":
            case "питер":
            case "спб": return "saint-petersburg";
            case "новосибирск": return "novosibirsk";
            case "екатеринбург": return "yekaterinburg";
            case "казань": return "kazan";
            case "нижний новгород": return "nizhny-novgorod";
            case "челябинск": return "chelyabinsk";
            case "самара": return "samara";
            case "омск": return "omsk";
            case "ростов-на-дону":
            case "ростов на дону": return "rostov-na-donu";
            case "уфа": return "ufa";
            case "красноярск": return "krasnoyarsk";
            case "пермь": return "perm";
            case "воронеж": return "voronezh";
            case "волгоград": return "volgograd";
            case "сочи": return "sochi";
            case "анапа": return "anapa";
            case "новороссийск": return "novorossiysk";
            case "минск": return "minsk";
            case "киев": return "kyiv";
            case "алматы": return "almaty";
            case "тбилиси": return "tbilisi";
            case "ереван": return "yerevan";
            case "баку": return "baku";
            case "ташкент": return "tashkent";
            default: return transliterate(c).replaceAll("[^a-z0-9]+", "-");
        }
    }

    private static String transliterate(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case 'а': sb.append('a'); break;
                case 'б': sb.append('b'); break;
                case 'в': sb.append('v'); break;
                case 'г': sb.append('g'); break;
                case 'д': sb.append('d'); break;
                case 'е': sb.append('e'); break;
                case 'ё': sb.append('e'); break;
                case 'ж': sb.append("zh"); break;
                case 'з': sb.append('z'); break;
                case 'и': sb.append('i'); break;
                case 'й': sb.append('y'); break;
                case 'к': sb.append('k'); break;
                case 'л': sb.append('l'); break;
                case 'м': sb.append('m'); break;
                case 'н': sb.append('n'); break;
                case 'о': sb.append('o'); break;
                case 'п': sb.append('p'); break;
                case 'р': sb.append('r'); break;
                case 'с': sb.append('s'); break;
                case 'т': sb.append('t'); break;
                case 'у': sb.append('u'); break;
                case 'ф': sb.append('f'); break;
                case 'х': sb.append("kh"); break;
                case 'ц': sb.append("ts"); break;
                case 'ч': sb.append("ch"); break;
                case 'ш': sb.append("sh"); break;
                case 'щ': sb.append("shch"); break;
                case 'ъ': break;
                case 'ы': sb.append('y'); break;
                case 'ь': break;
                case 'э': sb.append('e'); break;
                case 'ю': sb.append("yu"); break;
                case 'я': sb.append("ya"); break;
                default: sb.append(ch);
            }
        }
        return sb.toString();
    }

    // --- parsing helpers ---

    /** Strips HTML tags and collapses whitespace. */
    private static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<").replaceAll("&gt;", ">");
        s = s.replaceAll("\\s+", " ");
        return s.trim();
    }

    private static final Pattern P_TEMP = Pattern.compile(
            "(?:температура воздуха|температура)\\s*([+\\-−–]?\\d{1,2})\\s*°");
    private static final Pattern P_FEELS = Pattern.compile(
            "ощущается как\\s*([+\\-−–]?\\d{1,2})\\s*°");
    private static final Pattern P_WIND = Pattern.compile(
            "(?:скорость ветра|ветер)\\s*(\\d{1,2}(?:[\\.,]\\d)?)\\s*м/с,?\\s*([\\p{L}]+)");
    private static final Pattern P_HUM = Pattern.compile(
            "влажность(?:\\s+воздуха)?\\s*(\\d{1,3})\\s*%");
    private static final Pattern P_PRES = Pattern.compile(
            "давление\\s*(\\d{3})\\s*мм");
    private static final Pattern P_COND_AT_START = Pattern.compile(
            "погода сейчас:\\s*([^\\.]+?)\\s*\\.");
    /**
     * Captures the body sentence that starts with "&lt;city&gt;, погода
     * сейчас:" — Yandex's customer-facing weather summary that we feed
     * verbatim to the LLM as evidence. We deliberately exclude the page
     * title which also contains the phrase "погода сейчас".
     */
    private static final Pattern P_SUMMARY = Pattern.compile(
            "[А-ЯЁ][а-яё\\-\\s]+,\\s*погода сейчас:[^\\.]+\\.(?:\\s*[^\\.]+\\.){0,5}");

    /** Visible for tests. */
    static Snapshot parse(String html, String city) {
        if (html == null) return null;
        String text = stripHtml(html);
        if (text.isEmpty()) return null;

        Integer temp = parseSignedInt(find(text, P_TEMP, 1));
        Integer feels = parseSignedInt(find(text, P_FEELS, 1));
        Integer humidity = parseInt(find(text, P_HUM, 1));
        Integer pressure = parseInt(find(text, P_PRES, 1));

        Integer windMps = null;
        String windDir = null;
        String windRaw = find(text, P_WIND, 1);
        String windDirRaw = find(text, P_WIND, 2);
        if (windRaw != null) {
            try { windMps = (int) Math.round(Double.parseDouble(windRaw.replace(',', '.'))); }
            catch (NumberFormatException ignored) {}
        }
        if (windDirRaw != null) windDir = compactDir(windDirRaw);

        String condition = null;
        String condRaw = find(text, P_COND_AT_START, 1);
        if (condRaw != null) condition = condRaw.toLowerCase(Locale.ROOT).trim();

        // Compose a short summary the LLM can paraphrase in its own voice.
        StringBuilder summary = new StringBuilder();
        Matcher m = P_SUMMARY.matcher(text);
        if (m.find()) summary.append(m.group().trim());

        // If we got nothing useful, give up rather than feed the LLM garbage.
        if (temp == null && feels == null && condition == null
                && windMps == null && humidity == null) return null;

        return new Snapshot(city, summary.toString(),
                temp, feels, condition, windMps, windDir, humidity, pressure);
    }

    private static String find(String text, Pattern p, int group) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(group) : null;
    }

    private static Integer parseInt(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer parseSignedInt(String s) {
        if (s == null) return null;
        String t = s.replace('−', '-').replace('–', '-').trim();
        if (t.startsWith("+")) t = t.substring(1);
        try { return Integer.parseInt(t); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Yandex sometimes spells direction as a long Russian word ("западный",
     * "северо-западный") and sometimes as a compass abbreviation ("З", "СЗ").
     * Normalise to the abbreviation so it reads naturally when the LLM
     * paraphrases it.
     */
    private static String compactDir(String raw) {
        String r = raw.toLowerCase(Locale.ROOT).trim();
        switch (r) {
            case "северный": case "сев": return "С";
            case "северо-восточный": case "св": return "СВ";
            case "восточный": case "вост": return "В";
            case "юго-восточный": case "юв": return "ЮВ";
            case "южный": case "юж": return "Ю";
            case "юго-западный": case "юз": return "ЮЗ";
            case "западный": case "зап": return "З";
            case "северо-западный": case "сз": return "СЗ";
            default: return raw.toUpperCase(Locale.ROOT);
        }
    }

    private YandexWeather() {}
}
