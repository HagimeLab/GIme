#!/usr/bin/env node
/**
 * MCP stdio 桥：把 Qoder / Claude 等客户端的 stdio 请求转到手机上 GIme 的 HTTP 端点。
 *
 * mcpServers 配置（路径按本机填写，口令从 App「我的 → MCP 接口」复制）：
 *   "gime": {
 *     "command": "node",
 *     "args": ["<repo>/tools/gime-mcp.mjs"],
 *     "env": { "GIME_TOKEN": "<口令>", "ADB": "<可选 adb 路径>" }
 *   }
 *
 * 环境变量：
 *   GIME_TOKEN  必填，App 里显示的 MCP 口令
 *   GIME_URL    可选，默认 http://127.0.0.1:8765/mcp
 *   ADB         可选；给了就先执行 adb forward tcp:8765 tcp:8765
 *
 * 客户端跑 stdio，App 侧是 Streamable HTTP；这里只做转发，工具定义与执行都在 App 内，
 * 不维护第二份 schema。stdio 帧按 MCP 规范用换行分隔 JSON。
 */
import { execFileSync } from "node:child_process";

const ENDPOINT = process.env.GIME_URL || "http://127.0.0.1:8765/mcp";
const TOKEN = process.env.GIME_TOKEN || "";
const ADB = process.env.ADB || "";

if (!TOKEN) {
  process.stderr.write("GIme MCP：缺少 GIME_TOKEN（App 里「我的 → MCP 接口」复制口令）\n");
  process.exit(2);
}
if (ADB) {
  const m = /:(\d+)\/mcp/.exec(ENDPOINT);
  const port = m ? m[1] : "8765";
  try {
    execFileSync(ADB, ["forward", `tcp:${port}`, `tcp:${port}`], { stdio: "ignore" });
  } catch (e) {
    process.stderr.write("GIme MCP：adb forward 失败：" + e.message + "\n");
  }
}

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (line) handle(line);
  }
});
process.stdin.on("end", () => { if (buf.trim()) handle(buf.trim()); });

async function handle(line) {
  let req;
  try { req = JSON.parse(line); } catch { return; }
  const hasId = req && req.id !== undefined && req.id !== null;
  try {
    const res = await fetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": "Bearer " + TOKEN },
      body: line,
      signal: AbortSignal.timeout(25000),
    });
    if (res.status === 202) return;
    const text = await res.text();
    if (!hasId) return;
    if (res.ok) {
      process.stdout.write(text + "\n");
    } else {
      process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: req.id,
        error: { code: -32000, message: "GIme 返回 HTTP " + res.status + "：" + text.slice(0, 300) } }) + "\n");
    }
  } catch (e) {
    if (!hasId) return;
    process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: req.id,
      error: { code: -32000, message: "连不上 GIme：" + e.message } }) + "\n");
  }
}
