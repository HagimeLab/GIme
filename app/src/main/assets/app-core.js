"use strict";

/* ================= 数据与持久化 ================= */
var BANK = window.BANK || { subjects: [], questions: [] };
var LS = { p: "stk.progress.v2", s: "stk.settings.v2", j: "stk.session.v2", u: "stk.update.v1", c: "stk.chat.v1", r: "stk.resume.v1" };
var store = {
  read: function (k, d) { try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch (e) { return d; } },
  write: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
};
var prog = store.read(LS.p, {});
/* 主记录空、备份有货：说明上一次写盘把记录写没了，用备份救回来。
   合法的清空（清空做题记录 / 删掉最后一个题库）会连备份一起删，所以不会复活。 */
var LS_PROG_BAK = "stk.progress.bak";
function restoreProgFromBackup() {
  if (Object.keys(prog).length) return;
  var b = store.read(LS_PROG_BAK, null);
  if (b && Object.keys(b).length) prog = b;
}
restoreProgFromBackup();
var set = store.read(LS.s, { theme: "light", fscale: 100 });
/* 老版本可能把开关存成字符串 / 0 / 1（手改存档、早期迁移残留都出现过）。
   统一归一成布尔：只有明确关掉的值才算关，其余按默认开。
   归一之后 vibrate() 里的判断才有意义——否则 "false" 这种真值会被当成开启。 */
function asBool(v, dft) {
  if (v === true || v === 1 || v === "1" || v === "true" || v === "on") return true;
  if (v === false || v === 0 || v === "0" || v === "false" || v === "off") return false;
  return dft;
}
set.haptics = asBool(set.haptics, true);
set.autoExp = asBool(set.autoExp, true);
/* ================= T3 记忆：长期画像 + 会话摘要 =================
   画像按题库/进度现算，摘要由 chatSave 在历史超过 30 条时本地滚动压缩；
   两者都落 filesDir/memory/memory.json（Java MemoryStore），页面不另存一份。 */
var MEM = { profile: {}, session: {} };
var memSaveTimer = 0;
var LS_MEM = "stk.memory.v1";

function memoryCall(op, payload) {
  try {
    if (window.Android && Android.memoryCall) {
      return JSON.parse(Android.memoryCall(String(op || ""), JSON.stringify(payload || {})));
    }
  } catch (e) { }
  return null;
}
function memoryLoad() {
  var s = memoryCall("snapshot", {});
  if (s && !s.error) {
    MEM.profile = s.profile || {};
    MEM.session = s.session || {};
    return;
  }
  var local = store.read(LS_MEM, null);
  if (local && typeof local === "object") {
    MEM.profile = local.profile || {};
    MEM.session = local.session || {};
  }
}
function memorySaveSoon() {
  clearTimeout(memSaveTimer);
  memSaveTimer = setTimeout(function () { memorySaveNow(); }, 800);
}
function memorySaveNow() {
  MEM.profile = memoryProfile();
  var out = memoryCall("save", { profile: MEM.profile, session: MEM.session });
  if (!out || out.error) store.write(LS_MEM, { profile: MEM.profile, session: MEM.session });
}
function memDays() {
  var d = MEM.profile && MEM.profile.days;
  return d && typeof d === "object" ? d : {};
}
function memDayKey() {
  var d = new Date(), p2 = function (n) { return n < 10 ? "0" + n : "" + n; };
  return d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate());
}
/* 每答一题记一次；只留最近 30 天，避免无限长 */
function memDay(ok) {
  var days = memDays(), k = memDayKey();
  var x = days[k] || { n: 0, w: 0 };
  x.n++;
  if (!ok) x.w++;
  days[k] = x;
  var keys = Object.keys(days).sort();
  while (keys.length > 30) { delete days[keys.shift()]; }
  MEM.profile.days = days;
  memorySaveSoon();
}
function memoryProfile() {
  var by = {}, weak = null, i;
  BANK.questions.forEach(function (q) {
    var p = prog[q.id] || { n: 0, w: 0 };
    var t = by[q.type] || (by[q.type] = { n: 0, w: 0 });
    t.n += p.n || 0;
    t.w += p.w || 0;
  });
  Object.keys(by).forEach(function (t) {
    var x = by[t];
    if (!x.w) return;
    var acc = x.n ? Math.round((x.n - x.w) * 100 / x.n) : 0;
    if (!weak || acc < weak.acc || (acc === weak.acc && x.w > weak.w)) {
      weak = { type: t, name: typeName(t), n: x.n, w: x.w, acc: acc };
    }
  });
  var days = memDays(), keys = Object.keys(days).sort(), trend = { n: 0, w: 0, rate: null };
  for (i = Math.max(0, keys.length - 7); i < keys.length; i++) {
    var d = days[keys[i]] || {};
    trend.n += d.n || 0;
    trend.w += d.w || 0;
  }
  if (trend.n) trend.rate = Math.round((trend.n - trend.w) * 100 / trend.n);
  var plan = null;
  if (typeof PLAN !== "undefined" && PLAN.list) {
    for (i = 0; i < PLAN.list.length; i++) {
      var p = PLAN.list[i];
      if (!p.enabled) continue;
      var td = p.today || [], done = 0;
      for (var j = 0; j < td.length; j++) if (td[j].done) done++;
      plan = { id: p.id, title: p.title, pct: p.pct || 0,
               todayDone: done, todayTotal: td.length };
      break;
    }
  }
  return { weakest: weak, trend7: trend, plan: plan,
           prefs: { theme: set.theme === "dark" ? "dark" : "light", fscale: set.fscale || 100,
                    autoExp: set.autoExp !== false, haptics: set.haptics !== false },
           days: days, updatedAt: Date.now() };
}
function memorySummary() {
  return MEM.session && MEM.session.summary ? String(MEM.session.summary) : "";
}
function memoryClear() {
  memoryCall("clear", {});
  MEM = { profile: {}, session: {} };
  try { store.write(LS_MEM, MEM); } catch (e) { }
  closeTop();
  toast("记忆已清除");
  renderAll();
}
function memoryClearAsk() {
  glass({ title: "清除记忆", desc: "画像与会话摘要会被删掉；题库、进度、方案不受影响。",
    no: ["取消", null], ok: ["清除", function () { memoryClear(); }] });
}
function openMemory() {
  if (typeof planLoad === "function") planLoad();
  var p = memoryProfile();
  var weak = p.weakest ? esc(p.weakest.name) + "（正确率 " + p.weakest.acc + "%）" : "暂无";
  var trend = p.trend7 && p.trend7.n ? p.trend7.rate + "%（" + p.trend7.n + " 次）" : "暂无";
  var plan = p.plan ? esc(p.plan.title) + " · " + p.plan.pct + "% · 今天 " +
    p.plan.todayDone + "/" + p.plan.todayTotal : "无";
  var summary = memorySummary();
  var sh = document.getElementById("sheet3");
  sh.innerHTML = '<div class="handle"></div><h2>记忆</h2>' +
    '<div class="card pad" style="font-size:.8125rem;line-height:1.9">' +
      "<div>最弱题型：" + weak + "</div>" +
      "<div>近 7 天正确率：" + trend + "</div>" +
      "<div>进行中方案：" + plan + "</div>" +
      "<div>偏好：主题 " + (p.prefs.theme === "dark" ? "深色" : "浅色") + " · 字号 " + p.prefs.fscale + "%</div>" +
    "</div>" +
    '<div class="sec-title"><b>会话摘要</b><span>超过 30 条后自动压缩</span></div>' +
    '<div class="card pad" style="font-size:.8125rem;line-height:1.8">' +
      (summary ? esc(summary) : "还没有摘要") + "</div>" +
    '<button class="sheet-act" onclick="memoryClearAsk()">清除记忆</button>';
  openLayer(sh);
}

/* 旧版本可以「隐藏内置题库」，那套数据已经没有对应题库了。
   这里只做兼容清理：不再读它，但也不动它，免得降级回旧版时用户发现隐藏状态被重置 */
if (set.hidden && typeof set.hidden !== "object") set.hidden = {};
var S = store.read(LS.j, null);
var openLayers = [];
var curTab = "home";
var foldOpen = true;

var byId = {};
BANK.questions.forEach(function (q) { byId[q.id] = q; });

function P(id) { if (!prog[id]) prog[id] = { n: 0, w: 0, last: -1, b: 0, t: 0, x: 0 }; return prog[id]; }
/* 错题本是你自己维护的清单：答错过就留下，再答对也不会自动移出，只有手动/批量移出才消失。
   p.in 一旦被显式写过就以它为准；老数据没写过 p.in，退化成"答错过就算在里头" */
function inWrong(x) {
  var p = prog[x.id];
  if (!p) return false;
  if (p.in !== undefined) return p.in === 1;
  return p.w > 0;
}
function subjQs(sid, tf) {
  return BANK.questions.filter(function (q) {
    return (sid === "all" || q.subject === sid) && (!tf || q.type === tf);
  });
}
function subjName(sid) {
  if (sid === "all") return "全部题库";
  for (var i = 0; i < BANK.subjects.length; i++) if (BANK.subjects[i].id === sid) return BANK.subjects[i].name;
  return sid;
}
function esc(s) {
  /* 引号也要转义：这些字符串不只落在正文里，还会落进 value="…" 这类属性里，
     而题库名来自用户自己选的文件名，不转义就能从属性里跳出去 */
  return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
    return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
  });
}
function shuffle(a) {
  for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = a[i]; a[i] = a[j]; a[j] = t; }
  return a;
}
function save() { store.write(LS.j, S); saveProg(); store.write(LS.s, set); saveResume(); }
/* 做题记录「凭空消失」只有一个可见形态：一次空的 prog 覆盖了磁盘上有记录的 prog。
   合法的空只有两种——用户点「清空做题记录」，或删掉最后一个有记录的题库（走 progMayEmpty）。
   其余情况宁可这次不写，也不能把记录抹掉：先把现场记进 stk.diag，再给用户一句提示。 */
var progMayEmpty = false;
function progCount(o) { try { return Object.keys(o || {}).length; } catch (e) { return -1; } }
function diag(kind, n) {
  try {
    var d = store.read("stk.diag", []);
    d.push({ k: kind, n: n, at: Date.now() });
    store.write("stk.diag", d.slice(-10));
  } catch (e) { /* 诊断自己不能成为新的故障源 */ }
}
function saveProg() {
  var next = progCount(prog), prev = 0, old = null;
  try {
    var raw = localStorage.getItem(LS.p);
    if (raw) { old = JSON.parse(raw); prev = progCount(old); }
  } catch (e) {
    /* 旧值本身坏了：不能把"读不出来"当成"原来是空的"，这时照常写新的 */
    prev = -1;
  }
  if (!next && prev > 0 && !progMayEmpty) {
    diag("prog-empty-blocked", prev);
    toast("做题记录这次没有写入：新进度为空，已保留原有 " + prev + " 条记录");
    return;
  }
  /* 写之前把上一份「有记录」的版本留成备份；真要清空时反而要把备份删掉，
     否则下次开机会被 restoreProgFromBackup 复活成"删不掉"的记录 */
  if (!next) { try { localStorage.removeItem(LS_PROG_BAK); } catch (e) {} }
  else if (prev > 0) store.write(LS_PROG_BAK, old);
  progMayEmpty = false;
  store.write(LS.p, prog);
}
window.saveProgress = save;

/* ================= 中途退出续刷 =================
   会话 S 只记「当前打开的那一组」，换一个题库就会被覆盖；所以退出时另存一份按题库
   归档的快照（order / 当前题 / 已选 / 已判 / 主观题自评 / 输入中的文字）。
   「最近练习」和题库页的「继续刷」都从这里恢复，两条入口共用同一份进度。
   快照只收顺序练习：考试有交卷/放弃语义，错题/收藏/背题每次都是重新抽的清单，
   恢复旧的一组反而会跳过用户这次想练的范围。 */
var resume = store.read(LS.r, {});
function resumeKey(sid, tf) { return sid + "|" + (tf || ""); }
function practiceLeft(s) {
  var left = 0;
  s.order.forEach(function (id) {
    var x = byId[id];
    if (!x) return;
    if (isSubjective(x.type)) { if (!s.sub || !s.sub[id]) left++; }
    else if (!s.graded || s.graded[id] === undefined) left++;
  });
  return left;
}
function dropResume(key) {
  if (!resume[key]) return;
  delete resume[key];
  store.write(LS.r, resume);
}
function saveResume() {
  if (!S || S.mode !== "practice" || !S.order || !S.order.length) return;
  var key = resumeKey(S.subject, S.tf);
  if (!practiceLeft(S)) { dropResume(key); return; }
  resume[key] = { sid: S.subject, tf: S.tf || "", order: S.order.slice(), idx: S.idx,
                  picks: S.picks || {}, graded: S.graded || {}, text: S.text || {},
                  sub: S.sub || {}, refShut: S.refShut || {},
                  at: Date.now() };
  store.write(LS.r, resume);
}
/* 先找同一筛选条件的；没有再看这个题库最近退出的那一组。
   题库页的「继续刷」因此总能回到用户真正的退出位置，而不只是「第一道没刷过的」。 */
