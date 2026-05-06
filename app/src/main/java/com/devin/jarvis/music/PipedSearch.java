package com.devin.jarvis.music;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Backup music searcher that talks to public Piped instances. Used as a
 * fallback when NewPipeExtractor fails (e.g. with the infamous "page needs
 * to be reloaded" YouTube anti-scrape challenge).
 *
 * Piped is an open-source YouTube front-end whose REST API exposes the same
 * googlevideo audio stream URLs without API keys.
 */
public final class PipedSearch {

    private static final String TAG = "Jarvis.PipedSearch";

    /** Public, well-known Piped API instances. Tried in order until one works. */
    private static final String[] INSTANCES = new String[] {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.privacydev.net",
            "https://pipedapi.smnz.de",
            "https://pipedapi.r4fo.com",
    };

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private PipedSearch() {}

    public static MusicSearch.Result findFirst(String query) throws Exception {
        if (query == null || query.isEmpty()) return null;
        String q = URLEncoder.encode(query, "UTF-8");
        Exception lastErr = null;
        for (String base : INSTANCES) {
            try {
                String videoId = searchFirstVideoId(base, q);
                if (videoId == null) continue;
                MusicSearch.Result r = streamInfo(base, videoId);
                if (r != null) return r;
            } catch (Exception e) {
                lastErr = e;
                Log.d(TAG, "Piped instance failed " + base + ": " + e.getMessage());
            }
        }
        if (lastErr != null) throw lastErr;
        return null;
    }

    private static String searchFirstVideoId(String base, String encodedQuery) throws Exception {
        String url = base + "/search?q=" + encodedQuery + "&filter=music_songs";
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray items = json.optJSONArray("items");
            if (items == null) return null;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String type = it.optString("type", "");
                String urlField = it.optString("url", "");
                if (urlField == null || urlField.isEmpty()) continue;
                if (!"stream".equalsIgnoreCase(type) && !urlField.contains("/watch?v=")) continue;
                int idx = urlField.indexOf("v=");
                if (idx < 0) continue;
                String vid = urlField.substring(idx + 2);
                int amp = vid.indexOf('&');
                if (amp >= 0) vid = vid.substring(0, amp);
                return vid;
            }
            return null;
        }
    }

    private static MusicSearch.Result streamInfo(String base, String videoId) throws Exception {
        String url = base + "/streams/" + URLEncoder.encode(videoId, StandardCharsets.UTF_8.name());
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            String body = resp.body().string();
            JSONObject json = new JSONObject(body);
            JSONArray audios = json.optJSONArray("audioStreams");
            if (audios == null || audios.length() == 0) return null;
            int bestBitrate = -1;
            String bestUrl = null;
            for (int i = 0; i < audios.length(); i++) {
                JSONObject a = audios.getJSONObject(i);
                String aUrl = a.optString("url", "");
                if (aUrl.isEmpty()) continue;
                int br = a.optInt("bitrate", 0);
                if (br > bestBitrate) {
                    bestBitrate = br;
                    bestUrl = aUrl;
                }
            }
            if (bestUrl == null) return null;
            String title = json.optString("title", "");
            String uploader = json.optString("uploader", "");
            long duration = json.optLong("duration", 0) * 1000L;
            String thumb = json.optString("thumbnailUrl", "");
            return new MusicSearch.Result(bestUrl, title, uploader, duration, thumb);
        }
    }
}
