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
}