function latestResume(sid) {
  var best = null;
  Object.keys(resume).forEach(function (k) {
    var r = resume[k];
    if (r.sid !== sid) return;
    if (!best || (r.at || 0) > (best.at || 0)) best = r;
  });
  return best;
}
function findResume(sid, tf) {
  /* 选了题型就是明确要那一类，不能拿别的题型的旧会话顶替；
     「整个题库」这一档才回退到这个题库最近退出的一组。 */
  if (tf) return resume[resumeKey(sid, tf)] || null;
  return latestResume(sid);
}
function validResume(r) {
  if (!r || !r.order || !r.order.length) return false;
  if (typeof r.idx !== "number" || r.idx < 0 || r.idx >= r.order.length) return false;
  for (var i = 0; i < r.order.length; i++) if (!byId[r.order[i]]) return false;
  return true;
}
function restorePractice(r, from) {
  if (!validResume(r)) {
    Object.keys(resume).forEach(function (k) { if (resume[k] === r) delete resume[k]; });
    store.write(LS.r, resume);
    return false;
  }
  S = { subject: r.sid, mode: "practice", tf: r.tf || "", order: r.order.slice(), idx: r.idx,
        title: "", state: "",
        picks: r.picks || {}, graded: r.graded || {}, text: r.text || {},
        sub: r.sub || {}, refShut: r.refShut || {},
        started: Date.now(), dur: 0 };
  save();
  render();
  shAbort();
  SH = (from && !shRM()) ? (from === "cat" ? { tf: r.tf } : { sid: r.sid }) : null;
  if (SH && !shCard()) SH = null;
  if (SH) shOpen(); else shPlain();
  return true;
}
function resumeRecent(sid) {
  if (!subjectOf(sid)) return;
  var r = findResume(sid, "");
  if (r && restorePractice(r, null)) return;
  startSession("practice", sid, "", null);
}

/* ================= 统计 ================= */
function stats(sid, tf) {
  var qs = subjQs(sid, tf), seen = 0, mark = 0, lastT = 0;
  /* 正确率只统计客观题：主观题是自己核对出来的「掌握 / 待复习」，
     把它算进正确率等于给了个假分数 */
  var rated = 0, right = 0;
  qs.forEach(function (q) {
    var p = prog[q.id];
    if (!p) return;
    if (p.n > 0) seen++;
    if (isObjective(q.type) && p.n > 0) { rated++; if (p.last === 1) right++; }
    if (p.b) mark++;
    if (p.t > lastT) lastT = p.t;
  });
  var wrong = qs.filter(inWrong).length;
  return { total: qs.length, seen: seen, rated: rated, right: right, wrong: wrong, mark: mark, lastT: lastT,
           /* 完成率 = 已刷 / 总数：题库卡片与今日待刷用的是这一格，别和 rate（答对率）混用 */
           done: qs.length ? Math.round(seen / qs.length * 100) : 0,
           rate: rated ? Math.round(right / rated * 100) : 0 };
}

/* ================= 会话 ================= */
/* 六类卡片：前五种是题型，最后一张是组卷入口。
   scored=false 的主观题不参与自动判分，只在练习里给参考答案和自我核对，
   也永远不进模拟考试——没有可靠判分规则就不能假装它是客观题。 */
