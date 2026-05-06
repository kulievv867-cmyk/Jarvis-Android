package com.devin.jarvis.voice;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * On-disk MP3 cache for short, frequently-spoken phrases.
 *
 * Edge TTS round-trip is ≈500–1500 ms per request. For one-word ack
 * phrases ("Слушаю.", "Готово.", "Открываю Telegram.") that latency is
 * the user-perceived "lag" of Jarvis. We cache the MP3 the first time
 * each phrase is synthesised; subsequent plays hit disk in <50 ms.
 *
 * Keyed by SHA-1 of (text + voice + rate + pitch) — voice tweaks
 * automatically invalidate the cache.
 */
public final class TtsCache {

    private static final String TAG = "Jarvis.TtsCache";
    private static final long MAX_BYTES = 25L * 1024 * 1024; // ≤ 25 MB total
    private static final int MAX_TEXT_LEN_TO_CACHE = 80;     // longer chunks are session-unique

    private final File dir;

    public TtsCache(Context ctx) {
        this.dir = new File(ctx.getCacheDir(), "tts-cache");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
    }

    /** Whether a phrase is short enough to be worth caching. */
    public boolean isCacheable(String text) {
        return text != null && !text.isEmpty() && text.length() <= MAX_TEXT_LEN_TO_CACHE;
    }

    public byte[] get(String text, String voice, String rate, String pitch) {
        File f = fileFor(text, voice, rate, pitch);
        if (!f.exists() || f.length() == 0) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            Log.w(TAG, "read failed: " + e);
            return null;
        }
    }

    public void put(String text, String voice, String rate, String pitch, byte[] mp3) {
        if (mp3 == null || mp3.length < 64) return;
        if (!isCacheable(text)) return;
        File f = fileFor(text, voice, rate, pitch);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(mp3);
            os.flush();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        evictIfOverQuota();
    }

    public void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private void evictIfOverQuota() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;
        // Drop oldest until we're under quota. Sort ascending by lastModified.
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_BYTES) break;
            long sz = f.length();
            if (f.delete()) total -= sz;
        }
    }

    private File fileFor(String text, String voice, String rate, String pitch) {
        String key = text + "|" + voice + "|" + rate + "|" + pitch;
        return new File(dir, sha1(key) + ".mp3");
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                String hex = Integer.toHexString(b & 0xFF);
                if (hex.length() == 1) sb.append('0');
                sb.append(hex);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.UnsupportedEncodingException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
