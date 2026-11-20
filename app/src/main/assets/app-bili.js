/* 哔哩哔哩收藏（v1.1.7 P5）
 *
 * 这一页只做一件事：把「你账号收藏的视频」点开就能看。
 * 三条线画在这里，别的地方都没有：
 *   ① 页面永远拿不到 cookie，也拿不到上游的播放直链；
 *   ② 播放地址只会是 http://127.0.0.1:8766/bili/<hex>.mp4 这种本机回环串；
 *   ③ 全程只读 —— 不投币、不收藏、不发弹幕。
 *
 * 所有 Android.bili* 方法都是同步返回 JSON 字符串，页面侧 parse 一下即可。
 */

var biliState = null;    /* 最近一次 biliStatus() 的结果 */
var biliFolders = [];     /* 收藏夹清单，点进某个夹时拿标题用 */
/* 透明 1x1 占位图：无封面时作 poster，避免 WebView 在加载前显示默认灰块+播放图标，
   配合 video 黑色底形成“黑屏 + 居中加载 UI”。 */
var BILI_BLACK_PX = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAAAAAAALAAAAAABAAEAAAIBRAA7";
/* 换集进行中标志：屏蔽高频重复点击，避免并发 prepare / 并发流把本地流服务挤爆(503)。 */
var biliSwitching = false;

/* 主动释放当前 <video> 的网络连接，让原生 BiliStream 尽快腾出并发名额。 */
function biliReleaseVideo() {
  var v = document.getElementById("biliVideo");
  try { if (typeof biliSubDetach === "function") biliSubDetach(); } catch (e) {}
  if (!v) return;
  try { v.pause(); } catch (e) {}
  try { v.removeAttribute("src"); } catch (e) {}
  try { v.load(); } catch (e) {}
}
var biliCur = null;       /* { fid, title, pn, list, hasMore, bvid } */
var biliView = "folders"; /* folders | items | player */
var biliRealCount = {};   /* fid → 实际能列出的视频数；校正收藏夹卡片与列表的计数口径
                             （media_count 会把已失效/仅自己可见的条目也算进去，导致 9 vs 8） */

function biliHas() {
  return !!(window.Android && Android.biliStatus);
}

/* 「我的」设置卡里那一行的副标题。列表每次重画都会同步问一次原生，
   所以绑定/退出之后不用手动刷新这一行。 */
function biliSub() {
  if (!biliHas()) return "当前版本不支持";
  try {
    var s = JSON.parse(Android.biliStatus());
    if (s && s.bound) return "已绑定 · " + s.uname;
    return "绑定后可直接看收藏";
  } catch (e) {
    return "绑定后可直接看收藏";
  }
}

function biliOpen() {
  if (!biliHas()) {
    if (window.toast) toast("当前版本还不支持哔哩哔哩收藏");
    return;
  }
  if (typeof goTab === "function") goTab("video");
  else biliTabRender();
}

/* 视频页（原来的错题本 tab）第一次进来时挂上骨架，之后只重画内容。
   骨架里的 #biliTitle/#biliBody/#biliList 和以前整页层用同一套 id，
   所以下面的渲染函数一行都不用改。 */
function biliTabRender() {
  var host = document.getElementById("biliHost");
  if (!host) return;
  if (!biliHas()) {
    host.innerHTML = biliEmpty("当前版本还不支持哔哩哔哩收藏");
    return;
  }
  if (!host.getAttribute("data-mounted")) {
    host.innerHTML =
      '<div class="bili-head"><button type="button" class="bili-back" onclick="biliBack()">‹</button>' +
      '<h2 id="biliTitle">视频收藏</h2><i></i></div>' +
      '<div class="bili-body" id="biliBody"></div>';
    host.setAttribute("data-mounted", "1");
    biliView = "folders";
    biliRefresh();
  } else if (biliView === "player") {
    /* 从别的页面切回来：播放器 DOM 仍在（已暂停），把控制条显出来，点播放继续 */
    try { biliShowCtl(); } catch (e) {}
    try { biliSizeVideo(); } catch (e) {}
  }
}

/* 返回键分三层退：播放 → 列表 → 收藏夹 → 回首页（视频已经是底部 tab，没有「关页」） */
function biliBack() {
  if (biliView === "player") {
    if (biliIsFull()) { biliExitFull(); return; }
    biliStopVideo();
    /* 从「最近观看」直接进播放页时 biliCur.list 为空、无 fid：
       返回应回到收藏夹列表，而不是渲染成「这个收藏夹还是空的」。 */
    if (biliCur && biliCur.list && biliCur.list.length) biliShowItems();
    else biliShowFolders();
    return;
  }
  if (biliView === "items") {
    biliShowFolders();
    return;
  }
  if (typeof goTab === "function") goTab("home");
}

function biliClose() {
  biliStopVideo();
  if (typeof goTab === "function") goTab("home");
}

/* 离开播放页必须把 <video> 的 src 拔掉：不然回环串还占着一条连接，
   原生侧那张票也要等到 TTL 才回收。 */
function biliStopVideo() {
  /* 第 45 轮：如果正全屏（或退场动画还没跑完），不能一边转场一边拆页面 ——
     调用方紧接着就会切到收藏夹视图，页面在旋转中被掏空，卡片又会从黑幕里透出来。
     离开播放页这条路不要动画，直接硬落定。 */
  if (biliIsFull() || biliFsBusy) {
    if (biliFsT) { clearTimeout(biliFsT); biliFsT = 0; }
    biliFsBusy = 0;
    biliExitFullCommit();
  }
  var v = document.getElementById("biliVideo");
  if (!v || typeof v.removeAttribute !== "function") return;
  /* 离开播放页前把最后一刻的进度写回，别让 3 秒节流漏掉 */
  try { biliHistoryTouch(v.currentTime || 0, v.duration || 0, true); } catch (e) {}
  try { v.pause(); } catch (e) {}
  try { v.removeAttribute("src"); } catch (e) {}
  try { v.load(); } catch (e) {}
}

/* 离开视频 tab / 退到后台：只暂停，保留 <video> 与 src，回到视频页可继续播放。
   （旧逻辑 biliStopVideo 会拔掉 src，导致切回来黑屏报错） */
function biliPauseForLeave() {
  try { if (biliIsFull()) biliExitFull(); } catch (e) {}
  var v = document.getElementById("biliVideo");
  if (!v) return;
  try { biliHistoryTouch(v.currentTime || 0, v.duration || 0, true); } catch (e) {}
  try { v.pause(); } catch (e) {}
}

/* App 切到后台 / 锁屏时暂停，避免后台继续拉流 */
document.addEventListener("visibilitychange", function () {
  if (!document.hidden) return;
  var v = document.getElementById("biliVideo");
  if (v && !v.paused) {
    try { biliHistoryTouch(v.currentTime || 0, v.duration || 0, true); } catch (e) {}
    try { v.pause(); } catch (e) {}
  }
});

function biliRefresh() {
  var body = document.getElementById("biliBody");
  if (!body) return;
  var s = null;
  try { s = JSON.parse(Android.biliStatus()); } catch (e) {}
  if (!s || !s.ok) {
    body.innerHTML = biliEmpty("读不到绑定状态：" + (s && s.error ? s.error : "未知错误"));
    return;
  }
  biliState = s;
  if (!s.bound) {
    /* 空态居中构图：图标 → 一句结论 → 一句利益点 → 主按钮。
       原来是把卡片顶在最上面、下面留 2/3 屏空白，读起来像没加载完；
       说明还不写"不投币、不收藏、不发弹幕"这种三连否定——用户只关心能不能看收藏。 */
    body.innerHTML =
      '<div class="bili-blank">' +
        '<i class="bb-ic"><svg class="ico" viewBox="0 0 24 24">' +
          '<path d="M6.5 4.5h11v15l-5.5-3.8L6.5 19.5z"/></svg></i>' +
        "<b>还没有绑定哔哩哔哩</b>" +
        "<em>绑定后直接读你的收藏夹，只读不写入。</em>" +
        '<button type="button" class="bili-btn" onclick="biliLogin()">去绑定</button>' +
      "</div>";
    return;
  }
  body.innerHTML =
    '<div class="bili-srcbar"><button class="on" type="button">哔哩哔哩</button>' +
    '<button class="add" type="button" onclick="if(window.toast)toast(\'更多来源暂未接入\')">＋ 添加来源</button></div>' +
    '<div class="bili-user">' +
      (s.face ? '<img class="bili-ava" referrerpolicy="no-referrer" src="' + esc(s.face) + '" alt="">' : '<span class="bili-ava bili-ava-txt">B</span>') +
      '<div class="bili-who"><b>' + esc(s.uname || ("mid " + s.mid)) + '</b>' +
      '<small><i class="bili-dot"></i>已绑定 · 只读</small></div>' +
      '<button type="button" class="bili-btn bili-ghost" onclick="biliLogout()">退出绑定</button>' +
    '</div><div id="biliJobs" class="bili-jobs"></div>' +
    '<div id="biliList" class="bili-list"></div>';
  biliSubsPageEnsure();
  biliShowFolders();
  try { biliJobsPoll(); } catch (e) {}
}

function biliLogin() {
  Android.biliLogin();
}

function biliLogout() {
  Android.biliLogout();
}

/* 原生侧登录页回来：成功就重画，失败只提示，不动现有状态 */
window.onBiliLogin = function (okFlag, msg) {
  if (window.toast) toast(msg || (okFlag ? "绑定成功" : "已取消"));
  if (okFlag) biliRefresh();
};

window.onBiliLogout = function () {
  if (window.toast) toast("已退出绑定");
  biliRefresh();
};

/* 让「先画出来的那一帧」真的上屏，再去调同步桥。
   同步桥（Android.biliFolders/biliItems/biliPrepare）会占住 JS 线程，
   在它返回之前骨架根本画不出来，用户看到的就是一段空白。 */
function biliPaintThen(fn) {
  var raf = window.requestAnimationFrame || function (f) { return setTimeout(f, 16); };
  raf(function () { raf(function () { try { fn(); } catch (e) {} }); });
}
/* 加载占位：几条会呼吸的骨架，比「加载中…」四个字更像在干活 */
function biliSkeleton(n, hgt) {
  var h = '<div class="bili-sk">';
  for (var i = 0; i < (n || 3); i++) {
    h += '<i class="bili-sk-i"' + (hgt ? ' style="height:' + hgt + 'px"' : "") + '></i>';
  }
  return h + '</div>';
}
/* 标题换文案时淡进来一下，切收藏夹/回列表都有反馈 */
function biliTitleFlash() {
  var t = document.getElementById("biliTitle");
  if (!t) return;
  t.classList.remove("tt-flash");
  void t.offsetWidth;
  t.classList.add("tt-flash");
}

function biliShowFolders() {
  biliView = "folders";
  var t = document.getElementById("biliTitle");
  if (t) t.textContent = "视频收藏";
  biliTitleFlash();
  var box = document.getElementById("biliList");
  if (!box) return;
  box.removeAttribute("data-paint");     /* 新的一份列表，重新播入场 */
  box.innerHTML = biliSkeleton(3, 74);
  biliPaintThen(function () { biliFoldersFetch(box); });
}

function biliFoldersFetch(box) {
  if (biliView !== "folders" || !box || !document.body.contains(box)) return;
  var r = null;
  try { r = JSON.parse(Android.biliFolders()); } catch (e) {}
  if (!r || !r.ok) {
    box.innerHTML = biliEmpty(r && r.error ? r.error : "收藏夹加载失败");
    return;
  }
  biliFolders = r.folders || [];
  if (!biliFolders.length) {
    box.innerHTML = biliEmpty("这个账号没有收藏夹");
    return;
  }
  var first = box.getAttribute("data-paint") !== "1";
  box.setAttribute("data-paint", "1");
  box.classList.toggle("animv", first);
  var h = "";
  for (var i = 0; i < biliFolders.length; i++) {
    var f = biliFolders[i];
    h += '<div class="bili-folder" onclick="biliOpenFolder(' + i + ')"' +
         (first ? ' style="--i:' + Math.min(i, 8) + '"' : "") + '>' +
         '<span class="bili-fbadge">' + esc((f.title || "收").charAt(0)) + '</span>' +
         '<span class="bili-fmain"><b>' + esc(f.title) + '</b><small>' + (biliRealCount[f.mediaId] != null ? biliRealCount[f.mediaId] : f.count) + ' 个视频</small></span>' +
         '<span class="bili-rt">›</span></div>';
  }
  box.innerHTML = h;
}

function biliOpenFolder(i) {
  var f = biliFolders[i];
  if (!f) return;
  biliCur = { fid: f.mediaId, title: f.title, pn: 0, list: [], hasMore: true, bvid: "" };
  biliView = "items";
  var t = document.getElementById("biliTitle");
  if (t) t.textContent = f.title || "收藏夹";
  biliTitleFlash();
  var box = document.getElementById("biliList");
  if (box) {
    box.removeAttribute("data-paint");
    box.innerHTML = biliSkeleton(3, 96);
  }
  var cur = biliCur;
  biliPaintThen(function () {
    /* 这一帧里用户可能已经切走了：只给当前那份列表取数据 */
    if (biliCur === cur && biliView === "items") biliLoadMore();
  });
}

function biliLoadMore() {
  if (!biliCur || !biliCur.hasMore) return;
  var pn = biliCur.pn + 1;
  var r = null;
  try { r = JSON.parse(Android.biliItems(String(biliCur.fid), String(pn))); } catch (e) {}
  if (!r || !r.ok) {
    biliCur.hasMore = false;
    if (!biliCur.list.length) {
      var box = document.getElementById("biliList");
      if (box) box.innerHTML = biliEmpty(r && r.error ? r.error : "收藏夹加载失败");
    }
    return;
  }
  biliCur.pn = pn;
  biliCur.hasMore = !!r.hasMore;
  var list = r.list || [];
  for (var i = 0; i < list.length; i++) biliCur.list.push(list[i]);
  if (!biliCur.hasMore) biliRealCount[biliCur.fid] = biliCur.list.length;
  biliRenderItems();
}

function biliRenderItems() {
  var box = document.getElementById("biliList");
  if (!box || !biliCur) return;
  var first = box.getAttribute("data-paint") !== "1";
  box.setAttribute("data-paint", "1");
  box.classList.toggle("animv", first);
  var onBvid = biliCur.bvid || "";
  var h = "";
  if (!biliCur.list.length) {
    h = biliEmpty("这个收藏夹还是空的");
  } else {
    h = '<div class="bili-sec">共 ' + biliCur.list.length + ' 个视频</div>';
  }
  for (var i = 0; i < biliCur.list.length; i++) {
    var it = biliCur.list[i];
    var act = it.playable
      ? ' onclick="biliPlay(' + i + ')"'
      : ' onclick="biliNote(' + i + ')"';
    var cur = onBvid && it.bvid === onBvid;
    var cov = it.cover
      ? '<img referrerpolicy="no-referrer" src="' + esc(it.cover) + '" alt="" loading="lazy" onerror="this.style.display=\'none\'">'
      : "";
    h += '<div class="bili-v' + (it.playable ? "" : " bili-v-off") + (cur ? " on" : "") +
         '"' + act + (first ? ' style="--i:' + Math.min(i, 8) + '"' : "") + '>' +
         '<div class="bili-vthumb">' + cov +
           '<i class="bili-vdur">' + biliDur(it.duration) + '</i>' +
           (it.playable ? "" : '<i class="bili-vtag">' + esc(it.note || "不可播") + '</i>') +
         '</div>' +
         '<div class="bili-vmain"><b class="bili-vt">' + esc(it.title || it.bvid) + '</b>' +
         '<small>' + (it.upper ? esc(it.upper) : "未知 UP") +
           (cur ? '<i class="bili-now">播放中</i>' : "") + '</small></div>' +
         '</div>';
  }
  if (biliCur.hasMore) {
    h += '<div class="bili-more" onclick="biliLoadMore()">加载更多</div>';
  }
  box.innerHTML = h;
  var t = document.getElementById("biliTitle");
  if (t) t.textContent = biliCur.title || "收藏夹";
}

function biliNote(i) {
  var it = biliCur && biliCur.list[i];
  if (window.toast) toast(it && it.note ? it.note : "这条不能播");
}

/* 从播放页退回列表：列表数据还在内存里，只重画，别再打一次接口。 */
function biliShowItems() {
  biliView = "items";
  biliRenderItems();
}

function biliPlay(i) {
  var it = biliCur && biliCur.list[i];
  if (!it) return;
  /* 点的是正在播放的这条：已在播放就别再重新加载 */
  if (biliView === "player" && biliCur.bvid === it.bvid) {
    var v0 = document.getElementById("biliVideo");
    if (v0 && !v0.error) return;
  }
  if (biliSwitching) return;
  biliSwitching = true;
  var box = document.getElementById("biliList");
  if (box) box.innerHTML = biliSkeleton(1, 64) + '<div class="bili-loadtxt">正在取播放地址…</div>';
  /* 取地址是一次同步桥调用，可能几百毫秒到一两秒；先让骨架那一帧上屏再取，
     否则用户点下去只看到一片空白，像是没反应 */
  var cur = biliCur;
  biliPaintThen(function () { biliPlayDo(cur, i); });
}

function biliPlayDo(cur, i) {
  var it = cur && cur.list[i];
  /* 这两帧里用户可能已经退回收藏夹列表：那份 biliCur 已经被换掉，就别再播 */
  if (!it || biliCur !== cur) { biliSwitching = false; return; }
  var box = document.getElementById("biliList");
  var r = null;
  try { r = JSON.parse(Android.biliPrepare(it.bvid, "1")); } catch (e) {}
  if (!r || !r.ok) {
    biliSwitching = false;
    biliView = "items";
    if (box) box.innerHTML = biliEmpty(r && r.error ? r.error : "取播放地址失败");
    return;
  }
  biliReleaseVideo();
  biliCur.bvid = it.bvid;
  biliCur.idx = i;
  biliView = "player";
  biliHistoryPush(it, r.page, r.pages);
  /* 重新进入同一视频：把上次看到的进度接上（>3 秒才续播，太短从头） */
  var _sv = biliHistoryGetTime(it.bvid, r.page);
  biliPendingSeek = _sv > 3 ? _sv : 0;
  biliPlayerView(r, it);
  /* 播放器渲染完把页面滚到它顶部：否则用户从列表点进来时，
     16:9 的播放器下半截（含控制条和字幕）会停在视口外，要手动上滑才看得见。 */
  setTimeout(function () { biliSwitching = false; biliScrollToPlayer(); }, 350);
}

/* 把播放器顶部对齐到视口顶部（留一点边距），避免下半截掉出屏幕。 */
function biliScrollToPlayer() {
  try {
    var p = document.querySelector(".bili-player");
    if (!p) return;
    var y = p.getBoundingClientRect().top + window.scrollY - 6;
    window.scrollTo(0, Math.max(0, y));
  } catch (e) {}
}

function biliPlayPage(page) {
  var bvid = biliCur && biliCur.bvid;
  if (!bvid) return;
  var pg = parseInt(page, 10) || 1;
  /* 点的是当前正在播的这一集且未出错：直接忽略，不再重新加载 */
  if (biliView === "player" && pg === (biliCur.page || 0)) {
    var v0 = document.getElementById("biliVideo");
    if (v0 && !v0.error) return;
  }
  if (biliSwitching) return;
  biliSwitching = true;
  var r = null;
  try { r = JSON.parse(Android.biliPrepare(bvid, String(page))); } catch (e) {}
  if (!r || !r.ok) {
    biliSwitching = false;
    biliView = "items";
    var box = document.getElementById("biliList");
    if (box) box.innerHTML = biliEmpty(r && r.error ? r.error : "切换分 P 失败");
    return;
  }
  /* 拿到新地址后再释放旧流：避免切失败时把当前播放也弄没了 */
  biliReleaseVideo();
  /* 切分 P：同步播放历史里当前条目的 page（存储被禁用时静默跳过） */
  try {
    var hk = "gime.video.history";
    var hraw = localStorage.getItem(hk);
    var harr = hraw ? JSON.parse(hraw) : [];
    if (harr instanceof Array) {
      for (var hi = 0; hi < harr.length; hi++) {
        if (harr[hi] && harr[hi].bvid === bvid) {
          harr[hi].page = parseInt(r.page, 10) || 1;
          harr[hi].time = 0;   /* 换了集，时间轴从头开始 */
          harr[hi].dur = 0;
          break;
        }
      }
      localStorage.setItem(hk, JSON.stringify(harr));
    }
  } catch (e) {}
  biliPendingSeek = 0;   /* 换分 P：时间轴从头开始 */
  biliPlayerView(r, null);
  setTimeout(function () { biliSwitching = false; }, 350);
}

