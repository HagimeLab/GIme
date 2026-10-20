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
      /* 真 DOM 的 toggle 支持第二参数（force）：折叠逻辑靠它决定加还是删，
         桩里不实现这一半就等于没测 */
      toggle: (c, force) => {
        const want = force === undefined ? !cls.has(c) : !!force;
        if (want) cls.add(c); else cls.delete(c);
        return want;
      }
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

/* ---------- 3. 内置题库已移除 ----------
   v1.0.3 起所有题库都由用户导入。bank.js 只剩一个合法的空壳，
   这里要钉住「空题库是正式支持状态」，而不是把它当异常。 */
section("[3] 内置题库已移除 / 空题库是正常状态");
const BANK = sandbox.BANK;
ok("bank.js 仍是合法 BANK 结构", !!BANK && Array.isArray(BANK.subjects) && Array.isArray(BANK.questions));
ok("不再自带任何题库", BANK.subjects.length === 0, "还有 " + BANK.subjects.length + " 个");
ok("不再自带任何题目", BANK.questions.length === 0, "还有 " + BANK.questions.length + " 题");

/* 空题库下四个 tab 必须都能画出来，且不能出现 NaN / undefined。
   注意 renderAll() 只画当前 tab，所以要逐个切过去再读。 */
let emptyDraw = null;
try {
  sandbox.goTab("home");
  const home = String(sandbox.document.getElementById("tasks").innerHTML);
  const cats = String(sandbox.document.getElementById("cats").innerHTML);
  sandbox.goTab("bank");
  const bank = String(sandbox.document.getElementById("bankList").innerHTML);
  sandbox.goTab("me");
  const me = String(sandbox.document.getElementById("meStat").innerHTML);
  sandbox.goTab("home");
  emptyDraw = { home, cats, bank, me };
} catch (e) {
  emptyDraw = { err: e.stack };
}
ok("空题库下 renderAll 不抛异常", emptyDraw && !emptyDraw.err, emptyDraw && emptyDraw.err);
ok("「我的」页没有 NaN", emptyDraw && emptyDraw.me.indexOf("NaN") < 0);
ok("题库页给出导入入口", emptyDraw && emptyDraw.bank.indexOf("openImport") >= 0);
ok("首页给出导入入口", emptyDraw && emptyDraw.home.indexOf("openImport") >= 0);
ok("首页六张题型卡片都在", emptyDraw && (emptyDraw.cats.match(/class="cat-wrap/g) || []).length === 6,
   emptyDraw && String((emptyDraw.cats.match(/class="cat-wrap/g) || []).length));

/* 组卷在空题库下不能抛异常，只能提示 */
let paperThrew = null;
try { sandbox.openPaper("all"); } catch (e) { paperThrew = e.message; }
ok("空题库下点组卷不抛异常", paperThrew === null, paperThrew);
while (sandbox.openLayers.length) sandbox.closeTop();

/* ---------- 4. 导入解析与统计口径 ---------- */
section("[4] 导入解析 / 无答案题统计 / 主观题");
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
const usable = parsed.filter(sandbox.usableQ);
const rep = sandbox.importReport(parsed, usable);
ok("可判分 2 题", rep.usable === 2, "实际 " + rep.usable);
ok("无答案 1 题（不再恒为 0）", rep.noans === 1, "实际 " + rep.noans);
ok("统计三项相加等于总数", rep.usable + rep.noans + rep.dropped === rep.total,
   [rep.usable, rep.noans, rep.dropped, rep.total].join("+"));

/* 主观题：题型标记优先，参考答案单独存 ref，永远不进自动判分 */
const subjSample = [
  "1、（填空题）Kubernetes 中用于暴露集群内部服务的最小对象是 ______。",
  "参考答案：Service",
  "解析：Service 为一组 Pod 提供稳定入口。",
  "2、（解答题）简述容器与虚拟机的区别。",
  "参考答案：容器共享宿主内核，虚拟机各自运行完整操作系统。",
  "3、下列哪个是容器编排平台？",
  "A、Docker",
  "B、Kubernetes",
  "答案：B",
  "4、______ 是华为云的分布式缓存服务。",
  "参考答案：DCS"
].join("\n");
const sp = sandbox.parseBankText(subjSample);
ok("主观题样本解析出 4 题", sp.length === 4, "实际 " + sp.length);
ok("带「（填空题）」标记的识别为填空题", sp[0] && sp[0].type === "fill", sp[0] && sp[0].type);
ok("填空题的参考答案进 ref", sp[0] && sp[0].ref === "Service", sp[0] && sp[0].ref);
ok("填空题不带选项", sp[0] && sp[0].options.length === 0);
ok("填空题不被判为「无答案」", sp[0] && sp[0].answer.length === 0 && sandbox.usableQ(sp[0]));
ok("带「（解答题）」标记的识别为解答题", sp[1] && sp[1].type === "short", sp[1] && sp[1].type);
ok("解答题的参考答案进 ref", sp[1] && String(sp[1].ref).indexOf("容器共享宿主内核") === 0, sp[1] && sp[1].ref);
ok("题干里的「参考答案」四个字没有留在题干里",
   sp[0] && sp[0].stem.indexOf("参考答案") < 0, sp[0] && sp[0].stem);
ok("客观题不受影响（第 3 题仍是单选 B）", sp[2] && sp[2].type === "single" && sp[2].answer.join("") === "B",
   sp[2] && (sp[2].type + "/" + sp[2].answer.join("")));
ok("没写题型标记但有横线也按填空收", sp[3] && sp[3].type === "fill", sp[3] && sp[3].type);

const spUsable = sp.filter(sandbox.usableQ);
const spRep = sandbox.importReport(sp, spUsable);
ok("主观题计入收录数", spRep.usable === 4, "实际 " + spRep.usable);
ok("分题型统计：填空 2", spRep.by.fill === 2, "实际 " + spRep.by.fill);
ok("分题型统计：解答 1", spRep.by.short === 1, "实际 " + spRep.by.short);
ok("分题型统计：单选 1", spRep.by.single === 1, "实际 " + spRep.by.single);
ok("主观题不算「无答案」", spRep.noans === 0, "实际 " + spRep.noans);
ok("缺参考答案单独计数", spRep.noRef === 0, "实际 " + spRep.noRef);
ok("结果统计行包含题型明细", sandbox.importLine(spRep).indexOf("填空 2") >= 0);

/* 4a. 用户 2026-10-20 报的那份 ICT 题源排版（图一，md 与 pdf 是同一份稿子）：
   「第N题」标题 + 「题目：」标签 + 四个选项挤在一行 + 代码块。
   改之前 NUM_RE 只认行首「1、」，这份稿子整篇一道题都认不出来。
   下面这份夹具是按截图逐字重打的，不是原文件本身。 */
const labeled = [
  "# ICT大赛-计算赛道省赛",
  "",
  "第1题",
  "题目：可以列出所有数据库角色的是？",
  "A. \\du  B. dr  C. \\d",
  "答案：A",
  "解析：在openGauss/PostgreSQL中，`\\du` 是元命令，用于列出所有数据库角色（用户）。",
  "",
  "第2题",
  "题目：现有分区表t定义语句如下：",
  "```sql",
  "CREATE TABLE t",
  "(  col1 integer NOT NULL,",
  "  col2 character varying(60)",
  ") PARTITION BY RANGE (col1);",
  "```",
  "现执行插入语句：INSERT INTO t VALUES(20000); 以下选项哪个是正确的执行结果？",
  "A. 插入失败，原因是无法匹配到合适的分区 B. 插入失败，原因是语法错误 C. 插入成功，插入行位于自动新建的分区中 D. 插入成功，插入的行位于分区P3",
  "正确答案：A",
  "解析：范围分区表中，P1接受col1<5000。插入值20000超过了最大分区上限15000，没有匹配的分区，因此插入失败。",
  "",
  "第3题",
  "题目：以下哪个选项为字符处理函数？",
  "选项：A. lpad  B. bitand  C. age  D. path",
  "答案：A",
  "答案解析：**lpad(string, length, fill)** 是字符处理函数。bitand 是位运算函数。",
  "",
  "第4题",
  "题目：如果将分区sdd2开机自动挂载到/mnt/file目录下，管理员需将对应配置写入到以下哪个配置文件中？",
  "A. /etc/fstab  B. /etc/mtab  C. /etc/passwd  D. /etc/hosts",
].join("\n");
const lb = sandbox.parseBankText(labeled);
ok("「第N题 + 题目：」排版认得出 4 道题", lb.length === 4, "实际 " + lb.length);
ok("第 1 题答案 A、单选", lb[0] && lb[0].type === "single" && lb[0].answer.join("") === "A",
   lb[0] && (lb[0].type + "/" + lb[0].answer.join("")));
ok("第 1 题三个选项", lb[0] && lb[0].options.length === 3, lb[0] && String(lb[0].options.length));
ok("「题目：」标签没留在题干里", lb[0] && lb[0].stem.indexOf("题目") < 0, lb[0] && lb[0].stem);
ok("选项里的反斜杠保住（\\du 没被吃掉）", lb[0] && lb[0].options[0][1].indexOf("\\du") >= 0,
   lb[0] && JSON.stringify(lb[0].options));
ok("解析里的行内代码反引号被去掉", lb[0] && lb[0].explain.indexOf("`") < 0, lb[0] && lb[0].explain);
ok("代码块围栏 ``` 不进题干", lb[1] && lb[1].stem.indexOf("```") < 0, lb[1] && lb[1].stem);
ok("代码块正文收进题干", lb[1] && lb[1].stem.indexOf("CREATE TABLE t") >= 0
   && lb[1].stem.indexOf("INSERT INTO") >= 0, lb[1] && lb[1].stem);
ok("第 2 题四个选项都在", lb[1] && lb[1].options.length === 4, lb[1] && String(lb[1].options.length));
ok("「正确答案：」也算答案", lb[1] && lb[1].answer.join("") === "A", lb[1] && lb[1].answer.join(""));
ok("「正确答案」四个字没混进最后一个选项", lb[1] && lb[1].options[3][1].indexOf("正确") < 0,
   lb[1] && JSON.stringify(lb[1].options[3]));
ok("「选项：」标签行也能拆出四个选项", lb[2] && lb[2].options.length === 4, lb[2] && String(lb[2].options.length));
ok("「答案解析：」整条进解析，不留光杆标签", lb[2] && lb[2].explain.indexOf("lpad") >= 0
   && lb[2].options[3][1].indexOf("答案") < 0, lb[2] && JSON.stringify([lb[2].explain, lb[2].options[3]]));
ok("**加粗** 星号不进解析", lb[2] && lb[2].explain.indexOf("**") < 0, lb[2] && lb[2].explain);
ok("第 4 题没答案 → 不算可判分", lb[3] && lb[3].answer.length === 0 && !sandbox.usableQ(lb[3]));
const lbRep = sandbox.importReport(lb, lb.filter(sandbox.usableQ));
ok("这份稿子可判分 3 题", lbRep.usable === 3, "实际 " + lbRep.usable);
ok("题号从中间开始也认（第12题起的截稿）", sandbox.parseBankText(
   "第12题\n题目：下列哪个是集群？\nA、x B、y\n答案：A").length === 1);

/* ---------- 4b. 装入一套测试题库，后面的练习/考试流程才有题可出 ---------- */
section("[4b] 装入测试题库");
const TB = "test_bank";
BANK.subjects.push({ id: TB, name: "测试题库", file: "t.txt", count: 6, custom: true });
const mk = (i, type, opts, ans, ref) => ({
  id: TB + "-" + i, subject: TB, num: i + 1, pnum: i + 1, type,
  stem: "测试题 " + (i + 1) + "（" + type + "）",
  options: opts, answer: ans, ans_raw: ans.join(""), ref: ref || "",
  explain: "解析 " + (i + 1), note: "", flag: "", ex_source: "编写"
});
const testQs = [
  mk(0, "single", [["A", "甲"], ["B", "乙"], ["C", "丙"], ["D", "丁"]], ["A"]),
  mk(1, "multi", [["A", "甲"], ["B", "乙"], ["C", "丙"], ["D", "丁"]], ["A", "C"]),
  mk(2, "judge", [["A", "正确"], ["B", "错误"]], ["A"]),
  mk(3, "single", [["A", "甲"], ["B", "乙"]], ["B"]),
  mk(4, "fill", [], [], "参考答案甲"),
  mk(5, "short", [], [], "参考答案乙")
];
testQs.forEach((q) => { BANK.questions.push(q); sandbox.byId[q.id] = q; });
sandbox.set.paper.subs = {}; sandbox.set.paper.subs[TB] = 1;
ok("测试题库已装入 6 题", BANK.questions.length === 6, "实际 " + BANK.questions.length);
ok("模拟考试只抽客观题", sandbox.paperPool().length === 4, "实际 " + sandbox.paperPool().length);
ok("主观题不进组卷池", sandbox.paperPool().every((q) => sandbox.isObjective(q.type)));
ok("统计口径：总题数含主观题", sandbox.stats(TB).total === 6, "实际 " + sandbox.stats(TB).total);

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

/* 版本号全部相对页面实际版本推导：以后升 1.0.4、1.0.5 都不用回来改测试，
   也不会出现「测试里写死 1.0.3、页面已经是 1.0.3 之后」那种假通过。 */
function bump(v, n) {
  const a = String(v).split(".").map((x) => parseInt(x, 10) || 0);
  while (a.length < 3) a.push(0);
  a[a.length - 1] += n;
  return a.join(".");
}
const VER = W.APP_VER;
const NEXT = bump(VER, 1);
const NEXT2 = bump(VER, 2);
ok("页面兜底版本号格式正确", /^\d+\.\d+\.\d+$/.test(VER), VER);

let nativeChecks = 0;
let toasts = 0;
W.Android = { checkUpdate: () => { nativeChecks++; }, appVersion: () => VER };
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
  ok: true, hasUpdate: false, current: VER, latest: VER,
  urls: ["https://cdn.example.com/GIme-v" + VER + ".apk"], log: [], notes: ""
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
W.onUpdateInfo(info({ hasUpdate: true, latest: NEXT }));
ok("首次发现新版本弹小窗", glassOpen() && glassCls().includes("note"), glassCls());
ok("小窗只给版本号和两个按钮，不展开日志", !glassHtml().includes("upd-log"));
ok("发现新版本后小红点亮起", W.upd.hasNew === true);
const savedUpd = JSON.parse(storage.get("stk.update.v1") || "{}");
ok("可用版本已落盘", savedUpd.avail === NEXT, JSON.stringify(savedUpd));

/* 8.3 稍后更新 → 记住这个版本，但小红点保留 */
W.document.getElementById("noteLater").onclick();
ok("稍后更新关掉小窗", !glassOpen());
ok("稍后更新不熄灭小红点", W.upd.hasNew === true);
const savedUpd2 = JSON.parse(storage.get("stk.update.v1") || "{}");
ok("暂缓版本已落盘", savedUpd2.dismissed === NEXT, JSON.stringify(savedUpd2));

/* 8.4 再次进入「我的」，同一个版本不重复打断 */
resetUpdKeepDismissed();
function resetUpdKeepDismissed() {
  const s = JSON.parse(storage.get("stk.update.v1") || "{}");
  resetUpd(s.avail, s.dismissed);
}
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: true, latest: NEXT }));
ok("同一暂缓版本不再弹窗", !glassOpen());
ok("同一暂缓版本小红点还在", W.upd.hasNew === true);

/* 8.5 出现更高的版本 → 重新提醒 */
resetUpdKeepDismissed(); W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: true, latest: NEXT2 }));
ok("更高版本重新弹窗", glassOpen() && glassCls().includes("note"));
ok("小红点跟到更高版本", W.upd.avail === NEXT2);
W.closeTop();

