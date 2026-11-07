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
  biliExitFull();
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
    body.innerHTML =
      '<div class="bili-card"><b>还没有绑定哔哩哔哩</b>' +
      '<p class="bili-note">绑定后可用你自己账号的权限只读查看 B 站收藏夹。' +
      '不投币、不收藏、不发弹幕。</p>' +
      '<button type="button" class="bili-btn" onclick="biliLogin()">去绑定</button></div>';
    return;
  }
  body.innerHTML =
    '<div class="bili-srcbar"><button class="on" type="button">哔哩哔哩</button>' +
    '<button class="add" type="button" onclick="if(window.toast)toast(\'更多来源暂未接入\')">＋ 添加来源</button></div>' +
    '<div class="bili-user">' +
      (s.face ? '<img class="bili-ava" src="' + esc(s.face) + '" alt="">' : '<span class="bili-ava bili-ava-txt">B</span>') +
      '<div class="bili-who"><b>' + esc(s.uname || ("mid " + s.mid)) + '</b>' +
      '<small><i class="bili-dot"></i>已绑定 · 只读</small></div>' +
      '<button type="button" class="bili-btn bili-ghost" onclick="biliLogout()">退出绑定</button>' +
    '</div><div id="biliList" class="bili-list"></div>';
  biliShowFolders();
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

