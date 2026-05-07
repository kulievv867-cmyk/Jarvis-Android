package com.devin.jarvis.commands;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight on-device notes store. JSON-in-SharedPreferences keeps the
 * deps tiny (no Room / no SQLite migration code) — notes are short, and the
 * realistic ceiling is a few hundred entries.
 */
public class Notes {

    private static final String PREFS = "jarvis_notes";
    private static final String KEY_LIST = "list"; // JSON array of {id, ts, text}

    private static final AtomicLong nextId = new AtomicLong(System.currentTimeMillis());

    private final SharedPreferences sp;

    public Notes(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static class Note {
        public final long id;
        public final long ts;
        public final String text;
        public Note(long id, long ts, String text) {
            this.id = id;
            this.ts = ts;
            this.text = text == null ? "" : text;
        }
    }

    public synchronized List<Note> list() {
        List<Note> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Note(o.optLong("id"), o.optLong("ts"), o.optString("text")));
            }
        } catch (JSONException ignored) {}
        // Newest first.
        Collections.sort(out, (x, y) -> Long.compare(y.ts, x.ts));
        return out;
    }

    /** Adds a note and returns its id. */
    public synchronized long add(String text) {
        if (text == null) text = "";
        text = text.trim();
        if (text.isEmpty()) return -1;
        long id = nextId.getAndIncrement();
        List<Note> existing = list();
        existing.add(0, new Note(id, System.currentTimeMillis(), text));
        save(existing);
        return id;
    }

    /** Searches by case-insensitive substring. */
    public synchronized List<Note> search(String query) {
        if (query == null) return list();
        String q = query.toLowerCase().trim();
        if (q.isEmpty()) return list();
        List<Note> out = new ArrayList<>();
        for (Note n : list()) {
            if (n.text.toLowerCase().contains(q)) out.add(n);
        }
        return out;
    }

    /** Deletes a note by id. */
    public synchronized boolean deleteById(long id) {
        List<Note> all = list();
        boolean removed = false;
        List<Note> kept = new ArrayList<>();
        for (Note n : all) {
            if (n.id == id) { removed = true; continue; }
            kept.add(n);
        }
        if (removed) save(kept);
        return removed;
    }

    /** Deletes the first note matching the query. Returns the deleted note's text or null. */
    public synchronized String deleteByQuery(String query) {
        List<Note> hits = search(query);
        if (hits.isEmpty()) return null;
        Note n = hits.get(0);
        deleteById(n.id);
        return n.text;
    }

    public synchronized int count() { return list().size(); }

    public synchronized void clear() {
        sp.edit().remove(KEY_LIST).apply();
    }

    private void save(List<Note> notes) {
        try {
            JSONArray a = new JSONArray();
            for (Note n : notes) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("ts", n.ts);
                o.put("text", n.text);
                a.put(o);
            }
            sp.edit().putString(KEY_LIST, a.toString()).apply();
        } catch (JSONException ignored) {}
    }
}
