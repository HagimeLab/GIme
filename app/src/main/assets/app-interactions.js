/* ============================================================================
 * app-interactions.js — 高级交互升级层（按 mobile-advanced-interaction skill 落地）
 * 全部为「增量」组件，不改动原有判分/路由/存储逻辑。
 * 已实现：
 *   #1  Done Burst      交卷成绩粒子爆开（时序反馈，不跟手）
 *   #6  Dial            字号旋钮（跟角度 → 衰减 → 吸附）
 *   #7  Dock Snap       大肥鱼悬浮球跟手拖动、松手吸边
 *   #8  Tip Edge Flip   气泡避边（悬浮球长按提示）
 *   #10 Swipe Confirm   清除进度 / 删除方案的滑动确认（防误触）
 * ---------------------------------------------------------------------------
 * 说明：本文件自行实现所需原语（clamp/lerp/mapRange/tween/spring/velocity/
 * makeGesture），不依赖 core-mechanics 的 TS 版本，语义保持一致。
 * ==========================================================================*/
(function () {
  "use strict";

  /* ---------------- 数学 / 缓动 / 弹簧 ---------------- */
  function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function mapRange(v, inMin, inMax, outMin, outMax) {
    var d = (inMax - inMin) || 1;
    return lerp(outMin, outMax, clamp((v - inMin) / d, 0, 1));
  }
  function easeOutQuint(t) { return 1 - Math.pow(1 - t, 5); }
  function backOut(t, s) { s = s || 1.70158; var p = t - 1; return 1 + (s + 1) * p * p * p + s * p * p; }

  function tween(dur, ease, apply) {
    return new Promise(function (resolve) {
      var t0 = performance.now();
      function tick(now) {
        var t = clamp((now - t0) / dur, 0, 1);
        apply(ease(t));
        if (t < 1) requestAnimationFrame(tick); else resolve();
      }
      requestAnimationFrame(tick);
    });
  }

  function spring(cfg, from, to, apply) {
    var x = from, vel = 0, k = cfg.stiffness, c = cfg.damping, m = cfg.mass || 1;
    return new Promise(function (resolve) {
      var last = performance.now();
      function tick(now) {
        var dt = Math.min((now - last) / 1000, 0.033); last = now;
        var force = -k * (x - to) - c * vel;
        vel += force / m * dt;
        x += vel * dt;
        apply(x);
        if (Math.abs(x - to) < 0.05 && Math.abs(vel) < 0.05) { apply(to); resolve(); }
        else requestAnimationFrame(tick);
      }
      requestAnimationFrame(tick);
    });
  }

  function velocityEstimator(smoothing) {
    smoothing = smoothing || 0.35;
    var vx = 0, vy = 0;
    return function (dx, dy, dtMs) {
      var dt = Math.max(dtMs, 1) / 1000;
      vx = lerp(vx, dx / dt, smoothing);
      vy = lerp(vy, dy / dt, smoothing);
      return { vx: vx, vy: vy, speed: Math.hypot(vx, vy) };
    };
  }

  /* 统一指针手势：Pointer Events，按下/拖动/松手/取消；自动处理长按与 slop */
  function makeGesture(el, hooks) {
    hooks = hooks || {};
    var SLOP = 4, LONG = 400;
    var start = null, last = null, timer = null, longPressed = false, dragging = false;
    function pt(e) { return { x: e.clientX, y: e.clientY, t: performance.now() }; }
    function down(e) {
      if (e.button != null && e.button !== 0) return;
      try { el.setPointerCapture && el.setPointerCapture(e.pointerId); } catch (x) {}
      start = last = pt(e); longPressed = false; dragging = false;
      el.style.transition = "none";
      timer = setTimeout(function () {
        timer = null; longPressed = true;
        if (start && hooks.onLongPress) hooks.onLongPress(start);
      }, LONG);
      if (hooks.onPress) hooks.onPress(start);
    }
    function move(e) {
      if (!start || !last || longPressed) return;
      var cur = pt(e);
      if (!dragging && Math.hypot(cur.x - start.x, cur.y - start.y) > SLOP) {
        if (timer) { clearTimeout(timer); timer = null; }
        dragging = true;
        if (hooks.onDragStart) hooks.onDragStart(start);
      }
      if (dragging) {
        if (hooks.onDrag) hooks.onDrag(cur.x - last.x, cur.y - last.y, cur, last);
        last = cur;
      }
    }
    function up(e) {
      if (timer) { clearTimeout(timer); timer = null; }
      if (start && last && hooks.onRelease) hooks.onRelease(last, start, dragging);
      start = last = null; dragging = false;
    }
    function cancel() {
      if (timer) { clearTimeout(timer); timer = null; }
      start = last = null; dragging = false;
      if (hooks.onCancel) hooks.onCancel();
    }
    el.addEventListener("pointerdown", down);
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", cancel);
    return function destroy() {
      el.removeEventListener("pointerdown", down);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", cancel);
    };
  }

  function vib(p) { try { if (typeof set !== "undefined" && set.haptics === true && navigator.vibrate) navigator.vibrate(p); } catch (e) {} }

  /* ========================================================================
   * #10 Swipe Confirm — 滑动确认（防误触的破坏性操作）
   * ======================================================================*/
  function swipeConfirm(o) {
    o = o || {};
    var prevOverflow = document.body.style.overflow;
    var scrim = document.createElement("div");
    scrim.className = "sc-scrim";
    var panel = document.createElement("div");
    panel.className = "sc";
    panel.setAttribute("role", "dialog");
    panel.innerHTML =
      '<h2>' + (o.title || "确认操作") + '</h2>' +
      '<p>' + (o.desc || "") + '</p>' +
      '<div class="sc-tip" id="scTip">' + (o.hint || "按住滑块，向右滑到底确认") + '</div>' +
      '<div class="sc-track" id="scTrack">' +
        '<span class="sc-fill"></span>' +
        '<div class="sc-knob" id="scKnob"><svg class="ico" viewBox="0 0 24 24" style="width:20px;height:20px"><path d="M9 6l6 6-6 6"/></svg></div>' +
      '</div>' +
      '<button class="sc-cancel" type="button">取消</button>';
    document.body.appendChild(scrim);
    document.body.appendChild(panel);
    document.body.style.overflow = "hidden";
    requestAnimationFrame(function () { scrim.classList.add("on"); panel.classList.add("on"); });

    var track = panel.querySelector("#scTrack");
    var knob = panel.querySelector("#scKnob");
    var fill = panel.querySelector(".sc-fill");
    var hint = panel.querySelector("#scTip");
    var progress = 0, travel = 1, trackLeft = 0, committed = false;
    var getVel = velocityEstimator(0.35), speed = 0, destroyed = false;

    function render(v) {
      progress = v;
      knob.style.transform = "translate3d(" + (v * travel) + "px,0,0)";
      fill.style.transform = "scaleX(" + v + ")";
      panel.classList.toggle("ready", v > 0.02);
    }
    function teardown() {
      if (destroyed) return; destroyed = true;
      scrim.classList.remove("on"); panel.classList.remove("on");
      document.body.style.overflow = prevOverflow;
      setTimeout(function () {
        if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
        if (panel.parentNode) panel.parentNode.removeChild(panel);
      }, 220);
    }
    var destroyGesture = makeGesture(knob, {
      onDragStart: function () {
        var tr = track.getBoundingClientRect();
        trackLeft = tr.left;
        travel = Math.max(1, tr.width - knob.offsetWidth);
      },
      onDrag: function (dx, dy, cur, prev) {
        speed = getVel(dx, dy, cur.t - prev.t).speed;
        render(clamp((cur.x - trackLeft - knob.offsetWidth / 2) / travel, 0, 1));
      },
      onRelease: function (last, start, dragged) {
        var from = progress;
        if (!dragged) { spring({ stiffness: 260, damping: 18 }, from, 0, render); return; }
        if (from >= 0.85 || speed >= 600) {
          hint.textContent = o.okText || "已确认";
          spring({ stiffness: 260, damping: 18 }, from, 1, render).then(function () {
            if (committed) return; committed = true;
            vib([12]);
            setTimeout(function () {
              teardown();
              if (o.action) o.action();
            }, 140);
          });
        } else {
          spring({ stiffness: 260, damping: 18 }, from, 0, render);
        }
      },
      onCancel: function () { spring({ stiffness: 260, damping: 18 }, progress, 0, render); }
    });
    panel.querySelector(".sc-cancel").addEventListener("click", function () {
      destroyGesture(); teardown();
    });
    scrim.addEventListener("click", function () { destroyGesture(); teardown(); });
    return true;
  }

  /* ========================================================================
   * #1 Done Burst — 交卷成绩粒子爆开
   * ======================================================================*/
  function scoreBurst(el, good) {
    if (!el) return;
    var r = el.getBoundingClientRect();
    if (!r.width) return;
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var layer = document.createElement("div");
    layer.className = "gi-burst";
    var n = good ? 10 : 6, dots = [];
    for (var i = 0; i < n; i++) {
      var d = document.createElement("i");
      d.className = "gi-part" + (good ? " ok" : "");
      layer.appendChild(d); dots.push(d);
    }
    var ring = document.createElement("i");
    ring.className = "gi-ring";
    layer.appendChild(ring);
    document.body.appendChild(layer);
    // 首帧把粒子收到中心
    dots.forEach(function (d) {
      d.style.transform = "translate3d(" + cx + "px," + cy + "px,0) scale(.4)";
      d.style.opacity = "0";
    });
    ring.style.transform = "translate3d(" + cx + "px," + cy + "px,0) translate(-50%,-50%) scale(.2)";
    ring.style.opacity = "0";
    var R = Math.max(46, Math.min(88, r.width * 0.9));
    tween(good ? 620 : 460, function (t) { return t; }, function (p) {
      // 环：0→1 扩散淡出
      var rp = clamp(p * 1.6, 0, 1);
      ring.style.opacity = String(0.55 * (1 - rp));
      ring.style.transform = "translate3d(" + cx + "px," + cy + "px,0) translate(-50%,-50%) scale(" + lerp(0.2, 2.4, easeOutQuint(rp)) + ")";
      dots.forEach(function (d, i) {
        var a = (i / n) * Math.PI * 2;
        var pi = clamp((p - i * 0.02) / 0.85, 0, 1);
        var rad = lerp(0, R, backOut(pi));
        d.style.transform = "translate3d(" + (cx + Math.cos(a) * rad) + "px," + (cy + Math.sin(a) * rad) + "px,0) scale(" + lerp(0.4, 1, pi) + ")";
        d.style.opacity = String(Math.max(0, 1 - pi));
      });
    }).then(function () {
      if (layer.parentNode) layer.parentNode.removeChild(layer);
    });
    if (good) vib([18, 40, 18]);
  }

  /* ========================================================================
   * #6 Dial — 字号旋钮
   * ======================================================================*/
  var FONT_MIN = 90, FONT_MAX = 140, FONT_STEP = 10;
  var DIAL_A = -135, DIAL_B = 135; // 指针可用角度（度）

  function fontValToDeg(v) { return mapRange(v, FONT_MIN, FONT_MAX, DIAL_A, DIAL_B); }
  function fontDegToVal(deg) {
    var raw = mapRange(deg, DIAL_A, DIAL_B, FONT_MIN, FONT_MAX);
    return clamp(Math.round(raw / FONT_STEP) * FONT_STEP, FONT_MIN, FONT_MAX);
  }

  function openFontDial() {
    if (document.getElementById("fdPanel")) return;
    var scrim = document.createElement("div");
    scrim.className = "fd-scrim";
    var panel = document.createElement("div");
    panel.className = "fd";
    panel.id = "fdPanel";
    panel.setAttribute("role", "dialog");
    var ticks = "";
    for (var v = FONT_MIN; v <= FONT_MAX; v += FONT_STEP) {
      ticks += '<i class="fd-tick" style="transform:rotate(' + fontValToDeg(v) + 'deg)"></i>';
    }
    panel.innerHTML =
      '<h2>字号</h2>' +
      '<p>拖动旋钮调节，指针停在刻度上</p>' +
      '<div class="fd-dial" id="fdDial">' +
        '<div class="fd-ring"></div>' + ticks +
        '<div class="fd-pointer" id="fdPointer"></div>' +
        '<div class="fd-val"><b id="fdVal">' + (set.fscale || 100) + '</b><span>%</span></div>' +
      '</div>' +
      '<button class="fd-done" type="button">完成</button>';
    document.body.appendChild(scrim);
    document.body.appendChild(panel);
    var prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    requestAnimationFrame(function () { scrim.classList.add("on"); panel.classList.add("on"); });

    var dial = panel.querySelector("#fdDial");
    var pointer = panel.querySelector("#fdPointer");
    var valEl = panel.querySelector("#fdVal");
    var curDeg = fontValToDeg(set.fscale || 100), curVal = set.fscale || 100;
    function render(deg, val) {
      pointer.style.transform = "rotate(" + deg + "deg)";
      if (val != null) { valEl.textContent = String(val); curVal = val; }
    }
    render(curDeg, curVal);

    var live = set.fscale || 100;
    function applyLive(v) {
      live = v;
      try { set.fscale = v; if (typeof applyTheme === "function") applyTheme(); } catch (e) {}
    }

    var destroyGesture = makeGesture(dial, {
      onDragStart: function () { dial.classList.add("drag"); },
      onDrag: function (dx, dy, cur) {
        var r = dial.getBoundingClientRect();
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        // 0=正上、顺时针为正；下半圆(±180 附近)夹到端点，避免指针跳变
        var deg = Math.atan2(cur.x - cx, -(cur.y - cy)) * 180 / Math.PI;
        deg = clamp(deg, DIAL_A, DIAL_B);
        curDeg = deg;
        var vv = fontDegToVal(deg);
        render(deg, vv);
        applyLive(vv);
      },
      onRelease: function () {
        dial.classList.remove("drag");
        var target = fontDegToVal(curDeg);
        var tdeg = fontValToDeg(target);
        spring({ stiffness: 300, damping: 20 }, curDeg, tdeg, function (d) { render(d); }).then(function () {
          render(tdeg, target); applyLive(target);
        });
      },
      onCancel: function () {
        dial.classList.remove("drag");
        spring({ stiffness: 300, damping: 20 }, curDeg, fontValToDeg(curVal), function (d) { render(d); });
      }
    });

    function close() {
      destroyGesture();
      try { set.fscale = curVal; if (typeof save === "function") save(); if (typeof renderAll === "function") renderAll(); if (typeof moveTabPill === "function") moveTabPill(false); } catch (e) {}
      scrim.classList.remove("on"); panel.classList.remove("on");
      document.body.style.overflow = prevOverflow;
      setTimeout(function () {
        if (scrim.parentNode) scrim.parentNode.removeChild(scrim);
        if (panel.parentNode) panel.parentNode.removeChild(panel);
      }, 200);
    }
    panel.querySelector(".fd-done").addEventListener("click", close);
    scrim.addEventListener("click", close);
  }

  /* ========================================================================
   * #8 Tip Edge Flip — 气泡避边
   * ======================================================================*/
  var tipEl = null, tipTimer = null;
  function tipBubble(anchorRect, text, ms) {
    if (!tipEl) {
      tipEl = document.createElement("div");
      tipEl.className = "gi-tip";
      tipEl.innerHTML = '<span class="gi-tip-tx"></span><i class="gi-tip-ar"></i>';
      document.body.appendChild(tipEl);
    }
    var w = 150, h = 40;
    var spaces = {
      top: anchorRect.top, bottom: innerHeight - anchorRect.bottom,
      left: anchorRect.left, right: innerWidth - anchorRect.right
    };
    var need = { top: h, bottom: h, left: w, right: w };
    var order = Object.keys(spaces).sort(function (a, b) { return spaces[b] - spaces[a]; });
    var placement = null;
    for (var i = 0; i < order.length; i++) {
      var k = order[i];
      if ((k === "top" || k === "bottom") && need[k] <= spaces[k]) { placement = k; break; }
    }
    if (!placement) for (var j = 0; j < order.length; j++) { if (need[order[j]] <= spaces[order[j]]) { placement = order[j]; break; } }
    if (!placement) placement = order[0];
    var cx = anchorRect.left + anchorRect.width / 2;
    var cy = anchorRect.top + anchorRect.height / 2;
    var left, top;
    if (placement === "top" || placement === "bottom") {
      left = clamp(cx - w / 2, 8, innerWidth - w - 8);
      top = placement === "top" ? anchorRect.top - h - 10 : anchorRect.bottom + 10;
    } else {
      top = clamp(cy - h / 2, 8, innerHeight - h - 8);
      left = placement === "left" ? anchorRect.left - w - 10 : anchorRect.right + 10;
    }
    tipEl.querySelector(".gi-tip-tx").textContent = text;
    tipEl.style.left = left + "px";
    tipEl.style.top = top + "px";
    tipEl.setAttribute("data-place", placement);
    // 箭头位置
    var ar = tipEl.querySelector(".gi-tip-ar");
    if (placement === "top" || placement === "bottom") {
      ar.style.left = clamp(cx - left - 6, 8, w - 20) + "px";
      ar.style.top = "";
      ar.style.bottom = placement === "top" ? "-6px" : "";
      ar.style.top = placement === "bottom" ? "-6px" : ar.style.top;
      ar.style.borderLeft = "6px solid transparent"; ar.style.borderRight = "6px solid transparent";
      ar.style.borderTop = placement === "top" ? "7px solid var(--ink)" : "none";
      ar.style.borderBottom = placement === "bottom" ? "7px solid var(--ink)" : "none";
    } else {
      ar.style.top = clamp(cy - top - 6, 8, h - 20) + "px";
      ar.style.left = "";
      ar.style.borderTop = "6px solid transparent"; ar.style.borderBottom = "6px solid transparent";
      ar.style.borderRight = placement === "left" ? "7px solid var(--ink)" : "none";
      ar.style.borderLeft = placement === "right" ? "7px solid var(--ink)" : "none";
    }
    tipEl.classList.remove("on");
    void tipEl.offsetWidth;
    tipEl.classList.add("on");
    if (tipTimer) clearTimeout(tipTimer);
    if (ms !== 0) tipTimer = setTimeout(function () { tipEl.classList.remove("on"); }, ms || 2200);
  }

  /* ---------------- 暴露到全局 ---------------- */
  window.GI = {
    swipeConfirm: swipeConfirm,
    scoreBurst: scoreBurst,
    openFontDial: openFontDial,
    tipBubble: tipBubble,
    _primitives: { clamp: clamp, lerp: lerp, tween: tween, spring: spring, makeGesture: makeGesture }
  };
  window.swipeConfirm = swipeConfirm;
  window.openFontDial = openFontDial;

  function boot() {
    /* 大肥鱼悬浮球已按需求移除；这里只清理旧版本可能残留的节点 */
    try { var __b = document.getElementById("agBall"); if (__b) __b.remove(); } catch (e) {}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else setTimeout(boot, 0);
})();