/* 8.6 自动检查失败：不打扰、也不清标记 */
resetUpdKeepDismissed(); W.upd.avail = NEXT2; W.updSyncMark();
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(JSON.stringify({ ok: false, error: "网络不可用" }));
ok("自动检查失败不弹窗", !glassOpen());
ok("自动检查失败不清小红点", W.upd.hasNew === true, JSON.stringify(W.upd));

/* 8.7 确认已是最新版本 → 清掉提示 */
resetUpd(NEXT2, NEXT);
W.updLast = 0;
W.checkUpdateSilently();
W.onUpdateInfo(info({ hasUpdate: false }));
ok("确认最新后小红点熄灭", W.upd.hasNew === false);
ok("确认最新后清空落盘状态", (storage.get("stk.update.v1") || "").indexOf(NEXT2) < 0);

/* 8.8 手动检查：仍然弹完整更新弹窗（带日志） */
resetUpd(); toasts = 0; nativeChecks = 0;
W.checkUpdate(true);
ok("手动检查有「检查更新中」提示", toasts === 1, "实际 " + toasts);
W.onUpdateInfo(info({ hasUpdate: true, latest: NEXT, log: ["修复下载失败", "新增自动检查"] }));
ok("手动检查弹完整更新弹窗", glassCls().includes("upd"), glassCls());
ok("完整弹窗里有更新日志", glassHtml().includes("upd-log") && glassHtml().includes("修复下载失败"));
W.closeTop();

