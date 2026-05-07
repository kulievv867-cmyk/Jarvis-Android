package com.devin.jarvis.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Discovers, tests, and ranks free public proxies. Used to give the user
 * a "Pick best free proxy" button in Settings, so they don't have to
 * configure anything manually to get past their country's ChatGPT block.
 *
 * Sources are public GitHub-hosted lists (TheSpeedX/PROXY-List and
 * MuRongPIG/Proxy-Master). We download a few hundred candidates, then
 * race them in parallel with a TCP-CONNECT test to gstatic.com:443. The
 * fastest 5–10 surface in the UI for the user to pick from.
 *
 * Free proxies rotate constantly — never persist a tested list across
 * sessions for more than a few hours; always re-test on demand.
 */
public final class ProxyManager {

    private static final String TAG = "Jarvis.ProxyMgr";

    /** SOCKS5 list URLs (txt, "host:port" per line). */
    private static final String[] SOCKS5_SOURCES = {
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt",
            "https://raw.githubusercontent.com/MuRongPIG/Proxy-Master/main/socks5_checked.txt",
            "https://raw.githubusercontent.com/hookzof/socks5_list/master/proxy.txt",
    };

    /** HTTP CONNECT list URLs. */
    private static final String[] HTTP_SOURCES = {
            "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt",
            "https://raw.githubusercontent.com/MuRongPIG/Proxy-Master/main/http_checked.txt",
    };

    /** Endpoint hit through each candidate proxy to verify it works. Tiny
     *  204 response, served from Google's edge — perfect latency probe. */
    private static final String PROBE_HOST = "www.gstatic.com";
    private static final int PROBE_PORT = 443;

    /** Max number of candidates to download per source. Free lists can hit
     *  500k entries — testing them all is pointless. */
    private static final int MAX_PER_SOURCE = 80;

    /** Per-proxy connect timeout when probing. */
    private static final int PROBE_TIMEOUT_MS = 4000;

    /** Total candidates we'll race in parallel. */
    private static final int RACE_PARALLELISM = 30;

    public static class Candidate {
        public final Proxy.Type type;
        public final String host;
        public final int port;
        /** Round-trip latency in ms; -1 if untested or failed. */
        public volatile long latencyMs = -1;

        public Candidate(Proxy.Type type, String host, int port) {
            this.type = type;
            this.host = host;
            this.port = port;
        }

        public String url() {
            return (type == Proxy.Type.SOCKS ? "socks5://" : "http://")
                    + host + ":" + port;
        }

        @Override public String toString() {
            return url() + (latencyMs >= 0 ? " (" + latencyMs + "ms)" : "");
        }
    }

    public interface Listener {
        void onProgress(int testedCount, int totalCount, int passedCount);
        /** Called on the main thread when discovery + testing finishes. */
        void onDone(List<Candidate> ranked);
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    public void cancel() { cancelled = true; }

    /**
     * Asynchronously fetches lists, races them, and returns up to
     * {@code want} fastest live proxies (ascending latency). The Listener
     * fires on the main thread.
     */
    public void discoverAndRank(int want, Listener listener) {
        new Thread(() -> {
            try {
                List<Candidate> all = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                for (String src : SOCKS5_SOURCES) {
                    if (cancelled) break;
                    addFromUrl(src, Proxy.Type.SOCKS, all, seen);
                }
                for (String src : HTTP_SOURCES) {
                    if (cancelled) break;
                    addFromUrl(src, Proxy.Type.HTTP, all, seen);
                }
                if (all.isEmpty()) {
                    fail(listener, "Не удалось скачать список прокси (нет интернета или GitHub недоступен)");
                    return;
                }
                Log.i(TAG, "Pulled " + all.size() + " candidates, racing top " + RACE_PARALLELISM * 4);
                // Cap to a sane race size — any more and we just waste battery.
                int cap = Math.min(all.size(), RACE_PARALLELISM * 4);
                List<Candidate> race = all.subList(0, cap);
                List<Candidate> alive = race(race, listener);
                if (alive.isEmpty()) {
                    fail(listener, "Все проверенные прокси не отвечают. Попробуйте ещё раз через минуту.");
                    return;
                }
                Collections.sort(alive, (a, b) -> Long.compare(a.latencyMs, b.latencyMs));
                List<Candidate> top = new ArrayList<>(alive.subList(0, Math.min(want, alive.size())));
                Log.i(TAG, "Best " + top.size() + " proxies: " + top);
                main.post(() -> { if (listener != null) listener.onDone(top); });
            } catch (Throwable t) {
                Log.w(TAG, "discoverAndRank crashed", t);
                fail(listener, "Ошибка: " + t);
            }
        }, "Jarvis-ProxyDiscover").start();
    }

