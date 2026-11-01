"use strict";
/* ================= 检查更新 ================= */
var APP_VER = "1.0.9";
try { if (window.Android && Android.appVersion) APP_VER = Android.appVersion() || APP_VER; } catch (e) {}
var updInfo = null;
/* 更新状态拆成三份，别混在一个布尔里：
   upd.req      —— 请求是否在飞（自动/手动共用，同一时刻只允许一个）
   upd.silent   —— 这次请求是自动检查还是用户点的
   upd.avail / upd.dismissed —— 已知可用的版本、用户点了「稍后」的版本
   后两项要落盘：杀进程再回来，小红点还得在。 */
var upd = { req: false, silent: false, avail: "", dismissed: "", hasNew: false };
(function () {
  var s = store.read(LS.u, null);
  if (!s || typeof s !== "object") return;
  upd.avail = typeof s.avail === "string" ? s.avail : "";
  upd.dismissed = typeof s.dismissed === "string" ? s.dismissed : "";
})();
function updSave() { store.write(LS.u, { avail: upd.avail, dismissed: upd.dismissed }); }
/* 小红点只由「已知可用版本 > 当前版本」推出来，不单独存一个布尔，
   否则版本升级后标记会赖着不走 */
function updSyncMark() {
  upd.hasNew = !!(upd.avail && Android_verNewer(upd.avail, APP_VER));
}
function Android_verNewer(a, b) {
  /* 版本比较的唯一真源在原生 Ver.isNewer：页面这边只做等价的数值分段比较，
     规则保持一致（去掉 v 前缀、按 . 分段比数值），绝不比字符串 */
  var pa = String(a == null ? "" : a).replace(/^[vV]/, "").split("."),
      pb = String(b == null ? "" : b).replace(/^[vV]/, "").split(".");
  var n = Math.max(pa.length, pb.length);
  for (var i = 0; i < n; i++) {
    var x = parseInt(pa[i] || "0", 10), y = parseInt(pb[i] || "0", 10);
    if (isNaN(x)) x = 0;
    if (isNaN(y)) y = 0;
    if (x !== y) return x > y;
  }
  return false;
}

/* 手动检查。force=false 时，若自动检查刚查过且没有新结果，就直接用缓存的状态收尾，
   避免用户狂点「检查更新」把 GitHub 匿名配额（60 次/小时）烧光；
   真的发现新版本、或者距上次检查超过 COOL 毫秒，仍然照常联网。 */
var UPD_COOL = 60 * 1000;
var updLast = 0;              /* 上一次检查结束的时刻（Date.now） */
function checkUpdate(force) {
  if (!window.Android || !Android.checkUpdate) { toast("请在手机上使用 App 检查更新"); return; }
  /* 自动检查还在飞的时候用户点了「检查更新」：不另开一条请求（两条回调会互相踩），
     而是把这条在飞的请求升级成手动模式，结果照常弹给用户看 */
  if (upd.req) {
    if (upd.silent) { upd.silent = false; toast("正在检查更新…"); }
    return;
  }
  if (!force && !upd.hasNew && updLast && Date.now() - updLast < UPD_COOL) {
    glass({ icon: "✓", title: "目前已经为最新版本", desc: "当前版本 v" + APP_VER, no: null, ok: ["确定"] });
    return;
  }
  upd.req = true;
  upd.silent = false;
  toast("检查更新中…");
  Android.checkUpdate();
  /* 原生层无论成败都会回调 onUpdateInfo，这里只兜一个超时，免得按钮永久锁死 */
  setTimeout(function () { upd.req = false; }, 20000);
}
/* 进入「我的」时静默查一次。失败、无更新都不吭声，只有发现新版本才提醒 */
function checkUpdateSilently() {
  if (!window.Android || !Android.checkUpdate) return;
  if (upd.req) return;                 /* 手动检查在飞时不要再叠一条 */
  /* 来回切 tab 会一秒点好几次「我的」，冷却期内不重复打 GitHub 的匿名配额 */
  if (updLast && Date.now() - updLast < UPD_COOL) return;
  upd.req = true;
  upd.silent = true;
  Android.checkUpdate();
  setTimeout(function () { upd.req = false; }, 20000);
}
/* 更新弹窗复用 #glass 这一层，但内容要带进度条，所以不走 glass()（它只有两行文字+两个按钮） */
function updSheet(html, cls) {
  var g = document.getElementById("glass");
  g.className = "glass" + (cls ? " " + cls : "");
  g.innerHTML = html;
  openLayer(g);
}
/* 日志按纯文本渲染：远端内容是别人写的，直接塞 HTML 进 WebView 等于开门揖盗 */
function updLogHtml(d) {
  var list = d.log && d.log.length ? d.log : [];
  if (!list.length) {
    var t = (d.notes || "").trim();
    list = t ? [t] : [];
  }
  if (!list.length) return '<div class="upd-log"><div class="none">此版本暂无更新说明</div></div>';
  return '<div class="upd-log"><h3>更新日志</h3><ul>' +
    list.map(function (x) { return "<li>" + esc(x) + "</li>"; }).join("") + "</ul></div>";
}
/* 自动检查发现新版本时的小窗。和完整更新弹窗（updSheet）分开：
   这里只放版本号 + 两个按钮，不展开日志；「立即更新」再转进完整流程 */
function showUpdateNotice(d) {
  var canGet = !!(d.urls && d.urls.length) || !!d.url;
  updSheet(
    '<div class="icon">↑</div><h2>发现新版本</h2>' +
    "<p>发现 GIme v" + esc(d.latest) + "，建议更新以获得最新功能和修复。</p>" +
    '<div class="note-ver"><span>当前版本</span><b>v' + esc(d.current) + "</b></div>" +
    '<div class="note-ver"><span>最新版本</span><b>v' + esc(d.latest) + "</b></div>" +
    '<div class="note-acts">' +
      '<button class="sec" id="noteLater">稍后更新</button>' +
      '<button class="pri" id="noteNow"' + (canGet ? "" : " disabled") + ">立即更新</button>" +
    "</div>", "note");
  document.getElementById("noteLater").onclick = function () {
    /* 只记住「这个版本我暂缓了」，不动 avail：小红点要继续在 */
    upd.dismissed = d.latest;
    updSave();
    closeTop();
  };
  document.getElementById("noteNow").onclick = function () {
    if (!canGet) return;
    closeTop();
    openUpdateDetail(d);      /* 走完整更新流程，能看到日志再确认下载 */
  };
}
/* 完整更新弹窗：手动检查、以及小窗点「立即更新」都走这里 */
function openUpdateDetail(d) {
  updInfo = d;
  var canGet = !!(d.urls && d.urls.length) || !!d.url;
  updSheet(
    '<div class="upd-h"><div class="icon">↑</div><h2>发现新版本</h2>' +
      "<p>" + (canGet ? "更新后做题记录和题库都会保留" : "这个版本还没有上传安装包") + "</p></div>" +
    '<div class="upd-ver"><span>当前版本</span><b>v' + esc(d.current) + "</b></div>" +
    '<div class="upd-ver"><span>最新版本</span><b>v' + esc(d.latest) + "</b></div>" +
    (d.date ? '<div class="upd-ver"><span>发布日期</span><b>' + esc(d.date) + "</b></div>" : "") +
    updLogHtml(d) +
    '<div class="upd-acts">' +
      '<button class="sec" id="upLater">稍后</button>' +
      '<button class="pri" id="upNow"' + (canGet ? "" : " disabled") + ">立即更新</button>" +
    "</div>", "upd");
  document.getElementById("upLater").onclick = function () {
    /* 从完整弹窗里点「稍后」同样记一笔，免得下次进「我的」又弹一遍 */
    upd.dismissed = d.latest;
    updSave();
    closeTop();
  };
  document.getElementById("upNow").onclick = function () {
    if (!canGet) return;
    startDownload(d);
  };
}
window.onUpdateInfo = function (s) {
  var silent = upd.silent;
  upd.req = false;
  upd.silent = false;
  var d;
  try { d = JSON.parse(s); } catch (e) { d = { ok: false, error: "更新信息读不懂" }; }
  if (!d.ok) {
    /* 网络失败不代表没有新版本：自动检查静默吞掉，已知的小红点照旧保留 */
    if (silent) return;
    glass({ icon: "!", title: "检查失败", desc: d.error || "未知错误", no: null,
            ok: ["重试", function () { checkUpdate(true); }] });
    return;
  }
  /* 到这里才算「真的查到了」，冷却计时从这里起算 */
  updLast = Date.now();
  if (!d.hasUpdate) {
    /* 只有确认已是最新版本，才允许清掉提示状态 */
    upd.avail = "";
    upd.dismissed = "";
    updSave();
    updSyncMark();
    if (curTab === "me") renderMe();
    if (silent) return;               /* 自动检查不弹「已是最新版本」 */
    glass({ icon: "✓", title: "目前已经为最新版本", desc: "当前版本 v" + d.current,
            no: null, ok: ["确定"] });
    return;
  }
  upd.avail = d.latest;
  updSave();
  updSyncMark();
  if (curTab === "me") renderMe();
  if (silent) {
    /* 同一个版本已经说过「稍后」就不再打断；更高的版本才重新提醒 */
    if (upd.dismissed && !Android_verNewer(d.latest, upd.dismissed)) return;
    showUpdateNotice(d);
    return;
  }
  openUpdateDetail(d);
};
/* 只画下载页，不发指令：原生层回 onUpdateDownloading 时要用它重画，
   若这里也调 downloadUpdate，就会和回调互相触发成死循环。 */
function renderDownload(d) {
  updSheet('<div class="icon">↓</div><div class="big" id="upPct">0%</div>' +
    "<h2>正在下载 v" + esc(d.latest) + "</h2>" +
    '<div class="track" style="margin:14px 0 8px"><i id="upBar"></i></div>' +
    "<p>下载完成后会自动弹出安装页，确认即可覆盖安装，进度和题库都不会丢</p>");
}
function startDownload(d) {
  renderDownload(d);
  /* 优先走多源：原生层会逐个试，第一个连得上的就开始下载。
     jsDelivr 那几个入口在国内实测几十毫秒就返回，比 GitHub 资产地址可靠得多。 */
  var list = (d.urls && d.urls.length) ? d.urls : (d.url ? [d.url] : []);
  if (Android.downloadUpdateFrom) Android.downloadUpdateFrom(JSON.stringify(list));
  else if (list.length) Android.downloadUpdate(list[0]);
}
/* 原生层会先花几秒探一下下载地址通不通，这期间别让界面看起来像卡住了 */
window.onUpdateChecking = function () {
  updSheet('<div class="icon">↓</div><h2>正在连接更新服务器…</h2>' +
    '<p>如果网络屏蔽了下载地址，这里会直接告诉你，不会一直转圈</p>');
};
/* 探测通过、真正开始下载：把「连接中」换回下载页并重置进度，
   否则标题会一直停在连接中、进度条也不会从 0 开始涨。 */