/* 把底部悬浮导航的实际高度写进 --tabsH，供 .bili-player video 的 max-height 使用。
   不写死像素：导航高度随字号/安全区/是否显示中间大头像变化。 */
function biliSyncTabsH() {
  try {
    var t = document.getElementById("tabs");
    if (!t) return;
    var b = t.getBoundingClientRect();
    var h = Math.round(b.height + (window.innerHeight - b.bottom) + 8);
    if (h > 0) document.documentElement.style.setProperty("--tabsH", h + "px");
  } catch (e) {}
}
/* 网络类型只问一次就记住：切网不频繁，没必要每次渲染播放器都跨桥问。
   返回 "wifi" / "mobile" / "none" / "unknown"。 */
var biliNet = null;
function biliNetKind() {
  if (biliNet == null) {
    try { biliNet = (Android && Android.netKind) ? String(Android.netKind() || "unknown") : "unknown"; }
    catch (e) { biliNet = "unknown"; }
  }
  return biliNet;
}

function biliPlayerView(r, it) {
  var box = document.getElementById("biliList");
  if (!box) return;
  biliSyncTabsH();
  var poster = (it && it.cover) ? it.cover : ((r && r.cover) ? r.cover : BILI_BLACK_PX);
  if (biliCur) {
    biliCur.page = r.page; biliCur.pages = r.pages;
    biliCur.part = r.part || (it && it.title) || "";
    /* 视频名（不是分P名）：缓存目录按视频分组要显示它，队列里存的就是这个 */
    biliCur.vtitle = r.title || (it && it.title) || "";
    try { biliTitleRemember(biliCur.bvid, biliCur.vtitle); } catch (e) {}
  }
  var title = r.title || (it && it.title) || "";
  var page = parseInt(r.page, 10) || 1;
  var pages = parseInt(r.pages, 10) || 1;
  var prevHidden = page > 1 ? "" : " hidden";
  var nextHidden = page < pages ? "" : " hidden";
  /* WiFi 上让 <video> 自己提前囤一段（切集/拖进度条都更快）；蜂窝只取元数据，
     不然一进播放器就悄悄跑掉几十 MB。 */
  var preload = biliNetKind() === "wifi" ? "auto" : "metadata";
  var wave = "M0 20 L0 14 C35 12 55 6 95 10 C130 14 155 5 205 11 C245 16 275 6 320 12 " +
             "C355 17 385 4 435 10 C475 15 505 5 555 11 C595 16 625 4 675 10 " +
             "C715 15 745 6 795 12 C835 17 865 5 915 11 C950 15 975 8 1000 13 L1000 20 Z";
  var h = '<div class="bili-player">' +
          '<video id="biliVideo" playsinline preload="' + preload + '" crossorigin="anonymous"' +
          (poster ? ' poster="' + esc(poster) + '"' : "") +
          ' src="' + esc(r.src) + '"></video>' +
          '<div class="bili-loading" id="biliLoading"><div class="bili-spinner"></div></div>' +
          '<div class="bili-cap" id="biliCap" style="display:none">' +
             '<div class="bcap-cur" id="bcapCur"></div></div>' +
          '<div class="bili-vol" id="biliVol">' +
            '<svg class="ico"><use href="#i-volume"/></svg>' +
            '<div class="bili-vol-track">' +
              '<div class="bili-vol-bar"><i class="bili-vol-fill" id="biliVolFill"></i></div>' +
              '<input type="range" class="bili-vol-range" id="biliVolRange" min="0" max="100" value="50" oninput="biliVolSlide(this.value)" onchange="biliVolDone()">' +
            '</div>' +
            '<span class="bili-vol-pct" id="biliVolPct">50%</span>' +
          '</div>' +
          '<div class="bili-bright" id="biliBright">' +
            '<svg class="ico"><use href="#i-bright"/></svg>' +
            '<div class="bili-bright-track">' +
              '<div class="bili-bright-bar"><i class="bili-bright-fill" id="biliBrightFill"></i></div>' +
            '</div>' +
            '<span class="bili-bright-pct" id="biliBrightPct">50%</span>' +
          '</div>' +
          '<div class="bili-fast" id="biliFast">1.5× 倍速中</div>' +
          '<div class="bili-rate-menu" id="biliRateMenu">' +
            '<button type="button" onclick="biliSetRate(1)">1.0×</button>' +
            '<button type="button" onclick="biliSetRate(1.25)">1.25×</button>' +
            '<button type="button" onclick="biliSetRate(1.5)">1.5×</button>' +
            '<button type="button" onclick="biliSetRate(2)">2.0×</button>' +
          '</div>' +
          '<div class="bili-submenu" id="biliSubMenu" onclick="event.stopPropagation()">' +
            '<div class="bsm-head"><b>字幕</b>' +
              '<button type="button" class="bsm-switch" id="biliSubSwitch" role="switch" aria-checked="false" onclick="biliSubMasterTap(event)"><i></i></button>' +
            '</div>' +
            '<div class="bsm-seg">' +
              '<button type="button" class="bsm-src" id="biliSrcLocal" onclick="biliSubModePick(\'local\')">本地字幕</button>' +
              '<button type="button" class="bsm-src" id="biliSrcOfficial" onclick="biliSubModePick(\'official\')">上游字幕</button>' +
            '</div>' +
            '<div class="bsm-tip" id="biliSubTip"></div>' +
          '</div>' +
          '<div class="bili-topbar"><b>' + esc(title) + '</b>' +
          '<span class="ep" onclick="event.stopPropagation();biliEpDrawerOpen()">P' + page + ' / ' + pages + ' · 选集</span></div>' +
          '<div class="bili-epdrawer" id="biliEpDrawer"><div class="epd-head"><b>选集</b>' +
            '<span class="epd-all" id="biliEpdAll" onclick="event.stopPropagation();biliQueueAll(event)">缓存全集</span>' +
            '<button type="button" onclick="event.stopPropagation();biliEpDrawerClose()">×</button></div>' +
            '<div class="epd-list" id="biliEpDrawerList"></div></div>' +
          '<div class="bili-fshint" id="biliFsHint"></div>' +
          '<div class="bili-pause-layer"><svg class="ico"><use href="#i-pause"/></svg>' +
          '<span class="ph-tip">已暂停</span></div>' +
          '<div class="bili-ctl progress-v2" id="biliCtl" data-page="' + page + '" data-pages="' + pages + '">' +
            '<div class="bili-progress">' +
              '<span class="bili-time" id="biliTime">0:00 / 0:00</span>' +
              '<div class="bili-scrub" id="biliScrub"><b id="biliScrubT">0:00</b></div>' +
              '<svg class="bili-wave" viewBox="0 0 1000 20" preserveAspectRatio="none" aria-hidden="true"><path d="' + wave + '"/></svg>' +
              '<div class="bili-track">' +
                '<i class="bili-track-fill" id="biliTrackFill"></i>' +
                '<i class="bili-dot" style="left:6%"></i>' +
                '<i class="bili-dot" style="left:14%"></i>' +
                '<i class="bili-dot" style="left:36%"></i>' +
                '<i class="bili-dot" style="left:62%"></i>' +
                '<i class="bili-dot" style="left:88%"></i>' +
                '<i class="bili-dot" style="left:96%"></i>' +
                '<i class="bili-thumb" id="biliThumb"></i>' +
                '<input type="range" class="bili-seek" id="biliSeek" min="0" max="1000" value="0" oninput="biliSeekPreview(this.value)" onchange="biliSeekCommit()">' +
              '</div>' +
            '</div>' +
            '<div class="bili-cb-row">' +
              '<div class="bili-cb-group">' +
                '<button type="button" class="bili-cb bili-cb-ep-prev" onclick="biliEp(-1)" title="上一集"' + prevHidden + '><svg class="ico"><use href="#i-skip-prev"/></svg></button>' +
                '<button type="button" class="bili-cb" id="biliPlayBtn" onclick="biliPlayPause()"><svg class="ico"><use href="#i-play"/></svg></button>' +
                '<button type="button" class="bili-cb bili-cb-ep-next" onclick="biliEp(1)" title="下一集"' + nextHidden + '><svg class="ico"><use href="#i-skip-next"/></svg></button>' +
              '</div>' +
              '<span class="bili-cb-note" id="biliCtlNote">高清 · P' + page + ' / ' + pages + '</span>' +
              '<div class="bili-cb-group bili-cb-extra">' +
                '<button type="button" class="bili-cb bili-cc-btn" id="biliCcBtn" onclick="biliSubMenuTap(event)" title="字幕设置"><span>CC</span></button>' +
                '<button type="button" class="bili-cb bili-rate-btn" id="biliRateBtn" onclick="biliRateTap(event)" title="播放倍速"><span id="biliRateLbl">1.0×</span></button>' +
                '<button type="button" class="bili-cb" id="biliMuteBtn" onclick="biliVolOpen()"><svg class="ico"><use href="#i-volume"/></svg></button>' +
              '</div>' +
              '<button type="button" class="bili-cb bili-cb-more" id="biliMoreBtn" onclick="biliCtlMore(event)" title="更多" aria-label="更多"><b class="cb-dots"><i></i><i></i><i></i></b></button>' +
              '<div class="bili-cb-group">' +
                '<button type="button" class="bili-cb bili-cb-fs" id="biliFsBtn" onclick="biliToggleFull()" data-fs="expand" aria-label="全屏"><svg class="ico"><use href="#i-expand"/></svg></button>' +
              '</div>' +
            '</div>' +
          '</div>' +
          '</div>';
  h += '<div class="card pad">' +
       '<div class="bili-meta"><b>' + esc(title) + '</b>' +
       '<div class="bili-chips">' +
         '<span class="bili-chip">P' + page + '/' + pages + '</span>' +
         (r.qualityName ? '<span class="bili-chip">' + esc(r.qualityName) + '</span>' : "") +
         (r.duration ? '<span class="bili-chip">' + biliDur(r.duration) + '</span>' : "") +
       '</div>' +
       (r.part ? '<div class="bili-part">' + esc(r.part) + '</div>' : "") +
       '</div>';
  if (pages > 1) {
    /* 长条选集：一行一集，显示题目；右侧字幕生成进度；底部一条观看进度。整块可展开/收起 */
    var epOpen = biliEpOpen();
    h += '<button type="button" class="bili-eptog" onclick="biliEpToggle()">' +
         '<span>选集</span><b>P' + page + ' / 共 ' + pages + ' 集</b>' +
         '<span class="ep-all" id="biliEpAll" onclick="event.stopPropagation();biliQueueAll(event)">缓存全集</span>' +
         '<i class="chev' + (epOpen ? " up" : "") + '"></i></button>';
    var parts = (r && r.parts) || [];
    if (biliCur) biliCur.parts = parts;
    var seen = biliSeenGet((biliCur && biliCur.bvid) || "");
    h += '<div class="bili-pages' + (epOpen ? "" : " off") + '">';
    for (var p = 1; p <= pages; p++) {
      var pi = parts[p - 1] || {};
      var ptitle = pi.t || ("第 " + p + " 集");
      var pdur = parseInt(pi.d, 10) || 0;
      var spct = pdur > 0 ? Math.min(100, Math.round((seen[p] || 0) * 100 / pdur)) : 0;
      h += '<div class="bili-ep' + (p === page ? " on" : "") +
           '" data-p="' + p + '" data-dur="' + pdur + '" onclick="biliPlayPage(' + p + ')">' +
           '<span class="ep-n">P' + p + '</span>' +
           '<span class="ep-t">' + esc(ptitle) + '</span>' +
           /* 第 42 轮：看好进度不再只靠那条贴在字下的黑线 —— 百分比写在行内，
              进度条压到行底边当细线。注意：.ep-pct 是字幕状态列（biliSubsMarks 会整列重写），
              观看百分比必须另起一列，否则每 2 秒的轮询会把它清掉。
              第 44 轮：正在播那一行前面加"看到"当量词 —— "2%" 孤零零挂在行尾，
              旁边又紧跟字幕列的 "100%"，两个数字并排像乱码，分不清哪个是哪个。 */
           '<span class="ep-seen">' +
             (spct > 0 ? (p === page ? "看到 " + spct + "%" : spct + "%") : "") +
           '</span>' +
           '<span class="ep-pct"></span>' +
           '<span class="ep-act" onclick="event.stopPropagation();biliQueueToggle(' + p + ')"></span>' +
           '<i class="ep-bar"><b style="width:' + spct + '%"></b></i>' +
           '</div>';
    }
    h += "</div>";
  }
  h += "</div>";
  box.innerHTML = h;
  var v = document.getElementById("biliVideo");
  if (v) {
    try { v.load(); } catch (e) {}
    /* 封面域名会拒掉 WebView 自带的 appassets Referer；先用 no-referrer 预热一次，
       随后 <video poster> 的请求命中缓存，播放器上的封面才不会是一块黑 */
    if (poster && /^https?:/i.test(poster)) {
      try { var pi0 = new Image(); pi0.referrerPolicy = "no-referrer"; pi0.src = poster; } catch (e2) {}
    }
    biliShowLoading();
    biliSubAttach(v);
    /* 一个 <video> 只能绑一次 MediaElementSource，绑过之后它的声音就一直走那条音频图。
       所以「恢复播放」必须把绑定的 ctx 一起叫醒：实时采集拆掉后（有现成字幕轨时）
       biliAudioCtx 是 null，只认它会出现「画面在放、声音是死的」。 */
    v.addEventListener("play", function () {
      var c = biliAudioCtx || (biliSubBoundEl === v ? biliSubBoundCtx : null);
      try { if (c) c.resume(); } catch (e) {}
      try { if (typeof biliCueKick === "function") biliCueKick(); } catch (e) {}
    });
    v.addEventListener("pause", function () { try { if (biliAudioCtx) biliAudioCtx.suspend(); } catch (e) {} });
    v.addEventListener("timeupdate", biliSyncTime);
    v.addEventListener("loadstart", biliShowLoading);
    v.addEventListener("waiting", biliShowLoading);
    v.addEventListener("stalled", biliShowLoading);
    v.addEventListener("seeking", biliShowLoading);
    /* 兜底自愈：本地代理的上游偶发半死时，<video> 会一直停在加载态；
       超过 8 秒没进展就原地重开一次（等价于「微微拖一下进度条」），
       只做一次，避免真的没网时反复重试打转。 */
    var stuckT = 0, stuckDone = false;
    function stuckWatch() {
      if (stuckT) { clearTimeout(stuckT); stuckT = 0; }
      stuckT = setTimeout(function () {
        stuckT = 0;
        if (stuckDone || v.paused || v.ended || !v.getAttribute("src")) return;
        if (v.readyState >= 3) return;
        stuckDone = true;
        try { v.currentTime = v.currentTime + 0.05; } catch (e) {}
        try { var p2 = v.play(); if (p2 && p2.catch) p2.catch(function () {}); } catch (e) {}
      }, 8000);
    }
    v.addEventListener("waiting", stuckWatch);
    v.addEventListener("stalled", stuckWatch);
    v.addEventListener("canplay", function () { stuckDone = false; if (stuckT) { clearTimeout(stuckT); stuckT = 0; } });
    v.addEventListener("playing", function () { stuckDone = false; if (stuckT) { clearTimeout(stuckT); stuckT = 0; } });
    v.addEventListener("canplay", biliHideLoading);
    v.addEventListener("playing", biliHideLoading);
    v.addEventListener("loadeddata", biliHideLoading);
    v.addEventListener("seeked", biliHideLoading);
    v.addEventListener("error", function () {
      biliHideLoading();
      /* 本地流偶发 503（切集时旧连接尚未完全释放）：短暂等待后自动重连一次 */
      var tries = parseInt(v.dataset.retry || "0", 10);
      if (tries < 2 && v.getAttribute("src")) {
        v.dataset.retry = String(tries + 1);
        setTimeout(function () {
          var vv = document.getElementById("biliVideo");
          if (vv === v && vv.getAttribute("src")) {
            biliShowLoading();
            try { vv.load(); } catch (e) {}
            try { var p = vv.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
          }
        }, 700);
      }
    });
    v.addEventListener("loadedmetadata", function () {
      /* 从「最近观看」进来：元数据到齐后把时间轴拨回上次看到的位置 */
      if (biliPendingSeek > 0) {
        var seek = biliPendingSeek;
        biliPendingSeek = 0;
        try { v.currentTime = seek; } catch (e) {}
      }
      try { v.playbackRate = biliUserRate; } catch (e) {}
      biliSyncTime(); biliSizeVideo(); biliAutoPlay();
    });
    v.addEventListener("durationchange", biliSyncTime);
    v.addEventListener("play", function () {
      biliShowCtl();
      biliSetPlayIcon(true);       /* 自动播放后按钮要跟着变成暂停图标 */
      var tab = document.getElementById("tab-video");
      if (tab) tab.classList.remove("is-paused");
    });
    v.addEventListener("pause", function () {
      biliShowCtl();
      biliSetPlayIcon(false);
      var tab = document.getElementById("tab-video");
      if (tab) tab.classList.add("is-paused");
    });
    v.addEventListener("click", biliOnVideoTap);
    /* 本集播放结束：自动接下一集（多 P → 下一 P；单 P → 收藏夹里下一条能播的）；
       已经是最后一集/最后一条时 biliEp 会给出提示，不报错。 */
    v.addEventListener("ended", function () {
      try { biliEp(1); } catch (e) {}
    });
    try { v.playbackRate = biliUserRate; } catch (e) {}
    var _rlbl = document.getElementById("biliRateLbl"); if (_rlbl) _rlbl.textContent = biliRateText(biliUserRate);
    try { biliBrightSync(); } catch (e) {}
    biliBindGestures(v);
  }
  biliSyncTime();
  biliShowCtl();
  biliSizeVideo();
  biliApplyEp(page, pages);
  /* 分P按钮上挂生成进度；有排队/在跑的任务就持续刷新 */
  try { biliSubsMarks(); } catch (e) {}
  try { if (biliQueuePending(biliQueue())) biliSubsPoll(); } catch (e) {}
  /* 点视频 / 切分 P 是用户手势：默认直接播放，不再停在暂停态等用户再点一次 */
  biliAutoPlay();
  /* 播放器上屏一秒半之后再预取下一集的地址：立刻发会和当前这集起流抢带宽，
     而且用户未必会看下一集。只预取「地址」（不预下载媒体），仍由 <video> 现取。 */
  setTimeout(function () {
    try {
      if (!biliCur || !biliCur.bvid) return;
      if (!(page < pages)) return;
      if (biliNetKind() !== "wifi") return;
      if (Android && Android.biliPrefetch) Android.biliPrefetch(biliCur.bvid, String(page + 1));
    } catch (e) {}
  }, 1500);
}

/* ---- 模板落地（m02323）：集数同步 / 全屏单击双击 / 播放历史 ---- */
var biliChromeT = null;
var biliPendingSeek = 0;      /* 从「最近观看」恢复的时间轴（秒） */
var biliLastSaveAt = 0;       /* 播放进度写盘节流：3 秒一次 */

function biliApplyEp(page, pages) {
  var pg = parseInt(page, 10) || 1;
  var tp = parseInt(pages, 10) || 1;
  var note = document.getElementById("biliCtlNote");
  if (note) note.textContent = "高清 · P" + pg + " / " + tp;
  var ep = document.querySelector(".bili-topbar .ep");
  if (ep) ep.textContent = "P" + pg + " / " + tp + " · 选集";
  var chip = document.querySelector(".bili-chips .bili-chip");
  if (chip) chip.textContent = "P" + pg + "/" + tp;
  var ctl = document.getElementById("biliCtl");
  if (ctl) {
    ctl.setAttribute("data-page", String(pg));
    ctl.setAttribute("data-pages", String(tp));
  }
  var prev = document.querySelector(".bili-cb-ep-prev");
  if (prev) prev.hidden = !(pg > 1);
  var next = document.querySelector(".bili-cb-ep-next");
  if (next) next.hidden = !(pg < tp);
  var pgs = document.querySelectorAll(".bili-pages .bili-ep");
  for (var i = 0; i < pgs.length; i++) {
    if (i + 1 === pg) pgs[i].classList.add("on");
    else pgs[i].classList.remove("on");
  }
  /* 换集了：上一集的轨立刻作废；CC 还开着就对这一集重新走一遍（有轨播轨，没轨起任务） */
  biliSubsOnPage();
}

function biliFullToggleChrome(e) {
  if (Date.now() - biliGestureMovedAt < 400) return;
  if (!biliIsFull()) return;
  if (e && e.target && e.target.closest && e.target.closest(".bili-ctl")) return;
  var tab = document.getElementById("tab-video");
  if (!tab) return;
  if (biliChromeT) { clearTimeout(biliChromeT); biliChromeT = null; }
  if (e && e.detail === 2) {
    biliPlayPause();
  } else {
    biliChromeT = setTimeout(function () {
      biliChromeT = null;
      tab.classList.toggle("chrome-hidden");
    }, 240);
  }
}

function biliHistoryPush(it, page, pages) {
  try {
    if (!it || !it.bvid) return;
    var key = "gime.video.history";
    var arr = [];
    try { arr = JSON.parse(localStorage.getItem(key) || "[]") || []; } catch (e) { arr = []; }
    if (!(arr instanceof Array)) arr = [];
    var entry = {
      title: it.title || "",
      cover: it.cover || "",
      bvid: it.bvid,
      fid: (biliCur && biliCur.fid) || "",
      folder: (biliCur && biliCur.title) || "",
      page: parseInt(page, 10) || 1,
      pages: parseInt(pages, 10) || 1,
      time: 0,
      dur: 0,
      at: Date.now()
    };
    /* 同一个视频再点进来：集数没变就沿用上次看到的时间轴 */
    for (var j = 0; j < arr.length; j++) {
      if (arr[j] && arr[j].bvid === it.bvid) {
        if ((parseInt(arr[j].page, 10) || 1) === entry.page) {
          entry.time = parseFloat(arr[j].time) || 0;
          entry.dur = parseFloat(arr[j].dur) || 0;
        }
        break;
      }
    }
    var out = [];
    for (var i = 0; i < arr.length; i++) {
      if (arr[i] && arr[i].bvid !== it.bvid) out.push(arr[i]);
    }
    out.unshift(entry);
    out.sort(function (a, b) { return (b.at || 0) - (a.at || 0); });
    if (out.length > 8) out = out.slice(0, 8);
    localStorage.setItem(key, JSON.stringify(out));
  } catch (e) {}
}

/* 每集看到哪儿：localStorage 里按 bvid 存 {page: 秒}，用于选集长条底部的观看进度 */
var biliSeenAt = 0;
function biliSeenKey(bvid) { return "gime.video.seen." + bvid; }
/* 选集整块的展开/收起：默认展开，状态记在本机 */
function biliEpOpen() {
  try { return localStorage.getItem("gime.bili.epopen") !== "0"; } catch (e) { return true; }
}
function biliEpToggle() {
  var box = document.querySelector("#biliList .bili-pages");
  if (!box) return;
  var open = box.classList.contains("off");
  box.classList.toggle("off", !open);
  try { localStorage.setItem("gime.bili.epopen", open ? "1" : "0"); } catch (e) {}
  var ch = document.querySelector("#biliList .bili-eptog .chev");
  if (ch) ch.classList.toggle("up", open);
}
function biliSeenGet(bvid) {
  if (!bvid) return {};
  try {
    var o = JSON.parse(localStorage.getItem(biliSeenKey(bvid)) || "{}");
    return (o && typeof o === "object") ? o : {};
  } catch (e) { return {}; }
}
function biliSeenSave(bvid, page, sec) {
  if (!bvid || !page || !(sec > 0)) return;
  try {
    var o = biliSeenGet(bvid);
    o[page] = Math.round(sec * 10) / 10;
    localStorage.setItem(biliSeenKey(bvid), JSON.stringify(o));
  } catch (e) {}
}

/* m02804 ③ 播放进度写回历史：timeupdate 很密，按 3 秒节流；force 用于离开播放页 */
function biliHistoryTouch(t, d, force) {
  var bvid = biliCur && biliCur.bvid;
  if (!bvid) return;
  var now = Date.now();
  if (!force && now - biliLastSaveAt < 3000) return;
  biliLastSaveAt = now;
  try {
    var key = "gime.video.history";
    var arr = JSON.parse(localStorage.getItem(key) || "[]") || [];
    if (!(arr instanceof Array)) return;
    for (var i = 0; i < arr.length; i++) {
      if (arr[i] && arr[i].bvid === bvid) {
        arr[i].time = Math.floor(t) || 0;
        arr[i].dur = Math.floor(d) || 0;
        break;
      }
    }
    localStorage.setItem(key, JSON.stringify(arr));
  } catch (e) {}
}

/* 读取历史里某视频（同分 P）上次看到的时间，用于重进时续播 */
function biliHistoryGetTime(bvid, page) {
  try {
    var arr = JSON.parse(localStorage.getItem("gime.video.history") || "[]") || [];
    if (!(arr instanceof Array)) return 0;
    var pg = parseInt(page, 10) || 1;
    for (var i = 0; i < arr.length; i++) {
      var e = arr[i];
      if (e && e.bvid === bvid && (parseInt(e.page, 10) || 1) === pg) return parseFloat(e.time) || 0;
    }
  } catch (e) {}
  return 0;
}

/* m02804 ④ 点视频 / 切分 P 默认播放：在用户手势的同步栈里调 play()，被策略拦下就静默 */
function biliAutoPlay() {
  var v = document.getElementById("biliVideo");
  if (!v) return;
  try {
    var p = v.play();
    if (p && typeof p["catch"] === "function") {
      p["catch"](function () {
        /* 自动播放被策略拦下（如自动接下一集时无用户手势）：先静音重试，播起来再恢复声音 */
        try {
          var wasMuted = v.muted;
          v.muted = true;
          var p2 = v.play();
          if (p2 && typeof p2["then"] === "function") {
            p2["then"](function () { try { if (!wasMuted) v.muted = false; } catch (e) {} })
              ["catch"](function () {});
          }
        } catch (e) {}
      });
    }
  } catch (e) {}
}

/* 反查某个视频所在收藏夹的标题：遍历收藏夹第一页里找 bvid（历史老记录没有收藏夹字段时用） */
function biliResolveFolderName(bvid) {
  try {
    if (!biliFolders || !biliFolders.length) {
      var fr = null;
      try { fr = JSON.parse(Android.biliFolders()); } catch (e) {}
      if (fr && fr.folders) biliFolders = fr.folders;
    }
    for (var i = 0; i < biliFolders.length; i++) {
      var f = biliFolders[i];
      var ir = null;
      try { ir = JSON.parse(Android.biliItems(String(f.mediaId), "1")); } catch (e) { continue; }
      var lst = (ir && ir.list) || [];
      for (var j = 0; j < lst.length; j++) {
        if (lst[j] && lst[j].bvid === bvid) return f.title || "";
      }
    }
  } catch (e) {}
  return "";
}

/* m02804 ③ 首页「最近观看」：直接进播放页并恢复时间轴，而不是只跳到视频 tab */
function biliResume(bvid, page) {
  if (!biliHas()) { if (window.toast) toast("当前版本还不支持视频收藏"); return; }
  if (!bvid) return;
  var pg = parseInt(page, 10) || 1;
  var rec = null;
  try {
    var arr = JSON.parse(localStorage.getItem("gime.video.history") || "[]") || [];
    if (arr instanceof Array) {
      for (var i = 0; i < arr.length; i++) {
        if (arr[i] && arr[i].bvid === bvid) { rec = arr[i]; break; }
      }
    }
  } catch (e) {}
  if (typeof goTab === "function") goTab("video");
  /* 同步挂好骨架（data-mounted=1），这样 swapPage 动画结束后的 renderAll 不会重画掉播放页 */
  if (typeof biliTabRender === "function") biliTabRender();
  var box = document.getElementById("biliList");
  if (!box) {
    var body = document.getElementById("biliBody");
    if (!body) {
      var host = document.getElementById("biliHost");
      if (!host) return;
      host.innerHTML = '<div class="bili-head"><button type="button" class="bili-back" onclick="biliBack()">‹</button>' +
                       '<h2 id="biliTitle">视频收藏</h2><i></i></div>' +
                       '<div class="bili-body" id="biliBody"></div>';
      host.setAttribute("data-mounted", "1");
      body = document.getElementById("biliBody");
    }
    if (!body) return;
    body.innerHTML = '<div id="biliList" class="bili-list"></div>';
  }
  var r = null;
  try { r = JSON.parse(Android.biliPrepare(bvid, String(pg))); } catch (e) {}
  if (!r || !r.ok) {
    if (window.toast) toast("打开视频失败，请回收藏夹重试");
    return;
  }
  biliView = "player";
  /* 顶部标题显示「视频所在的收藏夹名」，而不是视频标题：
     优先用历史里记录的收藏夹名，老记录没有就现场反查一次 */
  var bid = (rec && rec.fid) || "";
  var folderTitle = (rec && rec.folder) || "";
  if (!folderTitle) { try { folderTitle = biliResolveFolderName(bvid) || ""; } catch (e) {} }
  biliCur = { fid: bid, title: folderTitle || "视频收藏", pn: 0, list: [],
              hasMore: false, bvid: bvid, idx: -1 };
  biliPendingSeek = (rec && parseFloat(rec.time) > 0) ? parseFloat(rec.time) : 0;
  biliPlayerView(r, { title: (rec && rec.title) || r.title || "", cover: (rec && rec.cover) || "", bvid: bvid });
  var t = document.getElementById("biliTitle");
  if (t) t.textContent = biliCur.title || "视频收藏";
}

/* —— 自制控制条：压在视频底部（原来原生 controls 那一排的位置） —— */
var biliHideT = null;

function biliShowCtl() {
  var c = document.getElementById("biliCtl");
  if (!c) return;
  biliVolSync();
  c.classList.remove("hide");
  if (biliHideT) { clearTimeout(biliHideT); biliHideT = null; }
  var v = document.getElementById("biliVideo");
  /* 全屏时控制条单独占底部一条，不自动隐藏，免得黑边空着 */
  if (v && !v.paused && !biliIsFull()) {
    biliHideT = setTimeout(function () {
      var v2 = document.getElementById("biliVideo");
      var c2 = document.getElementById("biliCtl");
      if (c2 && v2 && !v2.paused) { c2.classList.add("hide"); c2.classList.remove("more"); }
    }, 3200);
  }
}

/* 第 42 轮：小窗里倍速/CC/静音收进「⋯」——点开在控制条上方浮一小排，再点收起。
   拖到时间轴/切集/全屏都会自己收（入口都是先 biliShowCtl）。 */
function biliCtlMore(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  var c = document.getElementById("biliCtl");
  if (!c) return;
  c.classList.toggle("more");
  biliShowCtl();
}
window.biliCtlMore = biliCtlMore;

function biliToggleCtl() {
  if (Date.now() - biliGestureMovedAt < 400) return;
  var c = document.getElementById("biliCtl");
  if (!c) return;
  if (biliIsFull()) { biliShowCtl(); return; }
  if (c.classList.contains("hide")) { biliShowCtl(); return; }
  if (biliHideT) { clearTimeout(biliHideT); biliHideT = null; }
  c.classList.add("hide");
}

function biliSyncTime() {
  var v = document.getElementById("biliVideo");
  if (!v) return;
  var d = v.duration || 0;
  var t = v.currentTime || 0;
  /* 从「最近观看」恢复时间轴：loadedmetadata 之外的兜底（有些 WebView 不派发该事件），
     等 duration 一就绪就把 currentTime 拨回上次看到的位置，只做一次。 */
  if (biliPendingSeek > 0 && d > 0) {
    var seek = biliPendingSeek;
    biliPendingSeek = 0;             /* 先清，避免 currentTime 变更同步派发 timeupdate 时重入 */
    try { v.currentTime = seek; t = seek; } catch (e) {}
  }
  var tm = document.getElementById("biliTime");
  if (tm) tm.textContent = biliDur(Math.floor(t)) + " / " + biliDur(Math.floor(d));
  if (!biliScrubbing) {
    var sk = document.getElementById("biliSeek");
    if (sk && d > 0 && !sk.dataset.drag) sk.value = String(Math.round(t / d * 1000));
    var pct = d > 0 ? (t / d * 100) : 0;
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    var fill = document.getElementById("biliTrackFill");
    if (fill && fill.style) fill.style.width = pct + "%";
    var thumb = document.getElementById("biliThumb");
    if (thumb && thumb.style) thumb.style.left = pct + "%";
  }
  /* 播放进度写回历史，供首页「最近观看」恢复时间轴（3 秒节流，避免 timeupdate 刷盘） */
  if (d > 0 && t > 0) biliHistoryTouch(t, d);
  /* 选集长条：当前集底部的观看进度实时走，并按 3 秒节流记到本机 */
  try {
    var bv0 = biliCur && biliCur.bvid;
    if (bv0 && biliCur.page) {
      var row = document.querySelector('#biliList .bili-ep[data-p="' + biliCur.page + '"]');
      if (row) {
        var rd = parseFloat(row.getAttribute("data-dur")) || d || 0;
        var bar = row.querySelector(".ep-bar b");
        var pct2 = rd > 0 ? Math.min(100, Math.round(t * 100 / rd)) : 0;
        if (bar && rd > 0) bar.style.width = pct2 + "%";
        /* 百分比文字跟长条同步走，否则条在动、数字一直是渲染时的旧值 */
        var ptx = row.querySelector(".ep-seen");
        if (ptx) ptx.textContent = pct2 > 0 ? "看到 " + pct2 + "%" : "";
      }
      var now = Date.now();
      if (now - biliSeenAt > 3000) { biliSeenAt = now; biliSeenSave(bv0, biliCur.page, t); }
    }
  } catch (e) {}
  biliSetPlayIcon(!v.paused);
  var mb = document.getElementById("biliMuteBtn");
  if (mb) mb.classList.toggle("muted", !!v.muted);
}

/* ===== 进度拖动预览（req3）：拖动时只更新“欲滑位置”视觉，不跳转；松手才 seek ===== */
var biliScrubbing = false;
var biliScrubPct = 0;

function biliScrubShowPct(pct) {
  if (pct < 0) pct = 0;
  if (pct > 1) pct = 1;
  biliScrubPct = pct;
  biliScrubbing = true;
  var fill = document.getElementById("biliTrackFill");
  if (fill && fill.style) fill.style.width = (pct * 100) + "%";
  var thumb = document.getElementById("biliThumb");
  if (thumb && thumb.style) thumb.style.left = (pct * 100) + "%";
  var v = document.getElementById("biliVideo");
  var dur = (v && v.duration) ? v.duration : 0;
  var st = document.getElementById("biliScrubT");
  if (st) st.textContent = biliDur(Math.floor(dur * pct));
  var sc = document.getElementById("biliScrub");
  if (sc) { sc.style.left = (pct * 100) + "%"; sc.classList.add("show"); }
}

/* 全屏收起上下栏时的拖动进度反馈：挂在播放器层（和控制条无关），
   所以只飘一个时间胶囊，不会把上下栏带出来。 */
function biliSeekHint() {
  if (!biliIsFull()) return;
  var v = document.getElementById("biliVideo");
  var d = (v && v.duration) ? v.duration : 0;
  if (!d) return;
  biliHint(biliDur(Math.floor(d * biliScrubPct)) + " / " + biliDur(Math.floor(d)), 1200);
}

/* 进度条（小屏/全屏控制条）拖动预览：只改视觉 */
function biliSeekPreview(val) {
  var v = document.getElementById("biliVideo");
  var sk = document.getElementById("biliSeek");
  if (!v || !v.duration) return;
  if (sk) sk.dataset.drag = "1";
  biliScrubShowPct((parseInt(val, 10) || 0) / 1000);
}

/* 松手提交 seek */
function biliSeekCommit() {
  var v = document.getElementById("biliVideo");
  if (v && v.duration) { try { v.currentTime = v.duration * biliScrubPct; } catch (e) {} }
  biliScrubbing = false;
  var sk = document.getElementById("biliSeek");
  if (sk) { delete sk.dataset.drag; }
  var sc = document.getElementById("biliScrub");
  if (sc) sc.classList.remove("show");
  biliSyncTime();
}

/* ===== 音量（req2）：读写设备媒体音量（STREAM_MUSIC），与系统音量一致 ===== */
function biliVolState() {
  try {
    if (window.Android && Android.biliVolume) {
      var r = JSON.parse(Android.biliVolume());
      if (r && r.ok && r.max > 0) return { max: r.max, cur: r.cur, pct: r.cur / r.max };
    }
  } catch (e) {}
  var v = document.getElementById("biliVideo");
  var vol = (v && !v.muted) ? (v.volume || 0) : 0;
  return { max: 1, cur: vol, pct: vol };
}

function biliVolRender(pct) {
  if (pct < 0) pct = 0;
  if (pct > 1) pct = 1;
  var fill = document.getElementById("biliVolFill");
  if (fill && fill.style) fill.style.height = (pct * 100) + "%";
  var pc = document.getElementById("biliVolPct");
  if (pc) pc.textContent = Math.round(pct * 100) + "%";
  var rg = document.getElementById("biliVolRange");
  if (rg && document.activeElement !== rg) rg.value = String(Math.round(pct * 100));
  var mb = document.getElementById("biliMuteBtn");
  if (mb) {
    mb.classList.toggle("muted", pct <= 0);
    var u = mb.querySelector("use");
    if (u) u.setAttribute("href", pct <= 0 ? "#i-mute" : "#i-volume");
  }
}

function biliVolApply(pct, showHud) {
  if (pct < 0) pct = 0;
  if (pct > 1) pct = 1;
  var used = false;
  try {
    if (window.Android && Android.biliSetVolume) {
      var st = biliVolState();
      Android.biliSetVolume(Math.round(pct * st.max));
      used = true;
    }
  } catch (e) {}
  if (!used) {
    var v = document.getElementById("biliVideo");
    if (v) { v.muted = false; v.volume = pct; }
  }
  biliVolRender(pct);
  if (showHud) biliVolShowHud();
}

var biliVolT = null;
function biliVolShowHud() {
  var el = document.getElementById("biliVol");
  if (!el) return;
  el.classList.add("show");
  if (biliVolT) { clearTimeout(biliVolT); biliVolT = null; }
  biliVolT = setTimeout(function () {
    biliVolT = null;
    var e2 = document.getElementById("biliVol");
    if (e2 && !e2.classList.contains("interactive")) e2.classList.remove("show");
  }, 1400);
}

/* 控制条音量按钮：打开可拖动音量条（不再只是静音） */
function biliVolOpen() {
  var el = document.getElementById("biliVol");
  if (!el) return;
  var st = biliVolState();
  biliVolRender(st.pct);
  el.classList.add("show");
  el.classList.add("interactive");
  if (biliVolT) { clearTimeout(biliVolT); biliVolT = null; }
  biliVolT = setTimeout(function () {
    biliVolT = null;
    var e2 = document.getElementById("biliVol");
    if (e2) { e2.classList.remove("interactive"); e2.classList.remove("show"); }
  }, 3000);
}

function biliVolSlide(val) {
  if (biliVolT) { clearTimeout(biliVolT); biliVolT = null; }
  biliVolApply((parseInt(val, 10) || 0) / 100, false);
  var el = document.getElementById("biliVol");
  if (el) el.classList.add("show");
}

function biliVolDone() {
  var el = document.getElementById("biliVol");
  if (!el) return;
  el.classList.remove("interactive");
  biliVolShowHud();
}

/* 控制条出现/交互时读取当前设备音量刷新图标 */
function biliVolSync() {
  var el = document.getElementById("biliVol");
  if (!el) return;
  var st = biliVolState();
  biliVolRender(st.pct);
}

/* ===== 加载反馈（req1）：小屏/全屏都显示 ===== */
function biliShowLoading() {
  var el = document.getElementById("biliLoading");
  if (el) el.classList.add("show");
}
function biliHideLoading() {
  var el = document.getElementById("biliLoading");
  if (el) el.classList.remove("show");
}

/* ===== 亮度调节（本轮新增）：全屏左侧 1/4 上下滑，HUD 显示进度，与设备屏幕亮度同步 ===== */
var biliBrightPct = 50, biliBrightTimer = null;
function biliBrightState() { return { pct: biliBrightPct }; }
function biliBrightSync() {
  try {
    if (window.Android && Android.biliBrightness) {
      var o = JSON.parse(Android.biliBrightness());
      if (o && o.ok && typeof o.cur === "number") biliBrightPct = o.cur;
    }
  } catch (e) {}
}
function biliBrightApply(pct, keep) {
  pct = Math.round(pct);
  if (pct < 1) pct = 1; if (pct > 100) pct = 100;
  biliBrightPct = pct;
  try { if (window.Android && Android.biliSetBrightness) Android.biliSetBrightness(pct); } catch (e) {}
  biliBrightRender(pct, keep);
}
function biliBrightRender(pct, keep) {
  var f = document.getElementById("biliBrightFill"); if (f) f.style.height = pct + "%";
  var t = document.getElementById("biliBrightPct"); if (t) t.textContent = pct + "%";
  var el = document.getElementById("biliBright"); if (el) el.classList.add("show");
  if (biliBrightTimer) { clearTimeout(biliBrightTimer); biliBrightTimer = null; }
  if (!keep) biliBrightTimer = setTimeout(function () {
    var e2 = document.getElementById("biliBright"); if (e2) e2.classList.remove("show");
  }, 3000);
}
function biliBrightOpen() { biliBrightSync(); biliBrightRender(biliBrightPct, false); }

/* ===== 全屏手势（req4）：右侧 1/4 上下滑=音量；中间区域左右滑=进度预览 ===== */
var biliGestureMovedAt = 0;
function biliSuppressClick() { biliGestureMovedAt = Date.now(); }

function biliBindGestures(v) {
  var sx = 0, sy = 0, W = 1, H = 1, active = false, mode = "", startPct = 0, startVol = 0, rightZone = false, leftZone = false, startBright = 50;
  var lpTimer = null, fastOn = false, chromeHidden0 = false;
  function clearLp() { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } }
  /* 全屏时播放器整层旋转 ±90°：clientX/Y 是屏幕坐标，必须换算回播放器局部坐标，
     否则左右/上下和左右分区全反（表现为「上下滑改进度、左右滑改音量」）。 */
  function localPt(x, y) {
    var r = v.getBoundingClientRect();
    var w = v.offsetWidth || 1, h = v.offsetHeight || 1;
    var tv = document.getElementById("tab-video");
    var rot = 0;
    if (tv) {
      if (tv.classList.contains("fs-rot-90")) rot = 90;
      else if (tv.classList.contains("fs-rot--90")) rot = -90;
    }
    if (!rot) return { x: x - r.left, y: y - r.top, w: w, h: h };
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    if (rot === 90) return { x: w / 2 + (y - cy), y: h / 2 - (x - cx), w: w, h: h };
    return { x: w / 2 - (y - cy), y: h / 2 + (x - cx), w: w, h: h };
  }
  v.addEventListener("touchstart", function (e) {
    if (e.touches.length !== 1) return;
    var t = e.touches[0];
    var p0 = localPt(t.clientX, t.clientY);
    W = p0.w; H = p0.h;
    sx = p0.x; sy = p0.y;
    rightZone = sx >= W * 0.75;
    leftZone = sx <= W * 0.25;
    active = true; mode = ""; fastOn = false;
    startPct = (v.duration > 0) ? (v.currentTime / v.duration) : 0;
    startVol = biliVolState().pct;
    startBright = biliBrightPct;
    /* 记下起手时上下栏是收着的还是露着的：滑动手势结束时按这个状态复位，
       防止手势尾巴上的那次 click 把上下栏翻出来（音量/亮度本来就不会带出上下栏）。 */
    var tb0 = document.getElementById("tab-video");
    chromeHidden0 = !!(tb0 && tb0.classList.contains("chrome-hidden"));
    clearLp();
    /* 左右两侧长按 = 1.5× 倍速（松手恢复用户倍速）；短按/滑动不触发 */
    if (rightZone || leftZone) {
      lpTimer = setTimeout(function () {
        lpTimer = null;
        if (!active || mode) return;
        fastOn = true;
        biliFastStart();
        biliSuppressClick();
      }, 480);
    }
  }, { passive: false });
  v.addEventListener("touchmove", function (e) {
    if (!active || e.touches.length !== 1) return;
    var t = e.touches[0];
    var pm = localPt(t.clientX, t.clientY);
    var dx = pm.x - sx, dy = pm.y - sy;
    if (fastOn) { e.preventDefault(); return; }
    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) clearLp();
    if (!biliIsFull()) return; /* 小屏只支持长按倍速与双击，不做音量/进度手势 */
    if (!mode) {
      if (Math.abs(dx) < 12 && Math.abs(dy) < 12) return;
      /* 水平 → 进度（任何位置）；垂直 → 左半亮度、右半音量；中间竖滑不接管 */
      if (Math.abs(dx) > Math.abs(dy)) mode = "seek";
      else if (rightZone) mode = "vol";
      else if (leftZone) mode = "bright";
      else return;
      biliSuppressClick();
    }
    e.preventDefault();
    if (mode === "seek") {
      biliScrubShowPct(startPct + dx / W);
      biliSeekHint();                    /* 收起上下栏时也能看见目标时间 */
    } else if (mode === "bright") {
      biliBrightApply(startBright - dy / H * 100, true);
    } else {
      biliVolApply(startVol - dy / H, true);
      var el = document.getElementById("biliVol");
      if (el) el.classList.remove("interactive");
    }
  }, { passive: false });
  function endTouch() {
    clearLp();
    if (fastOn) { fastOn = false; biliFastEnd(); biliSuppressClick(); active = false; mode = ""; return; }
    if (!active) return;
    active = false;
    if (mode === "seek") biliSeekCommit();
    else if (mode === "bright") biliBrightRender(biliBrightPct, false);
    else if (mode === "vol") { var st = biliVolState(); biliVolRender(st.pct); }
    if (mode) {
      /* 手势收尾：把上下栏复位到起手时的样子，并把「下一次 click 不算点击」的窗口
         从 400ms 拉到 ~750ms——进度/音量滑完松手常常会多派发一次 click，
         以前就是它把收起的上下栏又翻出来。顺便清掉可能已排队的 240ms 切换定时器。 */
      biliSuppressClick();
      biliGestureMovedAt += 340;
      if (biliChromeT) { clearTimeout(biliChromeT); biliChromeT = null; }
      var tb1 = document.getElementById("tab-video");
      if (tb1 && biliIsFull()) {
        if (chromeHidden0) tb1.classList.add("chrome-hidden");
        else tb1.classList.remove("chrome-hidden");
      }
    }
    mode = "";
  }
  v.addEventListener("touchend", endTouch, { passive: false });
  v.addEventListener("touchcancel", endTouch, { passive: false });
}

