package com.devin.jarvis.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persistent long-term memory: a compact list of facts about the user
 * (extracted by the LLM) and rolling session summaries. Survives app
 * restarts and conversation clears.
 *
 * Retrieval is deliberately simple — bag-of-words overlap. We don't ship
 * an embeddings model on-device; the LLM still gets the most relevant
 * 5–10 facts per turn injected into its system prompt, which is enough
 * for "remember the user mentioned their wife is named Olya last month"
 * style continuity.
 */
public class LongMemory {

    private static final String PREFS = "jarvis_long_memory";
    private static final String KEY_FACTS = "facts";       // JSON array of {ts, text}
    private static final String KEY_SUMMARIES = "sessions"; // JSON array of {ts, text}

    private static final int MAX_FACTS = 200;
    private static final int MAX_SUMMARIES = 30;

    private final SharedPreferences sp;

    public LongMemory(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static class Fact {
        public final long ts;
        public final String text;
        public Fact(long ts, String text) {
            this.ts = ts;
            this.text = text == null ? "" : text.trim();
        }
    }

    public synchronized List<Fact> facts() {
        return readList(KEY_FACTS);
    }

    public synchronized List<Fact> summaries() {
        return readList(KEY_SUMMARIES);
    }

    /** Adds a fact. Skips near-duplicates (same lowercased text). */
    public synchronized void addFact(String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 400) return;
        List<Fact> all = readList(KEY_FACTS);
        String key = t.toLowerCase();
        for (Fact f : all) {
            if (f.text.toLowerCase().equals(key)) return; // dup
        }
        all.add(0, new Fact(System.currentTimeMillis(), t));
        while (all.size() > MAX_FACTS) all.remove(all.size() - 1);
        writeList(KEY_FACTS, all);
    }

    /** Adds a session-end summary (newest first, capped). */
    public synchronized void addSessionSummary(String text) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        List<Fact> all = readList(KEY_SUMMARIES);
        all.add(0, new Fact(System.currentTimeMillis(), t));
        while (all.size() > MAX_SUMMARIES) all.remove(all.size() - 1);
        writeList(KEY_SUMMARIES, all);
    }

    /**
     * Returns up to {@code limit} facts most relevant to {@code query},
     * scored by token overlap. If the query is empty, returns the most
     * recent facts.
     */
    public synchronized List<Fact> relevantFacts(String query, int limit) {
        List<Fact> all = facts();
        if (query == null || query.trim().isEmpty()) {
            return all.subList(0, Math.min(limit, all.size()));
        }
        Set<String> qTokens = tokens(query);
        if (qTokens.isEmpty()) {
            return all.subList(0, Math.min(limit, all.size()));
        }
        // Score each fact by overlap.
        List<int[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Set<String> ft = tokens(all.get(i).text);
            ft.retainAll(qTokens);
            int score = ft.size();
            if (score > 0) scored.add(new int[]{ score, i });
        }
        Collections.sort(scored, (a, b) -> Integer.compare(b[0], a[0]));
        List<Fact> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, scored.size()); i++) {
            out.add(all.get(scored.get(i)[1]));
        }
        // If we got fewer than `limit` matches, fill the rest with most-recent
        // facts so the LLM always sees some context.
        for (Fact f : all) {
            if (out.size() >= limit) break;
            if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    public synchronized void clear() {
        sp.edit().remove(KEY_FACTS).remove(KEY_SUMMARIES).apply();
    }

    /**
     * Scans the LLM's reply for "FACT:" markers (we instruct the model to
     * mark new long-term facts with that prefix in {@link OpenAiClient}'s
     * system prompt) and stores each one. Lines that don't start with the
     * marker are ignored — we don't want to memorise model fluff.
     */
    public void extractAndStoreFacts(String llmReply) {
        if (llmReply == null) return;
        String[] lines = llmReply.split("\\r?\\n");
        for (String raw : lines) {
            if (raw == null) continue;
            String s = raw.trim();
            // Accept "FACT: ...", "ФАКТ: ...", "[FACT] ...". Be permissive about
            // surrounding markdown the model sometimes adds (asterisks, dashes).
            String stripped = s.replaceAll("^[\\s\\*\\-•]+", "");
            String upper = stripped.toUpperCase();
            int idx = -1;
            if (upper.startsWith("FACT:")) idx = 5;
            else if (upper.startsWith("[FACT]")) idx = 6;
            else if (upper.startsWith("FACT ")) idx = 5;
            else if (upper.startsWith("\u0424\u0410\u041a\u0422:")) idx = "ФАКТ:".length();
            if (idx < 0) continue;
            String body = stripped.substring(idx).trim();
            // Drop trailing punctuation noise so duplicates collapse.
            while (!body.isEmpty()) {
                char c = body.charAt(body.length() - 1);
                if (c == '.' || c == '!' || c == '?' || c == ';') {
                    body = body.substring(0, body.length() - 1).trim();
                } else break;
            }
            if (!body.isEmpty()) addFact(body);
        }
    }

    /** Renders the long memory as a short multi-line block ready for the
     *  LLM system prompt. Returns "" if empty. */
    public String renderForPrompt(String query, int factLimit, String lang) {
        StringBuilder sb = new StringBuilder();
        List<Fact> rel = relevantFacts(query, factLimit);
        if (!rel.isEmpty()) {
            sb.append("ru".equalsIgnoreCase(lang)
                    ? "Что я помню о пользователе из прошлых разговоров:"
                    : "What I remember about the user from prior chats:");
            for (Fact f : rel) {
                sb.append("\n• ").append(f.text);
            }
        }
        List<Fact> sums = summaries();
        if (!sums.isEmpty()) {
            sb.append(sb.length() == 0 ? "" : "\n\n");
            sb.append("ru".equalsIgnoreCase(lang)
                    ? "Последние темы разговоров:"
                    : "Recent conversation topics:");
            int n = Math.min(3, sums.size());
            for (int i = 0; i < n; i++) {
                sb.append("\n• ").append(sums.get(i).text);
            }
        }
        return sb.toString();
    }

    // ---- internals ----

    private List<Fact> readList(String key) {
        List<Fact> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Fact(o.optLong("ts"), o.optString("text")));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private void writeList(String key, List<Fact> list) {
        try {
            JSONArray a = new JSONArray();
            for (Fact f : list) {
                JSONObject o = new JSONObject();
                o.put("ts", f.ts);
                o.put("text", f.text);
                a.put(o);
            }
            sp.edit().putString(key, a.toString()).apply();
        } catch (JSONException ignored) {}
    }

    private static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String t : text.toLowerCase().split("[^\\p{L}\\d]+")) {
            if (t.length() >= 3) out.add(t);
        }
        return out;
    }
}
