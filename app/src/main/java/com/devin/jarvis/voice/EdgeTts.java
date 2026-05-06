package com.devin.jarvis.voice;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Embedded client for Microsoft Edge's neural text-to-speech service.
 *
 * Talks the same WebSocket protocol used by Edge browser's "Read Aloud":
 *   wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1
 *
 * No system TTS engine required. Returns the raw MP3 stream produced by the
 * service (24 kHz mono, 48 kbit/s); the caller is expected to decode it via
 * {@link Mp3Decoder} before further DSP.
 */
public class EdgeTts {

    private static final String TAG = "Jarvis.EdgeTts";

    private static final String TRUSTED_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String SEC_MS_GEC_VERSION = "1-143.0.3650.75";
    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0";
    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    /** Voice short-names from Microsoft's catalog (free).
     *
     *  Thomas is an older, mid-baritone British voice — closer to the
     *  Paul-Bettany "calm butler" register than Ryan's lighter delivery,
     *  which the user described as "не похоже на Джарвиса". */
    public static final String VOICE_BRITISH_MALE = "en-GB-ThomasNeural";
    public static final String VOICE_RUSSIAN_MALE = "ru-RU-DmitryNeural";

    /** Output format negotiated with the server; only MP3 is supported. */
    private static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    private final OkHttpClient client;

    public EdgeTts() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    public byte[] synthesize(String text, String voice) throws IOException {
        return synthesize(text, voice, "+0%", "+0Hz");
    }

    public byte[] synthesize(String text, String voice, String rate, String pitch) throws IOException {
        if (text == null) text = "";
        // Microsoft caps a single SSML at ~1500 chars; trim defensively.
        if (text.length() > 1400) text = text.substring(0, 1400);
        // Strip control chars that would break SSML parsing
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        String escaped = escapeXml(text);

        String secMsGec = generateSecMsGec();
        String connectionId = UUID.randomUUID().toString().replace("-", "");
        String url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
                + "?TrustedClientToken=" + TRUSTED_TOKEN
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION
                + "&ConnectionId=" + connectionId;

        Request req = new Request.Builder()
                .url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", ORIGIN)
                .header("User-Agent", CHROME_UA)
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build();

        final ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final String requestId = UUID.randomUUID().toString().replace("-", "");
        final String timestamp = rfc3339Now();

        final String voiceName = voice != null ? voice : VOICE_BRITISH_MALE;
        final String localeTag = voiceName.startsWith("ru-") ? "ru-RU" : "en-GB";
        final String finalText = escaped;

        WebSocket ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                String configMsg =
                        "X-Timestamp:" + timestamp + "\r\n"
                        + "Content-Type:application/json; charset=utf-8\r\n"
                        + "Path:speech.config\r\n\r\n"
                        + "{\"context\":{\"synthesis\":{\"audio\":{"
                        + "\"metadataoptions\":{"
                        + "\"sentenceBoundaryEnabled\":\"false\","
                        + "\"wordBoundaryEnabled\":\"false\""
                        + "},\"outputFormat\":\"" + OUTPUT_FORMAT + "\""
                        + "}}}}";
                webSocket.send(configMsg);

                String ssml =
                        "<speak version=\"1.0\""
                        + " xmlns=\"http://www.w3.org/2001/10/synthesis\""
                        + " xml:lang=\"" + localeTag + "\">"
                        + "<voice name=\"" + voiceName + "\">"
                        + "<prosody pitch=\"" + pitch + "\" rate=\"" + rate + "\" volume=\"+0%\">"
                        + finalText
                        + "</prosody></voice></speak>";

                String ssmlMsg =
                        "X-RequestId:" + requestId + "\r\n"
                        + "Content-Type:application/ssml+xml\r\n"
                        + "X-Timestamp:" + timestamp + "\r\n"
                        + "Path:ssml\r\n\r\n"
                        + ssml;
                webSocket.send(ssmlMsg);
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done");
                    done.countDown();
                }
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                // Binary frames: 2-byte big-endian header length, header, audio
                byte[] data = bytes.toByteArray();
                if (data.length < 2) return;
                int hLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                if (hLen < 0 || hLen + 2 > data.length) return;
                String header = new String(data, 2, hLen);
                if (header.contains("Path:audio")) {
                    audioOut.write(data, 2 + hLen, data.length - 2 - hLen);
                }
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
                done.countDown();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(TAG, "WebSocket failure: " + t, t);
                failure.set(t);
                done.countDown();
            }
        });

        boolean ok;
        try {
            // Hard timeout: never block the speech worker for more than 20s
            // on a single utterance. The mic listener stays alive so the
            // user can still issue new commands; the failed utterance just
            // becomes silent text in the transcript.
            ok = done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            ws.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", ie);
        }
        if (!ok) {
            ws.cancel();
            throw new IOException("Edge TTS timed out");
        }
        Throwable f = failure.get();
        if (f != null) throw new IOException("Edge TTS failed: " + f.getMessage(), f);
        return audioOut.toByteArray();
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  sb.append("&amp;"); break;
                case '<':  sb.append("&lt;"); break;
                case '>':  sb.append("&gt;"); break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Computes the Sec-MS-GEC anti-bot token.
     * Algorithm: SHA-256 of (windowsTicks100ns rounded down to 5 minutes) ||
     * trustedClientToken, returned as upper-case hex.
     */
    private static String generateSecMsGec() {
        long unixSec = System.currentTimeMillis() / 1000L;
        long winSec = unixSec + 11644473600L; // delta between Unix and Win FILETIME epoch
        winSec -= winSec % 300L;             // round down to 5-min interval
        // Convert to 100-ns ticks (multiply by 1e7) using 128-bit math via BigInteger? long is enough.
        // Using long is fine: ~10^17 fits in 64-bit (max long is ~9.2×10^18).
        long ticks = winSec * 10_000_000L;
        String input = String.valueOf(ticks) + TRUSTED_TOKEN;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("US-ASCII"));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 is mandatory in JRE; this should not happen
            throw new RuntimeException(e);
        }
    }

    private static String rfc3339Now() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    @SuppressWarnings("unused")
    private static byte[] le16(int v) {
        ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) v);
        return bb.array();
    }
}
