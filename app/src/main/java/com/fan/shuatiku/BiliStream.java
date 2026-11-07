package com.fan.shuatiku;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 只绑 127.0.0.1 的播放流代理 —— B 站收藏夹功能的原生半边。
 *
 * <p>为什么非有它不可：2026-11-04 实测，B 站 CDN 拉流<b>同时</b>校验
 * {@code Referer} 与浏览器系 {@code User-Agent}，只带其中一个回 403；而
 * {@code <video src=直链>} 在 WebView 里既改不动 Origin 也塞不进 Referer。
 * 于是把「谁去拉流」换成原生：页面只看到一个本机回环地址，请求由这里带上
 * Referer / UA / Cookie 转发，并把 {@code Range} 原样透传（拖动进度条全靠它）。
 *
 * <p>顺带解决第二件事：直链带 {@code deadline}、几十分钟就废，而且 URL 里就编着
 * 会话身份。这两样都不该出现在页面手里 —— 页面拿到的是一个一次性编号，
 * 真地址只活在这张进程内的表里。
 *
 * <p>形状照 {@link McpServer}（同一个仓库里已有的回环服务），但语义完全不同：
 * 那边是 JSON-RPC 一问一答，这边是字节流转发，所以不共用端口，也不走它的口令。
 */
public final class BiliStream {

    public static final int PORT = 8766;
    /** 编号存活时间。直链本身就比这个短命，做长了也没意义。 */
    static final long TTL_MS = 20 * 60 * 1000L;
    /** 同时在拉的上游条数上限：一次拖动会瞬发好几个 Range 请求，放开没好处。 */
    static final int MAX_ACTIVE = 8;
    /** 表里最多留多少个旧编号，超了就挤掉最老的（过期回收之外的兜底）。 */
    static final int MAX_TICKETS = 32;

    private static final int MAX_HEAD = 8 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 15000;
    private static final String TAG = "GImeBili";

    /**
     * 允许代理的主机后缀。
     *
     * <p>这张表的职责不是「判 URL 是不是 B 站的」（URL 只可能来自
     * {@link BiliWeb#play} 的上游响应，页面碰不到），而是「万一有 bug，
     * 这台回环服务也不许变成任意 HTTP 代理」。所以宁可窄：
     * 哪天 B 站换了 CDN，这里会明确报「不在允许的主机里」，而不是悄悄放行。
     */
    private static final String[] ALLOW_SUFFIXES = {
            ".bilivideo.com",
            ".bilivideo.cn",
            ".mcdn.bilivideo.cn",
            ".bilibili.com",
            ".hdslb.com",
            // 2026-11-04 playurl 实际给出的边缘节点就是这一族
            ".mountaintoys.cn",
    };

    /** 取当前 cookie 的口子，由 {@code MainActivity.Bridge} 在启动时接上。 */
    public interface CookieSource { String cookie(); }

    private static volatile CookieSource cookies = new CookieSource() {
        @Override public String cookie() { return ""; }
    };

    private static final class Ticket {
        final String url;
        final long at;
        Ticket(String url) { this.url = url; this.at = System.currentTimeMillis(); }
        boolean alive() { return System.currentTimeMillis() - at < TTL_MS; }
    }

    /** 访问序 LinkedHashMap：正在播的编号不会被最久没碰的挤掉。 */
    private static final LinkedHashMap<String, Ticket> TICKETS =
            new LinkedHashMap<String, Ticket>(16, 0.75f, true);

    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile ServerSocket server;
    private static volatile boolean running;
    private static volatile String error = "";

    private BiliStream() { }

    public static void setCookieSource(CookieSource s) {
        if (s != null) cookies = s;
    }

    public static boolean isRunning() { return running; }
    public static String error() { return error; }

    /** 号发出去多少个（诊断用：拖动进度条时这里会跳）。 */
    public static int openCount() { return ACTIVE.get(); }

    /**
     * 为一个播放地址发一个编号，返回页面可以直接塞进 {@code <video src>} 的地址。
     *
     * <p>启动是惰性的：没看过收藏夹的人不会多一个监听端口。
     */
    public static String issue(String url) throws IOException {
        String host = hostOf(url);
        if (!allowed(url, host)) throw new IOException("播放地址不在允许的主机里：" + host);
        ensureStarted();
        String token = McpServer.randomHex(16);
        synchronized (TICKETS) {
            pruneLocked();
            TICKETS.put(token, new Ticket(url));
            if (TICKETS.size() > MAX_TICKETS) {
                java.util.Iterator<String> it = TICKETS.keySet().iterator();
                while (it.hasNext() && TICKETS.size() > MAX_TICKETS) {
                    it.next();
                    it.remove();
                }
            }
        }
        return "http://127.0.0.1:" + PORT + "/bili/" + token + ".mp4";
    }

    /** 退出登录：编号全清，正在放的这一路自然断，但绝不留旧地址给下一个会话。 */
    public static void revokeAll() {
        synchronized (TICKETS) { TICKETS.clear(); }
    }

