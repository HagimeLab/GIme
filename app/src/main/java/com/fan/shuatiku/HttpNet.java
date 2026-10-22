package com.fan.shuatiku;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 出站 HTTP 的一层薄抽象。
 *
 * <p>为什么要抽：v1.0.9 起握手换到 Chromium 的 Cronet（浏览器的 TLS/ALPN/H2），
 * 但桌面回归必须还能用纯 JDK 编译 {@link DeepSeekWeb}——所以 Cronet 实现在
 * Android 侧安装进 {@link #install(Impl)}，没安装时自动回退 {@link HttpURLConnection}。
 *
 * <p>接口刻意做成「请求一次、拿回状态码 + 头 + 字节流」，和原来
 * {@code HttpURLConnection} 的用法一一对应，SSE 解析、错误页判定、Cookie 回收都不动。
 */
public final class HttpNet {

    private HttpNet() {}

    public static final class Request {
        public final String url;
        public final String method;
        public final Map<String, String> headers;
        public final byte[] body;
        public final int connectTimeoutMs;
        public final int readTimeoutMs;

        public Request(String url, String method, Map<String, String> headers, byte[] body,
                       int connectTimeoutMs, int readTimeoutMs) {
            this.url = url;
            this.method = method == null ? "GET" : method;
            this.headers = headers == null ? new LinkedHashMap<String, String>() : headers;
            this.body = body;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    public interface Conn extends Closeable {
        int code();
        /** 首次出现的同名头；没有返回 null。 */
        String header(String name);
        /** 全部同名头（Set-Cookie 可能有多条）。 */
        List<String> headers(String name);
        /** 响应体；非 2xx 也是同一条流（Cronet 不再分 error stream）。 */
        InputStream body();
        /** 从别的线程掐断，让卡在 read() 上的调用立刻抛错。 */
        void abort();
    }

    public interface Impl {
        Conn open(Request req) throws Exception;
    }

    private static volatile Impl impl;

    /** Android 侧装 Cronet；重复调用只生效第一次。 */
    public static void install(Impl i) {
        if (i != null) impl = i;
    }

    public static boolean hasImpl() { return impl != null; }

    public static Conn open(Request req) throws Exception {
        Impl i = impl;
        if (i != null) return i.open(req);
        return urlOpen(req);
    }

    /* ===================== 桌面 / 兜底实现 ===================== */

    private static Conn urlOpen(Request req) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(req.url).openConnection();
        c.setRequestMethod(req.method);
        c.setConnectTimeout(req.connectTimeoutMs);
        c.setReadTimeout(req.readTimeoutMs);
        for (Map.Entry<String, String> e : req.headers.entrySet()) {
            c.setRequestProperty(e.getKey(), e.getValue());
        }
        if (req.body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(req.body.length);
            OutputStream os = c.getOutputStream();
            try { os.write(req.body); } finally { os.close(); }
        }
        int code = c.getResponseCode();
        Map<String, List<String>> hdrs = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null) hdrs.put(e.getKey().toLowerCase(Locale.US), e.getValue());
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new UrlConn(c, code, hdrs, in);
    }

    private static final class UrlConn implements Conn {
        private final HttpURLConnection c;
        private final int code;
        private final Map<String, List<String>> hdrs;
        private final InputStream in;

        UrlConn(HttpURLConnection c, int code, Map<String, List<String>> hdrs, InputStream in) {
            this.c = c;
            this.code = code;
            this.hdrs = hdrs;
            this.in = in;
        }

        @Override public int code() { return code; }
        @Override public String header(String name) {
            List<String> v = headers(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
        @Override public List<String> headers(String name) {
            return hdrs.get(name == null ? "" : name.toLowerCase(Locale.US));
        }
        @Override public InputStream body() { return in; }
        @Override public void abort() { try { c.disconnect(); } catch (Exception ignored) { } }
        @Override public void close() { try { if (in != null) in.close(); } catch (Exception ignored) { } abort(); }
    }

    /* ===================== 小工具 ===================== */

    public static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
        return out.toString("UTF-8");
    }

    static List<String> listOf(String v) {
        List<String> l = new ArrayList<String>();
        if (v != null) l.add(v);
        return l;
    }
}
