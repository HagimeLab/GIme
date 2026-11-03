// OpenUI Lang 渲染层：解析交给官方 @openuidev/lang-core（window.OpenUI），
// 这里只把元素树画成 App 自己的黑白组件，并把按钮动作交回 app.js。
const make = (tag, cls) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  return n;
};

const textOf = (v) => (v == null ? '' : String(v));

/* 一击的判定窗（卡片本体展开走的是同一对常量，见 app-chat.js 的 bindOuiOpen）：
   这台机 320dpi、dpr=2，1 CSS px≈0.16mm —— 原来的 12px/600ms 等于
   「手指滑到近 2mm、按住 0.6 秒仍算点」，误触就是这么来的。 */
var OUI_TAP_MOVE = 8, OUI_TAP_MS = 350;

// 按下即触发：收键盘会让布局在按下与抬起之间上移，走 click 会点空
// guard(target)：返回 true 表示这一击属于内层控件（按钮/输入/嵌套 Tap），外层不要接。
function bindTap(node, fn, guard) {
  let lastFire = 0;
  let sx = 0, sy = 0, st = 0, lastTouch = 0;
  const blocked = (t) => !!(guard && guard(t));
  const fire = (e) => {
    /* 真机同一次点击会先后送来 touchstart 和 mousedown；不去重的话
       toggle: 会连切两次（看起来没反应）、send: 会重复发两条。 */
    const now = Date.now();
    if (now - lastFire < 450) {
      if (e && e.cancelable) e.preventDefault();
      return;
    }
    lastFire = now;
    if (e && e.cancelable) e.preventDefault();
    fn(e);
    // 动作自己把光标放进了输入框（再调整 / value:）就不要收回来
    if (window.__ouiKeepFocus) {
      window.__ouiKeepFocus = false;
      return;
    }
    const a = document.activeElement;
    if (a && a.blur && /^(INPUT|TEXTAREA)$/.test(a.tagName) && a.type !== 'checkbox') a.blur();
  };
  /* 不在 touchstart 上直接触发：那一版要 preventDefault 才能挡住合成 click，
     代价是从按钮起手往下滑滚不动（卡片很高时很明显）。改成「按下记坐标、
     抬起时位移小且够快才算点」，判据与卡片本体的 bindOuiOpen 同一套（阈值见文件头的
     OUI_TAP_MOVE / OUI_TAP_MS）。收键盘导致的布局上移不影响判断——比的是手指坐标，不是落点元素。 */
  node.addEventListener('touchstart', (e) => {
    lastTouch = Date.now();
    if (blocked(e.target)) { st = 0; return; }
    const p = e.touches && e.touches[0];
    if (!p) return;
    sx = p.clientX;
    sy = p.clientY;
    st = Date.now();
  }, { passive: true });
  node.addEventListener('touchend', (e) => {
    if (!st) return;
    const p = e.changedTouches && e.changedTouches[0];
    const dt = Date.now() - st;
    st = 0;
    if (blocked(e.target)) return;
    if (!p || dt > OUI_TAP_MS) return;
    if (Math.abs(p.clientX - sx) + Math.abs(p.clientY - sy) > OUI_TAP_MOVE) return;
    fire(e);
  });
  node.addEventListener('touchcancel', () => { st = 0; });
  node.addEventListener('mousedown', (e) => {
    /* 触屏已经走过一遍 touch*（touchend 之后浏览器还会补一串鼠标事件），这里只兜鼠标 */
    if (Date.now() - lastTouch < 700) return;
    if (blocked(e.target)) return;
    fire(e);
  });
}
const listOf = (v) => (Array.isArray(v) ? v : []);
const hasKey = (o, k) => !!o && Object.prototype.hasOwnProperty.call(o, k);

/* 复制：优先走原生桥（Android.copyText 是 App 已有的出口，WebView 里
   navigator.clipboard 在 file:// 下不可用、execCommand 又常被拦）。 */
function ouiCopy(text) {
  const s = textOf(text);
  let ok = false;
  try {
    if (window.Android && Android.copyText) { Android.copyText(s); ok = true; }
  } catch (e) { ok = false; }
  if (!ok) {
    try {
      const ta = make('textarea');
      ta.value = s;
      ta.style.position = 'fixed';
      ta.style.left = '-9999px';
      document.body.append(ta);
      ta.select();
      ok = document.execCommand('copy');
      ta.remove();
    } catch (e) { ok = false; }
  }
  if (window.toast) toast(ok ? '已复制' : '复制失败，请手动选中');
  return ok;
}

/* 单元格的 tone 前缀：! 警示 / + 强调 / ~ 次要，渲染时剥掉前缀再加类 */
function cellOf(v) {
  const s = textOf(v);
  const m = /^([!+~])([\s\S]*)$/.exec(s);
  if (!m) return { text: s, tone: '' };
  return { text: m[2], tone: m[1] === '!' ? 'danger' : m[1] === '+' ? 'ok' : 'muted' };
}

/* action 里的占位符：{label} 换成图表标签，{1}..{n} 换成被点那一行各列的文字。
   卡片能反过来驱动 App（点某一段 → 跳到对应题库/错题）就靠这一步替换。 */
function actOf(tpl, map) {
  const s = textOf(tpl);
  if (!s) return '';
  return s.replace(/\{([^{}]+)\}/g, (m, k) => (map && hasKey(map, k) ? textOf(map[k]) : m));
}

/* 内层可点件的边界：外层 Tap 与「点整卡展开」都要让开它们 */
const TAP_INNER = 'button,input,textarea,select,label,summary,a,.md-link,.oui-empty,.oui-tap,.oui-seg-tap,.oui-row-tap';
function tapInner(t, self) {
  const el = t && t.closest ? t.closest(TAP_INNER) : null;
  return !!(el && el !== self);
}

/* ---- Markdown：卡片的 Text(md:true) 复用气泡那套 mdToHtml()（app-chat.js）----
   样式表原样共用（.bubble.md 已放宽成 .md），不再抄一份：抄两份的话气泡改了、
   卡片永远是旧的。mdToHtml 自己会 esc() 再上标记，链接走 mdSafeUrl 白名单，
   所以模型写的源码不会把 <script> 之类的东西塞进来。 */
function ouiMdHtml(src) {
  if (typeof window.mdToHtml !== 'function') return null;
  try {
    const h = window.mdToHtml(String(src == null ? '' : src));
    return typeof h === 'string' ? h : null;
  } catch (e) { return null; }
}

/* ---- 公式：KaTeX 是懒加载件（596KB），首屏不引 ----
   katex 没到位时先落一颗能读的字面量（$x_1$），加载完由 ouiMathFlush() 补排；
   待排的元素只存在这张表里，不往 DOM 上挂 data-tex——公式串带反斜杠和引号，
   拼进属性迟早撞转义。 */
