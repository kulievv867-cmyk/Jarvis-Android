package com.devin.jarvis.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.devin.jarvis.BuildConfig;

public class Settings {
    private static final String PREFS = "jarvis_prefs";

    public static final String KEY_LANGUAGE = "language";        // "ru" or "en"
    public static final String KEY_OPENAI_KEY = "openai_key";
    public static final String KEY_VOICE_LOCALE = "voice_locale"; // e.g. "en-GB"
    public static final String KEY_USE_LLM = "use_llm";
    public static final String KEY_TTS_REVERB = "tts_reverb";
    public static final String KEY_HOTWORD = "hotword";          // "джарвис" / "jarvis" — auto-detect

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String language() {
        return sp.getString(KEY_LANGUAGE, "ru");
    }

    public void setLanguage(String s) { sp.edit().putString(KEY_LANGUAGE, s).apply(); }

    public String voiceLocale() {
        return sp.getString(KEY_VOICE_LOCALE, "en-GB");
    }

    public void setVoiceLocale(String s) { sp.edit().putString(KEY_VOICE_LOCALE, s).apply(); }

    public String openAiKey() {
        String userKey = sp.getString(KEY_OPENAI_KEY, "");
        if (userKey != null && !userKey.isEmpty()) return userKey;
        // Fall back to build-time baked key.
        return BuildConfig.OPENAI_API_KEY_DEFAULT == null ? "" : BuildConfig.OPENAI_API_KEY_DEFAULT;
    }

    public void setOpenAiKey(String key) { sp.edit().putString(KEY_OPENAI_KEY, key == null ? "" : key).apply(); }

    public boolean useLlm() { return sp.getBoolean(KEY_USE_LLM, true); }
    public void setUseLlm(boolean v) { sp.edit().putBoolean(KEY_USE_LLM, v).apply(); }

    public boolean ttsReverb() { return sp.getBoolean(KEY_TTS_REVERB, true); }
    public void setTtsReverb(boolean v) { sp.edit().putBoolean(KEY_TTS_REVERB, v).apply(); }

    private static final String KEY_MODULATION = "modulation";
    public boolean modulation() { return sp.getBoolean(KEY_MODULATION, true); }
    public void setModulation(boolean v) { sp.edit().putBoolean(KEY_MODULATION, v).apply(); }

    // ---- Personalisation context that gets injected into the LLM prompt ----

    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_USER_CITY = "user_city";
    private static final String KEY_USER_COUNTRY = "user_country";
    private static final String KEY_USER_TIMEZONE = "user_timezone";

    public String userName() { return sp.getString(KEY_USER_NAME, ""); }
    public void setUserName(String s) { sp.edit().putString(KEY_USER_NAME, s == null ? "" : s).apply(); }

    public String userCity() { return sp.getString(KEY_USER_CITY, "Краснодар"); }
    public void setUserCity(String s) { sp.edit().putString(KEY_USER_CITY, s == null ? "" : s).apply(); }

    public String userCountry() { return sp.getString(KEY_USER_COUNTRY, "Россия"); }
    public void setUserCountry(String s) { sp.edit().putString(KEY_USER_COUNTRY, s == null ? "" : s).apply(); }

    public String userTimezone() { return sp.getString(KEY_USER_TIMEZONE, "Europe/Moscow"); }
    public void setUserTimezone(String s) { sp.edit().putString(KEY_USER_TIMEZONE, s == null ? "" : s).apply(); }

    // ---- UI theme ----

    /** One of: "blue" (default), "orange", "green", "graphite", "light". */
    public static final String KEY_THEME = "ui_theme";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_ORANGE = "orange";
    public static final String THEME_GREEN = "green";
    public static final String THEME_GRAPHITE = "graphite";
    public static final String THEME_LIGHT = "light";

    public String theme() {
        String t = sp.getString(KEY_THEME, THEME_BLUE);
        if (t == null) return THEME_BLUE;
        switch (t) {
            case THEME_BLUE: case THEME_ORANGE: case THEME_GREEN:
            case THEME_GRAPHITE: case THEME_LIGHT:
                return t;
            default:
                return THEME_BLUE;
        }
    }

    public void setTheme(String t) {
        sp.edit().putString(KEY_THEME, t == null ? THEME_BLUE : t).apply();
    }

    // ---- Whisper re-recognition (off by default; trades latency for accuracy) ----

    public static final String KEY_USE_WHISPER = "use_whisper";