/* ===== 倍速 & 双击暂停（本轮新增）===== */
var biliUserRate = 1;             /* 用户选择的常驻倍速 */
var biliLastTapAt = 0;

function biliRateText(r) {
  if (r === 1) return "1.0×";
  if (r === 1.25) return "1.25×";
  if (r === 1.5) return "1.5×";
  if (r === 2) return "2.0×";
  return r + "×";
}

function biliSetRate(r) {
  biliUserRate = r;
  var v = document.getElementById("biliVideo");
  if (v) { try { v.playbackRate = r; } catch (e) {} }
  var lbl = document.getElementById("biliRateLbl");
  if (lbl) lbl.textContent = biliRateText(r);
  biliRateClose();
}

function biliRateTap(e) {
  if (e && e.stopPropagation) e.stopPropagation();
  biliSubMenuClose();
  var m = document.getElementById("biliRateMenu");
  if (m) m.classList.toggle("show");
}
function biliRateClose() {
  var m = document.getElementById("biliRateMenu");
  if (m) m.classList.remove("show");
}

/* 长按 1.5×：临时加速，松开恢复用户倍速 */
function biliFastStart() {
  var v = document.getElementById("biliVideo");
  if (v) { try { v.playbackRate = 1.5; } catch (e) {} }
  var f = document.getElementById("biliFast");
  if (f) f.classList.add("show");
}
function biliFastEnd() {
  var v = document.getElementById("biliVideo");
  if (v) { try { v.playbackRate = biliUserRate; } catch (e) {} }
  var f = document.getElementById("biliFast");
  if (f) f.classList.remove("show");
}

