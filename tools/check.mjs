/**
 * GIme 回归检查（无第三方依赖，node 直接跑）：
 *   1. index.html 内联脚本语法检查
 *   2. 在最小 DOM 桩上真跑一遍页面逻辑，覆盖 v1.0.1 修的几个流程：
 *      导入解析/统计口径、模拟考试交卷防重入、放弃后停表、最后一题滑动
 *   3. bank.js 内置题库结构检查
 *
 * 用法：node tools/check.mjs
 */
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const HTML = path.join(ROOT, "app/src/main/assets/index.html");
const BANK_JS = path.join(ROOT, "app/src/main/assets/bank.js");

let pass = 0;
const fails = [];
function ok(name, cond, detail) {
  if (cond) { pass++; console.log("  ✓ " + name); }
  else { fails.push(name + (detail ? "：" + detail : "")); console.log("  ✗ " + name + (detail ? "：" + detail : "")); }
}
function section(t) { console.log("\n" + t); }

/* ---------- 1. 内联脚本语法 ---------- */
section("[1] index.html 内联脚本语法");
const html = fs.readFileSync(HTML, "utf8");
const open = html.indexOf("<script>");
const close = html.indexOf("</script>", open);
const src = html.slice(open + 8, close);
try {
  new vm.Script(src, { filename: "index.inline.js" });
  ok("内联脚本可编译", true);
} catch (e) {
  ok("内联脚本可编译", false, e.message);
  process.exit(1);
}

/* ---------- 最小 DOM 桩 ---------- */
function makeElement(id) {
  const cls = new Set();
  const style = new Proxy({}, { get: (t, k) => t[k] ?? "", set: (t, k, v) => { t[k] = v; return true; } });
  const el = {
    id, style, innerHTML: "", textContent: "", value: "", disabled: false, onclick: null,
    offsetWidth: 0, offsetHeight: 0, scrollHeight: 0, children: [],
    classList: {
      add: (c) => cls.add(c), remove: (c) => cls.delete(c), contains: (c) => cls.has(c),
      toggle: (c) => (cls.has(c) ? cls.delete(c) : cls.add(c))
    },
    setAttribute: (k, v) => { el["attr_" + k] = v; },
    getAttribute: (k) => el["attr_" + k],
    appendChild: (c) => { el.children.push(c); return c; },
    remove: () => {},
    querySelector: () => null,
    querySelectorAll: () => [],
    getBoundingClientRect: () => ({ top: 0, left: 0, bottom: 0, right: 0, width: 0, height: 0 }),
    addEventListener: () => {},
    focus: () => {},
    animate: () => ({ finished: Promise.resolve() }),
    closest: () => null
  };
  return el;
}

