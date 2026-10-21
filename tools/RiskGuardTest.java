package com.fan.shuatiku;

/**
 * RiskGuard 的桌面回归：熔断/恢复、跨天清零、缓存淘汰、风险判定、缓存键。
 *
 * <p>这个类是防封的最后一道闸，逻辑错了会直接表现为「正常用也被拦住」
 * 或者「该拦的时候没拦」，两种都很糟，所以逐条钉住。
 */
public class RiskGuardTest {

    private static int pass, fail;

    private static void eq(String name, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + "  期望=" + want + " 实际=" + got);
        }
    }

    private static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fail++;
        System.out.println("  [FAIL] " + name + "  " + detail);
    }

    public static void main(String[] args) {
        System.out.println("=== RiskGuard（熔断 / 预算 / 缓存）===");

        // ── 熔断 ──
        RiskGuard g = new RiskGuard();
        long t0 = 1_000_000L;
        ok("初始未熔断", !g.isFrozen(t0), "");
        g.onRisk(t0);                                  // 第 1 次
        ok("一次风险不熔断", !g.isFrozen(t0), "consecutive=" + g.consecutive());
        g.onRisk(t0 + 1000);                           // 第 2 次 → 熔断
        ok("连续两次风险就熔断", g.isFrozen(t0 + 1000), "consecutive=" + g.consecutive());
        ok("熔断时长 10 分钟",
           g.frozenRemainingMs(t0 + 1000) == RiskGuard.COOLDOWN_MS,
           String.valueOf(g.frozenRemainingMs(t0 + 1000)));
        ok("熔断期内仍然拦",
           g.isFrozen(t0 + 1000 + RiskGuard.COOLDOWN_MS - 1), "");
        ok("到点自动解除",
           !g.isFrozen(t0 + 1000 + RiskGuard.COOLDOWN_MS), "");

        // 成功一次要清零连续计数
        RiskGuard g2 = new RiskGuard();
        g2.onRisk(t0);
        g2.onSuccess();
        g2.onRisk(t0 + 1);
        ok("成功一次会清零计数（不该熔断）", !g2.isFrozen(t0 + 1),
           "consecutive=" + g2.consecutive());

        // 手动解除
        RiskGuard g3 = new RiskGuard();
        g3.onRisk(t0); g3.onRisk(t0);
        ok("（前置）已熔断", g3.isFrozen(t0), "");
        g3.resetFreeze();
        ok("手动解除后立刻可用", !g3.isFrozen(t0), "");
        ok("解除后计数也归零", g3.consecutive() == 0, "");

        // ── 每日预算 ──
        RiskGuard b = new RiskGuard();
        eq("新的一天从 0 开始", 0, b.touchDay("2026-10-21"));
        ok("有预算", b.budgetLeft("2026-10-21"), "");
        for (int i = 0; i < RiskGuard.DAILY_LIMIT; i++) b.onUsed("2026-10-21");
        eq("用满后计数等于上限", RiskGuard.DAILY_LIMIT, b.touchDay("2026-10-21"));
        ok("用满后没有预算", !b.budgetLeft("2026-10-21"), "");
        eq("剩余次数为 0", 0, b.remaining("2026-10-21"));
        eq("换一天自动清零", 0, b.touchDay("2026-10-22"));
        ok("换天后又有预算", b.budgetLeft("2026-10-22"), "");

        // 跨重启恢复：同一天要接着算，不能重置
        RiskGuard r = new RiskGuard();
        r.restore("2026-10-21", 55);
        eq("恢复后同日接着算", 55, r.touchDay("2026-10-21"));
        eq("恢复后剩余正确", RiskGuard.DAILY_LIMIT - 55, r.remaining("2026-10-21"));
        ok("恢复后仍可用（未满）", r.budgetLeft("2026-10-21"), "");
        eq("恢复但换了一天则清零", 0, r.touchDay("2026-10-22"));
        RiskGuard r2 = new RiskGuard();
        r2.restore("", 0);
        eq("恢复空日期不影响", 0, r2.touchDay("2026-10-21"));

        // ── 答案缓存 ──
        RiskGuard c = new RiskGuard();
        eq("初始缓存空", 0, c.cacheSize());
        c.cachePut("k1", "v1");
        eq("取回缓存", "v1", c.cacheGet("k1"));
        eq("不存在的键返回 null", null, c.cacheGet("nope"));
        c.cacheClear();
        c.cachePut("", "x");
        eq("空键不写", 0, c.cacheSize());
        c.cacheClear();
        eq("清空后为 0", 0, c.cacheSize());
        // 超过上限要按 LRU 淘汰最旧的
        for (int i = 0; i < RiskGuard.CACHE_MAX + 5; i++) c.cachePut("k" + i, "v" + i);
        ok("缓存不超过上限", c.cacheSize() <= RiskGuard.CACHE_MAX, "size=" + c.cacheSize());
        eq("最旧的已被淘汰", null, c.cacheGet("k0"));
        ok("最新的还在", c.cacheGet("k" + (RiskGuard.CACHE_MAX + 4)) != null, "");

        // ── 快照与恢复（落盘用）：跨重启还能命中 ──
        RiskGuard p = new RiskGuard();
        p.cachePut("a", "答案A");
        p.cachePut("b", "答案B");
        java.util.Map<String, String> snap = p.snapshot();
        eq("快照条数", 2, snap.size());
        // 改一份快照不该影响原对象
        snap.put("c", "答案C");
        eq("快照是拷贝，不共享", null, p.cacheGet("c"));

        RiskGuard q = new RiskGuard();
        q.restoreCache(p.snapshot());
        eq("恢复后可命中", "答案A", q.cacheGet("a"));
        eq("恢复条数一致", 2, q.cacheSize());

        // 恢复的顺序就是 LRU 顺序：超上限时淘汰最早恢复的那些
        RiskGuard rg = new RiskGuard();
        java.util.Map<String, String> many = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < RiskGuard.CACHE_MAX + 3; i++) many.put("r" + i, "v" + i);
        rg.restoreCache(many);
        ok("恢复也守上限", rg.cacheSize() <= RiskGuard.CACHE_MAX, "size=" + rg.cacheSize());
        eq("恢复时最旧的被淘汰", null, rg.cacheGet("r0"));
        ok("恢复时最新的还在", rg.cacheGet("r" + (RiskGuard.CACHE_MAX + 2)) != null, "");

        // null / 空值要安全（磁盘上的东西可能坏）
        RiskGuard rg2 = new RiskGuard();
        rg2.restoreCache(null);
        eq("恢复 null 不炸", 0, rg2.cacheSize());
        java.util.Map<String, String> dirty = new java.util.LinkedHashMap<String, String>();
        dirty.put(null, "x");
        dirty.put("ok", null);
        dirty.put("", "y");
        dirty.put("good", "z");
        rg2.restoreCache(dirty);
        eq("脏快照只留合法项", 1, rg2.cacheSize());

        // ── 风险判定 ──
        ok("40300 算风险", RiskGuard.isRisk(40300, ""), "");
        ok("429 算风险", RiskGuard.isRisk(429, ""), "");
        ok("「频繁」算风险", RiskGuard.isRisk(0, "请求过于频繁，请稍后再试"), "");
        ok("too many 算风险", RiskGuard.isRisk(0, "Too many requests"), "");
        ok("rate limit 算风险", RiskGuard.isRisk(0, "rate limit exceeded"), "");
        ok("「配额」算风险", RiskGuard.isRisk(0, "今日配额已用完"), "");
        ok("40301 不算风险（一次性挑战，偶发）", !RiskGuard.isRisk(40301, "INVALID_POW_RESPONSE"), "");
        ok("40003 不算风险（要重新登录）", !RiskGuard.isRisk(40003, "INVALID_TOKEN"), "");
        ok("普通网络错误不算风险", !RiskGuard.isRisk(-1, "connect failed"), "");
        ok("空消息不算风险", !RiskGuard.isRisk(0, null), "");
        /* 拦截页（上游回 HTML / 人机校验）是最明确的「别问了」，必须算风险 */
        ok("拦截页算风险", RiskGuard.isRisk(RiskGuard.BLOCKED_CODE, ""), "");
        ok("「请完成安全验证」算风险", RiskGuard.isRisk(0, "请完成安全验证后重试"), "");
        ok("「人机校验」算风险", RiskGuard.isRisk(0, "触发人机校验"), "");
        ok("captcha 算风险", RiskGuard.isRisk(0, "Captcha required"), "");
        ok("异常流量算风险", RiskGuard.isRisk(0, "检测到异常流量"), "");
        ok("access denied 算风险", RiskGuard.isRisk(0, "Access Denied"), "");

        // ── 缓存键 ──
        String k1 = RiskGuard.cacheKey("同一段 prompt");
        String k2 = RiskGuard.cacheKey("同一段 prompt");
        String k3 = RiskGuard.cacheKey("另一段 prompt");
        ok("同样的 prompt 键相同", k1.equals(k2), k1 + " vs " + k2);
        ok("不同的 prompt 键不同", !k1.equals(k3), k1 + " vs " + k3);
        eq("键长 16", 16, k1.length());
        eq("空 prompt 键为空", "", RiskGuard.cacheKey(""));
        eq("null prompt 键为空", "", RiskGuard.cacheKey(null));

        // ── 语义键：题目 + 提问，不含历史 ──
        // 这是「提高缓存命中率」的核心：同一道题同一问法，历史变了也要命中。
        String fp = "nb1-0\u0001TCP三次握手\u0001A=可以携带数据\u0002B=SYN必须携带数据\u0002A\u0002";
        String key1 = RiskGuard.cacheKey(fp, "这题为什么选A？");
        String key2 = RiskGuard.cacheKey(fp, "这题为什么选A？");
        eq("同题同问 → 同键", key1, key2);

        // 关键：把历史塞进去不影响键（老实现正是被历史打断命中）
        String keyWithHistory = RiskGuard.cacheKey(fp + "history-not-in-key", "这题为什么选A？");
        ok("题目指纹变了键才变（历史不进键）",
           !key1.equals(keyWithHistory), key1 + " vs " + keyWithHistory);
        eq("同题同问、历史不同 → 仍然同键",
           RiskGuard.cacheKey(fp, "这题为什么选A？"), key1);

        // 归一化：空白与首尾标点不影响命中
        eq("带问号与不带问号同键",
           RiskGuard.cacheKey(fp, "这题为什么选A"), RiskGuard.cacheKey(fp, "这题为什么选A？"));
        eq("带空格同键",
           RiskGuard.cacheKey(fp, "这题为什么 选A"), RiskGuard.cacheKey(fp, "这题为什么选A"));
        eq("大小学同键",
           RiskGuard.cacheKey(fp, "Why A?"), RiskGuard.cacheKey(fp, "why a"));

        // 不同题目 / 不同问法不能混
        ok("不同题目 → 不同键",
           !RiskGuard.cacheKey(fp, "怎么解").equals(RiskGuard.cacheKey("nb1-1\u0001另一题", "怎么解")), "");
        ok("不同问法 → 不同键",
           !RiskGuard.cacheKey(fp, "怎么解").equals(RiskGuard.cacheKey(fp, "为什么不选B")), "");

        // 没有题目上下文时（闲聊）只用提问当键
        String chit1 = RiskGuard.cacheKey("", "你是谁");
        ok("无题目上下文也能建键", chit1.length() == 16, chit1);
        eq("无题目上下文的同一问 → 同键", chit1, RiskGuard.cacheKey("", "你是谁？"));
        eq("题目与提问都空 → 空键", "", RiskGuard.cacheKey("", ""));
        eq("null 安全", "", RiskGuard.cacheKey(null, null));

        // normalizeAsk 单独验证
        eq("归一去掉首尾问号", "这题怎么解", RiskGuard.normalizeAsk("这题怎么解？"));
        eq("归一去掉中间空白", "abc", RiskGuard.normalizeAsk(" a b c "));
        eq("归一折叠大小写", "hello", RiskGuard.normalizeAsk("HeLLo"));
        eq("归一保留中间的标点", "a,b", RiskGuard.normalizeAsk("a,b"));
        eq("只有标点 → 空", "", RiskGuard.normalizeAsk("？？"));
        eq("null → 空", "", RiskGuard.normalizeAsk(null));

        // ── 递增惩罚：同一天被警告得越多，停得越久 ──
        RiskGuard esc = new RiskGuard();
        long t1 = 5_000_000L;
        esc.onRisk(t1); esc.onRisk(t1);                       // 第 1 次 → 10 分钟
        eq("第 1 次熔断 10 分钟", RiskGuard.COOLDOWN_MS, RiskGuard.cooldownFor(1));
        eq("第 2 次熔断 20 分钟", 2 * RiskGuard.COOLDOWN_MS, RiskGuard.cooldownFor(2));
        eq("第 3 次熔断 40 分钟", 4 * RiskGuard.COOLDOWN_MS, RiskGuard.cooldownFor(3));
        eq("递增有上限", RiskGuard.MAX_COOLDOWN_MS, RiskGuard.cooldownFor(20));
        ok("第 1 次熔断用了 10 分钟", esc.frozenRemainingMs(t1) == 10 * 60 * 1000L,
           String.valueOf(esc.frozenRemainingMs(t1)));
        eq("第 1 次记了一次警告", 1, esc.strikes());
        /* 熔断期内又来一次风险：已经是第 2 次，停 20 分钟 */
        long t2 = t1 + 10 * 60 * 1000L + 1;
        esc.onRisk(t2); esc.onRisk(t2);
        eq("第 2 次记了两次警告", 2, esc.strikes());
        ok("第 2 次停更久（20 分钟）", esc.frozenRemainingMs(t2) == 20 * 60 * 1000L,
           String.valueOf(esc.frozenRemainingMs(t2)));

        // 手动解除只解冻，不清「被警告几次」的账
        esc.resetFreeze();
        ok("手动解除立刻可用", !esc.isFrozen(t2), "");
        eq("手动解除不清警告次数", 2, esc.strikes());

        // 安静一整天之后才降级
        RiskGuard quiet = new RiskGuard();
        quiet.onRisk(t1); quiet.onRisk(t1);
        eq("先记一次警告", 1, quiet.strikes());
        ok("刚熔断完不算安静", !quiet.onSuccess(t1 + 1000), "");
        eq("警告次数保留", 1, quiet.strikes());
        ok("安静超过一天后降级", quiet.onSuccess(t1 + RiskGuard.STRIKE_FORGET_MS + 1), "");
        eq("降级后警告清零", 0, quiet.strikes());

        // 跨重启恢复熔断：重启一次不能把惩罚清空
        RiskGuard rf = new RiskGuard();
        rf.onRisk(t1); rf.onRisk(t1);
        long until = rf.frozenUntil();
        RiskGuard rf2 = new RiskGuard();
        rf2.restoreFreeze(until, rf.strikes(), rf.lastRiskAt());
        ok("恢复后仍在熔断", rf2.isFrozen(t1 + 1000), "");
        eq("恢复后剩余时间一致", rf.frozenRemainingMs(t1 + 1000), rf2.frozenRemainingMs(t1 + 1000));
        eq("恢复后警告次数一致", rf.strikes(), rf2.strikes());
        RiskGuard rf3 = new RiskGuard();
        rf3.restoreFreeze(0, 0, 0);
        ok("空状态恢复=没熔断", !rf3.isFrozen(t1), "");

        // ── 命中统计：让「缓存有没有用」可被观察 ──
        RiskGuard h = new RiskGuard();
        eq("初始没命中过", 0L, h.hitCount());
        h.cachePut("hk", "hv");
        h.cacheGet("hk");
        h.cacheGet("hk");
        h.cacheGet("miss");
        eq("命中两次只记两次", 2L, h.hitCount());
        h.restoreHits(40);
        eq("命中数按落盘值接上", 40L, h.hitCount());
        h.restoreHits(-5);
        eq("负数当 0", 0L, h.hitCount());

        // 单条超大答案不写：否则一条就能把几十条挤掉
        RiskGuard big = new RiskGuard();
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i <= RiskGuard.CACHE_MAX_CHARS; i++) huge.append('x');
        big.cachePut("big", huge.toString());
        eq("超大答案不进缓存", 0, big.cacheSize());
        big.cachePut("ok", "正常长度");
        eq("正常长度照进", 1, big.cacheSize());

        // ── 缓存键带作答状态：换了选项/判了分，就不是同一个问题 ──
        String k0 = RiskGuard.cacheKey(fp, "这题为什么选A？", "");
        eq("作答状态为空时与两参版一致", RiskGuard.cacheKey(fp, "这题为什么选A？"), k0);
        ok("选了 B 与选了 C 键不同",
           !RiskGuard.cacheKey(fp, "这题为什么选A？", "B|0|practice")
                .equals(RiskGuard.cacheKey(fp, "这题为什么选A？", "C|0|practice")), "");
        ok("判分前后键不同",
           !RiskGuard.cacheKey(fp, "这题为什么选A？", "B|0|practice")
                .equals(RiskGuard.cacheKey(fp, "这题为什么选A？", "B|1|practice")), "");
        ok("键仍然是 16 位", k0.length() == 16, k0);

        // 归一再扩一档：全角字母数字、零宽字符、句尾语气词
        eq("全角字母归一", "选a", RiskGuard.normalizeAsk("选Ａ"));
        eq("全角数字归一", "abc123", RiskGuard.normalizeAsk("ＡＢＣ１２３"));
        eq("句尾语气词归一", "这题怎么解", RiskGuard.normalizeAsk("这题怎么解呢"));
        eq("连着的语气词一并归一", "这题怎么解", RiskGuard.normalizeAsk("这题怎么解呢呀"));
        eq("语气词后面带问号也归一", "这题怎么解", RiskGuard.normalizeAsk("这题怎么解吧？"));
        eq("零宽字符被去掉", "abc", RiskGuard.normalizeAsk("a\u200Bb\uFEFFc"));
        ok("实词差异不能被归一掉",
           !RiskGuard.normalizeAsk("为什么选B").equals(RiskGuard.normalizeAsk("为什么不选B")), "");

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