const ouiMathPending = [];
var ouiGidSeq = 0;
function ouiKatexHtml(tex, display) {
  const k = window.katex;
  if (!k || !tex) return '';
  try {
    return k.renderToString(String(tex), {
      displayMode: !!display, throwOnError: false, output: 'html', maxSize: 60, maxExpand: 60, trust: false,
    });
  } catch (e) { return ''; }
}
function ouiMathNode(display, tex) {
  const el = make(display ? 'div' : 'span', 'oui-math' + (display ? ' disp' : ''));
  const html = ouiKatexHtml(tex, display);
  if (html) el.innerHTML = html;
  else {
    el.textContent = (display ? '$$' : '$') + textOf(tex) + (display ? '$$' : '$');
    ouiMathPending.push({ el: el, tex: textOf(tex), display: !!display });
    if (window.ouiEnsureMath) window.ouiEnsureMath();
  }
  return el;
}
window.ouiMathFlush = function () {
  for (let i = ouiMathPending.length - 1; i >= 0; i--) {
    const p = ouiMathPending[i];
    /* 卡片收回/消息重画会让节点脱离文档树：按 parentNode 判活，
       回归沙箱的 DOM 桩没有 isConnected，用 parentNode 两边都成立 */
    if (!p.el.parentNode) { ouiMathPending.splice(i, 1); continue; }
    const html = ouiKatexHtml(p.tex, p.display);
    if (!html) continue;
    p.el.textContent = '';
    p.el.innerHTML = html;
    ouiMathPending.splice(i, 1);
  }
  return ouiMathPending.length;
};

/* $…$ / $$…$$ 先摘成占位符再走 Markdown：LaTeX 里的 _ 和 * 会被强调规则吃掉，
   连 x_1 都会变成 <em>。摘出来的槽位用 \u0000 包，esc() 不动它，填回去即可。 */
function ouiSplitMath(s) {
  const holds = [];
  const out = String(s == null ? '' : s)
    .replace(/\$\$([\s\S]+?)\$\$/g, (m, t) => { holds.push({ tex: t.trim(), display: true }); return '\u0000M' + (holds.length - 1) + '\u0000'; })
    .replace(/\$([^$\n]+?)\$/g, (m, t) => { holds.push({ tex: t.trim(), display: false }); return '\u0000M' + (holds.length - 1) + '\u0000'; });
  return { text: out, holds: holds };
}
/* 把 md 源码画进 el：Markdown + 行内公式；没有 mdToHtml（回归沙箱）就退回纯文本 */
function ouiPaintMd(el, src) {
  const ex = ouiSplitMath(src);
  const html = ouiMdHtml(ex.text);
  if (html == null) { el.textContent = textOf(src); return el; }
  el.innerHTML = html.replace(/\u0000M(\d+)\u0000/g, (m, i) => '<i class="oui-mh" data-i="' + i + '"></i>');
  const marks = el.querySelectorAll('.oui-mh');
  for (let i = 0; i < marks.length; i++) {
    const hv = ex.holds[Number(marks[i].getAttribute('data-i'))];
    if (!hv) { marks[i].className = ''; continue; }
    marks[i].replaceWith(ouiMathNode(hv.display, hv.tex));
  }
  return el;
}

function boxAlign(d, p, extra) {
  /* align / justify / padding 一律走类名，不写内联 style：样式表里只有类名那一档
     能被暗色令牌和 check.mjs 的 CSS 断言管住。 */
  const al = ['start', 'center', 'end'].indexOf(p.align) >= 0 ? p.align : '';
  const ju = ['start', 'center', 'end', 'between'].indexOf(p.justify) >= 0 ? p.justify : '';
  const pd = p.padding === 'sm' || p.padding === 'md' ? p.padding : '';
  if (al) d.className += ' align-' + al;
  if (ju) d.className += ' justify-' + ju;
  if (pd) d.className += ' pad-' + pd;
  if (extra) d.className += extra;
  return d;
}