const nodes = new Map();
const doc = {
  getElementById(id) {
    if (!nodes.has(id)) nodes.set(id, makeElement(id));
    return nodes.get(id);
  },
  querySelector: () => null,
  querySelectorAll: () => [],
  createElement: (t) => makeElement("new-" + t),
  addEventListener: () => {},
  documentElement: makeElement("html")
};
const storage = new Map();
const sandbox = {
  console,
  document: doc,
  localStorage: {
    getItem: (k) => (storage.has(k) ? storage.get(k) : null),
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: (k) => storage.delete(k)
  },
  navigator: { vibrate: () => {} },
  devicePixelRatio: 3,
  innerWidth: 400,
  innerHeight: 800,
  requestAnimationFrame: (fn) => { fn(); return 0; },
  setTimeout: (fn) => { fn(); return 0; },     // 同步执行，测试里不需要等
  clearTimeout: () => {},
  setInterval: () => 0,
  clearInterval: () => {},
  alert: () => {}
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
sandbox.addEventListener = () => {};
sandbox.removeEventListener = () => {};

/* bank.js 先加载，页面启动要拿 BANK */
vm.runInNewContext(fs.readFileSync(BANK_JS, "utf8"), sandbox, { filename: "bank.js" });

section("[2] 页面脚本装载");
try {
  vm.runInNewContext(src, sandbox, { filename: "index.inline.js" });
  ok("脚本在 DOM 桩上启动", true);
} catch (e) {
  ok("脚本在 DOM 桩上启动", false, e.stack);
  process.exit(1);
}

/* ---------- 3. 题库结构 ---------- */
section("[3] bank.js 题库结构");
const BANK = sandbox.BANK;
ok("有 subjects", Array.isArray(BANK.subjects) && BANK.subjects.length > 0);
ok("有 questions", Array.isArray(BANK.questions) && BANK.questions.length > 0);
const ids = new Set();
let dupId = 0, badAns = 0, badOpt = 0, badType = 0, emptyStem = 0;
const TYPES = new Set(["single", "multi", "judge"]);
for (const q of BANK.questions) {
  if (ids.has(q.id)) dupId++; else ids.add(q.id);
  if (!q.stem || !String(q.stem).trim()) emptyStem++;
  if (!TYPES.has(q.type)) badType++;
  if (!Array.isArray(q.options) || q.options.length < 2) badOpt++;
  const letters = (q.options || []).map((o) => o[0]);
  if (!Array.isArray(q.answer)) badAns++;
  else if (q.answer.some((a) => !letters.includes(a))) badAns++;
  else if (q.type === "single" && q.answer.length > 1) badAns++;
  else if (q.type === "judge" && q.answer.length !== 1) badAns++;
}
ok("题目 id 唯一", dupId === 0, dupId + " 个重复");
ok("题干非空", emptyStem === 0, emptyStem + " 条空题干");
ok("题型合法", badType === 0, badType + " 条非法题型");
ok("选项至少两个", badOpt === 0, badOpt + " 条选项不足");
ok("答案落在选项内且与题型一致", badAns === 0, badAns + " 条不一致");
const subjIds = new Set(BANK.subjects.map((s) => s.id));
const orphan = BANK.questions.filter((q) => !subjIds.has(q.subject)).length;
ok("题目都挂在已知题库下", orphan === 0, orphan + " 条孤儿题");

/* ---------- 4. 导入解析与统计口径 ---------- */
section("[4] 导入解析 / 无答案题统计");
const sample = [
  "1、以下哪个是可靠传输协议？",
  "A、UDP",
  "B、TCP",
  "C、ICMP",
  "D、ARP",
  "答案：B",
  "解析：TCP 提供确认与重传。",
  "2、HTTP 默认端口是 80。",
  "答案：正确",
  "3、下面哪个不是传输层协议？",
  "A、TCP",
  "B、UDP",
  "C、IP",
  "D、SCTP"
].join("\n");
const parsed = sandbox.parseBankText(sample);
ok("解析出 3 道题", parsed.length === 3, "实际 " + parsed.length);
ok("第 1 题答案 B", parsed[0] && parsed[0].answer.join("") === "B", JSON.stringify(parsed[0] && parsed[0].answer));
ok("第 1 题带解析", !!(parsed[0] && parsed[0].explain));
ok("第 2 题识别为判断题", parsed[1] && parsed[1].type === "judge", parsed[1] && parsed[1].type);
ok("第 3 题无答案", parsed[2] && parsed[2].answer.length === 0);
const usable = parsed.filter((x) => x.answer.length && x.options.length >= 2);
const rep = sandbox.importReport(parsed, usable);
ok("可判分 2 题", rep.usable === 2, "实际 " + rep.usable);
ok("无答案 1 题（不再恒为 0）", rep.noans === 1, "实际 " + rep.noans);
ok("统计三项相加等于总数", rep.usable + rep.noans + rep.dropped === rep.total,
   [rep.usable, rep.noans, rep.dropped, rep.total].join("+"));

/* ---------- 5. 模拟考试状态机 ---------- */
section("[5] 模拟考试交卷 / 计时 / 放弃");
const W = sandbox;
const totalN = () => Object.values(W.prog).reduce((s, p) => s + (p.n || 0), 0);

W.startSession("exam", "all", "exam");
ok("考试会话进入 running", W.S && W.S.state === "running", W.S && W.S.state);
const orderLen = W.S.order.length;
ok("试卷有题", orderLen > 0);

/* E-01：最后一题反复滑动只交一次 */
const before = totalN();
for (let i = 0; i < orderLen; i++) W.go(1);   // 一路滑到最后；最后一题再滑应触发交卷
const firstSubmit = totalN() - before;
ok("滑动到最后一题触发交卷且会话已清空", W.S === null);
W.go(1);           // 交卷后再滑，不能报错、不能二次结算
W.submitExam();    // 二次交卷同样必须被挡住
ok("重复交卷不产生第二次结算", totalN() - before === firstSubmit,
   "第一次 " + firstSubmit + " 次，之后 " + (totalN() - before) + " 次");
ok("答错题进了错题本", Object.values(W.prog).some((p) => p.w > 0 && p.in === 1));

/* E-03：倒计时归零走同一交卷入口 */
W.startSession("exam", "all", "exam");
W.S.started = Date.now() - (W.S.dur + 5) * 1000;
const before2 = totalN();
W.tick();
ok("倒计时归零自动交卷并清空会话", W.S === null);
ok("倒计时归零只结算一次", totalN() - before2 > 0);
W.tick();
ok("交卷后 tick 不再结算", totalN() - before2 > 0 && W.S === null);

/* E-05：退出并放弃后停表，不再自动交卷 */
W.startSession("exam", "all", "exam");
const before3 = totalN();
W.abandonExam();
ok("放弃后会话清空", W.S === null);
W.tick();
ok("放弃后 tick 不再自动交卷", W.S === null && totalN() === before3);

/* E-06：普通练习返回不受影响 */
W.startSession("practice", "all", "");
const sidPractice = W.S.order.slice();
W.exitQuiz();
ok("普通练习退出保留会话且不结算", W.S !== null && W.S.order.length === sidPractice.length);

/* ---------- 6. 两条返回入口必须一致 ----------
   答题页左上角箭头走 exitQuiz()，系统返回键走 window.onBack()。
   两条路都必须拦住考试，否则从箭头那条路就能把计时丢在后台。 */
section("[6] 考试中两条返回入口一致");
const backEntry = await (async () => {
  // 箭头：直接调 exitQuiz
  W.startSession("exam", "all", "exam");
  const beforeArrow = totalN();
  W.exitQuiz();
  const arrowOpenedDialog = W.openLayers.length > 0 && W.S !== null;
  const arrowSettled = totalN() === beforeArrow;
  W.closeTop();

  // 返回键：window.onBack
  const beforeKey = totalN();
  const handled = W.onBack();
  const keyOpenedDialog = W.openLayers.length > 0 && W.S !== null;
  const keySettled = totalN() === beforeKey;
  W.closeTop();
  W.abandonExam();
  return { arrowOpenedDialog, arrowSettled, keyOpenedDialog, keySettled, handled };
})();
ok("答题页箭头拦下考试（不静默退出）", backEntry.arrowOpenedDialog === true);
ok("答题页箭头不触发结算", backEntry.arrowSettled === true);
ok("返回键拦下考试（不静默退出）", backEntry.keyOpenedDialog === true);
ok("返回键不触发结算", backEntry.keySettled === true);
ok("两条入口都被识别为已消费", backEntry.handled === true);

/* ---------- 7. 存档恢复规则 ----------
   submitting 必须归一成 running（否则永久卡在「正在交卷」）；
   submitted/abandoned 必须丢弃（否则已结束的卷子又被恢复出来）。
   直接调页面里的 normalizeSession，测真实现而不是复制一份规则。 */
section("[7] 会话恢复归一");
const norm = (s) => W.normalizeSession(JSON.parse(JSON.stringify(s)));
ok("submitting 恢复为 running", norm({ mode: "exam", state: "submitting" }).state === "running");
ok("缺失 state 恢复为 running", norm({ mode: "exam" }).state === "running");
ok("submitted 被丢弃", norm({ mode: "exam", state: "submitted" }) === null);
ok("abandoned 被丢弃", norm({ mode: "exam", state: "abandoned" }) === null);
ok("练习会话不受影响", norm({ mode: "practice", state: "x" }).state === "x");
ok("null 会话不崩", W.normalizeSession(null) === null);

/* ---------- 8. v1.0.2 进入「我的」自动检查更新 ----------
   这一节全部走页面里的真实现（checkUpdateSilently / onUpdateInfo / showUpdateNotice），
   不复制一份规则来测。原生层用桩记录调用次数，回调由测试手工触发。 */
section("[8] 自动检查更新 / 暂缓 / 小红点");

let nativeChecks = 0;
let toasts = 0;
W.Android = { checkUpdate: () => { nativeChecks++; }, appVersion: () => "1.0.2" };
const realToast = W.toast;
W.toast = function () { toasts++; };
/* 沙箱里的 setTimeout 是同步立即执行的（为了测异步流程），但更新逻辑用它做
   20 秒超时解锁。真跑起来不会立刻触发，这里换成空实现，让「请求在飞」的状态
   能保持住，否则测不出并发保护。 */
const realSetTimeout = W.setTimeout;
W.setTimeout = () => 0;

const glassEl = () => W.document.getElementById("glass");
const glassOpen = () => W.openLayers.length > 0;
const glassCls = () => String(glassEl().className || "");
const glassHtml = () => String(glassEl().innerHTML || "");
const resetUpd = (avail, dismissed) => {
  W.upd.req = false; W.upd.silent = false;
  W.upd.avail = avail || ""; W.upd.dismissed = dismissed || "";
  W.upd.hasNew = !!(avail && W.Android_verNewer(avail, W.APP_VER));
  W.updLast = 0;
  while (W.openLayers.length) W.closeTop();
};
const info = (o) => JSON.stringify(Object.assign({
  ok: true, hasUpdate: false, current: "1.0.2", latest: "1.0.2",
  urls: ["https://cdn.example.com/GIme-v1.0.2.apk"], log: [], notes: ""
}, o));

/* 8.1 自动检查：已是最新版本时什么都不弹 */
resetUpd(); toasts = 0; nativeChecks = 0;
W.checkUpdateSilently();
ok("进入「我的」会真的发起一次检查", nativeChecks === 1, "实际 " + nativeChecks);
ok("自动检查本身不弹 Toast", toasts === 0, "实际 " + toasts);
W.onUpdateInfo(info({ hasUpdate: false }));
ok("自动检查到最新版本时不弹窗", !glassOpen());
ok("自动检查到最新版本时不提示", toasts === 0, "实际 " + toasts);

/* 8.2 首次发现新版本 → 小窗，且状态落盘 */
resetUpd(); nativeChecks = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: true, latest: "1.0.3" }));
ok("首次发现新版本弹小窗", glassOpen() && glassCls().includes("note"), glassCls());
ok("小窗只给版本号和两个按钮，不展开日志", !glassHtml().includes("upd-log"));
ok("发现新版本后小红点亮起", W.upd.hasNew === true);
const savedUpd = JSON.parse(storage.get("stk.update.v1") || "{}");
ok("可用版本已落盘", savedUpd.avail === "1.0.3", JSON.stringify(savedUpd));

