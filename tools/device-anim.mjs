/**
 * 真机验收 v1.0.3 的四项动效。
 * 用法：node tools/device-anim.mjs <ws-url>
 *
 * 四项：
 *   1 底部 Tab 的胶囊流动背景（跟随点击、弹簧回弹、只用主题令牌不引入彩色）
 *   2 答题页选项按压弹性回弹（按下 0.96、松开过冲 1.02 再回 1）
 *   3 Tab 切页交错入场（旧页淡出 → 新页淡入 + 卡片依次滑入）
 *   4 题库页题型筛选的毛毛虫下划线（中间帧同时盖住新旧两枚）
 *
 * 每项都在浅色与深色下各取一次色值，确认没有写死颜色。
 * 采样靠页内 requestAnimationFrame，返回 [t, ...] 时间序列，断言看轨迹而不是终值。
 */
import net from "node:net";
import crypto from "node:crypto";

const wsUrl = process.argv[2];
if (!wsUrl) { console.error("用法: node device-anim.mjs ws://127.0.0.1:9333/devtools/page/<id>"); process.exit(2); }

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
        const op = buf[0] & 0x0f;
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
  const wrapped =
    `(async function(){try{var v=await (async function(){${expr}})();return JSON.stringify(v===undefined?null:v);}` +
    `catch(e){return JSON.stringify({__err:String(e&&e.stack||e)})}})()`;
  const r = await cdp.call("Runtime.evaluate", { expression: wrapped, returnByValue: true, awaitPromise: true });
  const raw = r.result && r.result.result && r.result.result.value;
  if (raw === undefined || raw === null) return { __err: "无返回值: " + JSON.stringify(r.result).slice(0, 400) };
  return JSON.parse(raw);
}

let pass = 0;
const fails = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log("  ✓ " + name); }
  else { fails.push(name + (detail ? "：" + detail : "")); console.log("  ✗ " + name + (detail ? "：" + detail : "")); }
}
const r2 = (n) => Math.round(n * 100) / 100;

/* ---------------------------------------------------------------- 环境 */
console.log("[真机] 动效环境");
const env = await evalJs(`
  return {
    chrome: (navigator.userAgent.match(/Chrome\\/[\\d.]+/) || [])[0] || "?",
    animate: typeof Element.prototype.animate,
    pointerEvent: typeof PointerEvent,
    raf: typeof requestAnimationFrame,
    reduced: matchMedia("(prefers-reduced-motion: reduce)").matches,
    w: innerWidth, h: innerHeight, dpr: devicePixelRatio
  };
`);
console.log("  · " + env.chrome + " · " + env.w + "x" + env.h + " @x" + env.dpr + " · reduced-motion=" + env.reduced);
ok("WebView 支持 element.animate（动效可用）", env.animate === "function", env.animate);
ok("WebView 支持 PointerEvent（按压事件可用）", env.pointerEvent === "function", env.pointerEvent);
ok("WebView 支持 requestAnimationFrame（采样可用）", env.raf === "function", env.raf);

/* 动效与 reduced-motion 无关（WAAPI 不受那条媒体查询影响），但系统关了动画时
   Chrome 仍会跑 WAAPI，所以这里只做记录，不做前置假设。 */