    public static synchronized void stop() {
        running = false;
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (Exception ignored) { }
        }
    }

    private static synchronized void ensureStarted() throws IOException {
        if (running && server != null) return;
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
        server = ss;
        running = true;
        error = "";
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "gime-bili");
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop() {
        while (running) {
            ServerSocket ss = server;
            if (ss == null) break;
            try {
                final Socket sock = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { serve(sock); }
                }, "gime-bili-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (ss.isClosed() || !running) break;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
    }

    private static void serve(Socket sock) {
        HttpNet.Conn up = null;
        try {
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if (head == null) return;

            String[] lines = head.split("\r\n");
            String[] start = lines.length == 0 ? new String[0] : lines[0].split(" ");
            if (start.length < 2) { plain(out, 400, "bad request line"); return; }
            String method = start[0].toUpperCase(Locale.US);
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                plain(out, 405, "GET or HEAD only");
                return;
            }

            Map<String, String> h = new LinkedHashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                                  lines[i].substring(c + 1).trim());
            }

            String token = tokenOf(start[1]);
            if (token == null) { plain(out, 404, "no such stream"); return; }
            String url;
            synchronized (TICKETS) {
                Ticket t = TICKETS.get(token);
                if (t == null) { plain(out, 404, "no such stream"); return; }
                if (!t.alive()) { plain(out, 410, "link expired"); return; }
                url = t.url;
            }

            if (ACTIVE.incrementAndGet() > MAX_ACTIVE) {
                ACTIVE.decrementAndGet();
                plain(out, 503, "too many concurrent streams");
                return;
            }
            try {
                Map<String, String> up1 = BiliWeb.headers(cookies.cookie());
                String range = h.get("range");
                if (range != null && !range.isEmpty()) up1.put("Range", range);
                up = HttpNet.open(new HttpNet.Request(url, method, up1, null, 8000, 30000));
                pipe(out, up, method);
            } finally {
                ACTIVE.decrementAndGet();
            }
        } catch (Exception e) {
            // 半截断开是常态（用户拖进度条、划走页面），不值得往日志里灌栈
            if (running) androidFree(e);
        } finally {
            if (up != null) {
                try { up.abort(); } catch (Exception ignored) { }
                try { up.close(); } catch (Exception ignored) { }
            }
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /** 异常只在「不是客户端先挂」时才有信息量；这里统一交给日志钩子，保持零 Android 依赖。 */
    private static void androidFree(Exception e) {
        System.err.println("[GImeBili] " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    /** 上游的头只透传这四个，其余一概不带 —— 特别是 transfer-encoding 与 set-cookie。 */
    private static void pipe(OutputStream out, HttpNet.Conn up, String method) throws IOException {
        int code = up.code();
        String type = pick(up, "content-type", "video/mp4");
        /* CDN 对 mp4 经常回 application/octet-stream，个别 <video> 会当成「不认识的类型」直接报
           MEDIA_ERR_SRC_NOT_SUPPORTED。这里是本机代理，内容只有一种，统一按 video/mp4 回。 */
        if (type == null || type.toLowerCase(Locale.US).contains("octet-stream")
                || type.toLowerCase(Locale.US).startsWith("text/")) type = "video/mp4";
        String len = pick(up, "content-length", null);
        String range = up.header("content-range");
        String acc = pick(up, "accept-ranges", "bytes");
        if (len == null) {
            // 没有长度就没法写 Content-Length，也就没法定位「拖到第几秒」；宁可明说
            plain(out, 502, "upstream gave no content length");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        if (range != null) sb.append("Content-Range: ").append(range).append("\r\n");
        sb.append("Accept-Ranges: ").append(acc).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if ("HEAD".equals(method)) { out.flush(); return; }

        InputStream body = up.body();
        if (body == null) { out.flush(); return; }
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = body.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String pick(HttpNet.Conn up, String name, String fallback) {
        String v = up.header(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 404: return "Not Found";
            case 403: return "Forbidden";
            case 410: return "Gone";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    /** 只认 {@code /bili/<32 hex>.mp4}；形状不对直接当没有这个流。 */
    static String tokenOf(String path) {
        if (path == null) return null;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!path.startsWith("/bili/") || !path.endsWith(".mp4")) return null;
        String t = path.substring("/bili/".length(), path.length() - ".mp4".length());
        if (t.length() != 32) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return null;
        }
        return t;
    }

    /** https + 主机落在允许后缀里。两条都过才算允许。 */
    static boolean allowed(String url, String host) {
        if (url == null || host == null) return false;
        String h = host.toLowerCase(Locale.US);
        if (!url.toLowerCase(Locale.US).startsWith("https://")) return false;
        for (int i = 0; i < ALLOW_SUFFIXES.length; i++) {
            if (h.endsWith(ALLOW_SUFFIXES[i])) return true;
        }
        return false;
    }

    /** 取主机名（去端口、去用户信息）。手搓是因为不想为这一个动作引入 android.net.Uri。 */
    static String hostOf(String url) {
        if (url == null) return "";
        int s = url.indexOf("://");
        if (s < 0) return "";
        int e = s + 3;
        while (e < url.length() && url.charAt(e) != '/' && url.charAt(e) != '?'
                && url.charAt(e) != '#') e++;
        String host = url.substring(s + 3, e);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host;
    }

    private static void pruneLocked() {
        java.util.Iterator<Map.Entry<String, Ticket>> it = TICKETS.entrySet().iterator();
        while (it.hasNext()) {
            if (!it.next().getValue().alive()) it.remove();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            int n = b.size();
            if (n >= 4) {
                byte[] a = b.toByteArray();
                if (a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                    return new String(a, 0, n - 4, StandardCharsets.ISO_8859_1);
                }
            }
            if (n > MAX_HEAD) return null;
        }
        return null;
    }

    private static void plain(OutputStream out, int code, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
