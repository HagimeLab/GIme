package com.fan.shuatiku;

import android.content.Context;
import android.util.Log;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UploadDataProviders;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用 Cronet（Chromium 网络栈）实现 {@link HttpNet}。
 *
 * <p>v1.0.9 的核心改动：原来走 {@code HttpURLConnection}，TLS 是平台 Conscrypt、
 * ALPN 只有 http/1.1，和 UA 里自称的 Chrome 对不上。Cronet 带来浏览器同源的
 * BoringSSL ClientHello、ALPN h2 和 HTTP/2 帧序；请求/响应语义仍由
 * {@link DeepSeekWeb} 的现有解析器处理。
 *
 * <p>Cronet 是异步回调模型，这里用 Piped 流把它包成同步的 InputStream：
 * 调用线程（桥接池）照旧 {@code readLine()}，回调线程只负责搬字节。
 */
public final class CronetNet implements HttpNet.Impl {

    private static final String TAG = "GImeNet";
    private static volatile boolean installed;
    private static final ExecutorService CALLBACK_POOL = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();

    private final CronetEngine engine;

    private CronetNet(Context ctx) {
        CronetEngine.Builder b = new CronetEngine.Builder(ctx)
                .enableQuic(false)      // 浏览器走了 h3 才有意义；这里统一走 h2
                .enableHttp2(true);
        engine = b.build();
        Log.i(TAG, "cronet " + engine.getVersionString());
    }

    /** 进程内只装一次；Cronet 初始化失败时保持 HttpURLConnection 兜底。 */
    public static void install(Context ctx) {
        if (installed || ctx == null) return;
        synchronized (CronetNet.class) {
            if (installed) return;
            try {
                HttpNet.install(new CronetNet(ctx.getApplicationContext()));
                installed = true;
            } catch (Throwable t) {
                Log.w(TAG, "cronet init failed, fallback to HttpURLConnection: " + t);
            }
        }
    }