/* 单击：切换控制条；双击：暂停/播放（小屏/全屏通用） */
function biliOnVideoTap(e) {
  if (Date.now() - biliGestureMovedAt < 400) return;
  var now = Date.now();
  if (now - biliLastTapAt < 300) {
    biliLastTapAt = 0;
    biliPlayPause();
    return;
  }
  biliLastTapAt = now;
  if (biliIsFull()) biliFullToggleChrome(e);
  else biliToggleCtl();
}

function biliPlayPause() {
  var v = document.getElementById("biliVideo");
  if (!v) return;
  if (v.paused) {
    try { var p = v.play(); if (p && typeof p["catch"] === "function") p["catch"](function () {}); } catch (e) {}
  } else { try { v.pause(); } catch (e) {} }
  biliShowCtl();
}

function biliSetPlayIcon(playing) {
  var b = document.getElementById("biliPlayBtn");
  if (!b) return;
  var u = b.querySelector("use");
  if (u) u.setAttribute("href", playing ? "#i-pause" : "#i-play");
}

function biliMute() {
  var v = document.getElementById("biliVideo");
  if (!v) return;
  v.muted = !v.muted;
  biliSyncTime();
  biliShowCtl();
}

/* 上一集/下一集：多 P 视频在分 P 里走（到头就提示，不跨视频）；
   单 P 视频（收藏夹里绝大多数）顺着收藏夹找上/下一个能播的。 */
function biliEp(d) {
  if (!biliCur) return;
  var pg = biliCur.page || 1;
  var tp = biliCur.pages || 1;
  if (tp > 1) {
    var np = pg + d;
    if (np >= 1 && np <= tp) { biliPlayPage(np); return; }
    if (window.toast) toast(d > 0 ? "已经是最后一集了" : "已经是第一集了");
    return;
  }
  var i = (biliCur.idx == null ? -1 : biliCur.idx) + d;
  var list = biliCur.list || [];
  for (; i >= 0 && i < list.length; i += d) {
    if (list[i] && list[i].playable) { biliPlay(i); return; }
  }
  if (window.toast) toast(d > 0 ? "已经是最后一个了" : "已经是第一个了");
}

/* 全屏：进全屏让原生把 Activity 锁成横屏（跟手机播放器一样），布局交给 CSS；
   退全屏恢复跟随系统方向。Activity 配了 configChanges，转屏不重建，WebView 状态不丢。 */
function biliIsFull() {
  var p = document.getElementById("tab-video");
  return !!(p && p.classList.contains("bili-full"));
}

/* 调原生方向锁；桥方法缺失时静默降级（页面自己仍然铺黑）。 */
function biliNativeFull(on) {
  try { if (window.Android && Android.biliFullscreen) Android.biliFullscreen(!!on); } catch (e) {}
}

/* 全屏横屏的方向：Activity 锁竖屏，靠这一层旋转出横屏画面。
   gamma 是设备左右倾角，±90 就是机身横过来；正负对应两个横屏方向。
   平放/竖直（|gamma| 小）时保持上一次的方向，不来回跳。 */
var biliFsRot = 90;
function biliSetFsRot(r) {
  r = r > 0 ? 90 : -90;
  if (r === biliFsRot) {
    var cur = document.getElementById("tab-video");
    if (cur && (cur.classList.contains("fs-rot-90") || cur.classList.contains("fs-rot--90"))) return;
  }
  biliFsRot = r;
  var p = document.getElementById("tab-video");
  if (!p) return;
  p.classList.remove("fs-rot-90", "fs-rot--90");
  p.classList.add(r > 0 ? "fs-rot-90" : "fs-rot--90");
}
window.addEventListener("deviceorientation", function (e) {
  try {
    if (typeof biliIsFull !== "function" || !biliIsFull()) return;
    var g = e.gamma || 0;
    if (g >= 25) biliSetFsRot(-90);
    else if (g <= -25) biliSetFsRot(90);
  } catch (err) {}
});

/* ---------- 全屏 / 小窗过渡 ----------
   原来切全屏是纯 class 切换：画面、旋转、控制条一帧内全部换位，
   眼睛看到的就是「闪一下」。这里给播放器补一段连续过渡：
   进场从卡片尺寸「转开」铺满，退场原路转回卡片大小，落定后再撤 full 态。 */
var biliFsBusy = 0, biliFsT = 0, biliFsGeom = null, biliFsScroll = 0;

function biliFsPlayerEl() {
  var p = document.getElementById("tab-video");
  return p ? p.querySelector(".bili-player") : null;
}

/* 记住「卡片态」播放器的位置与缩放：进出用同一套数，来回才对得上 */
function biliFsMeasure() {
  var pl = biliFsPlayerEl();
  if (!pl) return;
  var r = pl.getBoundingClientRect();
  var vw = window.innerWidth || 360, vh = window.innerHeight || 800;
  /* 全屏态是「宽=100vh、高=100vw」，再整体旋转 ±90°；按可视框反推缩放比 */
  var s = Math.min(r.width / Math.max(1, vh), r.height / Math.max(1, vw)) * 1.06;
  biliFsGeom = {
    s: Math.max(.3, Math.min(.62, s)),
    dy: Math.round((r.top + r.height / 2) - vh / 2)
  };
}

/* dir=1 进场，dir=-1 退场 */
function biliFsMorph(dir) {
  var pl = biliFsPlayerEl();
  if (!pl) return;
  var deg = (biliFsRot > 0 ? 90 : -90);
  var g = biliFsGeom || { s: .46, dy: 0 };
  var small = "translate(-50%,-50%) rotate(0deg) translateY(" + g.dy + "px) scale(" + g.s + ")";
  var big = "translate(-50%,-50%) rotate(" + deg + "deg) translateY(0px) scale(1)";
  /* 第 45 轮：这里原来同时动 transform / opacity / border-radius。
     全屏层是 100vh×100vw 再整体旋转，逐帧重算圆角和透明度会让 WebView 每帧
     重绘整块曲面 —— 真机表现就是"转得很重的卡顿"。现在只动 transform，
     圆角一步到位（这个尺度上看不出跳），并提前声明 will-change 让它走合成层。 */
  pl.style.transition = "none";
  pl.style.transform = dir > 0 ? small : big;
  pl.style.borderRadius = dir > 0 ? "12px" : "0px";
  pl.style.willChange = "transform";
  void pl.offsetWidth;                       /* 先落起点，下一帧再给终点，否则同帧两次写会被合并 */
  pl.style.transition = "transform .32s cubic-bezier(.22,1,.36,1)";
  pl.style.transform = dir > 0 ? big : small;
  pl.style.borderRadius = dir > 0 ? "0px" : "12px";
  biliFsBusy = 1;
  if (biliFsT) { clearTimeout(biliFsT); biliFsT = 0; }
  biliFsT = setTimeout(function () {
    biliFsT = 0; biliFsBusy = 0;
    if (dir > 0) {
      /* 进场收尾：把内联样式交还给 CSS，后续旋转/尺寸仍由 .bili-full 规则接管 */
      pl.style.transition = ""; pl.style.transform = "";
      pl.style.borderRadius = ""; pl.style.opacity = "";
      pl.style.willChange = "";
      var pg0 = document.getElementById("tab-video");
      if (pg0) { pg0.style.transition = ""; pg0.style.backgroundColor = ""; }
    } else {
      biliExitFullCommit();
    }
  }, 340);
}

function biliEnterFull() {
  var p = document.getElementById("tab-video");
  if (!p) return;
  if (biliFsBusy) return;
  biliFsMeasure();
  /* 第 45 轮：全屏态把页面改成 position:fixed，文档高度塌到一屏，滚动位置会被夹到 0。
     退全屏如果不管，回来就是"页面从最顶上开始"——标题栏、来源条、用户卡全跳出来，
     画面掉到半屏以下。记住进全屏前的滚动位置，落定时原样放回去。 */
  try {
    var se0 = document.scrollingElement || document.body;
    biliFsScroll = se0 ? (se0.scrollTop || 0) : 0;
  } catch (e) { biliFsScroll = 0; }
  p.classList.add("bili-full");
  /* 转场期间页面内容整体隐身（见 style.css 的 .fs-morph 规则） */
  p.classList.add("fs-morph");
  p.classList.remove("fs-in");
  /* 黑底淡入：进场那一下不加的话，页面背景是一帧之内由浅变全黑 */
  p.style.transition = "background-color .24s linear";
  p.style.backgroundColor = "rgba(0,0,0,0)";
  void p.offsetWidth;
  p.style.backgroundColor = "#000";
  biliSetFsRot(biliFsRot || 90);
  biliNativeFull(true);
  biliSizeVideo();
  biliFsMorph(1);
  biliShowCtl();
  /* 小窗里不出字幕，进全屏后立刻补上当前这句 */
  biliTrackIdx = -1; biliCuePage = -1;
  var v0 = document.getElementById("biliVideo");
  if (v0) { biliSubsSync(v0.currentTime); biliCueKick(); }
  var b = document.getElementById("biliFsBtn");
  if (b) {
    b.setAttribute("data-fs", "compress");
    b.setAttribute("aria-label", "退出全屏");
    var u = b.querySelector("use");
    if (u) u.setAttribute("href", "#i-compress");
  }
}

function biliExitFull() {
  var p = document.getElementById("tab-video");
  if (!p) return;
  if (biliFsBusy) return;
  var pl = biliFsPlayerEl();
  if (!pl) { biliExitFullCommit(); return; }
  /* 第 45 轮（改二）：退场不再"把全屏黑框缩回卡片"。
     那个框是被 100vh×100vw 黑底包着的，缩回来的路上黑底要占掉半屏，
     用户看到的是"大范围黑屏"；而它还要在旋转层上逐帧重绘，卡顿也在那儿。
     现在的做法：结构在同一帧直接回位（不漏卡、不叠影），
     播放器给一个 3.5% 的落定回弹，页面 160ms 淡入 —— 收回的动作由"落定"表达，
     全程不出现任何一块铺满屏幕的黑。 */
  biliFsBusy = 1;
  if (biliFsT) { clearTimeout(biliFsT); biliFsT = 0; }
  pl.style.transition = "none";
  pl.style.transform = "";
  pl.style.borderRadius = "";
  pl.style.willChange = "";
  p.style.transition = "none";
  p.style.backgroundColor = "";
  p.classList.remove("fs-morph");
  biliExitFullCommit();
  pl.classList.remove("fs-land-player");
  void pl.offsetWidth;
  pl.classList.add("fs-land-player");
  setTimeout(function () { pl.classList.remove("fs-land-player"); }, 300);
  biliFsBusy = 0;
}

/* 退场动画落定后才撤 .bili-full，页面结构回位；
   回位后给标题/信息/选集一个很短的淡入，替代原来的一帧硬切。 */
function biliExitFullCommit() {
  var p = document.getElementById("tab-video");
  if (!p) return;
  p.classList.remove("bili-full");
  p.classList.remove("fs-rot-90", "fs-rot--90");
  /* 内容从隐身状态淡回来（.fs-in 180ms），代替一帧硬切 */
  p.classList.remove("fs-morph");
  p.classList.add("fs-in");
  setTimeout(function () { p.classList.remove("fs-in"); }, 220);
  p.classList.remove("chrome-hidden");
  p.classList.remove("is-paused");
  p.style.transition = ""; p.style.backgroundColor = "";
  if (biliChromeT) { clearTimeout(biliChromeT); biliChromeT = null; }
  biliNativeFull(false);
  /* 回到小窗：字幕立即收起（小窗不出字幕），出场动画也停掉 */
  try { biliCueStop(); } catch (e) {}
  var cap0 = document.getElementById("biliCap");
  if (cap0) cap0.style.display = "none";
  var pl = p.querySelector(".bili-player");
  if (pl) {
    pl.style.transition = ""; pl.style.transform = "";
    pl.style.borderRadius = ""; pl.style.opacity = ""; pl.style.willChange = "";
  }
  var v = document.getElementById("biliVideo");
  if (v && v.style) { v.style.width = ""; v.style.height = ""; v.style.left = ""; v.style.top = ""; v.style.transform = ""; }
  var c = document.getElementById("biliCtl");
  if (c && c.style) { c.style.top = ""; c.style.left = ""; c.style.right = ""; c.style.width = ""; }
  var b = document.getElementById("biliFsBtn");
  if (b) {
    b.setAttribute("data-fs", "expand");
    b.setAttribute("aria-label", "全屏");
    var u = b.querySelector("use");
    if (u) u.setAttribute("href", "#i-expand");
  }
  biliSizeVideo();
  /* 滚动位置放回去：跟 .bili-full 移除在同一帧，落到小窗时画面还在原来的位置 */
  try {
    var se1 = document.scrollingElement || document.body;
    if (se1 && biliFsScroll) se1.scrollTop = biliFsScroll;
  } catch (e) {}
  /* 落地轻淡入只给标题和信息卡。播放器与选集是"要看的东西"，
     跟着从 0 透明度浮起来，像页面缺了一块再补上（第 45 轮去掉）。 */
  var land = p.querySelectorAll(".bili-head, .bili-meta");
  for (var i = 0; i < land.length; i++) {
    land[i].classList.add("fs-land");
    (function (el) {
      setTimeout(function () { el.classList.remove("fs-land"); }, 280);
    })(land[i]);
  }
}