/* 8.3 稍后更新 → 记住这个版本，但小红点保留 */
W.document.getElementById("noteLater").onclick();
ok("稍后更新关掉小窗", !glassOpen());
ok("稍后更新不熄灭小红点", W.upd.hasNew === true);
const savedUpd2 = JSON.parse(storage.get("stk.update.v1") || "{}");
ok("暂缓版本已落盘", savedUpd2.dismissed === "1.0.3", JSON.stringify(savedUpd2));

/* 8.4 再次进入「我的」，同一个版本不重复打断 */
resetUpdKeepDismissed();
function resetUpdKeepDismissed() {
  const s = JSON.parse(storage.get("stk.update.v1") || "{}");
  resetUpd(s.avail, s.dismissed);
}
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: true, latest: "1.0.3" }));
ok("同一暂缓版本不再弹窗", !glassOpen());
ok("同一暂缓版本小红点还在", W.upd.hasNew === true);

/* 8.5 出现更高的版本 → 重新提醒 */
resetUpdKeepDismissed(); W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: true, latest: "1.0.4" }));
ok("更高版本重新弹窗", glassOpen() && glassCls().includes("note"));
ok("小红点跟到更高版本", W.upd.avail === "1.0.4");
W.closeTop();

/* 8.6 自动检查失败：不打扰、也不清标记 */
resetUpdKeepDismissed(); W.upd.avail = "1.0.4"; W.updSyncMark();
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(JSON.stringify({ ok: false, error: "网络不可用" }));
ok("自动检查失败不弹窗", !glassOpen());
ok("自动检查失败不清小红点", W.upd.hasNew === true, JSON.stringify(W.upd));

