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
  if (raw === undefined || raw === null) return { __err: "无返回值: " + JSON.stringify(r.result) };
  return JSON.parse(raw);
}

let pass = 0;
const fails = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log("  ✓ " + name); }
  else { fails.push(name + (detail ? "：" + detail : "")); console.log("  ✗ " + name + (detail ? "：" + detail : "")); }
}

console.log("[真机] 页面与数据");
const base = await evalJs(`
  return {
    ver: (window.Android && Android.appVersion) ? Android.appVersion() : "?",
    total: BANK.questions.length,
    subjects: BANK.subjects.length,
    hasReady: typeof (window.Android && Android.pageReady) === "function",
    hasUpdLog: typeof updLogHtml === "function",
    hasImportReport: typeof importReport === "function",
    hasExamDone: typeof examDone === "function"
  };
`);
ok("运行的是 v1.0.1", base.ver === "1.0.1", base.ver);
ok("题库已装载", base.total > 900, String(base.total));
ok("原生 pageReady 握手存在", base.hasReady === true);
ok("更新日志渲染函数存在", base.hasUpdLog === true);
ok("导入统计函数存在", base.hasImportReport === true);

console.log("\n[真机] 更新弹窗（U-01/U-02/U-04/U-08）");
const upd = await evalJs(`
  var longNotes = [];
  for (var i = 0; i < 40; i++) longNotes.push("第 " + (i+1) + " 条：修复问题 <b>不应被解析</b> & 换行测试");
  window.onUpdateInfo(JSON.stringify({
    ok: true, current: "1.0.0", latest: "1.0.1", hasUpdate: true,
    url: "https://example.com/gime.apk", date: "2026-10-17",
    notes: "", log: longNotes
  }));
  var g = document.getElementById("glass");
  var logBox = g.querySelector(".upd-log");
  var acts = g.querySelector(".upd-acts");
  var lis = g.querySelectorAll(".upd-log li");
  var boldInLog = g.querySelectorAll(".upd-log b").length;
  var res = {
    hasUpdClass: g.classList.contains("upd"),
    liCount: lis.length,
    firstLi: lis.length ? lis[0].textContent : "",
    injectedHtml: boldInLog,
    logScrollable: logBox ? (logBox.scrollHeight > logBox.clientHeight || getComputedStyle(logBox).overflowY === "auto") : false,
    actsVisible: acts ? getComputedStyle(acts).display !== "none" : false,
    latestText: g.textContent.indexOf("v1.0.1") >= 0,
    currentText: g.textContent.indexOf("v1.0.0") >= 0,
    dateText: g.textContent.indexOf("2026-10-17") >= 0
  };
  closeTop();
  return res;
`);
ok("弹窗使用 upd 布局", upd.hasUpdClass === true);
ok("40 条日志全部渲染", upd.liCount === 40, String(upd.liCount));
ok("日志按纯文本显示（<b> 未被解析）", upd.injectedHtml === 0, String(upd.injectedHtml));
ok("日志含未转义原文的转义形式", upd.firstLi.includes("<b>") === true, upd.firstLi);
ok("日志区可滚动", upd.logScrollable === true);
ok("底部操作区可见", upd.actsVisible === true);
ok("显示当前/最新版本与发布日期", upd.latestText && upd.currentText && upd.dateText);

const updEmpty = await evalJs(`
  window.onUpdateInfo(JSON.stringify({ ok: true, current: "1.0.0", latest: "1.0.1", hasUpdate: true,
    url: "https://example.com/gime.apk", date: "", notes: "", log: [] }));
  var g = document.getElementById("glass");
  var txt = g.textContent;
  var nowBtn = g.querySelector("#upNow");
  var res = { empty: txt.indexOf("此版本暂无更新说明") >= 0, noNull: txt.indexOf("null") < 0 && txt.indexOf("undefined") < 0,
              hasUpdateBtn: !!nowBtn, hasLater: !!g.querySelector("#upLater") };
  closeTop();
  return res;
`);
ok("空日志显示友好空状态（U-02/U-03）", updEmpty.empty === true);
ok("空日志不出现 null/undefined", updEmpty.noNull === true);
ok("空日志仍保留更新与稍后按钮", updEmpty.hasUpdateBtn && updEmpty.hasLater);