function renderNode(node, ctx) {
  if (node == null) return null;
  if (typeof node === 'string') return document.createTextNode(node);
  if (node.type !== 'element') return null;
  const p = node.props || {};
  const kids = (arr) => {
    const box = document.createDocumentFragment();
    for (const k of listOf(arr)) {
      const n = renderNode(k, ctx);
      if (n) box.append(n);
    }
    return box;
  };

  switch (node.typeName) {
    case 'Stack': {
      /* gap 在 schema 里是 sm/md 两档，md 等于默认间距，只有 sm 要额外一类。
         以前这里完全忽略 p.gap，模型写了也看不出任何区别。 */
      const d = make('div', 'oui-stack' + (p.gap === 'sm' ? ' gap-sm' : ''));
      boxAlign(d, p);
      d.append(kids(p.children));
      return d;
    }
    case 'Row': {
      const d = make('div', 'oui-row');
      boxAlign(d, p);
      d.append(kids(p.children));
      return d;
    }
    case 'Card': {
      const d = make('div', 'oui-card');
      if (p.title) d.append(makeText('div', 'oui-card-t', p.title));
      const body = make('div', 'oui-stack');
      body.append(kids(p.children));
      d.append(body);
      return d;
    }
    case 'Tap': {
      /* 可点容器：让卡片里的图表 / 表格 / 整块文字反过来驱动 App。
         内层按钮会自己 stopPropagation，这里再加一道 guard 兜住嵌套 Tap。 */
      const act = textOf(p.action);
      /* 无 action 时和 Button 一样降级成纯文本：否则会渲染出一个“看着能点、
         点了只弹不支持”的 role=button，无障碍语义也是错的 */
      if (!act.trim()) {
        const plain = make('div', 'oui-tap-plain');
        plain.append(kids(p.children));
        return plain;
      }
      const d = make('div', 'oui-tap');
      d.setAttribute('role', 'button');
      /* 只包一行内容的 Tap 当成「可点条目」：加边框 + 右侧箭头，用户才知道能点 */
      const only = Array.isArray(p.children) ? p.children.length : 1;
      if (only === 1) d.className += ' oui-tap-line';
      d.append(kids(p.children));
      d.setAttribute('data-oui-act', act);
      bindTap(d, (e) => {
        e.stopPropagation();
        if (/^copy:/i.test(act)) { ouiCopy(act.slice(5)); return; }
        ctx.onAction(act, d, nearestForm(d));
      }, (t) => tapInner(t, d));
      return d;
    }
    case 'Title': {
      const lv = Math.max(1, Math.min(3, Number(p.level) || 2));
      return makeText('div', `oui-title l${lv}`, p.text);
    }
    case 'Text': {
      const cls = 'oui-text' + (p.muted ? ' muted' : '');
      /* md:true 才走 Markdown：默认纯文本是老行为，模型漏写也不会突然把 * 当记号。
         容器必须同时带 App 的 `md` 类本身：气泡那套排版规则全挂在 `.md 子元素` 上
         （.md ol 的 padding-left、.md code 的等宽底…）。类名带上前缀就不算 `md`，
         规则一条也不命中，全局 `* { padding: 0 }` 会把列表缩进清零，
         序号直接甩到内容盒左边（真机截图为证）。 */
      if (!p.md) return makeText('div', cls, p.text);
      const d = make('div', cls + ' md');
      return ouiPaintMd(d, p.text);
    }
    case 'Rich': {
      const d = make('div', 'oui-text md' + (p.muted ? ' muted' : ''));
      return ouiPaintMd(d, p.text);
    }
    case 'Math':
      return ouiMathNode(!!p.display, p.text);
    case 'Badge':
      return makeText('span', 'oui-badge', p.text);
    case 'Empty': {
      /* 空状态：以前只能留一行灰字，用户分不清「没有」和「没加载出来」 */
      const d = make('div', 'oui-empty');
      d.append(make('i', 'oui-empty-o'));
      d.append(makeText('div', 'oui-empty-t', p.text));
      if (p.hint) d.append(makeText('div', 'oui-empty-h', p.hint));
      const eAct = textOf(p.action).trim();
      if (eAct) {
        d.className += ' tap';
        d.setAttribute('data-oui-act', eAct);
        bindTap(d, (e) => {
          e.stopPropagation();
          if (/^copy:/i.test(eAct)) { ouiCopy(eAct.slice(5)); return; }
          ctx.onAction(eAct, d, nearestForm(d));
        });
      }
      return d;
    }
    case 'Error': {
      /* 错误态 + 重试：工具失败时模型爱把异常写成一句正文，这里给它一个显式形状 */
      const d = make('div', 'oui-error');
      d.append(makeText('b', 'oui-error-t', p.text));
      if (p.hint) d.append(makeText('div', 'oui-error-h', p.hint));
      const retry = textOf(p.retry).trim();
      if (retry) {
        const b = make('button', 'oui-btn secondary');
        b.type = 'button';
        b.textContent = '重试';
        bindTap(b, (e) => {
          e.stopPropagation();
          ctx.onAction(retry, b, nearestForm(b));
        });
        d.append(b);
      }
      return d;
    }
    case 'Tab': {
      /* 分段控件：labels 与 panels 一一对齐，点标题只换内容区。
         重画照 Table 分页那一套（清 body 再挂），不动卡片本体、不跳页。 */
      const labels = listOf(p.labels).map(textOf);
      const panels = listOf(p.panels);
      if (!labels.length) return make('div', 'oui-tabs');
      const box = make('div', 'oui-tabs');
      const head = make('div', 'oui-tabs-h');
      const body = make('div', 'oui-tabs-b');
      let cur = 0;
      const paint = () => {
        head.textContent = '';
        body.textContent = '';
        labels.forEach((l, i) => {
          const t = make('button', 'oui-tab-t' + (i === cur ? ' on' : ''));
          t.type = 'button';
          t.textContent = l;
          bindTap(t, (e) => {
            e.stopPropagation();
            if (i === cur) return;
            cur = i;
            paint();
          });
          head.append(t);
        });
        body.append(kids(panels[cur]));
      };
      paint();
      box.append(head, body);
      return box;
    }
    case 'List': {
      const d = make(p.ordered ? 'ol' : 'ul', 'oui-list');
      for (const item of listOf(p.items)) d.append(makeText('li', '', item));
      return d;
    }
    case 'Steps': {
      const d = make('ol', 'oui-steps');
      listOf(p.items).forEach((item, i) => {
        const li = make('li', '');
        li.append(makeText('i', '', String(i + 1)), makeText('span', '', item));
        d.append(li);
      });
      return d;
    }
    case 'Callout': {
      const tone = p.tone === 'warn' || p.tone === 'success' ? p.tone : 'info';
      const d = make('div', 'oui-callout ' + tone);
      if (p.title) d.append(makeText('b', 'oui-callout-t', p.title));
      d.append(makeText('div', 'oui-callout-x', p.text));
      return d;
    }
    case 'Chart': {
      const d = make('div', 'oui-chart');
      const labels = listOf(p.labels);
      const raw = listOf(p.values);
      /* unit / legend：数值单位（题、%、分钟）以前只能塞进 labels，柱顶就丢了；
         触屏没有 hover，所以不做 tooltip，数值本来就标在项上。 */
      const unit = textOf(p.unit).trim() ||
        /* 第 4 位（names）被写成字符串时按单位收：模型常写 Chart("bar", 标签, 数值, "题")，
           以前 zod 直接报 expects array、names 与单位一起丢 */
        (typeof p.names === 'string' ? p.names.trim() : '');
      const withUnit = (s) => (unit ? s + unit : String(s));
      const lgTop = p.legend === 'top';
      /* stack 传的是二维（每行一个类别、每列一个系列）：对数组做 Number() 会变 NaN，
         所以先认出二维，单系列的 values 才走 map(Number)。 */
      const is2D = raw.length > 0 && Array.isArray(raw[0]);
      /* 模型偶尔把数值写成「暂无」「12题」「N/A」，Number() 得到 NaN：NaN 会顺着
         max / 柱高把整张图的几何污染成 NaN，柱顶和折线标签还会印出「NaN」。
         统一口径：几何一律 numOf 用 0 兜底；显示用 shown——能转数字就带单位，
         转不了就照原文显示，绝不把 NaN / undefined 印给用户。 */
      const numOf = (x) => { const n = Number(x); return isFinite(n) ? n : 0; };
      const values = is2D ? [] : raw.map(numOf);
      const shown = (i) => { const n = Number(raw[i]); return isFinite(n) ? withUnit(n) : textOf(raw[i]); };
      if (!raw.length) return d;
      /* action：点某根柱 / 某个图例项就执行，{label} 换成该项标签。
         命中区给的是「看得见的部分」（数值、柱体、标签），不是整列：
         整列高 122px，矮柱上方的空白也算点，真机上点不准（实测误触源之一）。
         饼图是一整块 conic-gradient 画不出可点的扇区，可点的一直是图例行。 */
      const cAct = textOf(p.action);
      const segTap = (els, label) => {
        if (!cAct) return els;
        const a = actOf(cAct, { label: textOf(label) });
        for (const el of els) {
          el.setAttribute('data-oui-act', a);
          el.className = (el.className ? el.className + ' ' : '') + 'oui-seg-tap';
          bindTap(el, (e) => {
            e.stopPropagation();
            ctx.onAction(a, el, nearestForm(el));
          });
        }
        return els;
      };
      if (p.kind === 'pie') {
        /* 占比：柱/折线画不出「占多少」，用 conic-gradient 画饼，
           颜色走 5 档灰阶令牌（黑白界面里彩色饼图很扎眼）。 */
        const vals = values.map((v) => (isFinite(v) ? Math.max(0, v) : 0));
        const total = vals.reduce((a, b) => a + b, 0);
        if (!total) return d;
        let acc = 0;
        const segs = vals.map((v, i) => {
          const a = (acc / total) * 360, b = ((acc + v) / total) * 360;
          acc += v;
          return `var(--pie-${(i % 5) + 1}) ${a.toFixed(2)}deg ${b.toFixed(2)}deg`;
        });
        const pie = make('div', 'oui-pie');
        pie.style.background = 'conic-gradient(' + segs.join(',') + ')';
        pie.setAttribute('role', 'img');
        pie.setAttribute('aria-label', labels.map((l, i) => textOf(l) + ' ' + values[i]).join('，'));
        const lg = make('div', 'oui-pie-lg');
        labels.forEach((l, i) => {
          const row = make('div', 'oui-pie-k');
          row.append(make('i', 'oui-pie-d' + ((i % 5) + 1)));
          row.append(makeText('span', 'oui-pie-l', l));
          row.append(makeText('b', 'oui-pie-v',
            shown(i) + '（' + Math.round((vals[i] / total) * 100) + '%）'));
          segTap([row], l);
          lg.append(row);
        });
        const box = make('div', 'oui-pie-box' + (lgTop ? ' lg-top' : ''));
        if (lgTop) box.append(lg, pie); else box.append(pie, lg);
        d.append(box);
        return d;
      }
      if (p.kind === 'stack' || (is2D && !p.kind)) {
        /* 堆叠柱：多系列对比（新学 / 复习 / 错题）以前只能拆成三张单柱图，
           看不出「一天总共多少、各占多少」。段高用 flex 比例，不用算百分比。 */
        const rows = raw.map((r) => (Array.isArray(r) ? r.map((x) => {
          const n = Number(x);
          return isFinite(n) ? Math.max(0, n) : 0;
        }) : []));
        const names = Array.isArray(p.names) ? p.names.map(textOf) : [];
        /* 系列数取「最长一行」与 names 的较大值：模型偶尔少写一行数值或少写一个系列名，
           按 0 补 / 显示成「系列 N」总比「有一截颜色没有图例」强。 */
        const nS = rows.reduce((n, r) => Math.max(n, r.length), names.length);
        const totals = rows.map((r) => r.reduce((a, b) => a + b, 0));
        const maxT = Math.max.apply(null, totals.concat([1]));
        const bars = make('div', 'oui-bars');
        rows.forEach((r, i) => {
          const col = make('div', 'oui-bar');
          const total = makeText('b', '', withUnit(Math.round(totals[i] * 10) / 10));
          const stack = make('div', 'oui-col');
          stack.style.height = Math.max(2, (totals[i] / maxT) * 100) + '%';
          for (let s = 0; s < nS; s++) {
            const v = r[s] || 0;
            if (v <= 0) continue;
            const seg = make('i', 's' + ((s % 5) + 1));
            seg.style.flex = String(v);
            seg.title = (names[s] || '系列 ' + (s + 1)) + ' ' + v;
            stack.append(seg);
          }
          const lab = makeText('span', '', labels[i] || '');
          col.append(total, stack, lab);
          /* 可点的是「总量 + 柱体 + 标签」这三块看得见的，整列空白不算 */
          segTap([total, stack, lab], labels[i] || '');
          bars.append(col);
        });
        const lg = nS ? make('div', 'oui-legend') : null;
        if (lg) {
          for (let i = 0; i < nS; i++) {
            const item = make('span');
            item.append(make('i', 'oui-pie-d' + ((i % 5) + 1)));
            item.append(document.createTextNode(names[i] || '系列 ' + (i + 1)));
            lg.append(item);
          }
        }
        if (lg && lgTop) d.append(lg);
        d.append(bars);
        /* legend:top 让图例先看「哪个系列是什么色」，再看柱高（系列名长时尤其有用） */
        if (lg && !lgTop) d.append(lg);
        return d;
      }
      const max = Math.max.apply(null, values.concat([1]));
      if (p.kind === 'line') {
        const n = values.length, w = 300, h = 110;
        const pts = values.map((v, i) => {
          const x = n === 1 ? w / 2 : i * (w / (n - 1));
          const y = h - 8 - (Math.max(0, v) / max) * (h - 20);
          return x.toFixed(1) + ',' + y.toFixed(1);
        }).join(' ');
        const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('class', 'oui-line');
        svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
        svg.setAttribute('preserveAspectRatio', 'none');
        const poly = document.createElementNS('http://www.w3.org/2000/svg', 'polyline');
        poly.setAttribute('points', pts);
        poly.setAttribute('fill', 'none');
        poly.setAttribute('stroke', 'currentColor');
        poly.setAttribute('stroke-width', '2');
        svg.append(poly);
        d.append(svg);
      } else {
        const bars = make('div', 'oui-bars');
        values.forEach((v, i) => {
          const col = make('div', 'oui-bar');
          const val = makeText('b', '', shown(i));
          const fill = make('i', '');
          fill.style.height = Math.max(0, Math.min(100, (Math.max(0, v) / max) * 100)) + '%';
          const lab = makeText('span', '', labels[i] || '');
          col.append(val, fill, lab);
          /* 数值、柱体、标签可点；矮柱上方的空白不再算点 */
          segTap([val, fill, lab], labels[i] || '');
          bars.append(col);
        });
        d.append(bars);
      }
      if (p.kind === 'line' && labels.length) {
        /* 折线以前只有一根线：数值没处标，action 也无处可点（写了等于没写，静默失效）。
           现在每个 x 标签上方标数值，标签与数值都可点，{label} 照换。 */
        const x = make('div', 'oui-chart-x');
        labels.forEach((label, i) => {
          const cell = make('div', 'oui-chart-xi');
          const v = makeText('b', 'oui-chart-v', shown(i));
          const nm = makeText('span', '', textOf(label));
          cell.append(v, nm);
          /* 命中区原来只有数值 b 和标签 span（真机实测 18×14 / 22×18，都不到 44dp，
             点不准）。把整格 cell 也纳入可点，cell 内空白处也能点；b/nm 仍是内层，
             tapInner 会拦住，不会重复触发。 */
          segTap([cell, v, nm], label);
          x.append(cell);
        });
        d.append(x);
      }
      return d;
    }
    case 'Metric': {
      const d = make('div', 'oui-metric');
      d.append(makeText('div', 'oui-metric-k', p.label), makeText('div', 'oui-metric-v', p.value));
      /* delta：只有数字才有涨跌可言，正负决定颜色，符号是模型写的原文 */
      if (p.delta) {
        const s = textOf(p.delta).trim();
        const cls = /^[-−]/.test(s) ? ' down' : /^\+/.test(s) ? ' up' : '';
        d.append(makeText('div', 'oui-metric-d' + cls, s));
      }
      if (p.hint) d.append(makeText('div', 'oui-metric-h', p.hint));
      return d;
    }
    case 'Progress': {
      const d = make('div', 'oui-progress');
      const parts = listOf(p.parts);
      if (parts.length) {
        /* 分段进度：「已完成:40」「进行中:20」——单值进度条表达不了
           「已完成 / 进行中 / 未开始」这种拆分，模型只能拆成三根条。 */
        const segs = [];
        let total = 0;
        for (const t of parts) {
          const s = textOf(t);
          const i = s.lastIndexOf(':');
          const name = i > 0 ? s.slice(0, i).trim() : s.trim();
          const v = Math.max(0, i > 0 ? Number(s.slice(i + 1)) || 0 : 0);
          total += v;
          segs.push({ name: name, v: v });
        }
        if (!total) total = 1;
        if (p.label) d.append(makeText('div', 'oui-progress-t', p.label));
        const bar = make('div', 'oui-progress-bar parts');
        segs.forEach((s, i) => {
          const seg = make('i', 'seg' + ((i % 5) + 1));
          seg.style.width = (s.v / total) * 100 + '%';
          seg.title = s.name + ' ' + s.v;
          bar.append(seg);
        });
        d.append(bar);
        const lg = make('div', 'oui-progress-lg');
        segs.forEach((s, i) => {
          const row = make('span', 'oui-progress-l');
          row.append(make('i', 'oui-pie-d' + ((i % 5) + 1)));
          row.append(document.createTextNode(s.name + ' ' + s.v));
          lg.append(row);
        });
        d.append(lg);
        return d;
      }
      const v = Math.max(0, Math.min(100, Number(p.value) || 0));
      if (p.label) d.append(makeText('div', 'oui-progress-t', `${p.label} ${Math.round(v)}%`));
      const bar = make('div', 'oui-progress-bar');
      const fill = make('i');
      fill.style.width = `${v}%`;
      bar.append(fill);
      d.append(bar);
      return d;
    }
    case 'Divider':
      return make('hr', 'oui-div');
    case 'Image': {
      /* 只放行 http(s) 与 data:image：卡片源码来自模型，不能让它把 file://
         或自定义 scheme 塞进 WebView（会读到本地文件、或触发外部应用）。
         svg: 前缀是给模型的糖：裸 <svg …/> 包成 data:image/svg+xml 再交给 <img>。
         放 <img> 里是安全的——SVG 在 img 上下文不执行脚本、不加载外链。 */
      let src = textOf(p.src).trim();
      if (/^svg:/i.test(src)) {
        src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(src.replace(/^svg:/i, ''));
      }
      const ok = /^https?:\/\//i.test(src) || /^data:image\//i.test(src);
      const fig = make('figure', 'oui-figure');
      if (ok) {
        const img = make('img', 'oui-img');
        img.src = src;
        img.alt = textOf(p.alt || p.caption || '');
        img.loading = 'lazy';
        img.setAttribute('referrerpolicy', 'no-referrer');
        img.addEventListener('error', () => {
          img.remove();
          fig.append(makeText('div', 'oui-img-bad', '图片加载失败'));
        });
        fig.append(img);
      } else {
        fig.append(makeText('div', 'oui-img-bad', '图片地址不支持（只认 http(s) 与 data:image）'));
      }
      if (p.caption) fig.append(makeText('figcaption', 'oui-cap', p.caption));
      return fig;
    }
    case 'Link': {
      const href = textOf(p.href).trim();
      /* href 同样来自模型，而它会被交给 Android.openUrl（ACTION_VIEW）。
         intent:// 能拉起任意组件、file:// 能开本地文件，所以只放行 http(s)，
         其余降级成一段灰字，点了什么也不发生。 */
      if (!/^https?:\/\//i.test(href)) {
        return makeText('div', 'oui-text muted',
          textOf(p.text || href) + '（链接地址不支持，只认 http(s)）');
      }
      const a = make('a', 'oui-link');
      /* 走宿主统一的链接出口：document 上的 click 代理认 data-md-href，
         交给 Android.openUrl 用系统浏览器打开，WebView 不自己导航。 */
      a.setAttribute('data-md-href', href);
      a.textContent = textOf(p.text || href);
      a.addEventListener('click', (e) => e.preventDefault());
      return a;
    }
    case 'Skeleton': {
      const n = Math.max(1, Math.min(6, Number(p.rows) || 3));
      const d = make('div', 'oui-skel');
      d.setAttribute('aria-hidden', 'true');
      for (let i = 0; i < n; i++) d.append(make('i'));
      return d;
    }
    case 'Button': {
      const act = textOf(p.action);
      /* 没写 action 的按钮点了没反应（死按钮），比不放更糟：
         一律退化成纯文字，用户不会以为它能点。模型该给 action 就该给。 */
      if (!act.trim()) return makeText('div', 'oui-btn-plain', textOf(p.label));
      const b = make('button', `oui-btn ${p.variant === 'secondary' ? 'secondary' : 'primary'}`);
      b.type = 'button';
      /* loading 的转圈在前，icon 其次，最后才是文字：按顺序 append 拼，
         不用 insertBefore(firstChild)——回归沙箱的 DOM 桩没有 insertBefore。 */
      if (p.loading) {
        const sp = make('i', 'oui-spin');
        sp.setAttribute('aria-hidden', 'true');
        b.append(sp);
        b.className += ' loading';
        b.setAttribute('aria-busy', 'true');
      }
      /* icon：界面是黑白的、没有图标字体，所以只接一个字形（✓ ⏱ ↻ 或 emoji）。
         取前两个码位，模型写整词也不会把按钮撑破。 */
      if (p.icon) {
        const ic = make('span', 'oui-btn-i');
        ic.textContent = Array.from(textOf(p.icon)).slice(0, 2).join('');
        ic.setAttribute('aria-hidden', 'true');
        b.append(ic);
      }
      b.append(document.createTextNode(textOf(p.label)));
      /* disabled / loading 都不绑手势：灰按钮点下去必须有「什么都没发生」的确定感，
         绑了反而会把内层事件吞掉。 */
      if (p.disabled || p.loading) {
        b.disabled = true;
        b.className += ' disabled';
        return b;
      }
      bindTap(b, (e) => {
        e.stopPropagation();
        /* copy: 在渲染层就地完成，不绕回路由：剪贴板是纯前端能力，
           走 chatOuiRoute 只会落到「不支持」那一句。 */
        if (/^copy:/i.test(act)) { ouiCopy(act.slice(5) || textOf(p.label)); return; }
        ctx.onAction(act, b, nearestForm(b));
      });
      return b;
    }
    case 'Input': {
      const wrap = make('label', 'oui-field');
      if (p.label) wrap.append(makeText('span', 'oui-label', p.label));
      const name = textOf(p.name);
      const multi = p.kind === 'multiline';
      const i = make(multi ? 'textarea' : 'input', 'oui-input' + (multi ? ' multi' : ''));
      /* date 交给系统日期选择器：手打 YYYY-MM-DD 是建计划时最常见的输错点 */
      if (multi) i.rows = 3;
      else if (p.kind === 'number') i.type = 'number';
      else if (p.kind === 'date') i.type = 'date';
      /* datetime-local 在安卓 WebView 上没有原生滚轮，会退化成可手打的日期时间框，
         仍比让模型自己拼 "2026-11-03T18:00" 的纯文本强一档 */
      else if (p.kind === 'datetime') i.type = 'datetime-local';
      else i.type = 'text';
      i.name = name;
      i.dataset.fname = name;
      i.dataset.flabel = textOf(p.label || p.name);
      if (p.placeholder) i.placeholder = textOf(p.placeholder);
      if (hasKey(ctx.values, name)) i.value = textOf(ctx.values[name]);
      else if (p.value) i.value = textOf(p.value);
      i.addEventListener('input', () => {
        if (ctx.values) ctx.values[name] = i.value;
        if (ctx.onChange) ctx.onChange(false);
      });
      wrap.append(i);
      return wrap;
    }
    case 'Select': {
      // 不用系统下拉：原生弹层是白底蓝字，和这套黑白界面冲突
      const wrap = make('div', 'oui-field');
      if (p.label) wrap.append(makeText('span', 'oui-label', p.label));
      const name = textOf(p.name);
      const opts = listOf(p.options).map(textOf).filter((o) => o !== '');
      const saved = hasKey(ctx.values, name) ? textOf(ctx.values[name]) : '';
      const init = opts.indexOf(saved) >= 0 ? saved : opts.indexOf(textOf(p.value)) >= 0 ? textOf(p.value) : opts[0] || '';
      const field = make('button', 'oui-select');
      field.type = 'button';
      const shown = makeText('span', 'oui-select-t', init || '请选择');
      const held = make('input');
      held.type = 'hidden';
      held.name = name;
      held.dataset.fname = name;
      held.dataset.flabel = textOf(p.label || p.name);
      held.value = init;
      field.append(shown, make('i', 'oui-chev'), held);
      bindTap(field, (e) => {
        e.stopPropagation();
        if (!opts.length) return;
        const blur = document.activeElement;
        if (blur && /^(INPUT|TEXTAREA)$/.test(blur.tagName)) blur.blur();
        const pick = (v) => {
          held.value = v;
          shown.textContent = v;
          if (ctx.values) ctx.values[name] = v;
          if (ctx.onChange) ctx.onChange(true);
        };
        if (ctx.onPick) ctx.onPick(textOf(p.label || p.name), opts, held.value, pick);
        else pick(opts[(opts.indexOf(held.value) + 1) % opts.length]);
      });
      wrap.append(field);
      return wrap;
    }
    case 'RadioGroup': {
      /* 单选组：可见的 radio 只负责手感，值落在一个隐藏域上（照 Select 的做法），
         这样 readForm 的「一个 data-fname = 一项」契约不用改。
         name 用 gid 前缀：同一条消息里两张卡都写 name:"题型" 时，原生 radio
         会跨卡互斥，点第二张把第一张的选中态清掉。 */
      const wrap = make('div', 'oui-field oui-choices');
      if (p.label) wrap.append(makeText('span', 'oui-label', p.label));
      const name = textOf(p.name);
      const opts = listOf(p.options).map(textOf).filter((o) => o !== '');
      const saved = hasKey(ctx.values, name) ? textOf(ctx.values[name]) : textOf(p.value);
      const held = make('input');
      held.type = 'hidden';
      held.name = name;
      held.dataset.fname = name;
      held.dataset.flabel = textOf(p.label || p.name);
      held.value = opts.indexOf(saved) >= 0 ? saved : '';
      const gname = 'oui-r' + (ctx.gid || 'x') + '-' + name;
      opts.forEach((o) => {
        const row = make('label', 'oui-choice');
        const r = document.createElement('input');
        r.type = 'radio';
        r.name = gname;
        r.value = o;
        r.checked = held.value === o;
        row.append(r, makeText('span', 'oui-choice-t', o));
        r.onchange = () => {
          if (!r.checked) return;
          held.value = o;
          if (ctx.values) ctx.values[name] = o;
          if (ctx.onChange) ctx.onChange(true);
        };
        wrap.append(row);
      });
      wrap.append(held);
      return wrap;
    }
    case 'CheckboxGroup': {
      /* 多选组：选中项按「、」拼成一个值写进表单（和 Button 拼选项的老写法对齐，
         模型不用改语义就能从堆按钮迁过来） */
      const wrap = make('div', 'oui-field oui-choices');
      if (p.label) wrap.append(makeText('span', 'oui-label', p.label));
      const name = textOf(p.name);
      const opts = listOf(p.options).map(textOf).filter((o) => o !== '');
      const init = listOf(p.values).map(textOf);
      const saved = hasKey(ctx.values, name) ? textOf(ctx.values[name]).split('、') : init;
      const picked = opts.filter((o) => saved.indexOf(o) >= 0);
      const held = make('input');
      held.type = 'hidden';
      held.name = name;
      held.dataset.fname = name;
      held.dataset.flabel = textOf(p.label || p.name);
      held.value = picked.join('、');
      const cells = [];
      opts.forEach((o) => {
        const row = make('label', 'oui-choice');
        const c = document.createElement('input');
        c.type = 'checkbox';
        c.checked = picked.indexOf(o) >= 0;
        row.append(c, makeText('span', 'oui-choice-t', o));
        c.onchange = () => {
          const now = [];
          for (const one of cells) if (one.input.checked) now.push(one.text);
          held.value = now.join('、');
          if (ctx.values) ctx.values[name] = held.value;
          if (ctx.onChange) ctx.onChange(true);
        };
        cells.push({ input: c, text: o });
        wrap.append(row);
      });
      wrap.append(held);
      return wrap;
    }
    case 'Toggle': {
      const wrap = make('label', 'oui-toggle');
      const name = textOf(p.name);
      const c = document.createElement('input');
      c.type = 'checkbox';
      c.checked = hasKey(ctx.values, name) ? !!ctx.values[name] : !!p.checked;
      c.dataset.fname = name;
      c.dataset.flabel = textOf(p.label || p.name);
      wrap.append(c, makeText('span', null, p.label));
      c.onchange = () => {
        if (ctx.values) ctx.values[name] = c.checked;
        if (ctx.onChange) ctx.onChange(true);
        if (p.action) ctx.onAction(textOf(p.action), c, nearestForm(c));
      };
      return wrap;
    }
    case 'Table': {
      const t = make('table', 'oui-table');
      const cols = listOf(p.columns).map(textOf);
      const raw = listOf(p.rows).map((r) => listOf(r));
      /* 列数对齐：模型把表头写 4 列、数据写 5 列是常事，以前多出来的 td 会自己
         开一格、整张表从那一行起错位，而且一声不响（大肥鱼自己都点出了这条）。
         以表头为准（没表头就取最长行）：多的截掉、少的补空，保证每行等宽。 */
      const width = cols.length || raw.reduce((n, r) => Math.max(n, r.length), 0);
      const rows = raw.map((r) => {
        const out = [];
        for (let i = 0; i < width; i++) out.push(cellOf(r[i]));
        return out;
      });
      /* 数字列右对齐：整列都是数字（可带小数 / %）才右对齐，混排的列保持左对齐。
         手机窄屏上「3 / 12 / 100」左对齐时位数对不齐，扫一眼比不出来。 */
      const num = cols.map((_, ci) => {
        let seen = 0;
        for (const row of rows) {
          const v = row[ci] ? row[ci].text.trim() : '';
          if (!v) continue;
          if (!/^[-+]?\d+(?:\.\d+)?%?$/.test(v)) return false;
          seen++;
        }
        return seen > 0;
      });
      /* widths：按列给 CSS 宽度（"40%" / "72px" / "auto"）。题号、百分比这类短列
         和题干列挤在一行等宽时，短列会被拉得很宽、正文反而被压成竖排。 */
      const widths = listOf(p.widths).map(textOf).filter((w) => /^(auto|\d+(\.\d+)?(%|px|em|rem))$/.test(w.trim()));
      if (widths.length) {
        const cg = document.createElement('colgroup');
        for (let i = 0; i < width; i++) {
          const col = document.createElement('col');
          if (widths[i]) col.style.width = widths[i].trim();
          cg.append(col);
        }
        t.append(cg);
      }
      if (cols.length) {
        const thead = document.createElement('thead');
        const tr = document.createElement('tr');
        cols.forEach((c, ci) => {
          if (!p.sortable) { tr.append(makeText('th', num[ci] ? 'num' : null, c)); return; }
          const th = make('th', (num[ci] ? 'num ' : '') + 'oui-th-sort');
          th.append(document.createTextNode(c), make('i', 'oui-sort-c'));
          th.setAttribute('data-oui-col', String(ci));
          bindTap(th, (e) => { e.stopPropagation(); sortBy(ci); });
          tr.append(th);
        });
        thead.append(tr);
        t.append(thead);
      }
      const tb = make('tbody');
      /* 排序靠 order 这层索引表，不去动 rows 本身：分页、rowAction 的 {1}..{n}
         都还按原行取内容，排序只改「第 i 格画哪一行」。 */
      const order = rows.map((_, i) => i);
      let sortCol = -1, sortDir = 1;
      let repaint = () => {};
      const cmpCell = (a, b) => {
        const sa = a ? a.text.trim() : '', sb = b ? b.text.trim() : '';
        const na = parseFloat(sa.replace(/[%,，\s]/g, '')), nb = parseFloat(sb.replace(/[%,，\s]/g, ''));
        if (isFinite(na) && isFinite(nb) && na !== nb) return (na - nb) * sortDir;
        return (sa < sb ? -1 : sa > sb ? 1 : 0) * sortDir;
      };
      const sortBy = (ci) => {
        if (ci === sortCol) sortDir = -sortDir;
        else { sortCol = ci; sortDir = 1; }
        order.sort((a, b) => cmpCell(rows[a][ci], rows[b][ci]));
        const ths = t.querySelectorAll ? t.querySelectorAll('.oui-th-sort') : [];
        for (let i = 0; i < ths.length; i++) {
          const on = Number(ths[i].getAttribute('data-oui-col')) === ci;
          const mark = ths[i].querySelector ? ths[i].querySelector('.oui-sort-c') : null;
          if (mark) mark.textContent = on ? (sortDir > 0 ? '↑' : '↓') : '';
        }
        repaint();
      };
      /* rowAction：整行可点，{1}..{n} 换成这一行各列文字（点「概念混淆 60 47%」那行
         就能直接跳到对应题型，不用再让用户去别的页面翻）。 */
      const rAct = textOf(p.rowAction);
      const paint = (from, to) => {
        tb.textContent = '';
        for (let i = from; i < to && i < order.length; i++) {
          const row = rows[order[i]];
          const tr = document.createElement('tr');
          row.forEach((cell, ci) => {
            const td = makeText('td', (num[ci] ? 'num ' : '') + (cell.tone || ''), cell.text);
            tr.append(td);
          });
          if (rAct) {
            tr.className = (tr.className ? tr.className + ' ' : '') + 'oui-row-tap';
            const map = {};
            row.forEach((cell, ci) => { map[String(ci + 1)] = cell.text; });
            const a = actOf(rAct, map);
            tr.setAttribute('data-oui-act', a);
            bindTap(tr, (e) => {
              e.stopPropagation();
              ctx.onAction(a, tr, nearestForm(tr));
            });
          }
          tb.append(tr);
        }
      };
      /* 列多或某列文字长时，表格的固有宽度会超过卡片：包一层横向滚动容器，
         多出来的列可以滑出来看，而不是被卡片裁掉（360dp 上很常见）。 */
      const wrap = make('div', 'oui-tablewrap');
      wrap.append(t);
      const pageSize = Math.max(0, Number(p.pageSize) || 0);
      if (pageSize && rows.length > pageSize) {
        /* 分页：题库几百行时整表铺开会把卡片撑成几屏，模型也没法只列前几行 */
        const pages = Math.ceil(rows.length / pageSize);
        let page = 0;
        const pager = make('div', 'oui-pager');
        const prev = make('button', 'oui-pager-b');
        const next = make('button', 'oui-pager-b');
        const info = makeText('span', 'oui-pager-t', '');
        prev.type = next.type = 'button';
        prev.textContent = '‹';
        next.textContent = '›';
        const show = (n) => {
          page = Math.max(0, Math.min(pages - 1, n));
          paint(page * pageSize, Math.min(rows.length, (page + 1) * pageSize));
          info.textContent = (page + 1) + ' / ' + pages + '（' + rows.length + ' 行）';
          prev.disabled = page === 0;
          next.disabled = page === pages - 1;
        };
        repaint = () => show(page);
        bindTap(prev, (e) => { e.stopPropagation(); show(page - 1); });
        bindTap(next, (e) => { e.stopPropagation(); show(page + 1); });
        pager.append(prev, info, next);
        show(0);
        t.append(tb);
        const box = make('div', 'oui-tablebox');
        box.append(wrap, pager);
        return box;
      }
      paint(0, rows.length);
      repaint = () => paint(0, rows.length);
      t.append(tb);
      /* 三行以内直接摊开：折叠是为了省版面，短表折起来只是多一次点击。
         长表仍折成一行「表格 · N 列 M 行」，点开才占版面。 */
      if (rows.length <= 3) return wrap;
      const nCol = width;
      const fold = make('details', 'oui-fold');
      const sum = make('summary');
      sum.textContent = '表格 · ' + nCol + ' 列 ' + rows.length + ' 行';
      fold.append(sum, wrap);
      /* 长表默认折叠，wrap 在 display:none 下 clientWidth 为 0，mount 时的
         ouiMarkScrollX 量不到宽度、不会给「可左右滑」提示；展开后也没人再量。
         这里在展开时补一次，否则列多的长表一打开就是一排被裁掉的列、还没有提示。 */
      fold.addEventListener('toggle', () => {
        if (fold.open) { try { window.ouiMarkScrollX(fold); } catch (e) { } }
      });
      return fold;
    }
    case 'Form': {
      const f = make('div', 'oui-form');
      f.dataset.form = textOf(p.name);
      const body = make('div', 'oui-stack');
      body.append(kids(p.children));
      f.append(body);
      if (p.submitLabel) {
        const b = make('button', 'oui-btn primary');
        b.type = 'button';
        b.textContent = textOf(p.submitLabel);
        bindTap(b, (e) => {
          e.stopPropagation();
          ctx.onAction(textOf(p.action) || `submit:${textOf(p.name)}`, b, f);
        });
        f.append(b);
      }
      return f;
    }
    case 'Code': {
      /* 代码块：以前只能塞进 Text，缩进和等宽全丢，讲报文/代码时只能靠符号硬凑 */
      const d = make('div', 'oui-code');
      const head = make('div', 'oui-code-h');
      head.append(makeText('span', 'oui-code-l', textOf(p.lang || p.title || 'code')));
      const btn = make('button', 'oui-code-c');
      btn.type = 'button';
      btn.textContent = '复制';
      bindTap(btn, (e) => { e.stopPropagation(); ouiCopy(textOf(p.text)); });
      head.append(btn);
      const pre = make('pre', 'oui-code-b');
      const code = make('code');
      code.textContent = textOf(p.text);
      pre.append(code);
      d.append(head, pre);
      return d;
    }
    case 'Flow': {
      /* 纵向流程链：时序、依赖这类「谁先谁后」以前只能画 ASCII 箭头 */
      const d = make('div', 'oui-flow' + (p.kind === 'check' ? ' check' : ''));
      listOf(p.items).forEach((it, i) => {
        const row = make('div', 'oui-flow-i');
        const dot = make('i', 'oui-flow-n');
        dot.textContent = p.kind === 'check' ? '✓' : String(i + 1);
        row.append(dot, makeText('div', 'oui-flow-t', it));
        d.append(row);
      });
      return d;
    }
    case 'Grid': {
      const n = Math.max(2, Math.min(4, Number(p.cols) || 2));
      const d = make('div', 'oui-grid');
      d.style.setProperty('--oui-grid-n', String(n));
      d.append(kids(p.children));
      return d;
    }
    case 'Details': {
      const d = make('details', 'oui-details');
      if (p.open) d.open = true;
      d.append(makeText('summary', 'oui-details-s', p.summary));
      const body = make('div', 'oui-stack');
      body.append(kids(p.children));
      d.append(body);
      return d;
    }
    case 'Slider': {
      const wrap = make('label', 'oui-field');
      const name = textOf(p.name);
      const min = Number(p.min) || 0;
      const max = Number(p.max) || 100;
      const want = hasKey(ctx.values, name) ? Number(ctx.values[name]) : (Number(p.value) || min);
      const head = make('span', 'oui-label');
      head.append(document.createTextNode(textOf(p.label || p.name) + ' '));
      const out = makeText('b', 'oui-slider-v', '');
      head.append(out);
      const r = make('input', 'oui-slider');
      r.type = 'range';
      r.min = String(min);
      r.max = String(max);
      r.step = String(Number(p.step) || 1);
      r.value = String(want);
      /* range 会把初值吸附到 min+n*step 网格上（写 20、min=1 step=5 → 实际 21），
         所以显示和回写都读 r.value，不读 want：否则标签数字和提交值对不上 */
      const cur = Number(r.value);
      out.textContent = String(cur);
      r.name = name;
      r.dataset.fname = name;
      r.dataset.flabel = textOf(p.label || p.name);
      r.addEventListener('input', () => {
        out.textContent = r.value;
        if (ctx.values) ctx.values[name] = r.value;
        if (ctx.onChange) ctx.onChange(false);
      });
      wrap.append(head, r);
      return wrap;
    }
    default:
      return null;
  }
}