    /** Pulls a list URL and appends parsed candidates (deduped) to {@code out}. */
    private void addFromUrl(String url, Proxy.Type type, List<Candidate> out, Set<String> seen) {
        try {
            URL u = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", "Jarvis-Android/1.0 (proxy-discovery)");
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, url + " HTTP " + code);
                return;
            }
            int n = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null && n < MAX_PER_SOURCE) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) continue;
                    int colon = t.indexOf(':');
                    if (colon < 0) continue;
                    String host = t.substring(0, colon).trim();
                    String portStr = t.substring(colon + 1).trim();
                    int slash = portStr.indexOf('/');
                    if (slash >= 0) portStr = portStr.substring(0, slash);
                    int port;
                    try { port = Integer.parseInt(portStr); }
                    catch (NumberFormatException e) { continue; }
                    if (port <= 0 || port > 65535 || host.isEmpty()) continue;
                    String key = type + "|" + host + ":" + port;
                    if (!seen.add(key)) continue;
                    out.add(new Candidate(type, host, port));
                    n++;
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't fetch " + url + ": " + e);
        } catch (Throwable t) {
            Log.w(TAG, "Bad list at " + url, t);
        }
    }

    /** Tests {@code candidates} in parallel, returns those that responded
     *  with their measured latency populated. */
    private List<Candidate> race(List<Candidate> candidates, Listener listener) {
        ExecutorService pool = Executors.newFixedThreadPool(RACE_PARALLELISM, r -> {
            Thread t = new Thread(r, "Jarvis-ProxyRace");
            t.setDaemon(true);
            return t;
        });
        List<Candidate> alive = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger tested = new AtomicInteger(0);
        AtomicInteger passed = new AtomicInteger(0);
        int total = candidates.size();
        try {
            for (Candidate c : candidates) {
                if (cancelled) break;
                pool.submit(() -> {
                    if (cancelled) return;
                    long ms = probe(c);
                    int t = tested.incrementAndGet();
                    if (ms >= 0) {
                        c.latencyMs = ms;
                        alive.add(c);
                        passed.incrementAndGet();
                    }
                    if (listener != null) {
                        main.post(() -> listener.onProgress(t, total, passed.get()));
                    }
                });
            }
            pool.shutdown();
            // Hard ceiling so the user never waits longer than 25s for an
            // auto-pick — the fastest few proxies always come back well
            // before then.
            try {
                pool.awaitTermination(25, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            pool.shutdownNow();
        }
        return alive;
    }

    /**
     * Single-proxy probe: opens a TCP connection through the proxy to
     * gstatic.com:443 and measures connect-time. Returns -1 if anything
     * fails (refused, timeout, garbage SOCKS handshake).
     */
    private long probe(Candidate c) {
        long t0 = System.currentTimeMillis();
        Proxy proxy = new Proxy(c.type, new InetSocketAddress(c.host, c.port));
        Socket s = new Socket(proxy);
        try {
            s.connect(new InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS);
            return System.currentTimeMillis() - t0;
        } catch (Throwable t) {
            return -1;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /** Tests a single proxy URL. Returns latency in ms, or -1. */
    public static long testProxyUrl(String url) {
        NetClient.ProxySpec ps = NetClient.parse(url);
        if (ps == null) return -1;
        Candidate c = new Candidate(ps.type, ps.host, ps.port);
        return new ProxyManager().probe(c);
    }

    /** Serialises a list of candidates to JSON for SharedPreferences storage. */
    public static String toJson(List<Candidate> list) {
        try {
            JSONArray a = new JSONArray();
            for (Candidate c : list) {
                JSONObject o = new JSONObject();
                o.put("type", c.type == Proxy.Type.SOCKS ? "socks5" : "http");
                o.put("host", c.host);
                o.put("port", c.port);
                o.put("latencyMs", c.latencyMs);
                a.put(o);
            }
            return a.toString();
        } catch (JSONException e) {
            return "[]";
        }
    }

    public static List<Candidate> fromJson(String json) {
        List<Candidate> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Proxy.Type type = "socks5".equals(o.optString("type")) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                Candidate c = new Candidate(type, o.optString("host"), o.optInt("port"));
                c.latencyMs = o.optLong("latencyMs", -1);
                out.add(c);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    private void fail(Listener l, String msg) {
        main.post(() -> { if (l != null) l.onError(msg); });
    }
}
