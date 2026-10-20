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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 只绑 127.0.0.1 的 MCP Streamable HTTP 端点。
 *
 * 协议分发在页面 JS 里（题库解析、落盘、渲染都复用同一套逻辑），这一层只做
 * HTTP 帧、口令、202 通知、把请求交给 Handler、把回包写回去。
 * 工具本身不在这里实现，也不解析 JSON-RPC —— 少一处语义就会多一份漂移。
 */
public final class McpServer {

    /** 把一段 JSON-RPC 请求交给页面，返回页面生成的回包；通知返回空串。 */
    public interface Handler {
        String handle(String requestJson);
    }

    public static final int PORT = 8765;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_HEAD = 16 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 30000;

    private final int port;
    private final Handler handler;
    private final String sessionId;
    private volatile String token;
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile String error = "";

    public McpServer(int port, String token, Handler handler) {
        this.port = port;
        this.token = token == null ? "" : token;
        this.handler = handler;
        this.sessionId = randomHex(12);
    }

    public synchronized boolean start() {
        if (running) return true;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            server = ss;
            running = true;
            error = "";
            Thread t = new Thread(new Runnable() {
                @Override public void run() { acceptLoop(); }
            }, "gime-mcp");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (Exception e) {
            server = null;
            running = false;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocket ss = server;
        server = null;
        if (ss != null) {
            try { ss.close(); } catch (Exception ignored) { }
        }
    }

    public boolean isRunning() { return running; }
    public int port() { return port; }
    public String token() { return token; }
    public String error() { return error; }
    public void setToken(String t) { token = t == null ? "" : t; }

    public static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 0xff));
        return s.toString();
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket ss = server;
            if (ss == null) break;
            try {
                final Socket sock = ss.accept();
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { serve(sock); }
                }, "gime-mcp-conn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (ss.isClosed() || !running) break;
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
        }
    }

    private void serve(Socket sock) {
        try {
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] start = lines.length == 0 ? new String[0] : lines[0].split(" ");
            if (start.length < 3) { json(out, 400, errorJson("bad request line")); return; }

            Map<String, String> h = new HashMap<String, String>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) h.put(lines[i].substring(0, c).trim().toLowerCase(Locale.US),
                                  lines[i].substring(c + 1).trim());
            }

            String method = start[0];
            String path = start[1];
            int qm = path.indexOf('?');
            if (qm >= 0) path = path.substring(0, qm);

            if ("OPTIONS".equalsIgnoreCase(method)) { cors(out, 204, null, null); return; }
            if (!"/mcp".equals(path) && !"/".equals(path)) {
                json(out, 404, errorJson("not found; MCP endpoint is /mcp"));
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                json(out, 405, errorJson("use POST /mcp with JSON-RPC; SSE is not offered"));
                return;
            }
            if (!authorized(h)) {
                json(out, 401, errorJson("unauthorized: send Authorization: Bearer <token>"));
                return;
            }
            int len;
            try { len = Integer.parseInt(h.get("content-length")); }
            catch (Exception e) { len = -1; }
            if (len < 0) { json(out, 411, errorJson("Content-Length required")); return; }
            if (len > MAX_BODY) { json(out, 413, errorJson("request body too large")); return; }
            byte[] body = readN(in, len);
            if (body == null) { json(out, 400, errorJson("truncated body")); return; }

            String resp;
            try {
                resp = handler.handle(new String(body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":"
                        + quote("internal error: " + e.getMessage()) + "}}";
            }
            if (resp == null || resp.isEmpty()) { cors(out, 202, null, null); return; }
            cors(out, 200, "application/json; charset=utf-8", resp);
        } catch (Exception ignored) {
            // 连接级的异常不往上抛：一个坏请求不该带走整个服务
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    private boolean authorized(Map<String, String> h) {
        String t = token;
        if (t == null || t.isEmpty()) return false;
        String a = h.get("authorization");
        if (a != null && a.regionMatches(true, 0, "bearer ", 0, 7)
                && same(a.substring(7).trim(), t)) return true;
        String x = h.get("x-gime-token");
        return x != null && same(x.trim(), t);
    }

    private static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                                     b.getBytes(StandardCharsets.UTF_8));
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

    private static byte[] readN(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n <= 0) return null;
            off += n;
        }
        return b;
    }

    private static void json(OutputStream out, int code, String body) throws IOException {
        cors(out, code, "application/json; charset=utf-8", body);
    }

    private static void cors(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n");
        h.append("Access-Control-Allow-Headers: Authorization, Content-Type, Mcp-Session-Id, X-GIme-Token\r\n");
        h.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n");
        h.append("Access-Control-Expose-Headers: Mcp-Session-Id\r\n");
        h.append("Connection: close\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (b.length > 0) out.write(b);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            default: return "OK";
        }
    }

    private static String errorJson(String msg) {
        return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":"
                + quote(msg) + "}}";
    }

    private static String quote(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
