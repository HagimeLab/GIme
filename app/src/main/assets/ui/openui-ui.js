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
  node.addEventListener('touchstart', fire, { passive: false });
  node.addEventListener('mousedown', fire);
}
const listOf = (v) => (Array.isArray(v) ? v : []);
const hasKey = (o, k) => !!o && Object.prototype.hasOwnProperty.call(o, k);

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
      const d = make('div', 'oui-stack');
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
      const labels = listOf(p.labels), values = listOf(p.values).map(Number);
      if (!values.length) return d;
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
      if (p.hint) d.append(makeText('div', 'oui-metric-h', p.hint));
      return d;
    }
    case 'Progress': {
      const d = make('div', 'oui-progress');
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
    case 'Button': {
      const b = make('button', `oui-btn ${p.variant === 'secondary' ? 'secondary' : 'primary'}`);
      b.type = 'button';
      b.textContent = textOf(p.label);
      bindTap(b, (e) => {
        e.stopPropagation();
        ctx.onAction(textOf(p.action), b, nearestForm(b));
      });
      return b;
    }
    case 'Input': {
      const wrap = make('label', 'oui-field');
      if (p.label) wrap.append(makeText('span', 'oui-label', p.label));
      const name = textOf(p.name);
      const i = make('input', 'oui-input');
      i.type = p.kind === 'number' ? 'number' : 'text';
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
      const cols = listOf(p.columns);
      if (cols.length) {
        const thead = document.createElement('thead');
        const tr = document.createElement('tr');
        for (const c of cols) tr.append(makeText('th', null, c));
        thead.append(tr);
        t.append(thead);
      }
      const tb = document.createElement('tbody');
      for (const row of listOf(p.rows)) {
        const tr = document.createElement('tr');
        for (const cell of listOf(row)) tr.append(makeText('td', null, cell));
        tb.append(tr);
      }
      t.append(tb);
      return t;
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