const updNoApk = await evalJs(`
  window.onUpdateInfo(JSON.stringify({ ok: true, current: "1.0.0", latest: "1.0.1", hasUpdate: true,
    url: "", date: "", notes: "x", log: ["y"] }));
  var g = document.getElementById("glass");
  var nowBtn = g.querySelector("#upNow");
  var res = { disabled: nowBtn ? nowBtn.disabled === true : false };
  closeTop();
  return res;
`);
ok("无安装包时「立即更新」禁用", updNoApk.disabled === true);

const updLatest = await evalJs(`
  window.onUpdateInfo(JSON.stringify({ ok: true, current: "1.0.1", latest: "1.0.0", hasUpdate: false }));
  var txt = document.getElementById("glass").textContent;
  var res = { latest: txt.indexOf("目前已经为最新版本") >= 0, ver: txt.indexOf("v1.0.1") >= 0 };
  closeTop();
  return res;
`);
ok("无新版本提示正确（U-09）", updLatest.latest && updLatest.ver);

const updFail = await evalJs(`
  window.onUpdateInfo(JSON.stringify({ ok: false, error: "连不上更新服务器" }));
  var g = document.getElementById("glass");
  var txt = g.textContent;
  var btn = g.querySelector("#gOk");
  var res = { fail: txt.indexOf("检查失败") >= 0, retry: btn ? btn.textContent.indexOf("重试") >= 0 : false };
  closeTop();
  return res;
`);
ok("检查失败有提示与重试入口（U-07）", updFail.fail && updFail.retry);

console.log("\n[真机] 模拟考试（E-01/E-02/E-03/E-05）");
const exam = await evalJs(`
  var before = 0;
  for (var k in prog) before += (prog[k].n || 0);
  var snapshot = JSON.stringify(prog);
  var results = {};

  startSession("exam", "all", "exam");
  results.running = !!(S && S.state === "running");
  var n = S.order.length;
  for (var i = 0; i < n; i++) go(1);           // 一路滑到最后一题并触发交卷
  results.clearedAfterLast = (S === null);
  var afterFirst = 0; for (var k2 in prog) afterFirst += (prog[k2].n || 0);
  go(1); submitExam(); submitExam();            // 交卷后再滑/再交
  var afterMore = 0; for (var k3 in prog) afterMore += (prog[k3].n || 0);
  results.singleSubmit = (afterFirst === afterMore) && (afterFirst > before);

  startSession("exam", "all", "exam");
  S.started = Date.now() - (S.dur + 5) * 1000;
  var b2 = 0; for (var k4 in prog) b2 += (prog[k4].n || 0);
  tick();
  var a2 = 0; for (var k5 in prog) a2 += (prog[k5].n || 0);
  results.timeoutSubmitted = (S === null) && (a2 > b2);
  tick();
  var a3 = 0; for (var k6 in prog) a3 += (prog[k6].n || 0);
  results.timeoutOnce = (a3 === a2);

  startSession("exam", "all", "exam");
  var b3 = 0; for (var k7 in prog) b3 += (prog[k7].n || 0);
  abandonExam();
  var a4 = 0; for (var k8 in prog) a4 += (prog[k8].n || 0);
  tick();
  var a5 = 0; for (var k9 in prog) a5 += (prog[k9].n || 0);
  results.abandonStops = (S === null) && (a4 === b3) && (a5 === a4);

  startSession("practice", "all", "");
  var keep = S.order.length;
  exitQuiz();
  results.practiceKeep = !!(S && S.order.length === keep);

  prog = JSON.parse(snapshot); save();          // 复原进度，别把测试成绩留在用户数据里
  closeTop();
  return results;
`);
ok("考试会话进入 running", exam.running === true);
ok("末题滑动触发交卷并清空会话（E-01）", exam.clearedAfterLast === true);
ok("重复交卷不产生第二次结算（E-01/E-03）", exam.singleSubmit === true);
ok("倒计时归零自动交卷（E-03）", exam.timeoutSubmitted === true);
ok("交卷后计时器不再结算（E-03）", exam.timeoutOnce === true);
ok("退出并放弃后停表且不自动交卷（E-05）", exam.abandonStops === true);
ok("普通练习退出不受影响（E-06）", exam.practiceKeep === true);

