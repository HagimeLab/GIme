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

/* ---------- 结果 ---------- */
console.log("\n" + "=".repeat(46));
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("全部通过：" + pass + " 项");
