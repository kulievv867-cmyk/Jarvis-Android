package com.devin.jarvis.llm;

import com.devin.jarvis.core.LongMemory;
import com.devin.jarvis.core.Memory;
import com.devin.jarvis.core.NetClient;
import com.devin.jarvis.core.Persona;
import com.devin.jarvis.core.Settings;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Minimal OpenAI Chat Completions client. Designed to be a free-form fallback
 * when no built-in command matches, plus an asynchronous "summary" updater
 * that occasionally compresses recent history into a short biographical
 * summary stored in Memory.
 */
public class OpenAiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final boolean isOpenRouter;
    private final OkHttpClient http;
    private final LongMemory longMemory;

    public OpenAiClient(String apiKey) {
        this(apiKey, null, null);
    }

    public OpenAiClient(String apiKey, Settings settings) {
        this(apiKey, settings, null);
    }

    public OpenAiClient(String apiKey, Settings settings, LongMemory longMemory) {
        this.longMemory = longMemory;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        // Auto-detect provider: OpenRouter keys start with "sk-or-".
        if (this.apiKey.startsWith("sk-or-")) {
            this.baseUrl = "https://openrouter.ai/api/v1/chat/completions";
            this.model = "openai/gpt-4o-mini";
            this.isOpenRouter = true;
        } else {
            this.baseUrl = "https://api.openai.com/v1/chat/completions";
            this.model = "gpt-4o-mini";
            this.isOpenRouter = false;
        }
        OkHttpClient.Builder b = settings != null
                ? NetClient.okhttpBuilder(settings)
                : new OkHttpClient.Builder();
        this.http = b
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                // Hard ceiling: no LLM request should ever take longer than
                // 22 seconds end-to-end. Otherwise Jarvis appears to "hang"
                // in the Thinking state indefinitely.
                .callTimeout(22, TimeUnit.SECONDS)
                .build();
    }

    public boolean hasKey() { return !apiKey.isEmpty(); }

    public interface Callback {
        void onReply(String reply);
        void onError(String reason);
    }

    /**
     * Streaming callback. {@link #onChunk(String)} fires for every sentence
     * chunk emitted by the model — useful for piping into TTS as soon as the
     * first sentence arrives. {@link #onReply(String)} fires once with the
     * full assembled reply (so we can store it in Memory).
     */
    public interface StreamCallback extends Callback {
        /** Called with each new sentence-sized chunk as it arrives. */
        void onChunk(String chunk);
    }

    public void chatAsync(String userText, String lang, Memory memory, Settings settings, Callback cb) {
        new Thread(() -> {
            try {
                String reply = chat(userText, lang, memory, settings);
                cb.onReply(reply);
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "llm_failed" : e.getMessage());
            }
        }, "Jarvis-LLM").start();
    }

    /**
     * Streams the model's response and emits sentence-sized chunks via
     * {@link StreamCallback#onChunk(String)} so the caller can start
     * synthesising the reply before the model finishes generating.
     */
    public void chatStreamAsync(String userText, String lang, Memory memory, Settings settings, StreamCallback cb) {
        new Thread(() -> {
            try {
                String reply = chatStream(userText, lang, memory, settings, cb);
                cb.onReply(reply);
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "llm_failed" : e.getMessage());
            }
        }, "Jarvis-LLM-Stream").start();
    }

    /** @deprecated kept for binary compat — use the variant with Settings. */
    @Deprecated
    public void chatAsync(String userText, String lang, Memory memory, Callback cb) {
        chatAsync(userText, lang, memory, null, cb);
    }

    public String chat(String userText, String lang, Memory memory, Settings settings) throws IOException {
        if (!hasKey()) throw new IOException("no api key");
        try {
            JSONObject body = baseRequestBody(userText, lang, memory, settings);
            Request req = buildRequest(body);
            try (Response resp = http.newCall(req).execute()) {
                String txt = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code() + ": " + truncate(txt, 240));
                JSONObject json = new JSONObject(txt);
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) throw new IOException("no choices");
                JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                if (msg == null) throw new IOException("no message");
                String content = msg.optString("content", "").trim();
                if (content.isEmpty()) throw new IOException("empty content");
                return content;
            }
        } catch (JSONException e) {
            throw new IOException("json: " + e.getMessage(), e);
        }
    }

    public String chatStream(String userText, String lang, Memory memory, Settings settings,
                              StreamCallback cb) throws IOException {
        if (!hasKey()) throw new IOException("no api key");
        try {
            JSONObject body = baseRequestBody(userText, lang, memory, settings);
            body.put("stream", true);

            Request req = buildRequest(body);
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    String err = resp.body() != null ? resp.body().string() : "";
                    throw new IOException("HTTP " + resp.code() + ": " + truncate(err, 240));
                }
                if (resp.body() == null) throw new IOException("no body");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        resp.body().charStream());
                StringBuilder full = new StringBuilder();
                StringBuilder pending = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.equals("[DONE]")) break;
                    if (payload.isEmpty()) continue;
                    try {
                        JSONObject ev = new JSONObject(payload);
                        JSONArray choices = ev.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        if (delta == null) continue;
                        String text = delta.optString("content", "");
                        if (text.isEmpty()) continue;
                        full.append(text);
                        pending.append(text);
                        // Emit a chunk at sentence boundaries so TTS can start
                        // speaking before the model finishes generating.
                        int boundary = sentenceBoundary(pending);
                        while (boundary > 0) {
                            String chunk = pending.substring(0, boundary).trim();
                            pending.delete(0, boundary);
                            if (!chunk.isEmpty() && cb != null) cb.onChunk(chunk);
                            boundary = sentenceBoundary(pending);
                        }
                    } catch (JSONException ignored) {}
                }
                String tail = pending.toString().trim();
                if (!tail.isEmpty() && cb != null) cb.onChunk(tail);
                String reply = full.toString().trim();
                if (reply.isEmpty()) throw new IOException("empty content");
                return reply;
            }
        } catch (JSONException e) {
            throw new IOException("json: " + e.getMessage(), e);
        }
    }

    /** Returns the index AFTER the first sentence-ending punctuation or 0. */
    private static int sentenceBoundary(StringBuilder s) {
        int len = s.length();
        // Require at least 12 chars before splitting so we don't fire on
        // abbreviations like "Dr." or "т.е.".
        if (len < 12) return 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '…' || c == ';') {
                int end = i + 1;
                if (end < len && s.charAt(end) == ' ') end++;
                if (end >= 12) return end;
            }
        }
        // Fall back to comma after a long-enough run.
        if (len >= 80) {
            for (int i = 60; i < len; i++) {
                if (s.charAt(i) == ',' || s.charAt(i) == ' ') return i + 1;
            }
        }
        return 0;
    }

    private JSONObject baseRequestBody(final String userText, String lang, Memory memory, Settings settings)
            throws JSONException {
        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        String prompt = Persona.systemPrompt(lang);
        String ctx = buildContext(lang, settings);
        if (!ctx.isEmpty()) prompt = prompt + "\n\n" + ctx;
        String weather = buildWeatherContext(userText, lang, settings);
        if (!weather.isEmpty()) prompt = prompt + "\n\n" + weather;
        String summary = memory != null ? memory.summary() : "";
        if (summary != null && !summary.isEmpty()) {
            prompt = prompt + "\n\n" + ("ru".equalsIgnoreCase(lang)
                    ? "Краткая память о пользователе и предыдущих разговорах: "
                    : "Short memory about the user and prior conversations: ")
                    + summary;
        }
        // Long-term memory: facts and rolling session summaries are stored
        // separately so they survive even when the in-memory transcript is
        // cleared. We retrieve only the most relevant 6 facts plus the 3
        // freshest session summaries to keep the system prompt compact.
        if (settings != null && settings.longMemory() && longMemory != null) {
            try {
                String block = longMemory.renderForPrompt(userText, 6, lang);
                if (!block.isEmpty()) prompt = prompt + "\n\n" + block;
            } catch (Throwable ignored) {}
            // Teach the model to flag durable user facts so we can persist
            // them. The token "FACT:" is parsed by LongMemory.extractAndStoreFacts.
            prompt = prompt + "\n\n" + ("ru".equalsIgnoreCase(lang)
                    ? "Если в реплике пользователя есть постоянный факт о нём (имя, "
                            + "город, профессия, день рождения, имена близких, "
                            + "предпочтения), выведи его отдельной строкой в начале "
                            + "ответа в формате `FACT: <короткое утверждение>`. Если "
                            + "новых фактов нет — не пиши строку FACT."
                    : "If the user's message contains a durable personal fact (name, "
                            + "city, job, birthday, family names, preferences), emit "
                            + "it as a separate first line in the form "
                            + "`FACT: <short statement>`. Skip the FACT line if "
                            + "there's nothing new worth remembering.");
        }
        sys.put("content", prompt);
        messages.put(sys);
        if (memory != null) {
            List<Memory.Turn> hist = memory.history();
            int start = Math.max(0, hist.size() - 8);
            for (int i = start; i < hist.size(); i++) {
                Memory.Turn t = hist.get(i);
                JSONObject m = new JSONObject();
                m.put("role", "assistant".equals(t.role) ? "assistant" : "user");
                m.put("content", t.text);
                messages.put(m);
            }
        }
        JSONObject u = new JSONObject();
        u.put("role", "user");
        u.put("content", userText);
        messages.put(u);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", 0.6);
        body.put("max_tokens", 250);
        return body;
    }

    private Request buildRequest(JSONObject body) {
        Request.Builder rb = new Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON));
        if (isOpenRouter) {
            rb.header("HTTP-Referer", "https://github.com/devin/jarvis-android");
            rb.header("X-Title", "Jarvis Android");
        }
        return rb.build();
    }

    /** Asks the model to compress the current memory into a short summary. */
    public void updateSummaryAsync(Memory memory, String lang, Runnable onDone) {
        if (memory == null) { if (onDone != null) onDone.run(); return; }
        new Thread(() -> {
            try {
                List<Memory.Turn> h = memory.history();
                if (h.size() < 6) { if (onDone != null) onDone.run(); return; }
                StringBuilder transcript = new StringBuilder();
                for (Memory.Turn t : h) {
                    transcript.append(t.role).append(": ").append(t.text).append('\n');
                }
                JSONArray messages = new JSONArray();
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content",
                        "ru".equalsIgnoreCase(lang)
                            ? "Ты — модуль памяти Джарвиса. Сожми диалог в краткую сводку (3–5 предложений) о пользователе и их интересах, целях, привычках. Без эмодзи, без приветствий, только факты."
                            : "You are Jarvis's memory module. Compress this dialog into a short factual summary (3–5 sentences) about the user — interests, goals, habits. No greetings, just facts.");
                messages.put(sys);
                JSONObject u = new JSONObject();
                u.put("role", "user");
                u.put("content", transcript.toString());
                messages.put(u);

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("temperature", 0.2);
                body.put("max_tokens", 200);

                Request req = buildRequest(body);
                try (Response resp = http.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String txt = resp.body().string();
                        JSONObject json = new JSONObject(txt);
                        JSONArray choices = json.optJSONArray("choices");
                        if (choices != null && choices.length() > 0) {
                            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
                            if (msg != null) {
                                String content = msg.optString("content", "").trim();
                                if (!content.isEmpty()) memory.setSummary(content);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (onDone != null) onDone.run();
            }
        }, "Jarvis-Summary").start();
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }

    /**
     * Builds a short context block injected into every system prompt: the
     * current local time, day-of-week, user's city/country, and timezone. This
     * lets the model answer "what time is it / what's the date / what's the
     * weather like in my city" without us having to special-case anything in
     * Brain.
     */
    private static String buildContext(String lang, Settings settings) {
        try {
            String tz = settings != null && !settings.userTimezone().isEmpty()
                    ? settings.userTimezone() : "Europe/Moscow";
            String city = settings != null && !settings.userCity().isEmpty()
                    ? settings.userCity() : "Краснодар";
            String country = settings != null && !settings.userCountry().isEmpty()
                    ? settings.userCountry() : "Россия";
            String userName = settings != null ? settings.userName() : "";

            java.util.TimeZone z = java.util.TimeZone.getTimeZone(tz);
            java.util.Locale loc = "ru".equalsIgnoreCase(lang) ? new java.util.Locale("ru") : java.util.Locale.US;
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", loc);
            sdf.setTimeZone(z);
            String now = sdf.format(new java.util.Date());

            StringBuilder sb = new StringBuilder();
            if ("ru".equalsIgnoreCase(lang)) {
                sb.append("Контекст для ответа (используй только если уместно): ");
                sb.append("сейчас ").append(now).append(" по локальному времени пользователя (часовой пояс ").append(tz).append("). ");
                sb.append("Пользователь живёт в городе ").append(city);
                if (!country.isEmpty()) sb.append(", ").append(country);
                sb.append(". ");
                if (userName != null && !userName.isEmpty()) {
                    sb.append("Имя пользователя: ").append(userName).append(". ");
                }
                sb.append("Если пользователь спрашивает о времени, дате, погоде, новостях или местах рядом — учитывай эти данные. ");
                sb.append("Не повторяй контекст в ответе механически — отвечай естественно, как Джарвис.");
            } else {
                sb.append("Context (use only when relevant): ");
                sb.append("the user's local time is ").append(now).append(" (timezone ").append(tz).append("). ");
                sb.append("The user lives in ").append(city);
                if (!country.isEmpty()) sb.append(", ").append(country);
                sb.append(". ");
                if (userName != null && !userName.isEmpty()) {
                    sb.append("User name: ").append(userName).append(". ");
                }
                sb.append("If the user asks about time, date, weather, news or local places, take this into account. ");
                sb.append("Don't echo the context — reply naturally as Jarvis.");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static final java.util.regex.Pattern WEATHER_KEYWORDS_RU =
            java.util.regex.Pattern.compile(
                    "(?iu)\\b(?:погод\\p{L}*|темпер\\p{L}*|градус\\p{L}*|"
                            + "осадк\\p{L}*|дожд\\p{L}*|снег\\p{L}*|ветер|ветр\\p{L}*|"
                            + "влажност\\p{L}*|солнечн\\p{L}*|пасмурн\\p{L}*|тепло|холодно|жар\\p{L}*|"
                            + "морозн\\p{L}*|прогноз\\p{L}*|на улице|за окном)\\b");
    private static final java.util.regex.Pattern WEATHER_KEYWORDS_EN =
            java.util.regex.Pattern.compile(
                    "(?i)\\b(?:weather|forecast|temperature|degrees|rain(?:y|ing)?|snow(?:y|ing)?|"
                            + "wind(?:y)?|humid(?:ity)?|sunny|cloudy|hot|cold|freezing|outside)\\b");

    /**
     * If the user's prompt looks like a weather question, fetches a fresh
     * snapshot from yandex.ru/pogoda for the user's configured city and
     * returns it as a context block. Returns the empty string for any
     * non-weather query so we don't burn an HTTP request per turn.
     */
    private static String buildWeatherContext(String userText, String lang, Settings settings) {
        if (userText == null || userText.isEmpty()) return "";
        boolean ru = "ru".equalsIgnoreCase(lang);
        java.util.regex.Pattern p = ru ? WEATHER_KEYWORDS_RU : WEATHER_KEYWORDS_EN;
        if (!p.matcher(userText).find()) return "";
        String city = settings != null && settings.userCity() != null && !settings.userCity().isEmpty()
                ? settings.userCity() : "Краснодар";
        com.devin.jarvis.weather.YandexWeather.Snapshot snap;
        try {
            snap = com.devin.jarvis.weather.YandexWeather.fetch(city, settings);
        } catch (Throwable t) {
            return "";
        }
        if (snap == null) return "";
        StringBuilder sb = new StringBuilder();
        if (ru) {
            sb.append("Свежие данные с Яндекс.Погоды для города пользователя ").append(city).append(": ");
            sb.append(snap.renderRu()).append(' ');
            if (snap.summary != null && !snap.summary.isEmpty()) sb.append(snap.summary).append(' ');
            sb.append("Используй эти числа в ответе пользователю; не выдумывай свои.");
        } else {
            sb.append("Live data from Yandex Weather for the user's city ").append(city).append(": ");
            sb.append(snap.renderEn()).append(' ');
            sb.append("Use these numbers in your reply; do not invent your own.");
        }
        return sb.toString();
    }
}