/* ------------------------------------------------------- 造测试题库 */
console.log("\n[真机] 1. 底部 Tab 胶囊流动");
const seed = await evalJs(`
  window.__animSnap = { subjects: BANK.subjects.slice(), questions: BANK.questions.slice(), set: JSON.parse(JSON.stringify(set)) };
  var TB = "anim_test";
  /* 三张卡片：交错入场要有「后卡比前卡更晚亮」的观测对象，一张卡测不出来 */
  BANK.subjects = [
    { id: TB, name: "动效测试题库", file: "a.txt", count: 8, custom: true },
    { id: TB + "_2", name: "动效测试题库二", file: "b.txt", count: 4, custom: true },
    { id: TB + "_3", name: "动效测试题库三", file: "c.txt", count: 4, custom: true }
  ];
  var O4 = [["A","甲"],["B","乙"],["C","丙"],["D","丁"]];
  function q(sub, i, type, opts, ans) {
    return { id: sub + "-" + i, subject: sub, num: i + 1, pnum: i + 1, type: type,
             stem: "动效测试题 " + (i + 1), options: opts, answer: ans, ans_raw: ans.join(""),
             ref: "", explain: "解析", note: "", flag: "", ex_source: "编写" };
  }
  BANK.questions = [
    q(TB, 0, "single", O4, ["A"]), q(TB, 1, "multi", O4, ["A","C"]),
    q(TB, 2, "judge", [["A","正确"],["B","错误"]], ["A"]), q(TB, 3, "single", O4, ["B"]),
    q(TB, 4, "single", O4, ["C"]), q(TB, 5, "single", O4, ["D"]),
    q(TB, 6, "single", O4, ["A"]), q(TB, 7, "single", O4, ["B"]),
    q(TB + "_2", 0, "single", O4, ["A"]), q(TB + "_2", 1, "single", O4, ["B"]),
    q(TB + "_2", 2, "single", O4, ["C"]), q(TB + "_2", 3, "single", O4, ["D"]),
    q(TB + "_3", 0, "single", O4, ["A"]), q(TB + "_3", 1, "single", O4, ["B"]),
    q(TB + "_3", 2, "single", O4, ["C"]), q(TB + "_3", 3, "single", O4, ["D"])
  ];
  byId = {}; BANK.questions.forEach(function (q) { byId[q.id] = q; });
  set.theme = "light"; applyTheme();
  goTab("home");
  save(); renderAll();
  var pill = document.getElementById("tabPill");
  var tabs = document.getElementById("tabs");
  var cs = getComputedStyle(pill);
  return { pillBg: cs.backgroundColor, pillShadow: cs.boxShadow, tabsBg: getComputedStyle(tabs).backgroundColor,
           hasPill: !!pill, zPill: cs.zIndex, zTab: getComputedStyle(document.querySelector("#tabs .tab")).zIndex };
`);
ok("胶囊元素存在", seed.hasPill === true);
ok("胶囊压在按钮之下（z-index 0 < 1）", seed.zPill === "0" && seed.zTab === "1", seed.zPill + "/" + seed.zTab);
ok("胶囊底色沿用主题令牌（与 tabbar 同色，不引入彩色）",
   seed.pillBg === seed.tabsBg, seed.pillBg + " vs " + seed.tabsBg);
ok("胶囊用凹槽阴影而不是高亮块", /inset/.test(seed.pillShadow) === true, seed.pillShadow.slice(0, 60));

/* 采样：点「题库」后逐帧记胶囊的 left / width / top */
const pillTrace = await evalJs(`
  var pill = document.getElementById("tabPill");
  var target = document.querySelectorAll("#tabs .tab")[1];
  var before = { x: parseFloat(pill.style.left) || 0, w: parseFloat(pill.style.width) || 0 };
  var samples = [];
  tabTap("bank");
  await new Promise(function (res) {
    var t0 = performance.now();
    function tick() {
      var r = pill.getBoundingClientRect();
      samples.push([Math.round(performance.now() - t0), Math.round(r.left * 10) / 10, Math.round(r.width * 10) / 10]);
      if (performance.now() - t0 < 560) requestAnimationFrame(tick); else res();
    }
    requestAnimationFrame(tick);
  });
  var endRect = pill.getBoundingClientRect();
  return {
    before: before, samples: samples,
    end: { x: Math.round(endRect.left * 10) / 10, w: Math.round(endRect.width * 10) / 10 },
    targetX: Math.round(target.getBoundingClientRect().left * 10) / 10,
    targetW: Math.round(target.getBoundingClientRect().width * 10) / 10
  };
`);
const xs = pillTrace.samples.map((s) => s[1]);
const x0 = pillTrace.before.x + pillTrace.samples[0][1] - pillTrace.samples[0][1]; /* 起点用首帧 */
const firstX = xs[0], lastX = xs[xs.length - 1];
const moving = pillTrace.samples.filter((s) => Math.abs(s[1] - firstX) > 0.6 && Math.abs(s[1] - lastX) > 0.6).length;
console.log("  · 采样 " + pillTrace.samples.length + " 帧，left " + r2(firstX) + " → " + r2(lastX) +
            "，中间帧 " + moving + " 个");
