package com.devin.jarvis.music;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Bridges NewPipeExtractor's HTTP needs onto our shared OkHttpClient.
 */
public class NewPipeDownloader extends Downloader {

    private static volatile NewPipeDownloader INSTANCE;

    public static NewPipeDownloader get() {
        NewPipeDownloader d = INSTANCE;
        if (d == null) {
            synchronized (NewPipeDownloader.class) {
                d = INSTANCE;
                if (d == null) {
                    OkHttpClient c = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .build();
                    d = new NewPipeDownloader(c);
                    INSTANCE = d;
                }
            }
        }
        return d;
    }

    private final OkHttpClient client;

    public NewPipeDownloader(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public Response execute(@NonNull Request request) throws IOException, ReCaptchaException {
        String url = request.url();
        String method = request.httpMethod() == null ? "GET" : request.httpMethod();
        byte[] body = request.dataToSend();
        okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(url);
        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                String key = e.getKey();
                List<String> vals = e.getValue();
                if (vals == null) continue;
                if (vals.size() == 1) b.header(key, vals.get(0));
                else for (String v : vals) b.addHeader(key, v);
            }
        }
        RequestBody rb = body == null ? null : RequestBody.create(body);
        if ("GET".equalsIgnoreCase(method)) b.get();
        else b.method(method, rb);

        try (okhttp3.Response resp = client.newCall(b.build()).execute()) {
            if (resp.code() == 429) {
                throw new ReCaptchaException("reCaptcha challenge", url);
            }
            ResponseBody respBody = resp.body();
            String s = respBody == null ? "" : respBody.string();
            return new Response(
                    resp.code(),
                    resp.message(),
                    resp.headers().toMultimap(),
                    s,
                    resp.request().url().toString());
        }
    }
}