    public boolean useWhisper() { return sp.getBoolean(KEY_USE_WHISPER, false); }
    public void setUseWhisper(boolean v) { sp.edit().putBoolean(KEY_USE_WHISPER, v).apply(); }

    // ---- Whisper model variant ----
    // "base" — fastest, sub-second on flagships, weak Russian accuracy.
    // "small" — balanced (~1–2 sec, decent Russian).
    // "large-turbo" — top accuracy, ~3–5 sec on flagships.
    // Default is "base" so the user gets the speed they asked for.

    public static final String KEY_WHISPER_MODEL = "whisper_model";

    public String whisperModel() {
        String v = sp.getString(KEY_WHISPER_MODEL, "base");
        if (v == null) return "base";
        switch (v) {
            case "base":
            case "small":
            case "large-turbo":
                return v;
            default:
                return "base";
        }
    }
    public void setWhisperModel(String v) {
        sp.edit().putString(KEY_WHISPER_MODEL, v == null ? "base" : v).apply();
    }

    // ---- Proxy / built-in "VPN" ----
    // proxyUrl: empty = direct connection. Otherwise a URL like
    // socks5://1.2.3.4:1080 or http://user:pass@host:8080. Honoured by
    // OkHttp clients and HttpURLConnection through NetClient.

    public static final String KEY_PROXY_URL = "proxy_url";
    public static final String KEY_USE_PROXY = "use_proxy";
    /** JSON list of recently auto-discovered free proxies. */
    public static final String KEY_PROXY_CANDIDATES = "proxy_candidates_json";

    public boolean useProxy() { return sp.getBoolean(KEY_USE_PROXY, false); }
    public void setUseProxy(boolean v) { sp.edit().putBoolean(KEY_USE_PROXY, v).apply(); }

    /** Returns the configured proxy URL ONLY if useProxy() is true. */
    public String proxyUrl() {
        if (!useProxy()) return "";
        String s = sp.getString(KEY_PROXY_URL, "");
        return s == null ? "" : s.trim();
    }
    public String proxyUrlRaw() {
        String s = sp.getString(KEY_PROXY_URL, "");
        return s == null ? "" : s.trim();
    }
    public void setProxyUrl(String s) {
        sp.edit().putString(KEY_PROXY_URL, s == null ? "" : s.trim()).apply();
    }

    public String proxyCandidatesJson() { return sp.getString(KEY_PROXY_CANDIDATES, "[]"); }
    public void setProxyCandidatesJson(String json) {
        sp.edit().putString(KEY_PROXY_CANDIDATES, json == null ? "[]" : json).apply();
    }

    // ---- Fast TTS mode (system TTS instead of Edge TTS) ----
    // The Jarvis-flavoured Edge TTS pipeline is gorgeous but spends
    // ~500–1500 ms per sentence on the WebSocket round-trip. Power users
    // who care more about responsiveness can flip this to use Android's
    // built-in Google TTS, which produces audio in ~100 ms but loses the
    // cinematic Bettany-butler character.

    public static final String KEY_FAST_TTS = "fast_tts";
    public boolean fastTts() { return sp.getBoolean(KEY_FAST_TTS, false); }
    public void setFastTts(boolean v) { sp.edit().putBoolean(KEY_FAST_TTS, v).apply(); }

    // ---- Internal alarm fallback ----
    // If true, Jarvis schedules its own AlarmManager-backed alert in
    // addition to handing the alarm off to the system Clock app, so the
    // alarm still fires even when the OEM Clock silently rejects
    // ACTION_SET_ALARM (Xiaomi/MIUI is the worst offender — it accepts
    // the intent but never creates the alarm).

    public static final String KEY_INTERNAL_ALARM_FALLBACK = "internal_alarm_fallback";
    public boolean internalAlarmFallback() {
        return sp.getBoolean(KEY_INTERNAL_ALARM_FALLBACK, true);
    }
    public void setInternalAlarmFallback(boolean v) {
        sp.edit().putBoolean(KEY_INTERNAL_ALARM_FALLBACK, v).apply();
    }

    // ---- Long-term memory ----

    public static final String KEY_LONG_MEMORY = "long_memory";
    public boolean longMemory() { return sp.getBoolean(KEY_LONG_MEMORY, true); }
    public void setLongMemory(boolean v) { sp.edit().putBoolean(KEY_LONG_MEMORY, v).apply(); }
}