var TYPES = [
  { tf: "single", name: "单选题", scored: true,
    ico: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="2.6" fill="currentColor" stroke="none"/>' },
  { tf: "multi", name: "多选题", scored: true,
    ico: '<rect x="4.5" y="4.5" width="15" height="15" rx="4.5"/><path d="M8.6 12.2l2.4 2.4 4.4-4.8"/>' },
  { tf: "judge", name: "判断题", scored: true,
    ico: '<circle cx="12" cy="12" r="8"/><path d="M9.2 12.2l2 2 3.6-4.2"/>' },
  { tf: "fill", name: "填空题", scored: false,
    ico: '<path d="M4 7h16M4 12h10M4 17h6"/><path d="M17.5 14.5v6M14.5 17.5h6"/>' },
  { tf: "short", name: "解答题", scored: false,
    ico: '<rect x="4.5" y="3.5" width="15" height="17" rx="4"/><path d="M8.5 8.5h7M8.5 12.5h7M8.5 16.5h3"/>' },
  { tf: "exam", name: "真题套卷", scored: true,
    ico: '<rect x="4.5" y="3.5" width="15" height="17" rx="4"/><path d="M8.5 8.5h7M8.5 12.5h7M8.5 16.5h4"/>' }
];
var OBJECTIVE = { single: 1, multi: 1, judge: 1 };
function isObjective(t) { return OBJECTIVE[t] === 1; }
function isSubjective(t) { return t === "fill" || t === "short"; }

function examN() {
  paperInit();
  return set.paper.mode === "types" ? paperOrder().length : Math.min(set.paper.total, paperAvail());
}
function examMin() { paperInit(); return set.paper.min; }
function paperTitle() {
  /* 删掉的题库不进卷名：组卷选择里若还挂着它的 id，卷名会变成一串原始 id */
  var ids = Object.keys(set.paper.subs).filter(function (id) { return subjectOf(id); });
  if (!ids.length) return "组合卷";
  if (ids.length === paperBanks().length) return "全部题库";
  return ids.map(function (i) { return subjName(i); }).join(" + ");
}

/* ================= 卡片 → 答题页 共享元素转场 =================
   一套 FLIP 走天下：First 量起点矩形 → 幽灵盒子直接摆成全屏、用 transform 反算回起点（Invert）
   → Play 把变换去掉。两类入口只是"起点卡片"和"哪个文字飞到哪"不同，机制共用。
   圆角要按 X/Y 两轴分别除以当时的缩放：卡片和全屏的宽高比差得远，非等比缩放会把圆角拉成椭圆。
   Chrome 80 的 element.animate() 永远不触发 onfinish，所以时序全部用 setTimeout 串，
   并且每个动画都留引用，中断时 cancel 掉——否则 fill:"forwards" 会把终值钉在元素上。 */
var SH_IN = 350, SH_FLY = 300, SH_LAND = 200, SH_OUT = 450, SH_OUT_FADE = 120;
/* 返回的尾段：幽灵缩到终点附近后，和真卡片交叉淡入淡出的起点。
   按 SH_EASE 算，走到一半时幽灵已经是目标尺寸的 98%，剩下的位移肉眼看不出来，
   从这里换层最稳；再早就得露出比卡片小一圈的幽灵边。 */
var SH_OUT_LAND = 225;
var SH_EASE = "cubic-bezier(0.16,1,0.3,1)";
var SH = null, SH_nodes = [], SH_back = [], SH_anim = [], SH_timer = [];

function shRM() { return !!(window.matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches); }
function shTok(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }
function shAt(ms, fn) { SH_timer.push(setTimeout(fn, ms)); }
function shAnim(el, frames, dur, delay) {
  /* fill 必须是 both：带 delay 的动画在等待期不套用起始帧，
     只写 forwards 的话元素会先按 CSS 原样亮出来、再突然跳到 opacity:0 起步，
     错峰越靠后闪得越明显——就是"卡片莫名抖一下"。 */
  var a = el.animate(frames, { duration: dur, easing: SH_EASE, fill: "both", delay: delay || 0 });
  SH_anim.push(a);
  return a;
}
function shTrack(node) { SH_nodes.push(node); return node; }
function shHold(fn) { SH_back.push(fn); }
/* 中断/收尾都走这里：还原被我们藏起来的卡片、撤掉动画、删掉临时层，一样都不留 */
function shAbort() {
  var i;
  for (i = 0; i < SH_timer.length; i++) clearTimeout(SH_timer[i]);
  for (i = 0; i < SH_back.length; i++) { try { SH_back[i](); } catch (e) {} }
  for (i = 0; i < SH_anim.length; i++) { try { SH_anim[i].cancel(); } catch (e) {} }
  for (i = 0; i < SH_nodes.length; i++) if (SH_nodes[i].parentNode) SH_nodes[i].parentNode.removeChild(SH_nodes[i]);
  SH_timer = []; SH_back = []; SH_anim = []; SH_nodes = [];
}

/* 起点卡片：入口 A 按 data-tf、入口 B 按 data-sid 找回用户真正点的那一张。
   退出时列表已被 renderAll 重建，所以每次都重查重量。
   用遍历而不是 [data-x="…"]：题库 id 来自用户导入的文件，不保证能当 CSS 标识符。 */
function shCard() {
  if (!SH) return null;
  var cat = !!SH.tf, want = cat ? SH.tf : SH.sid;
  var wraps = document.querySelectorAll(cat ? "#cats .cat-wrap" : "#bankList .swipe");
  for (var i = 0; i < wraps.length; i++) {
    if (wraps[i].getAttribute(cat ? "data-tf" : "data-sid") === want)
      return wraps[i].querySelector(cat ? ".cat" : ".card.pad");
  }
  return null;
}

function shRadius(el) { return parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0; }

/* 幽灵：盒子固定为全屏，靠 transform 落在卡片矩形上；opening=true 则卡片→全屏。
   刻意不给它任何 box-shadow——真机量过：在近全屏的缩放层上挂模糊阴影（不管是动画还是静态），
   每次缩放都要重新光栅化，一次转场掉 4–9 帧、最差 54ms；去掉后 0 帧超 33ms、max 17.6ms。
   幽灵铺满时外阴影本来就在视口外，所以"淡出"这个观感不需要真的去画它。 */
function shGhost(r, rad, opening) {
  var W = window.innerWidth, H = window.innerHeight;
  var A = opening ? r : { left: 0, top: 0, width: W, height: H };
  var B = opening ? { left: 0, top: 0, width: W, height: H } : r;
  var bg0 = opening ? shTok("--raise") : shTok("--paper");
  var bg1 = opening ? shTok("--paper") : shTok("--raise");
  var kf = [], n = 8;
  for (var i = 0; i <= n; i++) {
    var p = i / n;
    var w = A.width + (B.width - A.width) * p, h = A.height + (B.height - A.height) * p;
    var sx = w / W, sy = h / H, rr = rad * (opening ? 1 - p : p);
    var f = {
      offset: p,
      transform: "translate(" + (A.left + (B.left - A.left) * p) + "px,"
                         + (A.top + (B.top - A.top) * p) + "px) scale(" + sx + "," + sy + ")",
      borderRadius: (rr / sx) + "px / " + (rr / sy) + "px"
    };
    if (i === 0) { f.backgroundColor = bg0; }
    if (i === n) { f.backgroundColor = bg1; }
    kf.push(f);
  }
  var g = document.createElement("div");
  g.className = "share-ghost";
  g.style.width = W + "px";
  g.style.height = H + "px";
  document.body.appendChild(shTrack(g));
  shAnim(g, kf, opening ? SH_IN : SH_OUT);
  return g;
}

/* 文字飞进 .q-head：克隆体按"终点"排版摆在终点矩形，位置用 transform 平移回去，
   字号则真的动画 font-size。
   不用 scale 模拟字号变化：缩放是把文字按一个尺寸光栅化后再放大/缩小，逐帧取整会让
   字形边界来回跳——矩形一动不动，看着却是"卡片在抖"。规格要的"字号渐变"本来也是这个意思。
   返回时把终点换成原卡片上的文字，方向和时长由调用方给（dur 缺省走进入的 300ms）；
   text 用来只飞题库名——#qSub 里还拼着"· 1 / 320"，整段飞回卡片会把进度也带进去。 */
function shFlyLabel(src, dst, dur, text) {
  if (!src || !dst) return;
  var a = src.getBoundingClientRect(), b = dst.getBoundingClientRect();
  var cs = getComputedStyle(dst), ss = getComputedStyle(src);
  var el = document.createElement("div");
  el.className = "share-fly";
  el.textContent = text == null ? src.textContent : text;
  el.style.left = b.left + "px";
  el.style.top = b.top + "px";
  el.style.width = b.width + "px";
  el.style.fontFamily = cs.fontFamily;
  el.style.fontWeight = cs.fontWeight;
  el.style.color = cs.color;
  el.style.textAlign = cs.textAlign;
  document.body.appendChild(shTrack(el));
  var dx = a.left + a.width / 2 - b.left - b.width / 2;
  var dy = a.top + a.height / 2 - b.top - b.height / 2;
  shAnim(el, [
    { transform: "translate(" + dx + "px," + dy + "px)", fontSize: ss.fontSize, lineHeight: ss.lineHeight },
    { transform: "translate(0,0)", fontSize: cs.fontSize, lineHeight: cs.lineHeight }
  ], dur || SH_FLY);
}

/* 次要元素"随容器放大淡出"：幽灵是盖在列表上的纯色矩形，原元素开场就被压住，
   所以克隆一份到飞行层上让它当着用户的面消失，而不是凭空没了。cloneNode 保住原有样式。
   只用于 HTML 元素：内联 <svg> 的 className 是只读对象，改它会写坏 class。 */
function shRider(el, dy, dur) {
  if (!el) return;
  var r = el.getBoundingClientRect();
  var c = el.cloneNode(true);
  c.setAttribute("class", (el.getAttribute("class") ? el.getAttribute("class") + " " : "") + "share-fly");
  c.style.position = "fixed";
  c.style.left = r.left + "px";
  c.style.top = r.top + "px";
  c.style.width = r.width + "px";
  c.style.height = r.height + "px";
  c.style.margin = "0";
  document.body.appendChild(shTrack(c));
  shAnim(c, [{ opacity: 1, transform: "translateY(0)" },
             { opacity: 0, transform: "translateY(" + (dy || 0) + "px)" }], dur || 220);
}

function shOpen() {
  var card = shCard();
  if (!card) { shPlain(); return; }
  var r = card.getBoundingClientRect(), rad = shRadius(card);
  /* 矩形塌成 0（祖先被 display:none 藏掉）时，圆角补偿会除零，
     Chrome 直接把那一帧判为非法整帧丢弃，转场会退化成硬切 —— 不如老老实实走淡入 */
  if (!r.width || !r.height) { SH = null; shPlain(); return; }
  card.style.opacity = "0";
  shHold(function () { card.style.opacity = ""; });
  shGhost(r, rad, true);
  if (SH.tf) {
    /* 入口 A：题型名飞进大标题；数量就地淡出，语义由进度条接替；
       图标不单独处理——它就在卡片矩形上，幽灵一盖住就是"直接淡出" */
    shFlyLabel(card.querySelector("b"), document.getElementById("qTitle"));
    shRider(card.querySelector("i"));
  } else {
    /* 入口 B：题库名飞进副标题，大标题由 render() 填好的题型名自然浮出，不飞 */
    shFlyLabel(card.querySelector(".bk-name"), document.getElementById("qSub"));
    shRider(card.querySelector(".bk-pct"));
    shRider(card.querySelector(".bk-sub"));
    shRider(card.querySelector(".bk-acts"), 8);   /* 三个按钮整体下滑 8px 淡出 */
  }
  shAt(SH_IN, shLand);
}

/* 落位：幽灵铺满那一帧 #quiz 上位。两者此刻底色相同，所以关掉过渡直接换层看不出来。
   大标题不淡入——飞行标签刚停在同一个位置，再淡一次就是闪。 */
function shLand() {
  var quiz = document.getElementById("quiz");
  quiz.classList.add("snap", "on");
  shAbort();
  shContent();
  shAt(SH_LAND + 300, function () { quiz.classList.remove("snap"); });
}

/* 没有起点卡片的入口（背题/错题本/收藏/交卷回看）：走 #quiz 自带的 180ms 淡入，同样不硬切 */
function shPlain() {
  document.getElementById("quiz").classList.add("on");
  if (!shRM()) shContent();
}

function shUp(el, delay) {
  if (el) shAnim(el, [{ opacity: 0, transform: "translateY(10px)" },
                      { opacity: 1, transform: "translateY(0)" }], 180, delay);
}

function shContent() {
  var quiz = document.getElementById("quiz");
  shUp(quiz.querySelector(".track"), 40);
  var bar = document.getElementById("qBar");
  if (bar) {                                   /* 进度条从 0 长到当前进度 */
    var w = bar.style.width;
    bar.style.transition = "none";
    bar.style.width = "0";
    void bar.offsetWidth;
    bar.style.transition = "";
    bar.style.width = w;
  }
  shUp(quiz.querySelector(".stem-card"), 80);
  var opts = quiz.querySelectorAll("#qbody .opt");
  if (opts.length) {
    for (var i = 0; i < opts.length; i++) shUp(opts[i], 120 + i * 40);   /* ABCD 错峰 40ms */
  } else {
    shUp(quiz.querySelector(".q-input"), 120);
  }
  shUp(quiz.querySelector(".q-foot"), 160);
}

/* 返回：内容先淡出让位，幽灵再从全屏缩回用户实际点的那张卡片，比进入略快 */
function shClose() {
  if (shRM() || !SH) return false;
  var quiz = document.getElementById("quiz");
  var card = shCard();
  if (!card) { SH = null; return false; }
  /* 还在飞就按了返回：这一趟压根没落地，撤干净停在列表就行 */
  if (!quiz.classList.contains("on")) { SH = null; shAbort(); return true; }
  var rad = shRadius(card), r = card.getBoundingClientRect();
  if (!r.width || !r.height) { SH = null; return false; }
  /* 幽灵在 t=0 就建好开始缩，内容淡出与它并行。原来先淡 150ms 再放手，
     那 150ms 屏上什么都不动 —— 就是"退出后约 0.1 秒才看到缩回"。
     幽灵夹在 #quiz 底下，淡出这一段正好把它盖住，不会提前露出缩小的矩形。 */
  card.style.opacity = "0";
  shHold(function () { card.style.opacity = ""; });
  var ghost = shGhost(r, rad, false);
  shAnim(quiz, [{ opacity: 1 }, { opacity: 0 }], SH_OUT_FADE);
  var labSel = SH.tf ? "b" : ".bk-name";
  var labSrc = document.getElementById(SH.tf ? "qTitle" : "qSub");
  var labTx = SH.tf ? null : (S ? (S.title || subjName(S.subject)) : null);
  var c2 = null, lab = null;
  /* 幽灵缩到终点附近（SH_OUT_LAND 时已到目标尺寸的 98%）再和真卡片交叉淡入。
     之前幽灵全程是不透明纯色板：卡片矩形从返回第一帧空到第 450ms，内容要等
     幽灵移除才出现——用户报的"卡片空白约 0.5 秒"就是这一段。同一点把真卡片
     淡进来、幽灵同步淡出，两者背景同色，收尾重合，移除幽灵那一帧看不出来。
     阴影跟着卡片一起淡入，正好接上"圆角/背景/阴影复原"这一步。
     计时按整段转场的绝对时间挂，不套在淡出回调里面：回调晚到一点，
     交叉淡入也得在 SH_OUT 之前收完，不然幽灵会在半透明时被硬删。 */
  shAt(SH_OUT_LAND, function () {
    if (!c2) return;
    shAnim(ghost, [{ opacity: 1 }, { opacity: 0 }], SH_OUT - SH_OUT_LAND);
    shAnim(c2, [{ opacity: 0 }, { opacity: 1 }], SH_OUT - SH_OUT_LAND);
  });
  shAt(SH_OUT_FADE, function () {
    quiz.classList.remove("on", "snap");
    renderAll();
    /* 题库页的堆叠样式原本是下一帧才补的。这里量完卡片位置、造好飞行标签之后，
       中途再被 stackCards 改一次 transform 就对不上了，所以先同步补一次
       （rAF 那一趟再跑一遍是幂等的）。 */
    if (curTab === "bank") stackCards();
    c2 = shCard();
    if (!c2) return;
    /* renderAll 会重建列表，新节点得再藏一次，否则它会隔着幽灵露出来 */
    c2.style.opacity = "0";
    shHold(function () { c2.style.opacity = ""; });
    /* 卡片底下压着左滑露出的「改名/删除」面板。卡片淡入的这 225ms 是半透明的，
       面板会整块透出来——题库页返回时右侧闪一下就是它。给卡片垫一层同色底、
       面板先藏掉，淡入的只剩卡片内容；收尾随 shAbort 一起还原。 */
    var host = c2.parentNode;
    if (host && host.classList && host.classList.contains("swipe")) {
      host.style.background = "var(--raise)";
      host.style.borderRadius = "var(--r-card)";
      shHold(function () { host.style.background = ""; host.style.borderRadius = ""; });
      var acts = host.querySelector(".swipe-acts");
      if (acts) {
        acts.style.opacity = "0";
        shHold(function () { acts.style.opacity = ""; });
      }
    }
    lab = c2.querySelector(labSel);
    if (lab) {
      /* 这枚文字由飞行标签去落位；卡片同时淡入的话会在落点看到两个一样的字 */
      lab.style.visibility = "hidden";
      shHold(function () { lab.style.visibility = ""; });
      shFlyLabel(labSrc, lab, SH_OUT - SH_OUT_FADE, labTx);
    }
  });
  shAt(SH_OUT, function () { SH = null; shAbort(); });
  return true;
}

function startSession(mode, sid, tf, from) {
  /* 顺序练习接着上次退出那一题继续：题库页「继续刷」、首页待刷、最近练习
     都走这里，和退出时自动保存的是同一份快照。 */
  if (mode === "practice") {
    var r0 = findResume(sid, tf);
    if (r0 && restorePractice(r0, from)) return;
  }
  var pool = subjQs(sid, tf === "exam" ? null : tf);
  if (mode === "wrong") {
    pool = pool.filter(inWrong);
    if (!pool.length) { toast("错题本是空的"); return; }
  }
  if (mode === "star") {
    pool = pool.filter(function (q) { return prog[q.id] && prog[q.id].b; });
    if (!pool.length) { toast("还没有收藏题目"); return; }
  }
  var order = mode === "exam" ? paperOrder() : pool.map(function (q) { return q.id; });
  if (mode === "random" || mode === "wrong") shuffle(order);
  if (!order.length) {
    toast(tf && isSubjective(tf) ? "这个题库里没有" + typeName(tf) : "这个分类下没有题目");
    return;
  }

  var idx = 0;
  if (mode === "practice") {                       // 顺序练习：停在第一道没刷过的题
    for (var i = 0; i < order.length; i++) {
      if (!(prog[order[i]] && prog[order[i]].n > 0)) { idx = i; break; }
      if (i === order.length - 1) idx = 0;
    }
  }
  S = { subject: sid, mode: mode, tf: tf || "", order: order, idx: idx,
        title: mode === "exam" ? paperTitle() : "",
        state: mode === "exam" ? "running" : "",
        picks: {}, graded: {}, text: {}, self: {}, sub: {},
        started: Date.now(), dur: mode === "exam" ? examMin() * 60 : 0 };
  save();
  render();
  /* 上面任何一条数据检查不通过都会先 toast + return，幽灵压根不会创建，所以不存在残留 */
  shAbort();
  SH = (from && !shRM()) ? (from === "cat" ? { tf: tf } : { sid: sid }) : null;
  if (SH && !shCard()) SH = null;
  if (SH) shOpen(); else shPlain();
}

function q() { return byId[S.order[S.idx]]; }
function isExam() { return S && S.mode === "exam"; }
/* 考试状态：running 答题中 → submitting 交卷中 → submitted 已交卷 / abandoned 已放弃。
   交卷和放弃都要停表，且只认第一次，重复调用直接返回，免得一次操作出两份成绩。 */
function examDone() { return S && S.mode === "exam" && (S.state === "submitted" || S.state === "abandoned"); }
function isRecite() { return S && S.mode === "recite"; }
function noAns(cur) { return !cur.answer || !cur.answer.length; }
/* 主观题没有「答案字母」，参考答案单独放 ref 字段 */
function isSubjQ(cur) { return !!cur && isSubjective(cur.type); }
function typeName(t) {
  return { single: "单选题", multi: "多选题", judge: "判断题", fill: "填空题", short: "解答题" }[t] || "题目";
}
/* 列表里展示的「答案」：客观题拼字母，主观题给参考答案首段，没有就留空 */
function answerText(x) {
  if (!x) return "";
  if (isSubjective(x.type)) {
    var r = String(x.ref || "").trim();
    return r ? r.slice(0, 40) + (r.length > 40 ? "…" : "") : "未提供";
  }
  return (x.answer || []).join("");
}

function exitQuiz() {
  /* 答题页左上角那个返回箭头也走这里，所以考试中必须同样拦一道，
     否则绕过返回键就能把计时丢在后台 */
  if (isExam() && !examDone()) { askExitExam(); return; }
  if (!isExam()) save();
  if (shClose()) return;                       /* 有起点卡片：由它负责让位与 renderAll */
  document.getElementById("quiz").classList.remove("on", "snap");
  shAbort();
  renderAll();
}

/* ================= 答题页渲染 ================= */
function render() {
  if (!S || !S.order || !S.order.length || !S.order[S.idx]) return;
  var cur = q();
  document.getElementById("qTitle").textContent = isExam() ? "模拟考试" : typeName(cur.type);
  document.getElementById("qSub").textContent = (S.title || subjName(S.subject)) + " · " + (S.idx + 1) + " / " + S.order.length;
  document.getElementById("qBar").style.width = ((S.idx + 1) / S.order.length * 100) + "%";

  var subj = isSubjQ(cur);
  var picks = S.picks[cur.id] || [];
  /* 主观题没有「判分」，只有「已核对 / 未核对」；作答内容单独存在 S.text 里 */
  var text = (S.text && S.text[cur.id]) || "";
  /* 主观题只分「作答过 / 没作答」：自评那套已撤掉，不判对错 */
  var submitted = subj && (isRecite() || !!(S.sub && S.sub[cur.id]));
  var done = subj ? submitted : ((isExam() || noAns(cur)) ? false : S.graded[cur.id] !== undefined);
  var reveal = subj ? (submitted && !isExam()) : (done || (isRecite() && !noAns(cur)));

  var tagText = subj
    ? (submitted && !isRecite() ? "已作答" : typeName(cur.type))
    : (reveal ? (S.graded[cur.id] === 1 ? "回答正确" : S.graded[cur.id] === 0 ? "回答错误" : typeName(cur.type)) : typeName(cur.type));
  var h = '<div class="stem-card"><div class="tagrow">' +
            '<span class="tag' + ((!subj && reveal && S.graded[cur.id] === 1) ? " solid" : "") + '">' +
              tagText + "</span>" +
            '<span class="tag">' + esc(subjName(cur.subject)) + "</span>" +
          '</div><div class="stem">' + esc(cur.stem) + "</div>" +
          (cur.note ? '<div class="note">' + esc(cur.note) + "</div>" : "") +
          (!subj && noAns(cur) ? '<div class="note">题源未标注本题答案 · 此题不判分</div>' : "") +
        "</div>";

  if (subj) {
    /* 主观题：先自己写，再对照参考答案。全程不显示对错——没有判分规则就
       不能给出一个假的「回答正确」，那会直接教错人。 */
    h += '<div class="opts">' +
           '<textarea class="q-input" id="qText" placeholder="' +
             (cur.type === "fill" ? "在这里填写答案" : "先自己写下要点，再展开参考答案对照") + '"' +
             (isRecite() ? " readonly" : "") + ">" + esc(text) + "</textarea>" +
         "</div>";
    /* 和选择题一样：没作答之前不亮参考答案；考试里交卷前也不亮 */
    if (reveal) {
      var ref = String(cur.ref || "").trim();
      var refShut = !!(S.refShut && S.refShut[cur.id]);
      h += '<button class="fold-head ref-head' + (refShut ? "" : " open") + '"' +
             ' onclick="toggleRef(event)">参考答案' +
             '<svg class="ico ar" viewBox="0 0 24 24" style="width:16px;height:16px">' +
               '<path d="M6 9l6 6 6-6"/></svg></button>' +
           '<div class="fold" id="refFold"><div class="explain">' +
             (ref ? esc(ref) : "（题库未提供参考答案，可对照解析自己判断）") + "</div></div>";
    }
    if (!isRecite() && !submitted) {
      h += '<div class="selfcheck"><button class="pri" onclick="submitSub()">提交，对照答案</button></div>';
    }
    h += '<div class="subj-tip">' +
      (isExam() ? "考试中不显示参考答案，交卷后不计分。"
                : cur.type === "fill"
                  ? "提交后自动对照参考答案；填错会进错题本（不计入正确率）。"
                  : "提交后对照参考答案；解答题没有唯一答案，答错可点「标记错题」。") + "</div>";
  } else {
    h += '<div class="opts">';
    cur.options.forEach(function (o) {
      var k = o[0], cls = "opt", m = "";
      if (reveal) {
        var isAns = cur.answer.indexOf(k) >= 0, picked = picks.indexOf(k) >= 0;
        if (isAns && picked) { cls += " right"; m = "✓"; }
        else if (isAns) { cls += " miss"; m = "答案"; }
        else if (picked) { cls += " wrong"; m = "✕"; }
        else cls += " dim";
      } else if (picks.indexOf(k) >= 0) {
        cls += " sel"; m = "✓";
      }
      h += '<button class="' + cls + '" data-k="' + k + '" onclick="pick(&quot;' + k + '&quot;)">' +
             '<span class="k">' + k + '</span><span class="v">' + esc(o[1]) + '</span><span class="m">' + m + "</span></button>";
    });
    h += "</div>";
  }

  if (done && !subj) {
    var okk = S.graded[cur.id] === 1;
    h += '<div class="verdict"><span class="mk">' + (okk ? "✓" : "✕") + "</span>" +
         '<span class="tx"><b>' + (okk ? "回答正确" : "回答错误") + "</b><i>正确答案 " +
         cur.answer.join("") + (okk ? "" : " · 已放进错题本") + "</i></span></div>";
  }
  if (reveal) {
    var ex = (cur.explain || "").trim();
    /* 解析来源必须让他看得见：题源原文 vs 按知识点撰写，可信度不是一回事 */
    var src = ex ? (cur.ex_src === "题源" ? "题源" : "编写") : "";
    h += '<button class="fold-head' + (foldOpen ? " open" : "") + '" id="exHead" onclick="toggleFold(event)">' +
           (subj ? "解析" : "答案 " + cur.answer.join("") + " · 解析") +
           (src ? '<span class="tag src">' + src + "</span>" : "") +
           '<svg class="ico ar" viewBox="0 0 24 24" style="width:16px;height:16px"><path d="M6 9l6 6 6-6"/></svg>' +
         "</button>" +
         '<div class="fold" id="fold"><div class="explain">' +
           (ex ? esc(ex) : (subj ? "（题库未提供解析）" : "（题源未提供本题答案与解析）")) + "</div></div>";
  }

  h += '<div class="tools">' +
         '<button class="' + (P(cur.id).b ? "on" : "") + '" onclick="toggleStar()">' +
           '<svg class="ico" viewBox="0 0 24 24"><path d="M12 4l2.4 5 5.6.7-4.1 3.8 1.1 5.5-5-2.8-5 2.8 1.1-5.5L4 9.7 9.6 9z"/></svg>收藏</button>' +
         '<button class="' + (inWrong(cur) ? "on" : "") + '" onclick="toggleWrong()">' +
           '<svg class="ico" viewBox="0 0 24 24"><path d="M6 21V4h11l-1.6 4L17 12H6"/></svg>' +
           (inWrong(cur) ? "移出错题本" : "标记错题") + "</button>" +
       "</div>";

  document.getElementById("qbody").innerHTML = h;
  optBounce();          /* 题面刚被整块重画，按压回弹要补挂到新节点上 */
  var ta = document.getElementById("qText");
  if (ta) {
    /* 输入即存：切题、退到后台、被杀进程都不该丢掉刚敲的字 */
    ta.addEventListener("input", function () {
      if (!S) return;
      if (!S.text) S.text = {};
      S.text[cur.id] = ta.value;
      save();
    });
  }
  document.getElementById("btnPrev").disabled = S.idx === 0;
  document.getElementById("btnAct").textContent =
    isExam() ? (S.idx === S.order.length - 1 ? "交卷" : "下一题")
    : subj ? "下一题"
    : (isRecite() || noAns(cur) || done) ? "下一题" : "确认";
  var pill = document.getElementById("qPill");
  var pct = Math.round(doneCount() / S.order.length * 100);
  if (isExam()) { tick(); }
  else { pill.textContent = "本组完成度 " + pct + "%"; }
  syncFold();
  syncRefFold();
}

/* 解析折叠：容器高度与内容同步变化，箭头同步转 180° */
function syncFold() {
  var f = document.getElementById("fold");
  if (!f) return;
  f.style.transition = "none";
  f.style.height = foldOpen ? f.scrollHeight + "px" : "0px";
  void f.offsetHeight;
  f.style.transition = "";
}
/* 参考答案折叠：与解析同一套动画，但状态按题记（S.refShut），切回来还是收起 */
function syncRefFold() {
  var f = document.getElementById("refFold");
  if (!f) return;
  var cur = q();
  var shut = !!(cur && S.refShut && S.refShut[cur.id]);
  f.style.transition = "none";
  f.style.height = shut ? "0px" : ((f.scrollHeight || 0) + "px");
  void f.offsetHeight;
  f.style.transition = "";
}
function toggleRef(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  var cur = q(), f = document.getElementById("refFold");
  if (!cur || !f) return;
  if (!S.refShut) S.refShut = {};
  var shut = !S.refShut[cur.id];
  S.refShut[cur.id] = shut ? 1 : 0;
  var head = document.querySelector("#qbody .ref-head");
  if (head) { if (shut) head.classList.remove("open"); else head.classList.add("open"); }
  f.style.height = shut ? "0px" : ((f.scrollHeight || 0) + "px");
  if (!shut) setTimeout(function () { f.style.height = "auto"; }, 320);
  save();
}
function toggleFold(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  var f = document.getElementById("fold"), head = document.getElementById("exHead");
  if (!f) return;
  foldOpen = !foldOpen;
  if (head) { if (foldOpen) head.classList.add("open"); else head.classList.remove("open"); }
  f.style.height = f.scrollHeight + "px";
  void f.offsetHeight;
  if (foldOpen) {
    f.style.height = f.scrollHeight + "px";
    setTimeout(function () { if (foldOpen) f.style.height = "auto"; }, 320);
  } else {
    f.style.height = "0px";
  }
}

/* ================= 交互 ================= */
function pick(k) {
  if (!S) return;
  if (examDone()) return;
  var cur = q();
  if (isSubjQ(cur)) return;        /* 主观题没有选项，这个入口不该被触发 */
  if (isRecite()) { go(1); return; }
  if (noAns(cur)) {
    var p0 = S.picks[cur.id] || (S.picks[cur.id] = []);
    if (cur.type === "multi") { var i0 = p0.indexOf(k); if (i0 >= 0) p0.splice(i0, 1); else p0.push(k); }
    else S.picks[cur.id] = [k];
    save(); render(); return;
  }
  var picks = S.picks[cur.id] || (S.picks[cur.id] = []);
  if (cur.type === "multi") {
    var i = picks.indexOf(k);
    if (i >= 0) picks.splice(i, 1); else picks.push(k);
    render();
  } else if (isExam()) {
    S.picks[cur.id] = [k];
    render();
  } else {
    S.picks[cur.id] = [k];
    grade([k]);
  }
  save();
}

function act() {
  if (!S) return;
  if (examDone()) return;
  var cur = q();
  /* 主观题只有「下一题」一种动作，判分与自评都在页面上的按钮里 */
  if (isSubjQ(cur)) { go(1); return; }
  if (noAns(cur)) { go(1); return; }
  if (isExam()) { if (S.idx === S.order.length - 1) submitExam(); else go(1); return; }
  if (isRecite()) { go(1); return; }
  if (S.graded[cur.id] !== undefined) { go(1); return; }
  grade(S.picks[cur.id] || []);
  save();
}

function same(a, b) {
  if (a.length !== b.length) return false;
  return a.slice().sort().join("") === b.slice().sort().join("");
}

function grade(picks) {
  var cur = q();
  if (isSubjQ(cur)) return;        /* 主观题走 submitSub，永远不进自动判分 */
  if (!picks.length && cur.type === "multi") { toast("请先选择选项"); return; }
  var ok = same(picks, cur.answer) ? 1 : 0;
  S.graded[cur.id] = ok;
  foldOpen = set.autoExp !== false;
  var p = P(cur.id);
  p.n++; p.last = ok; p.t = Date.now();
  if (!ok) { p.w++; p.in = 1; }
  memDay(ok);
  vibrate(ok ? [25, 30, 25] : [70, 60, 70, 60, 160]);
  render();
  toast(ok ? "✓ 回答正确" : "✕ 回答错误，正确答案 " + cur.answer.join(""));
  var body = document.getElementById("qbody"), quiz = document.getElementById("quiz");
  void body.offsetWidth;
  if (!ok) body.classList.add("shake");
  quiz.classList.add("q-flash");
  setTimeout(function () { body.classList.remove("shake"); quiz.classList.remove("q-flash"); }, 700);
}

/* 主观题：先作答，提交之后才展开参考答案（和选择题的先后顺序一致）。 */
/* 填空自动比对：只做「同一答案」的归一化比较，模糊语义不猜。
   解答题没有可判的唯一答案，保留「标记错题」手动加。 */
function normAns(s) {
  return String(s || "").toLowerCase()
    .replace(/[\s，。、；：！？“”‘’"'（）()【】\[\]<>《》,.;:!?\-_/\|]/g, "");
}
function submitSub() {
  var cur = q();
  if (!cur) return;
  if (!S.sub) S.sub = {};
  if (!S.sub[cur.id]) {
    var p = P(cur.id);
    p.n++;                 /* 作答过（算完成度） */
    p.t = Date.now();
    if (cur.type === "fill") {
      var ref = String(cur.ref || "").trim();
      var got = String((S.text && S.text[cur.id]) || "").trim();
      if (ref && got) {
        if (normAns(got) === normAns(ref)) {
          p.last = 1;
          memDay(1);
        } else {
          p.last = 0;
          p.w++;
          p.in = 1;          /* 填空答错自动进错题本 */
          memDay(0);
          toast("答错了，已加入错题本");
        }
      }
    }
  }
  S.sub[cur.id] = 1;
  render();
  save();
}

function go(d) {
  if (!S) return;
  if (examDone()) return;          /* 已经交卷/放弃了，不再碰会话，也不给第二次交卷机会 */
  var n = S.idx + d;
  if (n < 0) return;
  if (n >= S.order.length) {
    /* 最后一题再往后滑 = 交卷。这里必须 return，不能落进下面的换题动画：
       否则 S 已被 submitExam 清空，setTimeout 里的 render 会踩到 null。 */
    if (isExam()) submitExam();
    else toast("已到本组最后一题");
    return;
  }
  S.idx = n;
  var el = document.getElementById("qbody");
  el.style.opacity = "0";
  el.style.transform = "translateX(" + (d > 0 ? -16 : 16) + "px)";
  setTimeout(function () {
    render();
    el.style.transition = "none";
    el.style.transform = "translateX(" + (d > 0 ? 16 : -16) + "px)";
    requestAnimationFrame(function () {
      el.style.transition = "";
      el.style.opacity = "1";
      el.style.transform = "translateX(0)";
    });
  }, 130);
  save();
}

function toggleStar() {
  var p = P(q().id);
  p.b = p.b ? 0 : 1;
  toast(p.b ? "已加入收藏" : "已取消收藏");
  render(); save();
}
function toggleWrong() {
  var cur = q(), p = P(cur.id);
  p.in = inWrong(cur) ? 0 : 1;
  toast(p.in ? "已放进错题本" : "已移出错题本");
  render(); save();
}

/* 已作答计数：客观题按判分、主观题按「提交过」 */
function doneCount() {
  var n = 0;
  if (!S || !S.order) return 0;
  S.order.forEach(function (id) {
    var c = byId[id];
    if (!c) return;
    if (isSubjective(c.type)) { if (S.sub && S.sub[id]) n++; }
    else if (S.graded[id] !== undefined) n++;
  });
  return n;
}
function submitExam() {
  if (!S || !isExam()) return;
  if (S.state !== "running") return;      /* 倒计时、交卷按钮、最后一题滑动共用这一道闸 */
  S.state = "submitting";
  var right = 0, scored = 0;
  S.order.forEach(function (id) {
    var cur = byId[id];
    if (!cur || !isObjective(cur.type)) return;   /* 主观题不进卷，万一混进来也不能按对错算 */
    if (noAns(cur)) return;
    var ok = same(S.picks[id] || [], cur.answer) ? 1 : 0;
    S.graded[id] = ok; scored++;
    var p = P(id); p.n++; p.last = ok; p.t = Date.now();
    memDay(ok);
    if (ok) right++; else { p.w++; p.in = 1; }
  });
  var subjN = S.order.filter(function (id) {
    var c = byId[id];
    return c && isSubjective(c.type);
  }).length;
  var score = scored ? Math.round(right / scored * 100) : 0;
  var sid = S.subject;
  S.state = "submitted";
  S = null;
  localStorage.removeItem(LS.j);
  save();
  document.getElementById("quiz").classList.remove("on");
  renderAll();
  glass({
    icon: score >= 60 ? "✓" : "✕",
    big: score,
    title: score >= 60 ? "通过" : "未通过",
    desc: "答对 " + right + " / " + scored + " 题" +
           (subjN ? "；另有 " + subjN + " 道主观题不计分" : "") + "，错题已进错题本",
    ok: ["回首页", function () { goTab("home"); }],
    no: ["看错题", function () { startSession("wrong", sid, ""); }]
  });
}

/* ================= 计时 ================= */
function tick() {
  if (!S || !isExam() || !S.dur || S.state !== "running") return;   /* 交卷/放弃后表就停 */
  var left = S.dur - Math.floor((Date.now() - S.started) / 1000);
  var pill = document.getElementById("qPill");
  if (left <= 0) {
    if (pill) pill.setAttribute("data-t", "00:00");
    submitExam();
    return;
  }
  var m = Math.floor(left / 60), s = left % 60;
  var txt = (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
  pill.setAttribute("data-t", txt);
  pill.textContent = "剩余 " + txt + " · 完成度 " +
    Math.round(doneCount() / S.order.length * 100) + "%";
}
setInterval(tick, 1000);

/* ================= 首页 / 各 tab 渲染 ================= */
/* ================= 动效 =================
   四条约束：只用 transform / opacity；颜色一律走主题令牌（浅深两套自动成立）；
   曲线复用滑杆吸附那条 cubic-bezier(.34,1.56,.64,1)，全站手感一致；
   真机 WebView 是 Chrome 80，element.animate() 可用但**不派发 onfinish**，
   所以凡是要「动画结束后收尾」的一律靠 cancel() 或定时，不挂 onfinish。 */
var BOUNCE = "cubic-bezier(.34, 1.56, .64, 1)";
var EASE = "cubic-bezier(0.16, 1, 0.3, 1)";

/* ---- 1. 底部导航的流动胶囊 ---- */
var pillBox = null;                     /* 上一枚胶囊的 {x,y,w,h}，用来当动画起点 */
function moveTabPill(slide) {
  var bar = document.getElementById("tabs"), p = document.getElementById("tabPill");
  var b = bar && bar.querySelector(".tab.on");
  if (!b || !p) return;
  var x = b.offsetLeft, y = b.offsetTop, w = b.offsetWidth, h = b.offsetHeight;
  p.style.left = x + "px"; p.style.top = y + "px";
  p.style.width = w + "px"; p.style.height = h + "px";
  if (slide && p.animate && pillBox && (pillBox.x !== x || pillBox.w !== w)) {
    /* 起点是关键帧、终点是元素自己的行内值：动画跑完自然停在终点，不需要 fill 也不需要收尾回调。
       回弹来自 BOUNCE 这条 >1 的曲线——left 与 width 同时过冲，胶囊会先被拉长再弹回。 */
    p.animate([{ left: pillBox.x + "px", top: pillBox.y + "px", width: pillBox.w + "px", height: pillBox.h + "px" },
               { left: x + "px", top: y + "px", width: w + "px", height: h + "px" }],
              { duration: 420, easing: BOUNCE });
  }
  pillBox = { x: x, y: y, w: w, h: h };
}

/* ---- 2. 选项按钮的按压弹性 ---- */
var optPress = null, optPressEl = null, bounceKey = null, bounceAt = 0;
function optOf(node) { return node && node.closest ? node.closest(".opt") : null; }
function optSpring(el) {
  if (el && el.animate) {
    el.animate([{ transform: "scale(0.96)" }, { transform: "scale(1.02)", offset: 0.55 },
                { transform: "scale(1)" }], { duration: 280, easing: BOUNCE });
  }
}
document.addEventListener("pointerdown", function (e) {
  var el = optOf(e.target);
  if (!el || !el.animate) return;
  if (optPress) optPress.cancel();
  optPress = el.animate([{ transform: "scale(1)" }, { transform: "scale(0.96)" }],
                        { duration: 90, easing: "ease-out", fill: "forwards" });
  optPressEl = el;
});
function releaseOpt() {
  var el = optPressEl;
  /* 按住的这一段是 fill:forwards，不 cancel 掉按钮会永久停在 0.96；
     手指滑出按钮时 pointerup 的 target 已不是它，所以收尾认的是「按住的那只」而不是事件目标。 */
  if (optPress) { optPress.cancel(); optPress = null; }
  optPressEl = null;
  if (!el) return;
  bounceKey = el.getAttribute("data-k");
  bounceAt = Date.now();
  optSpring(el);
}
document.addEventListener("pointerup", releaseOpt);
document.addEventListener("pointercancel", releaseOpt);
/* 选中/判分会立刻重画整块题面，旧节点连同它身上的动画一起被换掉，
   所以回弹要在新节点上补一次；超过一帧半就当作已经错过，别在下次无关重画时凭空弹一下。 */
function optBounce() {
  if (!bounceKey) return;
  var k = bounceKey, stale = Date.now() - bounceAt > 250;
  bounceKey = null;
  if (stale) return;
  var el = document.querySelector('.opt[data-k="' + k + '"]');
  optSpring(el);
}

/* ---- 3. 切页：旧页淡出 → 新页淡入 + 卡片交错入场 ----
   顺序按语义点名，不按 DOM 顺序：首页是「搜索框 → 题型卡片（6 张同时）→ 今日待刷题 → 最近练习」，
   题库页是「导入卡片 → 筛选标签 → 题库列表卡片」。每组占一个 40ms 槽位，
   列表类那一组内部再按 40ms 逐张排开；单段时长 300ms，曲线用弹簧。
   只用 transform / opacity，Chrome 80 上两者都能被 element.animate 插值。 */
var TAB_ORDER = ["home", "bank", "wrong", "me"];
var swapSeq = 0, swapTimer = null;

function pageKids(pg, cls) {
  var out = [], i;
  for (i = 0; i < pg.children.length; i++) {
    if (pg.children[i].className && pg.children[i].className.indexOf(cls) >= 0) out.push(pg.children[i]);
  }
  return out;
}
function pageKidById(pg, id) {
  var i;
  for (i = 0; i < pg.children.length; i++) if (pg.children[i].id === id) return pg.children[i];
  return null;
}
/* 一个「小节」= 它的标题行 + 紧跟其后的列表容器，两者同时进 */
function sectionOf(pg, listId) {
  var list = pageKidById(pg, listId), out = [];
  if (!list) return out;
  var p = list.previousElementSibling;
  if (p && p.className.indexOf("sec-title") >= 0) out.push(p);
  out.push(list);
  return out;
}
function enterGroups(which, pg) {
  if (which === "home") {
    var cats = document.getElementById("cats");
    var head = pageKids(pg, "cats-head");
    var hero = pageKidById(pg, "planHero");
    return [
      { els: pageKids(pg, "search"), step: 0 },
      { els: hero ? [hero] : [], step: 0 },
      { els: head.concat(cats ? [].slice.call(cats.children) : []), step: 0 },
      { els: sectionOf(pg, "tasks"), step: 0 },
      { els: sectionOf(pg, "recent"), step: 0 }
    ];
  }
  if (which === "bank") {
    var list = pageKidById(pg, "bankList");
    var cards = list ? [].slice.call(list.querySelectorAll(".swipe")) : [];
    return [
      { els: list && list.firstElementChild ? [list.firstElementChild] : [], step: 0 },
      { els: list ? [].slice.call(list.querySelectorAll(".tbar")) : [], step: 0 },
      { els: cards.slice(0, 8), step: 40 }
    ];
  }
  /* 错题本与「我的」没有指定的点名顺序：按页内出现次序逐块排开 */
  var items = [], i;
  for (i = 0; i < pg.children.length; i++) {
    var k = pg.children[i];
    if (!k.className && k.children.length) {
      for (var j = 0; j < k.children.length && j < 8; j++) items.push(k.children[j]);
    } else items.push(k);
  }
  return [{ els: items.slice(0, 10), step: 40 }];
}
function enterPage(which, pg) {
  if (!pg || !pg.animate) return;
  pg.animate([{ opacity: 0, transform: "translateY(12px)" },
              { opacity: 1, transform: "translateY(0)" }],
             { duration: 300, easing: BOUNCE });
  var groups = enterGroups(which, pg), slot = 0, g, i;
  for (g = 0; g < groups.length; g++) {
    for (i = 0; i < groups[g].els.length; i++) {
      groups[g].els[i].animate([{ opacity: 0, transform: "translateY(16px)" },
                                { opacity: 1, transform: "translateY(0)" }],
                               /* fill: backwards 必须有：带 delay 的动画在等待期不生效，
                                  元素会先以完全不透明亮一帧再消失，看着就是闪一下 */
                               { duration: 300, delay: slot * 40 + i * groups[g].step, easing: BOUNCE,
                                 fill: "backwards" });
    }
    slot++;
  }
}

/* ---- 4. 启动页：呼吸 → 圆形揭幕 → 首页交错浮入 ----
   第一帧就是 #splash 的主题底色，全程没有白屏；揭幕期间首页节点被临时搬进
   被圆形裁剪的 #stage，跑完再按原兄弟位置插回 body。
   交错用 fill:forwards 收尾——因为入场前给元素压了行内 opacity:0，
   不 fill 的话动画一结束元素会退回透明，所以结束时要「先放行内终值、再 cancel」。 */
var BOOT_BREATH = 600, BOOT_REVEAL = 700, BOOT_STEP = 60, BOOT_WAIT = 1800;
var banksLoaded = false;

function bootGroups(home) {
  var cats = [].slice.call(document.getElementById("cats").children);
  var hero = pageKidById(home, "planHero");
  return [
    { els: pageKids(home, "hello"), step: 0 },
    { els: pageKids(home, "search"), step: 0 },
    { els: hero ? [hero] : [], step: 0 },
    { els: pageKids(home, "cats-head").concat(cats), step: 20 },
    { els: sectionOf(home, "tasks"), step: 0 },
    { els: sectionOf(home, "recent"), step: 0 },
    { els: [document.getElementById("tabs")], step: 0 }
  ];
}
function bootSplash() {
  var sp = document.getElementById("splash"), stage = document.getElementById("stage");
  var home = document.getElementById("tab-home"), tabs = document.getElementById("tabs");
  if (!sp) return;
  if (!stage || !sp.animate || !("clipPath" in document.documentElement.style)) {
    if (sp.parentNode) sp.parentNode.removeChild(sp);      /* 缺 clip-path 动画的内核：不做揭幕，直接进首页 */
    return;
  }
  var groups = bootGroups(home), items = [], bootAnims = [], g, i;
  for (g = 0; g < groups.length; g++) {
    for (i = 0; i < groups[g].els.length; i++) if (groups[g].els[i]) items.push(groups[g].els[i]);
  }
  for (i = 0; i < items.length; i++) { items[i].style.opacity = "0"; items[i].style.transform = "translateY(16px)"; }

  var restore = [[home, home.nextSibling], [tabs, tabs.nextSibling]];
  /* 搬进舞台 = 首页此刻的可见性完全由启动页负责。编排中途任何意外都必须还原，
     否则用户面对的会是一个永久透明的首页，而不是一个小 bug。 */
  function bail() {
    for (var k = 0; k < restore.length; k++) {
      if (restore[k][0].parentNode !== document.body) document.body.insertBefore(restore[k][0], restore[k][1]);
    }
    if (stage.parentNode) stage.parentNode.removeChild(stage);
    if (sp.parentNode) sp.parentNode.removeChild(sp);
    for (var m = 0; m < items.length; m++) { items[m].style.opacity = ""; items[m].style.transform = ""; }
  }
  try {
    stage.appendChild(home);
    stage.appendChild(tabs);
    run();
  } catch (e) {
    bail();
    return;
  }

  function run() {
  var logo = document.getElementById("spLogo"), word = document.getElementById("spWord"), sub = document.getElementById("spSub");
  function breathe() {
    logo.animate([{ transform: "scale(1)" }, { transform: "scale(1.06)", offset: 0.5 }, { transform: "scale(1)" }],
                 { duration: BOOT_BREATH, easing: EASE });
  }
  breathe();
  word.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 420, delay: 120, easing: EASE, fill: "forwards" });
  sub.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 420, delay: 260, easing: EASE, fill: "forwards" });

  var t0 = Date.now();
  /* 数据没读完就再呼吸一轮，不转圈不进度条。上限是必要的：读失败和读得慢
     在这里长得一模一样，无限等会把用户挡在「导入题库」入口之外，而那正是唯一的出路 */
  function waitData() {
    if (banksLoaded || Date.now() - t0 > BOOT_WAIT) { reveal(); return; }
    breathe();
    setTimeout(waitData, BOOT_BREATH);
  }
  setTimeout(waitData, BOOT_BREATH);

  function reveal() {
    stage.style.clipPath = "circle(150% at 50% 50%)";
    stage.animate([{ clipPath: "circle(0px at 50% 50%)" }, { clipPath: "circle(150% at 50% 50%)" }],
                  { duration: BOOT_REVEAL, easing: "cubic-bezier(.55, 0, .85, .35)" });
    var art = [logo, word, sub];
    for (var k = 0; k < art.length; k++) {
      art[k].animate([{ opacity: 1 }, { opacity: 0 }],
                     { duration: BOOT_REVEAL * 0.55, easing: "ease-in", fill: "forwards" });
    }
    setTimeout(finish, BOOT_REVEAL);
  }
  function finish() {
    for (var k = 0; k < restore.length; k++) document.body.insertBefore(restore[k][0], restore[k][1]);
    if (stage.parentNode) stage.parentNode.removeChild(stage);
    if (sp.parentNode) sp.parentNode.removeChild(sp);
    /* 交错一次性按 delay 排好，而不是逐组 setTimeout：定时器更少，
       而且每条动画的延迟是可读到、可核对的数字 */
    var last = 0;
    for (var s = 0; s < groups.length; s++) {
      var els = groups[s].els, step = groups[s].step;
      for (var m = 0; m < els.length; m++) {
        if (!els[m] || !els[m].animate) continue;
        var d = s * BOOT_STEP + m * step;
        if (d > last) last = d;
        bootAnims.push(els[m].animate([{ opacity: 0, transform: "translateY(16px)" },
                                       { opacity: 1, transform: "translateY(0)" }],
                                      /* both 不是笔误：这些元素 CSS 里本来就是可见的，
                                         只写 forwards 的话 delay 期间会先亮出来再消失 */
                                      { duration: 300, delay: d, easing: BOUNCE, fill: "both" }));
      }
    }
    setTimeout(function () {
      for (var m2 = 0; m2 < items.length; m2++) { items[m2].style.opacity = "1"; items[m2].style.transform = ""; }
      for (var a = 0; a < bootAnims.length; a++) { try { bootAnims[a].cancel(); } catch (e) {} }
    }, last + 360);
  }
  }   /* run() —— 上面整段编排都在 try 的保护里 */
}