ok("胶囊落到目标 Tab 上", Math.abs(pillTrace.end.x - pillTrace.targetX) <= 1.5,
   r2(pillTrace.end.x) + " vs " + r2(pillTrace.targetX));
ok("胶囊宽度贴合目标 Tab", Math.abs(pillTrace.end.w - pillTrace.targetW) <= 1.5,
   r2(pillTrace.end.w) + " vs " + r2(pillTrace.targetW));
ok("胶囊是「流动」不是瞬移（≥3 个中间帧）", moving >= 3, String(moving));
const overshoot = Math.max.apply(null, xs.map((x) => (lastX > firstX ? x - lastX : lastX - x)));
ok("胶囊有弹簧过冲（越过终点再回弹）", overshoot > 0.5, "overshoot=" + r2(overshoot) + "px");

/* 深色下再取一次色，确认不是写死颜色 */
const darkPill = await evalJs(`
  set.theme = "dark"; applyTheme();
  var pill = document.getElementById("tabPill"), tabs = document.getElementById("tabs");
  var a = getComputedStyle(pill).backgroundColor, b = getComputedStyle(tabs).backgroundColor;
  set.theme = "light"; applyTheme();
  return { pillBg: a, tabsBg: b };
`);
ok("深色下胶囊同样跟随主题（与 tabbar 同色）", darkPill.pillBg === darkPill.tabsBg,
   darkPill.pillBg + " vs " + darkPill.tabsBg);

/* ------------------------------------------------- 2. 选项按压回弹 */
console.log("\n[真机] 2. 选项按压弹性回弹");
const optTrace = await evalJs(`
  goTab("bank");
  startSession("practice", "anim_test", "single");
  var el = document.querySelector('#qbody .opt');
  if (!el) return { err: "no option" };
  function scaleOf(n) {
    var t = getComputedStyle(n).transform;
    if (!t || t === "none") return 1;
    var m = t.match(/matrix\\(([^)]+)\\)/);
    return m ? Math.round(parseFloat(m[1].split(",")[0]) * 1000) / 1000 : 1;
  }
  var down = [], up = [];
  el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, clientX: 40, clientY: 40, pointerId: 1, pointerType: "touch" }));
  await new Promise(function (res) {
    var t0 = performance.now();
    function tick() {
      down.push([Math.round(performance.now() - t0), scaleOf(el)]);
      if (performance.now() - t0 < 130) requestAnimationFrame(tick); else res();
    }
    requestAnimationFrame(tick);
  });
  document.dispatchEvent(new PointerEvent("pointerup", { bubbles: true, clientX: 40, clientY: 40, pointerId: 1, pointerType: "touch" }));
  await new Promise(function (res) {
    var t0 = performance.now();
    function tick() {
      up.push([Math.round(performance.now() - t0), scaleOf(el)]);
      if (performance.now() - t0 < 380) requestAnimationFrame(tick); else res();
    }
    requestAnimationFrame(tick);
  });
  var settled = scaleOf(el);
  exitQuiz();
  return { down: down, up: up, settled: settled, count: document.querySelectorAll("#qbody .opt").length };
`);
ok("答题页选项已渲染", (optTrace.count || 0) >= 2, String(optTrace.count));
const minDown = Math.min.apply(null, optTrace.down.map((s) => s[1]));
ok("按下缩到 0.96 附近", minDown <= 0.97 && minDown >= 0.93, "min=" + minDown);
const maxUp = Math.max.apply(null, optTrace.up.map((s) => s[1]));
ok("松开过冲到 1.02 附近（弹性回弹）", maxUp >= 1.005, "max=" + maxUp);
ok("回弹后回到 1.0", Math.abs(optTrace.settled - 1) <= 0.002, String(optTrace.settled));
const upSeq = optTrace.up.map((s) => s[1]);
const backTo1 = upSeq.indexOf(1);
ok("过冲发生在回 1 之前（不是一路缩）", backTo1 < 0 || upSeq.slice(0, backTo1).some((v) => v > 1.005),
   "seq=" + upSeq.slice(0, 8).join(","));