function biliToggleFull() {
  if (biliFsBusy) return;
  if (biliIsFull()) biliExitFull(); else biliEnterFull();
}

/* 全屏布局交给 CSS：视频铺满整个屏幕（object-fit: contain 留黑边），
   控制条浮在画面底部，跟常见手机播放器一致；这里只负责清掉内联尺寸。 */
function biliSizeVideo() {
  var v = document.getElementById("biliVideo");
  if (!v || !v.style) return;
  v.style.width = "";
  v.style.left = "";
  v.style.top = "";
  v.style.transform = "";
  var c = document.getElementById("biliCtl");
  if (c && c.style) { c.style.top = ""; c.style.left = ""; c.style.right = ""; c.style.width = ""; }
  if (!biliIsFull()) {
    /* 小屏：钉死 16:9 画面框。切换分 P 时新视频还没拿到元数据，若放任 height:auto
       会塌成方形（“无效扩画面”）；这里按宽度算 16:9 高度，黑底 + 居中加载 UI。
       但内联 height 的优先级高于 CSS，所以必须自己把上限压到「视口 - 悬浮导航」，
       否则 800x360 竖屏下算出 425px，底部控制条和字幕会被 .tabs 盖住。 */
    var w = v.clientWidth || 0;
    if (!w) { var pl = document.querySelector(".bili-player"); if (pl) w = pl.clientWidth || 0; }
    if (w > 0) {
      var h169 = Math.round(w * 9 / 16);
      var tabsH = 105;
      try {
        var tb = document.getElementById("tabs");
        if (tb) {
          var r = tb.getBoundingClientRect();
          tabsH = Math.round(r.height + (window.innerHeight - r.bottom) + 8);
        }
      } catch (e) {}
      var cap = window.innerHeight - tabsH - 24;
      if (cap > 80 && h169 > cap) h169 = Math.round(cap);
      v.style.height = h169 + "px";
    }
    v.style.objectFit = "contain";
    v.style.background = "#000";
  } else {
    v.style.height = "";
    v.style.objectFit = "";
    v.style.background = "";
  }
}

window.addEventListener("resize", function () { biliSizeVideo(); });
window.addEventListener("orientationchange", function () {
  setTimeout(function () { biliSizeVideo(); }, 120);
});

function biliDur(sec) {
  sec = parseInt(sec, 10);
  if (!sec || sec < 0) return "00:00";
  var m = Math.floor(sec / 60);
  var s = sec % 60;
  if (m >= 60) {
    var hh = Math.floor(m / 60);
    m = m % 60;
    return hh + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
  }
  return m + ":" + (s < 10 ? "0" : "") + s;
}

function biliEmpty(msg) {
  return '<div class="bili-empty">' + esc(msg) + "</div>";
}

/* 宿主 onPause 回调：切到后台时暂停视频并记录进度，用户回来可继续看 */
window.gimeHostPause = function () {
  try { if (typeof biliPauseForLeave === "function") biliPauseForLeave(); } catch (e) {}
};
/* ===== 视频实时字幕（离线 Vosk）：Web Audio 抓 <video> 采样 → 原生识别 → 字幕条 ===== */
var biliSubOn = false, biliSubEl = null, biliAudioCtx = null, biliSubSrc = null, biliSubProc = null;
var biliSubWorklet = null, biliSubMute = null, biliSubStarting = false;
/* 一个 <video> 元素只能绑定一次 MediaElementSourceNode，重复绑定会抛
   "already connected previously to a different MediaElementSourceNode"。
   所以 ctx / src 按元素长期持有：关闭字幕只拆 ScriptProcessor，换集（元素重建）时才释放旧 ctx。 */
var biliSubBoundEl = null, biliSubBoundCtx = null, biliSubBoundSrc = null;
function biliSubAttach(v) {
  if (biliSubBoundEl && biliSubBoundEl !== v) {
    try { if (biliSubBoundCtx) biliSubBoundCtx.close(); } catch (e) {}
    biliSubBoundEl = null; biliSubBoundCtx = null; biliSubBoundSrc = null;
    biliAudioCtx = null; biliSubSrc = null; biliSubProc = null;
    biliSubWorklet = null; biliSubMute = null; biliSubStarting = false;
  }
  biliSubEl = v;
  if (biliSubOn) {
    /* 播放器重建后 #biliCap 会回到模板里的 display:none、CC 按钮丢失 .on，
       切集/先开 CC 再开视频时这里把 UI 状态同步回来，否则识别在跑但看不见字幕。 */
  var capEl = document.getElementById("biliCap");
  /* 字幕只来自「生成好的本地轨」或「上游官方字幕」：识别/缓存期间不上屏任何字幕 */
  var official = subsMode() === "official";
  var st0 = null;
  try { st0 = biliSubsStateNow(); } catch (e) {}
  var ready0 = official ? !!biliTrack : !!(st0 && st0.state === "ready");
  if (capEl) capEl.style.display = (ready0 && biliIsFull()) ? "" : "none";
  var ccEl = document.getElementById("biliCcBtn");
  if (ccEl) ccEl.classList.add("on");
  if (!official && !ready0) { try { biliSubDetach(); } catch (e) {} biliSubsPoll(); }
  }
}
function biliSubDetach() {
  /* 只停止喂样本，保留 ctx / src 与元素的绑定，供再次开启时复用 */
  try {
    if (biliSubProc) { biliSubProc.onaudioprocess = null; biliSubProc.disconnect(); }
    if (biliSubWorklet) { try { biliSubWorklet.port.onmessage = null; } catch (e) {} biliSubWorklet.disconnect(); }
    if (biliSubMute) { try { biliSubMute.disconnect(); } catch (e) {} }
  } catch (e) {}
  biliSubProc = null; biliSubWorklet = null; biliSubMute = null;
  biliAudioCtx = null; biliSubSrc = null; biliSubStarting = false;
}
function biliSubStartGraph() {
  if (!biliSubEl || biliSubProc || biliSubWorklet || biliSubStarting) return;
  /* 通知原生启动识别引擎。缺这一句时 asrPcm 送进去也没人接
     （引擎未初始化 → rustHandle=0 → 直接丢弃），表现为"CC 开了没字幕"。 */
  try { if (window.Android && Android.asrStart) Android.asrStart(); } catch (e) {}
  try {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) { toast("此设备不支持音频采集"); return; }
    var ctx = biliSubBoundCtx, src = biliSubBoundSrc;
    if (!ctx || biliSubBoundEl !== biliSubEl) {
      ctx = new AC();
      src = ctx.createMediaElementSource(biliSubEl);
      src.connect(ctx.destination);
      biliSubBoundEl = biliSubEl; biliSubBoundCtx = ctx; biliSubBoundSrc = src;
    }
    biliAudioCtx = ctx; biliSubSrc = src;
    /* 优先 AudioWorklet：process() 在音频线程跑，主线程再忙也不会像
       ScriptProcessor 那样整块丢回调（丢帧 7.5% 的元凶）。加载失败回退 ScriptProcessor。 */
    if (ctx.audioWorklet && window.AudioWorkletNode) {
      biliSubStarting = true;
      ctx.audioWorklet.addModule("asr-worklet.js").then(function () {
        biliSubStarting = false;
        if (!biliSubOn) return;
        try {
          var node = new AudioWorkletNode(ctx, "asr-capture", { processorOptions: { ratio: ctx.sampleRate / 16000 } });
          node.port.onmessage = function (ev) {
            if (ev.data && ev.data.stat) { window.__wkStat = ev.data.stat; return; }
            if (!biliSubOn) return;
            var buf = ev.data;
            if (buf && !buf.length && buf.byteLength) buf = new Int16Array(buf);
            if (!buf || !buf.length) return;
            window.__sentSec = (window.__sentSec || 0) + buf.length / 16000;
            try { if (window.Android && Android.asrPcm) Android.asrPcm(biliB64(buf)); } catch (err) {}
          };
          src.connect(node);
          /* worklet 节点需要有下游才会被拉取；接一个 0 增益到 destination，不重复出声 */
          var mute = ctx.createGain(); mute.gain.value = 0;
          node.connect(mute); mute.connect(ctx.destination);
          biliSubWorklet = node; biliSubMute = mute;
          window.__subMode = "worklet";
          if (biliSubEl.paused) { try { ctx.suspend(); } catch (e) {} }
          else { try { ctx.resume(); } catch (e) {} }
        } catch (e) { biliSubStartScript(ctx, src); }
      }).catch(function () { biliSubStarting = false; biliSubStartScript(ctx, src); });
      return;
    }
    biliSubStartScript(ctx, src);
  } catch (e) { toast("字幕初始化失败：" + e.message); }
}
function biliSubStartScript(ctx, src) {
  if (!ctx || !src || biliSubProc) return;
  try {
    var proc = ctx.createScriptProcessor(8192, 1, 1);
    var ratio = ctx.sampleRate / 16000;
    var qbuf = [];   /* 攒约 0.13s（2048@16k）；8192@48k≈170ms 一块、回调约 5.9 次/秒，
                        降低主线程调度压力，减少 onaudioprocess 被跳过导致的音频丢帧 */
    proc.onaudioprocess = function (e) {
      if (!biliSubOn) return;
      var inp = e.inputBuffer.getChannelData(0);
      var n = Math.floor(inp.length / ratio);
      if (n <= 0) return;
      var out = new Int16Array(n), peak = 0;
      for (var i = 0; i < n; i++) {
        var a = Math.floor(i * ratio), b = Math.min(inp.length, Math.floor((i + 1) * ratio));
        var s = 0, c = 0;
        for (var j = a; j < b; j++) { s += inp[j]; c++; }
        var val = c ? s / c : 0;
        if (val > 1) val = 1; if (val < -1) val = -1;
        var av = val < 0 ? -val : val; if (av > peak) peak = av;
        out[i] = val < 0 ? val * 0x8000 : val * 0x7FFF;
      }
      /* 静音段换成等长零样本：识别器仍看到时间推进（断句正常），但几乎不耗解码 */
      var block = peak > 0.008 ? out : new Int16Array(n);
      for (var k = 0; k < block.length; k++) qbuf.push(block[k]);
      if (qbuf.length < 2048) return;
      var send = new Int16Array(qbuf); qbuf = [];
      window.__sentSec = (window.__sentSec || 0) + send.length / 16000;
      try { if (window.Android && Android.asrPcm) Android.asrPcm(biliB64(send)); } catch (err) {}
    };
    src.connect(proc);
    proc.connect(ctx.destination);
    biliAudioCtx = ctx; biliSubSrc = src; biliSubProc = proc;
    window.__subMode = "script";
    if (biliSubEl.paused) { try { ctx.suspend(); } catch (e) {} }
    else { try { ctx.resume(); } catch (e) {} }
  } catch (e) { toast("字幕初始化失败：" + e.message); }
}
function biliB64(i16) {
  var u8 = new Uint8Array(i16.buffer); var s = "";
  for (var i = 0; i < u8.length; i += 0x8000) {
    s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  }
  return btoa(s);
}
/* ===================== 预生成字幕轨 =====================
   实时识别已经退出播放链路：字幕只来自「提前生成好的轨道」。
   播放时按 currentTime 在轨里二分查找，零算力、零延迟。
   没有轨时点 CC = 起一个生成任务（后台服务跑），期间只给提示不给字。 */
var biliTrack = null, biliTrackKey = "", biliTrackIdx = -1, biliSubsTimer = 0, biliCuePage = -1;

function biliSubsIds() {
  var b = (typeof biliCur !== "undefined" && biliCur) ? (biliCur.bvid || "") : "";
  var p = (typeof biliCur !== "undefined" && biliCur) ? (parseInt(biliCur.page, 10) || 1) : 1;
  return { bvid: b, page: p };
}

function biliSubsTitle() {
  if (typeof biliCur !== "undefined" && biliCur) {
    if (biliCur.part) return biliCur.part;
    if (biliCur.title) return biliCur.title;
  }
  return "P" + biliSubsIds().page;
}

function biliSubsStateNow() {
  var k = biliSubsIds();
  if (!k.bvid || !(window.Android && Android.subsState)) return { state: "none" };
  try { return JSON.parse(Android.subsState(k.bvid, String(k.page))); } catch (e) { return { state: "none" }; }
}

/* 字幕轨归一化：逐字时间轴 → 按句末标点断句 → 句内逗号处切到 ≤40 字一条，标点不上屏。
   口语化视频常有一句 20 秒不断，整句上屏会把后面十几秒要说的话提前铺出来；
   切成短块后每条字幕都只贴着眼前这几秒。新旧两种轨（20 字碎片 / 已去标点的整句）都走这一套。 */
var BILI_PUNCT = /[，。！？：；、,.!?;:]/;
var BILI_PUNCT_G = /[，。！？：；、,.!?;:]/g;
var BILI_SENT_END = /[。！？!?;；…]/;
var BILI_SOFT = "，、：,:";
var BILI_CUE_MAX = 40;
function biliTrackNormalize(segs) {
  var i, hasP = false;
  for (i = 0; i < segs.length; i++) if (BILI_PUNCT.test(segs[i].t || "")) { hasP = true; break; }
  /* 新轨（生成时已按标点切块、标点也去掉了）：一条就是一块，只在超长时按字数硬切兜底 */
  if (!hasP) {
    var out0 = [];
    for (i = 0; i < segs.length; i++) {
      var t0 = String(segs[i].t || "");
      if (!t0) continue;
      var s0 = +segs[i].s || 0, e0 = +segs[i].e;
      if (!(e0 > s0)) e0 = s0;
      if (t0.length <= BILI_CUE_MAX) { out0.push({ s: s0, e: e0, t: t0 }); continue; }
      var n0 = t0.length;
      for (var k0 = 0; k0 < n0; k0 += BILI_CUE_MAX) {
        var a0 = k0, b0 = Math.min(n0, k0 + BILI_CUE_MAX);
        out0.push({
          s: Math.round((s0 + (e0 - s0) * a0 / n0) * 100) / 100,
          e: Math.round((s0 + (e0 - s0) * b0 / n0) * 100) / 100,
          t: t0.slice(a0, b0)
        });
      }
    }
    return out0;
  }
  /* 旧轨（20 字一行、带标点）：展开成逐字时间轴 → 句末断句 → 句内标点处切到 ≤40 字 */
  var chars = [], times = [];
  for (i = 0; i < segs.length; i++) {
    var t1 = String(segs[i].t || "");
    var n = t1.length;
    if (!n) continue;
    var s1 = +segs[i].s || 0, e1 = +segs[i].e;
    if (!(e1 > s1)) e1 = s1;
    for (var k = 0; k < n; k++) {
      chars.push(t1.charAt(k));
      times.push([s1 + (e1 - s1) * k / n, s1 + (e1 - s1) * (k + 1) / n]);
    }
  }
  if (!chars.length) return [];
  var out = [];
  var a = 0;
  for (var j = 0; j < chars.length; j++) {
    if (BILI_SENT_END.test(chars[j])) { biliCueChunks(out, chars, times, a, j + 1); a = j + 1; }
  }
  if (a < chars.length) biliCueChunks(out, chars, times, a, chars.length);
  return out;
}
function biliCueChunks(out, chars, times, a, b) {
  var cs = a, i = a;
  while (i < b) {
    var j = i;
    while (j < b && BILI_SOFT.indexOf(chars[j]) < 0) j++;
    var clauseEnd = (j < b) ? j + 1 : b;
    if (clauseEnd - cs > BILI_CUE_MAX && i > cs) { biliCueEmit(out, chars, times, cs, i); cs = i; }
    i = clauseEnd;
  }
  if (cs < b) biliCueEmit(out, chars, times, cs, b);
}
function biliCueEmit(out, chars, times, a, b) {
  var cs = a;
  while (b - cs > BILI_CUE_MAX) { biliCuePiece(out, chars, times, cs, cs + BILI_CUE_MAX); cs += BILI_CUE_MAX; }
  biliCuePiece(out, chars, times, cs, b);
}
function biliCuePiece(out, chars, times, a, b) {
  if (b <= a) return;
  var t = "";
  for (var i = a; i < b; i++) if (!BILI_PUNCT.test(chars[i])) t += chars[i];
  if (!t) return;
  out.push({ s: Math.round(times[a][0] * 100) / 100, e: Math.round(times[b - 1][1] * 100) / 100, t: t });
}

function biliSubsLoadTrack() {
  var k = biliSubsIds();
  if (!k.bvid || !(window.Android && Android.subsTrack)) return false;
  var key = k.bvid + ":" + k.page;
  if (biliTrackKey === key && biliTrack) return true;
  var raw = "";
  try { raw = Android.subsTrack(k.bvid, String(k.page)) || ""; } catch (e) { raw = ""; }
  if (!raw) { biliTrack = null; biliTrackKey = ""; return false; }
  try {
    var o = JSON.parse(raw);
    var segs = o.segs || [];
    if (!segs.length) { biliTrack = null; biliTrackKey = ""; return false; }
    segs.sort(function (a, b) { return a.s - b.s; });
    segs = biliTrackNormalize(segs);
    if (!segs.length) { biliTrack = null; biliTrackKey = ""; return false; }
    biliTrack = segs; biliTrackKey = key; biliTrackIdx = -1; biliCuePage = -1;
    return true;
  } catch (e) {
    biliTrack = null; biliTrackKey = "";
    return false;
  }
}

function biliSubsAttach() {
  var v = document.getElementById("biliVideo");
  if (!v || v.__subsHooked) return;
  v.__subsHooked = true;
  v.addEventListener("timeupdate", function () { biliSubsSync(v.currentTime); });
  v.addEventListener("seeking", function () { biliTrackIdx = -1; biliSubsSync(v.currentTime); });
  v.addEventListener("pause", function () { /* 停住不清屏，保持当前句 */ });
}

/** 按播放位置显示对应的一句；只在句子切换时才动 DOM。 */
/* 整句轨最多显示两行：超过两行的句子按「两行一页」分页，在句子时长内按字数占比翻页。
   容量用离屏同款样式的探针量真实换行（不靠估算），换屏宽/字号时自动重算。 */
var biliCapProbe = null;
/* 返回「从 from 起、两行放得下的最长前缀」的结束下标；量不到（框还没显示）返回 -1。
   必须按每页自己的起点量：同一句话从不同字符起换行点不同，按整句前缀量会漏字。 */
function biliCuePageEnd(text, from) {
  var cap = document.getElementById("biliCap");
  var cur = document.getElementById("bcapCur");
  if (!cap || !cur || !cap.clientWidth) return -1;
  if (!biliCapProbe) {
    biliCapProbe = document.createElement("div");
    biliCapProbe.className = "bcap-cur";
    biliCapProbe.style.cssText =
      "position:absolute;left:-9999px;top:0;visibility:hidden;pointer-events:none;" +
      "white-space:normal;word-break:break-word;";
    document.body.appendChild(biliCapProbe);
  }
  var ps = window.getComputedStyle(cap);
  /* 宽度基准取「字幕框撑满时的真实上限」：临时写入整句再还原，同一任务内完成、不上屏。
     不能用视口比例算——字幕框的含块是播放器容器（被 transform 限制），不等宽于视口。 */
  var prev = cur.textContent;
  cur.textContent = text;
  var w = Math.max(80, cap.clientWidth - (parseFloat(ps.paddingLeft) || 0) - (parseFloat(ps.paddingRight) || 0));
  cur.textContent = prev;
  biliCapProbe.style.width = w + "px";
  var lh = parseFloat(window.getComputedStyle(biliCapProbe).lineHeight) || 24;
  function fits(j) {
    biliCapProbe.textContent = text.slice(from, j);
    return biliCapProbe.scrollHeight <= lh * 2 + 1;
  }
  if (fits(text.length)) return text.length;
  var lo = from + 1, hi = text.length;
  while (lo < hi) {
    var mid = (lo + hi + 1) >> 1;
    if (fits(mid)) lo = mid; else hi = mid - 1;
  }
  return lo;
}
function biliCueEnv() {
  var tv = document.getElementById("tab-video");
  return window.innerWidth + ":" + (tv && tv.classList.contains("bili-full") ? "f" : "n");
}
function biliCuePages(cue) {
  var env = biliCueEnv();
  if (cue.__p && cue.__pw === env) return cue.__p;
  var pages = [], i = 0, n = cue.t.length, guard = 0;
  while (i < n && guard++ < 500) {
    var j = biliCuePageEnd(cue.t, i);
    if (j < 0) return [cue.t];   /* 量不到（框还没显示）就先整句，下次时间推进再分页 */
    pages.push(cue.t.slice(i, j));
    i = j;
  }
  if (!pages.length) pages = [cue.t];
  cue.__p = pages; cue.__pw = env;
  return pages;
}