function goTab(which) {
  var from = document.querySelector(".page.on");
  var to = document.getElementById("tab-" + which);
  curTab = which;
  /* 按 data-tab 认按钮，不按序号：中间插了一颗大肥鱼，序号已经对不上 TAB_ORDER 了。
     大肥鱼那颗没有 data-tab，所以永远不会被标成选中态。 */
  var tabs = document.querySelectorAll("#tabs .tab");
  for (var i = 0; i < tabs.length; i++) {
    var k = tabs[i].getAttribute("data-tab");
    tabs[i].className = "tab" + (k === which ? " on" : "") +
                        (k ? "" : " ask-tab");
  }
  moveTabPill(true);          /* 胶囊当场就走，切页的 200ms 淡出期间它就是反馈 */
  swapPage(from, to, which);
}

function swapPage(from, to, which) {
  var my = ++swapSeq;
  if (swapTimer) { clearTimeout(swapTimer); swapTimer = null; }
  function show() {
    if (my !== swapSeq) return;
    if (from && from !== to) from.className = "page";
    to.className = "page on";
    /* 淡出留下的行内 opacity=0 必须在这里清掉，否则下次进这一页是全透明的 */
    to.style.opacity = "";
    to.style.transform = "";
    renderAll();
    enterPage(which, to);
  }
  if (!from || from === to || !from.animate) { show(); return; }
  /* 先把行内值压成 0 再跑 1→0 的动画：动画结束后不 fill，靠这个行内值保持隐藏。
     少了这一步，动画收尾的那一帧会闪回旧页。 */
  from.style.opacity = "0";
  from.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 200, easing: "ease-out" });
  swapTimer = setTimeout(show, 200);
}
/* 底部四个 tab 的点击入口。和 goTab 分开：程序内部跳转（结算页的「回首页」等）
   不该顺带联网查版本，只有用户真点了「我的」才触发静默检查 */
