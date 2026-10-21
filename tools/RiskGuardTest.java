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

        // ── 缓存键 ──
        String k1 = RiskGuard.cacheKey("同一段 prompt");
        String k2 = RiskGuard.cacheKey("同一段 prompt");
        String k3 = RiskGuard.cacheKey("另一段 prompt");
        ok("同样的 prompt 键相同", k1.equals(k2), k1 + " vs " + k2);
        ok("不同的 prompt 键不同", !k1.equals(k3), k1 + " vs " + k3);
        eq("键长 16", 16, k1.length());
        eq("空 prompt 键为空", "", RiskGuard.cacheKey(""));
        eq("null prompt 键为空", "", RiskGuard.cacheKey(null));

        System.out.println("\n" + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
