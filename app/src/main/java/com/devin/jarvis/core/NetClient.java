package com.devin.jarvis.core;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Route;

/**
 * Centralised network helper. All outbound HTTP/WebSocket clients in the
 * app go through here so a single proxy/VPN setting (configured by the
 * user in Settings) is honoured uniformly — the LLM call, Edge TTS,
 * Whisper model download, and Yandex weather scraping all route the same
 * way.
 *
 * Supported proxy URL forms:
 *   socks5://host:port
 *   socks5://user:pass@host:port
 *   socks://host:port (alias for socks5)
 *   http://host:port
 *   http://user:pass@host:port
 *
 * Empty or malformed proxy URLs fall through to a direct connection — we
 * never throw on bad config, the user just gets a degraded experience
 * exactly as if they'd disabled the proxy.
 */
public final class NetClient {

    private static final String TAG = "Jarvis.Net";
    private NetClient() {}

    public static class ProxySpec {
        public final Proxy.Type type;
        public final String host;
        public final int port;
        public final String username; // may be null
        public final String password; // may be null

        public ProxySpec(Proxy.Type type, String host, int port, String user, String pass) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.username = user;
            this.password = pass;
        }

        public Proxy toProxy() {
            return new Proxy(type, new InetSocketAddress(host, port));
        }

        public boolean hasAuth() {
            return username != null && !username.isEmpty();
        }
    }

    /** Parses a proxy URL into a ProxySpec, or null on failure / empty. */
    public static ProxySpec parse(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;
        try {
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase();
            Proxy.Type type;
            if (scheme.startsWith("socks")) {
                type = Proxy.Type.SOCKS;
            } else if (scheme.equals("http") || scheme.equals("https")) {
                type = Proxy.Type.HTTP;
            } else {
                return null;
            }
            String host = u.getHost();
            int port = u.getPort();
            if (host == null || host.isEmpty() || port <= 0) return null;
            String user = null, pass = null;
            String userInfo = u.getUserInfo();
            if (userInfo != null) {
                int colon = userInfo.indexOf(':');
                if (colon >= 0) {
                    user = userInfo.substring(0, colon);
                    pass = userInfo.substring(colon + 1);
                } else {
                    user = userInfo;
                }
            }
            return new ProxySpec(type, host, port, user, pass);
        } catch (URISyntaxException e) {
            Log.w(TAG, "Bad proxy URL: " + s);
            return null;
        }
    }

    /**
     * Returns an OkHttpClient.Builder pre-configured with the given proxy
     * (if any). Caller adds timeouts / interceptors / etc.
     */
    public static OkHttpClient.Builder okhttpBuilder(Settings settings) {
        OkHttpClient.Builder b = new OkHttpClient.Builder();
        if (settings == null) return b;
        ProxySpec ps = parse(settings.proxyUrl());
        if (ps == null) return b;

        b.proxy(ps.toProxy());
        if (ps.hasAuth()) {
            // Both SOCKS5 and HTTP CONNECT auth — OkHttp handles HTTP via
            // proxyAuthenticator; for SOCKS5 java.net.Authenticator drives
            // the username/password handshake.
            if (ps.type == Proxy.Type.SOCKS) {
                final String user = ps.username == null ? "" : ps.username;
                final String pass = ps.password == null ? "" : ps.password;
                java.net.Authenticator.setDefault(new java.net.Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(user, pass.toCharArray());
                    }
                });
            } else {
                b.proxyAuthenticator(new Authenticator() {
                    @Override public Request authenticate(Route route, okhttp3.Response resp) throws IOException {
                        String credential = Credentials.basic(
                                ps.username == null ? "" : ps.username,
                                ps.password == null ? "" : ps.password);
                        return resp.request().newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build();
                    }
                });
            }
        }
        return b;
    }

    /** Returns a java.net.Proxy (or {@link Proxy#NO_PROXY}) for use with
     *  {@link java.net.URL#openConnection(Proxy)}. */
    public static Proxy javaNetProxy(Settings settings) {
        if (settings == null) return Proxy.NO_PROXY;
        ProxySpec ps = parse(settings.proxyUrl());
        if (ps == null) return Proxy.NO_PROXY;
        if (ps.hasAuth()) {
            final String user = ps.username == null ? "" : ps.username;
            final String pass = ps.password == null ? "" : ps.password;
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass.toCharArray());
                }
            });
        }
        return ps.toProxy();
    }

    /** Convenience: a default OkHttpClient with sane timeouts and the proxy applied. */
    public static OkHttpClient defaultClient(Settings settings) {
        return okhttpBuilder(settings)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }
}