/* 8.9 手动检查失败：沿用失败弹窗 */
resetUpd(); W.upd.avail = NEXT; W.updSyncMark();
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
W.onUpdateInfo(info({ hasUpdate: true, latest: NEXT, log: ["x"] }));
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
resetUpd(NEXT, "");
W.renderMe();
const meHtml = String(W.document.getElementById("meSet").innerHTML || "");
ok("「检查更新」行带小红点", /data-k="update"[\s\S]*?class="dot"/.test(meHtml));
ok("其它行没有小红点", (meHtml.match(/class="dot"/g) || []).length === 1,
   "实际 " + (meHtml.match(/class="dot"/g) || []).length);
resetUpd(VER, "");
W.renderMe();
ok("无新版本时小红点消失", !/class="dot"/.test(String(W.document.getElementById("meSet").innerHTML || "")));

W.toast = realToast;
W.setTimeout = realSetTimeout;

/* ---------- 9. v1.0.3 新增能力 ----------
   主观题、震动开关、六卡片展开、头像、删掉的搜索行。全部走页面真实现。 */
section("[9] v1.0.3：主观题 / 震动 / 六卡片 / 头像");

/* 9.1 主观题在练习里能出、能存文本、能自评，且不进正确率 */
W.startSession("practice", TB, "fill");
ok("填空题能开练习", W.S !== null && W.S.order.length === 1, W.S && W.S.order.length);
const qb = () => String(W.document.getElementById("qbody").innerHTML);
ok("主观题渲染成文本框", qb().indexOf('class="q-input"') >= 0);
ok("主观题展示参考答案", qb().indexOf("参考答案") >= 0);
ok("主观题没有可点的选项", qb().indexOf('onclick="pick') < 0);
ok("主观题没有「确认」按钮", W.document.getElementById("btnAct").textContent === "下一题",
   W.document.getElementById("btnAct").textContent);