/* 整句轨的「流式」只是一个出场动画：跟语音时长无关，固定高速逐字吐完。
   新的一页开始就从头快速打一遍，打完停住；不再按句子时长慢慢拉长。 */
var BILI_CUE_TICK = 4;   /* ms/字：约 250 字/秒，长句 1 秒内出完 */
var biliCueTxt = "", biliCueN = 0, biliCueRaf = 0, biliCueLastT = 0;
function biliCuePaint() {
  var cur = document.getElementById("bcapCur");
  if (!cur) return;
  var k = Math.floor(biliCueN);
  if (k < 0) k = 0;
  if (k > biliCueTxt.length) k = biliCueTxt.length;
  var s = biliCueTxt.slice(0, k);
  if (cur.textContent !== s) cur.textContent = s;
}
function biliCueLoop(ts) {
  biliCueRaf = 0;
  if (!biliCueTxt) return;
  if (!biliCueLastT) biliCueLastT = ts;
  var dt = ts - biliCueLastT;
  biliCueLastT = ts;
  if (dt > 0) biliCueN += dt / BILI_CUE_TICK;
  biliCuePaint();
  if (biliCueN < biliCueTxt.length) biliCueRaf = requestAnimationFrame(biliCueLoop);
}
function biliCueKick() {
  if (biliCueRaf) return;
  biliCueLastT = 0;
  biliCueRaf = requestAnimationFrame(biliCueLoop);
}
function biliCueStart(text) {
  biliCueTxt = text;
  biliCueN = 0;
  biliCueLastT = 0;
  biliCuePaint();
  biliCueKick();
}
function biliCueStop() {
  biliCueTxt = ""; biliCueN = 0;
  if (biliCueRaf) { cancelAnimationFrame(biliCueRaf); biliCueRaf = 0; }
}

function biliSubsSync(t) {
  if (!biliTrack || !biliTrack.length) return;
  /* 小窗（内嵌播放器）不出字幕：清掉游标，进全屏后的第一帧会从头补上当前句 */
  if (!biliIsFull()) { biliTrackIdx = -1; biliCuePage = -1; return; }
  var lo = 0, hi = biliTrack.length - 1, hit = -1;
  while (lo <= hi) {
    var mid = (lo + hi) >> 1;
    if (t < biliTrack[mid].s) hi = mid - 1;
    else if (t >= biliTrack[mid].e) lo = mid + 1;
    else { hit = mid; break; }
  }
  if (hit !== biliTrackIdx) { biliTrackIdx = hit; biliCuePage = -1; }
  if (hit < 0) return;
  var cue = biliTrack[hit];
  if (cue.__pw !== biliCueEnv()) biliCuePage = -1;
  var pages = biliCuePages(cue), pi = 0;
  if (pages.length > 1) {
    var n = cue.t.length, dur = cue.e - cue.s, acc = 0;
    for (var i = 0; i < pages.length; i++) {
      acc += pages[i].length;
      var te = cue.s + Math.min(1, acc / n) * dur;
      if (t < te || i === pages.length - 1) { pi = i; break; }
    }
  }
  if (pi !== biliCuePage) {
    biliCuePage = pi;
    window.giCaption({ text: pages[pi], final: false, lang: "zh", cue: true });
    biliCueStart(pages[pi]);
  }
}

function biliSubsStopPoll() {
  if (biliSubsTimer) { clearInterval(biliSubsTimer); biliSubsTimer = 0; }
}

/* 队列（这个视频所有分P的生成状态）：[{p,s,c}]，s=running/queued/ready/failed，c=百分比 */
function biliQueue() {
  try {
    if (!window.Android || !Android.subsQueue) return [];
    var k = biliSubsIds();
    if (!k.bvid) return [];
    var a = JSON.parse(Android.subsQueue(k.bvid) || "[]");
    return (a instanceof Array) ? a : [];
  } catch (e) { return []; }
}
function biliQueuePending(q) {
  for (var i = 0; i < q.length; i++) if (q[i].s === "running" || q[i].s === "queued") return true;
  return false;
}
/* 正在加载字幕的视频（全库范围，最多 2 个）：视频收藏页顶部的状态条 + 入队上限判断 */
function biliPendingAll() {
  try {
    if (!window.Android || !Android.subsPending) return [];
    var a = JSON.parse(Android.subsPending() || "[]");
    return (a instanceof Array) ? a : [];
  } catch (e) { return []; }
}
function biliJobOpen(bvid, page) {
  try { biliResume(bvid, page); } catch (e) {}
}
function biliJobCancel(bvid, page) {
  try { Android.subsCancel(bvid, String(page)); } catch (e) {}
  biliSubsHint("已从字幕队列移除");
  try { biliJobsRender(); } catch (e) {}
  try { biliSubsMarks(); } catch (e) {}
}
/* 正在加载字幕的视频（按 bvid 去重；同一部视频排多少集都只占一个名额） */
function biliPendingVideos() {
  var a = biliPendingAll(), m = {}, out = [];
  for (var i = 0; i < a.length; i++) {
    if (a[i].b && !m[a[i].b]) { m[a[i].b] = 1; out.push(a[i].b); }
  }
  return out;
}
/* 排队不再设上限：想缓存多少视频、多少集都直接排进队列，服务按顺序一部一部跑。
   原来「全库最多 2 部视频」的闸门已按用户要求取消，这里保留函数只为兼容旧调用点。 */
function biliVideoSlotOk() {
  return true;
}
/* 选集行上的「缓存 / 取消」：把这一集加入或移出字幕生成队列 */
function biliQueueToggle(p) {
  var k = biliSubsIds();
  if (!k.bvid) return;
  var q = biliQueue(), m = null;
  for (var i = 0; i < q.length; i++) if (q[i].p === p) { m = q[i]; break; }
  if (m && m.s === "ready") return;
  if (m && (m.s === "running" || m.s === "queued")) {
    try { Android.subsCancel(k.bvid, String(p)); } catch (e) {}
    biliSubsHint("已从字幕队列移除");
  } else {
    var t = (biliCur && biliCur.parts && biliCur.parts[p - 1] && biliCur.parts[p - 1].t) || ("P" + p);
    biliQueueStart(k.bvid, p, t);
    biliSubsHint("已加入字幕队列");
    try { biliSubsPoll(); } catch (e) {}
  }
  try { biliSubsMarks(); } catch (e) {}
  try { biliJobsRender(); } catch (e) {}
}
/* 队列状态强弱：同一集出现重复条目时，取最靠前的那个状态为准 */
function biliQueueRank(s) {
  return s === "ready" ? 4 : s === "running" ? 3 : s === "queued" ? 2 : s === "failed" ? 1 : 0;
}
/* 同一集在队列里可能留着重复条目（历史重试、并发入队都会留下）。
   选集行原来「后写覆盖」、折叠头计数原来「先写保留」，同一份数据被读出两种结论，
   才会出现「每行都 100%、胶囊却写缓存中 159/159」这种自相矛盾。统一按最强状态取。 */
function biliQueueMap(q) {
  var m = {};
  if (!q) return m;
  for (var i = 0; i < q.length; i++) {
    var it = q[i], p = it.p;
    if (!m[p] || biliQueueRank(it.s) > biliQueueRank(m[p].s)) m[p] = it;
  }
  return m;
}
/* 「缓存全集」：把还没缓存的集一次性全部入队（已就绪/在队列里的跳过）。
   同步桥会在 JS 线程上占住几百毫秒，所以顺序是：先切「正在加入」→ 让这一帧真的上屏
   → 再去调桥拿真实入队数 → 按返回值重绘。入队结果永远只信 Java 返回的数字，
   不在点击那一刻抢先画「缓存中」，否则点了没进队列也会显示缓存中。 */
var biliEpAllLock = 0;

function biliQueueAll(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  if (biliEpAllLock) return;
  var k = biliSubsIds();
  if (!k.bvid) return;
  var n = (biliCur && biliCur.pages) || 0;
  if (!n) return;
  var qm = biliQueueMap(biliQueue());
  var todo = [];
  for (var p = 1; p <= n; p++) {
    var m = qm[p];
    if (m && (m.s === "ready" || m.s === "running" || m.s === "queued")) continue;
    todo.push(p);
  }
  if (!todo.length) {
    var allReady = true;
    for (var r = 1; r <= n; r++) if (!qm[r] || qm[r].s !== "ready") { allReady = false; break; }
    biliSubsHint(allReady ? "全集字幕都已缓存好啦" : "全部已在缓存队列中");
    return;
  }
  var parts = (biliCur && biliCur.parts) || [], titles = [];
  for (var j = 0; j < todo.length; j++) {
    var pp = todo[j];
    titles.push((parts[pp - 1] && parts[pp - 1].t) || ("P" + pp));
  }
  var vt = (biliCur && (biliCur.vtitle || biliTitlesMap()[k.bvid])) || "";
  biliEpAllLock = 1;
  biliEpAllBusy("正在加入 " + todo.length + " 集");
  biliPaintThen(function () {
    var added = -1;
    try {
      if (Android.subsQueueAll) {
        added = parseInt(Android.subsQueueAll(k.bvid, todo.join(","), JSON.stringify(titles), vt), 10);
      }
    } catch (e) { added = -1; }
    if (isNaN(added) || added < 0) {
      /* 老桥没这个接口：退回逐集入队，行为与旧版一致 */
      for (var j2 = 0; j2 < todo.length; j2++) biliQueueStart(k.bvid, todo[j2], titles[j2]);
      added = todo.length;
    }
    biliSubsHint(added > 0 ? ("已把 " + added + " 集加入缓存队列") : "这些集已经在队列里了");
    try { biliSubsPoll(); } catch (e) {}
    try { biliSubsMarks(); } catch (e) {}
    try { biliJobsRender(); } catch (e) {}
    biliEpAllBusyOff();
    biliEpAllPaint();
    biliEpAllLock = 0;
  });
}
/* 胶囊进入「正在加入」态：两个入口（折叠头 / 全屏抽屉）一起换字 */
function biliEpAllBusy(txt) {
  var els = [document.getElementById("biliEpAll"), document.getElementById("biliEpdAll")];
  for (var i = 0; i < els.length; i++) {
    if (!els[i]) continue;
    els[i].classList.add("busy");
    els[i].textContent = txt;
  }
}
/* 入队回来必须摘掉 busy：留着的话两个入口都会是指针穿透的只读态，
   表现就是「点一次就死、要再点一次」——这不是动画，是状态没收尾。 */
function biliEpAllBusyOff() {
  var els = [document.getElementById("biliEpAll"), document.getElementById("biliEpdAll")];
  for (var i = 0; i < els.length; i++) if (els[i]) els[i].classList.remove("busy");
}
/* 「缓存全集」按钮的状态：全部就绪→已全缓存；有缺口→缓存全集（点了补齐） */
function biliEpAllPaint(q) {
  var els = [document.getElementById("biliEpAll"), document.getElementById("biliEpdAll")];
  if (!els[0] && !els[1]) return;
  var n = (biliCur && biliCur.pages) || 0;
  if (!n) return;
  if (!q) q = biliQueue();
  var m = biliQueueMap(q);
  var ready = 0, busy = 0;
  for (var p = 1; p <= n; p++) {
    var e = m[p];
    if (!e) continue;
    if (e.s === "ready") ready++;
    else if (e.s === "running" || e.s === "queued") busy++;
  }
  var tx = ready >= n ? "已全缓存" : (busy > 0 ? "缓存中 " + (ready + busy) + "/" + n : "缓存全集");
  for (var j = 0; j < els.length; j++) {
    if (!els[j]) continue;
    if (els[j].classList.contains("busy")) continue;   /* 正在入队，别被轮询打断文案 */
    els[j].textContent = tx;
    els[j].classList.toggle("done", ready >= n);
  }
  /* 抽屉开着就顺手刷新各集的进度行 */
  var d = document.getElementById("biliEpDrawer");
  if (d && d.classList.contains("on")) biliEpDrawerListRender(q);
}
/* 进度文案：两位小数；正好 100 时不带小数 */
function biliPctText(p) {
  p = +p || 0;
  if (p >= 99.995) return "100%";
  return p.toFixed(2) + "%";
}
/* 全屏右侧的选集抽屉 */
function biliEpDrawerOpen() {
  var d = document.getElementById("biliEpDrawer");
  if (!d) return;
  biliEpDrawerListRender();
  biliEpAllPaint();
  d.classList.add("on");
}
function biliEpDrawerListRender(q) {
  var list = document.getElementById("biliEpDrawerList");
  if (!list) return;
  var parts = (biliCur && biliCur.parts) || [];
  var cur = (biliCur && biliCur.page) || 0;
  var n = (biliCur && biliCur.pages) || 0;
  if (!q) q = biliQueue();
  var qm = biliQueueMap(q);
  var h = "";
  for (var p = 1; p <= n; p++) {
    var t = (parts[p - 1] && parts[p - 1].t) || ("第 " + p + " 集");
    var qq = qm[p], pct = "";
    if (qq) {
      if (qq.s === "ready") pct = "100%";
      else if (qq.s === "running") pct = (qq.c > 0) ? biliPctText(qq.c) : "处理中";
      else if (qq.s === "queued") pct = "排队中";
    }
    h += '<div class="epd-item' + (p === cur ? " on" : "") +
         '" onclick="event.stopPropagation();biliEpDrawerPick(' + p + ')">' +
         '<b>P' + p + '</b><span>' + esc(t) + '</span>' +
         (pct ? '<i class="epd-pct">' + pct + '</i>' : '') + '</div>';
  }
  list.innerHTML = h;
}
function biliEpDrawerClose() {
  var d = document.getElementById("biliEpDrawer");
  if (d) d.classList.remove("on");
}
function biliEpDrawerPick(p) {
  biliEpDrawerClose();
  biliPlayPage(p);
}
/* 入队统一出口：一并带上视频标题（缓存目录靠它按视频分组）。
   注意取的是 vtitle（视频名），不是 title（收藏夹名），也不是 part（分P名）。
   旧版本没有 subsStartV 时退回三参接口，不至于点了没反应。 */
function biliQueueStart(bvid, page, title) {
  var vt = (biliCur && (biliCur.vtitle || biliTitlesMap()[bvid])) || "";
  try {
    if (Android.subsStartV) Android.subsStartV(bvid, String(page), title, vt);
    else Android.subsStart(bvid, String(page), title);
  } catch (e) {}
}
/* 收藏页顶部的字幕入口：所有状态合成一张卡，点开就是识别队列。
   在识别时给「正在识别哪一集 + 进度」，只在排队时给「还有几集排队」。
   不再并排铺 4 张小卡：那几张信息重复、还很占高度。 */
/* 暂停状态（落盘在 Java 侧，读一次就好，不用每次问队列） */
function biliSubsPausedNow() {
  try { return !!(window.Android && Android.subsPaused && Android.subsPaused() === "1"); }
  catch (e) { return false; }
}
/* 一个按钮管两件事：现在在跑就暂停，暂停中再点就接着跑。
   暂停只停「往下跑」，队列、断点、已下好的音频都留着。 */
function biliSubsTogglePause() {
  var paused = biliSubsPausedNow();
  try {
    if (paused) { if (Android.subsResume) Android.subsResume(); }
    else { if (Android.subsPause) Android.subsPause(); }
  } catch (e) {}
  biliSubsHint(paused ? "已继续识别" : "已暂停识别");
  setTimeout(function () {
    if (biliSubsLast) biliSubsLast.paused = !paused;   /* 轮询回来之前先把本地态翻过来 */
    try { biliSubsPageRender(); } catch (e) {}
    try { biliJobsRender(); } catch (e) {}
  }, 120);
}
function biliJobsRender() {
  var box = document.getElementById("biliJobs");
  if (!box) return;
  var a = biliPendingAll();
  if (!a.length) { box.classList.remove("on"); box.innerHTML = ""; return; }
  var run = null, que = 0, first = null;
  for (var i = 0; i < a.length; i++) {
    if (a[i].s === "running" && !run) run = a[i];
    else if (a[i].s === "queued") {
      que++;
      if (!first) first = a[i];
    }
  }
  var paused = biliSubsPausedNow();
  var pct = run && run.c > 0 ? run.c : 0;
  var sub = [];
  if (paused) {
    /* 标题已经写了「字幕已暂停」，副标题不重复，只交代还剩多少 */
    sub.push(que ? ("还剩 " + que + " 集等待识别") : "队列已空");
  } else {
    if (run) sub.push("P" + (run.p || 1) + " · " + (run.t || ("第 " + run.p + " 集")));
    if (que) sub.push("排队 " + que + " 集");
    if (!sub.length && first) sub.push("P" + (first.p || 1) + " · " + (first.t || ""));
  }
  /* 结构没变就只改数字：重建 DOM 会让脉冲、流光从头播，
     2 秒轮询一次看着就是一直在抽。 */
  var sig = (run ? run.b + ":" + (run.p || 1) : "-") + "|" + que + "|" + (paused ? "p" : "r");
  if (box.dataset.sig === sig) {
    var pe = box.querySelector(".jobs-e-p");
    if (pe) pe.textContent = pct > 0 ? biliPctText(pct) : "";
    var pb = box.querySelector(".jobs-e-bar b");
    if (pb) pb.style.width = Math.max(2, pct) + "%";
    return;
  }
  box.dataset.sig = sig;
  var h = '<div class="jobs-entry' + (paused ? " paused" : "") + '" onclick="biliSubsPageOpen()">' +
            '<div class="jobs-e-top">' +
              '<i class="jobs-e-dot' + (!paused && run ? " run" : "") + '"></i>' +
              '<span class="jobs-e-t">' +
                (paused ? "字幕已暂停" : (run ? "字幕识别中" : "字幕排队中")) + '</span>' +
              (!paused && run && pct > 0 ? '<span class="jobs-e-p">' + biliPctText(pct) + '</span>' : '') +
              (paused ? '<i class="jobs-e-play" onclick="event.stopPropagation();biliSubsTogglePause()">继续</i>' : '<i class="jobs-e-arw">›</i>') +
            '</div>' +
            '<div class="jobs-e-sub">' + esc(sub.join(" · ")) + '</div>' +
            (!paused && run ? '<i class="jobs-e-bar"><b style="width:' + Math.max(2, pct) + '%"></b></i>' : '') +
          '</div>';
  box.innerHTML = h;
  box.classList.add("on");
}

/* ---------------- 字幕识别页 ----------------
   顶部那 4 张小卡只够看一眼「在跑什么」，排队排到几十集之后既看不到明细、
   也没地方取消。点卡片进这一页：上面识别中（带进度），下面排队中（带序号）。
   数据来自桥的 subsCatalog()；条目在队列里本来就带视频标题（vt）。 */
var biliSubsLast = null;

/* bvid → 视频名。队列里的旧条目没有 vt（入队那会儿还没这个字段），目录里总不能
   显示「视频 A6jEEF」——播放过的视频把名字记在本地，越用越准。 */
function biliTitlesMap() {
  try { return JSON.parse(localStorage.getItem("gime.bili.titles") || "{}") || {}; }
  catch (e) { return {}; }
}
function biliTitleRemember(bvid, title) {
  if (!bvid || !title) return;
  try {
    var m = biliTitlesMap();
    if (m[bvid] === title) return;
    m[bvid] = title;
    var ks = Object.keys(m);
    if (ks.length > 300) {                     /* 只留最近写入的 300 条 */
      for (var i = 0; i < ks.length - 300; i++) delete m[ks[i]];
    }
    localStorage.setItem("gime.bili.titles", JSON.stringify(m));
  } catch (e) {}
}
/* 缺标题时不要同步去取：桥是同步调用，一次网络请求就能把整个页面卡住几秒
   （实测能把 CDP 都拖断）。只靠三处已有信息：队列里的 vt、本地记住的名字、
   当前正在播放的视频名。新入队的条目都自带 vt，老队列看过的视频也有记录。 */