/* ---------------------------------------------- 3. 切页交错入场 */
console.log("\n[真机] 3. Tab 切页交错入场");
const enterTrace = await evalJs(`
  /* 筛选器可能被上一轮留在某个题型上，先回到「全部」，否则题库页没有卡片可入场 */
  bankTf = ""; tfSlide = false;
  goTab("home");
  await new Promise(function (r) { setTimeout(r, 700); });
  /* #bankList 的 innerHTML 是在 show() 里 renderBank() 重建的，
     所以节点必须在每帧现取，不能提前抓（提前抓到的会被整体替换掉） */
  function grab() {
    var list = document.getElementById("bankList");
    var cs = list ? [].slice.call(list.querySelectorAll(".swipe")) : [];
    return { head: list && list.firstElementChild, cards: cs };
  }
  var samples = [];
  goTab("bank");
  await new Promise(function (res) {
    var t0 = performance.now();
    function tick() {
      var t = Math.round(performance.now() - t0);
      var g = grab();
      var n = g.cards.length;
      samples.push([t,
        g.head ? Math.round(parseFloat(getComputedStyle(g.head).opacity) * 100) / 100 : -1,
        n > 0 ? Math.round(parseFloat(getComputedStyle(g.cards[0]).opacity) * 100) / 100 : -1,
        n > 1 ? Math.round(parseFloat(getComputedStyle(g.cards[n - 1]).opacity) * 100) / 100 : -1,
        n]);
      if (t < 900) requestAnimationFrame(tick); else res();
    }
    requestAnimationFrame(tick);
  });
  var pg = document.getElementById("tab-bank");
  var last = grab();
  return { samples: samples, cards: last.cards.length,
           pageOpacity: Math.round(parseFloat(getComputedStyle(pg).opacity) * 100) / 100,
           headExists: !!last.head };
`);
console.log("  · 题库页卡片 " + enterTrace.cards + " 张，采样 " + enterTrace.samples.length + " 帧");
ok("题库页有卡片可入场", enterTrace.cards >= 2, String(enterTrace.cards));
const o0 = enterTrace.samples.map((s) => s[2]);
const oN = enterTrace.samples.map((s) => s[3]);
const oH = enterTrace.samples.map((s) => s[1]);
const sawFade = oH.some((v) => v < 0.9) || o0.some((v) => v < 0.9);
ok("新页元素确实从透明淡入", sawFade === true, "head min=" + Math.min.apply(null, oH) + " card0 min=" + Math.min.apply(null, o0));
/* 交错：某一帧里靠后的卡片比靠前的更透明 */
const staggerFrames = enterTrace.samples.filter((s) => s[2] >= 0 && s[3] >= 0 && s[2] - s[3] > 0.05).length;
ok("卡片依次入场（存在后卡更透明的帧）", staggerFrames >= 1, String(staggerFrames));
/* 关键：带 delay 的动画必须 fill:backwards，否则 delay 期间元素是全不透明的，会先闪一下再消失 */
const flashFrames = enterTrace.samples.filter((s) => s[3] === 1 && s[2] < 0.9).length;
ok("带延迟的卡片在等待期不会先亮起来（无闪烁）", flashFrames === 0, "flashFrames=" + flashFrames);
ok("入场结束后页面完全不透明", enterTrace.pageOpacity === 1, String(enterTrace.pageOpacity));

