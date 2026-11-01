"use strict";
/* ================= 方案域（P1） =================
   数据只在原生 PlanStore 里（filesDir/plans/plans.json），页面不另存一份：
   首页 Hero、方案库、方案详情、MCP 工具读的都是同一个 rev 与同一份派生字段。
   改方案一律走 planWrite：rev 冲突时先重读再重试一次，冲突不静默。 */

var PLAN = { rev: 0, list: [], full: {}, ready: false, err: "" };

/* 与 PlanStore.ACTIONS 逐字一致；MCP schema 的枚举也引用这一份。 */
var PLAN_ACTIONS = ["pause", "resume", "rename", "remind_on", "remind_off",
                    "add_item", "remove_item", "toggle_item", "set_days"];

function planJson(s) {
  try { return JSON.parse(s); } catch (e) { return null; }
}

function planTodayKey() {
  var d = new Date();
  function p2(n) { return n < 10 ? "0" + n : "" + n; }
  return d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate());
}

function planDayLabel() {
  var d = new Date(), w = "日一二三四五六".charAt(d.getDay());
  return (d.getMonth() + 1) + "/" + d.getDate() + " 周" + w;
}

/* 读全部方案摘要。rev 变了就丢掉整份缓存：别处（MCP/另一个页面）改过时，
   详情页不能拿旧的 days 继续渲染。 */
function planLoad() {
  PLAN.err = "";
  if (!window.Android || !Android.planSnapshot) {
    PLAN.rev = 0; PLAN.list = []; PLAN.ready = false;
    return;
  }
  var o = planJson(Android.planSnapshot());
  if (!o || o.error) {
    PLAN.err = (o && o.error) || "方案库读不出来";
    PLAN.list = []; PLAN.ready = false;
    return;
  }
  var nr = Number(o.rev) || 0;
  if (nr !== PLAN.rev) PLAN.full = {};
  PLAN.rev = nr;
  PLAN.list = Array.isArray(o.plans) ? o.plans : [];
  PLAN.ready = true;
  if (PLAN.err) PLAN.err = "";
}

function planFind(id) {
  for (var i = 0; i < PLAN.list.length; i++) {
    if (PLAN.list[i].id === id) return PLAN.list[i];
  }
  return null;
}

/* 详情页要逐日内容；摘要故意不带 days，所以按 id 单取并按 rev 缓存。 */
function planFull(id) {
  if (PLAN.full[id]) return PLAN.full[id];
  if (!window.Android || !Android.planGet) return null;
  var o = planJson(Android.planGet(id));
  if (!o || o.error || !o.plan) return null;
  PLAN.full[id] = o.plan;
  return o.plan;
}

/* 所有写操作都从这里过：rev 冲突 → 重读一次再试；再冲突就把错误交回调用方。 */
function planWrite(id, action, args, retried) {
  if (!window.Android || !Android.planUpdate) return { error: "当前环境不能改方案" };
  var out = planJson(Android.planUpdate(id, action, JSON.stringify(args || {}), PLAN.rev));
  if (!out) return { error: "方案库没有回包" };
  if (out.rev === -1 && !retried) {
    planLoad();
    return planWrite(id, action, args, true);
  }
  if (out.error) return out;
  var full = out.plan || null;
  planLoad();
  if (full) PLAN.full[id] = full;
  planPaint();
  return out;
}

function planDeleteCall(id) {
  if (!window.Android || !Android.planDelete) return { error: "当前环境不能改方案" };
  var out = planJson(Android.planDelete(id, PLAN.rev));
  if (out && out.rev === -1) {
    planLoad();
    out = planJson(Android.planDelete(id, PLAN.rev));
  }
  if (!out) return { error: "方案库没有回包" };
  if (!out.error) { delete PLAN.full[id]; planLoad(); }
  return out;
}

/* 写完之后重画：首页 Hero、方案库/详情、以及正在看的这一页都要跟上。 */
function planPaint() {
  if (curTab === "home") renderHome();
  var pg = document.getElementById("planPage");
  if (pg && pg.classList && pg.classList.contains("on")) {
    if (planView.mode === "detail" && planView.id) planRenderDetail(planView.id);
    else planRenderList();
  }
}

/* ================= 首页 Hero ================= */

