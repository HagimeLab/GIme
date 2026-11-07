package com.fan.shuatiku;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回环播放流代理的回归测试（真起 socket，仿 McpServerTest）。
 *
 * <p>这里验的是「档 2 之所以是档 2」的那三条硬事实：
 * Referer/UA/Cookie 必须带上去、Range 必须原样透传（拖进度条靠它）、
 * 以及页面只能拿到回环地址、拿不到 B 站直链。
 */
public class BiliStreamTest {

    private static int pass = 0;
    private static int fail = 0;

    /** 假装是 B 站 CDN：记住收到的请求头，按 Range 切一小段回包。 */
    private static volatile Map<String, String> lastHeaders;
    private static volatile String lastUrl;
    /** 每一跳上游请求都记一笔，好按次序断言「第一次没要 Range、第二次要了」。 */
    private static final List<Map<String, String>> SEEN = new ArrayList<Map<String, String>>();
    private static final List<String> SEEN_URL = new ArrayList<String>();

    private static final String UP = "https://n.node.mcdn.bilivideo.cn/upgcxcode/1.mp4?deadline=1";
    private static final byte[] FULL = new byte[512];

    static {
        for (int i = 0; i < FULL.length; i++) FULL[i] = (byte) (i & 0xff);
    }

    public static void main(String[] args) throws Exception {
        HttpNet.install(new HttpNet.Impl() {
            @Override public HttpNet.Conn open(HttpNet.Request req) {
                lastUrl = req.url;
                lastHeaders = new LinkedHashMap<String, String>(req.headers);
                SEEN.add(new LinkedHashMap<String, String>(req.headers));
                SEEN_URL.add(req.url);
                String range = req.headers.get("Range");
                int from = 0, to = FULL.length - 1;
                boolean partial = false;
                if (range != null && range.startsWith("bytes=")) {
                    String[] p = range.substring(6).split("-");
                    from = Integer.parseInt(p[0]);
                    if (p.length > 1 && !p[1].isEmpty()) to = Integer.parseInt(p[1]);
                    partial = true;
                }
                int len = to - from + 1;
                byte[] slice = new byte[len];
                System.arraycopy(FULL, from, slice, 0, len);
                Map<String, List<String>> hd = new LinkedHashMap<String, List<String>>();
                hd.put("content-type", listOf("video/mp4"));
                hd.put("content-length", listOf(String.valueOf(len)));
                hd.put("accept-ranges", listOf("bytes"));
                /* 上游爱带的东西一律不许跟着进来：会话身份、分块编码、连接指令 */
                hd.put("set-cookie", listOf("SESSDATA=leak; Path=/"));
                hd.put("transfer-encoding", listOf("chunked"));
                if (partial) hd.put("content-range", listOf("bytes " + from + "-" + to + "/" + FULL.length));
                int code = partial ? 206 : 200;
                return new Fake(code, hd, slice);
            }
        });
        BiliStream.setCookieSource(new BiliStream.CookieSource() {
            @Override public String cookie() { return "SESSDATA=test-cookie"; }
        });

        /* ---- 1. 发号 ---- */
        String page = BiliStream.issue(UP);
        checkBool("回给页面的是回环地址", page.startsWith("http://127.0.0.1:8766/bili/"));
        checkBool("页面地址里没有 B 站直链", page.indexOf("bilivideo") < 0);
        checkBool("地址里也没有 cookie", page.indexOf("SESSDATA") < 0);
        checkBool("服务已就绪", BiliStream.isRunning());
        boolean refused = false;
        try { BiliStream.issue("https://evil.example.com/x.mp4"); }
        catch (Exception e) { refused = true; }
        checkBool("非白名单主机的地址不发号", refused);
        boolean refused2 = false;
        try { BiliStream.issue("http://n.node.mcdn.bilivideo.cn/x.mp4"); }
        catch (Exception e) { refused2 = true; }
        checkBool("明文 http 的地址不发号", refused2);

        /* ---- 2. 整段取 ---- */
        Resp r0 = ask(pathOf(page), null);
        checkBool("整段取回 200", r0.status == 200);
        checkBool("带上游给的真实长度", "512".equals(r0.h("content-length")));
        checkBool("声明支持 Range（拖动的前提）", "bytes".equals(r0.h("accept-ranges")));
        checkBool("字节数对得上", r0.body.length == 512);
        checkBool("真正去拉的是 B 站直链", UP.equals(SEEN_URL.get(0)));
        checkBool("上游收到了 Referer", SEEN.get(0).get("Referer") != null);
        checkBool("上游收到了浏览器 UA", SEEN.get(0).get("User-Agent").indexOf("Mozilla/5.0") == 0);
        checkBool("上游收到了整串 cookie", "SESSDATA=test-cookie".equals(SEEN.get(0).get("Cookie")));
        checkBool("整段取时不带 Range 头", SEEN.get(0).get("Range") == null);

        /* ---- 3. 分段取（拖进度条那条路） ---- */
        Resp r1 = ask(pathOf(page), "bytes=100-199");
        checkBool("分段取回 206", r1.status == 206);
        checkBool("Range 原样透传给上游", "bytes=100-199".equals(SEEN.get(1).get("Range")));
        checkBool("Content-Range 原样回给页面", "bytes 100-199/512".equals(r1.h("content-range")));
        checkBool("只回这一段", r1.body.length == 100 && r1.body[0] == (byte) 100);

        /* ---- 4. 上游的杂头不许漏进来 ---- */
        checkBool("不透传 set-cookie", r1.h("set-cookie") == null);
        checkBool("不透传 transfer-encoding", r1.h("transfer-encoding") == null);
        checkBool("连接按 close 收", "close".equals(r1.h("connection")));

        /* ---- 5. 认不出的编号一律 404 ---- */
        Resp r2 = ask("/bili/deadbeefdeadbeefdeadbeefdeadbeef.mp4", null);
        checkBool("没发过的编号 404", r2.status == 404);
        checkBool("路径形状之外的都 404", ask("/bili/nope.mp4", null).status == 404
                && ask("/mcp", null).status == 404);
        Resp r3 = raw("POST " + pathOf(page) + " HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n");
        checkBool("非 GET/HEAD 一律 405", r3.status == 405);

        /* ---- 6. 退出登录即回收 ---- */
        BiliStream.revokeAll();
        checkBool("回收后旧编号变 404", ask(pathOf(page), null).status == 404);

        /* ---- 7. 纯函数口径 ---- */
        checkBool("主机名解析带端口也对", "a.bilibili.com".equals(BiliStream.hostOf("https://a.bilibili.com:4483/x")));
        checkBool("用户信息伪装主机被拒",
                !BiliStream.allowed("https://evil.com/@a.bilibili.com/x", BiliStream.hostOf("https://evil.com/@a.bilibili.com/x")));
        checkBool("后缀必须真是结尾（bilibili.com.evil 不算）",
                !BiliStream.allowed("https://bilibili.com.evil.cn/x", "bilibili.com.evil.cn"));
        checkBool("编号必须是 32 位小写 hex", BiliStream.tokenOf("/bili/" + repeat("A", 32) + ".mp4") == null
                && BiliStream.tokenOf("/bili/" + repeat("a", 32) + ".mp4") != null);

        BiliStream.stop();
        System.out.println();
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
        System.out.println("全部通过：" + pass + " 项");
    }