/* ------------------------------------- 4. 题型筛选的毛毛虫下划线 */
console.log("\n[真机] 4. 题型筛选毛毛虫下划线");
const tfTrace = await evalJs(`
  var bar = document.querySelector("#bankList .tbar");
  var u = bar && bar.querySelector(".tbar-u");
  if (!u) return { err: "no underline" };
  /* setBankTf 会重建整条 tbar，所以按钮节点必须先记下标签、渲染完再重新取，
     否则拿到的是已经脱离文档的旧节点，offsetLeft 恒为 0 */
  var fromBtns = [].slice.call(bar.querySelectorAll("button"));
  var from = { x: fromBtns[0].offsetLeft, w: fromBtns[0].offsetWidth };
  var tfKey = BANK_TAGS[2][0];
  var samples = [];
  /* drawTfUnderline 会先把行内 left/width 写成终点，再用 keyframes 从旧位置跑过去；
     所以行内值永远只反映终点，采样必须读 getBoundingClientRect（它含动画合成结果）。 */
  function barRect() { return document.querySelector("#bankList .tbar").getBoundingClientRect(); }
  setBankTf(tfKey);
  var bar2 = document.querySelector("#bankList .tbar");
  var u2 = bar2.querySelector(".tbar-u");
  var btns = [].slice.call(bar2.querySelectorAll("button"));
  var barL = barRect().left;
  await new Promise(function (res) {
    var t0 = performance.now();
    function tick() {
      var r = u2.getBoundingClientRect();
      samples.push([Math.round(performance.now() - t0),
                    Math.round((r.left - barL) * 10) / 10, Math.round(r.width * 10) / 10]);
      if (performance.now() - t0 < 520) requestAnimationFrame(tick); else res();
    }
    requestAnimationFrame(tick);
  });
  var endR = u2.getBoundingClientRect();
  var ex = endR.left - barRect().left, ew = endR.width;
  var onBtn = btns.filter(function (b) { return b.className.indexOf("on") >= 0; })[0] || btns[0];
  return {
    samples: samples, from: from, end: { x: ex, w: ew },
    endTarget: { x: onBtn.offsetLeft + onBtn.offsetWidth * 0.29, w: onBtn.offsetWidth * 0.42 },
    fromTarget: { x: from.x + from.w * 0.29, w: from.w * 0.42 },
    color: getComputedStyle(u2).backgroundColor, ink: getComputedStyle(document.body).color,
    inBar: !!bar2.querySelector(".tbar-u")
  };
`);
if (tfTrace.err) {
  ok("题库页筛选栏有下划线元素", false, tfTrace.err);
} else {
  ok("题库页筛选栏有下划线元素", tfTrace.inBar === true);
  const ws = tfTrace.samples.map((s) => s[2]);
  const maxW = Math.max.apply(null, ws);
  const endW = tfTrace.end.w;
  console.log("  · 宽度轨迹 max=" + r2(maxW) + "px，终点 " + r2(endW) + "px");
  ok("下划线落到目标标签上", Math.abs(tfTrace.end.x - tfTrace.endTarget.x) <= 1.5,
     r2(tfTrace.end.x) + " vs " + r2(tfTrace.endTarget.x));
  ok("下划线宽度贴合标签", Math.abs(endW - tfTrace.endTarget.w) <= 1.5,
     r2(endW) + " vs " + r2(tfTrace.endTarget.w));
  const firstX = tfTrace.samples[0][1];
  ok("下划线从旧标签出发（不是直接跳过去）",
     Math.abs(firstX - tfTrace.fromTarget.x) <= 6, r2(firstX) + " vs " + r2(tfTrace.fromTarget.x));
  const spanNeeded = Math.abs(tfTrace.endTarget.x - tfTrace.fromTarget.x) + endW;
  ok("中间帧被拉长到同时盖住新旧两枚（毛毛虫）", maxW >= spanNeeded * 0.8,
     "max=" + r2(maxW) + " 需≈" + r2(spanNeeded) + " end=" + r2(endW));
  ok("下划线用主题墨色而不是彩色", tfTrace.color === tfTrace.ink,
     tfTrace.color + " vs ink " + tfTrace.ink);
}

/* ------------------------------------------------------ 还原与收尾 */
console.log("\n[真机] 还原");
const cleanup = await evalJs(`
  try { if (typeof exitQuiz === "function") exitQuiz(); } catch (e) {}
  if (window.__animSnap) {
    BANK.subjects = window.__animSnap.subjects;
    BANK.questions = window.__animSnap.questions;
    for (var k in window.__animSnap.set) set[k] = window.__animSnap.set[k];
    delete window.__animSnap;
    byId = {}; BANK.questions.forEach(function (q) { byId[q.id] = q; });
    applyTheme(); save(); renderAll();
  }
  goTab("home");
  return { subjects: BANK.subjects.length, questions: BANK.questions.length, theme: set.theme };
`);
console.log("  · 已还原：" + cleanup.subjects + " 个题库 / " + cleanup.questions + " 题 / 主题 " + cleanup.theme);

console.log("\n" + "=".repeat(46));
cdp.close();
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("真机动效全部通过：" + pass + " 项");
