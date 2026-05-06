package com.devin.jarvis.commands;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

/**
 * Free translation via MyMemory API. No API key required (rate-limited per IP).
 * Endpoint: https://api.mymemory.translated.net/get?q=...&langpair=src|dst
 */
public class Translator {

    public interface Callback {
        void onTranslated(String text);
        void onError(String reason);
    }

    private final OkHttpClient client;

    public Translator() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    public void translateAsync(String text, String src, String dst, Callback cb) {
        new Thread(() -> {
            try {
                String result = translate(text, src, dst);
                cb.onTranslated(result);
            } catch (Exception e) {
                cb.onError(e.getMessage() == null ? "translate_failed" : e.getMessage());
            }
        }, "Jarvis-Translate").start();
    }

    public String translate(String text, String src, String dst) throws IOException {
        if (src == null || src.isEmpty()) src = "auto";
        if (dst == null || dst.isEmpty()) dst = "en";
        // MyMemory does not understand "auto"; we default to English source if not given.
        if ("auto".equals(src)) src = guessSourceFor(dst);
        String langpair = src + "|" + dst;
        String url = "https://api.mymemory.translated.net/get?q="
                + URLEncoder.encode(text, "UTF-8")
                + "&langpair=" + URLEncoder.encode(langpair, "UTF-8");
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            String body = resp.body() != null ? resp.body().string() : "";
            JSONObject json = new JSONObject(body);
            JSONObject rd = json.optJSONObject("responseData");
            if (rd != null) {
                String t = rd.optString("translatedText", null);
                if (t != null && !t.isEmpty()) return t;
            }
            throw new IOException("empty response");
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Pick an opposite source language when src is unspecified. */
    private static String guessSourceFor(String dst) {
        if ("ru".equals(dst)) return "en";
        if ("en".equals(dst)) return "ru";
        return "en";
    }
}