    /* ==================== 小工具 ==================== */

    private static String pathOf(String page) {
        int i = page.indexOf("8766") + 4;
        return page.substring(i);
    }

    private static Resp ask(String path, String range) throws Exception {
        StringBuilder sb = new StringBuilder("GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:8766\r\n");
        if (range != null) sb.append("Range: ").append(range).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return raw(sb.toString());
    }

    private static Resp raw(String req) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", BiliStream.PORT), 5000);
        s.setSoTimeout(8000);
        try {
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStreamHolder bh = new ByteArrayOutputStreamHolder();
            int n;
            while ((n = in.read()) >= 0) {
                bh.write(n);
                if (bh.endsWithCrlfCrlf()) break;
            }
            String head = bh.headText();
            /* 累加器在 \r\n\r\n 那一下恰好停住，所以缓冲区里没有 body 字节：
               body 从这一刻起才在流里。 */
            List<Byte> all = new ArrayList<Byte>();
            while ((n = in.read()) >= 0) all.add((byte) n);
            byte[] body = new byte[all.size()];
            for (int i = 0; i < all.size(); i++) body[i] = all.get(i);
            String[] lines = head.split("\r\n");
            Map<String, String> h = new LinkedHashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(),
                                  lines[i].substring(c + 1).trim());
            }
            int code = lines[0].split(" ").length >= 2
                    ? Integer.parseInt(lines[0].split(" ")[1]) : -1;
            return new Resp(code, h, body);
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private static List<String> listOf(String v) {
        List<String> l = new ArrayList<String>();
        l.add(v);
        return l;
    }

    private static void checkBool(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    private static final class Resp {
        final int status;
        final Map<String, String> h;
        final byte[] body;
        Resp(int status, Map<String, String> h, byte[] body) {
            this.status = status;
            this.h = h;
            this.body = body;
        }
        String h(String n) { return h.get(n); }
    }

    /** 读头的小累加器，省得为一次测试引一堆缓冲类。 */
    private static final class ByteArrayOutputStreamHolder {
        private final java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        void write(int c) { b.write(c); }
        boolean endsWithCrlfCrlf() {
            byte[] a = b.toByteArray();
            int n = a.length;
            return n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n';
        }
        String headText() {
            byte[] a = b.toByteArray();
            return new String(a, 0, Math.max(0, a.length - 4), StandardCharsets.ISO_8859_1);
        }
    }

    /** 假上游：固定字节 + 固定头，形状与 HttpNet.Conn 一一对应。 */
    private static final class Fake implements HttpNet.Conn {
        private final int code;
        private final Map<String, List<String>> hd;
        private final byte[] body;
        Fake(int code, Map<String, List<String>> hd, byte[] body) {
            this.code = code;
            this.hd = hd;
            this.body = body;
        }
        @Override public int code() { return code; }
        @Override public String header(String name) {
            List<String> v = hd.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }
        @Override public List<String> headers(String name) { return hd.get(name.toLowerCase()); }
        @Override public InputStream body() { return new ByteArrayInputStream(body); }
        @Override public void abort() { }
        @Override public void close() { }
    }
}
