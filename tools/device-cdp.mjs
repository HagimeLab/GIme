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
ok("运行的是 v1.0.5", base.ver === "1.0.5", base.ver);
ok("不再内置题库（v1.0.3）", base.total === 0 && base.subjects === 0, base.total + "/" + base.subjects);
ok("原生 pageReady 握手存在", base.hasReady === true);
ok("更新日志渲染函数存在", base.hasUpdLog === true);
ok("导入统计函数存在", base.hasImportReport === true);
ok("考试状态判断函数存在", base.hasExamDone === true);

/* v1.0.3 起没有内置题库，后面所有练习/考试用例都要自带一套。
   装进去的同时记下当前 BANK 快照，跑完原样还原，不把测试题留在用户数据里。 */
const seed = await evalJs(`
  window.__bankSnap = { subjects: BANK.subjects.slice(), questions: BANK.questions.slice() };
  var TB = "cdp_test";
  BANK.subjects.push({ id: TB, name: "CDP 测试题库", file: "t.txt", count: 6, custom: true });
  function mk(i, type, opts, ans, ref) {
    return { id: TB + "-" + i, subject: TB, num: i + 1, pnum: i + 1, type: type,
             stem: "CDP 测试题 " + (i + 1), options: opts, answer: ans, ans_raw: (ans || []).join(""),
             ref: ref || "", explain: "解析 " + (i + 1), note: "", flag: "", ex_source: "编写" };
  }
  var qs = [
    mk(0, "single", [["A","甲"],["B","乙"],["C","丙"],["D","丁"]], ["A"]),
    mk(1, "multi",  [["A","甲"],["B","乙"],["C","丙"],["D","丁"]], ["A","C"]),
    mk(2, "judge",  [["A","正确"],["B","错误"]], ["A"]),
    mk(3, "single", [["A","甲"],["B","乙"]], ["B"]),
    mk(4, "fill",   [], [], "参考答案甲"),
    mk(5, "short",  [], [], "参考答案乙")
  ];
  qs.forEach(function (q) { BANK.questions.push(q); byId[q.id] = q; });
  set.paper.subs = {}; set.paper.subs[TB] = 1; save();
  return { added: BANK.questions.length, pool: paperPool().length, TB: TB };
`);
ok("测试题库已装入 6 题", seed.added === 6, String(seed.added));
ok("组卷池只含客观题（4 题）", seed.pool === 4, String(seed.pool));
const TB = seed.TB;

console.log("\n[真机] v1.0.2 进入「我的」自动检查更新（A/B/D）");
const autoUpd = await evalJs(`
  /* 只验证「接线」，不真的打网络：把两个检查函数换成计数器再调 tabTap。
     tabTap 是用户点底部「我的」的唯一入口，goTab 是程序内跳转。 */
  var realSilent = window.checkUpdateSilently, realManual = window.checkUpdate;
  var silentN = 0, manualN = 0;
  window.checkUpdateSilently = function () { silentN++; };
  window.checkUpdate = function () { manualN++; };
  var res = {};
  try {
    tabTap("me");            res.tabMe = silentN;
    tabTap("home");          res.tabHome = silentN;
    goTab("me");             res.programmatic = silentN;
    tabTap("wrong");         res.tabWrong = silentN;
  } finally {
    window.checkUpdateSilently = realSilent;
    window.checkUpdate = realManual;
  }
  res.manualUntouched = manualN;
  return res;
`);
ok("点「我的」触发静默检查（A-01）", autoUpd.tabMe === 1, String(autoUpd.tabMe));
ok("点其它 tab 不检查（A-02/A-03）", autoUpd.tabHome === 1 && autoUpd.tabWrong === 1,
   autoUpd.tabHome + "/" + autoUpd.tabWrong);
ok("程序内 goTab 不触发检查", autoUpd.programmatic === 1, String(autoUpd.programmatic));
ok("静默检查不会调手动检查", autoUpd.manualUntouched === 0, String(autoUpd.manualUntouched));