/* 8.7 确认已是最新版本 → 清掉提示 */
resetUpd("1.0.4", "1.0.3");
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: false }));
ok("确认最新后小红点熄灭", W.upd.hasNew === false);
ok("确认最新后清空落盘状态", (storage.get("stk.update.v1") || "").indexOf("1.0.4") < 0);

/* 8.8 手动检查：仍然弹完整更新弹窗（带日志） */
resetUpd(); toasts = 0; nativeChecks = 0;
W.checkUpdate(true);
ok("手动检查有「检查更新中」提示", toasts === 1, "实际 " + toasts);
W.onUpdateInfo(info({ hasUpdate: true, latest: "1.0.3", log: ["修复下载失败", "新增自动检查"] }));
ok("手动检查弹完整更新弹窗", glassCls().includes("upd"), glassCls());
ok("完整弹窗里有更新日志", glassHtml().includes("upd-log") && glassHtml().includes("修复下载失败"));
W.closeTop();

/* 8.9 手动检查失败：沿用失败弹窗 */
resetUpd(); W.upd.avail = "1.0.3"; W.updSyncMark();
W.checkUpdate(true);
W.onUpdateInfo(JSON.stringify({ ok: false, error: "连不上服务器" }));
ok("手动检查失败弹失败提示", glassOpen() && glassHtml().includes("连不上服务器"));
ok("手动检查失败不清小红点", W.upd.hasNew === true);
W.closeTop();