function biliCatTitleOf(grp) {
  var m = biliTitlesMap();
  if (grp.vt) return grp.vt;
  if (m[grp.b]) return m[grp.b];
  if (biliCur && biliCur.bvid === grp.b && biliCur.vtitle) return biliCur.vtitle;
  return "";
}
/* 老队列的条目没存视频名：如果队列里就有当前正在看的这部视频，把名字回填进队列
   （SubsJobService 收到已存在条目时只补 vt，不会把 running 降级、也不会重排任务）。
   这样下次打开目录就是真名，不用等用户重新入队。 */
var biliCatVtFilled = {};
function biliCatBackfillVt(grp) {
  var name = (biliCur && biliCur.bvid === grp.b) ? (biliCur.vtitle || "") : "";
  if (!name || grp.vt || biliCatVtFilled[grp.b]) return;
  biliCatVtFilled[grp.b] = 1;
  var first = grp.items[0];
  try {
    Android.subsStartV(grp.b, String((first && first.p) || 1), (first && first.t) || "", name);
  } catch (e) {}
}

function biliSubsCatalogData() {
  try {
    if (!window.Android || !Android.subsCatalog) return null;
    var d = JSON.parse(Android.subsCatalog() || "{}");
    return (d && d.items) ? d : null;
  } catch (e) { return null; }
}
/* ---------- 排队项左滑 ----------
   每一项是一张独立卡，左滑把卡面推到一边，露出「插队识别 / 取消排队」。
   动作区宽度写死在 BILI_QW，改动要跟 style.css 里的 152px 一起改。 */
var BILI_QW = 158;
var biliSubsOpenKey = "";      /* 当前展开的那一项；重绘后据此还原 */

function biliSubsSwipeBind(p) {
  if (!p || p.dataset.swipe === "1") return;
  p.dataset.swipe = "1";
  var st = { card: null, x0: 0, y0: 0, base: 0, dx: 0, lock: false };
  function face(c) { return c ? c.querySelector(".sp-q-face") : null; }
  function closeOthers(keep) {
    var a = p.querySelectorAll(".sp-q-card.open");
    for (var i = 0; i < a.length; i++) if (a[i] !== keep) a[i].classList.remove("open");
  }

  p.addEventListener("touchstart", function (e) {
    var t = e.touches[0];
    if (!t) return;
    var card = e.target.closest ? e.target.closest(".sp-q-card") : null;
    if (!card) { st.card = null; return; }
    var wasOpen = card.classList.contains("open");
    st.card = card;
    st.x0 = t.clientX;
    st.y0 = t.clientY;
    st.base = wasOpen ? -BILI_QW : 0;
    st.dx = st.base;
    st.lock = false;
    var f = face(card);
    if (f) f.style.transition = "none";       /* 跟手时不带缓动 */
  }, { passive: true });

  p.addEventListener("touchmove", function (e) {
    if (!st.card) return;
    var t = e.touches[0];
    if (!t) return;
    var mx = t.clientX - st.x0, my = t.clientY - st.y0;
    if (!st.lock) {
      if (Math.abs(mx) < 8 && Math.abs(my) < 8) return;
      if (Math.abs(my) >= Math.abs(mx)) {
        /* 竖着划：这是滚动列表，别抢手势 */
        var f0 = face(st.card);
        if (f0) f0.style.transition = "";
        st.card = null;
        return;
      }
      st.lock = true;
      closeOthers(st.card);
    }
    var x = st.base + mx;
    if (x > 0) x = 0;
    if (x < -BILI_QW) {                        /* 拉到头给一点阻尼，不是硬墙 */
      x = -BILI_QW - Math.min(14, (-x - BILI_QW) * 0.25);
    }
    st.dx = x;
    var f = face(st.card);
    if (f) f.style.transform = "translateX(" + x + "px)";
    if (e.cancelable) e.preventDefault();
  }, { passive: false });

  function settle() {
    var card = st.card;
    st.card = null;
    if (!card) return;
    var f = face(card);
    /* 过了 45% 就停在展开位，否则回弹 */
    var openNow = st.dx < -BILI_QW * 0.45;
    if (f) { f.style.transition = ""; f.style.transform = ""; }
    card.classList.toggle("open", openNow);
    biliSubsOpenKey = openNow ? (card.getAttribute("data-k") || "") : "";
  }
  p.addEventListener("touchend", settle, { passive: true });
  p.addEventListener("touchcancel", settle, { passive: true });

  /* 点已展开那一项的正文 = 收起；点动作按钮就交给它自己的 onclick */
  p.addEventListener("click", function (e) {
    var card = e.target.closest ? e.target.closest(".sp-q-card") : null;
    if (!card || (e.target.closest && e.target.closest(".sp-q-acts"))) return;
    if (card.classList.contains("open")) {
      card.classList.remove("open");
      biliSubsOpenKey = "";
    }
  });
}

function biliSubsPageEnsure() {
  if (document.getElementById("biliSubsPage")) return;
  var pg = document.createElement("div");
  pg.id = "biliSubsPage";
  pg.className = "sp-page";
  document.body.appendChild(pg);   /* 挂 body：上级若有 transform，fixed 会跟着跑偏 */
  biliSubsSwipeBind(pg);
}
function biliSubsPageOpen() {
  biliSubsPageEnsure();
  var p = document.getElementById("biliSubsPage");
  if (!p) return;
  p.classList.add("on");
  p.scrollTop = 0;
  biliSubsPageRender();
}
function biliSubsPageClose() {
  var p = document.getElementById("biliSubsPage");
  if (p) p.classList.remove("on");
}
/* 取消 / 全部取消：页面和顶部小卡共用，两边都要跟着重画 */
function biliSubsPageCancel(b, p) {
  try { Android.subsCancel(b, String(p)); } catch (e) {}
  biliSubsHint("已从字幕队列移除");
  biliSubsOpenKey = "";
  biliSubsPageRender();
  try { biliJobsRender(); } catch (e) {}
}
/* 插队识别：把这一集提到队首，正在跑的那一集在段边界收尾让位，然后立刻跑它。
   进度不丢——被让位的那一集退回排队，断点和已下好的音频都留着。 */
function biliSubsPageJump(b, p) {
  try {
    if (Android.subsJump) Android.subsJump(b, String(p));
  } catch (e) {}
  biliSubsHint("已插队：当前这一集收尾后就开跑");
  biliSubsOpenKey = "";
  setTimeout(function () { biliSubsPageRender(); try { biliJobsRender(); } catch (e) {} }, 300);
}
function biliSubsPageCancelAll() {
  var d = biliSubsLast, n = 0;
  if (!d) return;
  for (var i = 0; i < d.items.length; i++) {
    if (d.items[i].s !== "queued") continue;
    try { Android.subsCancel(d.items[i].b, String(d.items[i].p)); n++; } catch (e) {}
  }
  if (n) biliSubsHint("已取消 " + n + " 集排队");
  biliSubsPageRender();
  try { biliJobsRender(); } catch (e) {}
}
/* 运行卡状态文案：解码那几秒原来也写「模型识别中」，进度条不动就像卡死；
   现在按桥给的阶段字段切文案，让用户看见它在干活。暂停是软停：Rust 认完
   当前这一段（≤2 分钟音频）落好断点才停，所以文案如实说「正在收尾」。 */
function biliRunLbl(it, paused) {
  if (paused) return "正在收尾 · 这一段认完就停";
  return (it && it.g === "decode") ? "解码音频" : "模型识别中";
}
function biliSubsPageRender() {
  var p = document.getElementById("biliSubsPage");
  if (!p || !p.classList.contains("on")) return;
  var d = biliSubsCatalogData();
  if (!d) return;                        /* 桥还没就绪：保留上一次画面 */
  biliSubsLast = d;

  var run = [], que = [];
  for (var i = 0; i < d.items.length; i++) {
    var it = d.items[i];
    if (it.s === "running") run.push(it);
    else if (it.s === "queued") que.push(it);
  }
  var paused = d.paused === true || (biliSubsLast && biliSubsLast.paused === true && d.paused !== false);
  /* 整体进度：已落盘（tracks）+ 在跑 + 排队 = 全部。
     注意 items 只装 running/queued，已完成的条目桥那边只报数量。 */
  var readyN = d.tracks || 0;
  var totalN = readyN + run.length + que.length;
  var allDone = !paused && !run.length && !que.length;
  var sum = [];
  if (paused) {
    /* 数字都在下面的暂停面板里，头部只点一句状态，避免同屏三处重复 */
    sum.push("已暂停");
  } else {
    if (run.length) sum.push("识别中 " + run.length);
    if (que.length) sum.push("排队 " + que.length);
    if (d.tracks) sum.push("已完成 " + d.tracks);
  }
  var sumStr = sum.join(" · ") || "暂无任务";
  if (allDone) sumStr = readyN ? ("已全部完成 " + readyN) : "空闲";

  var runKey = "";
  for (var rk = 0; rk < run.length; rk++) runKey += run[rk].b + ":" + (run[rk].p || 1) + ";";
  var sig = runKey + "|" + que.length + "|" + (d.tracks || 0) + "|" + (paused ? "p" : "r");

  /* 结构没变就只刷数字和条宽：整页重建会让声波、流光、呼吸框从头播一遍，
     2 秒轮询一次等于一直在抽。 */
  if (p.dataset.sig === sig) {
    var se = p.querySelector(".sp-sum");
    if (se) se.textContent = sumStr;
    for (var rl = 0; rl < run.length; rl++) {
      var key2 = run[rl].b + "-" + (run[rl].p || 1);
      var cell = p.querySelector('.sp-run[data-k="' + key2 + '"]');
      if (!cell) continue;
      var lb = cell.querySelector(".sp-run-lbl");
      if (lb) {
        var lt = biliRunLbl(run[rl], paused);
        if (lb.textContent !== lt) lb.textContent = lt;
      }
      var lab = cell.querySelector(".sp-run-p");
      if (lab) {
        var txt = run[rl].c > 0 ? biliPctText(run[rl].c) : "处理中";
        if (lab.textContent !== txt) {     /* 变化时才闪一下，静止不闪 */
          lab.textContent = txt;
          lab.classList.remove("flash");
          void lab.offsetWidth;
          lab.classList.add("flash");
        }
      }
      var bw = cell.querySelector(".sp-run-bar b");
      if (bw) bw.style.width = Math.max(2, run[rl].c || 0) + "%";
    }
    return;
  }
  p.dataset.sig = sig;

  /* 重绘会换掉 innerHTML，先把滚动位置记住，否则用户滚到中间会跳回顶部 */
  var oldBody = p.querySelector(".sp-body");
  var keep = oldBody ? oldBody.scrollTop : 0;

  var h = '<div class="sp-hd">' +
            '<i class="sp-back" onclick="biliSubsPageClose()">‹</i>' +
            '<span class="sp-tt">字幕识别</span>' +
            '<span class="sp-sum">' + sumStr + '</span>' +
          '</div><div class="sp-body">';

  if (allDone) {
    /* 没有待识别的视频了：整页换一句空态。原来这里会并排出现
       「现在没有正在识别的视频」+「没有排队等待的视频」，看着像坏了。 */
    h += '<div class="sp-done-all">' +
           '<i class="sp-da-ic">✓</i>' +
           '<b>目前没有视频需要识别哦</b>' +
           '<small>' + (readyN ? ('已缓存 ' + readyN + ' 集字幕，播放时直接使用')
                               : '在选集里点「缓存」就能加入识别队列') + '</small>' +
         '</div>';
  } else {
    /* 整体进度条：已完成 / 全部，给「还要等多久」一个直观答案 */
    if (totalN > 0) {
      var allPct = Math.round(readyN * 100 / totalN);
      h += '<div class="sp-prog">' +
             '<div class="sp-prog-top"><span>识别进度</span><b>' + readyN + ' / ' + totalN +
               ' · ' + allPct + '%</b></div>' +
             '<i class="sp-prog-bar"><b style="width:' + (readyN * 100 / totalN) + '%"></b></i>' +
           '</div>';
    }

  /* ---- 识别队列：运行态一行标题，暂停/继续的按钮放在运行卡里（见下） ---- */
  h += '<div class="sp-sec"><div class="sp-sec-t"><span>' + (paused ? "识别队列" : "识别中") + '</span>' +
       (run.length ? '<small>' + run.length + '</small>' : '') +
       '</div>';
  if (paused) {
    /* 暂停不能只是一行了事：大标题 + 徽章 + 下一集 + 整宽主按钮，
       一眼就能看出「停了」并且知道点哪儿继续。 */
    var nx = que.length ? que[0] : null;
    var cnt = que.length ? ("还剩 " + que.length + " 集在排队")
                         : (d.tracks ? "队列已清空" : "队列是空的");
    if (d.tracks) cnt += " · 已完成 " + d.tracks;
    h += '<div class="sp-pz">' +
           '<div class="sp-pz-hd">' +
             '<i class="sp-pz-ic"></i>' +
             '<div class="sp-pz-l"><b>已暂停识别</b><small>' + cnt + '</small></div>' +
           '</div>' +
           (nx ? ('<div class="sp-pz-nx">下一集 <em>P' + (nx.p || 1) + '</em> · ' +
                  esc(nx.t || ("第 " + nx.p + " 集")) + '</div>') : '') +
           '<div class="sp-pz-tip">进度和断点都留着，继续就从断点接着跑</div>' +
           '<div class="sp-pz-btn" onclick="biliSubsTogglePause()">' +
             '<i class="sp-pz-play"></i>继续识别</div>' +
         '</div>';
  } else if (!run.length) {
    h += '<div class="sp-empty">现在没有正在识别的视频</div>';
  }
  for (var r = 0; r < run.length; r++) {
    var ri = run[r], pct = ri.c > 0 ? ri.c : 0;
    var rvt = biliCatTitleOf({ b: ri.b, vt: ri.vt });
    var rkey = ri.b + "-" + (ri.p || 1);
    h += '<div class="sp-run' + (paused ? " hold" : "") + '" data-k="' + esc(rkey) + '">' +
           '<div class="sp-run-top">' +
             '<i class="sp-wave"><i></i><i></i><i></i><i></i><i></i></i>' +
             '<span class="sp-run-lbl">' + biliRunLbl(ri, paused) + '</span>' +
             '<span class="sp-run-p">' + (pct > 0 ? biliPctText(pct) : "处理中") + '</span>' +
           '</div>' +
           '<div class="sp-run-t">P' + (ri.p || 1) + ' · ' +
             esc(ri.t || ("第 " + ri.p + " 集")) + '</div>' +
           (rvt ? '<div class="sp-run-sub">' + esc(rvt) + '</div>' : '') +
           '<i class="sp-run-bar"><b style="width:' + Math.max(2, pct) + '%"></b></i>' +
           '<div class="sp-run-foot">' +
             '<i class="sp-run-pz" onclick="biliSubsTogglePause()">' +
               '<i class="sp-run-pz-ic' + (paused ? " play" : "") + '"></i>' +
               (paused ? "继续识别" : "暂停识别") + '</i>' +
             '<i class="sp-run-x" onclick="biliSubsPageCancel(\'' +
               esc(ri.b) + '\',' + (ri.p || 1) + ')">取消识别</i>' +
           '</div>' +
         '</div>';
    if (!ri.vt) biliCatBackfillVt({ b: ri.b, vt: ri.vt, items: [ri] });
  }
  h += '</div>';

  /* ---- 排队中 ---- */
  h += '<div class="sp-sec"><div class="sp-sec-t"><span>排队中</span>' +
       (que.length ? '<small>' + que.length + '</small>' : '') + '</div>';
  if (!que.length) {
    h += '<div class="sp-empty">没有排队等待的视频</div>';
  } else {
    h += '<div class="sp-q-list">';
    /* 第 42 轮：140 条排队里每张卡都复读同一行来源，长列表里那行只剩纹理噪声。
       同一条来源只在这一段的第一项上写一次，后面共享同一来源的条目直接省掉。 */
    var lastVt = "";
    for (var q = 0; q < que.length; q++) {
      var qi = que[q];
      /* 来源：老队列条目没存 vt，就靠 localStorage 标题表 / 当前播放兜底；
         实在拿不到就退回 bvid 本身，别让某一行空着（不然一行有来源一行没有，看着像漏了）。 */
      var qvt = biliCatTitleOf({ b: qi.b, vt: qi.vt });
      if (!qvt) qvt = "视频 " + qi.b;
      var showSrc = (qvt !== lastVt);
      lastVt = qvt;
      var qk = qi.b + "-" + (qi.p || 1);
      var qpct = qi.c > 0 ? biliPctText(qi.c) : "";
      /* 每项一张独立卡：左滑露出「插队识别 / 取消排队」两个专用键。
         动作层压在卡下面，face 往左平移把动作层掀出来。
         键内文字竖排两行（2 字一行），窄按钮里也不会溢出。 */
      h += '<div class="sp-q-card" data-k="' + esc(qk) + '">' +
             '<div class="sp-q-acts">' +
               '<i class="sp-q-act jump" onclick="biliSubsPageJump(\'' + esc(qi.b) + '\',' +
                 (qi.p || 1) + ')"><b>↑</b><em>插队</em><em>识别</em></i>' +
               '<i class="sp-q-act del" onclick="biliSubsPageCancel(\'' + esc(qi.b) + '\',' +
                 (qi.p || 1) + ')"><b>✕</b><em>取消</em><em>排队</em></i>' +
             '</div>' +
             '<div class="sp-q-face">' +
               '<div class="sp-q-l">' +
                 '<div class="sp-q-row"><b class="sp-q-n">' + (q + 1) + '</b>' +
                   '<span>P' + (qi.p || 1) + ' · ' +
                   esc(qi.t || ("第 " + qi.p + " 集")) + '</span></div>' +
                 '<div class="sp-q-sub">' +
                   (showSrc ? '<small>' + esc(qvt) + '</small>' : '') +
                   (qpct ? '<i class="sp-q-pct">已识别 ' + qpct + '</i>' : '') +
                 '</div>' +
               '</div>' +
             '</div>' +
           '</div>';
    }
    h += '</div>';
    h += '<div class="sp-all" onclick="biliSubsPageCancelAll()">全部取消排队 · ' +
         que.length + ' 集</div>';
  }
  h += '</div>';

  /* ---- 已完成 ---- */
  if (d.tracks) {
    h += '<div class="sp-sec"><div class="sp-sec-t"><span>已完成</span><small>' +
         d.tracks + '</small></div>' +
         '<div class="sp-note">已落盘的字幕在播放页直接用，不用再识别。</div></div>';
  }
  }                                          /* ← else（allDone 分支）到此结束 */
  h += '</div>';                        /* sp-body */

  p.innerHTML = h;
  var nb = p.querySelector(".sp-body");
  if (nb && keep) nb.scrollTop = keep;
  /* 2 秒轮询会重建整页：把左滑展开的那一项还原，不然刚划开就自己弹回去 */
  if (biliSubsOpenKey) {
    var oc = p.querySelector('.sp-q-card[data-k="' + biliSubsOpenKey + '"]');
    if (oc) oc.classList.add("open");
    else biliSubsOpenKey = "";
  }
}
var biliJobsTimer = 0;
function biliJobsPoll() {
  if (biliJobsTimer) return;
  biliJobsTimer = setInterval(function () {
    var box = document.getElementById("biliJobs");
    if (!box) { clearInterval(biliJobsTimer); biliJobsTimer = 0; return; }
    biliJobsRender();
    /* 识别页开着就跟着刷：它是用户正盯着看的那份清单 */
    try {
      var sp = document.getElementById("biliSubsPage");
      if (sp && sp.classList.contains("on")) biliSubsPageRender();
    } catch (e) {}
  }, 2000);
  biliJobsRender();
}
/* 把生成进度挂到对应的选集长条上：生成中显示百分比，排队显示「排队」；完成后清空 */
function biliSubsMarks(q) {
  var rows = document.querySelectorAll("#biliList .bili-ep[data-p]");
  if (!rows.length) return;
  if (!q) q = biliQueue();
  var map = biliQueueMap(q);
  for (var j = 0; j < rows.length; j++) {
    var b = rows[j];
    var p = parseInt(b.getAttribute("data-p"), 10) || 0;
    var m = map[p];
    var el = b.querySelector(".ep-pct");
    var run = !!(m && m.s === "running");
    var que = !!(m && m.s === "queued");
    b.classList.toggle("pg-run", run);
    b.classList.toggle("pg-que", que);
    if (el) {
      if (run) el.textContent = "字幕 " + ((m.c > 0) ? biliPctText(m.c) : "处理中");
      else if (que) el.textContent = "排队中";
      /* 第 44 轮：已就绪原来写死 "100%"，正好排在观看进度右边 ——
         两个百分比并排（"看到 2%  100%"），谁也说明不了自己是什么。
         改成正在播那一行给一个小 "CC"（跟播放器里的字幕按钮同一个词），
         其余已就绪的集整列留空：它们右侧的「缓存」按钮已经隐藏，本身就说明有字幕了。 */
      else if (m && m.s === "ready") el.textContent = b.classList.contains("on") ? "CC" : "";
      else el.textContent = "";
    }
    var act = b.querySelector(".ep-act");
    if (act) {
      if (m && m.s === "ready") { act.textContent = ""; act.style.display = "none"; }
      else if (run || que) { act.textContent = "取消"; act.style.display = ""; }
      else { act.textContent = "缓存"; act.style.display = ""; }
    }
  }
  biliEpAllPaint(q);
}