function tabTap(which) {
  goTab(which);
  if (which === "me") checkUpdateSilently();
}

/* 分类区整体展开/收起。和单个题型无关，所以只存一个布尔值；
   不落盘——重启后展开是最不容易让人迷路的状态 */
var catsClosed = false;

/* 首页学习资料卡：按错题本的题型分布找薄弱点，交给大肥鱼联网检索 */
function studyFocus() {
  var byType = {}, total = 0, best = "", n = 0, i, q, k;
  for (i = 0; i < BANK.questions.length; i++) {
    q = BANK.questions[i];
    if (!inWrong(q)) continue;
    total++;
    k = q.type || "single";
    byType[k] = (byType[k] || 0) + 1;
  }
  for (k in byType) if (byType[k] > n) { n = byType[k]; best = k; }
  return { total: total, type: best, label: best ? typeName(best) : "", count: n };
}
function renderStudyCard() {
  var el = document.getElementById("studyCard");
  if (!el) return;
  var f = studyFocus();
  var on = typeof webSearchOn === "function" && webSearchOn();
  var sub = f.total
    ? "错题本里有 " + f.total + " 道错题" + (f.label ? "，" + f.label + "最多（" + f.count + " 道）" : "") + "；联网找 3 份适合的资料"
    : "先做几道题，我再按你的薄弱点联网找资料";
  el.innerHTML = '<div class="study-card">' +
    '<div class="sc-top">' +
      '<span class="sc-ic"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5z"/><path d="M4 5.5v16"/></svg></span>' +
      '<div class="sc-copy"><b>今日学习资料</b><em>' + esc(sub) + "</em></div>" +
      '<span class="sc-net' + (on ? " on" : "") + '">' + (on ? "联网" : "未联网") + "</span>" +
    "</div>" +
    '<button class="sc-go" onclick="studyMaterials()"><span>让大肥鱼找资料</span>' +
      '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg></button>' +
  "</div>";
}
function studyMaterials() {
  if (typeof webSearchOn === "function" && !webSearchOn()) {
    toast("先在聊天页打开联网搜索");
    return;
  }
  var f = studyFocus();
  var focus = f.total ? "，尤其是" + f.label + "（" + f.count + " 道错题）" : "";
  var prompt = "结合我的学习情况和错题" + focus +
    "，联网帮我找 3 份适合的学习资料，给出可点击链接、来源和推荐理由；不要编造链接。";
  openChatTab();
  chatAsk(prompt);
}
function renderHome() {
  /* 今日待刷 + 进行中方案合成一张 Hero；方案域还没加载时留空，不挡首页 */
  var hero = document.getElementById("planHero");
  if (hero) hero.innerHTML = typeof planHeroHtml === "function" ? planHeroHtml() : "";

  var all = stats("all");
  var noQ = !all.total;
  document.getElementById("hiName").textContent = noQ ? "先导入一个题库" : (all.seen ? "继续上次进度" : "先挑一个题库");
  /* 空题库时下面那张「还没有题目」的卡已经把该导入什么说清楚了，
     这里再挂一行格式说明就是重复，而且会把问候语挤成两行 */
  var sub = document.getElementById("hiSub");
  sub.textContent = noQ ? "" : "共 " + all.total + " 题 · 已刷 " + all.seen + " 题";
  sub.hidden = noQ;

  var cats = "";
  TYPES.forEach(function (t) {
    var n, sub2;
    if (t.tf === "exam") { n = examN(); sub2 = n + " 题 / 卷"; }
    else { n = stats("all", t.tf).total; sub2 = n + " 题"; }
    cats += '<div class="cat-wrap" data-tf="' + t.tf + '">' +
              '<button class="cat" onclick="catGo(&quot;' + t.tf + '&quot;)">' +
                '<svg class="ico" viewBox="0 0 24 24">' + t.ico + "</svg>" +
                "<b>" + t.name + "</b><i>" + sub2 + "</i></button>" +
            "</div>";
  });
  document.getElementById("cats").innerHTML = cats;
  syncCatsFold();
  renderTasks(noQ);
  renderStudyCard();
}