window.onUpdateDownloading = function () {
  if (updInfo) renderDownload(updInfo);
};
window.onUpdateProgress = function (p) {
  var b = document.getElementById("upBar"), t = document.getElementById("upPct");
  if (b) b.style.width = Math.max(0, Math.min(100, p)) + "%";
  if (t) t.textContent = Math.max(0, Math.min(100, p)) + "%";
};
window.onUpdateError = function (m) {
  /* 下载走的是 GitHub 的 release 资产域名，国内网络经常连不上。
     只给「重试」等于让用户在一个注定失败的按钮上反复点，
     所以同时给一条能走通的路：打开发布页手动下载。 */
  updSheet('<div class="icon">!</div><h2>更新失败</h2><p>' + esc(m) + "</p>" +
    '<div class="upd-acts">' +
      '<button class="sec" id="upClose">关闭</button>' +
      '<button class="sec" id="upPage">打开发布页</button>' +
      '<button class="pri" id="upRetry">重试</button>' +
    "</div>", "upd");
  document.getElementById("upClose").onclick = function () { closeTop(); };
  document.getElementById("upPage").onclick = function () {
    if (updInfo && updInfo.page) { closeTop(); Android.openUrl(updInfo.page); }
    else toast("没有可打开的发布页");
  };
  document.getElementById("upRetry").onclick = function () {
    if (updInfo && ((updInfo.urls && updInfo.urls.length) || updInfo.url)) startDownload(updInfo);
  };
};
window.onUpdateInstalled = function () { closeTop(); toast("请在安装页确认安装"); };
window.onUpdateNeedPermission = function () {
  updSheet('<div class="icon">!</div><h2>需要授权安装</h2>' +
    "<p>GIme 还没有「安装未知应用」的权限，已下载的安装包打不开。授权后回来再点一次下载即可。</p>" +
    '<div class="upd-acts"><button class="sec" id="upNo">稍后</button>' +
    '<button class="pri" id="upYes">去设置</button></div>', "upd");
  document.getElementById("upYes").onclick = function () { closeTop(); Android.openInstallPermission(); };
  document.getElementById("upNo").onclick = function () { closeTop(); };
};

/* 吸附滑杆：拖动跟手，松手按速度做惯性投影，再吸附到整数题数 */
var activeSl = null;

function makeSlider(el, values, getIdx, setIdx, unit) {
  var track = el.querySelector(".sl-track"), knob = el.querySelector(".sl-knob"),
      fill = el.querySelector(".sl-fill"), label = el.querySelector(".sl-top i");
  function show(i) { if (label) label.textContent = values[i] + (unit || ""); }
  var out = {
    paint: function (p) {
      p = Math.max(0, Math.min(1, p));
      knob.style.left = (p * 100) + "%";
      fill.style.width = (p * 100) + "%";
    },
    idx: function (p) { return Math.max(0, Math.min(values.length - 1, Math.round(p * (values.length - 1)))); },
    down: function (e) {
      var t = e.touches ? e.touches[0] : e;
      activeSl = out;
      /* 手指按在轨道哪里就跳到哪里，再跟手 1:1 拖 */
      var q = Math.max(0, Math.min(1, (t.clientX - track.getBoundingClientRect().left) / (track.clientWidth || 1)));
      out.d = { x0: t.clientX, p0: q, p: q, wp: q, wt: Date.now(), v: 0, w: track.clientWidth || 1 };
      el.classList.remove("snap");
      out.paint(q);
      show(out.idx(q));
      if (e.preventDefault) e.preventDefault();
    },
    move: function (e) {
      if (!out.d) return;
      var d = out.d, t = e.touches ? e.touches[0] : e, now = Date.now();
      var p = Math.max(0, Math.min(1, d.p0 + (t.clientX - d.x0) / d.w));
      /* 速度要按 >=60ms 的窗口取：事件被合批到同一毫秒时瞬时速度虚高，慢拖也会被判成甩动 */
      var wt = now - d.wt;
      if (wt >= 60) { d.v = (p - d.wp) / wt; d.wp = p; d.wt = now; }
      d.p = p;
      out.paint(p);
      show(out.idx(p));
      if (e.preventDefault) e.preventDefault();
    },
    up: function () {
      if (!out.d) return;
      /* 慢放到手就停在手那儿，只有真的甩出去才带惯性；投影幅度封顶，一次抖动不会跨满全程 */
      var v = out.d.v;
      var fling = Math.abs(v) < 0.0015 ? 0 : Math.max(-0.18, Math.min(0.18, v * 45));
      var projected = Math.max(0, Math.min(1, out.d.p + fling));
      var i = out.idx(projected);
      out.d = null;
      el.classList.add("snap");
      out.paint(i / (values.length - 1));
      show(i);
      setIdx(i);
    }
  };
  track.addEventListener("touchstart", out.down, { passive: false });
  track.addEventListener("mousedown", out.down);
  out.paint(getIdx() / (values.length - 1));
  show(getIdx());
  return out;
}
document.addEventListener("touchmove", function (e) { if (activeSl) activeSl.move(e); }, { passive: false });
document.addEventListener("mousemove", function (e) { if (activeSl) activeSl.move(e); });
document.addEventListener("touchend", function () { if (activeSl) activeSl.up(); });
document.addEventListener("mouseup", function () { if (activeSl) activeSl.up(); });

/* ---------------- 组卷：题库多选 + 按总题数 / 按题型分配 ---------------- */
/* 组卷能选的题库 = 题库页/首页真正列出来的那些（至少有一道题）。
   题库页对空题库是隐藏的，这里若直接遍历 BANK.subjects，
   空题库（导入时一道都没收录）就只在组卷里露出来——用户报的那条。 */
function paperBanks() {
  return BANK.subjects.filter(function (s) { return stats(s.id).total > 0; });
}
/* 删掉的题库、或从没进过本机的 id，不该继续算一票：
   否则「全选/全不选」和卷名会把它带上。空题库一并清掉。 */
function paperPrune() {
  var subs = set.paper.subs, changed = false;
  Object.keys(subs).forEach(function (id) {
    var s = subjectOf(id);
    if (!s || !stats(id).total) { delete subs[id]; changed = true; }
  });
  if (changed) {
    /* 唯一选中的题库被删了：回退到第一个有题的库，别让面板停在
       「一个都没选」却提示「所选题库里没有客观题」那种对不上号的状态 */
    var first = paperBanks()[0];
    if (!Object.keys(subs).length && first) subs[first.id] = 1;
    save();
  }
}
function paperInit() {
  if (!set.paper) {
    var subs = {}, first = paperBanks()[0];
    /* 题库可能一个都没有（全是用户导入的，随时可能被删干净），
       这里不能直接取 subjects[0].id，否则空题库时组卷一点就抛异常 */
    if (first) subs[first.id] = 1;
    set.paper = { subs: subs, mode: "total", total: 30, single: 10, multi: 10, judge: 10, fill: 0, short: 0, min: 20 };
  }
  paperPrune();
}
function paperPool() {
  paperInit();
  /* 主观题也进卷（用户要求）；它们不判分，交卷只统计客观题的得分 */
  return BANK.questions.filter(function (q) {
    return set.paper.subs[q.subject];
  });
}
function paperAvail(tf) {
  return paperPool().filter(function (q) { return !tf || q.type === tf; }).length;
}
function paperOrder() {
  var p = set.paper, pool = paperPool();
  if (p.mode !== "types") {
    return shuffle(pool.map(function (q) { return q.id; })).slice(0, Math.min(p.total, pool.length));
  }
  var out = [];
  ["single", "multi", "judge", "fill", "short"].forEach(function (t) {
    var ids = shuffle(pool.filter(function (q) { return q.type === t; }).map(function (q) { return q.id; }));
    out = out.concat(ids.slice(0, p[t] || 0));
  });
  return shuffle(out);
}
/* 滑杆是连续整数档（下限..上限），刻度只标 0/¼/½/¾/满，这样"40 题"这种具体数字能落准 */
function sliderHtml(id, name, lo, hi, value, unit) {
  var marks = [0, 0.25, 0.5, 0.75, 1].map(function (f) {
    var v = Math.round(lo + (hi - lo) * f);
    var st = f === 0 ? "left:0" : (f === 1 ? "right:0" : "left:" + (f * 100) + "%;transform:translateX(-50%)");
    return '<span style="' + st + '">' + v + "</span>";
  }).join("");
  return '<div class="sl" id="' + id + '"><div class="sl-top"><b>' + name + "</b><i>" +
    value + unit + '</i></div>' +
    '<div class="sl-track"><div class="sl-fill"></div><div class="sl-knob"></div></div>' +
    '<div class="sl-ticks">' + marks + "</div></div>";
}
function wireSlider(id, lo, hi, get, put, unit) {
  var values = [];
  for (var i = lo; i <= hi; i++) values.push(i);
  makeSlider(document.getElementById(id), values,
    function () { return Math.max(lo, Math.min(hi, get())) - lo; },
    function (i) { put(values[i]); save(); paperRelabel(); }, unit);
}
function paperCta() { return "开始 " + paperOrder().length + " 题 · " + set.paper.min + " 分钟"; }
function paperRelabel() {
  var b = document.querySelector("#sheet2 .sheet-act");
  if (b) b.textContent = paperCta();
}
function openExam(sid) { openPaper(sid); }
function openPaper(sid) {
  paperInit();
  var banks = paperBanks();
  if (!banks.length) { toast("还没有题库，先导入一个"); openImport(); return; }
  if (sid && sid !== "all") { set.paper.subs = {}; set.paper.subs[sid] = 1; }
  var p = set.paper;
  p.total = Math.min(p.total, paperAvail());
  ["single", "multi", "judge", "fill", "short"].forEach(function (t) {
    p[t] = Math.min(+p[t] || 0, paperAvail(t));
  });
  var picked = Object.keys(p.subs).length;
  var h = '<div class="handle"></div><h2>组卷设置</h2>' +
    '<div class="sec-title" style="margin:8px 0 12px"><b>选择题库</b>' +
      '<button style="font-size:.75rem;color:var(--ink-2);text-decoration:underline" onclick="paperAllSubs()">' +
        (picked === banks.length ? "全不选" : "全选") + "</button></div>" +
    banks.map(function (s) {
      return '<button class="pick' + (p.subs[s.id] ? " on" : "") + '" onclick="paperToggle(&quot;' + s.id + '&quot;)">' +
             '<span class="cbx"></span><b>' + esc(s.name) + "</b><i>" + stats(s.id).total + " 题</i></button>";
    }).join("") +
    '<div class="tbar" style="margin-top:20px">' +
      '<button class="' + (p.mode !== "types" ? "on" : "") + '" onclick="paperMode(&quot;total&quot;)">按总题数</button>' +
      '<button class="' + (p.mode === "types" ? "on" : "") + '" onclick="paperMode(&quot;types&quot;)">按题型分配</button>' +
    "</div>";

  var avail = paperAvail();
  if (!avail) {
    h += '<div class="note" style="margin:6px 0 18px">所选题库里还没有题目，先导入题库再来组卷。</div>';
  } else if (p.mode !== "types") {
    h += sliderHtml("pTotal", "总题数", 0, avail, p.total, " 题");
  } else {
    ["single", "multi", "judge", "fill", "short"].forEach(function (t) {
      h += sliderHtml("p_" + t, typeName(t), 0, paperAvail(t), p[t], " 题");
    });
    h += '<div class="sl-hint">五类分别定题数，0 表示不要这类；填空题/解答题不判分，' +
         "只计入卷面完成度。</div>";
  }
  h += sliderHtml("pMin", "时长（分钟）", 5, 45, p.min, " 分钟") +
     '<button class="sheet-act"' + (avail ? "" : " disabled") +
       ' onclick="startPaper()">' + (avail ? paperCta() : "没有可出的客观题") + "</button>";

  var sh = document.getElementById("sheet2");
  sh.innerHTML = h;
  if (avail && p.mode !== "types") {
    wireSlider("pTotal", 0, avail, function () { return p.total; }, function (v) { p.total = v; }, " 题");
  } else if (avail) {
    ["single", "multi", "judge"].forEach(function (t) {
      wireSlider("p_" + t, 0, paperAvail(t), function () { return p[t]; }, function (x) { p[t] = x; }, " 题");
    });
  }
  wireSlider("pMin", 5, 45, function () { return p.min; }, function (v) { p.min = v; }, " 分钟");
  openLayer(sh);
}
function paperToggle(sid) {
  if (set.paper.subs[sid]) delete set.paper.subs[sid]; else set.paper.subs[sid] = 1;
  save(); openPaper();
}
function paperAllSubs() {
  var banks = paperBanks();
  var all = Object.keys(set.paper.subs).length === banks.length;
  set.paper.subs = {};
  if (!all) banks.forEach(function (s) { set.paper.subs[s.id] = 1; });
  save(); openPaper();
}
function paperMode(m) { set.paper.mode = m; save(); openPaper(); }
function startPaper() {
  if (!paperPool().length) { toast("所选题库里没有可用于模拟考试的客观题"); return; }
  if (!paperOrder().length) { toast("题数还是 0，先拖滑杆"); return; }
  closeTop();
  startSession("exam", "all", "exam");
}