function planStatsOf(p) {
  var done = 0, total = 0, todayDone = 0, todayTotal = 0, executed = 0, t, i, k;
  var today = planTodayKey();
  var days = p && Array.isArray(p.days) ? p.days : [];
  for (i = 0; i < days.length; i++) {
    var d = days[i] || {}, items = Array.isArray(d.items) ? d.items : [];
    var dd = 0;
    for (k = 0; k < items.length; k++) {
      total++;
      if (items[k] && items[k].done) { done++; dd++; }
    }
    if (d.date === today) { todayTotal += items.length; todayDone += dd; }
    if (d.date < today || (items.length && dd === items.length)) executed++;
  }
  return { done: done, total: total, todayDone: todayDone, todayTotal: todayTotal,
           pct: total ? Math.round(done * 100 / total) : 0, executed: executed, days: days.length };
}

function planHeroHtml() {
  planLoad();
  var rows = [], i;
  for (i = 0; i < PLAN.list.length; i++) if (PLAN.list[i].enabled) rows.push(PLAN.list[i]);

  var todayTotal = 0, todayDone = 0, top = null;
  for (i = 0; i < rows.length; i++) {
    var t = Array.isArray(rows[i].today) ? rows[i].today : [], k, u = 0;
    for (k = 0; k < t.length; k++) if (!t[k].done) u++;
    todayTotal += t.length;
    todayDone += t.length - u;
    if (u > 0 && !top) top = rows[i];
  }

  var h = '<div class="card pad pl-hero">';
  h += '<div class="pl-hero-top"><span class="pl-hero-k">今日</span>' +
       '<span class="pl-hero-d">' + planDayLabel() + "</span></div>";
  if (!rows.length) {
    h += '<div class="pl-empty"><b>还没有进行中的方案</b>' +
         "<span>把目标拆成每天能做完的几件事，和刷题放在同一张卡里。</span></div>" +
         '<div class="pl-cta"><button class="pl-go" onclick="planCreateSheet()">新建方案</button>' +
         '<button class="pl-alt" onclick="openPlans()">方案库</button></div>';
    return h + "</div>";
  }

  var left = todayTotal - todayDone;
  if (todayTotal) {
    var pct = todayTotal ? Math.round(todayDone * 100 / todayTotal) : 0;
    h += '<div class="pl-hero-main"><b class="pl-hero-num">' + left + '</b>' +
         '<span class="pl-hero-unit">项没做</span>' +
         '<span class="pl-hero-of">' + todayDone + " / " + todayTotal + " 已完成</span></div>" +
         '<div class="pl-bar"><i style="width:' + pct + '%"></i></div>';
  } else {
    h += '<div class="pl-hero-main"><b class="pl-hero-num">' + rows.length + '</b>' +
         '<span class="pl-hero-unit">个方案进行中</span>' +
         '<span class="pl-hero-of">今天没有排期</span></div>';
  }

  h += '<div class="pl-chips">';
  var shown = rows.slice(0, 3);
  for (i = 0; i < shown.length; i++) {
    h += '<button class="pl-chip" onclick="openPlan(&quot;' + esc(shown[i].id) + '&quot;)">' +
         "<b>" + esc(shown[i].title) + "</b><span>" + (Number(shown[i].pct) || 0) + "%</span></button>";
  }
  if (rows.length > shown.length) {
    h += '<button class="pl-chip" onclick="openPlans()">+' + (rows.length - shown.length) + "</button>";
  }
  h += "</div>";

  h += '<div class="pl-cta"><button class="pl-go" onclick="planToday()">' +
       (top ? "开始今日" : "去练习") + "</button>" +
       '<button class="pl-alt" onclick="openPlans()">方案库</button></div>';
  return h + "</div>";
}

/* Hero 主 CTA：有今天的方案项就进方案，没有就回到题库接着刷。 */
function planToday() {
  planLoad();
  for (var i = 0; i < PLAN.list.length; i++) {
    var p = PLAN.list[i], t = Array.isArray(p.today) ? p.today : [], k;
    if (!p.enabled) continue;
    for (k = 0; k < t.length; k++) if (!t[k].done) { openPlan(p.id); return; }
  }
  if (!BANK.subjects.length) { toast("先导入一个题库，或者在方案里新建一个目标"); return; }
  startSession("practice", "all", "", "home");
}