function makeText(tag, cls, text) {
  const n = make(tag, cls);
  n.textContent = textOf(text);
  return n;
}

function nearestForm(node) {
  let n = node;
  while (n) {
    /* 用 'form' in dataset 判定：空名字表单的 dataset.form 是 ""（falsy），
       直接 if (n.dataset.form) 会跳过它，导致无名表单里自定义按钮提交拿不到值 */
    if (n.dataset && ('form' in n.dataset)) return n;
    n = n.parentElement;
  }
  return null;
}

function readForm(form) {
  if (!form) return [];
  const out = [];
  for (const f of form.querySelectorAll('[data-fname]')) {
    const name = f.dataset.flabel || f.dataset.fname;
    const v = f.type === 'checkbox' ? (f.checked ? '是' : '否') : f.value;
    if (name && String(v).trim()) out.push([name, String(v).trim()]);
  }
  return out;
}

/* 横向溢出提示：宽表格 / 长公式在 360dp 上一定超宽，容器自己会横滑，
   但用户看不出来还能滑——末列被从中间切断时，那画面和「文字超出边框」一模一样。
   必须在节点进文档之后调用（布局宽度这时候才有值）。 */
const OUI_SX_SEL = '.oui-tablewrap, .md-table, .oui-math.disp';
function ouiMarkScrollX(root) {
  if (!root || !root.querySelectorAll) return 0;
  let n = 0;
  for (const el of Array.prototype.slice.call(root.querySelectorAll(OUI_SX_SEL))) {
    const over = el.scrollWidth - el.clientWidth;
    const tip = el.nextElementSibling;
    if (over > 2) {
      let t = tip && String(tip.className) === 'oui-sx-tip' ? tip : null;
      if (!t) {
        t = make('div', 'oui-sx-tip');
        el.parentNode.insertBefore(t, el.nextSibling);
        el.addEventListener('scroll', () => {
          const cur = el.nextElementSibling;
          if (cur && String(cur.className).indexOf('oui-sx-tip') >= 0) {
            cur.className = el.scrollLeft + el.clientWidth >= el.scrollWidth - 2 ? 'oui-sx-tip gone' : 'oui-sx-tip';
          }
        }, { passive: true });
      }
      t.textContent = String(el.className).indexOf('oui-math') >= 0 ? '← 公式超宽，可左右滑 →' : '← 这一栏比屏幕宽，可左右滑 →';
      t.className = 'oui-sx-tip';
      n++;
    } else if (tip && String(tip.className).indexOf('oui-sx-tip') >= 0) {
      tip.remove();
    }
  }
  return n;
}