/* ================= 弹层 ================= */
/* 遮罩只服务面板/弹窗，不服务整页：.fs 自己就是全屏。
   方案页的 z-index 950 在遮罩 960 之下，从它上面开对话再退出时，
   遮罩如果按「还有层」留着，整页会一直发灰（2026-11-01 用户反馈）。
   所以按最上层判断：只有最上层是 sheet/glass 才亮遮罩。 */
function layerNeedsScrim(el) {
  return !!el && !el.classList.contains("fs");
}
function syncLayerScrim() {
  var ov = document.getElementById("overlay");
  if (!ov) return;
  var top = openLayers[openLayers.length - 1];
  ov.className = "overlay" + (layerNeedsScrim(top) ? " on" : "");
}
function openLayer(el) {
  el.classList.add("on");
  /* 面板重绘会再次调用本函数，同一元素只算一层，否则返回键弹不完、遮罩会留在屏上挡住点击 */
  if (openLayers.indexOf(el) < 0) openLayers.push(el);
  syncLayerScrim();
}
function closeLayer(el) {
  el.classList.remove("on");
  var i = openLayers.indexOf(el);
  if (i >= 0) openLayers.splice(i, 1);
  if (el.id === "planPage" && typeof planPageClosed === "function") planPageClosed();
  /* 对话收掉了，浮在上面的那张卡要跟着回它的槽位：窗口留在主页上既点不到也收不回 */
  if (el.id === "chat" && typeof chatOuiCollapse === "function") chatOuiCollapse();
  syncLayerScrim();
}
function closeTop() {
  if (openLayers.length) { closeLayer(openLayers[openLayers.length - 1]); return true; }
  return false;
}
window.onBack = function () {
  var top = openLayers[openLayers.length - 1];
  /* 方案页在最上面：详情先回列表，再按一次才关整页。
     面板/弹窗压在方案页上面时仍先收面板——不能从详情背后偷偷换页。 */
  if (top && top.id === "planPage") {
    if (typeof planBack === "function" && planBack()) return true;
    return closeTop();
  }
  /* 卡片悬浮窗压在对话上：先把它收回气泡，不能一按返回把整个对话关掉 */
  if (typeof chatOuiBack === "function" && chatOuiBack()) return true;
  if (closeTop()) return true;
  var fs = document.getElementById("fs");
  if (fs.classList.contains("on")) { closeFS(); return true; }
  if (document.getElementById("quiz").classList.contains("on")) { exitQuiz(); return true; }
  return false;
};
/* 考试中按返回键：不能一句话不说就把计时留在后台，也不能逼人当场交卷。
   继续 = 什么都不做；交卷 = 走同一个 submitExam；放弃 = 停表并丢弃本次会话。 */
function askExitExam() {
  glass({
    title: "要离开这场考试吗？",
    no: ["继续考试", function () {}],
    ok: ["交卷", function () { submitExam(); }]
  });
  /* 这一层需要第三个选项，glass() 只画两个，所以补一个同样式的按钮 */
  var g = document.getElementById("glass");
  var btns = g.querySelector(".btns");
  if (btns) {
    var b = document.createElement("button");
    b.className = "sec";
    b.textContent = "退出并放弃";
    b.onclick = function () { closeTop(); abandonExam(); };
    btns.appendChild(b);
  }
}
function abandonExam() {
  if (!S || !isExam()) return;
  S.state = "abandoned";
  S = null;
  localStorage.removeItem(LS.j);
  document.getElementById("quiz").classList.remove("on");
  renderAll();
  toast("已放弃这场考试，计时停止");
}
function openSheet() { renderGrid(); openLayer(document.getElementById("sheet")); }
var GRID_TYPES = [["single", "单选题"], ["multi", "多选题"], ["judge", "判断题"],
                  ["fill", "填空题"], ["short", "解答题"]];
function renderGrid() {
  if (!S) return;
  /* 答题卡按题型分块：题号还是整组的序号（与顶部「第 k / N 题」一致），
     只是同一题型的排在一起，长卷子里找某类题不用一行行扫。 */
  var byType = {};
  GRID_TYPES.forEach(function (t) { byType[t[0]] = []; });
  S.order.forEach(function (id, i) {
    var qq = byId[id] || P(id);
    var t = (qq && byType[qq.type]) ? qq.type : "single";
    byType[t].push(i);
  });
  var h = "";
  GRID_TYPES.forEach(function (t) {
    var idxs = byType[t[0]];
    if (!idxs.length) return;
    h += '<div class="gsec"><span class="gst">' + t[1] + '</span>' +
         '<span class="gsn">' + idxs.length + " 题</span></div>";
    idxs.forEach(function (i) {
      var cls = "";
      if (i === S.idx) cls += " cur";
      var g = S.graded[S.order[i]];
      if (g === 1) cls += " right";
      else if (g === 0) cls += " wrong";
      else if ((S.picks[S.order[i]] || []).length) cls += " picked";
      h += '<button class="' + cls + '" onclick="jump(' + i + ')"><b>' + (i + 1) + "</b></button>";
    });
  });
  document.getElementById("grid").innerHTML = h;
  document.getElementById("legend").innerHTML = isExam()
    ? '<i></i>已作答<i class="dash"></i>当前'
    : "<i></i>答对<i class=\"dash\"></i>答错<i></i>当前";
}
function jump(i) { S.idx = i; closeTop(); render(); save(); }

function glass(o) {
  var g = document.getElementById("glass");
  /* no 显式传 null 就是纯提示，不该多一个语义重复的「取消」 */
  var one = o.no === null;
  g.className = "glass" + (o.cls ? " " + o.cls : "");   /* 更新弹窗会给自己加 upd 类，这里必须清掉 */
  g.innerHTML = (o.icon ? '<div class="icon">' + o.icon + "</div>" : "") +
    (o.big != null ? '<div class="big">' + o.big + "</div>" : "") +
    "<h2>" + esc(o.title) + "</h2><p>" + esc(o.desc || "") + "</p>" +
    '<div class="btns">' +
      (one ? "" : '<button class="sec" id="gNo">' + esc(o.no ? o.no[0] : "取消") + "</button>") +
      '<button class="pri" id="gOk">' + esc(o.ok[0]) + "</button></div>";
  openLayer(g);
  document.getElementById("gOk").onclick = function () { closeTop(); o.ok[1] && o.ok[1](); };
  if (!one) document.getElementById("gNo").onclick = function () { closeTop(); o.no && o.no[1] && o.no[1](); };
}

/* MCP 口令：只在本机设置页露出，可复制给电脑端；重置后旧口令立即失效。 */
function openMcpCfg() {
  if (!window.Android || !Android.mcpStatus) { toast("仅手机版可用"); return; }
  var st = {};
  try { st = JSON.parse(Android.mcpStatus() || "{}"); } catch (e) { st = {}; }
  if (!st.running) {
    glass({ title: "MCP 未运行", desc: st.error || "服务没有起来", ok: ["知道了"] });
    return;
  }
  glass({
    title: "MCP 连接",
    cls: "mcp",
    desc: "地址 127.0.0.1:" + (st.port || 8765) + "\n口令 " + (st.token || ""),
    ok: ["复制口令", function () {
      try { Android.copyText(String(st.token || "")); toast("口令已复制"); }
      catch (e) { toast("复制失败"); }
    }],
    no: ["重置口令", function () { askMcpReset(); }]
  });
}
function askMcpReset() {
  glass({
    title: "重置 MCP 口令？",
    desc: "旧口令立即失效，已配置的电脑端要换成新口令。",
    ok: ["确认重置", function () {
      var t = "";
      try { t = String(Android.mcpResetToken() || ""); } catch (e) { t = ""; }
      if (!t) { toast("重置失败"); return; }
      try { Android.copyText(t); } catch (e) {}
      toast("已重置，新口令已复制");
    }]
  });
}

var toastTimer = null;
function toast(msg) {
  var t = document.getElementById("toast");
  t.textContent = msg;
  t.classList.add("on");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { t.classList.remove("on"); }, 2200);
}
/* 只有明确开着才震。用 === true 而不是真值判断：设置项从 localStorage 回来时
   可能是 "false" / 0 / "0" 这类真值串，真值判断会把「关」当成「开」，
   这正是「开关关了还震」的根因。 */
function vibrate(p) {
  if (set.haptics !== true) return;
  try { if (navigator && navigator.vibrate) navigator.vibrate(p); } catch (e) {}
}

/* ================= 全屏：搜索 / 统计 / 格式举例 ================= */
/* 举例正文和下载件同源：都读 assets/import-sample.md，
   所以「看到的」和「下载到的」不会长歪，也不会在 JS 里再抄一份样例。 */
function sampleText() {
  if (!window.Android || !Android.importSample) return "";
  try { return String(Android.importSample() || ""); } catch (e) { return ""; }
}
function saveSample() {
  if (!window.Android || !Android.saveImportSample) { toast("请在手机上使用 App 下载"); return; }
  var where = "";
  try { where = String(Android.saveImportSample() || ""); } catch (e) { where = ""; }
  toast(where ? "已存到 " + where : "没能存下来，可以长按正文选中复制");
}
function sampleBody() {
  var t = sampleText();
  /* 读不到随包文件时，兜底里也不能出现仓库路径：手机用户打不开 docs/。 */
  if (!t) return '<div class="cap">这台设备没能读出随包的举例文件。就照这个顺序写题：' +
                 "题号（「第1题」或「1、」）→ 题目 → 选项（A. 可以 B. 不行，一行写完或每行一个）→ 答案：A → 解析：。" +
                 "每道题之间空一行。</div>";
  return '<div class="cap" style="margin:0">下面这份就是可直接导入的样例。每道题由「题号 → 题目 → 选项 → 答案 → 解析」五步组成：' +
         "题号写「第1题」或「1、」都行，选项挤在一行或每行一个都行。" +
         '长按正文可以选中复制，或点下面的按钮把它存成 .md 到手机上，用编辑器照着改完再导回来。</div>' +
         '<pre class="sample-box">' + esc(t) + "</pre>" +
         '<button class="sheet-act" onclick="saveSample()">下载这份举例</button>';
}
function openFS(which) {
  var fs = document.getElementById("fs"), body = "";
  if (which === "stat") {
    var a = stats("all");
    body = '<div class="bignum">' + a.done + '%</div><div class="cap">完成度 · 已刷 ' + a.seen + " / " + a.total + "</div>";
    BANK.subjects.forEach(function (s) {
      var t = stats(s.id);
      body += '<button class="res" onclick="goTab(&quot;bank&quot;)"><b>' + esc(s.name) + "</b>" +
              "<small>" + t.total + " 题 · 已答 " + t.seen + " · 完成率 " + t.done + "% · 错题 " + t.wrong + "</small></button>";
    });
  } else if (which === "sample") {
    body = sampleBody();
  } else {
    body = '<input class="srch" id="srchBox" placeholder="输入题干或解析关键词" oninput="doSearch(this.value)">' +
           '<div id="srchRes" style="margin-top:8px"></div>';
  }
  fs.innerHTML = '<div class="fs-head"><button onclick="closeFS()"><svg class="ico" viewBox="0 0 24 24"><path d="M14.5 5.5L8 12l6.5 6.5"/></svg></button>' +
    "<h2>" + (which === "stat" ? "学习统计" : which === "sample" ? "导入格式举例" : "搜索题目") + "</h2><i></i></div>" +
    '<div class="fs-body">' + body + "</div>";
  fs.classList.add("on");
  if (openLayers.indexOf(fs) < 0) openLayers.push(fs);
  if (typeof syncLayerScrim === "function") syncLayerScrim();
  if (which === "search") setTimeout(function () { var b = document.getElementById("srchBox"); if (b) b.focus(); }, 320);
}
function closeFS() {
  var fs = document.getElementById("fs");
  fs.classList.remove("on");
  var i = openLayers.indexOf(fs);
  if (i >= 0) openLayers.splice(i, 1);
  if (typeof syncLayerScrim === "function") syncLayerScrim();
}
function doSearch(v) {
  v = (v || "").trim();
  var box = document.getElementById("srchRes");
  if (!box) return;
  if (!v) { box.innerHTML = ""; return; }
  var hits = BANK.questions.filter(function (x) {
    return x.stem.indexOf(v) >= 0 || (x.explain && x.explain.indexOf(v) >= 0);
  }).slice(0, 60);
  box.innerHTML = '<div class="cap">' + hits.length + " 条结果</div>" + hits.map(function (x) {
    return '<button class="res" onclick="openQuestion(&quot;' + x.id + '&quot;)"><b>' + esc(x.stem) + "</b>" +
           "<small>" + esc(subjName(x.subject)) + " · " + typeName(x.type) + " · " +
           (isSubjective(x.type) ? "参考答案" : "答案") + " " + esc(answerText(x)) + "</small></button>";
  }).join("");
}
function openQuestion(id) {
  closeFS();
  var x = byId[id];
  if (!x) return;
  var order = subjQs(x.subject, "").map(function (y) { return y.id; });
  S = { subject: x.subject, mode: "practice", tf: "", order: order, idx: order.indexOf(id),
        picks: {}, graded: {}, text: {}, self: {}, sub: {}, started: Date.now(), dur: 0 };
  save();
  render();
  document.getElementById("quiz").classList.add("on");
}