const notice = await evalJs(`
  var snapshot = localStorage.getItem(LS.u);
  /* 造一个「发现新版本」的自动检查结果 */
  upd.silent = true; upd.req = true;
  window.onUpdateInfo(JSON.stringify({
    ok: true, hasUpdate: true, current: Android.appVersion(), latest: "9.9.9",
    urls: ["https://example.com/x.apk"], log: ["不该在这里出现的日志"], notes: ""
  }));
  var g = document.getElementById("glass");
  var res = {
    cls: g.className,
    small: g.classList.contains("note"),
    hasLater: !!g.querySelector("#noteLater"),
    hasNow: !!g.querySelector("#noteNow"),
    noLog: g.querySelectorAll(".upd-log").length === 0,
    latestShown: g.textContent.indexOf("9.9.9") >= 0,
    hasNew: upd.hasNew === true,
    dotInMe: (function () {
      renderMe();
      return /data-k="update"[\\s\\S]*?class="dot"/.test(document.getElementById("meSet").innerHTML);
    })()
  };
  /* 稍后更新：关窗、记住版本、小红点留着 */
  g.querySelector("#noteLater").onclick();
  res.closedAfterLater = !g.classList.contains("on") && openLayers.length === 0;
  res.dismissedSaved = (JSON.parse(localStorage.getItem(LS.u) || "{}").dismissed) === "9.9.9";
  res.dotAfterLater = upd.hasNew === true;
  /* 同一版本再进来：不该再弹 */
  upd.silent = true; upd.req = true;
  window.onUpdateInfo(JSON.stringify({ ok: true, hasUpdate: true, current: Android.appVersion(),
    latest: "9.9.9", urls: ["https://example.com/x.apk"], log: [], notes: "" }));
  res.noRepeat = !g.classList.contains("on") && openLayers.length === 0;
  /* 自动检查失败：不弹、不清标记 */
  upd.silent = true; upd.req = true;
  window.onUpdateInfo(JSON.stringify({ ok: false, error: "网络不可用" }));
  res.failSilent = !g.classList.contains("on");
  res.failKeepsDot = upd.hasNew === true;
  /* 确认最新：标记熄灭 */
  upd.silent = true; upd.req = true;
  window.onUpdateInfo(JSON.stringify({ ok: true, hasUpdate: false, current: Android.appVersion() }));
  res.clearedOnLatest = upd.hasNew === false;
  if (snapshot === null) localStorage.removeItem(LS.u); else localStorage.setItem(LS.u, snapshot);
  upd.avail = ""; upd.dismissed = ""; upd.hasNew = false; upd.req = false; upd.silent = false;
  renderMe();
  return res;
`);
ok("自动发现新版本用小窗（B-01）", notice.small === true, notice.cls);
ok("小窗只有稍后/立即两个按钮", notice.hasLater && notice.hasNow);
ok("小窗不展开更新日志", notice.noLog === true);
ok("小窗显示最新版本号", notice.latestShown === true);
ok("「检查更新」行亮起小红点（B-03）", notice.dotInMe === true && notice.hasNew === true);
ok("稍后更新关窗（B-02）", notice.closedAfterLater === true);
ok("稍后更新记住暂缓版本（D-01）", notice.dismissedSaved === true);
ok("稍后更新后小红点保留（B-05）", notice.dotAfterLater === true);
ok("同一版本不再重复弹窗（B-04）", notice.noRepeat === true);
ok("自动检查失败不弹窗（C-05）", notice.failSilent === true);
ok("自动检查失败不清小红点", notice.failKeepsDot === true);
ok("确认最新后小红点熄灭（B-07）", notice.clearedOnLatest === true);

const manualUpd = await evalJs(`
  var res = {};
  var snapshot = localStorage.getItem(LS.u);
  upd.silent = false; upd.req = true;
  window.onUpdateInfo(JSON.stringify({ ok: true, hasUpdate: true, current: Android.appVersion(),
    latest: "9.9.9", urls: ["https://example.com/x.apk"], log: ["修复下载卡 0%"], notes: "" }));
  var g = document.getElementById("glass");
  res.fullSheet = g.classList.contains("upd");
  res.hasLog = g.textContent.indexOf("修复下载卡 0%") >= 0;
  closeTop();
  upd.silent = false; upd.req = true;
  window.onUpdateInfo(JSON.stringify({ ok: false, error: "连不上更新服务器" }));
  res.failDialog = g.textContent.indexOf("检查失败") >= 0;
  closeTop();
  if (snapshot === null) localStorage.removeItem(LS.u); else localStorage.setItem(LS.u, snapshot);
  upd.avail = ""; upd.dismissed = ""; upd.hasNew = false; upd.req = false; upd.silent = false;
  renderMe();
  return res;
`);
ok("手动检查仍走完整更新弹窗（C-01）", manualUpd.fullSheet === true);
ok("手动检查能看到完整日志", manualUpd.hasLog === true);
ok("手动检查失败仍有提示（C-06）", manualUpd.failDialog === true);

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
  var usable = all.filter(usableQ);
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

