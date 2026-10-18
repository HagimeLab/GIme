/**
 * 通过 CDP 在真机 WebView 上跑运行时断言。
 * 用法：node tools/device-cdp.mjs <ws-url>
 * 只读式检查：不改动用户的题库与进度（交卷/放弃类用例使用独立导入题库副本并复原）。
 */
import net from "node:net";
import crypto from "node:crypto";

const wsUrl = process.argv[2];
if (!wsUrl) { console.error("用法: node device-cdp.mjs ws://127.0.0.1:9333/devtools/page/<id>"); process.exit(2); }

/* 极简 WebSocket 客户端（node 无内置 ws，避免装依赖） */
function connect(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const key = crypto.randomBytes(16).toString("base64");
    const sock = net.connect(Number(u.port), u.hostname, () => {
      sock.write(
        `GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`
      );
    });
    let buf = Buffer.alloc(0);
    let upgraded = false;
    const waiters = new Map();
    let seq = 0;
    function send(obj) {
      const data = Buffer.from(JSON.stringify(obj));
      const mask = crypto.randomBytes(4);
      let header;
      if (data.length < 126) header = Buffer.from([0x81, 0x80 | data.length]);
      else if (data.length < 65536) { header = Buffer.alloc(4); header[0] = 0x81; header[1] = 0x80 | 126; header.writeUInt16BE(data.length, 2); }
      else { header = Buffer.alloc(10); header[0] = 0x81; header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(data.length), 2); }
      const masked = Buffer.from(data);
      for (let i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
      sock.write(Buffer.concat([header, mask, masked]));
    }
    function onMessage(text) {
      let msg; try { msg = JSON.parse(text); } catch { return; }
      const w = waiters.get(msg.id);
      if (w) { waiters.delete(msg.id); w(msg); }
    }
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!upgraded) {
        const idx = buf.indexOf("\r\n\r\n");
        if (idx < 0) return;
        const head = buf.slice(0, idx).toString();
        if (!/101/.test(head)) { reject(new Error("握手失败: " + head.split("\r\n")[0])); return; }
        buf = buf.slice(idx + 4);
        upgraded = true;
        resolve({
          call(method, params) {
            const id = ++seq;
            return new Promise((res) => { waiters.set(id, res); send({ id, method, params: params || {} }); });
          },
          close() { try { sock.destroy(); } catch {} }
        });
      }
      while (buf.length >= 2) {
        const fin = buf[0] & 0x80, op = buf[0] & 0x0f;
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const payload = buf.slice(off, off + len);
        buf = buf.slice(off + len);
        if (op === 1) onMessage(payload.toString("utf8"));
        else if (op === 8) { sock.destroy(); return; }
      }
    });
    sock.on("error", reject);
  });
}
const cdp = await connect(wsUrl);
await cdp.call("Runtime.enable");

async function evalJs(expr) {
  const r = await cdp.call("Runtime.evaluate", {
    expression: `(function(){try{return JSON.stringify((function(){${expr}})())}catch(e){return JSON.stringify({__err:String(e)})}})()`,
    returnByValue: true,
    awaitPromise: true
  });
  const raw = r.result && r.result.result && r.result.result.value;
  if (raw === undefined || raw === null) return { __err: "no value", dump: JSON.stringify(r.result).slice(0, 600) };
  return JSON.parse(raw);
}

const expr = `(async () => {
  const hosts = ["cdn.jsdelivr.net","fastly.jsdelivr.net","testingcf.jsdelivr.net","gcore.jsdelivr.net"];
  const name = "GIme-v1.0.2-c3bbdb3c.apk";
  const res = [];
  for (const h of hosts) {
    const url = "https://" + h + "/gh/HagimeLab/GIme@main/release/" + name;
    try {
      const t0 = Date.now();
      const r = await fetch(url, { method: "GET", cache: "no-store" });
      const b = await r.blob();
      res.push({ host: h, status: r.status, bytes: b.size, ms: Date.now() - t0 });
    } catch (e) { res.push({ host: h, error: String((e && e.message) || e) }); }
  }
  return res;
})()`;
await cdp.call("Runtime.evaluate", { expression: "window.__cdn = null; (function(){ const p = " + expr + "; p.then(v => { window.__cdn = v; }, e => { window.__cdn = [{ error: String(e) }]; }); })()", returnByValue: true });
let out = null;
for (let i = 0; i < 120; i++) {
  await new Promise(r => setTimeout(r, 1000));
  const r = await cdp.call("Runtime.evaluate", { expression: "window.__cdn", returnByValue: true });
  const v = r.result && r.result.result && r.result.result.value;
  if (v) { out = v; break; }
}
console.log(JSON.stringify(out, null, 1));
cdp.close();
process.exit(0);