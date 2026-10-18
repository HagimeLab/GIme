/**
 * 极简 HTTP 代理（支持 CONNECT 隧道），给没有 Wi-Fi 的真机做联网验证用。
 * 用法：node tools/dev-proxy.mjs [port]
 * 真机侧：adb reverse tcp:<port> tcp:<port> && adb shell settings put global http_proxy 127.0.0.1:<port>
 */
import net from "node:net";
import http from "node:http";

const port = Number(process.argv[2] || 8899);

const server = http.createServer((req, res) => {
  const target = new URL(req.url);
  const up = http.request(
    { host: target.hostname, port: target.port || 80, path: target.pathname + target.search, method: req.method, headers: req.headers },
    (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); }
  );
  up.on("error", () => { try { res.writeHead(502); res.end(); } catch {} });
  req.pipe(up);
});

server.on("connect", (req, sock, head) => {
  const [host, p] = req.url.split(":");
  const up = net.connect(Number(p || 443), host, () => {
    sock.write("HTTP/1.1 200 Connection Established\r\n\r\n");
    if (head && head.length) up.write(head);
    up.pipe(sock);
    sock.pipe(up);
  });
  up.on("error", () => { try { sock.destroy(); } catch {} });
  sock.on("error", () => { try { up.destroy(); } catch {} });
});

server.listen(port, "127.0.0.1", () => console.log(`proxy on 127.0.0.1:${port}`));