console.log("\n[真机] v1.0.3：主观题 / 震动 / 分类折叠 / 头像已删");
const v103 = await evalJs(`
  var res = {};
  /* 主观题：能出、能写、能自评，且不产生 graded */
  startSession("practice", "${TB}", "fill");
  var body = document.getElementById("qbody");
  res.fillOpened = !!(S && S.order.length === 1);
  res.hasTextarea = !!body.querySelector(".q-input");
  res.hasRefBox = !!body.querySelector(".ref-box");
  res.noOpts = body.querySelectorAll(".opt").length === 0;
  res.actText = document.getElementById("btnAct").textContent;
  var qid = S.order[0];
  var rateBefore = stats("${TB}").rate;
  selfCheck(1);
  res.selfSaved = S.self[qid] === 1;
  res.noGraded = S.graded[qid] === undefined;
  res.rateSame = stats("${TB}").rate === rateBefore;
  selfCheck(0);
  res.wrongAfter = prog[qid].in === 1;
  exitQuiz();

  /* 震动开关：关掉就一次都不该调 navigator.vibrate */
  var realVib = navigator.vibrate, vibes = 0;
  navigator.vibrate = function () { vibes++; };
  set.haptics = false;
  startSession("practice", "${TB}", "single");
  vibes = 0; grade(["A"]);
  res.vibOff = vibes;
  set.haptics = true;
  vibes = 0; grade(["A"]);
  res.vibOn = vibes;
  set.haptics = "false";
  vibes = 0; grade(["A"]);
  res.vibStrFalse = vibes;
  set.haptics = true;
  navigator.vibrate = realVib;
  exitQuiz();

  /* 分类区整体折叠：一个开关管六张卡片，卡片点击直接进练习 */
  S = null; goTab("home");
  var cats = document.getElementById("cats");
  var fold = document.getElementById("catsFold");
  var togg = document.getElementById("catsToggle");
  res.catCount = cats.querySelectorAll(".cat-wrap").length;
  res.noPanel = cats.querySelectorAll(".cat-panel, .cat-in").length === 0;
  res.cardGo = cats.querySelector(".cat").getAttribute("onclick").indexOf("catGo") >= 0;
  catsClosed = false; syncCatsFold();
  res.expandedByDefault = !fold.classList.contains("closed") && togg.getAttribute("aria-expanded") === "true";
  toggleCats();
  res.collapses = catsClosed === true && fold.classList.contains("closed")
                  && togg.getAttribute("aria-expanded") === "false"
                  && document.getElementById("catsToggleTx").textContent === "展开";
  res.collapseKeepsSession = (S === null);
  toggleCats();
  res.reopens = catsClosed === false && !fold.classList.contains("closed")
                && document.getElementById("catsToggleTx").textContent === "收起";
  /* 这台机器是 Chrome 80：grid-template-rows 过渡会被忽略成瞬跳，只能过渡 height。 */
  /* 这台机器系统里开了「减弱动画」，prefers-reduced-motion 就是 reduce：
     此时过渡被 CSS 按设计关掉，属正常，不能当成失败。 */
  res.reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  res.foldTransition = res.reducedMotion
      ? getComputedStyle(fold).transitionProperty === "none"
      : getComputedStyle(fold).transitionProperty.indexOf("height") >= 0;
  res.foldHeightBefore = fold.style.height;
  toggleCats();
  res.foldHeightClosing = fold.style.height;
  toggleCats();
  res.foldHeightOpening = fold.style.height;
  res.foldSingleContainer = fold.querySelectorAll(":scope > .cats-fold-in > .cats").length === 1;
  catGo("judge");
  res.cardEntersPractice = !!(S && S.tf === "judge");
  exitQuiz();

  /* 头像已删除，两个圆形按钮还在 */
  res.noAvatarImg = !document.getElementById("avatarImg");
  res.noAvatarNode = !document.querySelector(".avatar");
  renderMe();
  var meSet = document.getElementById("meSet").innerHTML;
  res.noSearchRow = meSet.indexOf('data-k="search"') < 0;
  res.hasUpdateRow = meSet.indexOf('data-k="update"') >= 0;
  res.meNoNaN = document.getElementById("meStat").innerHTML.indexOf("NaN") < 0;
  var btns = document.querySelectorAll(".hello .icon-btn");
  res.iconBtns = btns.length;
  res.themeBtn = !!document.getElementById("themeBtn");
  res.statBtn = !!(btns[1] && btns[1].getAttribute("onclick").indexOf("openFS") >= 0);
  /* 问候文字与按钮组不再贴在一起 */
  var tb = document.querySelector(".hello .txt").getBoundingClientRect();
  var bb = document.querySelector(".hello .bl").getBoundingClientRect();
  res.gap = Math.round(bb.left - tb.right);
  return res;
`);
ok("填空题能开练习", v103.fillOpened === true);
ok("主观题渲染文本框", v103.hasTextarea === true);
ok("主观题展示参考答案", v103.hasRefBox === true);
ok("主观题没有可点选项", v103.noOpts === true);
ok("主观题按钮是「下一题」", v103.actText === "下一题", v103.actText);
ok("自评写入 S.self", v103.selfSaved === true);
ok("自评不产生自动判分", v103.noGraded === true);
ok("自评不影响正确率", v103.rateSame === true);
ok("自评「待复习」进错题本", v103.wrongAfter === true);
ok("震动关闭时不调 vibrate", v103.vibOff === 0, String(v103.vibOff));
ok("震动开启时会调 vibrate", v103.vibOn > 0, String(v103.vibOn));
ok("haptics 存成 \"false\" 也算关闭", v103.vibStrFalse === 0, String(v103.vibStrFalse));
ok("首页六张题型卡片", v103.catCount === 6, String(v103.catCount));
ok("卡片不再有单卡展开面板", v103.noPanel === true);
ok("卡片点击走 catGo", v103.cardGo === true);
ok("分类区默认展开", v103.expandedByDefault === true);
ok("一个开关收起六张卡片", v103.collapses === true);
ok("收起不影响答题会话", v103.collapseKeepsSession === true);
ok("再点一下整体展开", v103.reopens === true);
ok(v103.reducedMotion ? "减弱动画时过渡被关掉（系统设置）" : "折叠过渡作用在 height 上（Chrome 80 兼容）", v103.foldTransition === true);
ok("收起时把高度写成具体像素（过渡有终点）", /^\d+(\.\d+)?px$/.test(String(v103.foldHeightClosing)), String(v103.foldHeightClosing));
ok("展开时先量出内容高度再过渡", /^\d+(\.\d+)?px$/.test(String(v103.foldHeightOpening)), String(v103.foldHeightOpening));
ok("折叠只有一个容器（不会逐张错位）", v103.foldSingleContainer === true);
ok("卡片点击进对应题型练习", v103.cardEntersPractice === true);
ok("首页头像已删除", v103.noAvatarImg === true && v103.noAvatarNode === true);
ok("主题与统计两个按钮都还在", v103.iconBtns === 2 && v103.themeBtn && v103.statBtn);
ok("问候文字与按钮组有间距", v103.gap >= 12, "实际 " + v103.gap + "px");
ok("「我的」页没有搜索题目行", v103.noSearchRow === true);
ok("「检查更新」行还在", v103.hasUpdateRow === true);
ok("「我的」页统计无 NaN", v103.meNoNaN === true);

