/**
 * 在真机 WebView 里测一组下载源的连通性与吞吐。
 * 用法：node tools/probe-sources.mjs <ws-url> [<ws-url> ...]
 * 只用 fetch 拿响应头/少量字节，不下载整个文件。
 */
import net from "node:net";
import crypto from "node:crypto";

const urls = process.argv.slice(2);
if (!urls.length) { console.error("用法: node probe-sources.mjs <ws-url>"); process.exit(2); }

function connect(url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const key = crypto.randomBytes(16).toString("base64");
    const sock = net.connect(Number(u.port), u.hostname, () => {
      sock.write(`GET ${u.pathname} HTTP/1.1\r\nHost: ${u.host}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        `Sec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n\r\n`);
    });
    let buf = Buffer.alloc(0), up = false, seq = 0;
    const waiters = new Map();
    function send(obj) {
      const d = Buffer.from(JSON.stringify(obj));
      const m = crypto.randomBytes(4);
      let h;
      if (d.length < 126) h = Buffer.from([0x81, 0x80 | d.length]);
      else if (d.length < 65536) { h = Buffer.alloc(4); h[0] = 0x81; h[1] = 0x80 | 126; h.writeUInt16BE(d.length, 2); }
      else { h = Buffer.alloc(10); h[0] = 0x81; h[1] = 0x80 | 127; h.writeBigUInt64BE(BigInt(d.length), 2); }
      const x = Buffer.from(d);
      for (let i = 0; i < x.length; i++) x[i] ^= m[i % 4];
      sock.write(Buffer.concat([h, m, x]));
    }
    sock.on("data", (c) => {
      buf = Buffer.concat([buf, c]);
      if (!up) {
        const i = buf.indexOf("\r\n\r\n");
        if (i < 0) return;
        if (!/101/.test(buf.slice(0, i).toString())) { reject(new Error("握手失败")); return; }
        buf = buf.slice(i + 4); up = true;
        resolve({
          call(method, params) { const id = ++seq; return new Promise((r) => { waiters.set(id, r); send({ id, method, params: params || {} }); }); },
          close() { try { sock.destroy(); } catch {} }
        });
      }
      while (buf.length >= 2) {
        const op = buf[0] & 0x0f;
        let len = buf[1] & 0x7f, off = 2;
        if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
        else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
        if (buf.length < off + len) return;
        const p = buf.slice(off, off + len);
        buf = buf.slice(off + len);
        if (op === 1) { let m; try { m = JSON.parse(p.toString("utf8")); } catch { continue; } const w = waiters.get(m.id); if (w) { waiters.delete(m.id); w(m); } }
        else if (op === 8) { sock.destroy(); return; }
      }
    });
    sock.on("error", reject);
  });
}

const cdp = await connect(urls[0]);
await cdp.call("Runtime.enable");
async function ev(expr) {
  const r = await cdp.call("Runtime.evaluate", {
    expression: `(function(){try{return JSON.stringify((function(){${expr}})())}catch(e){return JSON.stringify({__err:String(e)})}})()`,
    returnByValue: true, awaitPromise: false
  });
  const v = r.result?.result?.value;
  return v ? JSON.parse(v) : { __err: JSON.stringify(r.result) };
}

const targets = [
  // 静态文件托管候选（只需能直接 GET 到 .apk）
  "https://gitee.com",
  "https://gitee.com/api/v5/repos",
  "https://cdn.jsdelivr.net",
  "https://fastly.jsdelivr.net",
  "https://raw.githubusercontent.com",
  "https://objects.githubusercontent.com",
  "https://github.com",
  "https://api.github.com",
  "https://www.baidu.com",
  "https://pan.baidu.com",
  "https://developer.huawei.com",
  "https://repo.huaweicloud.com",
  "https://mirrors.aliyun.com",
  "https://oss-cdn.aliyuncs.com",
];

await ev(`
  window.__probe = { done: false, out: [] };
  var list = ${JSON.stringify(targets)};
  var i = 0;
  function next(){
    if (i >= list.length) { window.__probe.done = true; return; }
    var u = list[i++], t0 = Date.now();
    var ctl = new AbortController();
    var timer = setTimeout(function(){ try{ctl.abort();}catch(e){} }, 8000);
    fetch(u, {method:"GET", mode:"no-cors", signal: ctl.signal, cache:"no-store"})
      .then(function(r){ clearTimeout(timer);
        window.__probe.out.push({url:u, ok:true, type:r.type, status:r.status, ms:Date.now()-t0});
        try{ r.body && r.body.cancel(); }catch(e){}
        next(); })
      .catch(function(e){ clearTimeout(timer);
        window.__probe.out.push({url:u, ok:false, err:String(e).slice(0,60), ms:Date.now()-t0});
        next(); });
  }
  next();
  return { started: true };`);

for (let i = 0; i < 30; i++) {
  await new Promise((r) => setTimeout(r, 1000));
  const s = await ev(`return { done: window.__probe.done, n: window.__probe.out.length };`);
  if (s.done) break;
}
const res = await ev(`return window.__probe.out;`);
console.log("在真机上逐个探测（no-cors 只能看通/不通与耗时）：\n");
for (const r of res) {
  const mark = r.ok ? (r.ms < 3000 ? "通" : "通但慢") : "不通";
  console.log(`  ${mark.padEnd(6)} ${String(r.ms).padStart(6)}ms  ${r.url}${r.ok ? "" : "  <- " + r.err}`);
}
cdp.close();
process.exit(0);