/* ================= 方案库 / 详情 ================= */

var planView = { mode: "", id: "" };

function planPageEl() { return document.getElementById("planPage"); }

function planShow(html, mode, id) {
  var pg = planPageEl();
  if (!pg) return;
  /* 打卡/调整后会在原页重画：同一份详情要保住滚动位置，
     否则点时间线靠下的某一项，页面会把它弹回顶部。 */
  var same = planView.mode === mode && planView.id === (id || "");
  var keepTop = same ? pg.scrollTop : 0;
  pg.innerHTML = html;
  pg.classList.add("on");
  if (keepTop) pg.scrollTop = keepTop;
  if (openLayers.indexOf(pg) < 0) openLayers.push(pg);
  /* 方案页自己不是遮罩层：重画时顺手把上一层遗留的遮罩收掉 */
  if (typeof syncLayerScrim === "function") syncLayerScrim();
  planView = { mode: mode, id: id || "" };
  planHash(mode, id);
}

function planHash(mode, id) {
  if (!window.history || !window.history.replaceState || !window.location) return;
  try {
    var h = mode === "detail" ? "#/plan/" + id : (mode === "list" ? "#/plans" : "");
    window.history.replaceState(null, "", h || window.location.pathname);
  } catch (e) { /* file:// 下个别内核不让改，路由退化成页面内状态 */ }
}

/* 关闭方案页时清掉地址栏里的路由，免得下次冷启动又弹回详情。 */
function planPageClosed() {
  if (!planView.mode) return;
  planView = { mode: "", id: "" };
  planHash("", "");
}

/* 返回键：详情先回列表，再按一次才关页面。 */
function planBack() {
  if (planView.mode === "detail") { openPlans(); return true; }
  return false;
}