/* 折叠：只改一个容器的类，六张卡片一起动，不会逐张错位。
   高度必须由 JS 量：这台机器的 WebView 是 Chrome 80，
   grid-template-rows 的 0fr→1fr 过渡（Chrome 107+）会被忽略成瞬跳。
   量完把终点写死成像素，过渡结束后再放开成 auto——
   否则字号调大或换行变多时，写死的高度会把内容裁掉。 */
var catsFoldState = null;      /* null = 还没同步过；true/false = 上一次的展开状态 */

function syncCatsFold() {
  var fold = document.getElementById("catsFold");
  var btn = document.getElementById("catsToggle");
  var tx = document.getElementById("catsToggleTx");
  if (!fold) return;
  var closed = catsClosed;
  var changed = catsFoldState !== closed;
  catsFoldState = closed;

  if (btn) btn.setAttribute("aria-expanded", closed ? "false" : "true");
  if (tx) tx.textContent = closed ? "展开" : "收起";

  if (!changed) {
    /* 状态没变（renderHome 重画卡片时会走到这里）：高度保持现状，
       重画不该再触发一次动画，否则每次点别的按钮分类区都抖一下 */
    if (!closed) fold.style.height = "auto";
    return;
  }

  if (closed) {
    /* 收起：先固定当前像素高度，再收到 0，过渡才有起点 */
    fold.style.height = fold.scrollHeight + "px";
    void fold.offsetHeight;
    fold.classList.add("closed");
    fold.style.height = "0px";
    return;
  }

  /* 展开：容器此刻是 0 高，scrollHeight 量到的就是内容自然高度 */
  fold.classList.remove("closed");
  fold.style.height = "auto";
  var target = fold.scrollHeight;
  fold.style.height = "0px";
  void fold.offsetHeight;
  fold.style.height = target + "px";
  setTimeout(function () {
    if (!catsClosed) fold.style.height = "auto";
  }, 320);
}
function toggleCats() {
  catsClosed = !catsClosed;
  syncCatsFold();
  vibrate(10);
}
function catGo(tf) {
  if (tf === "exam") { openExam("all"); return; }   /* 模拟卷是另一套页面，不接本转场 */
  startSession("practice", "all", tf, "cat");
}

/* 空题库时只留一张卡：标题说清楚「还没有题目」，副标题说清楚点进去做什么。
   格式说明由导入面板负责，这里不再重复一遍 */
function emptyTaskHtml() {
  return '<button class="task" onclick="openImport()">' +
           '<span class="ck"><span class="box"></span></span>' +
           '<span class="bd"><b>还没有题目</b><small>点这里导入题库</small></span>' +
           '<span class="rt">导入</span></button>';
}

function renderTasks(noQ) {
  var rows = noQ ? emptyTaskHtml() : "", anyBank = false;
  BANK.subjects.forEach(function (s) {
    var t = stats(s.id);
    /* 与题库页同一条过滤：一道题都没有的题库不出现在这里。
       少了这一格，「今日待刷」会列出题库页里根本看不到的名字（用户报的那条）。 */
    if (!t.total) return;
    anyBank = true;
    var left = t.total - t.seen;
    rows += '<div class="swipe" data-sid="' + s.id + '">' +
              '<div class="swipe-acts">' +
                '<button class="swipe-act" onclick="askRenameBank(&quot;' + s.id + '&quot;)">改名</button>' +
                '<button class="swipe-act" onclick="askDeleteBank(&quot;' + s.id + '&quot;)">删除</button>' +
              "</div>" +
              '<button class="task" onclick="pickMode(&quot;practice&quot;,&quot;' + s.id + '&quot;)">' +
              '<span class="ck"><span class="box' + (left <= 0 ? " ok" : "") + '"></span></span>' +
              '<span class="bd"><b>' + esc(s.name) + "</b><small>" +
                (left <= 0 ? "已全部刷过" : "剩余 " + left + " 题未刷") + " · 完成率 " + t.done + "%</small></span>" +
              '<span class="rt">' + left + "<i>题</i></span>" +
            "</button></div>";
  });
  if (anyBank) rows += '<div class="sl-hint" style="margin:-4px 0 18px">左滑题库行可以改名或删除</div>';
  var w = wrongList();
  if (!noQ || w.length) {
    rows += '<button class="task" onclick="pickMode(&quot;wrong&quot;,&quot;all&quot;)">' +
              '<span class="ck"><span class="box' + (w.length ? "" : " ok") + '"></span></span>' +
              '<span class="bd"><b>错题本</b><small>' +
              (w.length ? "答错过的题，答对不会自动移出" : "已经清空了") + "</small></span>" +
              '<span class="rt">' + w.length + "<i>题</i></span></button>";
  }
  document.getElementById("tasks").innerHTML = rows;

  var rec = BANK.subjects.map(function (s) {
    var t = stats(s.id);
    t.id = s.id; t.name = s.name;
    t.r = findResume(s.id, "");
    t.at = Math.max(t.lastT || 0, t.r ? (t.r.at || 0) : 0);
    return t;
  })
    .filter(function (t) { return t.seen > 0 || !!t.r; })
    .sort(function (a, b) { return b.at - a.at; }).slice(0, 4);
  document.getElementById("recent").innerHTML = rec.length ? rec.map(function (t) {
    var sub = "完成 " + t.seen + " / " + t.total + " 题";
    if (t.r && validResume(t.r)) sub += " · 上次到第 " + (t.r.idx + 1) + " 题";
    return '<button class="rec" onclick="resumeRecent(&quot;' + t.id + '&quot;)">' +
             '<span class="n">' + esc(t.name) + "<i>" + sub + "</i></span>" +
             '<span class="p"><b>' + t.done + "%</b><small>完成率</small></span></button>";
  }).join("") : '<div class="cap" style="margin:0">还没有练习记录</div>';
}

function wrongList() {
  return BANK.questions.filter(inWrong);
}

var bankTf = "";
var BANK_TAGS = [["", "全部"], ["single", "单选题"], ["multi", "多选题"], ["judge", "判断题"],
                 ["fill", "填空题"], ["short", "解答题"]];
var tfU = null, tfSlide = false;

/* 用户点筛选才该有东西在爬；程序内部改 bankTf 不该，所以「要不要滑」在这里置位，
   由下一次渲染消费掉 */
function setBankTf(tf) { tfSlide = (tf !== bankTf); bankTf = tf; renderBank(); }

/* 下划线元素跟着每次重建的 tbar 一起新建，所以「上一枚在哪」只能记在 tfU 里。
   中间那一帧把条拉到同时盖住新旧两枚的跨度：前端先到、后端才收，就是虫在爬。 */
function drawTfUnderline() {
  var bar = document.querySelector("#bankList .tbar");
  var u = bar && bar.querySelector(".tbar-u");
  var b = bar && bar.querySelector("button.on");
  if (!u || !b) return;
  var x = b.offsetLeft + b.offsetWidth * 0.29, w = b.offsetWidth * 0.42;
  var from = tfU;
  u.style.left = x + "px"; u.style.width = w + "px";
  if (tfSlide && u.animate && from && (from.x !== x || from.w !== w)) {
    var lo = Math.min(from.x, x), hi = Math.max(from.x + from.w, x + w);
    u.animate([{ left: from.x + "px", width: from.w + "px" },
               { left: lo + "px", width: (hi - lo) + "px", offset: 0.5 },
               { left: x + "px", width: w + "px" }], { duration: 380, easing: EASE });
  }
  tfSlide = false;
  tfU = { x: x, w: w };
}