const rateBefore = W.stats(TB).rate;
const nBefore = (W.prog[TB + "-4"] && W.prog[TB + "-4"].n) || 0;
W.selfCheck(1);
ok("自评写入 S.self", W.S.self[TB + "-4"] === 1, String(W.S.self[TB + "-4"]));
ok("自评计入练习次数", (W.prog[TB + "-4"].n || 0) === nBefore + 1);
ok("自评不影响正确率", W.stats(TB).rate === rateBefore, W.stats(TB).rate + " vs " + rateBefore);
W.selfCheck(0);
ok("改判为待复习后进错题本", W.prog[TB + "-4"].in === 1 && W.prog[TB + "-4"].w > 0);
ok("主观题自评不产生 graded（不进判分口径）", W.S.graded[TB + "-4"] === undefined);

/* 9.2 主观题不能被当成客观题判分 */
W.startSession("practice", TB, "fill");
W.pick("A");
ok("主观题调 pick 不改变 picks", (W.S.picks[TB + "-4"] || []).length === 0);
W.grade(["A"]);
ok("主观题调 grade 不写 graded", W.S.graded[TB + "-4"] === undefined);

/* 9.3 震动开关：关掉就一次都不该调 navigator.vibrate */
let vibes = 0;
W.navigator.vibrate = () => { vibes++; };
W.set.haptics = false;
W.startSession("practice", TB, "single");
const correct = W.S.order[0] === TB + "-1" ? "A" : "A";
vibes = 0;
W.grade([correct]);
ok("震动关闭时不调 vibrate", vibes === 0, "实际 " + vibes);
W.set.haptics = true;
vibes = 0;
W.grade([correct]);
ok("震动开启时会调 vibrate", vibes > 0, "实际 " + vibes);
W.set.haptics = "false";
vibes = 0;
W.grade([correct]);
ok("haptics 存成字符串 \"false\" 也算关闭", vibes === 0, "实际 " + vibes);
W.set.haptics = true;