/* ================= 手势 ================= */
(function () {
  var drag = null;
  document.addEventListener("touchstart", function (e) {
    var p = e.touches[0], t = e.target;
    var sheet = document.querySelector(".sheet.on");
    if (sheet && p.clientY - sheet.getBoundingClientRect().top < 56) {
      drag = { el: sheet, axis: "y", y0: p.clientY, h: sheet.offsetHeight };
    } else if (!openLayers.length && S && document.getElementById("quiz").classList.contains("on")
               && t.closest && t.closest(".q-body")) {
      drag = { swipe: true, x0: p.clientX, y0: p.clientY, axis: "" };
    }
  }, { passive: true });
  document.addEventListener("touchmove", function (e) {
    if (!drag) return;
    var p = e.touches[0];
    if (drag.el) {
      drag.el.style.transition = "none";
      drag.el.style.transform = "translateY(" + Math.max(0, p.clientY - drag.y0) + "px)";
      return;
    }
    if (!drag.swipe) return;
    var dx = p.clientX - drag.x0, dy = p.clientY - drag.y0;
    if (drag.axis) {                       /* 已经定过轴，只按那条轴走 */
      if (drag.axis === "x" && e.preventDefault) e.preventDefault();
      return;
    }
    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;   /* 手还没动出来，先不定轴 */
    /* 竖向占优就整个放弃这一划，把它留给页面滚动。
       缺这道闸时，上下滑题目时拇指自然带出的横向漂移就能满足 |dx|>62，被误判成换题。
       要横向明显压过竖向（1.4 倍）才认作换题手势。 */
    if (Math.abs(dx) > Math.abs(dy) * 1.4) {
      drag.axis = "x";
      if (e.preventDefault) e.preventDefault();
    } else {
      drag = null;
    }
  }, { passive: false });
  document.addEventListener("touchend", function (e) {
    if (!drag) return;
    var d = drag, p = e.changedTouches[0];
    drag = null;
    if (d.el) {
      var el = d.el;
      el.style.transition = "";
      el.style.transform = "";
      if (p.clientY - d.y0 > Math.max(90, d.h * 0.2)) closeLayer(el);
    } else if (d.swipe && d.axis === "x") {
      var dx = p.clientX - d.x0;
      if (Math.abs(dx) > 62) go(dx < 0 ? 1 : -1);
    }
  });
  /* 滑到一半被打断（来电、切后台、系统手势截走触摸序列）时，面板会卡在拖到一半的位置 */
  document.addEventListener("touchcancel", function () {
    if (drag && drag.el) {
      drag.el.style.transition = "";
      drag.el.style.transform = "";
    }
    drag = null;
  });
})();

/* ================= 导入题库：正文 → 题目 ================= */
var NUM_RE = /^\s*(\d{1,4})\s*[、.．)）]\s*([\s\S]*)$/;
/* 「第1题」式标题：题号夹在「第 … 题」中间，行首式的「1、」认不了它。
   用户报的那份 ICT 题源（md / pdf 同一份稿子）整篇都是这种排版，之前一道题都认不出。 */
var QHEAD_RE = /^\s*第\s*(\d{1,4})\s*[题題]\s*[:：、.．]?\s*([\s\S]*)$/;
/* 带标签的行：「题目：」「题干：」「选项：」。标签是排版记号，不该留在题干里 */
var QLABEL_RE = /^\s*(?:题目|题干|内容|选项)\s*[:：]\s*([\s\S]*)$/;
function unlabel(s) {
  var m = QLABEL_RE.exec(s);
  return nz(m ? m[1] : s);
}
var QOPT_RE = /^\s*([A-G])\s*[、.．)）]\s*([\s\S]*)$/;
var QOPT_LOOSE_RE = /^\s*([A-G])\s+(\S[\s\S]*)$/;
var QANS_RE = /答\s*案\s*[:：]\s*([\s\S]*)/;
/* 主观题常写「参考答案：」，必须排在 QANS_RE 前面判断，
   否则「答案：」会在「参考答案：」里先命中，把「参考」两个字留在题干里 */
var QREF_RE = /参\s*考\s*答\s*案\s*[:：]\s*([\s\S]*)/;
var QEXPL_RE = /解\s*析\s*[:：]\s*([\s\S]*)/;
/* 题型标记：（多选题）（填空题）（解答题）这类，方括号圆括号都认 */
var QKIND_RE = /[（(【]\s*(单选题|多选题|判断题|填空题|解答题|简答题|问答题|填空|简答|问答|单选|多选|判断)\s*题?\s*[)）】]/;
var QKIND_MAP = {
  "单选题": "single", "单选": "single",
  "多选题": "multi", "多选": "multi",
  "判断题": "judge", "判断": "judge",
  "填空题": "fill", "填空": "fill",
  "解答题": "short", "简答题": "short", "问答题": "short", "简答": "short", "问答": "short"
};
/* 填空题的作答位：连续下划线，或一对空括号 */
var BLANK_RE = /_{2,}|＿{2,}|[（(]\s*[)）]/;
function kindOf(stem) {
  var m = QKIND_RE.exec(stem);
  return m ? (QKIND_MAP[m[1]] || "") : "";
}
var LET = "ABCDEFG";
var OPT_INLINE_RE = [];
for (var _i = 0; _i < 7; _i++) {
  /* 分隔符后面那一档空格必须吃掉：西文写法是「A. \du  B. dr」，点号后面带空格。
     少了这个 \s*，(?=\S) 会卡在空格上，一行四个选项就只拆出一个。
     带点号的允许停在行末：PDF 会把「… C.」和选项正文断在两行，行末那个
     「C.」仍是选项标记，正文从下一行接着写。空格分隔的写法不允许，
     否则正文里行末的「选项 C」会被当成空选项。 */
  OPT_INLINE_RE.push(new RegExp("(?:^|(?<=\\s))" + LET.charAt(_i)
    + "(?:\\s*[.、．)）]\\s*(?=\\S|$)|\\s+(?=\\S))"));
}

function nz(s) { return String(s == null ? "" : s).replace(/\s+/g, " ").trim(); }
/* PDF 是按行断的，中文词会被切在两行：「序」在行末、「列等对象」在下一行。
   逐行拼接时如果两边都是汉字就不要补空格，否则会拼出「序 列」；英文单词之间照旧留空。 */
var CJK_TAIL = /[\u4e00-\u9fff\u3400-\u4dbf\u3000-\u303f\uff00-\uffef\ufe30-\ufe4f]$/;
var CJK_HEAD = /^[\u4e00-\u9fff\u3400-\u4dbf\u3000-\u303f\uff00-\uffef\ufe30-\ufe4f]/;
function joinText(parts) {
  var out = "";
  for (var i = 0; i < parts.length; i++) {
    var s = String(parts[i] == null ? "" : parts[i]).trim();
    if (!s) continue;
    out = out ? out + (CJK_TAIL.test(out) && CJK_HEAD.test(s) ? "" : " ") + s : s;
  }
  return out;
}
/* 抽正文时红字被包在 \u0001 \u0002 里：出题库时去掉，判答案时要用它。
   markdown 的两个排版记号也在这里落地：行内代码的反引号、**加粗**——
   界面按纯文本渲染题干，留着就会在题里看到一排 ` 和 * */