function renderBank() {
  var bar = '<div class="tbar scroll"><i class="tbar-u"></i>' + BANK_TAGS.map(function (t) {
    return '<button class="' + (bankTf === t[0] ? "on" : "") + '" onclick="setBankTf(&quot;' + t[0] + '&quot;)">' +
           t[1] + "</button>";
  }).join("") + "</div>";
  var total = subjQs("all", bankTf).length;
  document.getElementById("bankTotal").textContent = total + " 题";

  var h = '<button class="card pad" style="width:100%;display:flex;align-items:center;margin-bottom:16px" ' +
            'onclick="openImport()">' +
            '<span style="flex:0 0 40px"><svg class="ico" viewBox="0 0 24 24" style="width:22px;height:22px">' +
            '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5"/><path d="M4.5 18.5h15"/></svg></span>' +
            '<span style="flex:1;text-align:left;margin-left:12px"><b style="font-size:1rem">导入题库</b>' +
            '<em style="font-style:normal;display:block;font-size:.6875rem;color:var(--ink-3)">' +
            "PDF / Word / txt / md，带解析一起导</em></span>" +
            '<span style="color:var(--ink-3)">›</span></button>' + bar;

  var shown = 0;
  BANK.subjects.forEach(function (s) {
    var t = stats(s.id, bankTf);
    if (!t.total) return;
    shown++;
    h += '<div class="swipe" data-sid="' + s.id + '">' +
           '<div class="swipe-acts">' +
             '<button class="swipe-act" onclick="askRenameBank(&quot;' + s.id + '&quot;)">改名</button>' +
             '<button class="swipe-act" onclick="askDeleteBank(&quot;' + s.id + '&quot;)">删除</button>' +
           "</div>" +
           '<div class="card pad">' +
             '<div style="display:flex;align-items:center">' +
               '<div style="flex:1;min-width:0"><b class="bk-name" style="font-size:1rem">' + esc(s.name) + "</b>" +
               '<em class="bk-sub" style="font-style:normal;display:block;font-size:.6875rem;color:var(--ink-3)">' +
               t.total + " 题 · 已刷 " + t.seen + " · 错题 " + t.wrong + "</em></div>" +
               '<div class="bk-pct" style="font-size:1.5rem;font-weight:800">' + t.done + "%</div></div>" +
             '<div class="bk-acts" style="display:flex;margin-top:16px">' +
               '<button style="flex:1;padding:12px 0;border-radius:16px;background:var(--ink);color:var(--paper);font-size:.8125rem;font-weight:700" ' +
                 'onclick="startSession(&quot;practice&quot;,&quot;' + s.id + '&quot;,&quot;' + bankTf + '&quot;,&quot;bank&quot;)">继续刷</button>' +
               '<button style="flex:1;margin-left:10px;padding:12px 0;border-radius:16px;background:var(--raise);box-shadow:var(--sh-sm);font-size:.8125rem;font-weight:600" ' +
                 'onclick="startSession(&quot;recite&quot;,&quot;' + s.id + '&quot;, &quot;' + bankTf + '&quot;)">背题</button>' +
               '<button style="flex:1;margin-left:10px;padding:12px 0;border-radius:16px;background:var(--raise);box-shadow:var(--sh-sm);font-size:.8125rem;font-weight:600" ' +
                 'onclick="openExam(&quot;' + s.id + '&quot;)">模拟</button>' +
             "</div></div></div>";
  });
  if (!shown) {
    /* 题库为空是正常状态，不是错误：这里必须给出下一步能做什么 */
    h += !BANK.subjects.length
      ? '<div class="card pad" style="text-align:center">' +
          '<div class="bignum" style="font-size:3rem">0</div>' +
          '<div class="cap">还没有任何题库</div>' +
          '<button style="width:100%;padding:15px 0;border-radius:16px;background:var(--ink);color:var(--paper);font-size:.9375rem;font-weight:700" ' +
            'onclick="openImport()">导入题库</button>' +
          '<div class="sl-hint" style="margin:14px 0 0">题库全部由你导入，导入后随时可以删除</div>' +
        "</div>"
      : '<div class="card pad" style="text-align:center;color:var(--ink-3)">这个题型下暂时没题</div>';
  }
  h += '<div class="sl-hint" style="margin:14px 0 0">左滑题库卡片可以改名或删除，导入的题库随时可以再导入</div>';
  document.getElementById("bankList").innerHTML = h;
  drawTfUnderline();
}

var batchOn = false, batchSel = {};

function renderWrong() {
  var w = wrongList();
  document.getElementById("wrongCount").textContent = w.length + " 题在错题本";
  if (!w.length) {
    batchOn = false; batchSel = {};
    document.getElementById("wrongList").innerHTML =
      '<div class="card pad" style="text-align:center"><div class="bignum" style="font-size:3rem">0</div>' +
      '<div class="cap" style="margin-bottom:0">错题本是空的，继续保持</div></div>';
    return;
  }
  var n = 0;
  for (var k in batchSel) n++;
  var h = "";
  if (batchOn) {
    h += '<div class="bbar">' +
           '<button onclick="selectAllWrong()">全选</button>' +
           '<button class="solid" id="batchGo" onclick="batchRemove()">移出 ' + n + "</button>" +
           '<button onclick="exitBatch()">取消</button></div>' +
           '<div class="sl-hint" style="margin:-4px 0 10px">点题库名可以展开，再勾选要移出的题</div>';
  } else {
    h += '<div class="bbar">' +
           '<button class="solid" style="flex:2" onclick="startSession(&quot;wrong&quot;,&quot;all&quot;, &quot;&quot;)">开始重刷全部 ' +
             w.length + " 题</button>" +
           '<button onclick="enterBatch()">管理</button></div>' +
           '<div class="sl-hint" style="margin:-4px 0 14px">答对不会自动移出错题本，重做对后到「管理」里勾选移出</div>';
  }
  function wrongRow(x) {
    var sel = batchSel[x.id] ? " sel" : "";
    return '<button class="res' + sel + '" data-id="' + x.id + '" onclick="' +
      (batchOn ? "tapSel(&quot;" + x.id + "&quot;)" : "openQuestion(&quot;" + x.id + "&quot;)") + '">' +
      (batchOn ? '<span class="cbx"></span>' : "") +
      "<b>" + esc(x.stem) + "</b>" +
      "<small>" + typeName(x.type) + " · " +
      (isSubjective(x.type) ? "参考答案" : "答案") + " " + esc(answerText(x)) +
      " · " + (prog[x.id].n > 0 ? "错 " + prog[x.id].w + " 次" : "手动标记") + "</small></button>";
  }
  /* 按题库分组：每个库给自己一小块，不再把几个库的错题混成一锅 */
  var groups = [];
  BANK.subjects.forEach(function (s2) {
    var list = w.filter(function (x) { return x.subject === s2.id; });
    if (list.length) groups.push({ id: s2.id, name: s2.name, list: list });
  });
  var orphan = w.filter(function (x) { return !subjectOf(x.subject); });
  if (orphan.length) groups.push({ id: "", name: "已删除的题库", list: orphan });
  h += groups.map(function (g) {
    /* 默认收起：错题一多，全展开会把页面拖得很长。
       批量管理也不强行展开——展开哪一组由用户自己点。 */
    var open = !!wrongOpen[g.id];
    return '<div class="wgroup" data-sid="' + g.id + '"><button class="wg-head" onclick="toggleWrongGroup(&quot;' + g.id + '&quot;)">' +
             '<span class="wg-t">' + esc(g.name) + "</span>" +
             '<span class="wg-n">' + g.list.length + " 题</span>" +
             '<span class="wg-chev">' + (open ? "▴" : "▾") + "</span>" +
           "</button>" +
           (g.id ? '<button class="wg-go" onclick="startWrongBank(&quot;' + g.id + '&quot;)">刷 ' +
                   g.list.length + " 题</button>" : "") +
           "</div>" +
           (open ? g.list.slice(0, 60).map(wrongRow).join("") : "");
  }).join("");
  if (batchOn) {
    h += '<div class="trash" id="trash"><svg class="ico" viewBox="0 0 24 24" style="width:20px;height:20px">' +
         '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/></svg></div>';
  }
  document.getElementById("wrongList").innerHTML = h;
}
var wrongOpen = {};   /* 每个库的错题块是否展开；默认收起 */
function toggleWrongGroup(sid) { wrongOpen[sid] = !wrongOpen[sid]; renderWrong(); }
function startWrongBank(sid) { startSession("wrong", sid, ""); }
function enterBatch() { batchOn = true; batchSel = {}; renderWrong(); }
function exitBatch() { batchOn = false; batchSel = {}; renderWrong(); }
function tapSel(id) {
  batchSel[id] = batchSel[id] ? 0 : 1;
  if (!batchSel[id]) delete batchSel[id];
  var el = document.querySelector('.res[data-id="' + id + '"]');
  if (el) {
    if (batchSel[id]) el.classList.add("sel"); else el.classList.remove("sel");
    el.classList.remove("pop"); void el.offsetWidth; el.classList.add("pop");
  }
  var go = document.getElementById("batchGo");
  var n = 0; for (var k in batchSel) n++;
  if (go) go.textContent = "移出 " + n;
}
/* 全选：从上到下错峰打勾，每项 5% 弹性放大，不一次性全亮 */
function selectAllWrong() {
  var rows = [].slice.call(document.querySelectorAll("#wrongList .res"));
  rows.forEach(function (r, i) {
    setTimeout(function () {
      var id = r.getAttribute("data-id");
      batchSel[id] = 1;
      r.classList.add("sel");
      r.classList.remove("pop"); void r.offsetWidth; r.classList.add("pop");
      var go = document.getElementById("batchGo");
      if (go) go.textContent = "移出 " + (i + 1);
    }, i * 45);
  });
}
function batchRemove() {
  var ids = Object.keys(batchSel);
  if (!ids.length) { toast("先勾选要移出的题目"); return; }
  var trash = document.getElementById("trash");
  ids.forEach(function (id, i) {
    var el = document.querySelector('.res[data-id="' + id + '"]');
    setTimeout(function () { flyTo(el, trash); }, i * 70);
  });
  setTimeout(function () {
    ids.forEach(function (id) { P(id).in = 0; });
    batchOn = false; batchSel = {};
    renderWrong(); save();
    toast("已移出错题本 " + ids.length + " 题");
  }, ids.length * 70 + 520);
}
/* 删除卡片沿曲线飞向角落图标：中途抬起、逐渐缩小旋转并淡出 */
function flyTo(el, target) {
  if (!el) return;
  var a = el.getBoundingClientRect();
  var b = (target || document.getElementById("trash")).getBoundingClientRect();
  var dx = (b.left + b.width / 2) - (a.left + a.width / 2);
  var dy = (b.top + b.height / 2) - (a.top + a.height / 2);
  if (!el.animate) { el.style.opacity = "0"; return; }
  el.animate([
    { transform: "translate(0,0) scale(1) rotate(0deg)", opacity: 1 },
    { transform: "translate(" + (dx * 0.3) + "px," + (dy * 0.12 - 46) + "px) scale(.8) rotate(-5deg)",
      opacity: 0.9, offset: 0.42 },
    { transform: "translate(" + dx + "px," + dy + "px) scale(.1) rotate(16deg)", opacity: 0 }
  ], { duration: 520, easing: "cubic-bezier(.5,.05,.45,.95)", fill: "forwards" });
}

/* 提醒的系统门槛：通知权限与省电豁免。桌面回归没有原生桥，
   这时返回 null，界面显示「当前环境不支持」而不是让测试去点系统设置。 */
var remindInfo = null;
function remindInfoGet() {
  try {
    if (window.Android && Android.planRemindState) return JSON.parse(Android.planRemindState());
  } catch (e) { }
  return null;
}
function askRemindPerm() {
  try { if (window.Android && Android.planRemindAsk) Android.planRemindAsk(); } catch (e) { }
}
function askBatteryOpt() {
  try { if (window.Android && Android.planBatteryAsk) Android.planBatteryAsk(); } catch (e) { }
}
/* T4：把最近 20 次 Agent 运行的三级 trace（脱敏）写到系统下载目录 */
function exportTrace() {
  try {
    if (window.Android && Android.exportTrace) {
      var r = Android.exportTrace();
      toast(r ? "诊断包已保存：" + r : "导出失败");
    } else toast("当前环境不能导出诊断包");
  } catch (e) { toast("导出失败"); }
}
window.onRemindPerm = function () {
  remindInfo = remindInfoGet();
  if (curTab === "me") renderAll();
};

