import com.fan.shuatiku.McpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** McpServer 的 HTTP 层：口令、OPTIONS、通知 202、GET 405、停止后端口释放。 */
public class McpServerTest {

    private static int pass = 0, fail = 0;

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else {
            fail++;
            System.out.println("  [FAIL] " + name + (detail == null ? "" : "  (" + detail + ")"));
        }
    }

    private static final class Resp {
        int code;
        String body;
    }

    public static void main(String[] args) throws Exception {
        ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = probe.getLocalPort();
        probe.close();

        final String token = "test-token-123";
        McpServer s = new McpServer(port, token, new McpServer.Handler() {
            @Override public String handle(String requestJson) {
                return requestJson.contains("\"notification\"")
                        ? ""
                        : "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
            }
        });
        ok("服务能绑到回环端口", s.start(), s.error());

        Resp r = req(port, "POST", "/mcp", null, "{}", null);
        ok("没口令返回 401", r.code == 401, "HTTP " + r.code);
        r = req(port, "POST", "/mcp", "Bearer wrong", "{}", null);
        ok("错口令返回 401", r.code == 401, "HTTP " + r.code);
        r = req(port, "POST", "/mcp", "Bearer " + token, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", null);
        ok("口令正确返回 200 且回包来自 Handler",
                r.code == 200 && r.body.indexOf("\"ok\":true") >= 0, "HTTP " + r.code + " " + r.body);
        r = req(port, "POST", "/mcp", null, "{}", token);
        ok("X-GIme-Token 也认", r.code == 200, "HTTP " + r.code);
        r = req(port, "POST", "/mcp", "Bearer " + token, "{\"method\":\"notification\"}", null);
        ok("通知回 202", r.code == 202, "HTTP " + r.code);
        r = req(port, "OPTIONS", "/mcp", null, null, null);
        ok("OPTIONS 回 204", r.code == 204, "HTTP " + r.code);
        r = req(port, "GET", "/mcp", null, null, null);
        ok("GET 回 405（不提供 SSE）", r.code == 405, "HTTP " + r.code);
        r = req(port, "POST", "/nope", "Bearer " + token, "{}", null);
        ok("别的路径回 404", r.code == 404, "HTTP " + r.code);

        s.stop();
        ok("停止后端口可以重新绑定", canBind(port), "port still busy");

        System.out.println("\n全部通过：" + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    private static Resp req(int port, String method, String path, String auth, String body,
                            String xToken) throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (xToken != null) c.setRequestProperty("X-GIme-Token", xToken);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        Resp r = new Resp();
        try {
            r.code = c.getResponseCode();
        } catch (IOException e) {
            r.code = -1;
            return r;
        }
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            } finally {
                in.close();
            }
        }
        r.body = new String(b.toByteArray(), StandardCharsets.UTF_8);
        c.disconnect();
        return r;
    }

    private static boolean canBind(int port) {
        try {
            ServerSocket t = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            t.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