function biliShowFolders() {
  biliView = "folders";
  var t = document.getElementById("biliTitle");
  if (t) t.textContent = "视频收藏";
  var box = document.getElementById("biliList");
  if (!box) return;
  box.innerHTML = '<div class="bili-empty">加载中…</div>';
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
  var h = "";
  for (var i = 0; i < biliFolders.length; i++) {
    var f = biliFolders[i];
    h += '<div class="bili-folder" onclick="biliOpenFolder(' + i + ')">' +
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
  var box = document.getElementById("biliList");
  if (box) box.innerHTML = '<div class="bili-empty">加载中…</div>';
  biliLoadMore();
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
    var cov = it.cover
      ? '<img src="' + esc(it.cover) + '" alt="" loading="lazy" onerror="this.style.display=\'none\'">'
      : "";
    h += '<div class="bili-v' + (it.playable ? "" : " bili-v-off") + '"' + act + '>' +
         '<div class="bili-vthumb">' + cov +
           '<i class="bili-vdur">' + biliDur(it.duration) + '</i>' +
           (it.playable ? "" : '<i class="bili-vtag">' + esc(it.note || "不可播") + '</i>') +
         '</div>' +
         '<div class="bili-vmain"><b class="bili-vt">' + esc(it.title || it.bvid) + '</b>' +
         '<small>' + (it.upper ? esc(it.upper) : "未知 UP") + '</small></div>' +
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
  if (box) box.innerHTML = '<div class="bili-empty">正在取播放地址…</div>';
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
  setTimeout(function () { biliSwitching = false; }, 350);
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

function biliPlayerView(r, it) {
  var box = document.getElementById("biliList");
  if (!box) return;
  var poster = (it && it.cover) ? it.cover : ((r && r.cover) ? r.cover : BILI_BLACK_PX);
  if (biliCur) { biliCur.page = r.page; biliCur.pages = r.pages; }
  var title = r.title || (it && it.title) || "";
  var page = parseInt(r.page, 10) || 1;
  var pages = parseInt(r.pages, 10) || 1;
  var prevHidden = page > 1 ? "" : " hidden";
  var nextHidden = page < pages ? "" : " hidden";
  var wave = "M0 20 L0 14 C35 12 55 6 95 10 C130 14 155 5 205 11 C245 16 275 6 320 12 " +
             "C355 17 385 4 435 10 C475 15 505 5 555 11 C595 16 625 4 675 10 " +
             "C715 15 745 6 795 12 C835 17 865 5 915 11 C950 15 975 8 1000 13 L1000 20 Z";
  var h = '<div class="bili-player">' +
          '<video id="biliVideo" playsinline preload="metadata"' +
          (poster ? ' poster="' + esc(poster) + '"' : "") +
          ' src="' + esc(r.src) + '"></video>' +
          '<div class="bili-loading" id="biliLoading"><div class="bili-spinner"></div></div>' +
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
          '<div class="bili-topbar"><b>' + esc(title) + '</b>' +
          '<span class="ep">P' + page + ' / ' + pages + '</span></div>' +
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
              '<div class="bili-cb-group">' +
                '<button type="button" class="bili-cb bili-rate-btn" id="biliRateBtn" onclick="biliRateTap(event)" title="播放倍速"><span id="biliRateLbl">1.0×</span></button>' +
                '<button type="button" class="bili-cb" id="biliMuteBtn" onclick="biliVolOpen()"><svg class="ico"><use href="#i-volume"/></svg></button>' +
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
         (r.part ? '<span class="bili-chip">' + esc(r.part) + '</span>' : "") +
       '</div></div>';
  if (pages > 1) {
    h += '<div class="bili-pages">';
    for (var p = 1; p <= pages; p++) {
      h += '<button type="button" class="bili-pg' + (p === page ? " on" : "") +
           '" onclick="biliPlayPage(' + p + ')">P' + p + '</button>';
    }
    h += "</div>";
  }
  h += "</div>";
  box.innerHTML = h;
  var v = document.getElementById("biliVideo");
  if (v) {
    try { v.load(); } catch (e) {}
    biliShowLoading();
    v.addEventListener("timeupdate", biliSyncTime);
    v.addEventListener("loadstart", biliShowLoading);
    v.addEventListener("waiting", biliShowLoading);
    v.addEventListener("stalled", biliShowLoading);
    v.addEventListener("seeking", biliShowLoading);
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
  /* 点视频 / 切分 P 是用户手势：默认直接播放，不再停在暂停态等用户再点一次 */
  biliAutoPlay();
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
  if (ep) ep.textContent = "P" + pg + " / " + tp;
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
  var pgs = document.querySelectorAll(".bili-pages .bili-pg");
  for (var i = 0; i < pgs.length; i++) {
    if (i + 1 === pg) pgs[i].classList.add("on");
    else pgs[i].classList.remove("on");
  }
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
      if (c2 && v2 && !v2.paused) c2.classList.add("hide");
    }, 3200);
  }
}

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
  var lpTimer = null, fastOn = false;
  function clearLp() { if (lpTimer) { clearTimeout(lpTimer); lpTimer = null; } }
  v.addEventListener("touchstart", function (e) {
    if (e.touches.length !== 1) return;
    var t = e.touches[0];
    var rect = v.getBoundingClientRect();
    W = rect.width || 1; H = rect.height || 1;
    sx = t.clientX; sy = t.clientY;
    rightZone = (t.clientX - rect.left) >= W * 0.75;
    leftZone = (t.clientX - rect.left) <= W * 0.25;
    active = true; mode = ""; fastOn = false;
    startPct = (v.duration > 0) ? (v.currentTime / v.duration) : 0;
    startVol = biliVolState().pct;
    startBright = biliBrightPct;
    clearLp();
    /* 右侧 1/4 长按 = 1.5× 倍速（松手恢复用户倍速）；短按/滑动不触发 */
    if (rightZone) {
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
    var dx = t.clientX - sx, dy = t.clientY - sy;
    if (fastOn) { e.preventDefault(); return; }
    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) clearLp();
    if (!biliIsFull()) return; /* 小屏只支持长按倍速与双击，不做音量/进度手势 */
    if (!mode) {
      if (Math.abs(dx) < 12 && Math.abs(dy) < 12) return;
      if (rightZone && Math.abs(dy) >= Math.abs(dx)) mode = "vol";
      else if (leftZone && Math.abs(dy) >= Math.abs(dx)) mode = "bright";
      else if (!rightZone && !leftZone && Math.abs(dx) > Math.abs(dy)) mode = "seek";
      else return;
      biliSuppressClick();
    }
    e.preventDefault();
    if (mode === "seek") {
      biliScrubShowPct(startPct + dx / W);
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
    if (mode) biliSuppressClick();
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

function biliEnterFull() {
  var p = document.getElementById("tab-video");
  if (!p) return;
  p.classList.add("bili-full");
  biliNativeFull(true);
  biliSizeVideo();
  biliShowCtl();
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
  if (p) {
    p.classList.remove("bili-full");
    p.classList.remove("chrome-hidden");
    p.classList.remove("is-paused");
  }
  if (biliChromeT) { clearTimeout(biliChromeT); biliChromeT = null; }
  biliNativeFull(false);
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
}

function biliToggleFull() {
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
       会塌成方形（“无效扩画面”）；这里按宽度算 16:9 高度，黑底 + 居中加载 UI。 */
    var w = v.clientWidth || 0;
    if (!w) { var pl = document.querySelector(".bili-player"); if (pl) w = pl.clientWidth || 0; }
    if (w > 0) v.style.height = Math.round(w * 9 / 16) + "px";
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