/* 冷启动深链：#/plan/<id> 直接进详情，#/plans 进列表。 */
function planDeepLink() {
  if (!window.location) return false;
  var h = String(window.location.hash || "");
  var m = h.match(/^#?\/?plan\/(.+)$/);
  if (m) { openPlan(decodeURIComponent(m[1])); return true; }
  if (/^#?\/?plans$/.test(h)) { openPlans(); return true; }
  return false;
}

function openPlans() {
  planLoad();
  planRenderList();
}

function planRenderList() {
  planLoad();
  var h = '<div class="fs-head"><button onclick="closeTop()">' + planBackIcon() + "</button>" +
          "<h2>方案</h2><button onclick=\"planCreateSheet()\">" + planPlusIcon() + "</button></div>" +
          '<div class="fs-body">';
  if (PLAN.err) h += '<div class="cap">' + esc(PLAN.err) + "</div>";
  if (!PLAN.list.length) {
    h += '<div class="card pad pl-empty"><b>还没有方案</b>' +
         "<span>从一个目标开始，拆成每天能做完的几件事；建立后可以逐条改。</span>" +
         '<button class="sheet-act" onclick="planCreateSheet()">新建方案</button></div>';
  } else {
    for (var i = 0; i < PLAN.list.length; i++) h += planCardHtml(PLAN.list[i]);
  }
  h += "</div>";
  planShow(h, "list", "");
}

function planCardHtml(p) {
  var t = Array.isArray(p.today) ? p.today : [], left = 0, i;
  for (i = 0; i < t.length; i++) if (!t[i].done) left++;
  var pct = Math.max(0, Math.min(100, Number(p.pct) || 0));
  var state = !p.enabled ? "已暂停" : (pct >= 100 ? "已完成" : "执行中");
  return '<button class="card pad pl-card" onclick="openPlan(&quot;' + esc(p.id) + '&quot;)">' +
    '<div class="pl-card-top"><b>' + esc(p.title) + '</b><span class="pl-state' +
      (p.enabled ? "" : " off") + '">' + state + "</span></div>" +
    '<div class="pl-goal">' + esc(p.goal) + "</div>" +
    '<div class="pl-bar"><i style="width:' + pct + '%"></i></div>' +
    '<div class="pl-meta"><span>' + (Number(p.done) || 0) + " / " + (Number(p.total) || 0) + " 项</span>" +
      "<span>" + pct + "%</span>" +
      "<span>" + (t.length ? "今日剩 " + left + " 项" : "今天没排期") + "</span></div>" +
    "</button>";
}

function openPlan(id) {
  var p = planFull(id);
  if (!p) {
    planLoad();
    p = planFull(id);
  }
  if (!p) { toast("方案不存在或读不出来"); return; }
  planRenderDetail(id);
}

function planRenderDetail(id) {
  var p = planFull(id);
  if (!p) { openPlans(); toast("方案不存在"); return; }
  var st = planStatsOf(p);
  var today = planTodayKey();
  var h = '<div class="fs-head"><button onclick="openPlans()">' + planBackIcon() + "</button>" +
          "<h2>" + esc(p.title) + '</h2><button class="pl-power" onclick="planPowerToggle(&quot;' +
          esc(p.id) + '&quot;)">' + (p.enabled ? "暂停" : "恢复") + "</button></div>" +
          '<div class="fs-body">';

  h += '<div class="card pad pl-ov"><div class="pl-goal">' + esc(p.goal) + "</div>" +
       '<div class="pl-nums">' +
         "<div><b>" + st.pct + "%</b><i>完成度</i></div>" +
         "<div><b>" + st.done + " / " + st.total + "</b><i>完成项</i></div>" +
         "<div><b>" + st.executed + " / " + st.days + "</b><i>已执行天</i></div>" +
       "</div>" +
       '<div class="pl-bar"><i style="width:' + st.pct + '%"></i></div></div>';

  /* 动作放在概览下面：方案排到 60 天时，按钮跟着时间线沉到底部就够不着了 */
  h += '<div class="pl-acts">' +
       '<button onclick="planAdjustSheet(&quot;' + esc(p.id) + '&quot;)">调整今日</button>' +
       '<button onclick="planRemindToggle(&quot;' + esc(p.id) + '&quot;)">' +
         (p.remind ? "关闭提醒" : "每日提醒") + "</button>" +
       '<button onclick="planInsight(&quot;' + esc(p.id) + '&quot;)">问大肥鱼</button>' +
       '<button class="danger" onclick="planDeleteAsk(&quot;' + esc(p.id) + '&quot;)">删除方案</button>' +
       "</div>";

  var day = planDayOf(p, today);
  h += '<div class="sec-title"><b>今天</b><span>' + today + "</span></div>";
  if (day && day.items.length) {
    h += '<div class="card pad" style="padding:6px 18px">' + planItemsHtml(p, day, p.enabled) + "</div>";
  } else {
    h += '<div class="cap">今天没有排期，去「其他日程」看看后面几天。</div>';
  }

  /* 「今天」已经单独占了一块，时间线里不再重复画一遍 */
  var others = 0, i;
  for (i = 0; i < p.days.length; i++) if (p.days[i].date !== today) others++;
  h += '<div class="sec-title"><b>' + (day ? "其他日程" : "全部日程") + "</b><span>" +
       others + " 天</span></div>";
  h += '<div class="pl-days">';
  for (i = 0; i < p.days.length; i++) {
    if (p.days[i].date === today) continue;
    h += planDayCard(p, p.days[i], today);
  }
  h += "</div>";

  h += '<div class="sec-title"><b>调整记录</b><span>' + p.adjustments.length + " 条</span></div>" +
       '<div class="card pad pl-adj">';
  var adj = p.adjustments.slice(-6);
  for (var k = 0; k < adj.length; k++) {
    var a = adj[k] || {};
    h += "<div><b>" + esc(a.reason || "") + "</b>　" + esc(a.change || "") + "</div>";
  }
  h += "</div>";
  h += "</div>";
  planShow(h, "detail", p.id);
}

function planDayOf(p, date) {
  var days = p && Array.isArray(p.days) ? p.days : [];
  for (var i = 0; i < days.length; i++) if (days[i] && days[i].date === date) return days[i];
  return null;
}

/* 任务文本可能带引号（模型/用户写的），不能拼进 onclick 的 JS 字符串里：
   走 data-* 交给 getAttribute 还原，HTML 实体只解一层，不会破句子。 */
function planItemsHtml(p, day, interactive) {
  var h = "", today = planTodayKey();
  for (var i = 0; i < day.items.length; i++) {
    var it = day.items[i] || {};
    var on = !!it.done;
    var can = interactive && day.date <= today;
    h += "<" + (can ? "button" : "div") + ' class="pl-item' + (on ? " done" : "") +
         (day.date > today ? " future" : "") + '"' +
         (can ? ' data-pid="' + esc(p.id) + '" data-date="' + esc(day.date) +
                '" data-text="' + esc(it.text) + '" onclick="planItemTap(this)"' : "") + ">" +
         '<span class="pl-box"></span><span class="pl-text">' + esc(it.text) + "</span>" +
         "</" + (can ? "button" : "div") + ">";
  }
  return h || '<div class="cap" style="margin:8px 0">这一天没有任务</div>';
}

function planItemTap(el) {
  if (!el || !el.getAttribute) return;
  planToggle(el.getAttribute("data-pid"), el.getAttribute("data-date"), el.getAttribute("data-text"));
}

function planRemoveTap(el) {
  if (!el || !el.getAttribute) return;
  planRemoveItem(el.getAttribute("data-pid"), el.getAttribute("data-text"));
}

function planDayCard(p, d, today) {
  var done = 0, i;
  for (i = 0; i < d.items.length; i++) if (d.items[i] && d.items[i].done) done++;
  var all = d.items.length && done === d.items.length;
  var tag = d.date === today ? "今天" : (all ? "已完成" : (d.date < today ? "已过期" : "待执行"));
  return '<div class="pl-day' + (d.date === today ? " today" : "") + '">' +
    '<div class="pl-day-head"><b>' + esc(d.date.slice(5)) + "</b><span>" + tag +
      " · " + done + "/" + d.items.length + "</span></div>" +
    '<div class="card pad" style="padding:8px 18px">' + planItemsHtml(p, d, p.enabled) + "</div></div>";
}

/* ================= 写操作入口 ================= */

function planToggle(id, date, text) {
  var p = planFull(id);
  if (!p) return;
  var d = planDayOf(p, date);
  if (!d) { toast("找不到这一天"); return; }
  var it = null;
  for (var i = 0; i < d.items.length; i++) if (d.items[i].text === text) { it = d.items[i]; break; }
  if (!it) { toast("找不到这一项"); return; }
  var out = planWrite(id, "toggle_item", { date: date, text: text, done: !it.done });
  if (out.error) { toast(out.error); return null; }
  vibrate(8);
  return !it.done;   /* 新状态，供卡片按钮即时更新 */
}

function planPowerToggle(id) {
  var p = planFull(id);
  if (!p) return;
  var out = planWrite(id, p.enabled ? "pause" : "resume", {});
  if (out.error) toast(out.error);
  else toast(p.enabled ? "方案已暂停" : "方案已恢复");
}

function planRemindToggle(id) {
  var p = planFull(id);
  if (!p) return;
  var out = planWrite(id, p.remind ? "remind_off" : "remind_on", {});
  if (out.error) toast(out.error);
  else toast(p.remind ? "已关闭每日提醒" : "已开启每日提醒（8:30 / 21:00）");
}

function planInsight(id) {
  var p = planFull(id);
  if (!p) return;
  if (chatBusy) { toast("大肥鱼还在回上一条，等它说完再问"); return; }
  var st = planStatsOf(p);
  chatOuiOpenPanel();
  chatAsk("看看我的方案「" + p.title + "」：目标 " + p.goal + "；进度 " + st.done + "/" + st.total +
          " 项（" + st.pct + "%），今天 " + st.todayDone + "/" + st.todayTotal +
          " 项已完成。结合现在的情况，下一阶段最该先做什么？");
}

/* ================= 新建 / 调整 ================= */

function planCreateSheet() {
  var sh = document.getElementById("sheet3");
  if (!sh) return;
  sh.innerHTML = '<div class="handle"></div><h2>新建方案</h2>' +
    '<label class="pl-field-l">目标</label>' +
    '<textarea class="pl-field" id="planGoal" rows="3" placeholder="例如：两周把线性代数错题刷完"></textarea>' +
    '<label class="pl-field-l">天数</label>' +
    '<input class="pl-field" id="planDays" type="number" min="1" max="60" value="7">' +
    '<label class="pl-field-l">标题（可空）</label>' +
    '<input class="pl-field" id="planTitle" maxlength="30" placeholder="默认取目标前 14 字">' +
    '<button class="sheet-act" onclick="planCreateGo()">按天拆解</button>' +
    '<div class="sl-hint" style="margin:16px 0 0">先按模板拆成每日任务，建立后可在详情里逐条改。</div>';
  openLayer(sh);
  setTimeout(function () { var b = document.getElementById("planGoal"); if (b) b.focus(); }, 320);
}

/* 面板收起时键盘不会自己退；不 blur 的话它会盖住刚打开的方案详情 */
function planBlur() {
  try {
    if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  } catch (e) { }
}

/* 建立方案：页面表单与 MCP create_plan 共用这一条，rev 冲突重读一次。 */
function planCreateCall(goal, days, title, daysJson) {
  if (!window.Android || !Android.planCreate) return { error: "当前环境不能保存方案" };
  var out = planJson(Android.planCreate(goal, days, title, daysJson || "", PLAN.rev));
  if (out && out.rev === -1) {
    planLoad();
    out = planJson(Android.planCreate(goal, days, title, daysJson || "", PLAN.rev));
  }
  if (!out) return { error: "方案库没有回包" };
  if (!out.error && out.plan) {
    var id = out.plan.id;
    planLoad();
    PLAN.full[id] = out.plan;
  }
  return out;
}

function planCreateGo() {
  var goalBox = document.getElementById("planGoal");
  var daysBox = document.getElementById("planDays");
  var titleBox = document.getElementById("planTitle");
  var goal = goalBox ? String(goalBox.value || "").trim() : "";
  var days = daysBox ? parseInt(daysBox.value, 10) : 7;
  var title = titleBox ? String(titleBox.value || "").trim() : "";
  if (!goal) { toast("先写一个目标"); return; }
  if (!(days >= 1)) days = 7;
  if (days > 60) days = 60;
  var out = planCreateCall(goal, days, title, "");
  if (out.error) { toast(out.error); return; }
  var id = out.plan && out.plan.id;
  planBlur();
  closeTop();
  toast("方案已建立");
  if (id) openPlan(id);
}

/* ---- 图标（与站内其他 fs-head 同一套线性风格） ---- */
function planBackIcon() {
  return '<svg class="ico" viewBox="0 0 24 24"><path d="M14.5 5.5L8 12l6.5 6.5"/></svg>';
}
function planPlusIcon() {
  return '<svg class="ico" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg>';
}

/* ================= MCP 计划工具执行（S4/P2） =================
   schema 已移到 Java ToolRegistry（唯一真源），这里只保留执行；
   写操作一律走 planWrite / planCreateCall / planDeleteCall，
   与页面按钮共享同一套 rev 校验与重试。 */
function mcpPlanNotify(title, body) {
  try {
    if (window.Android && Android.notifyPhone) return Android.notifyPhone(title, body) === true;
  } catch (e) { }
  return false;
}

/* 与页面按钮同一套写操作；抛错由 mcpDispatch 统一包成工具错误。 */
function mcpPlanTool(name, a) {
  a = a || {};
  planLoad();
  if (name === "list_plans") {
    return { rev: PLAN.rev, plans: PLAN.list };
  }
  if (name === "create_plan") {
    var goal = String(a.goal == null ? "" : a.goal).trim();
    if (!goal) throw new Error("goal 不能为空");
    var days = parseInt(a.totalDays, 10);
    if (!(days >= 1)) days = 7;
    if (days > 60) days = 60;
    var title = String(a.title == null ? "" : a.title).trim();
    var daysJson = "";
    if (a.days !== undefined) {
      if (!Array.isArray(a.days)) throw new Error("days 必须是数组");
      daysJson = JSON.stringify(a.days);
    }
    var out = planCreateCall(goal, days, title, daysJson);
    if (out.error) throw new Error(out.error);
    var p = out.plan;
    var notified = false;
    if (a.notify !== false) {
      var note = String(a.note == null ? "" : a.note).trim() ||
                 "「" + p.title + "」共 " + p.totalDays + " 天，今天开始执行。";
      notified = mcpPlanNotify("新方案已建立", note);
    }
    return { plan: p, rev: out.rev, notified: notified };
  }
  if (name === "update_plan") {
    var id = String(a.planId == null ? "" : a.planId).trim();
    if (!id) throw new Error("planId 不能为空");
    var action = String(a.action == null ? "" : a.action);
    if (PLAN_ACTIONS.indexOf(action) < 0) {
      throw new Error("未知 action：" + action + "（可用：" + PLAN_ACTIONS.join(" / ") + "）");
    }
    var args = {};
    if (a.date !== undefined) args.date = String(a.date);
    if (a.text !== undefined) args.text = String(a.text);
    if (a.title !== undefined) args.title = String(a.title);
    if (a.done !== undefined) args.done = !!a.done;
    if (a.days !== undefined) {
      if (!Array.isArray(a.days)) throw new Error("days 必须是数组");
      args.days = a.days;
    }
    var up = planWrite(id, action, args);
    if (up.error) throw new Error(up.error);
    var notifiedU = false;
    if (a.notify === true) {
      var noteU = String(a.note == null ? "" : a.note).trim() ||
                  "「" + ((up.plan && up.plan.title) || id) + "」已经调整。";
      notifiedU = mcpPlanNotify("方案已更新", noteU);
    }
    return { plan: up.plan, rev: up.rev, notified: notifiedU };
  }
  if (name === "delete_plan") {
    var did = String(a.planId == null ? "" : a.planId).trim();
    if (!did) throw new Error("planId 不能为空");
    var del = planDeleteCall(did);
    if (del.error) throw new Error(del.error);
    return { deleted: del.plan, rev: del.rev };
  }
  if (name === "notify_phone") {
    var body = String(a.body == null ? "" : a.body).trim();
    if (!body) throw new Error("body 不能为空");
    var t = String(a.title == null ? "" : a.title).trim() || "方案提醒";
    return { posted: mcpPlanNotify(t, body) };
  }
  throw new Error("未知工具：" + name);
}

/* S5：把「进行中方案 top1 + 今日完成度」作为一段独立 system 消息注入。
   固定段 chatSysBase() 不掺任何方案内容，前缀缓存照旧命中。 */
function chatPlanMsg() {
  planLoad();
  var active = [], i;
  for (i = 0; i < PLAN.list.length; i++) if (PLAN.list[i].enabled) active.push(PLAN.list[i]);
  if (!active.length) return "";
  var top = null, td = 0, tt = 0;
  for (i = 0; i < active.length; i++) {
    var p = active[i], t = Array.isArray(p.today) ? p.today : [], k, un = 0;
    for (k = 0; k < t.length; k++) {
      tt++;
      if (t[k].done) td++; else un++;
    }
    if (un > 0 && !top) top = active[i];
  }
  if (!top) top = active[0];
  var s = "进行中方案（仅作背景，不要主动改动）：最该推进的是「" + top.title + "」，整体 " +
          (top.pct || 0) + "%（" + (top.done || 0) + "/" + (top.total || 0) + " 项）";
  if (tt) s += "；今天所有方案共 " + td + "/" + tt + " 项完成";
  if (active.length > 1) s += "；另有 " + (active.length - 1) + " 个进行中方案";
  return s + "。";
}

function planAdjustSheet(id) {
  var p = planFull(id);
  if (!p) return;
  var today = planTodayKey();
  var day = planDayOf(p, today);
  var sh = document.getElementById("sheet3");
  if (!sh) return;
  var h = '<div class="handle"></div><h2>调整方案</h2>' +
    '<label class="pl-field-l">标题</label>' +
    '<input class="pl-field" id="planNewTitle" maxlength="30" value="' + esc(p.title) + '">' +
    '<button class="sheet-act" onclick="planRename(&quot;' + esc(id) + '&quot;)">保存标题</button>';
  if (day) {
    h += '<div class="sec-title" style="margin:22px 0 4px"><b>今天 · ' + today.slice(5) + "</b></div>";
    for (var i = 0; i < day.items.length; i++) {
      h += '<div class="pl-row"><span>' + esc(day.items[i].text) + "</span>" +
           '<button data-pid="' + esc(id) + '" data-text="' + esc(day.items[i].text) +
           '" onclick="planRemoveTap(this)">删除</button></div>';
    }
    h += '<input class="pl-field" id="planAddText" style="margin-top:14px" placeholder="新增一条今日任务">' +
         '<button class="sheet-act" onclick="planAddItem(&quot;' + esc(id) + '&quot;)">添加</button>';
  } else {
    h += '<div class="cap" style="margin:20px 0 0">今天没有排期，改不了今日任务。</div>';
  }
  h += '<div class="sl-hint" style="margin:16px 0 0">删除和新增都会记进调整记录。</div>';
  sh.innerHTML = h;
  openLayer(sh);
  setTimeout(function () { var b = document.getElementById("planNewTitle"); if (b) b.focus(); }, 320);
}

function planRename(id) {
  var box = document.getElementById("planNewTitle");
  var v = box ? String(box.value || "").trim() : "";
  if (!v) { toast("标题别空着"); return; }
  var out = planWrite(id, "rename", { title: v });
  if (out.error) { toast(out.error); return; }
  planBlur();
  closeTop();
  toast("标题已改");
}

function planAddItem(id) {
  var box = document.getElementById("planAddText");
  var v = box ? String(box.value || "").trim() : "";
  if (!v) { toast("先写一条任务"); return; }
  var out = planWrite(id, "add_item", { date: planTodayKey(), text: v });
  if (out.error) { toast(out.error); return; }
  planBlur();
  closeTop();
  toast("已加到今天");
}

function planRemoveItem(id, text) {
  var out = planWrite(id, "remove_item", { date: planTodayKey(), text: text });
  if (out.error) { toast(out.error); return; }
  planBlur();
  closeTop();
  toast("已删除这一项");
}

function planDeleteAsk(id) {
  var p = planFull(id);
  if (!p) return;
  glass({
    title: "删除方案",
    desc: "「" + p.title + "」和它的执行记录会一起删除，不能恢复。",
    no: ["取消", null],
    ok: ["删除", function () {
      var out = planDeleteCall(id);
      if (out.error) { toast(out.error); return; }
      toast("方案已删除");
      openPlans();
    }]
  });
}

/* ================= OpenUI 动作（nav:/approve:/toggle:/insight:） ================= */

/* nav 的业务目标：plans 或 plan/<id>；不是方案域就交回原来的 tab 白名单。 */
function planNav(arg) {
  var s = String(arg || "").replace(/^#\/?/, "").replace(/^\/+/, "");
  if (s === "plans" || s === "plan") { openPlans(); return true; }
  if (s.indexOf("plan/") === 0) {
    var id = s.slice(5);
    if (!id) return false;
    openPlan(id);
    return true;
  }
  return false;
}

/* toggle:<planId>:<date>:<序号|文本>。序号来自模型渲染的清单，文本来自手写卡片。 */
function planToggleByArg(arg) {
  var s = String(arg || ""), cut = s.indexOf(":");
  var pid = cut < 0 ? s : s.slice(0, cut);
  var rest = cut < 0 ? "" : s.slice(cut + 1);
  var cut2 = rest.indexOf(":");
  var date = cut2 < 0 ? "" : rest.slice(0, cut2);
  var tail = cut2 < 0 ? rest : rest.slice(cut2 + 1);
  var p = planFull(pid);
  if (!p) { toast("没有这个方案"); return; }
  var d = planDayOf(p, date);
  if (!d) { toast("方案里没有这一天：" + date); return; }
  var text = tail;
  if (/^\d+$/.test(tail)) {
    var it = d.items[Number(tail)];
    text = it ? it.text : "";
  }
  if (!text) { toast("这一项不在方案里"); return null; }
  return planToggle(pid, date, text);
}

function planApproveByArg(arg) {
  var id = String(arg || "").trim();
  if (!id) return false;
  var p = planFull(id);
  if (!p) return false;
  if (!p.enabled) {
    var out = planWrite(id, "resume", {});
    if (out.error) { toast(out.error); return true; }
    toast("方案已启用");
  } else {
    openPlan(id);
  }
  return true;
}

function planAdjustByArg(arg) {
  var id = String(arg || "").trim();
  if (!id) return false;
  var p = planFull(id);
  if (!p) return false;
  openPlan(id);
  planAdjustSheet(id);
  return true;
}