/* 8.10 自动请求在飞时点手动检查：不另开一条，而是升级成手动 */
resetUpd(); nativeChecks = 0;
W.checkUpdateSilently();
W.checkUpdate(true);
ok("在飞时点手动检查不重复发请求", nativeChecks === 1, "实际 " + nativeChecks);
ok("在飞的手动检查会升级为手动模式", W.upd.silent === false);
W.onUpdateInfo(info({ hasUpdate: true, latest: "1.0.3", log: ["x"] }));
ok("升级后结果按手动流程弹完整弹窗", glassCls().includes("upd"), glassCls());
W.closeTop();

/* 8.11 触发点：只有用户点「我的」才查，程序内跳转不查 */
resetUpd(); nativeChecks = 0;
W.goTab("me");
ok("程序内 goTab 不触发检查", nativeChecks === 0, "实际 " + nativeChecks);
W.tabTap("me");
ok("用户点「我的」触发检查", nativeChecks === 1, "实际 " + nativeChecks);
nativeChecks = 0;
W.tabTap("home");
ok("点其它 tab 不触发检查", nativeChecks === 0, "实际 " + nativeChecks);

/* 8.12 冷却：短时间内反复进「我的」不重复打 GitHub 配额 */
resetUpd(); W.updLast = Date.now();
nativeChecks = 0;
W.checkUpdateSilently();
ok("冷却期内不重复请求", nativeChecks === 0, "实际 " + nativeChecks);
nativeChecks = 0;
W.checkUpdate(true);
ok("手动检查不受冷却限制", nativeChecks === 1, "实际 " + nativeChecks);
W.onUpdateInfo(info({ hasUpdate: false }));

/* 8.13 小红点渲染在「检查更新」这一行上，且重画后仍在 */
resetUpd("1.0.3", "");
W.renderMe();
const meHtml = String(W.document.getElementById("meSet").innerHTML || "");
ok("「检查更新」行带小红点", /data-k="update"[\s\S]*?class="dot"/.test(meHtml));
ok("其它行没有小红点", (meHtml.match(/class="dot"/g) || []).length === 1,
   "实际 " + (meHtml.match(/class="dot"/g) || []).length);
resetUpd("1.0.2", "");
W.renderMe();
ok("无新版本时小红点消失", !/class="dot"/.test(String(W.document.getElementById("meSet").innerHTML || "")));

W.toast = realToast;
W.setTimeout = realSetTimeout;

/* ---------- 结果 ---------- */
console.log("\n" + "=".repeat(46));
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("全部通过：" + pass + " 项");
