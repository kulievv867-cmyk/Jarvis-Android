package com.devin.jarvis.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight persistent conversation memory.
 * Keeps the last N exchanges as raw history plus a short rolling summary
 * (the summary is updated externally — typically by the LLM client).
 */
public class Memory {
    private static final String PREFS = "jarvis_memory";
    private static final String KEY_HISTORY = "history";   // JSON array of {role,text}
    private static final String KEY_SUMMARY = "summary";

    private static final int MAX_HISTORY = 16;

    private final SharedPreferences sp;

    public Memory(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static class Turn {
        public final String role; // "user" or "assistant"
        public final String text;
        public Turn(String r, String t) { this.role = r; this.text = t; }
    }

    public synchronized List<Turn> history() {
        String raw = sp.getString(KEY_HISTORY, "[]");
        List<Turn> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Turn(o.optString("role"), o.optString("text")));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    public synchronized void appendUser(String text) { append("user", text); }
    public synchronized void appendAssistant(String text) { append("assistant", text); }

    private void append(String role, String text) {
        List<Turn> h = history();
        h.add(new Turn(role, text));
        while (h.size() > MAX_HISTORY) h.remove(0);
        try {
            JSONArray a = new JSONArray();
            for (Turn t : h) {
                JSONObject o = new JSONObject();
                o.put("role", t.role);
                o.put("text", t.text);
                a.put(o);
            }
            sp.edit().putString(KEY_HISTORY, a.toString()).apply();
        } catch (JSONException ignored) {}
    }

    public String summary() { return sp.getString(KEY_SUMMARY, ""); }
    public void setSummary(String s) { sp.edit().putString(KEY_SUMMARY, s == null ? "" : s).apply(); }

    public synchronized void clear() {
        sp.edit().remove(KEY_HISTORY).remove(KEY_SUMMARY).apply();
    }
}