/* 9.4 分类区整体折叠：一个开关管六张卡片，卡片本身只负责进练习 */
W.S = null; W.goTab("home");
const catHtml = () => String(W.document.getElementById("cats").innerHTML);
const foldEl = () => W.document.getElementById("catsFold");
const toggleBtn = () => W.document.getElementById("catsToggle");
ok("首页六张卡片", (catHtml().match(/class="cat-wrap/g) || []).length === 6);
ok("卡片不再带展开面板", catHtml().indexOf("cat-panel") < 0 && catHtml().indexOf("cat-in") < 0);
ok("卡片不再有 aria-expanded（没有单卡展开了）", catHtml().indexOf("aria-expanded") < 0);
ok("卡片点击直接进练习", /onclick="catGo\(/.test(catHtml()));
W.catsClosed = false; W.syncCatsFold();
ok("默认展开", !foldEl().classList.contains("closed") && toggleBtn().getAttribute("aria-expanded") === "true");
ok("默认按钮文案是「收起」", W.document.getElementById("catsToggleTx").textContent === "收起");
W.toggleCats();
ok("点一下整体收起", W.catsClosed === true && foldEl().classList.contains("closed"));
ok("收起后 aria-expanded=false", toggleBtn().getAttribute("aria-expanded") === "false");
ok("收起后按钮文案变「展开」", W.document.getElementById("catsToggleTx").textContent === "展开");
ok("收起不动会话（不会顺手开始答题）", W.S === null);
W.toggleCats();
ok("再点一下整体展开", W.catsClosed === false && !foldEl().classList.contains("closed"));
ok("展开后六张卡片仍都在", (catHtml().match(/class="cat-wrap/g) || []).length === 6);
W.catGo("judge");
ok("卡片点击进对应题型练习", W.S !== null && W.S.tf === "judge", W.S && W.S.tf);
W.exitQuiz();
W.S = null;

/* 9.5 首页头像已删除；「我的」页不再有搜索行 */
W.goTab("me");
const meSetHtml = String(W.document.getElementById("meSet").innerHTML);
ok("「我的」页没有搜索题目行", meSetHtml.indexOf('data-k="search"') < 0);
ok("「检查更新」行还在", meSetHtml.indexOf('data-k="update"') >= 0);
ok("首页头像已删除", html.indexOf('id="avatarImg"') < 0 && html.indexOf('class="avatar"') < 0);
ok("首页不再引用 icon.png", html.indexOf('src="icon.png"') < 0);
ok("主题按钮还在", html.indexOf('id="themeBtn"') >= 0);
ok("学习统计按钮还在", html.indexOf("openFS('stat')") >= 0);

/* 9.6 空题库时首页不出现 NaN，且不再重复格式说明 */
const emptyStats = { total: 0, seen: 0, rate: 0 };
BANK.subjects = BANK.subjects.filter((s) => s.id !== TB);
BANK.questions = BANK.questions.filter((q) => q.subject !== TB);
W.goTab("home");
const homeEmpty = String(W.document.getElementById("tasks").innerHTML);
ok("删光题库后首页无 NaN", homeEmpty.indexOf("NaN") < 0);
ok("删光题库后首页给导入入口", homeEmpty.indexOf("openImport") >= 0);
ok("删光题库后统计为 0", W.stats("all").total === 0, W.stats("all").total);
const hiSubEl = W.document.getElementById("hiSub");
ok("空题库时问候语副标题被隐藏", hiSubEl.hidden === true);
ok("空题库时不再显示格式说明", String(hiSubEl.textContent).indexOf("支持 .docx") < 0,
   String(hiSubEl.textContent));
ok("空题库时标题仍是「先导入一个题库」",
   W.document.getElementById("hiName").textContent === "先导入一个题库",
   W.document.getElementById("hiName").textContent);
W.goTab("me");
ok("删光题库后「我的」页无 NaN",
   String(W.document.getElementById("meStat").innerHTML).indexOf("NaN") < 0);

/* ---------- 10. v1.0.4：今日待刷左滑删除 / 不列空库 / 题库改名 ---------- */
section("[10] v1.0.4：今日待刷左滑 / 过滤空库 / 改名");
const NB = "nb1";
const mkq = (sid, i, ans) => ({ id: sid + "-" + i, subject: sid, num: i + 1, pnum: i + 1,
  type: "single", stem: "甲乙"[i] + "题", options: [["A", "x"], ["B", "y"]], answer: [ans],
  ans_raw: ans, ref: "", explain: "", note: "", flag: "" });
BANK.subjects.push({ id: NB, name: "有题的库", file: "nb1.md", count: 2, custom: true });
BANK.questions.push(mkq(NB, 0, "A"), mkq(NB, 1, "B"));
BANK.subjects.push({ id: "empty1", name: "空库不该出现", file: "e.md", count: 0, custom: true });
W.goTab("home");
const taskHtml = String(W.document.getElementById("tasks").innerHTML);
ok("今日待刷列出有题的库", taskHtml.indexOf("有题的库") >= 0);
ok("今日待刷不列题库页里根本没有的空库", taskHtml.indexOf("空库不该出现") < 0, taskHtml);
ok("今日待刷每行带左滑面板", taskHtml.indexOf('class="swipe"') >= 0 && taskHtml.indexOf("swipe-act") >= 0);
ok("今日待刷面板里那格是删除", /swipe-act"[^>]*askDeleteBank/.test(taskHtml), taskHtml.slice(0, 160));
ok("左滑的面板前景是 .task 本身", taskHtml.indexOf('class="task"') >= 0);

let saved = null;
W.Android = { saveBank: (id, json) => { saved = [id, JSON.parse(json)]; return true; } };
W.askRenameBank(NB);
const sheetHtml = String(W.document.getElementById("sheet3").innerHTML);
ok("改名面板有输入框并预填当前名", sheetHtml.indexOf('class="rn-box"') >= 0
   && sheetHtml.indexOf('value="有题的库"') >= 0, sheetHtml.slice(0, 140));
W.renameBank(NB, "  改过的名字  ");
ok("改名去掉首尾空白并落到列表", W.subjectOf(NB).name === "改过的名字", W.subjectOf(NB).name);
ok("改名重写整包、id 不变、题数不少", saved && saved[0] === NB && saved[1].subject.id === NB
   && saved[1].questions.length === 2, saved && JSON.stringify([saved[0], saved[1].questions.length]));
ok("改名不动题目本身（答案原样）", saved && saved[1].questions[0].answer.join("") === "A");
saved = null;
W.Android = { saveBank: () => false };
W.renameBank(NB, "写盘失败的名字");
ok("写盘失败时名字回滚，不留界面对磁盘错的两本账",
   W.subjectOf(NB).name === "改过的名字", W.subjectOf(NB).name);
/* 这一段跑在 W.toast 恢复成真函数之后（check.mjs:567），所以直接读 #toast 的文本 */
ok("写盘失败当场提示",
   String(W.document.getElementById("toast").textContent).indexOf("保存失败") >= 0,
   String(W.document.getElementById("toast").textContent));
W.renameBank(NB, "   ");
ok("空名字不改", W.subjectOf(NB).name === "改过的名字");
delete W.Android;

/* 题库名要落进 value="…"：不转义就能从属性里跳出去 */
BANK.subjects.push({ id: "q1", name: 'x" onmouseover="boom()', file: "q.md", count: 1, custom: true });
BANK.questions.push(mkq("q1", 0, "A"));
W.askRenameBank("q1");
const injHtml = String(W.document.getElementById("sheet3").innerHTML);
ok("题库名里的引号被转义，不会从属性里跳出去",
   injHtml.indexOf("onmouseover=&quot;boom()") >= 0 && injHtml.indexOf('onmouseover="boom') < 0,
   injHtml.slice(0, 200));
BANK.subjects = BANK.subjects.filter((s) => s.id !== "q1");
BANK.questions = BANK.questions.filter((q) => q.subject !== "q1");

/* ---------- 11. v1.0.4：导入格式举例（手机上能打开、能整份下载） ----------
   用户 2026-10-20 的要求：把「详见仓库文档」换成能直接打开、并且能完整下载的举例。
   三条都得钉住：随包文件自己就能解析成题（举例不是摆设）、查看与下载同源、
   界面上不再出现手机打不开的仓库路径。 */
section("[11] v1.0.4：导入格式举例");
const SAMPLE_MD = path.join(ROOT, "app/src/main/assets/import-sample.md");
const JAVA = path.join(ROOT, "app/src/main/java/com/fan/shuatiku/MainActivity.java");
const sampleSrc = fs.readFileSync(SAMPLE_MD, "utf8");
const javaSrc = fs.readFileSync(JAVA, "utf8");
const toastTx = () => String(W.document.getElementById("toast").textContent);

ok("原生桥里的举例文件名就是随包那个",
   javaSrc.indexOf('SAMPLE_ASSET = "' + path.basename(SAMPLE_MD) + '"') >= 0, path.basename(SAMPLE_MD));

const ss = sandbox.parseBankText(sampleSrc);
ok("举例自身解析出 8 道题", ss.length === 8, "实际 " + ss.length);
const ssRep = sandbox.importReport(ss, ss.filter(sandbox.usableQ));
ok("举例里可判分 7 题", ssRep.usable === 7, "实际 " + ssRep.usable);
ok("举例演示 1 道无答案", ssRep.noans === 1, "实际 " + ssRep.noans);
ok("举例一道都没被静默丢掉", ssRep.dropped === 0, "实际 " + ssRep.dropped);
ok("举例三项相加等于总数", ssRep.usable + ssRep.noans + ssRep.dropped === ssRep.total,
   [ssRep.usable, ssRep.noans, ssRep.dropped, ssRep.total].join("+"));
const types = ss.map((q) => q.type);
ok("举例覆盖五种题型",
   ["single", "multi", "judge", "fill", "short"].every((t) => types.indexOf(t) >= 0), types.join(","));
ok("举例里多选题的答案是两个以上字母",
   types.indexOf("multi") >= 0 && ss[types.indexOf("multi")].answer.length >= 2,
   types.indexOf("multi") >= 0 ? ss[types.indexOf("multi")].answer.join("") : "没有多选题");
ok("举例里填空题带参考答案",
   types.indexOf("fill") >= 0 && !!ss[types.indexOf("fill")].ref,
   types.indexOf("fill") >= 0 ? String(ss[types.indexOf("fill")].ref) : "没有填空题");
const dirty = ss.filter((q) => /题目\s*[:：]|题干\s*[:：]|选项\s*[:：]|```/.test(q.stem)).map((q) => q.stem);
ok("题干里没有标签行或代码块围栏残留", dirty.length === 0, JSON.stringify(dirty));
const noAns = ss[ss.length - 1];
ok("举例最后一题就是那道无答案示范", noAns && noAns.answer.length === 0 && !sandbox.usableQ(noAns),
   JSON.stringify(noAns && noAns.answer));

W.openImport();
const impHtml = String(W.document.getElementById("sheet3").innerHTML);
ok("导入面板给「查看格式举例」入口", impHtml.indexOf('openFS(&quot;sample&quot;)') >= 0, impHtml.slice(0, 200));
ok("导入面板给「下载这份举例」按钮", impHtml.indexOf("saveSample()") >= 0);
ok("导入面板不再把用户支到仓库文档",
   impHtml.indexOf("docs/") < 0 && impHtml.indexOf("导入指南") < 0, impHtml.slice(0, 200));
W.closeTop();

let dlCalls = 0;
W.Android = {
  importSample: () => sampleSrc,
  saveImportSample: () => { dlCalls++; return "系统「下载」目录里的 GIme导入格式举例.md"; }
};
const fsHtml = () => String(W.document.getElementById("fs").innerHTML);
const PRE_OPEN = '<pre class="sample-box">';
const preBody = (h) => {
  const a = h.indexOf(PRE_OPEN), b = h.indexOf("</pre>");
  return a < 0 || b < 0 ? null : h.slice(a + PRE_OPEN.length, b);
};
W.openFS("sample");
ok("查看页标题是「导入格式举例」", fsHtml().indexOf("导入格式举例") >= 0);
const viewed = preBody(fsHtml());
ok("查看页给的是完整举例，不是截断摘要",
   viewed !== null && viewed.indexOf("第1题") >= 0 && viewed.indexOf(String(sampleSrc).trim().split("\n").pop()) >= 0,
   viewed === null ? "没有 sample-box 区块" : "少了结尾那题");
ok("查看页里也能直接下载", fsHtml().indexOf("saveSample()") >= 0);
W.saveSample();
ok("下载走原生桥且把存放位置说清楚",
   dlCalls === 1 && toastTx().indexOf("系统「下载」目录") >= 0, dlCalls + "/" + toastTx());
/* 「下载这份举例」按钮长在举例页里面，提示条若压在页面下面，用户点完什么都看不见。
   不点名具体浮层，直接要求 toast 是整份 CSS 里最高的一层。 */
const cssText = html.slice(html.indexOf("<style>"), html.indexOf("</style>"));
const zs = (cssText.match(/z-index:\s*\d+/g) || []).map((s) => parseInt(s.split(":")[1], 10));
const toastZ = parseInt((/\.toast\s*\{[^}]*?z-index:\s*(\d+)/.exec(cssText) || [])[1], 10);
ok("提示条压在全部浮层之上（举例页里点下载能看见反馈）",
   toastZ === Math.max.apply(null, zs), toastZ + " vs 最高 " + Math.max.apply(null, zs));
W.Android = { importSample: () => "第1题\n题目：<script>alert(1)</script>\n答案：A" };
W.openFS("sample");
ok("举例正文按文本展示，不会被当成 HTML 执行",
   fsHtml().indexOf("<script>alert(1)</script>") < 0 && fsHtml().indexOf("&lt;script&gt;") >= 0,
   String(preBody(fsHtml())));
W.closeTop();

delete W.Android;
W.openFS("sample");
const noBridge = fsHtml();
ok("读不到举例时给可照抄的写法，而不是仓库路径",
   noBridge.indexOf("docs/") < 0 && noBridge.indexOf("导入指南") < 0 && noBridge.indexOf("题号") >= 0,
   noBridge.slice(0, 200));
W.closeTop();
W.saveSample();
ok("没有原生桥时点下载只提示，不抛异常", toastTx().indexOf("请在手机上") >= 0, toastTx());
W.Android = { importSample: () => sampleSrc, saveImportSample: () => "" };
W.saveSample();
ok("存盘失败时改成教用户长按复制", toastTx().indexOf("长按") >= 0, toastTx());
delete W.Android;

/* ---------- 结果 ---------- */
console.log("\n" + "=".repeat(46));
if (fails.length) {
  console.log("失败 " + fails.length + " 项：");
  fails.forEach((f) => console.log("  - " + f));
  process.exit(1);
}
console.log("全部通过：" + pass + " 项");