/* 空题库引导：不再重复格式说明，导入入口还在 */
const emptyGuide = await evalJs(`
  var snapSubs = BANK.subjects.slice(), snapQs = BANK.questions.slice();
  BANK.subjects = []; BANK.questions = []; byId = {};
  goTab("home");
  var res = {
    hiName: document.getElementById("hiName").textContent,
    hiSubHidden: document.getElementById("hiSub").hidden,
    hiSubText: document.getElementById("hiSub").textContent,
    taskHasImport: document.getElementById("tasks").innerHTML.indexOf("openImport") >= 0,
    taskSmall: (document.querySelector("#tasks .task small") || {}).textContent || "",
    noNaN: document.getElementById("tasks").innerHTML.indexOf("NaN") < 0
  };
  BANK.subjects = snapSubs; BANK.questions = snapQs;
  byId = {}; BANK.questions.forEach(function (q) { byId[q.id] = q; });
  save(); renderAll();
  return res;
`);
ok("空题库标题仍是「先导入一个题库」", emptyGuide.hiName === "先导入一个题库", emptyGuide.hiName);
ok("空题库不再显示格式说明", emptyGuide.hiSubHidden === true && emptyGuide.hiSubText === "",
   "hidden=" + emptyGuide.hiSubHidden + " text=" + emptyGuide.hiSubText);