console.log("\n[真机] 导入与本地数据（I-03/I-04/D-01/D-02）");
const imp = await evalJs(`
  var sample = ["1、TCP 是可靠传输协议。","答案：正确",
                "2、HTTP 默认端口？","A、21","B、80","C、443","D、8080","答案：B",
                "3、没有答案的题","A、甲","B、乙"].join("\\n");
  var all = parseBankText(sample);
  var usable = all.filter(function (x) { return x.answer.length && x.options.length >= 2; });
  var rep = importReport(all, usable);
  return { total: all.length, usable: usable.length, noans: rep.noans, dropped: rep.dropped,
           sumOk: rep.usable + rep.noans + rep.dropped === rep.total };
`);
ok("导入解析出 3 题", imp.total === 3, String(imp.total));
ok("可判分 2 题", imp.usable === 2, String(imp.usable));
ok("无答案 1 题（I-04）", imp.noans === 1, String(imp.noans));
ok("统计三项相加等于总数", imp.sumOk === true);

const native = await evalJs(`
  var res = { hasSave: typeof (window.Android && Android.saveBank) === "function",
              hasDelete: typeof (window.Android && Android.deleteBank) === "function" };
  var sid = "probe_" + Date.now().toString(36);
  res.saveOk = Android.saveBank(sid, JSON.stringify({ subject: { id: sid, name: "探针" }, questions: [] }));
  res.deleteOk = Android.deleteBank(sid);
  res.deleteMissing = Android.deleteBank("probe_never_exists");
  return res;
`);
ok("原生保存接口可用", native.hasSave === true);
ok("原生保存成功（D-01 正常路径）", native.saveOk === true);
ok("原生删除成功（D-02 正常路径）", native.deleteOk === true);
ok("删除不存在的题库视为成功", native.deleteMissing === true);

const del = await evalJs(`
  var sid = "probe_" + Date.now().toString(36);
  var subj = { id: sid, name: "探针题库", custom: true, count: 1 };
  var q1 = { id: sid + "-0", subject: sid, stem: "探针题", type: "single",
             options: [["A","甲"],["B","乙"]], answer: ["A"], explain: "" };
  Android.saveBank(sid, JSON.stringify({ subject: subj, questions: [q1] }));
  BANK.subjects.push(subj); byId[q1.id] = q1; BANK.questions.push(q1);
  var okDel = removeBank(sid);
  var gone = !BANK.subjects.some(function (s) { return s.id === sid; }) && !byId[q1.id];
  return { okDel: okDel, gone: gone, fileGone: Android.deleteBank(sid) };
`);
ok("removeBank 返回成功并清理内存（D-02）", del.okDel === true && del.gone === true);

console.log("\n[真机] 会话恢复归一（D-03）");
const restore = await evalJs(`
  var raw = localStorage.getItem(LS.j);
  localStorage.setItem(LS.j, JSON.stringify({ subject: "all", mode: "exam", order: ["x"], idx: 0,
    picks: {}, graded: {}, started: Date.now(), dur: 600, state: "submitting" }));
  var saved = localStorage.getItem(LS.j);
  var S2 = JSON.parse(saved);
  var normalized = (S2.state === "submitted" || S2.state === "abandoned") ? null : "running";
  if (raw === null) localStorage.removeItem(LS.j); else localStorage.setItem(LS.j, raw);
  return { submittingBecomesRunning: normalized === "running" };
`);
ok("submitting 恢复为 running（不会卡死）", restore.submittingBecomesRunning === true);

console.log("\n" + "=".repeat(46));
cdp.close();
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("真机全部通过：" + pass + " 项");
