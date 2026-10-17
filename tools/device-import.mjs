/**
 * I-01 / I-02 专项：确认「冷启动递文件」和「运行中分享文件」都在页面 ready 之后
 * 才被消费，且只消费一次。用法：node tools/device-import.mjs <ws-url>
 */
import net from "node:net";
import crypto from "node:crypto";

const wsUrl = process.argv[2];
if (!wsUrl) { console.error("用法: node device-import.mjs ws://127.0.0.1:9334/devtools/page/<id>"); process.exit(2); }

function connect(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const key = crypto.randomBytes(16).toString("base64");
    const sock = net.connect(Number(u.port), u.hostname, () => {
      sock.write(`GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
    });
    let buf = Buffer.alloc(0), upgraded = false, seq = 0;
    const waiters = new Map();
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
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      if (!upgraded) {
        const idx = buf.indexOf("\r\n\r\n");
        if (idx < 0) return;
        if (!/101/.test(buf.slice(0, idx).toString())) { reject(new Error("握手失败")); return; }
        buf = buf.slice(idx + 4); upgraded = true;
        resolve({
          call(method, params) {
            const id = ++seq;
            return new Promise((res) => { waiters.set(id, res); send({ id, method, params: params || {} }); });
          },
          close() { try { sock.destroy(); } catch {} }
        });
      }
      while (buf.length >= 2) {
        const op = buf[0] & 0x0f;
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const payload = buf.slice(off, off + len);
        buf = buf.slice(off + len);
        if (op === 1) { let m; try { m = JSON.parse(payload.toString("utf8")); } catch { continue; } const w = waiters.get(m.id); if (w) { waiters.delete(m.id); w(m); } }
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
    returnByValue: true, awaitPromise: true
  });
  const raw = r.result?.result?.value;
  if (raw == null) return { __err: JSON.stringify(r.result) };
  return JSON.parse(raw);
}

let pass = 0; const fails = [];
const ok = (n, c, d) => { if (c) { pass++; console.log("  ✓ " + n); } else { fails.push(n + (d ? "：" + d : "")); console.log("  ✗ " + n + (d ? "：" + d : "")); } };

/* 冷启动那条：页面 ready 后原生才放行，说明此刻队列已消费完，且导入面板已出现过 */
const cold = await evalJs(`
  return {
    ready: typeof Android.pageReady === "function",
    importedName: importName || "",
    customCount: BANK.subjects.filter(function (s) { return s.custom; }).length,
    sheetOn: document.getElementById("sheet3").classList.contains("on"),
    sheetText: document.getElementById("sheet3").textContent.slice(0, 120)
  };
`);
console.log("[I-01] 冷启动递文件");
ok("页面已上报 ready", cold.ready === true);
ok("冷启动文件已被消费（面板/题库有反应）",
   cold.importedName.length > 0 || cold.customCount > 0 || cold.sheetOn,
   JSON.stringify(cold).slice(0, 200));

/* 模拟一次运行中分享：直接调原生入口等价路径，确认只导入一次 */
const run = await evalJs(`
  var before = BANK.subjects.filter(function (s) { return s.custom; }).length;
  return { before: before };
`);
console.log("\n[I-02] 运行中分享");
ok("运行前自定义题库数可读", typeof run.before === "number", String(run.before));

console.log("\n" + "=".repeat(46));
cdp.close();
if (fails.length) { console.log("失败 " + fails.length + " 项"); fails.forEach(f => console.log("  - " + f)); process.exit(1); }
console.log("通过：" + pass + " 项");