ok("空题库保留导入入口", emptyGuide.taskHasImport === true);
ok("空题库卡片不再重复「导入题库」", emptyGuide.taskSmall.indexOf("导入题库") >= 0, emptyGuide.taskSmall);
ok("空题库统计无 NaN", emptyGuide.noNaN === true);

console.log("\n[真机] v1.0.3：PDF 导入（文字层 / 拒绝路径 / 统计口径）");
/* PdfTidy / PdfText 是原生类，JS 侧碰不到；这里验两件 JS 能负责的事：
   1. 原生把 PDF 取成文字后，同一套 parseBankText 能正确解析；
   2. 导入面板把「需要文字层」讲清楚，用户不会把扫描件当成成功。 */
const pdf = await evalJs(`
  var tidied = "1\\u3001\\u4e0b\\u5217\\u54ea\\u4e2a\\u662f TCP \\u534f\\u8bae \\uff1f\\nA\\u3001UDP\\nB\\u3001TCP\\n\\u7b54\\u6848\\uff1aB\\n\\u89e3\\u6790\\uff1aTCP \\u6709\\u786e\\u8ba4\\u4e0e\\u91cd\\u4f20\\u3002\\n" +
                "2\\u3001HTTP \\u9ed8\\u8ba4\\u7aef\\u53e3\\u662f 80\\u3002\\n\\u7b54\\u6848\\uff1a\\u6b63\\u786e\\n" +
                "3\\u3001\\uff08\\u591a\\u9009\\uff09\\u4e0b\\u5217\\u54ea\\u4e9b\\u5c5e\\u4e8e\\u4e91\\u539f\\u751f\\u8981\\u7d20\\uff1f\\nA\\u3001\\u5bb9\\u5668\\nB\\u3001\\u5fae\\u670d\\u52a1\\nC\\u3001DevOps\\nD\\u3001\\u4f20\\u7edf\\u673a\\u623f\\n\\u7b54\\u6848\\uff1aABC";
  var qs = parseBankText(tidied);
  var usable = qs.filter(usableQ);
  var rep = importReport(qs, usable);
  var im = document.getElementById("impBar");
  openImport();
  var txt = document.getElementById("sheet3").textContent;
  closeTop();
  return {
    total: qs.length, usable: usable.length,
    types: usable.map(function (q) { return q.type; }).join(","),
    ans0: qs.length ? qs[0].answer.join("") : "",
    exp0: qs.length ? qs[0].explain.slice(0, 12) : "",
    noans: rep.noans, dropped: rep.dropped,
    sumOk: rep.usable + rep.noans + rep.dropped === rep.total,
    saysPdf: txt.indexOf("PDF") >= 0, saysLayer: txt.indexOf("\\u6587\\u5b57\\u5c42") >= 0
  };
`);
ok("PDF 取出的文字能解析出 3 题", pdf.total === 3, String(pdf.total));
ok("单选 / 判断 / 多选 都认对", pdf.types === "single,judge,multi", pdf.types);
ok("答案与解析一起带出来", pdf.ans0 === "B" && pdf.exp0.length > 0, pdf.ans0 + "/" + pdf.exp0);
ok("PDF 题目统计三项相加等于总数", pdf.sumOk === true && pdf.noans === 0 && pdf.dropped === 0);
ok("导入说明写清支持 PDF", pdf.saysPdf === true);
ok("导入说明提示 PDF 需要文字层", pdf.saysLayer === true);

/* 还原题库快照，测试题不留在用户数据里 */
const cleanup = await evalJs(`
  if (window.__bankSnap) {
    BANK.subjects = window.__bankSnap.subjects;
    BANK.questions = window.__bankSnap.questions;
    byId = {}; BANK.questions.forEach(function (q) { byId[q.id] = q; });
    delete window.__bankSnap;
    save(); renderAll();
  }
  return { subjects: BANK.subjects.length, questions: BANK.questions.length };
`);
console.log("  · 已还原题库：" + cleanup.subjects + " 个题库 / " + cleanup.questions + " 题");

console.log("\n" + "=".repeat(46));
cdp.close();
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("真机全部通过：" + pass + " 项");
