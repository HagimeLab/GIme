/**
 * 在真机上实测「真的能下到 APK 字节」的镜像，而不是只看域名通不通。
 * 用法：node tools/test-mirrors.mjs <ws-url>
 */
import net from "node:net";
import crypto from "node:crypto";

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

const cdp = await connect(process.argv[2]);
await cdp.call("Runtime.enable");
async function ev(expr, awaitP) {
  const r = await cdp.call("Runtime.evaluate", {
    expression: `(function(){try{return JSON.stringify((function(){${expr}})())}catch(e){return JSON.stringify({__err:String(e)})}})()`,
    returnByValue: true, awaitPromise: !!awaitP
  });
  const v = r.result?.result?.value;
  return v ? JSON.parse(v) : { __err: JSON.stringify(r.result) };
}

const TAG = "v1.0.2";
const FILE = "GIme-v1.0.2.apk";
const RAW = `https://github.com/HagimeLab/GIme/releases/download/${TAG}/${FILE}`;

const candidates = [
  { name: "GitHub 原始", url: RAW },
  { name: "ghproxy.com", url: `https://ghproxy.com/${RAW}` },
  { name: "mirror.ghproxy.com", url: `https://mirror.ghproxy.com/${RAW}` },
  { name: "ghfast.top", url: `https://ghfast.top/${RAW}` },
  { name: "gh-proxy.com", url: `https://gh-proxy.com/${RAW}` },
  { name: "ghp.ci", url: `https://ghp.ci/${RAW}` },
  { name: "gh.api.99988866.xyz", url: `https://gh.api.99988866.xyz/${RAW}` },
  { name: "hub.gitmirror.com", url: `https://hub.gitmirror.com/${RAW}` },
  { name: "github.moeyy.xyz", url: `https://github.moeyy.xyz/${RAW}` },
  { name: "ghproxy.net", url: `https://ghproxy.net/${RAW}` },
];

console.log("在真机上真实抓取前 64KB（能拿到字节才算可用）：\n");
for (const c of candidates) {
  const r = await ev(`
    return (function(){
      window.__r = null;
      var ctl = new AbortController();
      var timer = setTimeout(function(){ try{ctl.abort();}catch(e){} }, 20000);
      var t0 = Date.now();
      fetch(${JSON.stringify(c.url)}, {signal: ctl.signal})
        .then(function(res){
          if (!res.ok) { clearTimeout(timer); window.__r = {ok:false, status:res.status, ms:Date.now()-t0}; return; }
          var reader = res.body.getReader(), got = 0;
          function pump(){
            reader.read().then(function(x){
              if (x.done) { clearTimeout(timer); window.__r = {ok:true, bytes:got, ms:Date.now()-t0, status:res.status}; return; }
              got += x.value.length;
              if (got >= 65536) { clearTimeout(timer); try{ctl.abort();}catch(e){}
                window.__r = {ok:true, bytes:got, ms:Date.now()-t0, status:res.status}; return; }
              pump();
            }).catch(function(e){ clearTimeout(timer); window.__r = {ok:false, err:String(e).slice(0,50), bytes:got, ms:Date.now()-t0}; });
          }
          pump();
        })
        .catch(function(e){ clearTimeout(timer); window.__r = {ok:false, err:String(e).slice(0,50), ms:Date.now()-t0}; });
      return { started: true };
    })();`);
  // 轮询结果
  let got = null;
  for (let i = 0; i < 24; i++) {
    await new Promise((r) => setTimeout(r, 1000));
    const s = await ev(`return window.__r;`);
    if (s && !s.__err && (s.ok !== undefined)) { got = s; break; }
  }
  if (!got) got = { ok: false, err: "超时无响应" };
  const mark = got.ok ? "可用" : "不可用";
  const detail = got.ok
    ? `${got.bytes} bytes / ${got.ms}ms  (${Math.round(got.bytes / 1024 / (got.ms / 1000))} KB/s)`
    : (got.err || ("HTTP " + got.status));
  console.log(`  ${mark.padEnd(4)} ${c.name.padEnd(22)} ${detail}`);
}
cdp.close();
process.exit(0);