function renderMe() {
  var a = stats("all");
  remindInfo = remindInfoGet();
  /* 题库为空时覆盖率的分母是 0，直接除会得到 NaN 显示在界面上 */
  var cover = a.total ? Math.round(a.seen / a.total * 100) + "%" : "—";
  document.getElementById("meStat").innerHTML =
    '<div class="metrics">' +
      '<div class="metric"><b>' + a.seen + "</b><i>已刷题数</i></div>" +
      '<div class="metric"><b>' + a.done + "%</b><i>完成率</i></div>" +
      '<div class="metric"><b>' + a.wrong + "</b><i>待消灭错题</i></div>" +
    "</div>" +
    '<div style="height:1px;background:var(--line);margin:18px 0"></div>' +
    '<div class="metrics">' +
      '<div class="metric" onclick="startSession(&quot;star&quot;,&quot;all&quot;, &quot;&quot;)"><b>' + a.mark + "</b><i>收藏 · 点击刷</i></div>" +
      '<div class="metric"><b>' + a.total + "</b><i>题库总量</i></div>" +
      '<div class="metric"><b>' + cover + "</b><i>覆盖率</i></div>" +
    "</div>";

  document.getElementById("meSet").innerHTML =
    row("dark", set.theme === "dark" ? "深色模式" : "浅色模式", "从开关处圆形扩散换肤", true, set.theme === "dark") +
    row("haptics", "震动反馈", "答对短震、答错长震", true, set.haptics) +
    row("autoExp", "答完展开解析", "关闭后判分只给答案，解析自己点开", true, set.autoExp) +
    row("import", "导入题库", "PDF / Word / txt / md，带解析一起导", false) +
    row("agent", "大肥鱼", agentSub(), false) +
    row("mcp", "MCP 连接", "127.0.0.1:8765 · 复制口令 / 重置", false) +
    row("remind", "每日提醒", "8:30 / 21:00 · 每个方案单独开关", false) +
    row("notify", "通知权限", remindInfo
      ? (remindInfo.granted ? "已允许 · 提醒可以弹出" : "未允许 · 点这里开启")
      : "当前环境不支持", false) +
    row("battery", "省电豁免", remindInfo
      ? (remindInfo.battery ? "已豁免 · 提醒更准时" : "未设置 · 系统可能压掉提醒")
      : "当前环境不支持", false) +
    row("font", "字号", "当前 " + set.fscale + "%，点击循环切换", false) +
    row("memory", "记忆", "查看画像与摘要，可清除", false) +
    row("trace", "导出诊断包", "最近 20 次 Agent 运行（脱敏）", false) +
    row("update", "检查更新", "当前 v" + APP_VER + " 版本", false, false, upd.hasNew) +
    row("clear", "清空进度", "做题记录、错题、收藏全部删除", false);
}
/* mark=true 时在标题后挂一个小红点。做成参数而不是直接改 DOM，
   这样 renderMe 每次重画列表，标记都会跟着 upd 状态重新出现 */
function row(k, title, sub, sw, on, mark) {
  var fn = k === "dark" ? "toggleSet('dark', event)"
         : k === "haptics" ? "toggleSet('haptics', event)"
         : k === "autoExp" ? "toggleSet('autoExp', event)"
         : k === "font" ? "cycleFont()"
         : k === "import" ? "openImport()"
         : k === "agent" ? "openAgentCfg()"
         : k === "mcp" ? "openMcpCfg()"
         : k === "remind" ? "openPlans()"
         : k === "notify" ? "askRemindPerm()"
         : k === "battery" ? "askBatteryOpt()"
         : k === "memory" ? "openMemory()"
         : k === "trace" ? "exportTrace()"
         : k === "update" ? "checkUpdate(true)" : "askClear()";
  return '<div class="row" data-k="' + k + '" onclick="' + fn + '"><span>' + title +
         (mark ? '<i class="dot"></i>' : "") +
         (sub ? "<em>" + sub + "</em>" : "") + "</span>" +
         (sw ? '<span style="flex:0 0 52px;text-align:right"><span class="sw' + (on ? " on" : "") + '"></span></span>'
             : '<span style="flex:0 0 20px;text-align:right;color:var(--ink-3)">›</span>') +
         "</div>";
}
function renderAll() {
  if (curTab === "home") renderHome();
  else if (curTab === "bank") { renderBank(); requestAnimationFrame(stackCards); }
  else if (curTab === "wrong") renderWrong();
  else renderMe();
}

/* 题库页卡片堆叠：顶部卡片先粘住，被后续卡片覆盖时逐层压缩、变淡 */
function stackCards() {
  var cards = [].slice.call(document.querySelectorAll("#bankList .swipe"));
  var i;
  for (i = 0; i < cards.length; i++) {
    cards[i].style.position = "sticky";
    cards[i].style.top = (72 + i * 10) + "px";
    cards[i].style.zIndex = String(10 + i);
  }
  for (i = 0; i < cards.length; i++) {
    var nx = cards[i + 1];
    if (!nx) { cards[i].style.transform = ""; cards[i].style.opacity = ""; continue; }
    var cr = cards[i].getBoundingClientRect(), nr = nx.getBoundingClientRect();
    var ov = Math.max(0, Math.min(1, (cr.bottom - nr.top) / (cr.height || 1)));
    cards[i].style.transform = "scale(" + (1 - 0.05 * ov) + ") translateY(" + (-8 * ov) + "px)";
    cards[i].style.opacity = String(1 - 0.22 * ov);
  }
}
window.addEventListener("scroll", function () { if (curTab === "bank") stackCards(); }, { passive: true });

function pickMode(mode, sid) { startSession(mode, sid, ""); }
function toggleSet(k, ev) {
  var p = pointOf(ev);
  if (k === "dark") { setTheme(set.theme === "dark" ? "light" : "dark", p.x, p.y); jiggleSiblings(k); return; }
  if (k === "haptics") set.haptics = set.haptics !== true;
  if (k === "autoExp") set.autoExp = set.autoExp !== true;
  renderAll(); save(); jiggleSiblings(k);
}
function cycleFont() {
  set.fscale = set.fscale >= 120 ? 90 : set.fscale + 10;
  applyTheme(); renderAll(); save();
  moveTabPill(false);        /* 字号一变按钮就变宽，胶囊得跟上，不然会盖不住图标 */
}
function applyTheme() {
  document.documentElement.setAttribute("data-theme", set.theme === "dark" ? "dark" : "light");
  document.documentElement.style.fontSize = (16 * set.fscale / 100) + "px";
  applyChatFs();
  /* 状态栏/导航栏的图标深浅和窗口底色在原生层，换肤时要一起告诉它 */
  if (window.Android && Android.themeHint) { try { Android.themeHint(set.theme !== "dark"); } catch (e) {} }
}
/* 原生量到的系统栏高度（物理像素），换算成 CSS px 塞给 --sat/--sab */
window.__insets = function (top, bottom) {
  var d = devicePixelRatio || 1, s = document.documentElement.style;
  s.setProperty("--sat", (top / d) + "px");
  s.setProperty("--sab", (bottom / d) + "px");
};

/* 圆形扩散换肤：新内核走 View Transitions，只裁切整页快照，题干/选项/卡片本身不缩放不变形；
   老 Android WebView 没有该 API，退化成从触点扩散的开肤环 */
function pointOf(ev) {
  if (ev && ev.touches && ev.touches[0]) return { x: ev.touches[0].clientX, y: ev.touches[0].clientY };
  if (ev && ev.clientX) return { x: ev.clientX, y: ev.clientY };
  return { x: innerWidth / 2, y: 60 };
}
var reveal = null, waveSeq = 0;
/* 老内核没有 View Transitions，就自己造一份"换肤后的界面快照"：把当前 DOM 复制进一层容器，
   容器挂上目标主题的变量。扩散波携带的是这份快照，圆扫过的地方看到的是新界面本身，
   而不是一块把内容糊掉的纯色——上一版就是栽在这里。
   代价先登记在案，别等下一轮又当新发现：真机 Chrome 80、停在「我的」页、暗↔亮各一次，
   这条路径丢 6~9 帧、单帧最长 166.7ms；换成只动 transform/opacity 的动效可以一帧不丢。
   保留它的理由是用户 2026-10-20 的裁定：不接受整页颜色插值（两套色板之间会路过一层发闷的灰）。 */
function themedSnapshot(next) {
  var l = document.createElement("div");
  l.className = "reveal";
  l.setAttribute("data-theme", next);
  l.setAttribute("aria-hidden", "true");
  var kids = document.body.children;
  for (var i = 0; i < kids.length; i++) {
    if (kids[i].tagName === "SCRIPT") continue;
    l.appendChild(kids[i].cloneNode(true));
  }
  return l;
}
function setTheme(next, x, y) {
  if (next === set.theme) return;
  var my = ++waveSeq;                       /* 本轮令牌：晚到的闭包一律只认自己这一轮 */
  var r = Math.ceil(Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y)));
  /* 状态和配色分两步：开关、标题当场跟着手指翻过去，颜色交给转场。
     上一版把两件事一起压到波形结束（420ms）之后，点下去界面毫无反应，看着就是卡顿。
     全篇只有这一行的标题/开关读 set.theme，其余界面全是 CSS 变量，不用重画。 */
  set.theme = next;
  if (curTab === "me") renderMe();
  save();
  var painted = false;
  function paint() {
    if (my !== waveSeq || painted) return;
    painted = true;
    /* 换色这一帧要掐掉元素自己的过渡。快照接着就要淡出，真界面如果还在 .2s 里往新色爬，
       淡完露出来的是爬到一半的卡片——首页那六张分类卡闪深浅，栽的就是这一格
       （.cat 上挂着 background/color .2s、box-shadow .24s，量到换色后 690→788ms 才爬到位）。
       摘除放在两帧之后：rAF 回调跑在本帧样式计算之前，只等一帧等于什么都没掐住。 */
    document.documentElement.classList.add("swap");
    applyTheme();
    requestAnimationFrame(function () {
      requestAnimationFrame(function () { document.documentElement.classList.remove("swap"); });
    });
  }
  if (document.startViewTransition) {
    var st = document.documentElement.style;
    st.setProperty("--vx", x + "px");
    st.setProperty("--vy", y + "px");
    st.setProperty("--vr", r + "px");
    document.startViewTransition(paint);
    /* 兜底：某些 WebView 会把转场直接 skip，不能让配色卡在旧值上 */
    setTimeout(paint, 900);
  } else {
    /* 颜色由波携带着走：盖满整屏那一刻才真正换色，然后快照淡出露出真界面。 */
    if (reveal) reveal.remove();
    var o = themedSnapshot(next);            /* DOM 已是新状态：波里波外读到的标题一致 */
    document.body.appendChild(o);
    reveal = o;
    o.scrollTop = window.pageYOffset || document.documentElement.scrollTop || 0;
    var at = " at " + x + "px " + y + "px";
    var WAVE = 420, FADE = 150;
    if (o.animate) {
      o.animate([{ clipPath: "circle(0px" + at + ")" }, { clipPath: "circle(" + r + "px" + at + ")" }],
        { duration: WAVE, easing: "cubic-bezier(.4, 0, .2, 1)", fill: "forwards" });
    } else {
      o.style.clipPath = "circle(" + r + "px" + at + ")";
    }
    /* Chrome 80 只把 playState 推到 finished、不派发 Animation.onfinish，所以推进只能按时间走；
       另加一道硬兜底，任何情况下都不允许快照层留在屏上糊住界面。 */
    setTimeout(function () {
      if (my !== waveSeq) return;
      paint();                               /* 此刻整屏已被新界面盖住，换色看不见跳变 */
      o.style.transition = "opacity " + FADE + "ms ease-out";
      o.style.opacity = "0";
      setTimeout(function () {
        if (o.parentNode) o.remove();
        if (reveal === o) reveal = null;
      }, FADE + 60);
    }, WAVE);
    setTimeout(function () {
      if (my !== waveSeq) { if (o.parentNode) o.remove(); return; }
      paint();
      if (o.parentNode) o.remove();
      if (reveal === o) reveal = null;
    }, 1400);
  }
}
function onThemeTap(ev) {
  var p = pointOf(ev);
  setTheme(set.theme === "dark" ? "light" : "dark", p.x, p.y);
}
/* 开关组联动：同组其他开关按距离错峰轻震，只动不改变状态 */
function jiggleSiblings(k) {
  var rows = [].slice.call(document.querySelectorAll("#meSet .row"));
  var at = -1;
  rows.forEach(function (r, i) { if (r.getAttribute("data-k") === k) at = i; });
  if (at < 0) return;
  rows.forEach(function (r, i) {
    if (i === at) return;
    setTimeout(function () {
      r.classList.add("jig");
      setTimeout(function () { r.classList.remove("jig"); }, 320);
    }, Math.abs(i - at) * 70);
  });
}
/* 清空「这一个题库」的进度：只抹做题记录与续刷快照，
   错题本标记和收藏保留——那是复习材料，不该被“重刷”顺手清掉。 */
function askClearBank() {
  var sid = S && S.subject;
  var s = subjectOf(sid);
  if (!sid || !s) { toast("现在没有打开的题库"); return; }
  var t = stats(sid);
  glass({
    title: "清空「" + s.name + "」的进度？",
    desc: t.seen
      ? "这个题库已刷 " + t.seen + " / " + t.total + " 题，清空后会回到未刷状态，方便重刷；错题本和收藏保留"
      : "这个题库还没有做题记录，清空不会有变化；错题本和收藏保留",
    ok: ["确认清空", function () { clearBankProgress(sid); }]
  });
}
function clearBankProgress(sid) {
  var s = subjectOf(sid);
  if (!sid || !s) return;
  BANK.questions.forEach(function (q) {
    if (q.subject !== sid) return;
    var p = prog[q.id];
    if (!p) return;
    /* 错题/收藏留成空进度：inWrong 与收藏都读 prog，直接删会把它们一起抹掉 */
    var keepIn = inWrong(q) ? 1 : 0, keepB = p.b ? 1 : 0;
    if (keepIn || keepB) prog[q.id] = { n: 0, w: 0, last: 0, t: 0, b: keepB, in: keepIn };
    else delete prog[q.id];
  });
  if (S && S.subject === sid) {
    S.idx = 0;
    S.picks = {}; S.graded = {}; S.text = {}; S.self = {}; S.sub = {}; S.refShut = {};
    S.state = ""; S.doneAt = 0;
  }
  progMayEmpty = (progCount(prog) === 0);
  save();
  /* save() 会按当前会话再写一份快照，这里在它之后把这个库的快照删掉 */
  delete resume[sid];
  store.write(LS.r, resume);
  if (S && S.subject === sid) render();
  renderAll();
  toast("已清空「" + s.name + "」的进度");
}
function askClear() {
  glass({ title: "清空全部进度？", desc: "做题记录、错题本、收藏都会被删除，无法恢复",
          ok: ["确认清空", function () { progMayEmpty = true; prog = {}; localStorage.removeItem(LS.p); renderAll(); toast("已清空"); }] });
}