    @Override public HttpNet.Conn open(HttpNet.Request req) throws Exception {
        PipedInputStream in = new PipedInputStream(64 * 1024);
        PipedOutputStream out = new PipedOutputStream(in);
        final CountDownLatch headersReady = new CountDownLatch(1);
        final AtomicInteger code = new AtomicInteger(0);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final AtomicBoolean done = new AtomicBoolean(false);
        final Map<String, List<String>> hdrs = new LinkedHashMap<String, List<String>>();
        final RequestState st = new RequestState(req.readTimeoutMs, failure);

        UrlRequest.Callback cb = new UrlRequest.Callback() {
            @Override public void onRedirectReceived(UrlRequest r, UrlResponseInfo info, String url) {
                if (BuildConfig.DEBUG) Log.d(TAG, "redirect " + info.getHttpStatusCode() + " -> " + url);
                r.followRedirect();
            }

            @Override public void onResponseStarted(UrlRequest r, UrlResponseInfo info) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "resp " + info.getHttpStatusCode() + " ct="
                            + info.getAllHeaders().get("content-type") + " enc="
                            + info.getAllHeaders().get("content-encoding"));
                }
                code.set(info.getHttpStatusCode());
                synchronized (hdrs) {
                    for (Map.Entry<String, String> e : info.getAllHeadersAsList()) {
                        if (e.getKey() == null) continue;
                        String k = e.getKey().toLowerCase(Locale.US);
                        List<String> l = hdrs.get(k);
                        if (l == null) { l = new ArrayList<String>(); hdrs.put(k, l); }
                        l.add(e.getValue());
                    }
                }
                headersReady.countDown();
                st.startIdleGuard(r);
                r.read(ByteBuffer.allocateDirect(32 * 1024));
            }

            @Override public void onReadCompleted(UrlRequest r, UrlResponseInfo info, ByteBuffer buf) {
                buf.flip();
                int n = buf.remaining();
                if (n > 0) {
                    byte[] b = new byte[n];
                    buf.get(b);
                    try {
                        st.touch();
                        out.write(b);
                    } catch (Exception e) {
                        failure.compareAndSet(null, e);
                        r.cancel();
                        return;
                    }
                }
                buf.clear();
                r.read(buf);
            }

            @Override public void onSucceeded(UrlRequest r, UrlResponseInfo info) {
                done.set(true);
                st.finish();
                headersReady.countDown();
                closeQuietly(out);
            }

            @Override public void onFailed(UrlRequest r, UrlResponseInfo info, CronetException e) {
                if (BuildConfig.DEBUG) Log.w(TAG, "failed " + e);
                failure.compareAndSet(null, e);
                done.set(true);
                st.finish();
                headersReady.countDown();
                closeQuietly(out);
            }

            @Override public void onCanceled(UrlRequest r, UrlResponseInfo info) {
                done.set(true);
                st.finish();
                headersReady.countDown();
                closeQuietly(out);
            }
        };

        UrlRequest.Builder rb = engine.newUrlRequestBuilder(req.url, cb, CALLBACK_POOL)
                .setHttpMethod(req.method);
        for (Map.Entry<String, String> e : req.headers.entrySet()) {
            try {
                rb.addHeader(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ex) {
                // Cronet 拒绝由它自己管理的头（Host/Content-Length 等）；我们不靠这些头
                if (BuildConfig.DEBUG) Log.w(TAG, "header skipped: " + e.getKey());
            }
        }
        if (req.body != null) {
            rb.setUploadDataProvider(UploadDataProviders.create(req.body), CALLBACK_POOL);
        }
        UrlRequest request = rb.build();
        if (BuildConfig.DEBUG) {
            Log.d(TAG, req.method + " " + req.url + " body=" + (req.body == null ? 0 : req.body.length));
        }
        request.start();

        ScheduledFuture<?> connectGuard = TIMER.schedule(new Runnable() {
            @Override public void run() {
                if (headersReady.getCount() == 0) return;
                failure.compareAndSet(null, new SocketTimeoutException("连接超时"));
                request.cancel();
            }
        }, req.connectTimeoutMs, TimeUnit.MILLISECONDS);

        try {
            if (!headersReady.await(req.connectTimeoutMs + 500L, TimeUnit.MILLISECONDS)) {
                request.cancel();
                throw new SocketTimeoutException("连接超时");
            }
        } finally {
            connectGuard.cancel(false);
        }
        int c = code.get();
        Throwable err = failure.get();
        if (c == 0) {
            closeQuietly(out);
            if (err instanceof Exception) throw (Exception) err;
            throw new IOException(err == null ? "请求失败" : err.toString());
        }
        return new CronetConn(request, c, hdrs, new FailureInputStream(in, failure), out, done, failure);
    }

    private static final class RequestState {
        private final int readTimeoutMs;
        private final AtomicReference<Throwable> failure;
        private volatile UrlRequest request;
        private volatile ScheduledFuture<?> idle;

        RequestState(int readTimeoutMs, AtomicReference<Throwable> failure) {
            this.readTimeoutMs = readTimeoutMs;
            this.failure = failure;
        }

        void startIdleGuard(UrlRequest r) {
            request = r;
            schedule();
        }

        void touch() {
            if (idle != null) idle.cancel(false);
            schedule();
        }

        private void schedule() {
            UrlRequest r = request;
            if (readTimeoutMs <= 0 || r == null) return;
            idle = TIMER.schedule(new Runnable() {
                @Override public void run() {
                    failure.compareAndSet(null, new SocketTimeoutException("读超时"));
                    r.cancel();
                }
            }, readTimeoutMs, TimeUnit.MILLISECONDS);
        }

        void finish() {
            if (idle != null) idle.cancel(false);
        }
    }

    private static final class CronetConn implements HttpNet.Conn {
        private final UrlRequest request;
        private final int code;
        private final Map<String, List<String>> hdrs;
        private final InputStream in;
        private final PipedOutputStream out;
        private final AtomicBoolean done;
        private final AtomicReference<Throwable> failure;

        CronetConn(UrlRequest request, int code, Map<String, List<String>> hdrs,
                   InputStream in, PipedOutputStream out, AtomicBoolean done,
                   AtomicReference<Throwable> failure) {
            this.request = request;
            this.code = code;
            this.hdrs = hdrs;
            this.in = in;
            this.out = out;
            this.done = done;
            this.failure = failure;
        }

        @Override public int code() { return code; }

        @Override public String header(String name) {
            List<String> v = headers(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        @Override public List<String> headers(String name) {
            synchronized (hdrs) {
                return hdrs.get(name == null ? "" : name.toLowerCase(Locale.US));
            }
        }

        @Override public InputStream body() { return in; }

        @Override public void abort() {
            try { if (!done.get()) request.cancel(); } catch (Exception ignored) { }
            closeQuietly(out);
        }

        @Override public void close() {
            try { in.close(); } catch (Exception ignored) { }
            abort();
        }
    }

    /**
     * Piped 流在回调失败时只会给 EOF；这层把「EOF 前有过错误」翻成 IOException，
     * 免得半截回答被当成正常结束。
     */
    private static final class FailureInputStream extends InputStream {
        private final InputStream in;
        private final AtomicReference<Throwable> failure;
        private boolean eof;

        FailureInputStream(InputStream in, AtomicReference<Throwable> failure) {
            this.in = in;
            this.failure = failure;
        }

        @Override public int read() throws IOException {
            int b = in.read();
            check(b < 0);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            check(n < 0);
            return n;
        }

        private void check(boolean end) throws IOException {
            if (!end || eof) return;
            eof = true;
            Throwable t = failure.get();
            if (t != null) {
                throw new IOException(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }

        @Override public void close() throws IOException { in.close(); }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) { }
    }
}
