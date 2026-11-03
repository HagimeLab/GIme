// OpenUI Lang 渲染层：解析交给官方 @openuidev/lang-core（window.OpenUI），
// 这里只把元素树画成 App 自己的黑白组件，并把按钮动作交回 app.js。
const make = (tag, cls) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  return n;
};

const textOf = (v) => (v == null ? '' : String(v));

// 按下即触发：收键盘会让布局在按下与抬起之间上移，走 click 会点空
function bindTap(node, fn) {
  let lastFire = 0;
  let sx = 0, sy = 0, st = 0, lastTouch = 0;
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
     抬起时位移小且够快才算点」，判据与卡片本体的 bindOuiOpen 同一套。
     收键盘导致的布局上移不影响判断——比的是手指坐标，不是落点元素。 */
  node.addEventListener('touchstart', (e) => {
    lastTouch = Date.now();
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
    if (!p || dt > 600) return;
    if (Math.abs(p.clientX - sx) + Math.abs(p.clientY - sy) > 12) return;
    fire(e);
  });
  node.addEventListener('touchcancel', () => { st = 0; });
  node.addEventListener('mousedown', (e) => {
    /* 触屏已经走过一遍 touch*（touchend 之后浏览器还会补一串鼠标事件），这里只兜鼠标 */
    if (Date.now() - lastTouch < 700) return;
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
      d.append(kids(p.children));
      return d;
    }
    case 'Row': {
      const d = make('div', 'oui-row');
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
    case 'Title': {
      const lv = Math.max(1, Math.min(3, Number(p.level) || 2));
      return makeText('div', `oui-title l${lv}`, p.text);
    }
    case 'Text':
      return makeText('div', `oui-text${p.muted ? ' muted' : ''}`, p.text);
    case 'Badge':
      return makeText('span', 'oui-badge', p.text);
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
      /* stack 传的是二维（每行一个类别、每列一个系列）：对数组做 Number() 会变 NaN，
         所以先认出二维，单系列的 values 才走 map(Number)。 */
      const is2D = raw.length > 0 && Array.isArray(raw[0]);
      const values = is2D ? [] : raw.map(Number);
      if (!raw.length) return d;
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
            String(values[i]) + '（' + Math.round((vals[i] / total) * 100) + '%）'));
          lg.append(row);
        });
        const box = make('div', 'oui-pie-box');
        box.append(pie, lg);
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
        const names = listOf(p.names).map(textOf);
        /* 系列数取「最长一行」与 names 的较大值：模型偶尔少写一行数值或少写一个系列名，
           按 0 补 / 显示成「系列 N」总比「有一截颜色没有图例」强。 */
        const nS = rows.reduce((n, r) => Math.max(n, r.length), names.length);
        const totals = rows.map((r) => r.reduce((a, b) => a + b, 0));
        const maxT = Math.max.apply(null, totals.concat([1]));
        const bars = make('div', 'oui-bars');
        rows.forEach((r, i) => {
          const col = make('div', 'oui-bar');
          col.append(makeText('b', '', String(Math.round(totals[i] * 10) / 10)));
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
          col.append(stack, makeText('span', '', labels[i] || ''));
          bars.append(col);
        });
        d.append(bars);
        if (nS) {
          const lg = make('div', 'oui-legend');
          for (let i = 0; i < nS; i++) {
            const item = make('span');
            item.append(make('i', 'oui-pie-d' + ((i % 5) + 1)));
            item.append(document.createTextNode(names[i] || '系列 ' + (i + 1)));
            lg.append(item);
          }
          d.append(lg);
        }
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
          const fill = make('i', '');
          fill.style.height = Math.max(0, Math.min(100, (Math.max(0, v) / max) * 100)) + '%';
          col.append(makeText('b', '', String(v)), fill, makeText('span', '', labels[i] || ''));
          bars.append(col);
        });
        d.append(bars);
      }
      if (p.kind === 'line' && labels.length) {
        const x = make('div', 'oui-chart-x');
        for (const label of labels) x.append(makeText('span', '', label));
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
      const b = make('button', `oui-btn ${p.variant === 'secondary' ? 'secondary' : 'primary'}`);
      b.type = 'button';
      b.textContent = textOf(p.label);
      bindTap(b, (e) => {
        e.stopPropagation();
        const act = textOf(p.action);
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
      if (cols.length) {
        const thead = document.createElement('thead');
        const tr = document.createElement('tr');
        cols.forEach((c, ci) => tr.append(makeText('th', num[ci] ? 'num' : null, c)));
        thead.append(tr);
        t.append(thead);
      }
      const tb = make('tbody');
      const paint = (from, to) => {
        tb.textContent = '';
        for (let i = from; i < to; i++) {
          const tr = document.createElement('tr');
          rows[i].forEach((cell, ci) => {
            const td = makeText('td', (num[ci] ? 'num ' : '') + (cell.tone || ''), cell.text);
            tr.append(td);
          });
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
      t.append(tb);
      /* 三行以内直接摊开：折叠是为了省版面，短表折起来只是多一次点击。
         长表仍折成一行「表格 · N 列 M 行」，点开才占版面。 */
      if (rows.length <= 3) return wrap;
      const nCol = width;
      const fold = make('details', 'oui-fold');
      const sum = make('summary');
      sum.textContent = '表格 · ' + nCol + ' 列 ' + rows.length + ' 行';
      fold.append(sum, wrap);
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
      const cur = hasKey(ctx.values, name) ? Number(ctx.values[name]) : (Number(p.value) || min);
      const head = make('span', 'oui-label');
      head.append(document.createTextNode(textOf(p.label || p.name) + ' '));
      const out = makeText('b', 'oui-slider-v', String(cur));
      head.append(out);
      const r = make('input', 'oui-slider');
      r.type = 'range';
      r.min = String(min);
      r.max = String(max);
      r.step = String(Number(p.step) || 1);
      r.value = String(cur);
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
    if (n.dataset && n.dataset.form) return n;
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

function renderOpenUI(source, opts) {
  if (!source || !window.OpenUI) return null;
  let res = null;
  try {
    res = window.OpenUI.parse(source);
  } catch (e) {
    return null;
  }
  if (!res || !res.root) return null;
  const errors = (res.meta && res.meta.errors) || [];
  const box = make('div', 'oui');
  const node = renderNode(res.root, {
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
    warn.textContent = '卡片有 ' + errors.length + ' 处格式问题，已跳过无法识别的部分';
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