/** 每 2 秒刷新一次：分P进度徽标 + 当前集进度条 + 完成后切轨 */
function biliSubsPoll() {
  biliSubsStopPoll();
  var announced = false;
  biliSubsTimer = setInterval(function () {
    var q = biliQueue();
    biliSubsMarks(q);
    var st = biliSubsStateNow();
    if (st.state === "ready") {
      if (!announced && biliSubsLoadTrack()) {
        announced = true;
        biliTrackIdx = -1; biliCuePage = -1;
        if (biliSubOn) {
          var v = document.getElementById("biliVideo");
          biliSubsSync(v ? v.currentTime : 0);
          biliSubsHint("字幕君生成好啦！");
        }
      }
    }
    var busy = biliQueuePending(q) || st.state === "running" || st.state === "queued";
    if (!busy) biliSubsStopPoll();
  }, 2000);
  var q0 = biliQueue();
  biliSubsMarks(q0);
}

/** 打开 CC（或换集后仍开着）时调用：有轨直接播，没轨就起任务并提示。 */
/* 字幕状态提示：全屏时提示要跟着画面旋转、并且别挡住画面中间，
   所以放在播放器层内部（中央上方），1 秒后自动淡出；小窗仍用全局 toast。 */
var biliHintT = 0;
function biliHint(msg, ms) {
  var el = document.getElementById("biliFsHint");
  if (!el) { toast(msg); return; }
  el.textContent = msg;
  el.classList.add("on");
  if (biliHintT) { clearTimeout(biliHintT); biliHintT = 0; }
  biliHintT = setTimeout(function () {
    biliHintT = 0;
    var e2 = document.getElementById("biliFsHint");
    if (e2) e2.classList.remove("on");
  }, ms || 1000);
}
function biliSubsHint(msg) {
  if (biliIsFull()) biliHint(msg); else toast(msg);
}

/* 字幕来源：local = 本地 X-ASR 生成；official = 视频上游的官方 AI 字幕 */
function subsMode() {
  try { return localStorage.getItem("gime.subs.mode") === "official" ? "official" : "local"; } catch (e) { return "local"; }
}
function subsModeSet(m) {
  try { localStorage.setItem("gime.subs.mode", m === "official" ? "official" : "local"); } catch (e) {}
}
var biliOffPending = "";
function biliOfficialLoad() {
  var k = biliSubsIds();
  if (!k.bvid) return;
  var key = k.bvid + ":" + k.page;
  if (biliTrack && biliTrackKey === key + ":off") {
    var v0 = document.getElementById("biliVideo");
    biliSubsSync(v0 ? v0.currentTime : 0);
    return;
  }
  if (biliOffPending === key) return;
  biliOffPending = key;
  biliTrack = null; biliTrackKey = ""; biliTrackIdx = -1; biliCuePage = -1;
  var cap = document.getElementById("biliCap");
  if (cap) cap.style.display = "none";
  biliSubsHint("正在获取官方字幕…");
  try { Android.biliOfficialSubs(k.bvid, String(k.page)); }
  catch (e) { biliOffPending = ""; toast("获取官方字幕失败"); }
}
window.onOfficialSubs = function (r) {
  var pending = biliOffPending;
  biliOffPending = "";
  /* 请求回来时用户可能已关字幕或切到本地：这份上游轨不能覆盖当前状态 */
  if (subsMode() !== "official" || !biliSubOn) return;
  var k0 = biliSubsIds();
  if (pending && pending !== k0.bvid + ":" + k0.page) return;
  if (!r || !r.ok) { toast(r && r.error ? r.error : "这条视频没有上游字幕"); return; }
  var segs = biliTrackNormalize(r.segs || []);
  if (!segs.length) { toast("这条视频没有上游字幕"); return; }
  var k = biliSubsIds();
  biliTrack = segs;
  biliTrackKey = k.bvid + ":" + k.page + ":off";
  biliTrackIdx = -1; biliCuePage = -1;
  var v = document.getElementById("biliVideo");
  biliSubsSync(v ? v.currentTime : 0);
  biliSubsHint("上游字幕已加载");
};

function biliSubsOn() {
  var k = biliSubsIds();
  if (!k.bvid) { toast("先打开一个视频"); return; }
  biliSubsAttach();
  if (subsMode() === "official") { biliOfficialLoad(); return; }
  var st = biliSubsStateNow();
  if (st.state === "ready" && biliSubsLoadTrack()) {
    biliTrackIdx = -1;
    /* 切到「有轨」这一侧时把实时采集停掉，避免两套字幕同时写 #bcapCur。 */
    try { biliSubDetach(); } catch (e) {}
    var v0 = document.getElementById("biliVideo");
    biliSubsSync(v0 ? v0.currentTime : 0);
    return;
  }
  /* 识别/缓存期间不上屏字幕：拆掉实时采集，字幕框收起，只留进度 */
  try { biliSubDetach(); } catch (e) {}
  var capG = document.getElementById("biliCap");
  if (capG) capG.style.display = "none";
  if (st.state === "none" && window.Android && Android.subsStart) {
    biliQueueStart(k.bvid, k.page, biliSubsTitle());
    biliSubsHint("字幕君开始生成啦，生成完会自动显示");
    biliSubsPoll();
    return;
  }
  biliSubsHint("字幕君正在努力生成中哦！");
  biliSubsPoll();
}

/** 换集：丢掉上一集的轨；有排队任务就继续轮询进度（进度只显示在分P/卡片上）。 */
function biliSubsOnPage() {
  biliTrack = null; biliTrackKey = ""; biliTrackIdx = -1;
  biliSubsStopPoll();
  var st = biliSubsStateNow();
  if (st.state === "running" || st.state === "queued") biliSubsPoll();
  if (biliSubOn) biliSubsOn();
}

function biliSubMenuTap(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  var m = document.getElementById("biliSubMenu");
  if (!m) return;
  if (m.classList.contains("show")) { biliSubMenuClose(); return; }
  biliRateClose();
  biliSubMenuSync();
  /* 内嵌播放器很矮时藏掉说明行：按钮文案已能说明来源，弹窗也才贴得住 CC */
  var p = document.querySelector(".bili-player");
  m.classList.toggle("short", !!(p && p.clientHeight < 190));
  m.classList.add("show");
  biliSubMenuPlace();
  biliShowCtl();
  /* 弹窗开着时别让控制条自动缩回去（关弹窗时再恢复计时） */
  if (biliHideT) { clearTimeout(biliHideT); biliHideT = null; }
}
/* 贴着 CC 按钮摆：底边距按钮 8px，水平以按钮中心居中，超出播放器则夹住 */
function biliSubMenuPlace() {
  var m = document.getElementById("biliSubMenu");
  var p = document.querySelector(".bili-player");
  var cc = document.getElementById("biliCcBtn");
  if (!m || !p || !cc || !m.classList.contains("show")) return;
  var x = 0, y = 0, el = cc;
  while (el && el !== p) { x += el.offsetLeft; y += el.offsetTop; el = el.offsetParent; }
  if (el !== p) {
    var pr = p.getBoundingClientRect(), cr = cc.getBoundingClientRect();
    x = cr.left - pr.left; y = cr.top - pr.top;
  }
  var mw = m.offsetWidth, mh = m.offsetHeight;
  var left = x + cc.offsetWidth / 2 - mw / 2;
  left = Math.max(8, Math.min(p.clientWidth - mw - 8, left));
  var below = p.clientHeight - y + 8;
  var maxBelow = p.clientHeight - mh - 8;
  if (below > maxBelow) below = Math.max(6, maxBelow);
  m.style.left = Math.round(left) + "px";
  m.style.right = "auto";
  m.style.bottom = Math.round(below) + "px";
  m.style.top = "auto";
  var arrow = Math.max(14, Math.min(mw - 14, x + cc.offsetWidth / 2 - left));
  m.style.setProperty("--arrow", Math.round(arrow) + "px");
}
window.addEventListener("resize", function () {
  var m = document.getElementById("biliSubMenu");
  if (m && m.classList.contains("show")) biliSubMenuPlace();
});
function biliSubMenuClose() {
  var m = document.getElementById("biliSubMenu");
  if (!m || !m.classList.contains("show")) return;
  m.classList.remove("show");
  biliShowCtl();
}
function biliSubMenuSync() {
  var sw = document.getElementById("biliSubSwitch");
  if (sw) {
    sw.classList.toggle("on", biliSubOn);
    sw.setAttribute("aria-checked", biliSubOn ? "true" : "false");
  }
  var m = subsMode();
  var l = document.getElementById("biliSrcLocal");
  var o = document.getElementById("biliSrcOfficial");
  if (l) l.classList.toggle("on", m === "local");
  if (o) o.classList.toggle("on", m === "official");
  var tip = document.getElementById("biliSubTip");
  if (tip) tip.textContent = m === "official" ? "视频自带字幕，免生成" : "离线生成，首次需等待";
}
function biliSubMasterTap(ev) {
  if (ev && ev.stopPropagation) ev.stopPropagation();
  biliSubSetOn(!biliSubOn);
}
function biliSubClearCue() {
  try { biliCueStop(); } catch (e) {}
  var cap = document.getElementById("biliCap");
  if (!cap) return;
  var capCur0 = document.getElementById("bcapCur");
  cap.style.display = "none";
  if (bcapClearTimer) { clearTimeout(bcapClearTimer); bcapClearTimer = 0; }
  if (bcapPumpTimer) { clearTimeout(bcapPumpTimer); bcapPumpTimer = 0; }
  if (capCur0) capCur0.textContent = ""; else cap.textContent = "";
  bcapCurText = ""; bcapLastRaw = ""; bcapFresh = 0;
  bcapQueue = ""; bcapFinPending = false;
}
function biliSubSetOn(on) {
  biliSubOn = !!on;
  var b = document.getElementById("biliCcBtn");
  if (b) b.classList.toggle("on", biliSubOn);
  biliSubMenuSync();
  var cap = document.getElementById("biliCap");
  if (!biliSubOn) {
    biliSubClearCue();
    biliSubsStopPoll();
    return;
  }
  if (cap) cap.style.display = biliIsFull() ? "" : "none";
  biliSubsOn();
}
function biliSubModePick(mode) {
  var m = mode === "official" ? "official" : "local";
  if (subsMode() === m) { biliSubMenuSync(); return; }
  subsModeSet(m);
  biliSubMenuSync();
  if (!biliSubOn) return;
  /* 切来源：上一套轨、挂起中的请求、采集节点全部作废，再按新来源重挂 */
  biliOffPending = "";
  biliTrack = null; biliTrackKey = ""; biliTrackIdx = -1; biliCuePage = -1;
  try { biliSubDetach(); } catch (e) {}
  biliSubsStopPoll();
  biliSubClearCue();
  var cap = document.getElementById("biliCap");
  if (cap) cap.style.display = biliIsFull() ? "" : "none";
  biliSubsOn();
}
function biliSubMenuOutside(ev) {
  var m = document.getElementById("biliSubMenu");
  if (!m || !m.classList.contains("show")) return;
  var t = ev.target;
  if (m.contains(t)) return;
  var cc = document.getElementById("biliCcBtn");
  if (cc && (cc === t || cc.contains(t))) return;
  biliSubMenuClose();
}
if (!window.__biliSubOutsideHooked) {
  window.__biliSubOutsideHooked = true;
  document.addEventListener("pointerdown", biliSubMenuOutside, true);
}
/* 单行逐句字幕：只显示当前这一句，一行放不下的部分从左侧滚走；
   识别引擎每次回的是「当前整句的累积文本」而非增量，按前缀差算出新吐出的字，
   新字淡入 + 末尾光标；一句话说完（final）后整行清空，下一句从空行重新开始。 */
var bcapCurText = "", bcapLastRaw = "", bcapFresh = 0, bcapClearTimer = 0;
var bcapQueue = "", bcapPumpTimer = 0, bcapFinPending = false, bcapAcc = 0, bcapLastT = 0;
var BCAP_MAX = 20;      /* 单行容量：约 20 个汉字，超出部分从左侧滚走 */
var BCAP_HOLD = 900;    /* 断句后停留 0.9 秒再清屏，留时间读完最后一帧 */
var BCAP_TICK = 12;     /* 打字机速度：每 12ms 吐一个字（约 83 字/秒，一句 20 字 0.24s 打完） */
/* 把当前已吐出的字渲染出来：末尾 bcapFresh 个字用 .bcap-new 淡入，再跟一个闪烁光标 */
function bcapRender(done) {
  var cur = document.getElementById("bcapCur");
  if (!cur) return;
  var cut = Math.max(0, bcapCurText.length - bcapFresh);
  var head = bcapCurText.slice(0, cut), tail = bcapCurText.slice(cut);
  cur.textContent = "";
  if (head) cur.appendChild(document.createTextNode(head));
  if (tail) {
    var s = document.createElement("span");
    s.className = "bcap-new"; s.textContent = tail;
    cur.appendChild(s);
  }
  var caret = document.createElement("span");
  caret.className = "bcap-caret";
  cur.appendChild(caret);
  var cap = document.getElementById("biliCap");
  if (cap) cap.classList.toggle("partial", !done);
}
/* 打字机泵：按「已过去多少毫秒」折算这次该吐几个字。WebView 主线程一忙，
   setTimeout 常被延迟到 40–80ms，光靠固定间隔会忽快忽慢；用时间补偿（欠账）
   就能保证平均速度严格是 BCAP_TICK 毫秒/字，卡顿后一次补 2–3 个字。 */
function bcapPump() {
  bcapPumpTimer = 0;
  if (!biliSubOn) return;
  if (!bcapQueue.length) {
    bcapAcc = 0; bcapLastT = 0;
    if (bcapFinPending) { bcapFinPending = false; bcapFinalize(); }
    return;
  }
  var now = Date.now();
  if (!bcapLastT) bcapLastT = now;
  bcapAcc += (now - bcapLastT);
  bcapLastT = now;
  var n = Math.floor(bcapAcc / BCAP_TICK);
  if (n < 1) { bcapPumpTimer = setTimeout(bcapPump, Math.max(8, Math.ceil(BCAP_TICK - bcapAcc))); return; }
  bcapAcc -= n * BCAP_TICK;
  if (n > bcapQueue.length) n = bcapQueue.length;
  var ch = bcapQueue.slice(0, n);
  bcapQueue = bcapQueue.slice(n);
  bcapCurText = (bcapCurText + ch).slice(-BCAP_MAX);
  bcapFresh = Math.min(ch.length, bcapCurText.length);
  bcapRender(false);
  bcapPumpTimer = setTimeout(bcapPump, Math.max(8, Math.ceil(BCAP_TICK - bcapAcc)));
}
/* 队列吐完且已收到 final：去掉 partial 与光标，停留片刻后整行消失 */
function bcapFinalize() {
  bcapFresh = 0;
  bcapLastRaw = "";
  bcapRender(true);
  if (bcapClearTimer) clearTimeout(bcapClearTimer);
  bcapClearTimer = setTimeout(function () { bcapClearTimer = 0; bcapClearNow(); }, BCAP_HOLD);
}
function bcapClearNow() {
  bcapCurText = ""; bcapLastRaw = ""; bcapFresh = 0;
  bcapQueue = ""; bcapFinPending = false; bcapAcc = 0; bcapLastT = 0;
  if (bcapPumpTimer) { clearTimeout(bcapPumpTimer); bcapPumpTimer = 0; }
  var cur = document.getElementById("bcapCur");
  if (cur) cur.textContent = "";
  var cap = document.getElementById("biliCap");
  if (cap) cap.classList.remove("partial");
}
window.giCaption = function (o) {
  var cap = document.getElementById("biliCap");
  if (!cap || !biliSubOn) return;
  /* 没有成片轨（识别/缓存期）就不上屏字幕 */
  if (!biliTrack) { cap.style.display = "none"; return; }
  /* 设定：小窗状态（内嵌播放器）不出字幕；只在大屏全屏里显示 */
  if (!biliIsFull()) { cap.style.display = "none"; return; }
  /* 诊断探针：字幕到达时刻的视频位置 vs 已送入音频累计秒数，用于量化端到端滞后 */
  try {
    window.__lag = window.__lag || [];
    window.__lag.push({ t: Date.now(), ct: biliSubEl ? +biliSubEl.currentTime.toFixed(2) : -1, sent: +(window.__sentSec || 0).toFixed(2), fin: !!(o && o.final) });
    if (window.__lag.length > 200) window.__lag.shift();
  } catch (e) {}
  cap.style.display = "";   /* 播放器重建后的兜底 */
  if (!o || !o.text) return;
  var cur = document.getElementById("bcapCur");
  /* 恢复中文连写；<unk> 是解码占位符；标点只用来断句，不上屏 */
  var t = String(o.text).replace(/\s+/g, "").replace(/<unk>/g, "").replace(BILI_PUNCT_G, "");
  if (!cur) {
    /* 自愈：任何地方用 cap.textContent 写状态都会清掉子节点，这里重建回来 */
    cap.innerHTML = '<div class="bcap-cur" id="bcapCur"></div>';
    cur = document.getElementById("bcapCur");
  }
  /* 整句轨：不做打字机、不截 20 字，整句直接上屏（CSS 里允许换行），停在屏上直到下一句 */
  if (o.cue) {
    if (bcapPumpTimer) { clearTimeout(bcapPumpTimer); bcapPumpTimer = 0; }
    if (bcapClearTimer) { clearTimeout(bcapClearTimer); bcapClearTimer = 0; }
    bcapQueue = ""; bcapFinPending = false; bcapAcc = 0; bcapLastT = 0;
    bcapCurText = t; bcapFresh = 0; bcapLastRaw = t;
    cap.classList.add("cue");
    cap.classList.remove("partial");
    if (cur) cur.textContent = t;
    return;
  }
  cap.classList.remove("cue");
  /* 新的一句来了就取消上一句的定时清屏，直接换句 */
  if (bcapClearTimer) { clearTimeout(bcapClearTimer); bcapClearTimer = 0; }
  /* 与上一次的累积文本比前缀：成立＝同一句又吐了新字；否则是新句或模型回改。
     新字不直接上屏，而是进队列，由 bcapPump 一个字一个字地吐出来（打字机效果）。 */
  var same = bcapLastRaw && t === bcapLastRaw;
  var grows = bcapLastRaw && !same && t.indexOf(bcapLastRaw) === 0;
  if (same) {
    /* 断句帧常与上一帧内容完全相同：别误判成新句，也不重复入队 */
  } else if (grows) {
    bcapQueue += t.slice(bcapLastRaw.length);
  } else {
    /* 新的一句：清空当前行与待吐队列，从空行逐字重新开始 */
    bcapCurText = ""; bcapFresh = 0;
    bcapQueue = t.slice(-BCAP_MAX);
    bcapAcc = 0; bcapLastT = 0;
  }
  bcapLastRaw = t;
  if (o.final) bcapFinPending = true;
  if (!bcapPumpTimer) bcapPump();
};
window.giAsrStatus = function (o) {
  /* 只写当前行；用 cap.textContent 会清掉两行子节点，字幕会永久失效 */
  var cur = document.getElementById("bcapCur");
  if (cur && biliSubOn && o && o.msg) cur.textContent = o.msg;
};