function plain(s) {
  return nz(String(s).replace(/[\u0001\u0002]/g, "")
    .replace(/\*\*([^*]+)\*\*/g, "$1").replace(/`/g, ""));
}
function redRatio(s) {
  var tot = 0, red = 0, on = false;
  for (var i = 0; i < s.length; i++) {
    var c = s.charAt(i);
    if (c === "\u0001") { on = true; continue; }
    if (c === "\u0002") { on = false; continue; }
    if (c === " ") continue;
    tot++;
    if (on) red++;
  }
  return tot ? red / tot : 0;
}
function answerLetters(v) {
  var t = String(v || "").replace(/[\s,，、.。;；:：/()（）{}【】]/g, "");
  if (!t || t.length > 8) return [];
  var up = t.toUpperCase(), out = [], i;
  for (i = 0; i < up.length; i++) if (LET.indexOf(up.charAt(i)) < 0) return [];
  for (i = 0; i < up.length; i++) if (out.indexOf(up.charAt(i)) < 0) out.push(up.charAt(i));
  return out.sort();
}
function judgeLetters(v) {
  var t = String(v || "").replace(/[\s.。]/g, "");
  if (t === "正确" || t === "对" || t === "√" || t === "是") return ["A"];
  if (t === "错误" || t === "错" || t === "×" || t === "X" || t === "否") return ["B"];
  return [];
}
/* 一行里塞了多个选项："A、x B、y C、z" */
function inlineOptions(text, want) {
  var idx = LET.indexOf(want), pos = 0, bounds = [], guard = 0;
  while (idx < LET.length && guard++ < 12) {
    var rest = text.slice(pos), m = OPT_INLINE_RE[idx].exec(rest);
    if (!m) break;
    bounds.push([pos + m.index, pos + m.index + m[0].length, LET.charAt(idx)]);
    pos = pos + m.index + m[0].length;
    idx++;
  }
  var out = [];
  for (var i = 0; i < bounds.length; i++) {
    var nxt = i + 1 < bounds.length ? bounds[i + 1][0] : text.length;
    out.push([bounds[i][2], nz(text.slice(bounds[i][1], nxt))]);
  }
  return out;
}

function parseBankText(text) {
  var raw = String(text).replace(/\r/g, "\n").split("\n");
  var qs = [], cur = null, expect = 1;
  function close() { if (cur) qs.push(cur); cur = null; }

  for (var li = 0; li < raw.length; li++) {
    var line = nz(raw[li]);
    if (!line) continue;
    /* markdown 的代码围栏是排版记号，不是题干；里面的代码行照旧收进题干 */
    if (/^[`~]{3,}/.test(line)) continue;
    var head, answer = null, explain = null, ref = null;
    var me = QEXPL_RE.exec(line);
    if (me) { explain = me[1].trim(); line = nz(line.slice(0, me.index)); }
    /* 先认「参考答案：」再认「答案：」，顺序不能反 */
    var mr = QREF_RE.exec(line);
    if (mr) { ref = mr[1].trim(); line = nz(line.slice(0, mr.index)); }
    else {
      var ma = QANS_RE.exec(line);
      if (ma) { answer = ma[1].trim(); line = nz(line.slice(0, ma.index)); }
    }
    /* 「正确答案：A」「答案解析：X」这类带前缀/后缀的标签，切完会剩下一截光杆标签
       （「正确」「答案」）。它不是题干也不是选项，留着会被当成选项的续行拼到最后一个选项上。 */
    if (/^\s*(?:参考|正确|标准|答案|题目|题干|选项|解析|答案解析)*\s*[:：]?\s*$/.test(line)) line = "";
    head = unlabel(line);

    var mn = NUM_RE.exec(head) || QHEAD_RE.exec(head);
    /* 「第N题」这种标题没有歧义，不受连续号限制：从中间截一份题集（第12题起）也认得出来。
       行首「1、」就不行——题干里以数字开头的行太多，必须靠连续性挡误判。 */
    if (mn && (+mn[1] >= expect && +mn[1] <= expect + 3 || QHEAD_RE.test(head))) {
      close();
      cur = { num: +mn[1], stem: [unlabel(mn[2])], options: [], answer_text: "", ref_text: "",
              explain: [], in_expl: false };
      expect = cur.num + 1;
    } else if (cur && head) {
      if (cur.in_expl) {
        cur.explain.push(head);
      } else {
        var want = LET.charAt(cur.options.length);
        var mo = QOPT_RE.exec(head) || QOPT_LOOSE_RE.exec(head);
        if (mo && mo[1] === "A" && cur.options.length && !cur.answer_text) {
          // 第二次从 A 开始，说明上一批 A/B/C 是题干里的子项，不是选项
          for (var k = 0; k < cur.options.length; k++) {
            cur.stem.push(cur.options[k][0] + "、" + cur.options[k][1]);
          }
          cur.options = [];
          want = "A";
        }
        if (mo && mo[1] === want) {
          /* 已经收过前几个选项，这一行以 C. 开头也不代表 C 是最后一个：
             PDF 换行后同一行里可能还塞着 D. */
          var multi = inlineOptions(head, want);
          if (multi.length > 1) {
            for (var m2 = 0; m2 < multi.length; m2++) {
              cur.options.push([multi[m2][0], multi[m2][1], redRatio(multi[m2][1])]);
            }
          } else {
            cur.options.push([mo[1], nz(mo[2]), redRatio(mo[2])]);
          }
        } else if (!cur.options.length) {
          cur.stem.push(head);
        } else {
          /* 选项整段是连续的「A. … B. … C. …」，PDF 换行会从中间切断：
             「… C. 数据」在行末，剩下的「在存储时加密 D. …」落到下一行。
             续行里再出现的 C. / D. 不拆开，就会被并进上一个选项，
             答案字母（D）也就对不上任何选项，整题变成「无答案」。 */
          var more = cur.options.length < LET.length ? inlineOptions(head, LET.charAt(cur.options.length)) : [];
          var last = cur.options[cur.options.length - 1];
          if (more.length) {
            var cut = OPT_INLINE_RE[cur.options.length].exec(head);
            var keep = head.slice(0, cut.index);
            if (keep.trim()) {
              last[1] = nz(joinText([last[1], keep]));
              last[2] = Math.max(last[2], redRatio(keep));
            }
            for (var m3 = 0; m3 < more.length; m3++) {
              cur.options.push([more[m3][0], more[m3][1], redRatio(more[m3][1])]);
            }
          } else {
            last[1] = nz(joinText([last[1], head]));
            last[2] = Math.max(last[2], redRatio(head));
          }
        }
      }
    }
    if (cur) {
      if (answer !== null) cur.answer_text = nz(cur.answer_text + " " + answer);
      if (ref !== null) cur.ref_text = nz(cur.ref_text + " " + ref);
      if (explain !== null) { cur.in_expl = true; cur.explain.push(explain); }
    }
  }
  close();

  var out = [];
  for (var i = 0; i < qs.length; i++) {
    var q = qs[i], stem = nz(joinText(q.stem)), opts = [];
    for (var j = 0; j < q.options.length; j++) opts.push([q.options[j][0], plain(q.options[j][1])]);
    var letters = answerLetters(q.answer_text);
    var ans = letters.length ? letters : judgeLetters(q.answer_text);
    var type = "single";
    /* 显式题型标记优先级最高：写了「填空题」就是填空题，后面所有靠答案形态
       猜出来的结论都不能把它改回客观题——那正是「把主观题伪装成自动对错」 */
    var kind = kindOf(stem);
    if (!letters.length && ans.length && kind !== "fill" && kind !== "short") {
      type = "judge";
      if (!opts.length) {
        opts = [["A", "正确"], ["B", "错误"]];
        stem = nz(stem.replace(/(\s*(正确|错误|对|错)){1,2}$/, ""));
      }
    }
    if (ans.length > 1 && kind !== "fill" && kind !== "short") type = "multi";
    if (!ans.length && kind !== "fill" && kind !== "short") {
      var red = [];
      for (j = 0; j < q.options.length; j++) if (q.options[j][2] > 0.3) red.push(q.options[j][0]);
      if (red.length) { ans = red; type = red.length > 1 ? "multi" : "single"; }
    }
    if (/[（(【]\s*多选/.test(stem) && kind !== "fill" && kind !== "short") type = "multi";
    if (/[（(【]\s*单选/.test(stem) && kind !== "fill" && kind !== "short") type = "single";
    if (type === "single" && opts.length === 2) {
      var pair = opts[0][1] + "|" + opts[1][1];
      if (pair === "正确|错误" || pair === "对|错") type = "judge";
    }
    if (kind === "fill" || kind === "short") {
      /* 主观题：选项不是选项，是正文的一部分（有些题库会把「要点」排成 A/B/C） */
      if (opts.length) {
        var extra = opts.map(function (o) { return o[0] + "、" + o[1]; }).join(" ");
        stem = nz(joinText([stem, extra]));
        opts = [];
      }
      type = kind;
      ans = [];
    } else if (!opts.length && !ans.length && BLANK_RE.test(stem)) {
      /* 没写题型标记，但题干里有作答横线：按填空题收，总比当成无答案的选择题丢掉好 */
      type = "fill";
    } else if (!opts.length && !ans.length && q.ref_text) {
      /* 有参考答案、没选项、也没有可判的字母：这是解答题 */
      type = "short";
    }
    ans = ans.filter(function (a) {
      for (var n = 0; n < opts.length; n++) if (opts[n][0] === a) return true;
      return false;
    }).sort();
    var refText = plain(q.ref_text);
    if ((type === "fill" || type === "short") && !refText) refText = plain(q.answer_text);
    out.push({
      num: q.num, pnum: q.num,
      stem: plain(stem.replace(/[（(【]\s*(多选|单选|判断|填空|解答|简答|问答)[题]?\s*[)）】]/g, " ")),
      type: type, options: opts, answer: ans, ans_raw: q.answer_text,
      ref: (type === "fill" || type === "short") ? refText : "",
      explain: plain(joinText(q.explain)), note: "", flag: ""
    });
  }

  var seen = {}, ded = [], dup = 0;
  out.forEach(function (x) {
    var k = qKey(x);
    if (seen[k]) { dup++; return; }
    seen[k] = 1;
    ded.push(x);
  });
  ded.report = { raw: out.length, dup: dup };
  return ded;
}

/* ================= 导入流程与进度 ================= */
var IMPORT_STAGES = { pick: .06, read: .22, extract: .5, parse: .72, save: .92, done: 1 };
var importName = "";

function openImport() {
  var sh = document.getElementById("sheet3");
  sh.innerHTML = '<div class="handle"></div><h2>导入题库</h2>' +
    '<div class="sl-hint" style="margin:0 0 18px">支持 PDF、Word（.docx）和 txt / md。' +
    '带解析的会一起导入；红字标答案的文档也能识别。</div>' +
    '<button class="sheet-act" onclick="pickFile()">选择文件导入</button>' +
    '<div style="display:flex;margin-top:12px">' +
      '<button class="sheet-act2" onclick="openFS(&quot;sample&quot;)">查看格式举例</button>' +
      '<button class="sheet-act2" onclick="saveSample()">下载这份举例</button>' +
    "</div>" +
    '<div class="sl-hint" style="margin:14px 0 0">PDF 需要有文字层：扫描件、图片版和加密文件读不出内容，会明确提示而不是当成导入成功。</div>' +
    '<div class="sl-hint" style="margin:10px 0 0">也可以在微信里长按文件 →「用其他应用打开」→ 选「GIme」。</div>' +
    '<div id="impBar" style="display:none">' +
      '<div class="track" style="margin:22px 0 8px"><i id="impFill"></i></div>' +
      '<div class="sl-hint" id="impMsg" style="margin:0">准备中…</div>' +
    "</div>";
  openLayer(sh);
}
function pickFile() {
  if (!window.Android || !Android.pickFile) { toast("请在手机上使用 App 导入"); return; }
  importStage("pick");
  Android.pickFile();
}
function importStage(k) {
  var sh = document.getElementById("sheet3");
  /* 微信「用其他应用打开」直接递文件进来时面板还没打开；上一次的成功页也会顶掉进度条。
     两种情况都得先把导入面板重建出来，否则进度和结果都写在看不见的地方。 */
  if (sh && (!document.getElementById("impBar") || !sh.classList.contains("on"))) openImport();
  var bar = document.getElementById("impBar"), fill = document.getElementById("impFill"),
      msg = document.getElementById("impMsg");
  if (!bar) return;
  bar.style.display = "block";
  fill.style.width = Math.round((IMPORT_STAGES[k] || 0) * 100) + "%";
  msg.textContent = ({
    pick: "等待选择文件…", read: "读取文件…", extract: "提取正文…",
    parse: "识别题目和解析…", save: "生成题库…", done: "完成"
  })[k] || "处理中…";
}
window.importStage = importStage;
/* 原生层出错时要能给用户一句提示，得有个挂到 window 上的入口 */
window.toast = toast;

/* 同一道题的判据：题干（去掉标点空格）+ 选项正文。本地解析去重和模型分批出题都用它，
   两份口径不能各写一遍——一边算重复、另一边不算，题数就对不上了。 */
function qKey(x) {
  return String(x.stem || "").replace(/[^0-9A-Za-z一-鿿]/g, "") +
         "||" + (x.options || []).map(function (o) { return o[1]; }).join("|");
}
/* 题干相似度：只用来兜「同一题被改写一两个字」这种，阈值 0.95。
   实测同一份 253 题的库里，两道**不同**的题最高能到 0.898，
   所以阈值放低会把真题并掉——宁可漏合并，不能误合并。 */
var STEM_SIM_MERGE = 0.95;
function stemNorm(s) { return String(s || "").replace(/[^0-9A-Za-z一-鿿]/g, ""); }
function stemDice(a, b) {
  if (a === b) return 1;
  if (a.length < 6 || b.length < 6) return 0;
  var i, hit = 0, ca = {};
  for (i = 0; i < a.length - 1; i++) ca[a.substr(i, 2)] = (ca[a.substr(i, 2)] || 0) + 1;
  for (i = 0; i < b.length - 1; i++) {
    var g = b.substr(i, 2);
    if (ca[g]) { ca[g]--; hit++; }
  }
  return (2 * hit) / ((a.length - 1) + (b.length - 1));
}
/* 一道题能不能进题库：客观题要有答案且至少两个选项；
   主观题只要有题干就行（参考答案缺失也收，界面会标「未提供参考答案」，
   总比默默丢掉强）。判分与否交给 isObjective，不在这里一刀切。 */
function usableQ(x) {
  if (isSubjective(x.type)) return !!x.stem;
  return x.answer.length > 0 && x.options.length >= 2;
}

window.onImportText = function (name, text) { runImport(name, text); };
function runImport(name, text) {
  importName = name || "未命名题库";
  importStage("parse");
  setTimeout(function () {
    var qs;
    try { qs = parseBankText(text); }
    catch (e) { onImportError("解析失败：" + e.message); return; }
    var ok = qs.filter(usableQ);
    if (!qs.length) { onImportError("没在文件里认出题目（题号要像 1、2、3 这样连续编号）"); return; }
    importStage("save");
    setTimeout(function () { importWithName(qs, ok); }, 30);
  }, 60);
};
/* 落盘前先过名字那道闸：超过 30 字就让人改名，改完带着新名字继续导入。
   文件名（题源）保持原样，只改显示名。 */
function importWithName(qs, ok) {
  var want = bankName(importName);
  if (bankNameTooLong(want)) {
    askBankNameThen(want, "这份题库的名字有 " + want.length + " 字", function (n) {
      finishImport(qs, ok, n);
    });
    return;
  }
  finishImport(qs, ok);
}

/* ================= 外部打开（微信「用其他应用打开」） =================
   文件已经在原生层抽成正文，这里只问用户走哪条：系统导入，还是让大肥鱼按导入格式解析。
   两条路复用同一份正文，不重复读文件；大肥鱼那条只把文件挂成附件并用当前会话发一句提示词。 */
/* 附件交给大肥鱼识别这条路的额度，放在两处字符串拼接之前（先赋值后才能拼进去）：
   一次上行多少字正文、一批多少题、识别给几轮、每轮多少输出额度。
   SEND_MAX 按「一份百题题库的正文」定，超过就在附件条和提示里明说只发了前截。 */
/* 一次请求最多走几步：8 × 24 ≈ 190 题，一份大题库一轮就能跑完。 */
var CHAT_FILE_ROUNDS = 8;
var CHAT_FILE_MAX_TOKENS = 6000;
/* 一轮还交不完时，页面自己接着要，不用用户再喊「继续」。 */
var CHAT_AUTO_ROUNDS = 6;
var chatAutoRounds = 0, chatLastImported = 0;
var extFile = null;
var EXT_AGENT_PROMPT = "上一条系统消息里就是这份附件的正文。按导入格式把正文里的题全部识别出来，" +
  "用 import_attachment 一次交回来；万一一次写不下（回复被长度上限截断），就把剩下的接着调用，直到全部交完。" +
  "题号连续的按题拆，并把原文题号填进 num（重跑同一份文档时靠它去重）；缺答案或缺选项的跳过（不要自己补答案）。" +
  "全部交完后用一句话告诉用户：已识别多少道题、其中可收录多少道，问他是否导入。";

window.onExternalFile = function (name, text) {
  extFile = { name: String(name || "文件"), text: String(text || "") };
  showExternalChoice();
};
function showExternalChoice() {
  if (!extFile) return;
  var st = dsStatus();
  var needLogin = !st || !st.loggedIn;
  var sh = document.getElementById("sheet3");
  sh.innerHTML = '<div class="handle"></div><h2>怎么导入这份题库？</h2>' +
    '<div class="note" style="margin:0 0 16px;word-break:break-all">' + esc(extFile.name) + "</div>" +
    '<div class="sheet-pair">' +
      '<button class="sheet-act" onclick="externalImportDirect()">直接导入</button>' +
      '<button class="sheet-act2" onclick="externalImportByAgent()">交给大肥鱼解析</button>' +
    "</div>" +
    '<div class="sl-hint" style="margin:14px 0 0">直接导入只在这台手机上解析，文件不出本机。</div>' +
    '<div class="sl-hint" style="margin:8px 0 0">交给大肥鱼会把正文分段发给 DeepSeek 逐题识别' +
    "（超过约 " + Math.round(CHAT_SEG_MAX / 1000) + " 千字自动分段，长文档也能整份认完），格式不一样的文档认得更全；" +
    "写进题库前还会弹卡片让你确认。" +
    (needLogin ? "还没登录 DeepSeek，点它会先带你去登录。" : "") + "</div>";
  openLayer(sh);
}
function externalImportDirect() {
  var f = extFile;
  extFile = null;
  closeTop();
  if (f) runImport(f.name, f.text);
}
function externalImportByAgent() {
  var f = extFile;
  if (!f) return;
  var cfg = agentCfg();
  if (!cfg) { closeTop(); toast("先登录 DeepSeek 或填 API Key"); openAgentCfg(); return; }
  if (cfg.builtin) {
    var st = dsStatus();
    if (!st || !st.loggedIn) { closeTop(); toast("先登录 DeepSeek"); dsLoginNow(); return; }
  }
  closeTop();
  window.onChatFile(f.name, f.text);
  if (!chatFile) return;      /* 扩展名或解析被 onChatFile 拒了，它自己会 toast */
  extFile = null;
  openChatTab();
  chatAsk(EXT_AGENT_PROMPT);
}
/* 导入结果的统计口径统一在这里：无答案题必须在「原始解析结果」上数，
   usable 已经把它滤掉了，在 usable 上数永远是 0。主观题另算，不算「无答案」。 */
function importReport(all, usable) {
  var by = { single: 0, multi: 0, judge: 0, fill: 0, short: 0 };
  var noRef = 0;
  usable.forEach(function (x) {
    if (by[x.type] !== undefined) by[x.type]++;
    if (isSubjective(x.type) && !String(x.ref || "").trim()) noRef++;
  });
  var noans = all.filter(function (x) {
    return !isSubjective(x.type) && !x.answer.length;
  }).length;
  return {
    total: all.length,
    usable: usable.length,
    noans: noans,
    dropped: all.length - usable.length - noans,
    noRef: noRef,
    by: by,
    dup: (all.report && all.report.dup) || 0
  };
}
/* 结果页的统计行：分题型报数，主观题单列「参考答案缺失」，
   这样用户能看出是文档格式问题还是真的没写答案，而不是只看到一句「缺选项 N」 */
function importLine(rep) {
  var b = rep.by || {};
  var parts = [];
  if (b.single) parts.push("单选 " + b.single);
  if (b.multi) parts.push("多选 " + b.multi);
  if (b.judge) parts.push("判断 " + b.judge);
  if (b.fill) parts.push("填空 " + b.fill);
  if (b.short) parts.push("解答 " + b.short);
  var s = "认出 " + rep.total + " 题，收录 " + rep.usable + " 题";
  if (parts.length) s += "（" + parts.join("、") + "）";
  var bad = [];
  if (rep.noans) bad.push("无答案 " + rep.noans);
  if (rep.dropped) bad.push("缺选项 " + rep.dropped);
  if (rep.noRef) bad.push("缺参考答案 " + rep.noRef);
  if (rep.dup) bad.push("重复 " + rep.dup);
  if (bad.length) s += "；未收录或待补：" + bad.join("、");
  return esc(s);
}
/* 落盘只有这一处实现：导入卡和大肥鱼的附件确认共用，免得两份口径慢慢漂开。 */
function saveImportBank(displayName, usable, sourceName) {
  var src = sourceName == null ? displayName : sourceName;
  var sid = "import_" + Date.now().toString(36);
  var subj = { id: sid, name: bankName(displayName), file: src, count: usable.length, custom: true };
  usable.forEach(function (x, i) {
    x.num = i + 1;
    x.id = sid + "-" + i;
    x.subject = sid;
    x.source = src;
  });
  var payload = { subject: subj, questions: usable };
  var ok = true;
  if (window.Android && Android.saveBank) ok = Android.saveBank(sid, JSON.stringify(payload));
  /* 存盘失败就别往列表里塞：否则下次启动题库凭空消失，比当场报错更糟 */
  if (ok) {
    BANK.subjects.push(subj);
    usable.forEach(function (x) { byId[x.id] = x; });
    BANK.questions = BANK.questions.concat(usable);
  }
  return { ok: ok, sid: sid, subj: subj };
}
function finishImport(all, usable, displayName) {
  var r = saveImportBank(displayName || importName, usable, importName);
  var saved = r.ok, subj = r.subj;
  importStage("done");
  var rep = importReport(all, usable);
  document.getElementById("sheet3").innerHTML =
    '<div class="handle"></div><div class="glass" style="position:static;transform:none;opacity:1;visibility:visible;max-width:none;width:auto;box-shadow:none">' +
      '<div class="icon">' + (saved ? "✓" : "!") + "</div>" +
      '<div class="big">' + (saved ? usable.length : 0) + "</div>" +
      "<h2>" + (saved ? "导入成功" : "没能保存") + "</h2><p>" + esc(subj.name) + "　" +
      (saved ? "已存到本机" : "写文件失败，题库未加入列表，请检查存储空间后重试") + "</p></div>" +
    '<div class="legend" style="margin:0 0 16px">' + importLine(rep) + "</div>" +
    (saved
      ? '<button class="sheet-act" onclick="closeTop();goTab(&quot;bank&quot;)">去看这个题库</button>'
      : '<button class="sheet-act" onclick="openImport()">重新导入</button>');
  save();
  renderAll();
}
function bankName(file) {
  return String(file).replace(/\.(docx|doc|pdf|txt|md)$/i, "").replace(/^[\s\-—_]+|[\s\-—_]+$/g, "") || "导入题库";
}

/* 题库名上限 30 字。超长不静默截断：先把人拦下来改名，改完再继续原来的动作——
   悄悄砍掉半截名字，用户下次在列表里认不出这是哪份题。 */
var BANK_NAME_MAX = 30;
var bankNameOkFn = null;
function bankNameTooLong(n) { return String(n == null ? "" : n).length > BANK_NAME_MAX; }
function askBankNameThen(current, why, onOk) {
  var full = String(current == null ? "" : current);
  var sh = document.getElementById("sheet3");
  sh.innerHTML = '<div class="handle"></div><h2>题库名太长了</h2>' +
    '<div class="note" style="margin:0 0 14px">' + esc(why) + '，题库名最多 ' + BANK_NAME_MAX +
      ' 字。改短之后才能继续。</div>' +
    '<input class="rn-box" id="bnName" maxlength="' + BANK_NAME_MAX + '" placeholder="题库显示名" value="' +
      esc(full.slice(0, BANK_NAME_MAX)) + '" onkeydown="if(event.key===&quot;Enter&quot;)bankNameOk()">' +
    '<button class="sheet-act" style="margin-top:18px" onclick="bankNameOk()">用这个名字继续</button>' +
    '<div class="sl-hint" style="margin:16px 0 0">原名字 ' + full.length + ' 字：' + esc(full) + '</div>';
  bankNameOkFn = onOk;
  openLayer(sh);
  setTimeout(function () { var b = document.getElementById("bnName"); if (b) b.focus(); }, 320);
}
function bankNameOk() {
  var el = document.getElementById("bnName");
  var v = nz(String(el && el.value || ""));
  if (!v) { toast("名字别空着"); return; }
  if (v.length > BANK_NAME_MAX) { toast("题库名最多 " + BANK_NAME_MAX + " 字"); return; }
  var fn = bankNameOkFn;
  bankNameOkFn = null;
  closeTop();
  if (fn) fn(v);
}
window.onImportError = function (msg) {
  var sh = document.getElementById("sheet3");
  if (!sh || !sh.classList.contains("on")) { toast(msg); return; }
  sh.innerHTML = '<div class="handle"></div><h2>导入失败</h2>' +
    '<div class="note" style="margin:0 0 18px">' + esc(msg) + "</div>" +
    '<button class="sheet-act" onclick="openImport()">重试</button>';
};

/* ================= 已导入 / 已隐藏题库的注册表 ================= */
function bootImportedBanks() {
  if (!window.Android || !Android.loadBanks) return;
  var list;
  try { list = JSON.parse(Android.loadBanks()); } catch (e) { return; }
  for (var i = 0; i < list.length; i++) {
    var b = list[i];
    if (!b || !b.subject || !b.questions) continue;
    BANK.subjects.push(b.subject);
    for (var j = 0; j < b.questions.length; j++) {
      var q = b.questions[j];
      q.subject = b.subject.id;
      byId[q.id] = q;
      BANK.questions.push(q);
    }
  }
  banksLoaded = true;
}
function subjectOf(sid) {
  for (var i = 0; i < BANK.subjects.length; i++) if (BANK.subjects[i].id === sid) return BANK.subjects[i];
  return null;
}
function removeBank(sid) {
  /* 原生删文件失败时不能假装删了：刷新后题库会诈尸，用户以为数据丢了 */
  if (window.Android && Android.deleteBank) {
    var okDel = false;
    try { okDel = Android.deleteBank(sid); } catch (e) { okDel = false; }
    if (!okDel) { toast("删除失败，题库还在，请稍后重试"); return false; }
  }
  BANK.subjects = BANK.subjects.filter(function (s) { return s.id !== sid; });
  BANK.questions = BANK.questions.filter(function (q) {
    if (q.subject !== sid) return true;
    delete byId[q.id];
    delete prog[q.id];
    return false;
  });
  /* 进行中的会话如果属于这个题库，题都没了留着只会渲染出空页，直接丢掉 */
  if (S && S.subject === sid) {
    S = null;
    localStorage.removeItem(LS.j);
    document.getElementById("quiz").classList.remove("on");
  }
  Object.keys(resume).forEach(function (k) { if (k.indexOf(sid + "|") === 0) delete resume[k]; });
  if (set.hidden) delete set.hidden[sid];
  /* 组卷选择里也要摘掉：否则卷名和「全选」标签会继续把已删题库算进去 */
  if (set.paper && set.paper.subs) delete set.paper.subs[sid];
  progMayEmpty = true;   /* 删库会让 prog 合法地变少甚至变空 */
  save();
  renderAll();
  return true;
}
function askDeleteBank(sid) {
  var s = subjectOf(sid);
  if (!s) return;
  /* 所有题库都是用户自己导入的，所以只有「删除」一种处理；
     确认框里必须把连带影响说清楚：题目、进度、错题、收藏、进行中的会话 */
  glass({ title: "删除「" + s.name + "」？",
    desc: "这个题库的 " + stats(sid).total + " 道题会一起删掉，它们的做题记录、错题、收藏也会清空，无法恢复。",
    ok: ["删除", function () { if (removeBank(sid)) toast("题库已删除"); }] });
}

/* 改名：显示名只存在这个题库自己的 json 里，所以改名 = 整包重写（id 不变）。
   进度、错题、收藏都挂在题目 id 上，不跟着名字走，改完不会掉记录。 */
function askRenameBank(sid) {
  var s = subjectOf(sid);
  if (!s) return;
  var sh = document.getElementById("sheet3");
  sh.innerHTML = '<div class="handle"></div><h2>给题库改个名</h2>' +
    '<input class="rn-box" id="rnName" maxlength="30" placeholder="题库显示名" ' +
      'value="' + esc(s.name) + '" onkeydown="if(event.key===&quot;Enter&quot;)renameBank(&quot;' + s.id + '&quot;, this.value)">' +
    '<button class="sheet-act" style="margin-top:18px" onclick="renameBank(&quot;' + s.id + '&quot;, document.getElementById(&quot;rnName&quot;).value)">保存</button>' +
    '<div class="sl-hint" style="margin:16px 0 0">只改显示名。题目里的「题源」标记仍写导入时的文件名——那是这份题出处的记录，改了会对不上原文件。</div>';
  openLayer(sh);
  /* 面板滑上来要 250ms，早于它 focus 会被随后的 transform 打断 */
  setTimeout(function () { var b = document.getElementById("rnName"); if (b) b.focus(); }, 320);
}
function renameBank(sid, name) {
  var s = subjectOf(sid);
  if (!s) return;
  var n = nz(String(name || ""));
  if (n.length > BANK_NAME_MAX) { toast("题库名最多 " + BANK_NAME_MAX + " 字"); return; }
  if (!n) { toast("名字别空着"); return; }
  if (n === s.name) { closeTop(); return; }
  var old = s.name;
  s.name = n;
  if (window.Android && Android.saveBank) {
    var payload = { subject: s, questions: BANK.questions.filter(function (q) { return q.subject === sid; }) };
    var okSave = false;
    try { okSave = Android.saveBank(sid, JSON.stringify(payload)); } catch (e) { okSave = false; }
    if (!okSave) { s.name = old; toast("保存失败，名字没改"); return; }
  }
  closeTop();
  renderAll();
  toast("已改名为「" + n + "」");
}

/* 左滑面板：题库卡片和首页「今日待刷」行共用这一套手势；竖向手势留给页面滚动，不抢 */
(function () {
  var sw = null;
  function faceOf(el) { return el && el.querySelector ? el.querySelector(".card, .task") : null; }
  /* 面板多宽由里面几个按钮决定（只有删除=92，改名+删除=184），现场量不写死 */
  function panelW(el) { var p = el.firstElementChild; return p && p.offsetWidth ? p.offsetWidth : 92; }
  /* 起点偏移记在 base 上，move 里只算不改：已打开的卡片要从面板宽度往回算，
     否则右滑的位移会被当成从 0 开始，收不回去（原来用 locked 直接禁掉，
     结果就是删除区一旦打开，在这个页面里再也关不掉）。 */
  document.addEventListener("touchstart", function (e) {
    if (curTab !== "bank" && curTab !== "home") { sw = null; return; }
    var t = e.touches[0];
    var host = e.target.closest ? e.target.closest(".swipe") : null;
    sw = null;
    if (!host) return;
    var w = panelW(host);
    host.style.setProperty("--swx", "-" + w + "px");
    sw = { el: host, w: w, x0: t.clientX, y0: t.clientY,
           base: host.classList.contains("open") ? -w : 0, off: 0 };
  }, { passive: true });
  document.addEventListener("touchmove", function (e) {
    if (!sw) return;
    var t = e.touches[0], dx = t.clientX - sw.x0, dy = t.clientY - sw.y0;
    if (Math.abs(dy) > Math.abs(dx)) { sw = null; return; }
    if (Math.abs(dx) < 8) return;
    sw.off = Math.max(-sw.w, Math.min(0, sw.base + dx));
    var c = faceOf(sw.el);
    if (!c) return;
    c.style.transition = "none";
    c.style.transform = "translateX(" + sw.off + "px)";
  }, { passive: true });
  document.addEventListener("touchend", function () {
    if (!sw) return;
    var el = sw.el, open = sw.off < -sw.w * .435, c = faceOf(el);
    if (c) { c.style.transition = ""; c.style.transform = ""; }
    [].slice.call(document.querySelectorAll(".swipe.open")).forEach(function (o) {
      if (o !== el) o.classList.remove("open");
    });
    if (open) el.classList.add("open"); else el.classList.remove("open");
    sw = null;
  });
  /* 被打断的横滑要把卡片弹回原位，否则它停在半开的位置上 */
  document.addEventListener("touchcancel", function () {
    if (!sw) return;
    var c = faceOf(sw.el);
    if (c) { c.style.transition = ""; c.style.transform = ""; }
    sw = null;
  });
})();

/* ================= MCP 工具层 =================
   原生层只做 HTTP 与口令（McpServer），JSON-RPC 与工具都在这里：
   导入沿用 parseBankText，题库落盘走 Android.saveBank，改解析复用同一份题库对象。
   这样 agent 看到的题和 App 里刷的是同一份，不会出现两套解析口径。 */
var MCP_PROTO = "2025-03-26";
/* 10 个工具的 schema 在 Java ToolRegistry（唯一真源），页面只做缓存与读取。
   执行仍留在下面这些函数里：题库解析、落盘、渲染复用同一套逻辑。 */
var MCP_TOOL_CACHE = null;
function mcpRegistryTools() {
  if (MCP_TOOL_CACHE) return MCP_TOOL_CACHE;
  try {
    if (window.Android && Android.toolSchemas) {
      var o = JSON.parse(Android.toolSchemas());
      if (o && Array.isArray(o.tools) && o.tools.length) {
        MCP_TOOL_CACHE = o.tools;
        return MCP_TOOL_CACHE;
      }
    }
  } catch (e) { }
  return [];
}function mcpBankArg(v) {
  var want = String(v == null ? "" : v).trim();
  if (!want) throw new Error("bank 不能为空");
  var i;
  for (i = 0; i < BANK.subjects.length; i++) if (BANK.subjects[i].id === want) return BANK.subjects[i];
  var hits = BANK.subjects.filter(function (s) { return s.name === want; });
  if (!hits.length) hits = BANK.subjects.filter(function (s) { return s.name.toLowerCase() === want.toLowerCase(); });
  if (hits.length === 1) return hits[0];
  if (!hits.length) throw new Error("找不到题库：" + want);
  var dup = new Error("题库名不唯一，请改用 id：" + hits.map(function (s) { return s.id; }).join(", "));
  /* 新版协议把它变成正式 MRTR：input_required + 选项，客户端选完重试原请求。 */
  dup.needInput = { field: "bank", options: hits.map(function (s) { return { id: s.id, label: s.name }; }) };
  throw dup;
}
function mcpQ(q) {
  return { id: q.id, num: q.num, type: q.type, stem: q.stem,
           options: (q.options || []).map(function (o) { return { key: o[0], text: o[1] }; }),
           answer: (q.answer || []).slice(), explain: q.explain || "", ref: q.ref || "",
           source: q.source || "", flag: q.flag || "" };
}
function mcpImport(args) {
  var text = typeof args.text === "string" ? args.text : "";
  if (!text.trim()) throw new Error("text 不能为空");
  var all = parseBankText(text), usable = all.filter(usableQ), rep = importReport(all, usable);
  if (args.dryRun === true) return { dryRun: true, report: rep, preview: usable.slice(0, 3).map(mcpQ) };
  if (!usable.length) throw new Error("没有可收录的题目：认出 " + rep.total + " 题，可用 0 题（缺选项/答案）");
  if (!window.Android || !Android.saveBank) throw new Error("当前环境不能保存题库");
  var name = String(args.name == null ? "" : args.name).trim() || "MCP 导入";
  /* 超长不截断：agent 背后没有人能改名字，让它换一个短名再来，
     否则写进题库的是半截名字，用户在列表里认不出这是哪份题。 */
  if (bankNameTooLong(name)) {
    throw new Error("题库名最多 " + BANK_NAME_MAX + " 字（这个名字 " + name.length + " 字），请换一个更短的显示名再导入");
  }
  var sid, n = 0;
  do { sid = "import_" + (Date.now() + n).toString(36); n++; } while (subjectOf(sid));
  var subj = { id: sid, name: name, file: "mcp.txt", count: usable.length, custom: true };
  usable.forEach(function (x, i) {
    x.num = i + 1; x.pnum = i + 1; x.id = sid + "-" + i; x.subject = sid; x.source = "MCP";
  });
  var saved = false;
  try { saved = Android.saveBank(sid, JSON.stringify({ subject: subj, questions: usable })); } catch (e) { saved = false; }
  if (!saved) throw new Error("题库写盘失败，没有导入");
  BANK.subjects.push(subj);
  usable.forEach(function (x) { byId[x.id] = x; });
  BANK.questions = BANK.questions.concat(usable);
  renderAll();
  toast("agent 导入了「" + name + "」· " + usable.length + " 题");
  return { bank: { id: sid, name: name, count: usable.length }, report: rep };
}
function mcpListQuestions(args) {
  var bank = mcpBankArg(args.bank);
  var all = BANK.questions.filter(function (q) { return q.subject === bank.id; });
  if (Array.isArray(args.ids)) {
    var map = {}, picked = [], missing = [];
    all.forEach(function (q) { map[q.id] = q; });
    args.ids.forEach(function (id) {
      var q = map[String(id)];
      if (q) picked.push(q); else missing.push(String(id));
    });
    return { bank: { id: bank.id, name: bank.name }, total: picked.length,
             questions: picked.map(mcpQ), missing: missing };
  }
  if (args.wrong === true) all = all.filter(inWrong);
  if (args.type) all = all.filter(function (q) { return q.type === args.type; });
  if (args.query) {
    var qq = String(args.query).toLowerCase();
    all = all.filter(function (q) { return String(q.stem || "").toLowerCase().indexOf(qq) >= 0; });
  }
  var off = Math.max(0, parseInt(args.offset, 10) || 0);
  var lim = Math.min(100, Math.max(1, parseInt(args.limit, 10) || 20));
  var page = all.slice(off, off + lim);
  if (args.compact === true) {
    return { bank: { id: bank.id, name: bank.name }, total: all.length, offset: off, limit: lim,
             compact: true, ids: page.map(function (q) { return q.id; }) };
  }
  return { bank: { id: bank.id, name: bank.name }, total: all.length, offset: off, limit: lim,
           questions: page.map(mcpQ) };
}
function mcpWriteExplanations(args) {
  var bank = mcpBankArg(args.bank);
  var items = Array.isArray(args.items) ? args.items : [];
  if (!items.length) throw new Error("items 不能为空");
  var mode = args.mode === "append" ? "append" : "replace";
  var qs = BANK.questions.filter(function (q) { return q.subject === bank.id; });
  var map = {}, missing = [], changes = [];
  qs.forEach(function (q) { map[q.id] = q; });
  items.forEach(function (it) {
    var id = it && it.id != null ? String(it.id) : "";
    var q = map[id];
    if (!q) { missing.push(id); return; }
    var hasEx = typeof it.explain === "string", hasRef = typeof it.ref === "string";
    if (!hasEx && !hasRef) { missing.push(id); return; }
    if (hasEx && it.explain.length > 20000) throw new Error("解析过长：" + id);
    if (hasRef && it.ref.length > 20000) throw new Error("参考答案过长：" + id);
    var addEx = hasEx ? it.explain.trim() : "";
    var addRef = hasRef ? it.ref.trim() : "";
    var nextEx = hasEx ? (mode === "append" ? (addEx ? (q.explain ? q.explain + "\n\n" + addEx : addEx) : q.explain) : addEx) : null;
    var nextRef = hasRef ? (mode === "append" ? (addRef ? (q.ref ? q.ref + "\n\n" + addRef : addRef) : q.ref) : addRef) : null;
    changes.push({ q: q, ex: nextEx, rf: nextRef, hasEx: hasEx, hasRef: hasRef });
  });
  if (!changes.length) return { updated: 0, missing: missing };
  if (args.dryRun === true) {
    return { dryRun: true, updated: changes.length, missing: missing,
             changes: changes.map(function (c) {
               return { id: c.q.id, explain: c.hasEx ? c.ex : "", ref: c.hasRef ? c.rf : "" };
             }) };
  }
  if (!window.Android || !Android.saveBank) throw new Error("当前环境不能保存题库");
  var clone = {};
  qs.forEach(function (q) { clone[q.id] = Object.assign({}, q); });
  changes.forEach(function (c) {
    if (c.hasEx) clone[c.q.id].explain = c.ex;
    if (c.hasRef) clone[c.q.id].ref = c.rf;
  });
  var payload = { subject: bank, questions: qs.map(function (q) { return clone[q.id]; }) };
  var saved = false;
  try { saved = Android.saveBank(bank.id, JSON.stringify(payload)); } catch (e) { saved = false; }
  if (!saved) throw new Error("题库写盘失败，解析没有改动");
  changes.forEach(function (c) {
    if (c.hasEx) c.q.explain = c.ex;
    if (c.hasRef) c.q.ref = c.rf;
  });
  toast("agent 更新了 " + changes.length + " 条解析");
  return { updated: changes.length, missing: missing };
}
/* 改正确答案：只动客观题的 answer / ans_raw，主观题走 write_explanations 的 ref。
   所有条目先校验完再落盘：不让一半题改了、一半题报错。 */
function mcpWriteAnswers(args) {
  var bank = mcpBankArg(args.bank);
  var items = Array.isArray(args.items) ? args.items : [];
  if (!items.length) throw new Error("items 不能为空");
  var qs = BANK.questions.filter(function (q) { return q.subject === bank.id; });
  var map = {}, missing = [], changes = [];
  qs.forEach(function (q) { map[q.id] = q; });
  items.forEach(function (it) {
    var id = it && it.id != null ? String(it.id) : "";
    var q = map[id];
    if (!q) { missing.push(id); return; }
    if (!isObjective(q.type)) {
      throw new Error("第 " + id + " 题是主观题，没有选项答案；改参考答案请用 write_explanations");
    }
    var raw = it.answer;
    if (!Array.isArray(raw) || !raw.length) throw new Error("第 " + id + " 题 answer 必须是非空选项字母数组");
    var keys = (q.options || []).map(function (o) { return o[0]; });
    var ans = [];
    raw.forEach(function (k) {
      var K = String(k == null ? "" : k).trim().toUpperCase();
      if (keys.indexOf(K) < 0) throw new Error("第 " + id + " 题没有选项 " + (K || "(空)"));
      if (ans.indexOf(K) < 0) ans.push(K);
    });
    ans.sort(function (a, b) { return keys.indexOf(a) - keys.indexOf(b); });
    if ((q.type === "single" || q.type === "judge") && ans.length !== 1) {
      throw new Error("第 " + id + " 题是" + (q.type === "judge" ? "判断题" : "单选题") + "，只能有一个答案");
    }
    changes.push({ q: q, ans: ans });
  });
  if (!changes.length) return { updated: 0, missing: missing };
  if (args.dryRun === true) {
    return { dryRun: true, updated: changes.length, missing: missing,
             changes: changes.map(function (c) { return { id: c.q.id, answer: c.ans.slice() }; }) };
  }
  if (!window.Android || !Android.saveBank) throw new Error("当前环境不能保存题库");
  var clone = {};
  qs.forEach(function (q) { clone[q.id] = Object.assign({}, q); });
  changes.forEach(function (c) {
    clone[c.q.id].answer = c.ans.slice();
    clone[c.q.id].ans_raw = c.ans.join("");
  });
  var payload = { subject: bank, questions: qs.map(function (q) { return clone[q.id]; }) };
  var saved = false;
  try { saved = Android.saveBank(bank.id, JSON.stringify(payload)); } catch (e) { saved = false; }
  if (!saved) throw new Error("题库写盘失败，答案没有改动");
  changes.forEach(function (c) {
    c.q.answer = c.ans.slice();
    c.q.ans_raw = c.ans.join("");
  });
  toast("agent 改了 " + changes.length + " 题答案");
  return { updated: changes.length, missing: missing };
}
function mcpErr(id, code, msg, data) {
  var e = { code: code, message: String(msg) };
  if (data) e.data = data;
  return { jsonrpc: "2.0", id: id === undefined ? null : id, error: e };
}
function mcpOk(id, result) {
  return { jsonrpc: "2.0", id: id === undefined ? null : id, result: result };
}
function mcpToolResult(data, isNew, isList) {
  var r = { content: [{ type: "text", text: JSON.stringify(data) }], structuredContent: data };
  if (isNew && isList) { r.ttlMs = 300000; r.cacheScope = "public"; }
  if (isNew) r.resultType = "complete";
  return r;
}
function mcpToolError(msg) {
  return { content: [{ type: "text", text: String(msg) }], isError: true };
}
/* MRTR：需要补充信息时先存下原工具与参数，客户端带 inputResponses 重试。 */
var MCP_INPUT_STATES = {}, mcpInputSeq = 0;
/* 同步的纯分发：原生 HTTP 线程把请求塞进来，回包由 __mcpHandle 交回；
   check.mjs 直接调它测工具，不经过网络。 */
function mcpDispatch(req) {
  if (!req || typeof req !== "object") return mcpErr(null, -32700, "Parse error");
  var id = req.id === undefined ? null : req.id;
  if (req.jsonrpc !== "2.0" || typeof req.method !== "string") return mcpErr(id, -32600, "Invalid Request");
  var meta = req._meta || (req.params && req.params._meta) || null;
  var isNew = !!meta;
  if (isNew && meta.protocolVersion !== "2026-07-28") {
    return mcpErr(id, -32022, "Unsupported protocolVersion", { supported: ["2026-07-28"] });
  }
  var m = req.method;
  if (m.indexOf("notifications/") === 0) return null;
  if (isNew && (m === "initialize" || m === "ping")) {
    return mcpErr(id, -32601, m + " removed in 2026-07-28; use server/discover");
  }
  if (m === "server/discover") {
    return mcpOk(id, { protocolVersion: "2026-07-28", supportedVersions: ["2026-07-28"],
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: "gime", version: APP_VER }, resultType: "complete" });
  }
  if (m === "initialize") {
    var known = { "2024-11-05": 1, "2025-03-26": 1, "2025-06-18": 1 };
    var pv = req.params && known[req.params.protocolVersion] ? req.params.protocolVersion : MCP_PROTO;
    return mcpOk(id, {
      protocolVersion: pv,
      capabilities: { tools: {} },
      serverInfo: { name: "gime", version: APP_VER },
      instructions: "GIme 工具：list_banks 看题库；import_bank 导入文本；list_questions 取题与选项；" +
        "write_explanations 写或改解析；write_answers 改客观题正确答案；" +
        "create_plan / update_plan / delete_plan / list_plans 管理方案；notify_phone 发通知。" +
        "所有工具都只在用户明确要求时调用，尤其不要主动改动题库、解析、答案或方案。"
    });
  }
  if (m === "ping") return mcpOk(id, {});
  if (m === "tools/list") {
    var tl = { tools: mcpRegistryTools() };
    if (isNew) { tl.ttlMs = 300000; tl.cacheScope = "public"; tl.resultType = "complete"; }
    return mcpOk(id, tl);
  }
  if (m === "tools/call") {
    var p = req.params || {}, args = p.arguments || {}, data;
    /* MRTR 重试：把用户选的值并回原参数，再走同一条执行路径 */
    if (isNew && p.requestState) {
      var st = MCP_INPUT_STATES[p.requestState];
      if (!st) return mcpErr(id, -32602, "unknown requestState");
      if (p.inputResponses && p.inputResponses.bank) args.bank = p.inputResponses.bank;
      delete MCP_INPUT_STATES[p.requestState];
    }
    var isList = p.name === "list_banks" || p.name === "list_questions" || p.name === "list_plans";
    try {
      if (p.name === "list_banks") {
        data = { banks: BANK.subjects.map(function (s) {
          return { id: s.id, name: s.name, count: stats(s.id).total, file: s.file || "" };
        }) };
      } else if (p.name === "import_bank") data = mcpImport(args);
      else if (p.name === "list_questions") data = mcpListQuestions(args);
      else if (p.name === "write_explanations") data = mcpWriteExplanations(args);
      else if (p.name === "write_answers") data = mcpWriteAnswers(args);
      else if (["create_plan", "update_plan", "delete_plan", "list_plans", "notify_phone"].indexOf(p.name) >= 0) {
        if (typeof mcpPlanTool !== "function") throw new Error("方案工具还没加载");
        data = mcpPlanTool(p.name, args);
      }
      else {
        var unk = mcpToolError("未知工具：" + p.name);
        if (isNew) unk.resultType = "complete";
        return mcpOk(id, unk);
      }
      return mcpOk(id, mcpToolResult(data, isNew, isList));
    } catch (e) {
      if (isNew && e && e.needInput) {
        var state = "in" + (++mcpInputSeq) + Date.now().toString(36);
        MCP_INPUT_STATES[state] = { tool: p.name, args: args };
        return mcpOk(id, { resultType: "input_required",
          inputRequests: [{ id: e.needInput.field, prompt: "题库名不唯一，请选择一个",
            options: e.needInput.options }],
          requestState: state });
      }
      var terr = mcpToolError(e && e.message ? e.message : String(e));
      if (isNew) terr.resultType = "complete";
      return mcpOk(id, terr);
    }
  }
  return mcpErr(id, -32601, "Method not found: " + m);
}
window.__mcpHandle = function (rid, reqJson) {
  var resp = null;
  try { resp = mcpDispatch(JSON.parse(reqJson)); }
  catch (e) { resp = mcpErr(null, -32700, "Parse error"); }
  try {
    if (window.Android && Android.mcpReply) {
      Android.mcpReply(String(rid), resp == null ? "" : JSON.stringify(resp));
    }
  } catch (e) {}
};
/* MCP 服务没有开关，也没有配置面板：应用在跑它就在 127.0.0.1:8765 上监听，
   应用关掉（Activity 销毁）就停。工具分发在 mcpDispatch，下面的大肥鱼也复用它。 */