function renderOpenUI(source, opts) {
  if (!source || !window.OpenUI) return null;
  let res = null;
  try {
    res = window.OpenUI.parse(source);
  } catch (e) {
    return null;
  }
  if (!res) return null;
  const errors = (res.meta && res.meta.errors) || [];
  /* 解析彻底失败时（根组件名写错、必填字段缺失、根节点类型不符），
     root 建不起来但 meta.errors 有内容；以前这里直接 return null，整张卡
     凭空消失、连「格式问题」提示都没有。有错误就照常出提示卡，
     根节点缺失只当「没内容」，不要连错误一起丢掉。 */
  if (!res.root && !errors.length) return null;
  const box = make('div', 'oui');
  /* 每张卡一个 gid：radio 的原生 name 只在同 name 的 input 之间互斥，
     不带 gid 的话两条消息里同名字段的选项会互相抢选中态。 */
  ouiGidSeq += 1;
  const node = renderNode(res.root, {
    gid: String(ouiGidSeq),
    onAction: (action, node, form) => opts && opts.onAction && opts.onAction(action, node, form),
    onPick: (title, options, current, done) => opts && opts.onPick && opts.onPick(title, options, current, done),
    values: (opts && opts.values) || {},
    onChange: (now) => opts && opts.onChange && opts.onChange(now),
  });
  if (node) box.append(node);
  /* 官方解析器会把不合法的 prop/节点直接丢掉，只剩一个空壳。
     把 meta.errors 亮出来，别让用户对着一张空白卡猜。 */
  if (errors.length) {
    const warn = make('div', 'oui-warn');
    warn.textContent = node
      ? ('卡片有 ' + errors.length + ' 处格式问题，已跳过无法识别的部分')
      : ('卡片解析失败（' + errors.length + ' 处格式问题），请让大肥鱼重新生成一次');
    box.append(warn);
    box.setAttribute('data-oui-errors', String(errors.length));
    try { if (window.console && console.warn) console.warn('[OpenUI]', errors); } catch (e) { }
    if (opts && opts.onError) { try { opts.onError(errors); } catch (e) { } }
  }
  return node || errors.length ? box : null;
}

// —— 非 module 版的出口（GIme 以 classic script 从 file:///android_asset/ 加载，不能用 import/export）——
window.renderOpenUI = renderOpenUI;
window.readForm = readForm;
window.ouiMarkScrollX = ouiMarkScrollX;
